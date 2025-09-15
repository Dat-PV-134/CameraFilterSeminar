package com.rekoj134.camerafilterseminar.filter_camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.otaliastudios.cameraview.controls.Mode
import com.rekoj134.camerafilterseminar.R
import com.rekoj134.camerafilterseminar.databinding.ActivityFilterCameraBinding

class FilterCameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFilterCameraBinding

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilterCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        binding.cameraView.setLifecycleOwner(this)
        binding.cameraView.mode = Mode.PICTURE
        binding.cameraView.filter = RefractionFilter(this, R.drawable.test_texture)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.cameraView.destroy()
    }
}
