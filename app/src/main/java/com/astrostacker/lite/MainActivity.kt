package com.astrostacker.lite

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.astrostacker.lite.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MainActivity : AppCompatActivity() {

    private val TAG = "AstroStackerLite"
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var stackingThread: HandlerThread? = null
    private var stackingHandler: Handler? = null

    // Capabilities
    private var isManualExposureSupported = false
    private var exposureRange: Range<Long>? = null
    private var isoRange: Range<Int>? = null
    
    // State
    private var currentExposure: Long = 100000000L // 100ms
    private var currentIso: Int = 800
    private var stackCount: Int = 25
    private var currentCaptureCount = 0
    private var isCapturing = false
    private var isDngMode = true
    private var isTorchOn = false
    private var currentZoom = 1.0f
    private val maxZoom = 10.0f
    
    // Memory efficient stacking array
    private var masterCanvas: IntArray? = null
    private var canvasWidth = 0
    private var canvasHeight = 0
    private var sensorRect: Rect? = null
    private var lastCaptureResult: TotalCaptureResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupUI()
    }

    private fun setupUI() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        binding.sbExposure.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                exposureRange?.let {
                    val min = it.lower.toDouble().coerceAtLeast(1000.0)
                    val max = it.upper.toDouble()
                    val p = progress / 100.0
                    currentExposure = exp(ln(min) + p * (ln(max) - ln(min))).toLong()
                    binding.tvExposure.text = "EXP: ${currentExposure / 1000000}ms"
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.sbStackCount.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                stackCount = progress.coerceAtLeast(1)
                binding.tvStackCount.text = "STACK: $stackCount"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.sbIso.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                isoRange?.let {
                    val min = it.lower.toDouble()
                    val max = it.upper.toDouble()
                    val p = progress / 100.0
                    currentIso = exp(ln(min) + p * (ln(max) - ln(min))).toInt()
                    binding.tvIso.text = "ISO: $currentIso"
                    if (!isCapturing) updatePreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.sbZoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                currentZoom = 1.0f + (progress / 100.0f) * (maxZoom - 1.0f)
                binding.tvZoom.text = "ZOOM: ${String.format("%.1fx", currentZoom)}"
                if (!isCapturing) updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnTorch.setOnClickListener {
            if (isCapturing) return@setOnClickListener
            isTorchOn = !isTorchOn
            updatePreview()
        }

        binding.btnSettings.setOnClickListener {
            if (isCapturing) return@setOnClickListener
            isDngMode = !isDngMode
            Toast.makeText(this, "Format: ${if(isDngMode) "DNG" else "RAW BIN"}", Toast.LENGTH_SHORT).show()
        }

        binding.btnCapture.setOnClickListener {
            if (!isCapturing && cameraId != null) {
                startCountdown()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startThreads()
        if (binding.textureView.isAvailable) openCamera()
    }

    override fun onPause() {
        closeCamera()
        stopThreads()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (binding.textureView.isAvailable) openCamera()
        }
    }

    private fun startThreads() {
        backgroundThread = HandlerThread("CameraBg").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        stackingThread = HandlerThread("StackingBg").apply { start() }
        stackingHandler = Handler(stackingThread!!.looper)
    }

    private fun stopThreads() {
        backgroundThread?.quitSafely()
        stackingThread?.quitSafely()
        try {
            backgroundThread?.join()
            stackingThread?.join()
        } catch (e: InterruptedException) { e.printStackTrace() }
        backgroundThread = null
        backgroundHandler = null
        stackingThread = null
        stackingHandler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            return
        }

        try {
            // Find best back camera with RAW capability
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                
                if (facing == CameraCharacteristics.LENS_FACING_BACK && caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) {
                    cameraId = id
                    isManualExposureSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                    
                    exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    // Get highest resolution RAW size
                    val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
                    
                    if (rawSize != null) {
                        canvasWidth = rawSize.width
                        canvasHeight = rawSize.height
                        
                        // Limit ImageReader buffer to 3 to save RAM on low end devices
                        imageReader = ImageReader.newInstance(canvasWidth, canvasHeight, ImageFormat.RAW_SENSOR, 3).apply {
                            setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
                        }
                        
                        cameraManager.openCamera(id, stateCallback, backgroundHandler)
                        return
                    }
                }
            }
            runOnUiThread { Toast.makeText(this, "RAW capability not found on back camera!", Toast.LENGTH_LONG).show() }
        } catch (e: Exception) { 
            Log.e(TAG, "Error opening camera", e)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) { closeCamera() }
        override fun onError(camera: CameraDevice, error: Int) { closeCamera() }
    }

    private fun createPreviewSession() {
        try {
            val surface = Surface(binding.textureView.surfaceTexture)
            cameraDevice?.createCaptureSession(listOf(surface, imageReader?.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    updatePreview()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Preview Failed", Toast.LENGTH_SHORT).show() }
                }
            }, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "createPreviewSession error", e) }
    }

    private fun updatePreview() {
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
            builder.addTarget(Surface(binding.textureView.surfaceTexture))
            
            if (isManualExposureSupported) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) 
            builder.set(CaptureRequest.FLASH_MODE, if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            applyZoom(builder)
            
            captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "updatePreview error", e) }
    }

    private fun applyZoom(builder: CaptureRequest.Builder) {
        sensorRect?.let {
            val centerX = it.centerX()
            val centerY = it.centerY()
            val deltaX = (0.5f * it.width() / currentZoom).toInt()
            val deltaY = (0.5f * it.height() / currentZoom).toInt()
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY))
        }
    }

    private fun startCountdown() {
        val timerValue = 5
        binding.tvTimer.visibility = View.VISIBLE
        binding.btnCapture.isEnabled = false
        binding.controlsLayout.visibility = View.GONE
        
        object : CountDownTimer((timerValue * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                binding.tvTimer.text = "${(millisUntilFinished / 1000) + 1}"
            }
            override fun onFinish() {
                binding.tvTimer.visibility = View.GONE
                startCaptureBurst()
            }
        }.start()
    }

    private fun startCaptureBurst() {
        try {
            isCapturing = true
            currentCaptureCount = 0
            
            // Allocate IntArray instead of LongArray to cut memory footprint in half for low-end phones
            masterCanvas = IntArray(canvasWidth * canvasHeight)
            
            runOnUiThread {
                binding.statusOverlay.visibility = View.VISIBLE
                binding.tvStatus.text = "Stacking: 0/$stackCount"
            }

            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: return
            builder.addTarget(imageReader!!.surface)
            
            if (isManualExposureSupported) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
            
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
            builder.set(CaptureRequest.FLASH_MODE, if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            applyZoom(builder)

            val requests = List(stackCount) { builder.build() }
            
            captureSession?.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (lastCaptureResult == null) {
                        lastCaptureResult = result
                    }
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "startCaptureBurst error", e)
            resetUI()
        }
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireNextImage() ?: return@OnImageAvailableListener
        if (!isCapturing) {
            image.close()
            return@OnImageAvailableListener
        }
        
        // Pass to stacking thread so ImageReader isn't blocked
        stackingHandler?.post {
            try {
                processFrame(image)
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing error", e)
                image.close()
            }
        }
    }

    private fun processFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val canvas = masterCanvas ?: run { image.close(); return }

        val shortBuffer = buffer.asShortBuffer()
        
        // Optimized processing loop for RAW16 (pixelStride typically 2 bytes, 1 short)
        var canvasIdx = 0
        for (y in 0 until canvasHeight) {
            var bufIdx = (y * rowStride) / 2
            for (x in 0 until canvasWidth) {
                canvas[canvasIdx++] += shortBuffer.get(bufIdx).toInt() and 0xFFFF
                bufIdx += pixelStride / 2
            }
        }
        image.close()
        
        currentCaptureCount++
        runOnUiThread { binding.tvStatus.text = "Stacking: $currentCaptureCount/$stackCount" }
        
        if (currentCaptureCount >= stackCount) {
            finalizeStack()
        }
    }

    private fun finalizeStack() {
        val canvas = masterCanvas ?: return
        
        // Allocate direct ByteBuffer for the final DNG writing
        val finalBuffer = ByteBuffer.allocateDirect(canvasWidth * canvasHeight * 2)
        finalBuffer.order(ByteOrder.nativeOrder())
        
        for (i in canvas.indices) {
            val avg = (canvas[i] / stackCount).coerceIn(0, 65535).toShort()
            finalBuffer.putShort(avg)
        }
        finalBuffer.rewind()

        try {
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AstroStacker")
            if (!publicDir.exists()) publicDir.mkdirs()
            val fileName = "ASTRO_${System.currentTimeMillis()}.${if(isDngMode) "dng" else "raw"}"
            val file = File(publicDir, fileName)
            
            FileOutputStream(file).use { out ->
                if (isDngMode && lastCaptureResult != null && cameraId != null) {
                    val chars = cameraManager.getCameraCharacteristics(cameraId!!)
                    val dng = DngCreator(chars, lastCaptureResult!!)
                    dng.writeByteBuffer(out, Size(canvasWidth, canvasHeight), finalBuffer, 0L)
                    dng.close()
                } else {
                    out.channel.write(finalBuffer)
                }
            }

            android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null) { _, _ -> }
            runOnUiThread { 
                Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
                resetUI()
            }
        } catch (e: Exception) { 
            Log.e(TAG, "finalizeStack save error", e)
            runOnUiThread { Toast.makeText(this, "Save Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            resetUI()
        }
    }

    private fun resetUI() {
        isCapturing = false
        masterCanvas = null
        lastCaptureResult = null
        
        runOnUiThread {
            binding.statusOverlay.visibility = View.GONE
            binding.controlsLayout.visibility = View.VISIBLE
            binding.btnCapture.isEnabled = true
            updatePreview()
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            imageReader?.close(); imageReader = null
        } catch (e: Exception) { Log.e(TAG, "closeCamera error", e) }
    }
}
