package py.soulhoshi.parkassist

import android.app.Application

class ParkAssistApplication : Application() {
    lateinit var bluetooth: BluetoothController
        private set

    override fun onCreate() {
        super.onCreate()
        bluetooth = BluetoothController(this)
    }
}
