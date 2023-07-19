package xh.rabbit.usbcameralib

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import timber.log.Timber
import xh.rabbit.usbcamera.ToastUtil
import xh.rabbit.usbcamera.UsbCameraManager
import xh.rabbit.usbcamera.uvc.UVCFragment
import xh.rabbit.usbcameralib.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), UVCFragment.OnFragmentActionListener {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.ubt.robocontroller.USB_PERMISSION"
        private const val REQUEST_CODE_ALL_PERMISSION = 1

    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var fragment: UVCFragment
    private var mUsbManager: UsbManager? = null

    private val activityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            if (!Environment.isExternalStorageManager()) {
//                try {
//                    val intent = Intent(
//                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
//                        Uri.parse("package:" + packageName))
//                    activityLauncher.launch(intent)
//                } catch (e: Exception) {
//                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
//                    activityLauncher.launch(intent)
//
//                }
//            } else {
//                permissionTask()
//            }
//        } else {
//            // 权限申请
//            permissionTask()
//        }

        permissionTask()
    }

    @AfterPermissionGranted(REQUEST_CODE_ALL_PERMISSION)
    private fun permissionTask() {
        if (hasPermission()) {
            tryGetUsbPermission()
        } else {
            EasyPermissions.requestPermissions(
                this,
                "App需要相机和麦克风权限",
                REQUEST_CODE_ALL_PERMISSION,
                Manifest.permission.CAMERA
            )
        }
    }

    private fun hasPermission() : Boolean {
        return EasyPermissions.hasPermissions(
            this,
            Manifest.permission.CAMERA
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    private fun tryGetUsbPermission() {
        mUsbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // 注册权限接收广播
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(mUsbPermissionActionReceiver, filter)

        requestUsbPermission()
    }

    private fun requestUsbPermission() {
        val mPermissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE
        )
        val filters = UsbCameraManager.getDeviceFilters(this)
        var foundDevice = false
        mUsbManager?.deviceList?.values?.forEach { usbDevice ->
            filters.forEach { filiter ->
                if (filiter.mProductId == usbDevice.productId && !filiter.isExclude) {
                    // 32802
                    // 7749
                    foundDevice = true
                    if (mUsbManager!!.hasPermission(usbDevice)) {
                        afterGetUsbPermission(usbDevice)
                    } else {
                        // 请求USB权限
                        mUsbManager!!.requestPermission(usbDevice, mPermissionIntent)
                    }
                }
            }
        }
        if (!foundDevice) {
            ToastUtil.show(this, "未找到指定设备")
        }
    }

    private val mUsbPermissionActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION == intent?.action) {
                synchronized(this) {
                    val usbDevice: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (usbDevice != null) {
                            afterGetUsbPermission(usbDevice)
                        }
                    } else {
                        requestUsbPermission()
                    }
                }
            }
        }
    }

    private fun afterGetUsbPermission(usbDevice: UsbDevice) {
        Timber.d("afterGetUsbPermission: ${usbDevice.deviceId}")
        fragment = UVCFragment.newInstance(usbDevice.productId, "xh.rabbit.usbcameralib")
        supportFragmentManager.beginTransaction()
            .add(R.id.camera_fragment_container, fragment)
            .commit()

    }

    override fun onMarking(index: Int, code: Int) {

    }

    override fun onFpsChange(fps: Int, fpsHandle: Int) {

    }
}