package xh.rabbit.usbcamera.uvc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.serenegiant.common.BaseFragment
import com.serenegiant.usb.CameraDialog
import com.serenegiant.usb.DeviceFilter
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import xh.rabbit.usbcamera.Config
import xh.rabbit.usbcamera.Logger
import xh.rabbit.usbcamera.R
import xh.rabbit.usbcamera.ToastUtil
import xh.rabbit.usbcamera.databinding.FragmentUvcBinding
import xh.rabbit.usbcamera.uvc.service.UVCService
import xh.rabbit.usbcamera.uvc.serviceclient.CameraClient
import xh.rabbit.usbcamera.uvc.serviceclient.ICameraClient
import xh.rabbit.usbcamera.uvc.serviceclient.ICameraClientCallback


class UVCFragment : BaseFragment() {

    private lateinit var binding: FragmentUvcBinding
    private var mUSBMonitor: USBMonitor? = null
    private var mCameraClient: ICameraClient? = null
    private var listener: OnFragmentActionListener? = null
    private var hasAddSurface = false

    private val pid: Int by lazy { arguments?.getInt(ARG_PID) ?: 0 }
//    private val points: ArrayList<PointF> by lazy {
//        arguments?.getParcelableArrayList(ARG_POINTS) ?: arrayListOf()
//    }
    private val packageName: String by lazy {
        arguments?.getString(ARG_PACKAGE_NAME) ?: ""
    }

    // 相机权限申请后回调
    private val mOnDeviceConnectListener: USBMonitor.OnDeviceConnectListener =
        object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice) {
                // 检查完USB权限之后调用，这时候设备是有权限的
                if (!updateCameraDialog() && binding.cameraView.hasSurface()) {
                    tryOpenUVCCamera(true)
                }
            }

            override fun onConnect(
                device: UsbDevice,
                ctrlBlock: USBMonitor.UsbControlBlock,
                createNew: Boolean
            ) {
                Logger.d("onConnect")
            }

            override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
                Logger.d("onDisconnect")
            }

            override fun onDettach(device: UsbDevice) {
                Logger.d("onDettach")
                queueEvent({
                    if (mCameraClient != null) {
                        mCameraClient?.disconnect()
                        mCameraClient?.release()
                        mCameraClient = null
                    }
                }, 0)
                updateCameraDialog()
            }

            override fun onCancel(device: UsbDevice) {
                Logger.d("onCancel")
            }
        }

    // 相机连接后回调
    private val mCameraListener: ICameraClientCallback = object : ICameraClientCallback {
        override fun onConnect() {
//            mCameraClient!!.addSurface(binding.cameraView.surface, false)
            addSurfaceWithCheck()
            // start UVCService
            val intent = Intent(activity, UVCService::class.java)
            activity.startService(intent)
            activity?.runOnUiThread {
                ToastUtil.show(context, "启动服务")
            }
        }

        override fun onDisconnect() {
//            setPreviewButton(false)
//            enableButtons(false)
        }

        override fun onMarking(index: Int, code: Int) {
            listener?.onMarking(index, code)
        }

        override fun onFpsChange(fps: Int, fpsHandle: Int) {
            listener?.onFpsChange(fps, fpsHandle)
        }
    }

    override fun onAttach(activity: Activity?) {
        super.onAttach(activity)
        if (activity is OnFragmentActionListener) {
            listener = activity
        }
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (mUSBMonitor == null) {
            mUSBMonitor = USBMonitor(activity.applicationContext, mOnDeviceConnectListener)
            val filters = DeviceFilter.getDeviceFilters(activity, R.xml.device_filter)
            mUSBMonitor?.setDeviceFilter(filters)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentUvcBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View?, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cameraView.aspectRatio = (UVCCamera.DEFAULT_PREVIEW_WIDTH / UVCCamera.DEFAULT_PREVIEW_HEIGHT.toFloat()).toDouble()

    }

    override fun onResume() {
        super.onResume()
        mUSBMonitor?.register()
    }

    override fun onPause() {
        removeSurfaceWithCheck()
        super.onPause()
    }

    override fun onDestroy() {
        mUSBMonitor!!.unregister()
        mCameraClient?.release()
        super.onDestroy()
    }

    private fun updateCameraDialog(): Boolean {
        val fragment = fragmentManager.findFragmentByTag("CameraDialog")
        if (fragment is CameraDialog) {
            fragment.updateDevices()
            return true
        }
        return false
    }

    fun addSurfaceWithCheck() {
        Logger.d("addWithCheckSurface")
        if (!hasAddSurface) {
            mCameraClient?.addSurface(binding.cameraView.surface, false)
            hasAddSurface = true
        }
    }

    private fun removeSurfaceWithCheck() {
        if (hasAddSurface) {
            mCameraClient?.removeSurface(binding.cameraView.surface)
            hasAddSurface = false
        }
    }

    private fun tryOpenUVCCamera(requestPermission: Boolean) {
        openUVCCamera()
    }

    private fun openUVCCamera() {
        if (!mUSBMonitor!!.isRegistered) return
//        val list = mUSBMonitor!!.deviceList
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val list = usbManager.deviceList.values
        list.forEach { device ->
            if (device.productId == pid) {
                ToastUtil.show(context, "打开相机${device.productId}")
                if (mCameraClient == null) mCameraClient = CameraClient(activity, packageName, mCameraListener)
                // 确认USB权限，注册回调方法
                mCameraClient!!.select(device)
                mCameraClient!!.resize(Config.CAMERA_WIDTH, Config.CAMERA_HEIGHT)
                // 1. 如果相机已经打开，回调ICameraClientCallback::onConnect方法
                // 2. 如果相机没有打开，调用CameraServer::CameraThread::handleOpen方法创建UVCCamera对象，并打开相机，
                // 同时回调onConnect方法，onConnect方法里面会添加一个Surface到服务端。
                // 接着调用CameraServer::CameraThread::handleStartPreview方法，获得上一步添加的Surface，显示预览画面
                mCameraClient!!.connect()
            }
        }
    }

    fun setExposureMode(mode: Int) {
        mCameraClient?.setExposureMode(mode);
    }

    fun setExposure(exposure: Int) {
        mCameraClient?.exposure = exposure
    }

    fun getExposure(): Int {
        return mCameraClient?.exposure ?: -1
    }

    fun addSurface() {
        mCameraClient?.addSurface(binding.cameraView.surface, false)
    }

    fun stopService() {
        val intent = Intent(activity, UVCService::class.java)
        activity.stopService(intent)
    }

    interface OnFragmentActionListener {
        fun onMarking(index: Int, code: Int)
        fun onFpsChange(fps: Int, fpsHandle: Int)
    }

    companion object {
        private const val ARG_PID = "ARG_PID"
        private const val ARG_POINTS = "ARG_POINTS"
        private const val ARG_PACKAGE_NAME = "ARG_PACKAGE_NAME"

        fun newInstance(pid: Int, packageName: String) = UVCFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_PID, pid)
//                putParcelableArrayList(ARG_POINTS, points)
                putString(ARG_PACKAGE_NAME, packageName)
            }
        }
    }
}