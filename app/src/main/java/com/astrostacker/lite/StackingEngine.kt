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

    private var canvasWidth = 0
    private var canvasHeight = 0
    
    private var isCapturing = false
    private var framesProcessed = 0
    private var masterCanvas: IntArray? = null
    private var dngCaptureResult: TotalCaptureResult? = null
    private var isSaving = false

    fun setup(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
    }

    fun startCapture() {
        isCapturing = true
        framesProcessed = 0
        isSaving = false
        dngCaptureResult = null
        
        try {
            masterCanvas = IntArray(canvasWidth * canvasHeight)
        } catch (e: OutOfMemoryError) {
            activity.runOnUiThread { Toast.makeText(activity, "Out of Memory. Clear RAM.", Toast.LENGTH_LONG).show() }
            isCapturing = false
            return
        }

        activity.runOnUiThread {
            binding.processingOverlay.visibility = View.VISIBLE
            binding.tvProgress.text = "Capturing: 0/$targetStackCount"
        }
    }

    fun onCaptureResultReceived(result: TotalCaptureResult) {
        if (dngCaptureResult == null) {
            dngCaptureResult = result
        }
    }

    fun onSequenceCompleted() {
        if (!isSaving && framesProcessed > 0) {
            isCapturing = false
            executeSavingLogic()
        } else if (framesProcessed == 0) {
            isCapturing = false
            resetUI()
            activity.runOnUiThread { Toast.makeText(activity, "No frames captured.", Toast.LENGTH_SHORT).show() }
        }
    }

    val imageListener = ImageReader.OnImageAvailableListener { reader ->
        var image: Image? = null
        try {
            image = reader.acquireNextImage()
            if (image == null || !isCapturing || isSaving) return@OnImageAvailableListener

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
            activity.runOnUiThread { binding.tvProgress.text = "Capturing: $framesProcessed/$targetStackCount" }
            
            if (framesProcessed >= targetStackCount && !isSaving) {
                isCapturing = false
                executeSavingLogic()
            }
        } catch (e: Exception) { 
            Log.e(TAG, "Process error", e) 
        } finally { 
            image?.close() 
        }
    }

    private fun executeSavingLogic() {
        isSaving = true
        val actualFramesStacked = framesProcessed
        val canvas = masterCanvas

        if (canvas == null || actualFramesStacked == 0) {
            resetUI()
            return
        }

        activity.runOnUiThread { binding.tvProgress.text = "Processing... ($actualFramesStacked f)" }

        Thread {
            try {
                // Apply Bayer-aware Hot Pixel Removal in-place to save memory
                if (isHotPixelRemovalEnabled) {
                    applyHotPixelRemoval(canvas, canvasWidth, canvasHeight, actualFramesStacked)
                }

                val finalBuffer = ByteBuffer.allocateDirect(canvasWidth * canvasHeight * 2)
                finalBuffer.order(ByteOrder.nativeOrder())
                
                // Additive/Average logic with brightness multiplier
                for (i in canvas.indices) {
                    val rawPixel = canvas[i].toFloat()
                    val averaged = rawPixel / actualFramesStacked
                    val brightened = averaged * brightnessMultiplier
                    finalBuffer.putShort(brightened.coerceIn(0f, 65535f).toInt().toShort())
                }
                finalBuffer.rewind()

                val ext = if (isDngMode && dngCaptureResult != null) "dng" else "raw"
                val name = "ASTRO_${System.currentTimeMillis()}.$ext"

                activity.runOnUiThread { binding.tvProgress.text = "Saving File..." }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (ext == "dng") "image/x-adobe-dng" else "application/octet-stream")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AstroStacker")
                    }
                }

                val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    activity.contentResolver.openOutputStream(uri)?.use { out ->
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
                    activity.runOnUiThread { Toast.makeText(activity, "Saved successfully!", Toast.LENGTH_LONG).show() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Save error", e)
                activity.runOnUiThread { Toast.makeText(activity, "Save Failed", Toast.LENGTH_LONG).show() }
            } finally {
                masterCanvas = null
                isSaving = false
                resetUI()
            }
        }.start()
    }

    /**
     * Replaces abnormally bright isolated pixels with the median of their same-color neighbors.
     * Since this is a RAW Bayer CFA, same-color neighbors are 2 pixels away.
     */
    private fun applyHotPixelRemoval(canvas: IntArray, width: Int, height: Int, frames: Int) {
        // Base noise threshold scales with frames. 
        // 5000 is an arbitrary brightness difference that usually signifies a stuck sensor pixel.
        val spikeThreshold = 5000 * frames 
        
        // Skip edges
        for (y in 2 until height - 2) {
            var idx = y * width + 2
            for (x in 2 until width - 2) {
                val p = canvas[idx]
                
                // Compare to same color pixels in the Bayer pattern
                val left = canvas[idx - 2]
                val right = canvas[idx + 2]
                val up = canvas[idx - 2 * width]
                val down = canvas[idx + 2 * width]
                
                val avgNeighbors = (left + right + up + down) / 4
                
                // If it is significantly brighter than its direct neighbors, it's a hot pixel.
                if (p > avgNeighbors * 1.5 && (p - avgNeighbors) > spikeThreshold) {
                    canvas[idx] = avgNeighbors
                }
                idx++
            }
        }
    }

    fun resetUI() {
        activity.runOnUiThread {
            binding.processingOverlay.visibility = View.GONE
            binding.btnCapture.visibility = View.VISIBLE
            binding.btnDevOptions.visibility = View.VISIBLE
        }
    }
}