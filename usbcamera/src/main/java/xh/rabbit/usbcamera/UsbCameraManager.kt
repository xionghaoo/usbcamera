package xh.rabbit.usbcamera

import android.content.Context
import androidx.annotation.XmlRes
import com.serenegiant.usb.DeviceFilter

class UsbCameraManager {
    companion object {
        fun getDeviceFilters(context: Context) =
            DeviceFilter.getDeviceFilters(context, R.xml.device_filter)
    }
}