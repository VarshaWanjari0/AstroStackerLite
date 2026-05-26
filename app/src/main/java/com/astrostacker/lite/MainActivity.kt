package com.astrostacker.lite

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.provider.MediaStore
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
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln

class MainActivity : AppCompatActivity() {

    private val TAG = "AstroV4"
    private lateinit var binding: ActivityMainBinding

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var activeCameraId: String? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    private var sensorRect: Rect? = null
    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var isManualSupported = false

    private var currentZoom = 1.0f
    private val maxZoom = 10.0f
    private var currentIso = 800
    private var currentExposure = 100_000_000L
    private var targetStackCount = 25
    private var isTorchOn = false
    private var isDngMode = true

    private var isCapturing = false
    private var framesProcessed = 0
    private var masterCanvas: IntArray? = null
    private var canvasWidth = 0
    private var canvasHeight = 0
    private var dngCaptureResult: TotalCaptureResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        startThreads()
        if (binding.textureView.isAvailable) {
            openCamera()
        } else {
            binding.textureView.surfaceTextureListener = textureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopThreads()
        super.onPause()
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = openCamera()
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun setupUI() {
        binding.btnFormat.setOnClickListener {
            if (isCapturing) return@setOnClickListener
            isDngMode = !isDngMode
            binding.btnFormat.text = if (isDngMode) "DNG" else "RAW"
        }

        binding.btnTorch.setOnClickListener {
            if (isCapturing) return@setOnClickListener
            isTorchOn = !isTorchOn
            updatePreview()
        }

        binding.btnCapture.setOnClickListener {
            if (!isCapturing && activeCameraId != null) startCountdown()
        }

        binding.sbZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                currentZoom = 1.0f + (progress / 100.0f) * (maxZoom - 1.0f)
                binding.tvZoom.text = "${String.format("%.1fx", currentZoom)}"
                if (!isCapturing) updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.sbExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                exposureRange?.let {
                    val min = it.lower.toDouble().coerceAtLeast(1000.0)
                    val max = it.upper.toDouble()
                    currentExposure = exp(ln(min) + (progress / 100.0) * (ln(max) - ln(min))).toLong()
                    binding.tvExposure.text = "${currentExposure / 1_000_000}ms"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.sbIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                isoRange?.let {
                    val min = it.lower.toDouble()
                    val max = it.upper.toDouble()
                    currentIso = exp(ln(min) + (progress / 100.0) * (ln(max) - ln(min))).toInt()
                    binding.tvIso.text = "ISO $currentIso"
                    if (!isCapturing) updatePreview()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.sbStack.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                targetStackCount = progress.coerceAtLeast(1)
                binding.tvStack.text = "$targetStackCount f"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun startThreads() {
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        imageThread = HandlerThread("ImageThread").apply { start() }
        imageHandler = Handler(imageThread!!.looper)
    }

    private fun stopThreads() {
        cameraThread?.quitSafely(); cameraThread?.join(); cameraThread = null; cameraHandler = null
        imageThread?.quitSafely(); imageThread?.join(); imageThread = null; imageHandler = null
    }

    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }

        try {
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)

                if (facing == CameraCharacteristics.LENS_FACING_BACK && caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true) {
                    activeCameraId = id
                    isManualSupported = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
                    isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                    
                    // FIX: Find proper preview size for TextureView
                    val previewSize = map.getOutputSizes(SurfaceTexture::class.java)?.firstOrNull() ?: Size(1920, 1080)
                    binding.textureView.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)

                    // Find RAW size
                    val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
                    if (rawSize != null) {
                        canvasWidth = rawSize.width
                        canvasHeight = rawSize.height
                        imageReader = ImageReader.newInstance(canvasWidth, canvasHeight, ImageFormat.RAW_SENSOR, 2).apply {
                            setOnImageAvailableListener(imageListener, imageHandler)
                        }
                        cameraManager.openCamera(id, cameraStateCallback, cameraHandler)
                        return
                    }
                }
            }
            Toast.makeText(this, "RAW Camera not found", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Log.e(TAG, "Open camera error", e) }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
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
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Session configure failed")
                }
            }, cameraHandler)
        } catch (e: Exception) { Log.e(TAG, "createSession error", e) }
    }

    private fun updatePreview() {
        if (cameraDevice == null || captureSession == null || isCapturing) return
        try {
            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(Surface(binding.textureView.surfaceTexture!!))
            applySettings(builder)
            captureSession!!.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) { Log.e(TAG, "updatePreview error", e) }
    }

    private fun startCountdown() {
        binding.controlsPanel.visibility = View.GONE
        binding.tvTimer.visibility = View.VISIBLE
        
        object : CountDownTimer(5000, 1000) {
            override fun onTick(m: Long) { binding.tvTimer.text = "${m / 1000 + 1}" }
            override fun onFinish() {
                binding.tvTimer.visibility = View.GONE
                startBurst()
            }
        }.start()
    }

    private fun startBurst() {
        if (cameraDevice == null || captureSession == null) return
        try {
            masterCanvas = IntArray(canvasWidth * canvasHeight)
            isCapturing = true
            framesProcessed = 0
            dngCaptureResult = null

            binding.processingOverlay.visibility = View.VISIBLE
            binding.tvProgress.text = "Stacking: 0/$targetStackCount"

            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(imageReader!!.surface!!)
            applySettings(builder)

            val requests = List(targetStackCount) { builder.build() }
            captureSession!!.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (dngCaptureResult == null) dngCaptureResult = result
                }
            }, cameraHandler)
        } catch (e: Exception) { 
            Log.e(TAG, "Burst error", e)
            resetUI()
        }
    }

    private fun applySettings(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
        builder.set(CaptureRequest.FLASH_MODE, if (isTorchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)

        if (isManualSupported) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposure)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
        }

        sensorRect?.let {
            val cx = it.centerX(); val cy = it.centerY()
            val dx = (0.5f * it.width() / currentZoom).toInt()
            val dy = (0.5f * it.height() / currentZoom).toInt()
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cx - dx, cy - dy, cx + dx, cy + dy))
        }
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        var image: Image? = null
        try {
            image = reader.acquireNextImage()
            if (image == null || !isCapturing) return@OnImageAvailableListener

            val plane = image.planes[0]
            val buffer = plane.buffer
            val shortBuffer = buffer.asShortBuffer()
            val rs = plane.rowStride
            val ps = plane.pixelStride
            
            val canvas = masterCanvas ?: return@OnImageAvailableListener
            var idx = 0
            for (y in 0 until canvasHeight) {
                var bIdx = (y * rs) / 2
                for (x in 0 until canvasWidth) {
                    canvas[idx++] += shortBuffer.get(bIdx).toInt() and 0xFFFF
                    bIdx += ps / 2
                }
            }

            framesProcessed++
            runOnUiThread { binding.tvProgress.text = "Stacking: $framesProcessed/$targetStackCount" }
            
            if (framesProcessed >= targetStackCount) {
                isCapturing = false
                runOnUiThread { binding.tvProgress.text = "Saving..." }
                imageHandler?.post { saveImage() }
            }
        } catch (e: Exception) { Log.e(TAG, "Process error", e) }
        finally { image?.close() }
    }

    private fun saveImage() {
        val canvas = masterCanvas ?: return
        try {
            val finalBuffer = ByteBuffer.allocateDirect(canvasWidth * canvasHeight * 2)
            finalBuffer.order(ByteOrder.nativeOrder())
            for (i in canvas.indices) {
                finalBuffer.putShort((canvas[i] / targetStackCount).coerceIn(0, 65535).toShort())
            }
            finalBuffer.rewind()

            val ext = if (isDngMode && dngCaptureResult != null) "dng" else "raw"
            val name = "ASTRO_${System.currentTimeMillis()}.$ext"

            // FIX: Use MediaStore for universal Android 10+ saving without Scoped Storage errors
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (ext == "dng") "image/x-adobe-dng" else "application/octet-stream")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AstroStacker")
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    if (ext == "dng") {
                        val chars = cameraManager.getCameraCharacteristics(activeCameraId!!)
                        val dng = DngCreator(chars, dngCaptureResult!!)
                        dng.writeByteBuffer(out, Size(canvasWidth, canvasHeight), finalBuffer, 0L)
                        dng.close()
                    } else {
                        val bytes = ByteArray(finalBuffer.remaining())
                        finalBuffer.get(bytes)
                        out.write(bytes)
                    }
                }
                runOnUiThread { Toast.makeText(this, "Saved to Pictures/AstroStacker", Toast.LENGTH_LONG).show() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Save error", e)
            runOnUiThread { Toast.makeText(this, "Save Failed", Toast.LENGTH_LONG).show() }
        } finally {
            masterCanvas = null
            runOnUiThread { resetUI() }
        }
    }

    private fun resetUI() {
        isCapturing = false
        binding.processingOverlay.visibility = View.GONE
        binding.controlsPanel.visibility = View.VISIBLE
        updatePreview()
    }

    private fun closeCamera() {
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            imageReader?.close(); imageReader = null
        } catch (e: Exception) { Log.e(TAG, "Close error", e) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (binding.textureView.isAvailable) openCamera()
        }
    }
}
