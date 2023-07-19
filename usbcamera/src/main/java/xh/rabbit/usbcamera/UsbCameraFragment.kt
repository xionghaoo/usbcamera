package xh.rabbit.usbcamera

import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.serenegiant.usb.CameraDialog
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usbcameracommon.UVCCameraHandler
import com.serenegiant.utils.ThreadPool.queueEvent
import com.serenegiant.utils.UIThreadHelper.runOnUiThread
import xh.rabbit.usbcamera.databinding.FragmentUsbCameraBinding


// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

/**
 * A simple [Fragment] subclass.
 * Use the [UsbCameraFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class UsbCameraFragment : Fragment() {

    private lateinit var binding: FragmentUsbCameraBinding

    private var param1: String? = null
    private var param2: String? = null
    private var mSurface: Surface? = null

    /**
     * for accessing USB
     */
    private var mUSBMonitor: USBMonitor? = null

    /**
     * Handler to execute camera releated methods sequentially on private thread
     */
    private var mCameraHandler: UVCCameraHandler? = null

    private val mOnDeviceConnectListener: OnDeviceConnectListener =
        object : OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice) {
                Toast.makeText(context, "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT).show()
            }

            override fun onConnect(
                device: UsbDevice,
                ctrlBlock: UsbControlBlock,
                createNew: Boolean
            ) {
                if (DEBUG) Log.v(TAG, "onConnect:")
                mCameraHandler!!.open(ctrlBlock)
                startPreview()
            }

            override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
                if (DEBUG) Log.v(TAG, "onDisconnect:")
                if (mCameraHandler != null) {
                    mCameraHandler!!.close()
//                    setCameraButton(false)
                }
            }

            override fun onDettach(device: UsbDevice) {
                Toast.makeText(context, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show()
            }

            override fun onCancel(device: UsbDevice) {}
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentUsbCameraBinding.inflate(inflater, container, false)
        // Inflate the layout for this fragment
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mUSBMonitor = USBMonitor(context, mOnDeviceConnectListener)
        mCameraHandler = UVCCameraHandler.createHandler(
            activity, binding.cameraView,
            2, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE
        )
    }

    override fun onStart() {
        super.onStart()
        if (DEBUG) Log.v(TAG, "onStart:");
        mUSBMonitor?.register();
        binding.cameraView.onResume()
    }

    override fun onStop() {
        if (DEBUG) Log.v(TAG, "onStop:")
        queueEvent(Runnable { mCameraHandler?.close() })
        binding.cameraView.onPause()
//        setCameraButton(false)
//        mCaptureButton.setVisibility(View.INVISIBLE)
        mUSBMonitor?.unregister()
        super.onStop()
    }

    override fun onDestroy() {
        if (DEBUG) Log.v(TAG, "onDestroy:");
        if (mCameraHandler != null) {
            mCameraHandler?.release()
            mCameraHandler = null
        }
        if (mUSBMonitor != null) {
            mUSBMonitor?.destroy()
            mUSBMonitor = null
        }
//        mUVCCameraView = null;
//        mCameraButton = null;
//        mCaptureButton = null;
        super.onDestroy()
    }

    private fun startPreview() {
        val st: SurfaceTexture? = binding.cameraView.surfaceTexture
        if (mSurface != null) {
            mSurface?.release()
        }
        mSurface = Surface(st)
//        mCameraHandler?.addCallback(object : Camer)
        mCameraHandler?.startPreview(mSurface)
//        runOnUiThread(Runnable { mCaptureButton.setVisibility(View.VISIBLE) })
    }

//    protected fun checkPermissionResult(requestCode: Int, permission: String?, result: Boolean) {
//        super.checkPermissionResult(requestCode, permission, result)
//        if (!result && permission != null) {
//            setCameraButton(false)
//        }
//    }

//    override fun getUSBMonitor(): USBMonitor {
//        return usbMonitor
//    }

//    override fun onDialogResult(canceled: Boolean) {
//        if (canceled) {
//            setCameraButton(false);
//        }
//    }

    companion object {

        private const val TAG = "UsbCameraFragment"
        private var DEBUG = false

        /**
         * preview resolution(width)
         * if your camera does not support specific resolution and mode,
         * [UVCCamera.setPreviewSize] throw exception
         */
        private const val PREVIEW_WIDTH = 640

        /**
         * preview resolution(height)
         * if your camera does not support specific resolution and mode,
         * [UVCCamera.setPreviewSize] throw exception
         */
        private const val PREVIEW_HEIGHT = 480

        /**
         * preview mode
         * if your camera does not support specific resolution and mode,
         * [UVCCamera.setPreviewSize] throw exception
         * 0:YUYV, other:MJPEG
         */
        private const val PREVIEW_MODE = UVCCamera.FRAME_FORMAT_MJPEG

        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment UsbCameraFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            UsbCameraFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }
}