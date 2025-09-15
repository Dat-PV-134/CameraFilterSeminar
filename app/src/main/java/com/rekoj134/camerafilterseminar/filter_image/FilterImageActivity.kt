package com.rekoj134.camerafilterseminar.filter_image

import android.graphics.BitmapFactory
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.rekoj134.camerafilterseminar.R
import com.rekoj134.camerafilterseminar.databinding.ActivityFilterImageBinding

class FilterImageActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFilterImageBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityFilterImageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bitmap = BitmapFactory.decodeResource(resources, R.drawable.img_test)
        binding.glSurfaceView.setEGLContextClientVersion(2)
        binding.glSurfaceView.setRenderer(ImageRenderer(bitmap))
        binding.glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
}
