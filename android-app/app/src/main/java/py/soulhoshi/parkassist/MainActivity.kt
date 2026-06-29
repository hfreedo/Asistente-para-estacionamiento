package py.soulhoshi.parkassist

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import java.util.Locale

class MainActivity : Activity(), BluetoothController.Listener {
    private enum class OperationMode { SENSOR, MANUAL, DEMO }

    private val bluetooth: BluetoothController
        get() = (application as ParkAssistApplication).bluetooth
    private lateinit var connectButton: Button
    private lateinit var distanceText: TextView
    private lateinit var stateText: TextView
    private lateinit var ledStrip: LedStripView
    private lateinit var logText: TextView
    private lateinit var systemSwitch: Switch
    private lateinit var soundSwitch: Switch
    private lateinit var brightnessSlider: SeekBar
    private lateinit var speedSlider: SeekBar
    private lateinit var manualPanel: View
    private lateinit var modeStatusText: TextView
    private lateinit var profileSummaryText: TextView
    private lateinit var rangeMaxEdit: EditText
    private lateinit var rangeMinEdit: EditText
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var mainScroll: ScrollView
    private lateinit var pageTitleText: TextView
    private var monitorViews: List<View> = emptyList()
    private var calibrationViews: List<View> = emptyList()
    private var settingsViews: List<View> = emptyList()
    private val bottomNavigationButtons = mutableListOf<Button>()
    private val modeButtons = mutableListOf<Button>()
    private var currentMode = OperationMode.SENSOR
    private var systemActive = true
    private var suppressUiEvents = false
    private lateinit var rangeCardRef: View
    private lateinit var preferencesCardRef: View
    private lateinit var testCardRef: View
    private lateinit var profileCardRef: View
    private var selectedDeviceLabel: String? = null
    private var rangeMaxCm = 120f
    private var rangeMinCm = 15f
    private var lastDistanceCm: Float? = null

