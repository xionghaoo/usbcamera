/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package xh.rabbit.usbcamera.uvc.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.serenegiant.encoder.MediaAudioEncoder;
import com.serenegiant.encoder.MediaEncoder;
import com.serenegiant.encoder.MediaMuxerWrapper;
import com.serenegiant.encoder.MediaSurfaceEncoder;
import com.serenegiant.glutils.RenderHolderCallback;
import com.serenegiant.glutils.RendererHolder;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.Size;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import xh.rabbit.usbcamera.Config;
import xh.rabbit.usbcamera.IUVCServiceCallback;
import xh.rabbit.usbcamera.IUVCServiceOnFrameAvailable;
import xh.rabbit.usbcamera.R;

public final class CameraServer extends Handler {
	private static final boolean DEBUG = true;
	private static final String TAG = "CameraServer";

	private static final int DEFAULT_WIDTH = Config.CAMERA_WIDTH;
	private static final int DEFAULT_HEIGHT = Config.CAMERA_HEIGHT;
	
	private int mFrameWidth = DEFAULT_WIDTH, mFrameHeight = DEFAULT_HEIGHT;
	
    private static class CallbackCookie {
		boolean isConnected;
	}

    private final RemoteCallbackList<IUVCServiceCallback> mCallbacks
		= new RemoteCallbackList<IUVCServiceCallback>();
    private int mRegisteredCallbackCount;

	private RendererHolder mRendererHolder;
	private final WeakReference<CameraThread> mWeakThread;

	public static CameraServer createServer(
			final Context context,
			final UsbControlBlock ctrlBlock,
			final int vid,
			final int pid
	) {
		if (DEBUG) Log.d(TAG, "createServer:");
		final CameraThread thread = new CameraThread(context, ctrlBlock);
		thread.start();
		return thread.getHandler();
	}

	private CameraServer(final CameraThread thread) {
		if (DEBUG) Log.d(TAG, "Constructor:");
		mWeakThread = new WeakReference<CameraThread>(thread);
		mRegisteredCallbackCount = 0;
		mRendererHolder = new RendererHolder(mFrameWidth, mFrameHeight, mRenderHolderCallback);
	}

	@Override
	protected void finalize() throws Throwable {
		if (DEBUG) Log.i(TAG, "finalize:");
		release();
		super.finalize();
	}

	public void registerCallback(final IUVCServiceCallback callback) {
		if (DEBUG) Log.d(TAG, "registerCallback:");
		mCallbacks.register(callback, new CallbackCookie());
		mRegisteredCallbackCount++;
	}

	public boolean unregisterCallback(final IUVCServiceCallback callback) {
		if (DEBUG) Log.d(TAG, "unregisterCallback:");
		mCallbacks.unregister(callback);
		mRegisteredCallbackCount--;
		if (mRegisteredCallbackCount < 0) mRegisteredCallbackCount = 0;
		return mRegisteredCallbackCount == 0;
	}

	public void release() {
		if (DEBUG) Log.d(TAG, "release:");
		disconnect();
		mCallbacks.kill();
		if (mRendererHolder != null) {
			mRendererHolder.release();
			mRendererHolder = null;
		}
	}

//********************************************************************************
//********************************************************************************
	public void resize(final int width, final int height) {
		if (DEBUG) Log.d(TAG, String.format("resize(%d,%d)", width, height));
		if (!isRecording()) {
			mFrameWidth = width;
			mFrameHeight = height;
			if (mRendererHolder != null) {
				mRendererHolder.resize(width, height);
			}
		}
	}
	
	public void connect() {
		if (DEBUG) Log.d(TAG, "connect:");
		final CameraThread thread = mWeakThread.get();
		if (!thread.isCameraOpened()) {
			sendMessage(obtainMessage(MSG_OPEN));
			sendMessage(obtainMessage(MSG_PREVIEW_START, mFrameWidth, mFrameHeight, mRendererHolder.getSurface()));
		} else {
			if (DEBUG) Log.d(TAG, "already connected, just call callback");
			processOnCameraStart();
		}
	}

	public void connectSlave() {
		if (DEBUG) Log.d(TAG, "connectSlave:");
		final CameraThread thread = mWeakThread.get();
		if (thread.isCameraOpened()) {
			processOnCameraStart();
		}
	}

