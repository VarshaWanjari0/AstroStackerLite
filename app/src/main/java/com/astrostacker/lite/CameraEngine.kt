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

    // Capabilities
    private var sensorRect: Rect? = null
    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var isManualSupported = false

    // State
    var currentZoom = 1.0f
    var currentIso = 800
    var currentExposure = 100_000_000L
    var currentFocusDistance = 0.0f
    var isTorchOn = false
    var isCapturing = false
    var isAutoPreviewEnabled = true // Fixes camera lag during preview

    fun start() {
        startBackgroundThread()
        if (binding.textureView.isAvailable) {
            openCamera()
        } else {
            binding.textureView.surfaceTextureListener = textureListener
        }
    }

    fun stop() {
        closeCamera()
        stopBackgroundThread()
    }

    fun isReady(): Boolean = cameraDevice != null && captureSession != null && !isCapturing

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("CameraEngineThread").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (e: Exception) {}
        backgroundThread = null
        backgroundHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

                if (facing == CameraCharacteristics.LENS_FACING_BACK && caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) {
                    activeCameraId = id
                    stackingEngine.activeCameraId = id
                    
                    isManualSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                    isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                    
                    val previewSize = map.getOutputSizes(SurfaceTexture::class.java)?.firstOrNull() ?: Size(1920, 1080)
                    binding.textureView.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

                    val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
                    if (rawSize != null) {
                        stackingEngine.setup(rawSize.width, rawSize.height)
                        imageReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 3).apply {
                            setOnImageAvailableListener(stackingEngine.imageListener, backgroundHandler)
                        }
                        cameraManager.openCamera(id, stateCallback, backgroundHandler)
                        return
                    }
                }
            }
            activity.runOnUiThread { Toast.makeText(activity, "RAW Camera not found", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { Log.e(TAG, "Open camera error", e) }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) { cameraDevice = camera; createSession() }
        override fun onDisconnected(camera: CameraDevice) { closeCamera() }
        override fun onError(camera: CameraDevice, error: Int) { closeCamera() }
    }

    private fun createSession() {
        try {
            val texture = binding.textureView.surfaceTexture ?: return
            val previewSurface = Surface(texture)
            val rawSurface = imageReader?.surface ?: return

            cameraDevice?.createCaptureSession(listOf(previewSurface, rawSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updatePreview()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "createSession error", e) }
    }

    fun updatePreview() {
        if (cameraDevice == null || captureSession == null || isCapturing) return
        try {
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(Surface(binding.textureView.surfaceTexture!!))
            applySettings(builder, isPreview = true)
            captureSession!!.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "updatePreview error", e) }
    }

    fun startBurstCapture() {
        if (cameraDevice == null || captureSession == null) return
        try {
            isCapturing = true
            stackingEngine.startCapture()

            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(imageReader!!.surface!!)
            applySettings(builder, isPreview = false)

            val requests = List(stackingEngine.targetStackCount) { builder.build() }
            
            captureSession!!.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    stackingEngine.onCaptureResultReceived(result)
                }
                
                override fun onCaptureSequenceCompleted(session: CameraCaptureSession, sequenceId: Int, frameNumber: Long) {
                    stackingEngine.onSequenceCompleted()
                    isCapturing = false
                }
                
                override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                    stackingEngine.onSequenceCompleted()
                    isCapturing = false
                }
            }, backgroundHandler)
            
        } catch (e: Exception) { 
            Log.e(TAG, "Burst error", e)
            isCapturing = false
            stackingEngine.resetUI()
        }
    }

    private fun applySettings(builder: CaptureRequest.Builder, isPreview: Boolean) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance)
        builder.set(CaptureRequest.FLASH_MODE, if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)

        if (isManualSupported) {
            // FIX: If it's a preview and Auto Preview is ON, let the camera AE run so the preview isn't lagging.
            if (isPreview && isAutoPreviewEnabled) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            } else {
                // Actual capture, or user disabled Auto Preview: lock manual settings
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            }
        }

        sensorRect?.let {
            val cx = it.centerX(); val cy = it.centerY()
            val dx = (0.5f * it.width() / currentZoom).toInt()
            val dy = (0.5f * it.height() / currentZoom).toInt()
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cx - dx, cy - dy, cx + dx, cy + dy))
        }
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    fun setExposureProgress(progress: Int) {
        exposureRange?.let {
            val min = it.lower.toDouble().coerceAtLeast(1000.0)
            val max = it.upper.toDouble()
            currentExposure = exp(ln(min) + (progress / 100.0) * (ln(max) - ln(min))).toLong()
        }
    }

    fun setIsoProgress(progress: Int) {
        isoRange?.let {
            val min = it.lower.toDouble()
            val max = it.upper.toDouble()
            currentIso = exp(ln(min) + (progress / 100.0) * (ln(max) - ln(min))).toInt()
        }
    }
}