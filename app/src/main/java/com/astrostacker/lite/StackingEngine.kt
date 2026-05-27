package com.astrostacker.lite

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraManager
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.astrostacker.lite.databinding.ActivityMainBinding
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StackingEngine(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding
) {
    private val TAG = "StackingEngine"
    private val cameraManager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    var activeCameraId: String? = null
    var targetStackCount = 25
    var brightnessMultiplier = 1.0f
    var isDngMode = true
    var isHotPixelRemovalEnabled = true
    var isSigmaClippingEnabled = true

    private var canvasWidth = 0
    private var canvasHeight = 0
    private var framesProcessed = 0
    private var isCapturing = false
    private var isSaving = false
    
    private var masterCanvas: IntArray? = null
    private var minCanvas: ShortArray? = null
    private var maxCanvas: ShortArray? = null
    
    private var dngCaptureResult: TotalCaptureResult? = null

    fun setup(w: Int, h: Int) { canvasWidth = w; canvasHeight = h }

    fun startCapture() {
        isCapturing = true; framesProcessed = 0; isSaving = false; dngCaptureResult = null
        try { 
            masterCanvas = IntArray(canvasWidth * canvasHeight)
            if (isSigmaClippingEnabled) {
                minCanvas = ShortArray(canvasWidth * canvasHeight) { 32767 }
                maxCanvas = ShortArray(canvasWidth * canvasHeight) { 0 }
            } else {
                minCanvas = null
                maxCanvas = null
            }
        } catch (e: OutOfMemoryError) { 
            activity.runOnUiThread { Toast.makeText(activity, "Out of Memory. Clear RAM.", Toast.LENGTH_LONG).show() }
            isCapturing = false; return 
        }
        activity.runOnUiThread {
            binding.processingOverlay.visibility = View.VISIBLE
            binding.tvProgress.text = "Capturing: 0/$targetStackCount"
        }
    }

    fun onCaptureResultReceived(res: TotalCaptureResult) { if (dngCaptureResult == null) dngCaptureResult = res }

    fun onSequenceCompleted() { if (!isSaving && framesProcessed > 0) { isCapturing = false; executeSaving() } }

    val imageListener = ImageReader.OnImageAvailableListener { reader ->
        var img: Image? = null
        try {
            img = reader.acquireNextImage()
            if (img == null || !isCapturing || isSaving) return@OnImageAvailableListener

            val shortBuffer = img.planes[0].buffer.asShortBuffer()
            val rs = img.planes[0].rowStride; val ps = img.planes[0].pixelStride
            val canvas = masterCanvas ?: return@OnImageAvailableListener
            val minC = minCanvas
            val maxC = maxCanvas
            
            var idx = 0
            for (y in 0 until canvasHeight) {
                var bIdx = (y * rs) / 2
                for (x in 0 until canvasWidth) {
                    val p = shortBuffer.get(bIdx).toInt() and 0xFFFF
                    canvas[idx] += p
                    if (minC != null && maxC != null) {
                        val pShort = p.toShort()
                        if (p < minC[idx]) minC[idx] = pShort
                        if (p > maxC[idx]) maxC[idx] = pShort
                    }
                    idx++
                    bIdx += ps / 2
                }
            }
            framesProcessed++
            activity.runOnUiThread { binding.tvProgress.text = "Capturing: $framesProcessed/$targetStackCount" }
            if (framesProcessed >= targetStackCount) { isCapturing = false; executeSaving() }
        } catch (e: Exception) { Log.e(TAG, "Process error", e) } finally { img?.close() }
    }

    private fun executeSaving() {
        if (isSaving) return
        isSaving = true
        val actualFramesStacked = framesProcessed
        val canvas = masterCanvas ?: return
        activity.runOnUiThread { binding.tvProgress.text = "Finalizing..." }

        Thread {
            try {
                var effectiveFrames = actualFramesStacked
                
                // Min/Max Outlier Rejection (Pseudo-Sigma Clipping)
                if (isSigmaClippingEnabled && minCanvas != null && maxCanvas != null && actualFramesStacked >= 3) {
                    activity.runOnUiThread { binding.tvProgress.text = "Removing Outliers..." }
                    for (i in canvas.indices) {
                        val minVal = minCanvas!![i].toInt() and 0xFFFF
                        val maxVal = maxCanvas!![i].toInt() and 0xFFFF
                        canvas[i] = canvas[i] - minVal - maxVal
                    }
                    effectiveFrames -= 2
                }

                // Bayer-Aware Median Hot Pixel Filter
                if (isHotPixelRemovalEnabled) {
                    activity.runOnUiThread { binding.tvProgress.text = "Filtering Hot Pixels..." }
                    applyAdvancedHotPixelFilter(canvas, effectiveFrames)
                }

                activity.runOnUiThread { binding.tvProgress.text = "Encoding Output..." }
                val finalBuffer = ByteBuffer.allocateDirect(canvasWidth * canvasHeight * 2).apply { order(ByteOrder.nativeOrder()) }
                for (i in canvas.indices) {
                    val pixel = (canvas[i].toFloat() / effectiveFrames) * brightnessMultiplier
                    finalBuffer.putShort(pixel.coerceIn(0f, 65535f).toInt().toShort())
                }
                finalBuffer.rewind()

                val name = "ASTRO_${System.currentTimeMillis()}.${if (isDngMode) "dng" else "raw"}"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (isDngMode) "image/x-adobe-dng" else "application/octet-stream")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AstroStacker")
                }
                val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    activity.contentResolver.openOutputStream(it)?.use { out ->
                        if (isDngMode && dngCaptureResult != null) {
                            val dng = DngCreator(cameraManager.getCameraCharacteristics(activeCameraId!!), dngCaptureResult!!)
                            dng.writeByteBuffer(out, Size(canvasWidth, canvasHeight), finalBuffer, 0L)
                            dng.close()
                        } else {
                            val bytes = ByteArray(finalBuffer.remaining()); finalBuffer.get(bytes); out.write(bytes)
                        }
                    }
                    activity.runOnUiThread { Toast.makeText(activity, "Saved to Gallery", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) { activity.runOnUiThread { Toast.makeText(activity, "Save Failed", Toast.LENGTH_SHORT).show() } }
            finally { 
                masterCanvas = null
                minCanvas = null
                maxCanvas = null
                isSaving = false
                resetUI() 
            }
        }.start()
    }

    /**
     * Replaces abnormally bright isolated pixels with the max of their 8 same-color neighbors.
     * Prevents clipping stars, because a star will illuminate multiple same-color pixels.
     */
    private fun applyAdvancedHotPixelFilter(canvas: IntArray, frames: Int) {
        val spike = 2000 * frames
        for (y in 2 until canvasHeight - 2) {
            var i = y * canvasWidth + 2
            for (x in 2 until canvasWidth - 2) {
                val p = canvas[i]
                
                val n1 = canvas[i - 2 - 2 * canvasWidth]
                val n2 = canvas[i - 2 * canvasWidth]
                val n3 = canvas[i + 2 - 2 * canvasWidth]
                val n4 = canvas[i - 2]
                val n5 = canvas[i + 2]
                val n6 = canvas[i - 2 + 2 * canvasWidth]
                val n7 = canvas[i + 2 * canvasWidth]
                val n8 = canvas[i + 2 + 2 * canvasWidth]
                
                var maxN = n1
                if (n2 > maxN) maxN = n2
                if (n3 > maxN) maxN = n3
                if (n4 > maxN) maxN = n4
                if (n5 > maxN) maxN = n5
                if (n6 > maxN) maxN = n6
                if (n7 > maxN) maxN = n7
                if (n8 > maxN) maxN = n8
                
                if (p > maxN + spike) canvas[i] = maxN
                i++
            }
        }
    }

    fun resetUI() {
        activity.runOnUiThread {
            binding.processingOverlay.visibility = View.GONE
            binding.mainUiLayout.visibility = View.VISIBLE
            binding.btnCapture.visibility = View.VISIBLE
            binding.btnDevOptions.visibility = View.VISIBLE
        }
    }
}