	public void disconnect() {
		if (DEBUG) Log.d(TAG, "disconnect:");
		stopRecording();
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		synchronized (thread.mSync) {
			sendEmptyMessage(MSG_PREVIEW_STOP);
			sendEmptyMessage(MSG_CLOSE);
			// wait for actually preview stopped to avoid releasing Surface/SurfaceTexture
			// while preview is still running.
			// therefore this method will take a time to execute
			try {
				thread.mSync.wait();
			} catch (final InterruptedException e) {
			}
		}
	}

	public boolean isConnected() {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isCameraOpened();
	}

	public boolean isRecording() {
		final CameraThread thread = mWeakThread.get();
		return (thread != null) && thread.isRecording();
	}

	public void addSurface(final int id, final Surface surface, final boolean isRecordable, final IUVCServiceOnFrameAvailable onFrameAvailableListener) {
		if (DEBUG) Log.d(TAG, "addSurface:id=" + id +",surface=" + surface);
		if (mRendererHolder != null)
			mRendererHolder.addSurface(id, surface, isRecordable);
	}

	public void removeSurface(final int id) {
		if (DEBUG) Log.d(TAG, "removeSurface:id=" + id);
		if (mRendererHolder != null)
			mRendererHolder.removeSurface(id);
	}

	public void startRecording() {
		if (!isRecording())
			sendEmptyMessage(MSG_CAPTURE_START);
	}

	public void stopRecording() {
		if (isRecording())
			sendEmptyMessage(MSG_CAPTURE_STOP);
	}

	public void captureStill(final String path) {
		if (mRendererHolder != null) {
			mRendererHolder.captureStill(path);
			sendMessage(obtainMessage(MSG_CAPTURE_STILL, path));
		}
	}

	public void setExposureMode(int mode) {
		if (mRendererHolder != null) {
			sendMessage(obtainMessage(MSG_SET_EXPOSURE_MODE, mode));
		}
	}

	public void setExposure(int exposure) {
		if (mRendererHolder != null) {
			sendMessage(obtainMessage(MSG_SET_EXPOSURE, exposure));
		}
	}

	public int getExposure() {
		final CameraThread thread = mWeakThread.get();
		if (thread != null) {
			return thread.getExposure();
		} else {
			return -1;
		}
	}

