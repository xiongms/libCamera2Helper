package com.xiongms.libcamera2helper

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.util.Log
import android.util.Range
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.xiongms.libcamera2helper.utils.CompareSizesByArea
import com.xiongms.libcamera2helper.utils.ImageUtil
import com.xiongms.libcamera2helper.view.AutoFitTextureView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Time:2019/7/8
 * Author:xiongms
 * Description: 摄像头相关工具类
 */

class CameraHelper(lifecycleOwner: LifecycleOwner, val textureView: AutoFitTextureView) :
    LifecycleObserver {

    private val context: Context = textureView.context

    /**
     * 相机设备
     */
    private var mCameraDevice: CameraDevice? = null

    /**
     * 请求的Session
     */
    private var previewCameraCaptureSession: CameraCaptureSession? = null


    private var startRecordTime: Long = 0

    /**
     * 预览的尺寸
     */
    private var mPreviewSize: Size? = null

    /**
     * MediaRecorder的录像尺寸
     */
    private var mVideoSize: Size? = null

    /**
     * MediaRecorder对象
     */
    private var mMediaRecorder: MediaRecorder? = null

    /**
     * 判断是否正在录像
     */
    private var mIsRecordingVideo: Boolean = false

    /**
     * 开启一个后台线程,防止阻塞主UI
     */
    private var mBackgroundThread: HandlerThread? = null

    /**
     * 后台线程的Handler
     */
    private var mBackgroundHandler: Handler? = null

    /**
     * 相机锁
     */
    private val mCameraOpenCloseLock = Semaphore(1)

    /**
     * 设备传感器的方向
     */
    private var mSensorOrientation: Int? = null


    /**
     * 录像存储文件_临时
     */
    private var videoTempFile: File = File(context.externalCacheDir!!.absolutePath, VIDEO_TEMP_FILE)
    /**
     * 相机管理
     */
    private var mCameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    /**
     * 相机特性
     */
    private var mCameraCharacteristics: CameraCharacteristics? = null
    /**
     * 预览请求
     */
    private var mPreviewRequest: CaptureRequest? = null
    /**
     * 预览的参数设置对象
     */
    private var mPreviewBuilder: CaptureRequest.Builder? = null
    /**
     * camera2的图片获取
     */
    private var mImageReader: ImageReader? = null
    /**
     * 摄像机角度
     */
    private var mOrientation: Int = 0
    /**
     * 相机ID
     */
    private var cameraId: String? = null
    /**
     * 录制事件监听
     */
    private var onRecordListener: OnRecordListener? = null
    /**
     * 预览画面回调
     */
    private var onPreviewSurfaceCallback: OnPreviewSurfaceCallback? = null

    /**
     * 是否正在预览
     */
    private var isPreviewing = false

    /**
     * surface的宽高
     */
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0


    init {
        initParameter()
        lifecycleOwner.lifecycle.addObserver(this)
    }

    /**
     * 主线程Handler
     */
    private val mMainHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message?) {
            super.handleMessage(msg)
            if (msg?.what == HANDLER_MSG_START_RECORD) {
                if (isPreviewing) {
                    // 开始录制
                    try {
                        startRecordTime = System.currentTimeMillis()
                        mMediaRecorder?.start()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    sendEmptyMessageDelayed(HANDLER_MSG_START_RECORD, 500)
                }
            }
        }
    }

    /**
     * 预览View的Surface
     */
    private val mSurfaceTextureListener = object : TextureView.SurfaceTextureListener {

        /**
         * 可用时才能打开相机预览画面
         */
        override fun onSurfaceTextureAvailable(
            surfaceTexture: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            surfaceWidth = width
            surfaceHeight = height
            openCamera()
        }

        /**
         * 大小改变时需要重新设置TextureView的Matrix参数
         */
        override fun onSurfaceTextureSizeChanged(
            surfaceTexture: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            surfaceWidth = width
            surfaceHeight = height
            configureTransform(width, height)
        }

        /**
         * surfaceTexture销毁时调用
         */
        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            return true
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {}

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->

        val image = reader.acquireNextImage()

        if (onPreviewSurfaceCallback != null) {
            val rect = image.cropRect
            // 将YUV_420_888转为NV21
            val data = ImageUtil.yuv420ToNV21(image)
            if (data != null) {
                val tempBitmap = ImageUtil.nv21ToBitmap(context, data, rect.width(), rect.height())

                // 旋转图片
                val rotation =
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).getDefaultDisplay()
                        .getRotation()
                val degree = INVERSE_ORIENTATIONS.get(rotation)
                val width = tempBitmap.width
                val height = tempBitmap.height
                val matrix = Matrix()
                matrix.setRotate(degree.toFloat())
                val newBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, width, height, matrix, false)
                tempBitmap.recycle()

                onPreviewSurfaceCallback?.onPreviewUpdate(newBitmap)
            }
        }
        image.close()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    private fun onAny(owner: LifecycleOwner, event: Lifecycle.Event) {

    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    private fun onDestroy() {

    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    private fun onResume() {
        startPreview()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    private fun onPause() {
        mMainHandler.removeMessages(HANDLER_MSG_START_RECORD)
        stopPreview()
    }

    /**
     *
     * 相机设备的状态回调
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its status.
     */
    private val mStateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            mCameraDevice = cameraDevice
            readyToPreview()
            mCameraOpenCloseLock.release()
            textureView.apply {
                configureTransform(width, height)
            }
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            mCameraOpenCloseLock.release()
            cameraDevice.close()
            mCameraDevice = null
        }
    }

    /**
     * 是否被授权
     *
     * @param permissions 权限列表
     */
    private fun hasPermissionsGranted(context: Context, permissions: Array<String>): Boolean {
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    /**
     * 初始化参数,相机锁加锁
     */
    private fun initParameter() {
        if (!hasPermissionsGranted(context, VIDEO_PERMISSIONS)) {
            Log.d(TAG, "无权限")
            return
        }

        try {
            if (!mCameraOpenCloseLock.tryAcquire(2000, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            for (cid in mCameraManager.cameraIdList) {
                val characteristics = mCameraManager.getCameraCharacteristics(cid)

                // We don't use a front facing camera in this sample.
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    cameraId = cid
                    break
                }
            }

            if (cameraId.isNullOrEmpty()) {
                cameraId = mCameraManager.cameraIdList[0]
            }

            mCameraCharacteristics =
                mCameraManager.getCameraCharacteristics(cameraId!!).also { cameraCharacteristics ->
                    val map =
                        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    mSensorOrientation =
                        cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                    if (map == null) {
                        throw RuntimeException("Cannot get available preview/video sizes")
                    }

                    // 从配置文件加载分辨率设置
                    mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))

                    // 预览分辨率和录制分辨率保持一致
                    mPreviewSize = chooseVideoSize(map.getOutputSizes(SurfaceTexture::class.java))
                    mOrientation = context.resources.configuration.orientation
                    mPreviewSize?.let { previewSize ->
                        if (mOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                            textureView.setAspectRatio(previewSize.width, previewSize.height)
                        } else {
                            textureView.setAspectRatio(previewSize.height, previewSize.width)
                        }
                    }
                }
        } catch (e: CameraAccessException) {
            Log.e(context.javaClass.simpleName, "Cannot access the camera.")
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        } finally {
            mCameraOpenCloseLock.release()
        }
    }

    /**
     * 选择Video分辨率  ---优先选择高宽比为1，尺寸最小的分辨率
     *
     */
    private fun chooseVideoSize(choices: Array<Size>): Size {
        if (choices == null) {
            return Size(400, 400)
        }
        val bigEnough = ArrayList<Size>()
        for (size in choices) {
            // 选择高宽相等的size
            if (size.width == size.height) {
                bigEnough.add(size)
            }
        }
        if (bigEnough.size > 0) {
            return Collections.min(bigEnough, CompareSizesByArea())
        } else {
            Log.e(TAG, "Couldn't find any suitable video size")
            for (size in choices) {
                // 选择高宽相等的size
                if (size.width < 700) {
                    return size
                }
            }
            return choices[0]
        }
    }

    /**
     * 配置图形变换策略
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (null == mPreviewSize) {
            return
        }
        val rotation =
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect =
            RectF(0f, 0f, mPreviewSize!!.height.toFloat(), mPreviewSize!!.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / mPreviewSize!!.height,
                viewWidth.toFloat() / mPreviewSize!!.width
            )
            matrix.postScale(scale, scale, centerX, centerY)
        }
        // 旋转屏幕
        var degree = 0f
        when (rotation) {
            Surface.ROTATION_0 -> degree = 0f
            Surface.ROTATION_90 -> degree = 270f
            Surface.ROTATION_180 -> degree = 180f
            Surface.ROTATION_270 -> degree = 90f
        }
        matrix.postRotate(degree, centerX, centerY)
        textureView.setTransform(matrix)
    }


    @Throws(Exception::class)
    private fun setupMediaRecorder() {


        if (null != mMediaRecorder) {
            mMediaRecorder!!.release()
            mMediaRecorder = null
        }

        if (mMediaRecorder == null) {
            mMediaRecorder = MediaRecorder()
        }

        try {
            mMediaRecorder!!.reset()
        } catch (ex: Exception) {
            ex.printStackTrace()
        }

        if (videoTempFile.exists()) {
            videoTempFile.delete()
        }

        mMediaRecorder!!.setAudioSource(MediaRecorder.AudioSource.MIC)
        mMediaRecorder!!.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mMediaRecorder!!.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        // 设置音频的编码格式
        mMediaRecorder!!.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mMediaRecorder!!.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        // 设置尺寸
        mMediaRecorder!!.setVideoSize(mVideoSize!!.width, mVideoSize!!.height)
        // 设置比特率
        mMediaRecorder!!.setVideoEncodingBitRate(mVideoSize!!.width * mVideoSize!!.height / 3 * 2)
        // 设置帧率
        mMediaRecorder!!.setVideoFrameRate(16)

        mMediaRecorder!!.setOutputFile(videoTempFile.absolutePath)

        mMediaRecorder!!.setOnErrorListener { _, what, extra ->
            onRecordListener?.onError(
                Throwable(
                    "MediaRecorder OnError what:$what extra:$extra"
                )
            )
        }

        val rotation =
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        when (mSensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES -> mMediaRecorder!!.setOrientationHint(
                DEFAULT_ORIENTATIONS.get(rotation)
            )
            SENSOR_ORIENTATION_INVERSE_DEGREES -> mMediaRecorder!!.setOrientationHint(
                INVERSE_ORIENTATIONS.get(rotation)
            )
        }
        mMediaRecorder!!.prepare()
    }

    private fun setupImageReader() {
        if (mImageReader == null) {
            mImageReader = ImageReader.newInstance(
                mPreviewSize!!.width,
                mPreviewSize!!.height,
                ImageFormat.YUV_420_888,
                1
            )
        }

        mImageReader?.setOnImageAvailableListener(onImageAvailableListener, mBackgroundHandler)
    }

    /**
     * 开始预览,同时输出到MediaRecorder
     */
    private fun readyToPreview() {
        if (null == mCameraDevice || !textureView.isAvailable || null == mPreviewSize) {
            return
        }

        try {
            closePreviewSession()

            setupMediaRecorder()

            setupImageReader()
            val surfaceTexture = textureView.surfaceTexture
            surfaceTexture!!.setDefaultBufferSize(mPreviewSize!!.width, mPreviewSize!!.height)
            mPreviewBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            mPreviewBuilder!!.set(
                CaptureRequest.CONTROL_CAPTURE_INTENT,
                CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT
            )
            // 初始化参数
            mPreviewBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            // 边缘增强,高质量
            mPreviewBuilder!!.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
            // 3A--->auto
            mPreviewBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            // 3A
            mPreviewBuilder!!.set(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_AUTO
            )
            mPreviewBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            mPreviewBuilder!!.set(
                CaptureRequest.CONTROL_AWB_MODE,
                CameraMetadata.CONTROL_AWB_MODE_AUTO
            )

            // fps
            mPreviewBuilder!!.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, getFpsRange())

            val surfaces = ArrayList<Surface>()
            // Set up Surface for the camera preview
            val previewSurface = Surface(surfaceTexture)
            surfaces.add(previewSurface)
            mPreviewBuilder!!.addTarget(previewSurface)

            if (mMediaRecorder != null) {
                val recorderSurface = mMediaRecorder!!.surface
                surfaces.add(recorderSurface)
                mPreviewBuilder!!.addTarget(recorderSurface)
            }

            if (mImageReader != null) {
                val surface = mImageReader!!.surface
                surfaces.add(surface)
                mPreviewBuilder!!.addTarget(surface)
            }

            // Start a capture session
            mCameraDevice!!.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        previewCameraCaptureSession = cameraCaptureSession

                        if (null == mCameraDevice) {
                            return
                        }
                        try {
                            mPreviewBuilder!!.set(
                                CaptureRequest.CONTROL_AF_TRIGGER,
                                CaptureRequest.CONTROL_AF_TRIGGER_IDLE
                            )
                            mPreviewRequest = mPreviewBuilder!!.build()
                            cameraCaptureSession.setRepeatingRequest(
                                mPreviewRequest!!,
                                captureCallback,
                                mBackgroundHandler
                            )

                            // 进入预览状态
                            isPreviewing = true
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        } catch (e: IllegalStateException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        Log.e(context.javaClass.simpleName, "Failed")
                    }
                },
                mBackgroundHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 打开摄像头
     */
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            configureTransform(surfaceWidth, surfaceHeight)
            mCameraManager.openCamera(cameraId!!, mStateCallback, null)
        } catch (e: CameraAccessException) {
            Log.e(context.javaClass.simpleName, "Cannot access the camera.")
        }
    }

    /**
     * 关闭相机设备
     */
    private fun closeCamera() {
        try {
            // 请求加锁
            if (!mCameraOpenCloseLock.tryAcquire(2000, TimeUnit.MILLISECONDS)) {
                Log.d(TAG, "Time out waiting to lock camera opening.")
                return
            }
            closePreviewSession()
            if (null != mCameraDevice) {
                mCameraDevice!!.close()
                mCameraDevice = null
            }

            if (mImageReader != null) {
                mImageReader!!.close()
                mImageReader = null
            }

            if (null != mMediaRecorder) {
                mMediaRecorder!!.release()
                mMediaRecorder = null
            }
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.")
        } finally {
            mCameraOpenCloseLock.release()
            // 退出预览状态
            isPreviewing = false
            mIsRecordingVideo = false
        }
    }


    /**
     * 关闭预览的Session
     */
    private fun closePreviewSession() {
        previewCameraCaptureSession?.close()
        previewCameraCaptureSession = null
    }


    /**
     * 获取摄像头的fps范围
     *
     * 下限小于15大于10，同时跨度最大的range
     * @return
     */
    private fun getFpsRange(): Range<Int>? {
        val ranges =
            mCameraCharacteristics!!.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

        var result: Range<Int>? = null
        for (range in ranges!!) {
            //帧率不能太低，大于10
            if (range.lower < 10)
                continue
            if (result == null)
                result = range
            else if (range.lower <= 15 && range.upper - range.lower > result.upper - result.lower)
                result =
                    range //FPS下限小于15，弱光时能保证足够曝光时间，提高亮度。range范围跨度越大越好，光源足够时FPS较高，预览更流畅，光源不够时FPS较低，亮度更好。
        }
        return result
    }

    /**
     * 开启一个后台线程,不会阻塞UI
     *
     */
    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("CameraBackground")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    /**
     * 停止后台线程
     *
     */
    private fun stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread!!.quit()
            try {
                mBackgroundThread!!.join()
                mBackgroundThread = null

                mBackgroundHandler?.removeCallbacksAndMessages(null)
                mBackgroundHandler = null
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }

        }
    }


    /**
     * 开始预览
     */
    private fun startPreview() {
        startBackgroundThread()
        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = mSurfaceTextureListener
        }
    }

    /**
     * 停止预览
     */
    private fun stopPreview() {
        closeCamera()
        stopBackgroundThread()
    }

    /**
     * 开始录制
     */
    fun startRecord(listener: OnRecordListener?) {
        if (mIsRecordingVideo) {
            return
        }

        onRecordListener = listener
        mIsRecordingVideo = true

        mMainHandler.sendEmptyMessageDelayed(HANDLER_MSG_START_RECORD, if (isPreviewing) 0 else 500)
    }

    /**
     * 停止录制
     */
    fun stopRecord(destFile: File?): Boolean {
        var result = false

        // 删除开始录制消息
        mMainHandler.removeMessages(HANDLER_MSG_START_RECORD)

        if (mIsRecordingVideo) {
            try {
                val now = System.currentTimeMillis()
                if (now - startRecordTime < 1000) {
                    Thread.sleep(1000)
                }
                stopPreview()
            } catch (ex: Exception) {
                ex.printStackTrace()
                onRecordListener?.onError(ex)
            }

            // 状态更新
            mIsRecordingVideo = false

            try {
                if (destFile != null && videoTempFile.exists() && videoTempFile.length() > 0) {
                    if (destFile.exists()) {
                        destFile.delete()
                    }
                    val fos = FileOutputStream(videoTempFile, true)
                    val fileLock = fos.channel.lock()
                    fileLock?.release()
                    fos.close()
                    videoTempFile.copyTo(destFile, true, 1000)
                    if (destFile.exists()) {
                        destFile.deleteOnExit()
                        onRecordListener?.onSavedRecord(destFile)
                        result = true
                    }
                } else {
                    onRecordListener?.onError(Throwable("视频录制失败"))
                }
            } catch (e: IOException) {
                e.printStackTrace()
                onRecordListener?.onError(e)
            }

            // 重新配置参数，进入预览状态
            startPreview()
        }
        return result
    }

    /**
     * 设置预览画面回调
     */
    fun setOnPreviewSurfaceCallback(callback: OnPreviewSurfaceCallback) {
        onPreviewSurfaceCallback = callback
    }

    interface OnRecordListener {
        fun onSavedRecord(file: File)

        fun onError(error: Throwable)
    }

    interface OnPreviewSurfaceCallback {
        fun onPreviewUpdate(bitmap: Bitmap)
    }

    /**
     * 获取音频音量
     */
    fun getAudioVolume(): Double {
        if (mMediaRecorder == null) {
            return 0.0
        }
        val amplitude = mMediaRecorder!!.getMaxAmplitude().toDouble()
        val ratio = amplitude / AUDIO_BASE_VOLUME
        var db = 0.0// 分贝
        if (ratio > 1) {
            db = 20 * Math.log10(ratio)
        }

        return db
    }

    companion object {
        private const val VIDEO_TEMP_FILE = "camera_video_temp.mp4"

        private const val TAG = "CameraHelper"

        private const val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90

        private const val SENSOR_ORIENTATION_INVERSE_DEGREES = 270

        private const val HANDLER_MSG_START_RECORD = 0x0123

        private val VIDEO_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO
        )

        private val DEFAULT_ORIENTATIONS = SparseIntArray().also { DEFAULT_ORIENTATIONS ->
            DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90)
            DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0)
            DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270)
            DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180)
        }

        private val INVERSE_ORIENTATIONS = SparseIntArray().also { INVERSE_ORIENTATIONS ->
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90)
            INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0)
        }

        /**
         * 音频
         */
        private const val AUDIO_BASE_VOLUME = 10
    }

}