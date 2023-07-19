package xh.rabbit.usbcameralib

import android.app.Application
import timber.log.Timber

class UsbCameraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}