	public void setTouchMask(int x, int y, int width, int height, boolean isMask) {
		if (mRendererHolder != null) {
			Bundle data = new Bundle();
			data.putInt("x", x);
			data.putInt("y", y);
			data.putInt("w", width);
			data.putInt("h", height);
			data.putBoolean("isMask", isMask);
			Message msg = obtainMessage(MSG_SET_TOUCH_MASK);
			msg.setData(data);
			sendMessage(msg);
		}
	}

//********************************************************************************
	private void processOnCameraStart() {
		if (DEBUG) Log.d(TAG, "processOnCameraStart:");
		try {
			final int n = mCallbacks.beginBroadcast();
			for (int i = 0; i < n; i++) {
				if (!((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected) {
					try {
						mCallbacks.getBroadcastItem(i).onConnected();
						((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected = true;
					} catch (final Exception e) {
						Log.e(TAG, "failed to call IOverlayCallback#onFrameAvailable");
					}
				}
			}
			mCallbacks.finishBroadcast();
		} catch (final Exception e) {
			Log.w(TAG, e);
		}
	}

	private void processOnCameraStop() {
		if (DEBUG) Log.d(TAG, "processOnCameraStop:");
		final int n = mCallbacks.beginBroadcast();
		for (int i = 0; i < n; i++) {
			if (((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected) {
				try {
					mCallbacks.getBroadcastItem(i).onDisConnected();
					((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected = false;
				} catch (final Exception e) {
					Log.e(TAG, "failed to call IOverlayCallback#onDisConnected");
				}
			}
		}
		mCallbacks.finishBroadcast();
	}

	private void processOnMarking(int index, int code) {
		final int n = mCallbacks.beginBroadcast();
		for (int i = 0; i < n; i++) {
			if (((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected) {
				try {
					mCallbacks.getBroadcastItem(i).onMarking(index, code);
				} catch (final Exception e) {
					Log.e(TAG, "failed to call IOverlayCallback#processOnMarking");
				}
			}
		}
		mCallbacks.finishBroadcast();
	}

	private void processOnFpsChange(int fps, int fpsHandle) {
		final int n = mCallbacks.beginBroadcast();
		for (int i = 0; i < n; i++) {
			if (((CallbackCookie)mCallbacks.getBroadcastCookie(i)).isConnected) {
				try {
					mCallbacks.getBroadcastItem(i).onFpsChange(fps, fpsHandle);
				} catch (final Exception e) {
					Log.e(TAG, "failed to call IOverlayCallback#processOnFpsChange");
				}
			}
		}
		mCallbacks.finishBroadcast();
	}

//**********************************************************************
	private static final int MSG_OPEN = 0;
	private static final int MSG_CLOSE = 1;
	private static final int MSG_PREVIEW_START = 2;
	private static final int MSG_PREVIEW_STOP = 3;
	private static final int MSG_CAPTURE_STILL = 4;
	private static final int MSG_CAPTURE_START = 5;
	private static final int MSG_CAPTURE_STOP = 6;
	private static final int MSG_MEDIA_UPDATE = 7;
	private static final int MSG_RELEASE = 9;
	private static final int MSG_SET_EXPOSURE_MODE = 10;
	private static final int MSG_SET_EXPOSURE = 11;
	private static final int MSG_SET_TOUCH_MASK = 12;

	@Override
	public void handleMessage(final Message msg) {
		final CameraThread thread = mWeakThread.get();
		if (thread == null) return;
		switch (msg.what) {
		case MSG_OPEN:
			thread.handleOpen();
			break;
		case MSG_CLOSE:
			thread.handleClose();
			break;
		case MSG_PREVIEW_START:
			thread.handleStartPreview(msg.arg1, msg.arg2, (Surface)msg.obj);
			break;
		case MSG_PREVIEW_STOP:
			thread.handleStopPreview();
			break;
		case MSG_CAPTURE_STILL:
			thread.handleCaptureStill((String)msg.obj);
			break;
		case MSG_CAPTURE_START:
			thread.handleStartRecording();
			break;
		case MSG_CAPTURE_STOP:
			thread.handleStopRecording();
			break;
		case MSG_MEDIA_UPDATE:
			thread.handleUpdateMedia((String)msg.obj);
			break;
		case MSG_RELEASE:
			thread.handleRelease();
			break;
		case MSG_SET_EXPOSURE_MODE:
			thread.setExposureMode((int)msg.obj);
			break;
		case MSG_SET_EXPOSURE:
			thread.setExposure((int)msg.obj);
			break;
		case MSG_SET_TOUCH_MASK:
			Bundle data = msg.getData();
			int x = data.getInt("x");
			int y = data.getInt("y");
			int w = data.getInt("w");
			int h = data.getInt("h");
			boolean isMask = data.getBoolean("isMask");
			thread.setTouchMask(x, y, w, h, isMask);
			break;
		default:
			throw new RuntimeException("unsupported message:what=" + msg.what);
		}
	}

	private final RenderHolderCallback mRenderHolderCallback
		= new RenderHolderCallback() {
		@Override
		public void onCreate(final Surface surface) {
		}

		@Override
		public void onFrameAvailable() {
			final CameraThread thread = mWeakThread.get();
			if ((thread != null) && (thread.mVideoEncoder != null)) {
				try {
					thread.mVideoEncoder.frameAvailableSoon();
				} catch (final Exception e) {
					//
				}
			}
		}

		@Override
		public void onDestroy() {
		}
	};

	private static final class CameraThread extends Thread {
		private static final String TAG_THREAD = "CameraThread";
		private final Object mSync = new Object();
		private boolean mIsRecording;
	    private final WeakReference<Context> mWeakContext;
		private int mEncoderSurfaceId;
		private int mFrameWidth, mFrameHeight;

		private static final int FIX_FPS = Config.DEFAULT_FPS;
		private static final int FPS_MIN = FIX_FPS;
		private static final int FPS_MAX = FIX_FPS;
		private static final int FACTOR = 1;

		private long lastFrameTime = 0;
		private long lastHandleTime = 0;
		private int frameCount = 0;
		private int frameCountHandle = 0;
		private int fps = 0;
		private int fpsHandle = 0;
		private int w = 1920;
		private int h = 1080;
		/**
		 * shutter sound
		 */
		private SoundPool mSoundPool;
		private int mSoundId;
		private CameraServer mHandler;
		private UsbControlBlock mCtrlBlock;
		/**
		 * for accessing UVC camera
		 */
		private volatile UVCCamera mUVCCamera;
		/**
		 * muxer for audio/video recording
		 */
		private MediaMuxerWrapper mMuxer;
		private MediaSurfaceEncoder mVideoEncoder;

//		private final PreferenceStorage prefs;

		private Bitmap framebuffer;
//		private TouchManager touchManager = TouchManager.Companion.instance();
		private int currentMarkIndex = 0;
		private int runMode = 1;
		private int markerMaxIndex = 0;

		private CameraThread(final Context context, final UsbControlBlock ctrlBlock) {
			super("CameraThread");
			if (DEBUG) Log.d(TAG_THREAD, "Constructor:");
			mWeakContext = new WeakReference<Context>(context);
			mCtrlBlock = ctrlBlock;
			loadShutterSound(context);
//			prefs = new SharedPreferenceStorage(context);

//			File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
//
//			ArrayList<PointF> points = null;
//			// 保存points
//			if (pointArr != null) {
//				String keyPointStr = new Gson().toJson(pointArr);
//				File pointFile = new File(downloadDir, "MarkPoints.json");
//				try {
//					FileWriter writer = new FileWriter(pointFile);
//					writer.write(keyPointStr);
//					writer.flush();
//					writer.close();
//				} catch (Exception e) {
//					Log.e(TAG_THREAD, "Points save failure");
//					e.printStackTrace();
//				}
//				points = pointArr;
//			} else {
//				try {
//					File pointFile = new File(downloadDir, "MarkPoints.json");
//					String pointStr = FileUtil.Companion.readFile(pointFile);
//					Type listType = new TypeToken<List<PointF>>() {}.getType();
//					points = (new Gson()).fromJson(pointStr, listType);
//				} catch (Exception e) {
//					e.printStackTrace();
//				}
//			}

//			if (points == null) {
//				return;
//			}
//			markerMaxIndex = points.size() - 1;
//			Log.d(TAG_THREAD, "received points: " + points.size());
//			// 初始化触控程序
//			touchManager.initialTouchPanel(points, w, h);
//
//			if (MarkUtil.Companion.isRunMode()) {
//				runMode = 2;
//			} else {
//				runMode = 1;
//			}
//			touchManager.setCurrentMode(runMode);

//			touchManager.setCallback((index, code) -> {
//				mHandler.processOnMarking(currentMarkIndex, code);
//
//				switch (code) {
//					case 1606: {
//						if (currentMarkIndex == markerMaxIndex) {
//							touchManager.setCurrentMode(2);
//						}
//						break;
//					}
//					case 1600: {
//						// 处理UI
//						break;
//					}
//					case 1: {
//						if (currentMarkIndex == markerMaxIndex) {
//							// 4个点标定完成
//							// 显示等待动画
//						} else {
//							currentMarkIndex = index + 1;
//							touchManager.setMarkIndex(currentMarkIndex);
//						}
//						break;
//					}
//				}
//
//			});
//
//			// 初始化标定
//			touchManager.setMarkIndex(currentMarkIndex);
		}

		@Override
		protected void finalize() throws Throwable {
			Log.i(TAG_THREAD, "CameraThread#finalize");
			super.finalize();
		}

		public CameraServer getHandler() {
			if (DEBUG) Log.d(TAG_THREAD, "getHandler:");
			synchronized (mSync) {
				if (mHandler == null) {
					try {
						mSync.wait();
					} catch (final InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
			return mHandler;
		}

		public boolean isCameraOpened() {
			return mUVCCamera != null;
		}

		public boolean isRecording() {
			return (mUVCCamera != null) && (mMuxer != null);
		}

		public void handleOpen() {
			if (DEBUG) Log.d(TAG_THREAD, "handleOpen:");
			handleClose();
			synchronized (mSync) {
				mUVCCamera = new UVCCamera();
				Log.d(TAG, "open product id: " + mCtrlBlock.getDevice().getProductId() + "， " + mCtrlBlock.getDevice().getDeviceName());
//				Timber.d("fps: %s", mUVCCamera.getPowerlineFrequency());
				mUVCCamera.open(mCtrlBlock);
				if (DEBUG) Log.i(TAG, "supportedSize:" + mUVCCamera.getSupportedSize());
			}
			mHandler.processOnCameraStart();
		}

		public void handleClose() {
			if (DEBUG) Log.d(TAG_THREAD, "handleClose:");
			handleStopRecording();
			boolean closed = false;
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
					mUVCCamera.destroy();
					mUVCCamera = null;
					closed = true;
				}
				mSync.notifyAll();
			}
			if (closed)
				mHandler.processOnCameraStop();
			if (DEBUG) Log.d(TAG_THREAD, "handleClose:finished");
		}

		public void handleStartPreview(final int width, final int height, final Surface surface) {
			if (DEBUG) Log.d(TAG_THREAD, "handleStartPreview:");
			synchronized (mSync) {
				if (mUVCCamera == null) return;
				try {
					mUVCCamera.setPreviewSize(width, height, FPS_MIN, FPS_MAX, UVCCamera.FRAME_FORMAT_MJPEG,FACTOR);
				} catch (final IllegalArgumentException e) {
					try {
						// fallback to YUV mode
						mUVCCamera.setPreviewSize(width, height, FPS_MIN, FPS_MAX, UVCCamera.DEFAULT_PREVIEW_MODE, FACTOR);
					} catch (final IllegalArgumentException e1) {
						mUVCCamera.destroy();
						mUVCCamera = null;
					}
				}
				if (mUVCCamera == null) return;
				mFrameWidth = width;
				mFrameHeight = height;
				mUVCCamera.setPreviewDisplay(surface);
				mUVCCamera.startPreview();
				mUVCCamera.updateCameraParams();
				mUVCCamera.setFrameCallback(mIFrameCallback, UVCCamera.PIXEL_FORMAT_RGB565);
				Log.d(TAG, "end start preview");

//				File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
				// 设置曝光度
//				File f = new File(downloadDir, "uvc_config.json");
//				if (f.exists()) {
//					try {
//						String configStr = FileUtil.Companion.readFile(f);
//						UVCConfig config = new Gson().fromJson(configStr, UVCConfig.class);
//						Log.d(TAG_THREAD, "setExposure from uvc_config: " + config.getExposure());
//						setExposure(config.getExposure());
//					} catch (Exception e) {
//						e.printStackTrace();
//					}
//				}
			}
		}

		public void handleStopPreview() {
			if (DEBUG) Log.d(TAG_THREAD, "handleStopPreview:");
			synchronized (mSync) {
				if (mUVCCamera != null) {
					mUVCCamera.stopPreview();
				}
			}
		}

		private void handleResize(final int width, final int height, final Surface surface) {
			synchronized (mSync) {
				if (mUVCCamera != null) {
					final Size sz = mUVCCamera.getPreviewSize();
					if ((sz != null) && ((width != sz.width) || (height != sz.height))) {
						mUVCCamera.stopPreview();
						try {
							mUVCCamera.setPreviewSize(width, height);
						} catch (final IllegalArgumentException e) {
							try {
								mUVCCamera.setPreviewSize(sz.width, sz.height);
							} catch (final IllegalArgumentException e1) {
								// unexpectedly #setPreviewSize failed
								mUVCCamera.destroy();
								mUVCCamera = null;
							}
						}
						if (mUVCCamera == null) return;
						mFrameWidth = width;
						mFrameHeight = height;
						mUVCCamera.setPreviewDisplay(surface);
						mUVCCamera.startPreview();
					}
				}
			}
		}
		
		public void handleCaptureStill(final String path) {
			if (DEBUG) Log.d(TAG_THREAD, "handleCaptureStill:");

			mSoundPool.play(mSoundId, 0.2f, 0.2f, 0, 0, 1.0f);	// play shutter sound
		}

		public void handleStartRecording() {
			if (DEBUG) Log.d(TAG_THREAD, "handleStartRecording:");
			try {
				if ((mUVCCamera == null) || (mMuxer != null)) return;
				mMuxer = new MediaMuxerWrapper(".mp4");	// if you record audio only, ".m4a" is also OK.
//				new MediaSurfaceEncoder(mFrameWidth, mFrameHeight, mMuxer, mMediaEncoderListener);
				new MediaSurfaceEncoder(mMuxer, mFrameWidth, mFrameHeight, mMediaEncoderListener);
				if (true) {
					// for audio capturing
					new MediaAudioEncoder(mMuxer, mMediaEncoderListener);
				}
				mMuxer.prepare();
				mMuxer.startRecording();
			} catch (final IOException e) {
				Log.e(TAG, "startCapture:", e);
			}
		}

		public void handleStopRecording() {
			if (DEBUG) Log.d(TAG_THREAD, "handleStopRecording:mMuxer=" + mMuxer);
			if (mMuxer != null) {
				synchronized (mSync) {
					if (mUVCCamera != null) {
						mUVCCamera.stopCapture();
					}
				}
				mMuxer.stopRecording();
				mMuxer = null;
				// you should not wait here
			}
		}

		public void handleUpdateMedia(final String path) {
			if (DEBUG) Log.d(TAG_THREAD, "handleUpdateMedia:path=" + path);
			final Context context = mWeakContext.get();
			if (context != null) {
				try {
					if (DEBUG) Log.i(TAG, "MediaScannerConnection#scanFile");
					MediaScannerConnection.scanFile(context, new String[]{ path }, null, null);
				} catch (final Exception e) {
					Log.e(TAG, "handleUpdateMedia:", e);
				}
			} else {
				Log.w(TAG, "MainActivity already destroyed");
				// give up to add this movice to MediaStore now.
				// Seeing this movie on Gallery app etc. will take a lot of time.
				handleRelease();
			}
		}

		public void handleRelease() {
			if (DEBUG) Log.d(TAG_THREAD, "handleRelease:");
			handleClose();
			if (mCtrlBlock != null) {
				mCtrlBlock.close();
				mCtrlBlock = null;
			}
			if (!mIsRecording)
				Looper.myLooper().quit();
		}

		public void setExposureMode(int mode) {
			mUVCCamera.setExposureMode(mode);
		}

		public void setExposure(int exposure) {
			mUVCCamera.setExposure(exposure);
		}

		public void setTouchMask(int x, int y, int width, int height, boolean isMask) {
			Log.d(TAG_THREAD, "setTouchMask: "+x+","+y+","+width+","+height+","+isMask);
//			touchManager.setMaskArea(x, y, width, height, isMask);
		}

		public int getExposure() {
			return mUVCCamera.getExposure();
		}

		private final IFrameCallback mIFrameCallback = frame -> {
			// 处理前帧率
			if (runMode == 1) {
				frameCount ++;
				// 标定模式
				if (lastHandleTime == 0) lastHandleTime = System.currentTimeMillis();
				if (lastFrameTime == 0) lastFrameTime = System.currentTimeMillis();

				if (frameCount >= FIX_FPS) {
					long curTime = System.currentTimeMillis();
					fps = (int) (((float) frameCount) / (curTime - lastFrameTime) * 1000f);
					frameCount = 0;
					lastFrameTime = 0;
				}
			}
			// ----------- 业务处理 start ----------------
			try {
				if (framebuffer == null) {
//					Timber.d("--------- frame callback create bitmap -------------");
					framebuffer = Bitmap.createBitmap(mFrameWidth, mFrameHeight, Bitmap.Config.RGB_565);
				}
				framebuffer.copyPixelsFromBuffer(frame);
			} catch (Exception e) {
				e.printStackTrace();
			}
//			if (runMode == 2 && frameCount > 600) {
//				Timber.d("--------- frame callback is running -------------");
//				frameCount = 0;
//			}
			// 处理帧
//			touchManager.process(framebuffer);
			// ----------- 业务处理 end ----------------
			// 处理后帧率
			if (runMode == 1) {
				frameCountHandle ++;
				if (frameCountHandle >= FIX_FPS) {
					long curTime = System.currentTimeMillis();
					fpsHandle = (int) (((float) frameCountHandle) / (curTime - lastHandleTime) * 1000f);
					frameCountHandle = 0;
					lastHandleTime = 0;
					mHandler.processOnFpsChange(fps, fpsHandle);
				}
			}
		};

		public static Bitmap yuv2Bmp(byte[] data, int width, int height) {
			ByteArrayOutputStream baos;
			byte[] rawImage;
			Bitmap bitmap;
			BitmapFactory.Options newOpts = new BitmapFactory.Options();
			newOpts.inJustDecodeBounds = true;
			YuvImage yuvimage = new YuvImage(
					data,
					ImageFormat.NV21,
					width,
					height,
					null);
			baos = new ByteArrayOutputStream();
			yuvimage.compressToJpeg(new Rect(0, 0, width, height), 100, baos);
			rawImage = baos.toByteArray();
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inPreferredConfig = Bitmap.Config.ARGB_8888;
			bitmap = BitmapFactory.decodeByteArray(rawImage, 0, rawImage.length, options);
			return bitmap;
		}

		private final IUVCServiceOnFrameAvailable mOnFrameAvailable = new IUVCServiceOnFrameAvailable() {
			@Override
			public IBinder asBinder() {
				if (DEBUG) Log.d(TAG_THREAD, "asBinder:");
				return null;
			}
			@Override
			public void onFrameAvailable() throws RemoteException {
//				if (DEBUG) Log.d(TAG_THREAD, "onFrameAvailable:");
				if (mVideoEncoder != null)
					mVideoEncoder.frameAvailableSoon();
			}
		};

		private final MediaEncoder.MediaEncoderListener mMediaEncoderListener = new MediaEncoder.MediaEncoderListener() {
			@Override
			public void onPrepared(final MediaEncoder encoder) {
				if (DEBUG) Log.d(TAG, "onPrepared:encoder=" + encoder);
				mIsRecording = true;
				if (encoder instanceof MediaSurfaceEncoder)
				try {
					mVideoEncoder = (MediaSurfaceEncoder)encoder;
					final Surface encoderSurface = mVideoEncoder.getInputSurface();
					mEncoderSurfaceId = encoderSurface.hashCode();
					mHandler.mRendererHolder.addSurface(mEncoderSurfaceId, encoderSurface, true);
				} catch (final Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
			}

			@Override
			public void onStopped(final MediaEncoder encoder) {
				if (DEBUG) Log.v(TAG_THREAD, "onStopped:encoder=" + encoder);
				if ((encoder instanceof MediaSurfaceEncoder))
				try {
					mIsRecording = false;
					if (mEncoderSurfaceId > 0) {
						try {
							mHandler.mRendererHolder.removeSurface(mEncoderSurfaceId);
						} catch (final Exception e) {
							Log.w(TAG, e);
						}
					}
					mEncoderSurfaceId = -1;
					synchronized (mSync) {
						if (mUVCCamera != null) {
							mUVCCamera.stopCapture();
						}
					}
					mVideoEncoder = null;
					final String path = encoder.getOutputPath();
					if (!TextUtils.isEmpty(path)) {
						mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_MEDIA_UPDATE, path), 1000);
					}
				} catch (final Exception e) {
					Log.e(TAG, "onPrepared:", e);
				}
			}
		};

		/**
		 * prepare and load shutter sound for still image capturing
		 */
		@SuppressWarnings("deprecation")
		private void loadShutterSound(final Context context) {
			if (DEBUG) Log.d(TAG_THREAD, "loadShutterSound:");
	    	// get system stream type using refrection
	        int streamType;
	        try {
	            final Class<?> audioSystemClass = Class.forName("android.media.AudioSystem");
	            final Field sseField = audioSystemClass.getDeclaredField("STREAM_SYSTEM_ENFORCED");
	            streamType = sseField.getInt(null);
	        } catch (final Exception e) {
	        	streamType = AudioManager.STREAM_SYSTEM;	// set appropriate according to your app policy
	        }
	        if (mSoundPool != null) {
	        	try {
	        		mSoundPool.release();
	        	} catch (final Exception e) {
	        	}
	        	mSoundPool = null;
	        }
	        // load sutter sound from resource
		    mSoundPool = new SoundPool(2, streamType, 0);
		    mSoundId = mSoundPool.load(context, R.raw.camera_click, 1);
		}

		@Override
		public void run() {
			if (DEBUG) Log.d(TAG_THREAD, "run:");
			Looper.prepare();
			synchronized (mSync) {
				mHandler = new CameraServer(this);
				mSync.notifyAll();
			}
			Looper.loop();
			synchronized (mSync) {
				mHandler = null;
				mSoundPool.release();
				mSoundPool = null;
				mSync.notifyAll();
			}
			if (DEBUG) Log.d(TAG_THREAD, "run:finished");
		}
	}

}
