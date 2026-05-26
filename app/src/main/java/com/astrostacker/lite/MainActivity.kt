package com.astrostacker.lite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.astrostacker.lite.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraEngine: CameraEngine
    private lateinit var stackingEngine: StackingEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Engines
        stackingEngine = StackingEngine(this, binding)
        cameraEngine = CameraEngine(this, binding, stackingEngine)

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions()) {
            cameraEngine.start()
        } else {
            requestPermissions()
        }
    }

    override fun onPause() {
        cameraEngine.stop()
        super.onPause()
    }

    private fun hasPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            cameraEngine.start()
        } else {
            Toast.makeText(this, "Camera permission required.", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        // Main UI
        binding.btnDevOptions.setOnClickListener {
            binding.devOptionsPanel.visibility = View.VISIBLE
            binding.mainUiLayout.visibility = View.GONE
        }

        binding.btnCloseDev.setOnClickListener {
            binding.devOptionsPanel.visibility = View.GONE
            binding.mainUiLayout.visibility = View.VISIBLE
            cameraEngine.updatePreview() // Apply any changed dev settings
        }

        binding.btnCapture.setOnClickListener {
            if (cameraEngine.isReady()) {
                startCountdown()
            } else {
                Toast.makeText(this, "Camera not ready yet.", Toast.LENGTH_SHORT).show()
            }
        }

        // Feature Toggles
        binding.swAutoPreview.setOnCheckedChangeListener { _, isChecked ->
            cameraEngine.isAutoPreviewEnabled = isChecked
            cameraEngine.updatePreview()
        }

        binding.swHotPixel.setOnCheckedChangeListener { _, isChecked ->
            stackingEngine.isHotPixelRemovalEnabled = isChecked
        }

        // Dev Options - Brightness Boost
        binding.sbDevBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val boost = 1.0f + (progress / 100.0f) * 9.0f // 1.0x to 10.0x
                binding.tvDevBrightness.text = "Brightness Boost: ${String.format("%.1fx", boost)}"
                stackingEngine.brightnessMultiplier = boost
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Dev Options - Zoom
        binding.sbDevZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoom = 1.0f + (progress / 100.0f) * 9.0f
                binding.tvDevZoom.text = "Digital Zoom: ${String.format("%.1fx", zoom)}"
                cameraEngine.currentZoom = zoom
                cameraEngine.updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Dev Options - ISO
        binding.sbDevIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                cameraEngine.setIsoProgress(progress)
                binding.tvDevIso.text = "ISO: ${cameraEngine.currentIso}"
                cameraEngine.updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Dev Options - Exposure
        binding.sbDevExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                cameraEngine.setExposureProgress(progress)
                binding.tvDevExposure.text = "Exposure: ${cameraEngine.currentExposure / 1_000_000}ms"
                cameraEngine.updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Dev Options - Stack Count
        binding.sbDevStack.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val count = (progress.coerceAtLeast(1) * 0.5f).toInt().coerceAtLeast(1) // 1 to 50
                binding.tvDevStack.text = "Stack Target: $count frames"
                stackingEngine.targetStackCount = count
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Dev Options - Focus
        binding.sbDevFocus.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                // 0 = Infinity (0.0f), 100 = Macro (10.0f diopters roughly)
                val focus = (progress / 100.0f) * 10.0f
                binding.tvDevFocus.text = if (progress == 0) "Focus: Infinity" else "Focus: ${String.format("%.1f", focus)}"
                cameraEngine.currentFocusDistance = focus
                cameraEngine.updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Dev Options - Toggles
        binding.btnDevFormat.setOnClickListener {
            stackingEngine.isDngMode = !stackingEngine.isDngMode
            binding.btnDevFormat.text = "Format: ${if (stackingEngine.isDngMode) "DNG" else "RAW"}"
        }

        binding.btnDevTorch.setOnClickListener {
            cameraEngine.isTorchOn = !cameraEngine.isTorchOn
            binding.btnDevTorch.text = "Torch: ${if (cameraEngine.isTorchOn) "ON" else "OFF"}"
            cameraEngine.updatePreview()
        }
    }

    private fun startCountdown() {
        binding.btnCapture.visibility = View.GONE
        binding.btnDevOptions.visibility = View.GONE
        binding.tvTimer.visibility = View.VISIBLE
        binding.processingOverlay.visibility = View.GONE
        
        object : CountDownTimer(5000, 1000) {
            override fun onTick(m: Long) { binding.tvTimer.text = "${m / 1000 + 1}" }
            override fun onFinish() {
                binding.tvTimer.visibility = View.GONE
                cameraEngine.startBurstCapture()
            }
        }.start()
    }
}