package com.rekoj134.camerafilterseminar.fish_eyes_cameraX

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.rekoj134.camerafilterseminar.BitmapUtils
import com.rekoj134.camerafilterseminar.databinding.ActivityFishEyesCameraXactivityBinding
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class FishEyesCameraXActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFishEyesCameraXactivityBinding
    private lateinit var renderer: EyeWarpRenderer
    private var faceLandmarker: FaceLandmarker? = null
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

    private val LEFT_EYE_IDX = listOf(33, 133, 157, 158, 159, 160, 161, 163)
    private val RIGHT_EYE_IDX = listOf(362, 385, 386, 387, 388, 390, 263, 373)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                renderer.surfaceReadyCallback?.let { cb ->
                    // Nếu surface đã sẵn sàng thì start camera
                    renderer.surfaceTexture?.let { startCamera(it) }
                }
            } else {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFishEyesCameraXactivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderer = EyeWarpRenderer()
        binding.glSurface.setEGLContextClientVersion(2)
        binding.glSurface.setRenderer(renderer)
        // Dùng continuous để demo mượt; muốn tiết kiệm hơn có thể dùng WHEN_DIRTY + onFrameAvailable
        binding.glSurface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        renderer.surfaceReadyCallback = { surfaceTexture ->
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                initMediaPipe()
                startCamera(surfaceTexture)
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun initMediaPipe() {
        try {
            val baseOptions = com.google.mediapipe.tasks.core.BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(com.google.mediapipe.tasks.vision.core.RunningMode.VIDEO)
                .setNumFaces(1)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(this, options)
        } catch (e: Exception) {
            Log.e("FishEyesCameraX", "MediaPipe init error", e)
        }
    }

    private fun startCamera(surfaceTexture: SurfaceTexture) {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = runCatching { cameraProviderFuture.get() }.getOrNull() ?: return@addListener
            bindUseCases(cameraProvider, surfaceTexture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases(cameraProvider: ProcessCameraProvider, surfaceTexture: SurfaceTexture) {
        val preview = Preview.Builder().build()

        // QUAN TRỌNG: setDefaultBufferSize theo request.resolution trên GL thread
        preview.setSurfaceProvider { request ->
            val size = request.resolution
            binding.glSurface.queueEvent {
                renderer.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
            }
            val surface = Surface(renderer.surfaceTexture)
            request.provideSurface(surface, bgExecutor) {
                surface.release()
            }
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(bgExecutor) { imageProxy ->
            processFrame(imageProxy)
        }

        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val mediaImage = imageProxy.image
            val mp = faceLandmarker ?: return
            if (mediaImage != null) {
                // 1) YUV -> Bitmap
                val bitmap = BitmapUtils.yuvToBitmap(mediaImage) ?: return
                // 2) Mirror ngang cho front camera để MP nhận đúng hướng
//                val mirrored = BitmapUtils.mirrorBitmap(bitmap)
//                bitmap.recycle()

                // 3) Gửi bitmap cho MediaPipe
                val mpImage = BitmapImageBuilder(bitmap).build()

                // Dùng timestamp từ camera (ns -> ms)
                val tsMs = imageProxy.imageInfo.timestamp / 1_000_000

                val result = runCatching {
                    mp.detectForVideo(
                        mpImage,
                        ImageProcessingOptions.builder().build(),
                        tsMs
                    )
                }.getOrNull()

                bitmap.recycle()

                // 4) Update landmarks cho renderer
                val landmarks = result?.faceLandmarks()?.firstOrNull()
                if (landmarks != null && landmarks.isNotEmpty()) {
                    val leftEye = computeEyeCenter(landmarks, LEFT_EYE_IDX)
                    val rightEye = computeEyeCenter(landmarks, RIGHT_EYE_IDX)
                    val radius = computeEyeRadius(landmarks, LEFT_EYE_IDX, RIGHT_EYE_IDX)
                    renderer.updateEyePositions(
                        leftEye.first, leftEye.second,
                        rightEye.first, rightEye.second,
                        radius
                    )
                    renderer.setMirror(true) // camera trước -> mirror
                } else {
                    // Không có mặt: có thể giảm bulge về 0 từ từ nếu muốn
                    // renderer.setBulgeStrength(0f)
                    Log.d("FishEyesCameraX", "No face detected")
                }
            }
        } catch (e: Exception) {
            Log.e("FishEyesCameraX", "processFrame error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun computeEyeCenter(
        landmarks: List<NormalizedLandmark>,
        indices: List<Int>
    ): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var c = 0
        for (i in indices) {
            if (i < landmarks.size) {
                val lm = landmarks[i]
                x += lm.x()
                y += lm.y()
                c++
            }
        }
        return if (c > 0) x / c to y / c else 0.5f to 0.5f
    }

    private fun computeEyeRadius(
        landmarks: List<NormalizedLandmark>,
        leftIndices: List<Int>,
        rightIndices: List<Int>
    ): Float {
        val leftSize = computeEyeSize(landmarks, leftIndices)
        val rightSize = computeEyeSize(landmarks, rightIndices)
        // scale nhẹ cho hiệu ứng rõ hơn
        return ((leftSize + rightSize) / 2f * 1.5f).coerceIn(0.06f, 0.12f)
    }

    private fun computeEyeSize(
        landmarks: List<NormalizedLandmark>,
        indices: List<Int>
    ): Float {
        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        for (i in indices) {
            if (i < landmarks.size) {
                val lm = landmarks[i]
                minX = min(minX, lm.x())
                maxX = max(maxX, lm.x())
                minY = min(minY, lm.y())
                maxY = max(maxY, lm.y())
            }
        }
        return (maxX - minX + maxY - minY) / 2f
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { faceLandmarker?.close() }
        bgExecutor.shutdown()
    }
}
