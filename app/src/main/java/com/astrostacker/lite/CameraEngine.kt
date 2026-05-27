package com.astrostacker.lite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astrostacker.lite.databinding.ActivityMainBinding
import kotlin.math.exp
import kotlin.math.ln

class CameraEngine(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val stackingEngine: StackingEngine
) {
    private val TAG = "CameraEngine"
    private val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    var cameraDevice: CameraDevice? = null
    var captureSession: CameraCaptureSession? = null
    var imageReader: ImageReader? = null
    var activeCameraId: String? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // State
    var currentIso = 800
    var currentExposure = 100_000_000L
    var isAutoPreviewEnabled = true
    private var isCapturing = false

    private var sensorRect: Rect? = null
    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var isManualSupported = false

    fun start() {
        backgroundThread = HandlerThread("CameraBg").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        if (binding.textureView.isAvailable) openCamera()
        else binding.textureView.surfaceTextureListener = textureListener
    }

    fun stop() {
        closeCamera()
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    fun isReady() = cameraDevice != null && captureSession != null && !isCapturing

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) {
                    
                    activeCameraId = id
                    stackingEngine.activeCameraId = id
                    isManualSupported = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
                    isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                    val previewSize = map.getOutputSizes(SurfaceTexture::class.java)?.firstOrNull() ?: Size(1920, 1080)
                    binding.textureView.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

                    val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } ?: continue
                    stackingEngine.setup(rawSize.width, rawSize.height)
                    imageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3).apply {
                        setOnImageAvailableListener(stackingEngine.imageListener, backgroundHandler)
                    }
                    cameraManager.openCamera(id, stateCallback, backgroundHandler)
                    return
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Open error", e) }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) { cameraDevice = camera; createSession() }
        override fun onDisconnected(camera: CameraDevice) { closeCamera() }
        override fun onError(camera: CameraDevice, error: Int) { closeCamera() }
    }

    private fun createSession() {
        try {
            val previewSurface = Surface(binding.textureView.surfaceTexture!!)
            val rawSurface = imageReader!!.surface
            cameraDevice?.createCaptureSession(listOf(previewSurface, rawSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { captureSession = session; updatePreview() }
                override fun onConfigureFailed(s: CameraCaptureSession) {}
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "Session error", e) }
    }

    fun updatePreview() {
        if (cameraDevice == null || captureSession == null || isCapturing) return
        try {
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(Surface(binding.textureView.surfaceTexture!!))
            
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
            
            if (isManualSupported && !isAutoPreviewEnabled) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            captureSession!!.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "Update preview error", e) }
    }

    fun startBurstCapture() {
        if (cameraDevice == null || captureSession == null) return
        try {
            isCapturing = true
            stackingEngine.startCapture()
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(imageReader!!.surface)
            
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)

            val requests = List(stackingEngine.targetStackCount) { builder.build() }
            captureSession!!.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) { stackingEngine.onCaptureResultReceived(res) }
                override fun onCaptureSequenceCompleted(s: CameraCaptureSession, id: Int, f: Long) { isCapturing = false; stackingEngine.onSequenceCompleted() }
                override fun onCaptureSequenceAborted(s: CameraCaptureSession, id: Int) { isCapturing = false; stackingEngine.onSequenceCompleted() }
            }, backgroundHandler)
        } catch (e: Exception) { isCapturing = false; stackingEngine.resetUI() }
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    fun setExposureProgress(p: Int) {
        exposureRange?.let {
            val min = it.lower.toDouble().coerceAtLeast(1000.0)
            val max = it.upper.toDouble()
            currentExposure = exp(ln(min) + (p / 100.0) * (ln(max) - ln(min))).toLong()
        }
    }

    fun setIsoProgress(p: Int) {
        isoRange?.let {
            val min = it.lower.toDouble()
            val max = it.upper.toDouble()
            currentIso = exp(ln(min) + (p / 100.0) * (ln(max) - ln(min))).toInt()
        }
    }
}