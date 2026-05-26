package com.astrostacker.lite

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.util.Range
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Stacking Thread for heavy math
    private var stackingThread: HandlerThread? = null
    private var stackingHandler: Handler? = null

    private var exposureRange: Range<Long>? = null
    private var currentExposure: Long = 100000000L // 100ms
    private var stackCount: Int = 25
    
    private var isCapturing = false
    private var currentCaptureCount = 0
    private var masterCanvas: LongArray? = null // Use Long to prevent overflow during summing
    private var canvasWidth = 0
    private var canvasHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupUI()
    }

    private fun setupUI() {
        binding.textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        binding.sbExposure.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                exposureRange?.let {
                    val min = it.lower.toDouble()
                    val max = it.upper.toDouble()
                    val p = progress / 100.0
                    val value = Math.exp(Math.log(min) + p * (Math.log(max) - Math.log(min))).toLong()
                    currentExposure = value
                    binding.tvExposure.text = "Exposure: ${value / 1000000}ms"
                    if (!isCapturing) updatePreview()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.sbStackCount.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                stackCount = progress.coerceAtLeast(1)
                binding.tvStackCount.text = "Stack Count: $stackCount"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnCapture.setOnClickListener {
            if (!isCapturing) startCaptureBurst()
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

    private fun startThreads() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        
        stackingThread = HandlerThread("StackingEngine").also { it.start() }
        stackingHandler = Handler(stackingThread!!.looper)
    }

    private fun stopThreads() {
        backgroundThread?.quitSafely()
        stackingThread?.quitSafely()
        backgroundThread = null
        stackingThread = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            return
        }

        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                
                if (facing == CameraCharacteristics.LENS_FACING_BACK &&
                    caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) {
                    
                    cameraId = id
                    exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val rawSize = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.firstOrNull()
                    
                    if (rawSize != null) {
                        canvasWidth = rawSize.width
                        canvasHeight = rawSize.height
                        setupImageReader(rawSize.width, rawSize.height)
                        cameraManager.openCamera(id, stateCallback, backgroundHandler)
                        return
                    }
                }
            }
            Toast.makeText(this, "Hardware Not Supported", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
        }
        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
        }
    }

    private fun setupImageReader(w: Int, h: Int) {
        imageReader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, 5).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (isCapturing) {
                    // Offload math to stacking thread immediately
                    stackingHandler?.post { processFrame(img) }
                } else {
                    img.close()
                }
            }, backgroundHandler)
        }
    }

    private fun createPreviewSession() {
        val surface = Surface(binding.textureView.surfaceTexture)
        cameraDevice?.createCaptureSession(listOf(surface, imageReader?.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                updatePreview()
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {}
        }, backgroundHandler)
    }

    private fun updatePreview() {
        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
        builder.addTarget(Surface(binding.textureView.surfaceTexture))
        
        // Astro Locks
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) // Infinity
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, 1600) // High ISO for preview

        captureSession?.setRepeatingRequest(builder.build(), null, backgroundHandler)
    }

    private fun startCaptureBurst() {
        isCapturing = true
        currentCaptureCount = 0
        masterCanvas = LongArray(canvasWidth * canvasHeight)
        
        runOnUiThread {
            binding.statusOverlay.visibility = View.VISIBLE
            binding.btnCapture.isEnabled = false
            binding.tvStatus.text = "Stacking: 0/$stackCount"
        }

        val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE) ?: return
        builder.addTarget(imageReader!!.surface)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, 800)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)

        val requests = List(stackCount) { builder.build() }
        captureSession?.captureBurst(requests, null, backgroundHandler)
    }

    private fun processFrame(image: Image) {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val canvas = masterCanvas ?: return

        val shortBuffer = buffer.asShortBuffer()
        var cIdx = 0
        for (y in 0 until canvasHeight) {
            var bIdx = (y * rowStride) / 2
            for (x in 0 until canvasWidth) {
                canvas[cIdx] += (shortBuffer.get(bIdx).toInt() and 0xFFFF).toLong()
                cIdx++
                bIdx += pixelStride / 2
            }
        }
        image.close()
        
        currentCaptureCount++
        runOnUiThread {
            binding.tvStatus.text = "Stacking: $currentCaptureCount/$stackCount"
        }

        if (currentCaptureCount >= stackCount) finalizeStack()
    }

    private fun finalizeStack() {
        val canvas = masterCanvas ?: return
        val finalBuffer = ByteBuffer.allocateDirect(canvasWidth * canvasHeight * 2)
        
        for (i in canvas.indices) {
            val avg = (canvas[i] / stackCount).coerceIn(0, 65535).toShort()
            finalBuffer.putShort(avg)
        }
        finalBuffer.rewind()

        try {
            val folder = File(getExternalFilesDir(null), "AstroStacker")
            if (!folder.exists()) folder.mkdirs()
            val file = File(folder, "STACK_${System.currentTimeMillis()}.raw")
            
            FileOutputStream(file).use { it.channel.write(finalBuffer) }
            runOnUiThread { 
                Toast.makeText(this, "Saved to ${file.name}", Toast.LENGTH_SHORT).show()
                resetUI()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun resetUI() {
        isCapturing = false
        masterCanvas = null
        binding.statusOverlay.visibility = View.GONE
        binding.btnCapture.isEnabled = true
        updatePreview()
    }

    private fun closeCamera() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
    }
}