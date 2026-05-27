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

        stackingEngine = StackingEngine(this, binding)
        cameraEngine = CameraEngine(this, binding, stackingEngine)

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraEngine.start()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onPause() {
        cameraEngine.stop()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            cameraEngine.start()
        }
    }

    private fun setupUI() {
        binding.btnDevOptions.setOnClickListener {
            binding.devOptionsPanel.visibility = View.VISIBLE
            binding.mainUiLayout.visibility = View.GONE
        }

        binding.btnCloseDev.setOnClickListener {
            binding.devOptionsPanel.visibility = View.GONE
            binding.mainUiLayout.visibility = View.VISIBLE
            cameraEngine.updatePreview()
        }

        binding.btnCapture.setOnClickListener {
            if (cameraEngine.isReady()) startCountdown()
        }

        // Toggles
        binding.swSigmaClip.setOnCheckedChangeListener { _, isChecked -> stackingEngine.isSigmaClippingEnabled = isChecked }
        binding.swHotPixel.setOnCheckedChangeListener { _, isChecked -> stackingEngine.isHotPixelRemovalEnabled = isChecked }
        binding.swAutoPreview.setOnCheckedChangeListener { _, isChecked -> 
            cameraEngine.isAutoPreviewEnabled = isChecked 
            cameraEngine.updatePreview()
        }

        binding.sbDevBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                stackingEngine.brightnessMultiplier = 1.0f + (progress / 100.0f) * 9.0f
                binding.tvDevBrightness.text = "Brightness Boost: ${String.format("%.1fx", stackingEngine.brightnessMultiplier)}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.sbDevIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                cameraEngine.setIsoProgress(progress)
                binding.tvDevIso.text = "ISO: ${cameraEngine.currentIso}"
                cameraEngine.updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.sbDevExposure.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                cameraEngine.setExposureProgress(progress)
                binding.tvDevExposure.text = "Exposure: ${cameraEngine.currentExposure / 1_000_000}ms"
                cameraEngine.updatePreview()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.sbDevStack.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                stackingEngine.targetStackCount = (progress.coerceAtLeast(1) * 0.5f).toInt().coerceAtLeast(1)
                binding.tvDevStack.text = "Stack Target: ${stackingEngine.targetStackCount} frames"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.btnDevFormat.setOnClickListener {
            stackingEngine.isDngMode = !stackingEngine.isDngMode
            binding.btnDevFormat.text = "Save Format: ${if (stackingEngine.isDngMode) "DNG" else "RAW BIN"}"
        }
    }

    private fun startCountdown() {
        binding.mainUiLayout.visibility = View.GONE
        binding.tvTimer.visibility = View.VISIBLE
        object : CountDownTimer(5000, 1000) {
            override fun onTick(m: Long) { binding.tvTimer.text = "${m / 1000 + 1}" }
            override fun onFinish() {
                binding.tvTimer.visibility = View.GONE
                cameraEngine.startBurstCapture()
            }
        }.start()
    }
}