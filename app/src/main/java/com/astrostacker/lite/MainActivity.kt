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
import android.util.Log
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

    private var stackingThread: HandlerThread? = null
    private var stackingHandler: Handler? = null

    private var exposureRange: Range<Long>? = null
    private var currentExposure: Long = 100000000L // 100ms default
    private var stackCount: Int = 25
    
    private var isCapturing = false
    private var currentCaptureCount = 0
    private var masterCanvas: LongArray? = null
    private var canvasWidth = 0
    private var canvasHeight = 0
    
    private var isDngMode = true
    private var dngCreator: DngCreator? = null

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
                    val min = it.lower.toDouble().coerceAtLeast(1000.0)
                    val max = it.upper.toDouble()
                    val p = progress / 100.0
                    val value = Math.exp(Math.log(min) + p * (Math.log(max) - Math.log(min))).toLong()
                    currentExposure = value
                    binding.tvExposure.text = "Exposure: ${value / 1000000}ms"
                    // Lag Fix: Don't update preview exposure during adjustment
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

        binding.btnSettings.setOnClickListener {
            isDngMode = !isDngMode
            Toast.makeText(this, "Save Format: ${if(isDngMode) "DNG" else "RAW BIN"}", Toast.LENGTH_SHORT).show()
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
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (binding.textureView.isAvailable) openCamera()
        }
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
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (cameraDevice != null) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            return
        }

        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_CAPABILITY_FLAGS) // Just iterate all
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                
                if (caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) {
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
        } catch (e: Exception) { e.printStackTrace() }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) { cameraDevice?.close(); cameraDevice = null }
        override fun onError(camera: CameraDevice, error: Int) { cameraDevice?.close(); cameraDevice = null }
    }

    private fun setupImageReader(w: Int, h: Int) {
        imageReader = ImageReader.newInstance(w, h, ImageFormat.RAW_SENSOR, 5).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (isCapturing) stackingHandler?.post { processFrame(img) } else img.close()
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
        
        // LAG FIX: Auto exposure for preview, fixed only for capture
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f) 

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
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

        val chars = cameraManager.getCameraCharacteristics(cameraId!!)
        dngCreator = DngCreator(chars, builder.build())

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
        runOnUiThread { binding.tvStatus.text = "Stacking: $currentCaptureCount/$stackCount" }
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
            val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AstroStacker")
            if (!publicDir.exists()) publicDir.mkdirs()
            
            val ext = if (isDngMode) "dng" else "raw"
            val file = File(publicDir, "ASTRO_${System.currentTimeMillis()}.$ext")
            
            FileOutputStream(file).use { out -> out.channel.write(finalBuffer) }

            // Thumbnail Fix: Force Media Scanner update
            android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null) { _, _ -> }

            runOnUiThread { 
                Toast.makeText(this, "Saved to Gallery: ${file.name}", Toast.LENGTH_LONG).show()
                resetUI()
            }
        } catch (e: Exception) { 
            e.printStackTrace()
            runOnUiThread { Toast.makeText(this, "Save Failed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun resetUI() {
        isCapturing = false
        masterCanvas = null
        dngCreator?.close()
        dngCreator = null
        runOnUiThread {
            binding.statusOverlay.visibility = View.GONE
            binding.btnCapture.isEnabled = true
            updatePreview()
        }
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }
}
