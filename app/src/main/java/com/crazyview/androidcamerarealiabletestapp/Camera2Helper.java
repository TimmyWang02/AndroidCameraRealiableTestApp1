package com.crazyview.androidcamerarealiabletestapp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import com.test.sleeper.Sleeper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Auther: Crazy.Mo
 * DateTime: 2017/9/12 12:11
 * Summary:
 */
public class Camera2Helper {
    private static final String TAG = "Camera2Helper";
    private static Activity activity;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_LOCK = 1;//Camera state: Waiting for the focus to be locked.
    private static final int STATE_WAITING_PRECAPTURE = 2;//Camera state: Waiting for the exposure to be pre capture state.
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;//Camera state: Waiting for the exposure state to be something other than precapture.
    private static final int STATE_PICTURE_TAKEN = 4;//Camera state: Picture was taken.
    private static final int MAX_PREVIEW_WIDTH = 1920;//Max preview width that is guaranteed by Camera2 API
    private static final int MAX_PREVIEW_HEIGHT = 1080;//Max preview height that is guaranteed by Camera2 API
    private AutoFitTextureView textureView;
    private String mCameraId;
    private CameraCaptureSession mCaptureSession;
    private static CameraDevice mCameraDevice;
    private Size mPreviewSize;
    private HandlerThread mBackgroundThread;//An additional thread for running tasks that shouldn't block the UI.
    private Handler mBackgroundHandler;//A {@link Handler} for running tasks in the background.
    private ImageReader mImageReader;
    private static File mFile = null;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);//以防止在关闭相机之前应用程序退出
    private boolean mFlashSupported;
    private int mSensorOrientation;
    private CaptureRequest.Builder mPreviewRequestBuilder;//{@link CaptureRequest.Builder} for the camera preview
    private CaptureRequest mPreviewRequest;//{@link CaptureRequest} generated by {@link #mPreviewRequestBuilder}
    private int mState = STATE_PREVIEW;//{#see mCaptureCallback}The current state of camera state for taking pictures.
    private static CameraManager manager;
    private AfterDoListener listener;
    private boolean isNeedHideProgressbar=true;

    private ConditionVariable cv_onClosed = new ConditionVariable();

    //从屏幕旋转转换为JPEG方向
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    //This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a still image is ready to be saved.
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            // mBackgroundHandler.post(new Camera2Helper.ImageSaver(reader.acquireNextImage(), mFile));
            // mdf by linja 20210918
            Log.i(TAG,"ImageReader.onImageAvailable: acquireNextImage ");
            Image img = reader.acquireNextImage();
            // Image img = reader.acquireLatestImage();
            Log.i(TAG , "saveFileName:" + mFile.getName());
            if(img == null){
                Log.i(TAG,"ImageReader.onImageAvailable: img == null ");
            }
            else{
                mBackgroundHandler.post(new Camera2Helper.ImageSaver(img, mFile));
            }

        }
    };

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            //3.在TextureView可用的时候尝试打开摄像头
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        }
    };

    //实现监听CameraDevice状态回调
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.i(TAG,"CameraDevice.StateCallback.onOpened()");
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();//要想预览、拍照等操作都是需要通过会话来实现，所以创建会话用于预览
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.i(TAG,"CameraDevice.StateCallback.onDisconnected()");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Log.i(TAG,"CameraDevice.StateCallback.onError()");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onClosed(CameraDevice camera){
            super.onClosed(camera);
            Log.i(TAG,"CameraDevice.StateCallback=>onClosed.");
            cv_onClosed.open();
        }
    };

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            Log.i(TAG, "mState:"+mState);
            Log.i(TAG, "afState:"+result.get(CaptureResult.CONTROL_AF_STATE));
            Log.i(TAG, "aeState:"+result.get(CaptureResult.CONTROL_AE_STATE));
            switch (mState) {
                case STATE_PREVIEW: {
                    if(isNeedHideProgressbar) {
                        listener.onAfterPreviewBack();
                        isNeedHideProgressbar=false;
                    }
                    // We have nothing to do when the camera preview is working normally.
                    break;
                }
                case STATE_WAITING_LOCK: {

                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (afState == null) {
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_INACTIVE == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_INACTIVE ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            captureStillPicture();
                        }
                        else {
                            Log.i(TAG, "aeState1111111:"+aeState);
//                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                case STATE_WAITING_PRECAPTURE: {

                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }
                case STATE_WAITING_NON_PRECAPTURE: {
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        captureStillPicture();
                    }
                    break;
                }
                default:
                    break;
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
            Log.i(TAG,"onCaptureProgressed...");
//            super.onCaptureProgressed(session, request, partialResult);
            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            Log.i(TAG,"onCaptureCompleted...");
//            super.onCaptureCompleted(session, request, result);
            process(result);
        }

    };

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */


    private volatile static Camera2Helper singleton;///注意:用volatile修饰的变量，线程在每次使用变量的时候，都会读取变量修改后的最的值

    private Camera2Helper(Activity act, AutoFitTextureView view) {
    }

    private Camera2Helper(Activity act, AutoFitTextureView view, File file, String cameraId) {
        activity = act;
        textureView = view;
        mFile = file;
        mCameraId = cameraId;
        manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    }

    public static Camera2Helper getInstance(Activity act, AutoFitTextureView view, File file, String cameraId) {
        if (singleton == null) {
            synchronized (Camera2Helper.class) {
                singleton = new Camera2Helper(act, view, file, cameraId);
            }
        }
        return singleton;
    }

    /**
     * 开启相机预览界面
     */
    public void startCameraPreView() {
        startBackgroundThread();
        //1、如果TextureView 可用则直接打开相机
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                if (textureView != null) {
                    if (textureView.isAvailable()) {
                        openCamera(textureView.getWidth(), textureView.getHeight());
                    } else {
                        textureView.setSurfaceTextureListener(mSurfaceTextureListener);//设置TextureView 的回调后，当满足之后自动回调到
                    }
                }
            }
        },300);//建议加上尤其是你需要在多个界面都要开启预览界面时候

    }

    /**
     * 开启HandlerThread
     */
    private void startBackgroundThread() {
        Log.i(TAG,"startBackgroundThread...");
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        Log.i(TAG,"stopBackgroundThread()...");
        if (mBackgroundThread == null) {
            return;
        }
        Log.i(TAG,"mBackgroundThread.quit()...");
        mBackgroundThread.quitSafely();
        try {
            Log.i(TAG,"mBackgroundThread...join");
            mBackgroundThread.join(10000);
            mBackgroundThread = null;
            mBackgroundHandler = null;
            Log.i(TAG,"mBackgroundThread...end");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 拍照
     */
    public void takePicture() {
        lockFocus();
    }

    /**
     * 拍照
     */
    public void takePicture(File file,boolean switchFlash) {
        setFlashMode(switchFlash);
        mFile = file;
        Log.i(TAG , "new file name:" + file.getName());
        lockFocus();
    }


    /**
     * 通过会话提交捕获图像的请求，通常在捕获回调回应后调用
     */
    private void captureStillPicture() {
        Log.i(TAG,"captureStillPicture()...");
        try {
            if (null == mCameraDevice) {
                return;
            }
            //创建用于拍照的CaptureRequest.Builder
            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());


            // 使用和预览一样的模式 AE and AF
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(captureBuilder);
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(rotation));


            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    Log.i(TAG,"captureStillPicture()-->onCaptureCompleted()...");
                    //                    unlockFocus();
                    listener.onAfterTakePicture();
                    Log.i(TAG,"onCaptureCompleted " + "保存照片成功");
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                    super.onCaptureFailed(session, request, failure);
                    if(failure.wasImageCaptured()){
                        listener.onAfterTakePicture();
                    }
                    Log.i(TAG,"failure.getReason():"+failure.getReason());
                    Log.i(TAG,"failure.wasImageCaptured():"+failure.wasImageCaptured());
                    Log.i(TAG,"failure.getRequest():"+failure.getRequest());
                    Log.i(TAG,"failure.getFrameNumber():"+failure.getFrameNumber());
                    Log.i(TAG,"failure.getSequenceId():"+failure.getSequenceId());
                    Log.i(TAG,"captureStillPicture()-->onCaptureFailed()...");
                }
            };
            mCaptureSession.stopRepeating();
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        Log.i(TAG,"captureStillPicture()...end");
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        Log.i(TAG,"lockFocus000");
        try {
            long time1 = System.currentTimeMillis();
            while (true) {
                if (System.currentTimeMillis() - time1 > 30000){
                    return;
                }
                if (mCaptureSession == null) {
                    Sleeper.sleep(1000);
                } else {
                    break;
                }
            }
            if (mCaptureSession == null) {
                return;
            }
            Log.i(TAG,"lockFocus");
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        try {
            // Reset the auto-focus trigger
            if (mCaptureSession == null) {
                return;
            }
            Log.i(TAG,"unlockFocus");
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            setAutoFlash(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
            // After this, the camera will go back to the normal state of preview，重新预览
            mState = STATE_PREVIEW;
            mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * 设置自动闪光灯
     *
     * @param requestBuilder
     */
    private void setAutoFlash(CaptureRequest.Builder requestBuilder) {
        if (mFlashSupported) {
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

    /**
     * 从指定的屏幕旋转中检索JPEG方向。
     *
     * @param rotation The screen rotation.
     * @return The JPEG orientation (one of 0, 90, 270, and 360)
     */
    private int getOrientation(int rotation) {
        // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
        // We have to take that into account and rotate JPEG properly.
        // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
        // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
        return (ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            Log.i(TAG,"runPrecaptureSequence...");
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
            Log.i(TAG,"runPrecaptureSequence...end");
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createCameraPreviewSession() {
        Log.i(TAG,"createCameraPreviewSession...");
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            // 将默认缓冲区的大小配置为我们想要的相机预览的大小。
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);
            // set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);// 把显示预览界面的TextureView添加到到CaptureRequest.Builder中
            // create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()), new CameraCaptureSession.StateCallback() {

                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    // The camera is already closed
                    if (null == mCameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    Log.i(TAG,"onConfigured...");
                    mCaptureSession = cameraCaptureSession;
                    try {
                        // 设置自动对焦参数并把参数设置到CaptureRequest.Builder中
                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        //设置闪光灯自动模式
                        setAutoFlash(mPreviewRequestBuilder);

                        // 封装好CaptureRequest.Builder后，调用build 创建封装好CaptureRequest 并发送请求
                        mPreviewRequest = mPreviewRequestBuilder.build();
                        mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback, mBackgroundHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                }

                @Override
                public void onClosed(CameraCaptureSession session){
                    super.onClosed(session);
                    Log.i(TAG,"CameraCaptureSession.StateCallback.onClosed()...");
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setUpCameraOutputs(int width, int height) {

        try {
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

                /*Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                //前置摄像头
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }else if(facing != null && facing == CameraCharacteristics.LENS_FACING_BACK){
                    continue;
                }*/
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new Camera2Helper.CompareSizesByArea());
                mImageReader = ImageReader.newInstance(largest.getWidth(), largest.getHeight(), ImageFormat.JPEG, /*maxImages*/2);//初始化ImageReader
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);//设置ImageReader监听

                //处理图片方向相关
                int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                Log.i(TAG,"displayRotation:"+displayRotation+",mSensorOrientation:"+mSensorOrientation);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                        break;
                }
                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = displaySize.x;
                int maxPreviewHeight = displaySize.y;
                if (swappedDimensions) {
                    Log.i(TAG,"swappedDimensions...");
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = displaySize.y;
                    maxPreviewHeight = displaySize.x;
                }

                if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
                    maxPreviewWidth = MAX_PREVIEW_WIDTH;
                }

                if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
                    maxPreviewHeight = MAX_PREVIEW_HEIGHT;
                }

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, largest);

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = activity.getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    textureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                } else {
                    textureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }
                // 设置是否支持闪光灯
                Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Log.i(TAG, "mFlashSupported:"+available);
                mFlashSupported = available == null ? false : available;
//                mCameraId = cameraId;
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
        }
    }

    /**
     * 为了避免太大的预览大小会超过相机总线的带宽限
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth, int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight && option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth && option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new Camera2Helper.CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new Camera2Helper.CompareSizesByArea());
        } else {
            return choices[0];
        }
    }

    /**
     * 打开指定摄像头
     */
    void openCamera(int width, int height) {

        //4、设置参数
        Log.i(TAG,"openCamera--> width:"+width+",height:"+height);
        setUpCameraOutputs(width, height);
        Log.i(TAG,"configureTransform--> width:"+width+",height:"+height);
        configureTransform(width, height);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            Log.i(TAG,"openCamera--> mCameraId:" + mCameraId);
            String realCameraId = getCameraIdFromId(mCameraId , manager);  // 20220427  因传入时0表示后置，1表示前置 ， 需要实际遍历摄像头来判断对应的cameraid，直接复制是存在问题的
            Log.i(TAG,"openCamera--> realCameraId:" + realCameraId);
            manager.openCamera(realCameraId, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }
    //通过id来获取实际cameraid
    public String getCameraIdFromId(String id , CameraManager manager) throws CameraAccessException {
        for(final String cameraId : manager.getCameraIdList()){
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            int cOrientation = characteristics.get(CameraCharacteristics.LENS_FACING);
            if(cOrientation == CameraCharacteristics.LENS_FACING_FRONT && id.equals("1"))
                return cameraId;
            if(cOrientation == CameraCharacteristics.LENS_FACING_BACK && id.equals("0"))
                return cameraId;
        }
        Log.i(TAG,"no find camera id");  //0表示后置，1表示前置
        return id;
    };

    /**
     * Closes the current {@link CameraDevice}.
     */
    void closeCamera() {
        Log.i(TAG,"closeCamera()...");
        try {
            mCameraOpenCloseLock.acquire();
            Log.i(TAG,"mCameraOpenCloseLock.acquire()...end");
            if (null != mCaptureSession) {
                mCaptureSession.close();
                Log.i(TAG,"mCaptureSession.close()...end");
                mCaptureSession = null;
            }
            Log.i(TAG,"mCameraDevice == null ? "+(null == mCameraDevice));
            if (null != mCameraDevice) {
                mCameraDevice.close();
                Log.i(TAG,"mCameraDevice.close()...end");
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                Log.i(TAG,"mImageReader.close()...end");
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
        Log.i(TAG,"closeCamera()...end");
    }

    /**
     * @author wang zhizhi
     * @date 2024/05/20
     *
     */
    private void setFlashMode(boolean switchFlash) {
        if (mFlashSupported && mPreviewRequestBuilder != null) {
            if (switchFlash) {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            } else {
                mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            }
            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * Configures the necessary {@link Matrix} transformation to `textureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `textureView` is fixed.
     *
     * @param viewWidth  The width of `textureView`
     * @param viewHeight The height of `textureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {

        if (null == textureView || null == mPreviewSize) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    /**
     * 释放Act和View
     */
    public void onDestroyHelper() {
        closeCamera();
        Log.i(TAG,"onDestroyHelper, wait for onClosed...");
        cv_onClosed.block();
        cv_onClosed.close();

        stopBackgroundThread();
        activity = null;
        textureView = null;
        listener = null;
        singleton = null;
    }

    private static class CompareSizesByArea implements Comparator<Size> {

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private static class ImageSaver implements Runnable {

        private final Image mImage;
        private final File mFile;

        public ImageSaver(Image image, File file) {
            mImage = image;
            mFile = file;
        }

        @Override
        public void run() {
            FileOutputStream output = null;
            try {
                Log.i(TAG,"ImageSaver.run:mImage.getPlanes() " + mFile.getPath());
                Image.Plane[] pls = mImage.getPlanes();
                Log.i(TAG,"ImageSaver.run:pls.length=>" + pls.length);
                ByteBuffer buffer = pls[0].getBuffer();
                // ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                Bitmap bitmap = BitmapUtil.bytes2Bitmap(bytes);//原始的转为Bitmap
                Bitmap bitmapAfter = BitmapUtil.compressBitmapBySampleSize(bitmap, 4);//压缩
                byte[] bytesAfter = BitmapUtil.bitmap2Bytes(bitmapAfter);//压缩后的Bitmap 转为Bytes
                output = new FileOutputStream(mFile);
                output.write(bytesAfter);//写到文件输出流

                Log.i(TAG,"ImageSaver.run:write=>end");
            } catch (Exception e) {
                Log.i(TAG, e.getMessage().toString());
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.flush();
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            Log.i(TAG,"ImageSaver.run:=>end");
        }
    }

    public void setAfterDoListener(AfterDoListener listener){
        this.listener=listener;
    }

    public interface AfterDoListener {
        void onAfterPreviewBack();

        void onAfterTakePicture();
    }
}
