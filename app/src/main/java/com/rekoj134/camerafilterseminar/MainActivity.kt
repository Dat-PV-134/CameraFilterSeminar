package com.rekoj134.camerafilterseminar

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rekoj134.camerafilterseminar.databinding.ActivityMainBinding
import com.rekoj134.camerafilterseminar.filter_camera.FilterCameraActivity
import com.rekoj134.camerafilterseminar.filter_image.FilterImageActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnImageFilter.setOnClickListener {
            startActivity(Intent(this@MainActivity, FilterImageActivity::class.java))
        }

        binding.btnCameraFilter.setOnClickListener {
            startActivity(Intent(this@MainActivity, FilterCameraActivity::class.java))
        }
    }
}