    private val bg = Color.rgb(8, 17, 31)
    private val panel = Color.rgb(16, 28, 45)
    private val panelAlt = Color.rgb(21, 36, 58)
    private val textColor = Color.rgb(237, 245, 255)
    private val muted = Color.rgb(143, 163, 189)
    private val cyan = Color.rgb(56, 212, 197)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildInterface())
        ensureBluetoothPermission()
    }

    override fun onStart() {
        super.onStart()
        bluetooth.setListener(this)
        if (bluetooth.isConnected()) {
            connectButton.text = "● ${bluetooth.connectedDeviceName ?: "HC-06"}"
            connectButton.setTextColor(Color.rgb(98, 233, 173))
            send("CONFIG")
        }
    }

    override fun onStop() {
        bluetooth.setListener(null)
        super.onStop()
    }

    private fun buildInterface(): View {
        drawerLayout = DrawerLayout(this).apply { setBackgroundColor(bg) }
        val root = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        mainScroll = root
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(34))
        }
        root.addView(content)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val menuButton = button("☰", false).apply {
            textSize = 19f
            contentDescription = "Abrir menú"
            setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.parkassist_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Logo de ParkAssist"
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
        val title = TextView(this).apply {
            setTextColor(textColor); textSize = 22f; setTypeface(typeface, Typeface.BOLD)
            text = "ParkAssist\n"
            append(spanText("Asistente Arduino", muted, 11f))
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(58), 1f))
        connectButton = button("Conectar HC-06", false).apply { setOnClickListener { toggleConnection() } }
        header.addView(connectButton)
        content.addView(header)
        pageTitleText = TextView(this).apply {
            text = "Monitor"
            setTextColor(muted); textSize = 12f; letterSpacing = .10f
            setPadding(dp(2), dp(10), 0, 0)
        }
        content.addView(pageTitleText)

        val statusCard = card()
        statusCard.addView(label("DISTANCIA ACTUAL"))
        val distanceRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        distanceText = TextView(this).apply {
            setTextColor(textColor); textSize = 48f; setTypeface(typeface, Typeface.BOLD); text = "-- cm"
        }
        stateText = TextView(this).apply {
            text = "SIN CONEXIÓN"; setTextColor(muted); textSize = 11f
            setPadding(dp(10), dp(7), dp(10), dp(7)); background = rounded(Color.rgb(30, 43, 61), 10)
        }
        distanceRow.addView(distanceText, LinearLayout.LayoutParams(0, dp(70), 1f))
        distanceRow.addView(stateText)
        statusCard.addView(distanceRow)
        ledStrip = LedStripView(this)
        statusCard.addView(ledStrip, LinearLayout.LayoutParams(-1, dp(52)))
        systemSwitch = Switch(this).apply {
            text = "Sistema activo"
            setTextColor(textColor)
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                if (!suppressUiEvents && !send("SISTEMA ${if (checked) "ON" else "OFF"}")) {
                    applySystemState(systemActive)
                }
            }
        }
        statusCard.addView(systemSwitch, topMargin(8))
        content.addView(statusCard, cardParams())

        val profileCard = card()
        profileCardRef = profileCard
        profileCard.addView(sectionTitle("Estilo visual"))
        profileSummaryText = TextView(this).apply {
            text = "Guiado\n15 → 9 → 5 LED, sin movimiento adicional"
            setTextColor(Color.rgb(197, 211, 229)); textSize = 14f
            setLineSpacing(0f, 1.15f)
        }
        profileCard.addView(profileSummaryText)
        profileCard.addView(button("Cambiar estilo", false).apply {
            setOnClickListener { showProfileSelector() }
        }, topMargin(12))
        content.addView(profileCard, cardParams())

        val rangeCard = card()
        rangeCardRef = rangeCard
        rangeCard.addView(sectionTitle("Calibración de distancias"))
        val rangeRow = LinearLayout(this)
        val maxInput = numberInput("Rango máximo", rangeMaxCm.toInt().toString())
        val minInput = numberInput("Detenerse en", rangeMinCm.toInt().toString())
        rangeMaxEdit = maxInput.tag as EditText
        rangeMinEdit = minInput.tag as EditText
        rangeRow.addView(maxInput, LinearLayout.LayoutParams(0, -2, 1f))
        rangeRow.addView(minInput, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
        rangeCard.addView(rangeRow)
        rangeCard.addView(button("Aplicar rangos", false).apply {
            setOnClickListener {
                val max = rangeMaxEdit.text.toString()
                val min = rangeMinEdit.text.toString()
                val parsedMax = max.toFloatOrNull()
                val parsedMin = min.toFloatOrNull()
                if (parsedMax == null || parsedMin == null || parsedMin < 5f ||
                    parsedMax > 400f || parsedMax < parsedMin + 30f) {
                    alert("Rango inválido", "El mínimo debe ser al menos 5 cm y debe haber 30 cm de diferencia.")
                } else {
                    send("RANGO $max $min")
                }
            }
        }, topMargin(10))
        content.addView(rangeCard, cardParams())

        val preferencesCard = card()
        preferencesCardRef = preferencesCard
        preferencesCard.addView(sectionTitle("Preferencias de alerta"))
        val brightnessControl = sliderControl("Brillo", 1, 25, 16) { send("BRILLO $it") }
        brightnessSlider = brightnessControl.getChildAt(1) as SeekBar
        preferencesCard.addView(brightnessControl)
        val speedControl = sliderControl("Velocidad", 1, 10, 5) { send("VELOCIDAD $it") }
        speedSlider = speedControl.getChildAt(1) as SeekBar
        preferencesCard.addView(speedControl)
        soundSwitch = Switch(this).apply {
            text = "Alerta sonora"; setTextColor(textColor); isChecked = true
            buttonTintList = null
            setOnCheckedChangeListener { _, checked ->
                if (!suppressUiEvents) send("SONIDO ${if (checked) "ON" else "OFF"}")
            }
        }
        preferencesCard.addView(soundSwitch, topMargin(8))
        preferencesCard.addView(button("Restaurar valores de fábrica", false).apply {
            setOnClickListener { confirmFactoryReset() }
        }, topMargin(12))
        content.addView(preferencesCard, cardParams())

        val testCard = card()
        testCardRef = testCard
        testCard.addView(sectionTitle("Modo de operación"))
        testCard.addView(TextView(this).apply {
            text = "Solo puede existir un modo activo. El Arduino confirma cada cambio."
            setTextColor(muted); textSize = 12f
        })
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Sensor", "Manual", "Demo").forEachIndexed { index, label ->
            val modeButton = button(label, false).apply {
                setOnClickListener { requestOperationMode(OperationMode.entries[index]) }
            }
            modeButtons.add(modeButton)
            modeRow.addView(modeButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        testCard.addView(modeRow, topMargin(12))
        modeStatusText = TextView(this).apply {
            setTextColor(cyan); textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }
        testCard.addView(modeStatusText)
        val distanceValue = TextView(this).apply { setTextColor(cyan); text = "68 cm" }
        val simSlider = SeekBar(this).apply {
            max = 135; progress = 63
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + 5
                    distanceValue.text = "$value cm"
                    if (fromUser && currentMode == OperationMode.MANUAL) updateDistance(value.toFloat())
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (currentMode == OperationMode.MANUAL) send("DISTANCIA ${bar?.progress?.plus(5)}")
                }
            })
        }
        val simHeader = LinearLayout(this).apply {
            addView(TextView(this@MainActivity).apply { text = "Distancia simulada"; setTextColor(muted) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(distanceValue)
        }
        manualPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(simHeader)
            addView(simSlider)
        }
        testCard.addView(manualPanel, topMargin(10))
        testCard.addView(button("Guardar configuración", true).apply {
            setOnClickListener { send("GUARDAR") }
        }, topMargin(10))
        updateModeUi(OperationMode.SENSOR)
        content.addView(testCard, cardParams())

        logText = TextView(this).apply {
            setTextColor(muted); textSize = 11f; text = "Esperando conexión..."
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val safetyNote = TextView(this).apply {
            text = "El Arduino conserva el control de seguridad aunque el teléfono se desconecte."
            setTextColor(Color.rgb(102, 123, 150)); textSize = 9f; gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), 0)
        }
        content.addView(safetyNote)

        monitorViews = listOf(statusCard, safetyNote)
        calibrationViews = listOf(rangeCard, testCard)
        settingsViews = listOf(profileCard, preferencesCard)

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(root, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(buildBottomNavigation(), LinearLayout.LayoutParams(-1, dp(68)))
        }
        drawerLayout.addView(shell, DrawerLayout.LayoutParams(-1, -1))
        drawerLayout.addView(
            buildNavigationDrawer(),
            DrawerLayout.LayoutParams(dp(304), -1).apply { gravity = GravityCompat.START }
        )
        showDestination(0)
        updateControlAvailability()
        return drawerLayout
    }

    private fun buildBottomNavigation(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(18, 31, 49), Color.rgb(10, 20, 34))
            ).apply { setStroke(dp(1), Color.rgb(38, 56, 81)) }

            listOf("Monitor", "Calibración", "Ajustes").forEachIndexed { index, label ->
                val navButton = button(label, false).apply {
                    setOnClickListener { showDestination(index) }
                }
                bottomNavigationButtons.add(navButton)
                addView(navButton, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (index > 0) marginStart = dp(6)
                })
            }
        }
    }

    private fun showDestination(destination: Int) {
        (monitorViews + calibrationViews + settingsViews).forEach { it.visibility = View.GONE }
        val selectedViews = when (destination) {
            1 -> calibrationViews
            2 -> settingsViews
            else -> monitorViews
        }
        selectedViews.forEach { it.visibility = View.VISIBLE }
        pageTitleText.text = when (destination) {
            1 -> "Calibración"
            2 -> "Ajustes"
            else -> "Monitor"
        }
        bottomNavigationButtons.forEachIndexed { index, button ->
            button.background = rounded(
                if (index == destination) Color.rgb(25, 61, 59) else Color.TRANSPARENT,
                12
            )
            button.setTextColor(if (index == destination) cyan else muted)
            button.setTypeface(button.typeface, if (index == destination) Typeface.BOLD else Typeface.NORMAL)
        }
        mainScroll.post { mainScroll.smoothScrollTo(0, 0) }
    }

    private fun confirmFactoryReset() {
        AlertDialog.Builder(this)
            .setTitle("Restaurar configuración")
            .setMessage("Se recuperarán los rangos, perfiles y alertas predeterminados. ¿Deseas continuar?")
            .setPositiveButton("Restaurar") { _, _ ->
                applyRanges(120f, 15f)
                send("FABRICA")
                send("GUARDAR")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun toggleConnection() {
        if (bluetooth.isConnected()) { bluetooth.disconnect(); return }
        if (!bluetooth.hasPermission()) { ensureBluetoothPermission(); return }
        if (!bluetooth.isBluetoothEnabled()) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return
        }
        val devices = bluetooth.pairedDevices()
        if (devices.isEmpty()) {
            alert("Sin dispositivos", "Empareja primero el HC-06 desde Ajustes > Bluetooth.")
            return
        }
        val labels = devices.map { "${it.name ?: "Dispositivo"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Seleccionar HC-06").setItems(labels) { _, index ->
            selectedDeviceLabel = labels[index].substringBefore('\n')
            bluetooth.connect(devices[index])
        }.setNegativeButton("Cancelar", null).show()
    }

    private fun buildNavigationDrawer(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(38), dp(22), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(18, 35, 56), Color.rgb(8, 17, 31))
            )

            val header = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            header.addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.parkassist_logo)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginEnd = dp(13) })
            header.addView(TextView(this@MainActivity).apply {
                text = "ParkAssist\nVersión 0.1.0"
                setTextColor(textColor); textSize = 18f; setTypeface(typeface, Typeface.BOLD)
            })
            addView(header)
            addView(TextView(this@MainActivity).apply {
                text = "INFORMACIÓN"
                setTextColor(muted); textSize = 10f; letterSpacing = .16f
                setPadding(0, dp(34), 0, dp(10))
            })
            addView(drawerItem("◉", "Dispositivo y conexión") {
                drawerLayout.closeDrawers(); showDeviceInfo()
            })
            addView(drawerItem("≡", "Diagnóstico") {
                drawerLayout.closeDrawers(); showDiagnostics()
            })
            addView(drawerItem("?", "Preguntas frecuentes") {
                drawerLayout.closeDrawers(); showFaq()
            })
            addView(drawerItem("ⓘ", "Acerca del proyecto") {
                drawerLayout.closeDrawers(); showAbout()
            })

            addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "PROTOTIPO EXPERIMENTAL"
                setTextColor(Color.rgb(102, 123, 150)); textSize = 9f; gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = "Diseñado por @hfreedo"
                setTextColor(cyan); textSize = 11f; gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, 0)
                setOnClickListener { openGithub() }
            })
        }
    }

    private fun drawerItem(icon: String, title: String, action: () -> Unit): View {
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(13), dp(13), dp(13))
            background = rounded(Color.rgb(21, 36, 58), 13)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
            addView(TextView(this@MainActivity).apply {
                text = icon; setTextColor(cyan); textSize = 20f; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
            addView(TextView(this@MainActivity).apply {
                text = title; setTextColor(textColor); textSize = 14f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            layoutParams = LinearLayout.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(9) }
        }
    }

    private fun showAbout() {
        showThemedPage(
            "Acerca de ParkAssist",
            "Versión 0.1.0\n\n" +
                "ParkAssist es un prototipo de asistencia al estacionamiento basado en Arduino UNO, " +
                "sensor HC-SR04, comunicación HC-06, buzzer y tiras WS2812B.\n\n" +
                "Su objetivo es comunicar la proximidad mediante colores, reducción progresiva de " +
                "la zona iluminada y alertas sonoras claras.\n\n" +
                "Licencia: MIT\n" +
                "Plataforma: GitHub\n\n" +
                "Este proyecto es experimental y no sustituye la atención ni el criterio del conductor.",
            "Ver perfil en GitHub"
        ) { openGithub() }
    }

    private fun showDeviceInfo() {
        val state = when {
            bluetooth.isConnected() -> "Conectado"
            !bluetooth.isBluetoothEnabled() -> "Bluetooth desactivado"
            else -> "Desconectado"
        }
        val device = selectedDeviceLabel?.let { "Dispositivo: $it" } ?: "Ningún dispositivo seleccionado"
        showThemedPage(
            "Dispositivo y conexión",
            "Estado: $state\n" +
                "$device\n" +
                "Protocolo: Bluetooth clásico SPP\n" +
                "Velocidad del HC-06: 9600 baudios\n\n" +
                "El HC-06 debe estar emparejado previamente desde los ajustes de Android. " +
                "La pérdida de conexión no detiene el funcionamiento autónomo del Arduino.",
            if (bluetooth.isConnected()) "Desconectar" else "Seleccionar HC-06"
        ) {
            if (bluetooth.isConnected()) bluetooth.disconnect() else toggleConnection()
        }
    }

    private fun showDiagnostics() {
        val distance = lastDistanceCm?.let { String.format(Locale.getDefault(), "%.1f cm", it) }
            ?: "Sin datos"
        showThemedPage(
            "Diagnóstico",
            "Bluetooth: ${if (bluetooth.isConnected()) "conectado" else "desconectado"}\n" +
                "Última distancia: $distance\n" +
                "Rango configurado: ${rangeMaxCm.toInt()} / ${rangeMinCm.toInt()} cm\n\n" +
                "REGISTRO DE COMUNICACIÓN\n\n${logText.text}",
            "Limpiar registro"
        ) { logText.text = "Registro limpio." }
    }

    private fun showFaq() {
        showThemedPage(
            "Preguntas frecuentes",
                "¿La app funciona sin HC-SR04?\n" +
                    "Sí. Activa el modo simulado o la demostración automática.\n\n" +
                    "¿Por qué no aparece el HC-06?\n" +
                    "Primero debes emparejarlo desde los ajustes Bluetooth de Android.\n\n" +
                    "¿Qué significan los colores?\n" +
                    "Verde: seguro. Amarillo: precaución. Rojo: peligro. Rojo intermitente: detenerse.\n\n" +
                    "¿Los cambios se conservan?\n" +
                    "Solo después de pulsar Guardar, que escribe la configuración en la EEPROM.\n\n" +
                    "¿Qué sucede si se desconecta el teléfono?\n" +
                    "El Arduino continúa midiendo y alertando de forma autónoma.\n\n" +
                    "¿Por qué el brillo está limitado?\n" +
                    "Para proteger la alimentación USB durante las pruebas con una tira de 15 LED."
        )
    }

    private fun showThemedPage(
        title: String,
        body: String,
        actionLabel: String? = null,
        action: (() -> Unit)? = null
    ) {
        val dialog = Dialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(18))
            background = rounded(panel, 22)
            addView(TextView(this@MainActivity).apply {
                text = title; setTextColor(textColor); textSize = 21f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(View(this@MainActivity).apply { setBackgroundColor(Color.rgb(38, 56, 81)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(14) })
            val bodyText = TextView(this@MainActivity).apply {
                text = body; setTextColor(Color.rgb(197, 211, 229)); textSize = 14f
                setLineSpacing(0f, 1.22f); setPadding(0, 0, dp(6), 0)
            }
            addView(ScrollView(this@MainActivity).apply { addView(bodyText) },
                LinearLayout.LayoutParams(-1, 0, 1f))
            val actions = LinearLayout(this@MainActivity).apply { gravity = Gravity.END }
            if (actionLabel != null && action != null) {
                actions.addView(button(actionLabel, false).apply {
                    setOnClickListener { dialog.dismiss(); action() }
                }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(9) })
            }
            actions.addView(button("Cerrar", true).apply { setOnClickListener { dialog.dismiss() } },
                LinearLayout.LayoutParams(dp(96), dp(46)))
            addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(.72f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout((resources.displayMetrics.widthPixels * .90f).toInt(),
                (resources.displayMetrics.heightPixels * .78f).toInt())
        }
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * .90f).toInt(),
            (resources.displayMetrics.heightPixels * .78f).toInt()
        )
    }

    private fun openGithub() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/hfreedo")))
    }

    private fun ensureBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 41)
        }
    }

    private fun send(command: String): Boolean {
        if (!bluetooth.send(command)) {
            appendLog("No enviado: $command (sin conexión)")
            Toast.makeText(this, "HC-06 no conectado", Toast.LENGTH_SHORT).show()
            return false
        }
        appendLog("→ $command")
        return true
    }

    override fun onConnecting(deviceName: String) {
        connectButton.text = "Conectando…"; appendLog("Conectando con $deviceName")
    }
    override fun onConnected(deviceName: String) {
        connectButton.text = "● $deviceName"; connectButton.setTextColor(Color.rgb(98, 233, 173))
        appendLog("Conectado con $deviceName")
        updateControlAvailability()
        send("CONFIG")
    }
    override fun onReconnecting(attempt: Int, delayMs: Long) {
        connectButton.text = "Reconectando…"
        appendLog("Reconexión $attempt en ${delayMs / 1000} s")
    }
    override fun onDisconnected(message: String) {
        connectButton.text = "Conectar HC-06"; connectButton.setTextColor(textColor)
        stateText.text = "SIN CONEXIÓN"; appendLog(message)
        updateControlAvailability()
    }
    override fun onLineReceived(line: String, command: String?) {
        val responseStatus = Regex("^RSP\\s+\\d+\\s+(OK|ERR)(?:\\s|$)")
            .find(line)?.groupValues?.getOrNull(1)
        if (command != null && command != "ESTADO") {
            appendLog(if (responseStatus == "OK") "✓ $command" else "✕ $command")
        } else if (!line.startsWith("RSP ")) {
            appendLog("← $line")
        }
        if (responseStatus == "ERR" && command != "ESTADO") {
            Toast.makeText(this, "Arduino rechazó: ${command ?: "comando desconocido"}", Toast.LENGTH_SHORT).show()
        }
        val match = Regex("DIST=([0-9]+(?:\\.[0-9]+)?)").find(line)
        match?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let { updateDistance(it) }
        if (line.startsWith("CONFIG ") || line.startsWith("RSP ")) {
            val max = configValue(line, "RANGE")?.toFloatOrNull()
            val min = configValue(line, "MIN")?.toFloatOrNull()
            if (max != null && min != null) applyRanges(max, min)
            configValue(line, "PROFILE")?.toIntOrNull()?.let { updateProfileSummary(it) }
            configValue(line, "BRIGHT")?.toIntOrNull()?.let { brightnessSlider.progress = it - 1 }
            configValue(line, "SPEED")?.toIntOrNull()?.let { speedSlider.progress = it - 1 }
            configValue(line, "SOUND")?.let { sound ->
                suppressUiEvents = true
                soundSwitch.isChecked = sound == "1"
                suppressUiEvents = false
            }
            configValue(line, "SYSTEM")?.let { applySystemState(it == "1") }
            configValue(line, "MODE")?.let { modeValue ->
                updateModeUi(when (modeValue) {
                    "MANUAL" -> OperationMode.MANUAL
                    "DEMO" -> OperationMode.DEMO
                    else -> OperationMode.SENSOR
                })
            }
            configValue(line, "STATE")?.let { updateState(it) }
        }
        if ('|' in line) updateState(line.substringAfter('|').trim())
    }

    override fun onCommandFailed(command: String, reason: String) {
        if (command != "ESTADO") {
            appendLog("⚠ $command: $reason")
            Toast.makeText(this, "$command: $reason", Toast.LENGTH_SHORT).show()
        }
        if (command.startsWith("SISTEMA ")) applySystemState(systemActive)
        if (command.startsWith("MODO ")) updateModeUi(currentMode)
        if (reason == "Sin respuesta del Arduino" && command != "CONFIG") send("CONFIG")
    }

    private fun updateDistance(value: Float) {
        lastDistanceCm = value
        distanceText.text = String.format(Locale.getDefault(), "%.1f cm", value)
        ledStrip.setDistance(value)
        val span = rangeMaxCm - rangeMinCm
        val redLimit = rangeMinCm + span / 3f
        val yellowLimit = rangeMinCm + 2f * span / 3f
        updateState(when {
            value > rangeMaxCm -> "FUERA DE RANGO"
            value <= rangeMinCm -> "DETENERSE / CRITICO"
            value <= redLimit -> "PELIGRO / ROJO"
            value <= yellowLimit -> "PRECAUCION / AMARILLO"
            else -> "SEGURO / VERDE"
        })
    }

    private fun applyRanges(max: Float, min: Float) {
        rangeMaxCm = max
        rangeMinCm = min
        rangeMaxEdit.setText(if (max % 1f == 0f) max.toInt().toString() else max.toString())
        rangeMinEdit.setText(if (min % 1f == 0f) min.toInt().toString() else min.toString())
        ledStrip.setThresholds(max, min)
        lastDistanceCm?.let { updateDistance(it) }
    }

    private fun configValue(line: String, key: String): String? {
        return Regex("(?:^| )$key=([^ ]+)").find(line)?.groupValues?.getOrNull(1)
    }

    private fun requestOperationMode(mode: OperationMode) {
        if (!systemActive) {
            Toast.makeText(this, "Activa el sistema antes de cambiar el modo", Toast.LENGTH_SHORT).show()
            return
        }
        if (send("MODO ${mode.name}")) {
            modeStatusText.text = "Esperando confirmación del Arduino…"
        }
    }

    private fun updateModeUi(mode: OperationMode) {
        currentMode = mode
        modeButtons.forEachIndexed { index, button ->
            val selected = index == mode.ordinal
            button.background = rounded(
                if (selected) Color.rgb(25, 61, 59) else Color.rgb(28, 42, 62), 12
            )
            button.setTextColor(if (selected) cyan else textColor)
        }
        manualPanel.visibility = if (mode == OperationMode.MANUAL) View.VISIBLE else View.GONE
        modeStatusText.text = when (mode) {
            OperationMode.SENSOR -> "Sensor real: lectura del HC-SR04"
            OperationMode.MANUAL -> "Simulación manual: ajusta una distancia fija"
            OperationMode.DEMO -> "Demostración automática en ejecución"
        }
        updateControlAvailability()
    }

    private fun applySystemState(active: Boolean) {
        systemActive = active
        suppressUiEvents = true
        systemSwitch.isChecked = active
        suppressUiEvents = false
        if (!active) updateState("SISTEMA PAUSADO")
        updateControlAvailability()
    }

    private fun updateControlAvailability() {
        if (!::rangeCardRef.isInitialized) return
        val connected = bluetooth.isConnected()
        systemSwitch.isEnabled = connected
        setEnabledRecursively(rangeCardRef, connected)
        setEnabledRecursively(preferencesCardRef, connected)
        setEnabledRecursively(profileCardRef, connected)
        setEnabledRecursively(testCardRef, connected && systemActive)
        if (connected && systemActive) {
            setEnabledRecursively(manualPanel, currentMode == OperationMode.MANUAL)
        }
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) setEnabledRecursively(view.getChildAt(index), enabled)
        }
        if (view === rangeCardRef || view === preferencesCardRef || view === profileCardRef || view === testCardRef) {
            view.alpha = if (enabled) 1f else .48f
        }
    }

    private fun updateState(value: String) {
        if (!systemActive) {
            stateText.text = "SISTEMA PAUSADO"
            stateText.setTextColor(muted)
            stateText.background = rounded(Color.rgb(30, 43, 61), 10)
            return
        }
        stateText.text = when {
            "CRITICO" in value -> "DETENERSE / CRÍTICO"
            "ROJO" in value -> "PELIGRO / ROJO"
            "AMARILLO" in value -> "PRECAUCIÓN / AMARILLO"
            "VERDE" in value -> "SEGURO / VERDE"
            "FUERA" in value -> "FUERA DE RANGO"
            "ERROR" in value -> "ERROR DEL SENSOR"
            else -> value
        }
        val color = when {
            "CRITICO" in value || "ROJO" in value -> Color.rgb(255, 77, 94)
            "AMARILLO" in value -> Color.rgb(255, 210, 73)
            "VERDE" in value -> Color.rgb(62, 229, 123)
            else -> muted
        }
        stateText.setTextColor(color)
        val stateBackground = when {
            "CRITICO" in value || "ROJO" in value -> Color.rgb(66, 30, 40)
            "AMARILLO" in value -> Color.rgb(64, 55, 17)
            "VERDE" in value -> Color.rgb(21, 55, 47)
            else -> Color.rgb(30, 43, 61)
        }
        stateText.background = rounded(stateBackground, 10)
    }

    private fun appendLog(message: String) {
        logText.text = (message + "\n" + logText.text).take(1200)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(panel, 20)
    }
    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value; setTextColor(textColor); textSize = 15f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(12))
    }
    private fun label(value: String) = TextView(this).apply { text = value; setTextColor(muted); textSize = 10f; letterSpacing = .12f }
    private fun button(value: String, primary: Boolean) = Button(this).apply {
        text = value; textSize = 11f; isAllCaps = false; setTextColor(if (primary) Color.rgb(6, 36, 33) else textColor)
        background = rounded(if (primary) cyan else Color.rgb(28, 42, 62), 12); minHeight = 0; minWidth = 0
    }
    private fun numberInput(title: String, value: String): LinearLayout {
        val input = EditText(this).apply {
            setText(value); setTextColor(textColor); textSize = 18f; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null; setPadding(0, 2, 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(11), dp(9), dp(11), dp(9)); background = rounded(panelAlt, 13)
            addView(label(title.uppercase())); addView(input); tag = input
        }
    }
    private fun sliderControl(title: String, min: Int, max: Int, initial: Int, onStop: (Int) -> Unit): LinearLayout {
        val value = TextView(this).apply { setTextColor(cyan); text = "$initial / $max" }
        val header = LinearLayout(this).apply {
            addView(TextView(this@MainActivity).apply { text = title; setTextColor(muted) }, LinearLayout.LayoutParams(0, -2, 1f)); addView(value)
        }
        val slider = SeekBar(this).apply {
            this.max = max - min; progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { value.text = "${progress + min} / $max" }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) { onStop((bar?.progress ?: 0) + min) }
            })
        }
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(header); addView(slider) }.also { it.layoutParams = topMargin(12) }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun updateProfileSummary(index: Int) {
        val summaries = listOf(
            "Simple\nColor fijo en toda la tira, mínima distracción",
            "Guiado\n15 → 9 → 5 LED, sin movimiento adicional",
            "Dinámico\nDos señales convergen desde los extremos",
            "Flujo\nLa luz se construye desde el centro",
            "Respiración\nPulso suave de intensidad"
        )
        if (index in summaries.indices) profileSummaryText.text = summaries[index]
    }

    private fun showProfileSelector() {
        val tokens = listOf("SIMPLE", "GUIADO", "DINAMICO", "FLUJO", "RESPIRACION")
        val labels = listOf(
            "Simple — color fijo y mínima distracción",
            "Guiado — reducción progresiva 15 → 9 → 5",
            "Dinámico — convergencia desde ambos extremos",
            "Flujo — expansión desde el centro",
            "Respiración — pulso suave de intensidad"
        )
        val dialog = Dialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = rounded(panel, 22)
            addView(sectionTitle("Seleccionar estilo visual"))
            labels.forEachIndexed { index, label ->
                addView(button(label, false).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        if (send("PERFIL ${tokens[index]}")) dialog.dismiss()
                    }
                }, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(8) })
            }
            addView(button("Cancelar", true).apply { setOnClickListener { dialog.dismiss() } },
                LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        }
        dialog.setContentView(container)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(.72f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), -2)
        }
    }
    private fun marginEnd(value: Int) = LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(value) }
    private fun topMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun alert(title: String, message: String) = AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Aceptar", null).show()
    private fun spanText(value: String, color: Int, size: Float) = android.text.SpannableString(value).apply {
        setSpan(android.text.style.ForegroundColorSpan(color), 0, length, 0)
        setSpan(android.text.style.AbsoluteSizeSpan(size.toInt(), true), 0, length, 0)
    }
}
