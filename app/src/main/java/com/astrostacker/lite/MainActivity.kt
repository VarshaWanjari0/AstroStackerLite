package com.astrostacker.lite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.astrostacker.lite.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.ln

/**
 * Enterprise-Grade rewrite of AstroStacker Lite.
 * Utilizes a strict State Machine to prevent race conditions.
 * Memory footprint optimized using IntArray and safe buffer clearing.
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "AstroStackerV3"
    private lateinit var binding: ActivityMainBinding

    // Threading
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    // Camera Core
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var activeCameraId: String? = null

    // Sensor Capabilities
    private var sensorRect: Rect? = null
    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var supportsManualControl = false

    // App State Machine
    private enum class AppState { IDLE, COUNTDOWN, CAPTURING, SAVING }
    private var currentState = AppState.IDLE

    // User Settings
    private var currentZoom = 1.0f
    private val maxZoom = 10.0f
    private var currentIso = 800
    private var currentExposure = 100_000_000L // 100ms
    private var targetStackCount = 25
    private var isTorchOn = false
    private var isDngMode = true

    // Stacking Memory & Counters
    private var masterCanvas: IntArray? = null
    private var canvasWidth = 0
    private var canvasHeight = 0
    private val framesProcessed = AtomicInteger(0)
    private var dngCaptureResult: TotalCaptureResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        setupTextureView()
        setupUIControls()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThreads()
        if (binding.textureView.isAvailable) {
            openCameraStrict()
        } else {
            setupTextureView()
        }
    }

    override fun onPause() {
        closeCameraStrict()
        stopBackgroundThreads()
        super.onPause()
    }

    // =========================================================================
    // UI SETUP
    // =========================================================================

    private fun setupTextureView() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCameraStrict() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun setupUIControls() {
        // Format Toggle
        binding.btnFormatToggle.setOnClickListener {
            if (currentState != AppState.IDLE) return@setOnClickListener
            isDngMode = !isDngMode
            binding.tvCurrentFormat.text = if (isDngMode) "FORMAT: DNG" else "FORMAT: RAW BIN"
        }

        // Torch Toggle
        binding.btnTorch.setOnClickListener {
            if (currentState != AppState.IDLE) return@setOnClickListener
            isTorchOn = !isTorchOn
            updatePreviewSafely()
        }

        // Capture Button
        binding.btnCapture.setOnClickListener {
            if (currentState == AppState.IDLE && activeCameraId != null) {
                transitionToCountdown()
            }
        }

        // Zoom Slider
        binding.sbZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                currentZoom = 1.0f + (progress / 100.0f) * (maxZoom - 1.0f)
                binding.tvZoom.text = "ZOOM: ${String.format("%.1fx", currentZoom)}"
                if (currentState == AppState.IDLE) updatePreviewSafely()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Exposure Slider (Logarithmic)
        binding.sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                exposureRange?.let {
                    val min = it.lower.toDouble().coerceAtLeast(1000.0)
                    val max = it.upper.toDouble()
                    val p = progress / 100.0
                    currentExposure = exp(ln(min) + p * (ln(max) - ln(min))).toLong()
                    binding.tvExposure.text = "EXP: ${currentExposure / 1_000_000}ms"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ISO Slider (Logarithmic)
        binding.sbIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                isoRange?.let {
                    val min = it.lower.toDouble()
                    val max = it.upper.toDouble()
                    val p = progress / 100.0
                    currentIso = exp(ln(min) + p * (ln(max) - ln(min))).toInt()
                    binding.tvIso.text = "ISO: $currentIso"
                    if (currentState == AppState.IDLE) updatePreviewSafely()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Stack Count Slider
        binding.sbStackCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                targetStackCount = progress.coerceAtLeast(1)
                binding.tvStackCount.text = "STACK: $targetStackCount"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    // =========================================================================
    // STATE MACHINE
    // =========================================================================

    private fun transitionToCountdown() {
        currentState = AppState.COUNTDOWN
        binding.controlsPanel.visibility = View.GONE
        binding.topBar.visibility = View.GONE
        binding.tvTimer.visibility = View.VISIBLE
        binding.btnCapture.isEnabled = false
        
        object : CountDownTimer(5000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.tvTimer.text = "${(millisUntilFinished / 1000) + 1}"
            }
            override fun onFinish() {
                binding.tvTimer.visibility = View.GONE
                transitionToCapturing()
            }
        }.start()
    }

    private fun transitionToCapturing() {
        currentState = AppState.CAPTURING
        framesProcessed.set(0)
        dngCaptureResult = null
        
        // Safe allocation. OOM proofing on low-end devices.
        try {
            masterCanvas = IntArray(canvasWidth * canvasHeight)
        } catch (e: OutOfMemoryError) {
            handleErrorState("Out of memory. Lower resolution or clear RAM.")
            return
        }

        binding.processingOverlay.visibility = View.VISIBLE
        binding.tvProgress.text = "Stacking: 0/$targetStackCount"
        
        startBurstCapture()
    }

    private fun transitionToSaving() {
        currentState = AppState.SAVING
        binding.tvProgress.text = "Saving File..."
        binding.tvProgressSub.text = "Encoding output buffer"
        
        imageHandler?.post {
            executeSavingLogic()
        }
    }

    private fun transitionToIdle() {
        currentState = AppState.IDLE
        masterCanvas = null
        dngCaptureResult = null
        
        runOnUiThread {
            binding.processingOverlay.visibility = View.GONE
            binding.controlsPanel.visibility = View.VISIBLE
            binding.topBar.visibility = View.VISIBLE
            binding.btnCapture.isEnabled = true
            updatePreviewSafely()
        }
    }

    private fun handleErrorState(msg: String) {
        Log.e(TAG, "Error: $msg")
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            transitionToIdle()
        }
    }

    // =========================================================================
    // THREAD MANAGEMENT
    // =========================================================================

    private fun startBackgroundThreads() {
        cameraThread = HandlerThread("CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        
        imageThread = HandlerThread("ImageThread").also { it.start() }
        imageHandler = Handler(imageThread!!.looper)
    }

    private fun stopBackgroundThreads() {
        cameraThread?.quitSafely()
        imageThread?.quitSafely()
        try {
            cameraThread?.join()
            imageThread?.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        cameraThread = null
        cameraHandler = null
        imageThread = null
        imageHandler = null
    }

    // =========================================================================
    // CAMERA 2 CORE
    // =========================================================================

    private fun openCameraStrict() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            return
        }

        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                
                if (facing == CameraCharacteristics.LENS_FACING_BACK && caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) {
                    
                    activeCameraId = id
                    supportsManualControl = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                    isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    val bestSize = rawSizes?.maxByOrNull { it.width * it.height }
                    
                    if (bestSize != null) {
                        canvasWidth = bestSize.width
                        canvasHeight = bestSize.height
                        
                        // Ultra-conservative buffer: 2 images. 
                        // We process faster than we capture during long exposure.
                        imageReader = ImageReader.newInstance(canvasWidth, canvasHeight, ImageFormat.RAW_SENSOR, 2).apply {
                            setOnImageAvailableListener(imageAvailableListener, imageHandler)
                        }
                        
                        cameraManager.openCamera(id, cameraStateCallback, cameraHandler)
                        return
                    }
                }
            }
            handleErrorState("RAW capability not found on any back camera.")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCaptureSession()
        }
        override fun onDisconnected(camera: CameraDevice) { closeCameraStrict() }
        override fun onError(camera: CameraDevice, error: Int) { closeCameraStrict() }
    }

    private fun createCaptureSession() {
        try {
            val texture = binding.textureView.surfaceTexture
            texture?.setDefaultBufferSize(binding.textureView.width, binding.textureView.height)
            val previewSurface = Surface(texture)
            val rawSurface = imageReader?.surface
            
            if (rawSurface == null) return

            cameraDevice?.createCaptureSession(listOf(previewSurface, rawSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    updatePreviewSafely()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    handleErrorState("Failed to configure camera session.")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession error", e)
        }
    }

    private fun updatePreviewSafely() {
        if (cameraDevice == null || captureSession == null || currentState != AppState.IDLE) return
        
        try {
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(Surface(binding.textureView.surfaceTexture!!))
            
            applySettingsToBuilder(builder, isPreview = true)
            captureSession!!.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "updatePreviewSafely error", e)
        }
    }

    private fun startBurstCapture() {
        if (cameraDevice == null || captureSession == null) {
            handleErrorState("Camera disconnected before capture.")
            return
        }

        try {
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(imageReader!!.surface!!)
            
            applySettingsToBuilder(builder, isPreview = false)
            
            val requests = List(targetStackCount) { builder.build() }
            
            captureSession!!.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    // Save the first result to be used for DNG metadata
                    if (dngCaptureResult == null) {
                        dngCaptureResult = result
                    }
                }
            }, cameraHandler)
            
        } catch (e: Exception) {
            handleErrorState("Failed to start burst: ${e.message}")
        }
    }

    private fun applySettingsToBuilder(builder: CaptureRequest.Builder, isPreview: Boolean) {
        // Focus to infinity always for Astro
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) 

        // Flash Torch
        builder.set(CaptureRequest.FLASH_MODE, if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)

        // Exposure & ISO
        if (supportsManualControl) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        // Digital Zoom Crop
        sensorRect?.let { rect ->
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            val dx = (0.5f * rect.width() / currentZoom).toInt()
            val dy = (0.5f * rect.height() / currentZoom).toInt()
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(centerX - dx, centerY - dy, centerX + dx, centerY + dy))
        }
    }

    // =========================================================================
    // IMAGE PROCESSING & STACKING
    // =========================================================================

    private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        
        if (currentState != AppState.CAPTURING) {
            image.close() // Discard if we aren't explicitly in capture mode
            return@OnImageAvailableListener
        }

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val shortBuffer = buffer.asShortBuffer()
            
            val canvas = masterCanvas
            if (canvas != null) {
                var cIdx = 0
                for (y in 0 until canvasHeight) {
                    var bIdx = (y * rowStride) / 2
                    for (x in 0 until canvasWidth) {
                        canvas[cIdx++] += shortBuffer.get(bIdx).toInt() and 0xFFFF
                        bIdx += pixelStride / 2
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            // GUARANTEED CLOSE. Critical for low end devices to prevent ImageReader stall.
            image.close()
        }

        val count = framesProcessed.incrementAndGet()
        runOnUiThread { 
            if (currentState == AppState.CAPTURING) {
                binding.tvProgress.text = "Stacking: $count/$targetStackCount"
            }
        }

        if (count >= targetStackCount) {
            runOnUiThread { transitionToSaving() }
        }
    }

    private fun executeSavingLogic() {
        val canvas = masterCanvas
        if (canvas == null) {
            handleErrorState("Memory lost during processing.")
            return
        }

        try {
            val finalBuffer = ByteBuffer.allocateDirect(canvasWidth * canvasHeight * 2)
            finalBuffer.order(ByteOrder.nativeOrder())
            
            for (i in canvas.indices) {
                val avg = (canvas[i] / targetStackCount).coerceIn(0, 65535).toShort()
                finalBuffer.putShort(avg)
            }
            finalBuffer.rewind()

            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AstroStacker")
            if (!publicDir.exists()) publicDir.mkdirs()
            
            val ext = if (isDngMode && dngCaptureResult != null) "dng" else "raw"
            val file = File(publicDir, "ASTRO_${System.currentTimeMillis()}.$ext")

            FileOutputStream(file).use { out ->
                if (ext == "dng") {
                    val chars = cameraManager.getCameraCharacteristics(activeCameraId!!)
                    val dng = DngCreator(chars, dngCaptureResult!!)
                    dng.writeByteBuffer(out, Size(canvasWidth, canvasHeight), finalBuffer, 0L)
                    dng.close()
                } else {
                    out.channel.write(finalBuffer)
                }
            }

            android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null) { _, _ -> }
            
            runOnUiThread {
                Toast.makeText(this, "Successfully Saved: ${file.name}", Toast.LENGTH_LONG).show()
                transitionToIdle()
            }

        } catch (e: Exception) {
            Log.e(TAG, "executeSavingLogic error", e)
            handleErrorState("Failed to save final image: ${e.message}")
        }
    }

    // =========================================================================
    // CLEANUP
    // =========================================================================

    private fun closeCameraStrict() {
        try {
            captureSession?.close()
            captureSession = null
            
            cameraDevice?.close()
            cameraDevice = null
            
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (binding.textureView.isAvailable) openCameraStrict()
        } else {
            Toast.makeText(this, "Permissions are required to run AstroStacker.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
