package py.soulhoshi.parkassist

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.UUID
import kotlin.concurrent.thread

class BluetoothController(context: Context) {
    interface Listener {
        fun onConnecting(deviceName: String)
        fun onConnected(deviceName: String)
        fun onReconnecting(attempt: Int, delayMs: Long)
        fun onDisconnected(message: String)
        fun onLineReceived(line: String, command: String?)
        fun onCommandFailed(command: String, reason: String)
    }

    private data class QueuedCommand(
        val command: String,
        val sequence: Int,
        val isPoll: Boolean,
        var retriesLeft: Int = 1
    )

    private val appContext = context.applicationContext
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandQueue = ArrayDeque<QueuedCommand>()
    private var listener: Listener? = null
    private var socket: BluetoothSocket? = null
    private var lastDevice: BluetoothDevice? = null
    private var reconnectTask: Runnable? = null
    private var commandTimeoutTask: Runnable? = null
    private var pollTask: Runnable? = null
    private var inFlight: QueuedCommand? = null
    private var nextSequence = 1
    private var reconnectAttempt = 0
    private var userRequestedDisconnect = false
    @Volatile private var connected = false
    @Volatile private var generation = 0
    var connectedDeviceName: String? = null
        private set

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val RECONNECT_DELAYS = longArrayOf(1000, 2000, 5000, 10000, 15000)
        private const val COMMAND_TIMEOUT_MS = 1800L
        private const val POLL_INTERVAL_MS = 750L
        private val COALESCED_COMMANDS = setOf(
            "SISTEMA", "MODO", "DISTANCIA", "RANGO", "PERFIL", "BRILLO", "VELOCIDAD", "SONIDO"
        )
    }

    fun setListener(value: Listener?) {
        listener = value
        if (value == null) cancelPoll() else schedulePoll()
    }

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isBluetoothAvailable(): Boolean = adapter != null
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true
    fun isConnected(): Boolean = connected

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BluetoothDevice> {
        if (!hasPermission()) return emptyList()
        return adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        userRequestedDisconnect = false
        reconnectAttempt = 0
        lastDevice = device
        cancelReconnect()
        clearCommands()
        startConnection(device, announce = true)
    }

    @SuppressLint("MissingPermission")
    private fun startConnection(device: BluetoothDevice, announce: Boolean) {
        val connectionGeneration = ++generation
        connected = false
        closeSocket()
        val name = device.name ?: device.address
        connectedDeviceName = name
        if (announce) mainHandler.post { listener?.onConnecting(name) }

        thread(name = "hc06-connect") {
            try {
                val newSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                socket = newSocket
                newSocket.connect()
                if (generation != connectionGeneration || userRequestedDisconnect) {
                    try { newSocket.close() } catch (_: Exception) { }
                    return@thread
                }
                connected = true
                reconnectAttempt = 0
                mainHandler.post {
                    listener?.onConnected(name)
                    schedulePoll()
                }
                readLoop(newSocket, connectionGeneration)
            } catch (error: Exception) {
                if (generation == connectionGeneration && !userRequestedDisconnect) {
                    handleUnexpectedDisconnect(error.message ?: "No se pudo conectar", connectionGeneration)
                }
            }
        }
    }

    fun send(command: String): Boolean {
        if (!connected) return false
        val normalized = command.trim().uppercase()
        if (normalized.isEmpty()) return false
        mainHandler.post { enqueueCommand(normalized, isPoll = false) }
        return true
    }

    private fun enqueueCommand(command: String, isPoll: Boolean) {
        if (!connected) return
        cancelPoll()
        val key = command.substringBefore(' ')
        if (!isPoll && key in COALESCED_COMMANDS) {
            commandQueue.removeAll { !it.isPoll && it.command.substringBefore(' ') == key }
        }
        commandQueue.addLast(
            QueuedCommand(command, nextSequence(), isPoll, retriesLeft = if (isPoll) 0 else 1)
        )
        pumpQueue()
    }

    private fun pumpQueue() {
        if (!connected || inFlight != null) return
        val next = commandQueue.pollFirst()
        if (next == null) {
            schedulePoll()
            return
        }
        inFlight = next
        writeFramedCommand(next)
    }

    private fun writeFramedCommand(pending: QueuedCommand) {
        if (!writeRaw("@${pending.sequence} ${pending.command}")) return
        commandTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        commandTimeoutTask = Runnable { handleCommandTimeout(pending.sequence) }.also {
            mainHandler.postDelayed(it, COMMAND_TIMEOUT_MS)
        }
    }

    private fun writeRaw(command: String): Boolean {
        if (!connected) return false
        return try {
            socket?.outputStream?.write((command + "\n").toByteArray(Charsets.US_ASCII))
            socket?.outputStream?.flush()
            true
        } catch (error: Exception) {
            handleUnexpectedDisconnect(error.message ?: "Falló el envío", generation)
            false
        }
    }

    private fun handleCommandTimeout(sequence: Int) {
        val pending = inFlight ?: return
        if (pending.sequence != sequence) return
        if (pending.retriesLeft > 0 && connected) {
            pending.retriesLeft--
            listener?.onCommandFailed(pending.command, "Sin respuesta; reintentando una vez")
            mainHandler.postDelayed({
                if (inFlight?.sequence == sequence && connected) writeFramedCommand(pending)
            }, 120L)
        } else {
            listener?.onCommandFailed(pending.command, "Sin respuesta del Arduino")
            finishCommand()
        }
    }

    private fun handleIncomingLine(line: String) {
        val response = Regex("^RSP\\s+(\\d+)\\s+(OK|ERR)(?:\\s|$)").find(line)
        if (response == null) {
            listener?.onLineReceived(line, null)
            return
        }
        val sequence = response.groupValues[1].toIntOrNull()
        val pending = inFlight
        if (sequence == null || pending == null || sequence != pending.sequence) {
            listener?.onLineReceived(line, null)
            return
        }
        val status = response.groupValues[2]
        if (status == "ERR" && pending.retriesLeft > 0 && connected) {
            pending.retriesLeft--
            commandTimeoutTask?.let { mainHandler.removeCallbacks(it) }
            commandTimeoutTask = null
            mainHandler.postDelayed({
                if (inFlight?.sequence == sequence && connected) writeFramedCommand(pending)
            }, 120L)
            return
        }
        listener?.onLineReceived(line, pending.command)
        finishCommand()
    }

    private fun finishCommand() {
        commandTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        commandTimeoutTask = null
        inFlight = null
        mainHandler.postDelayed({ pumpQueue() }, 60L)
    }

    private fun nextSequence(): Int {
        val result = nextSequence
        nextSequence = if (nextSequence >= 9999) 1 else nextSequence + 1
        return result
    }

    fun disconnect(notify: Boolean = true) {
        userRequestedDisconnect = true
        cancelReconnect()
        cancelPoll()
        clearCommands()
        ++generation
        val wasConnected = connected
        connected = false
        connectedDeviceName = null
        closeSocket()
        if (notify && wasConnected) mainHandler.post { listener?.onDisconnected("Desconectado por el usuario") }
    }

    private fun readLoop(activeSocket: BluetoothSocket, connectionGeneration: Int) {
        try {
            val reader = BufferedReader(InputStreamReader(activeSocket.inputStream, Charsets.US_ASCII))
            while (connected && generation == connectionGeneration) {
                val line = reader.readLine() ?: break
                mainHandler.post { handleIncomingLine(line) }
            }
            if (generation == connectionGeneration && !userRequestedDisconnect) {
                handleUnexpectedDisconnect("El HC-06 cerró la conexión", connectionGeneration)
            }
        } catch (error: Exception) {
            if (generation == connectionGeneration && !userRequestedDisconnect) {
                handleUnexpectedDisconnect(error.message ?: "Se perdió la conexión", connectionGeneration)
            }
        }
    }

    private fun handleUnexpectedDisconnect(message: String, connectionGeneration: Int) {
        if (generation != connectionGeneration || userRequestedDisconnect) return
        if (!connected && reconnectTask != null) return
        connected = false
        cancelPoll()
        clearCommands()
        closeSocket()
        mainHandler.post { listener?.onDisconnected(message) }
        scheduleReconnect()
    }

    @SuppressLint("MissingPermission")
    private fun scheduleReconnect() {
        val device = lastDevice ?: return
        if (userRequestedDisconnect || reconnectAttempt >= RECONNECT_DELAYS.size) return
        val delay = RECONNECT_DELAYS[reconnectAttempt]
        val attempt = reconnectAttempt + 1
        reconnectAttempt++
        mainHandler.post { listener?.onReconnecting(attempt, delay) }
        reconnectTask = Runnable {
            reconnectTask = null
            if (!userRequestedDisconnect && !connected) startConnection(device, announce = false)
        }.also { mainHandler.postDelayed(it, delay) }
    }

    private fun schedulePoll() {
        cancelPoll()
        if (!connected || listener == null) return
        pollTask = Runnable {
            pollTask = null
            if (connected && listener != null && inFlight == null && commandQueue.isEmpty()) {
                enqueueCommand("ESTADO", isPoll = true)
            } else {
                schedulePoll()
            }
        }.also { mainHandler.postDelayed(it, POLL_INTERVAL_MS) }
    }

    private fun cancelPoll() {
        pollTask?.let { mainHandler.removeCallbacks(it) }
        pollTask = null
    }

    private fun clearCommands() {
        commandTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        commandTimeoutTask = null
        inFlight = null
        commandQueue.clear()
    }

    private fun cancelReconnect() {
        reconnectTask?.let { mainHandler.removeCallbacks(it) }
        reconnectTask = null
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }
}
