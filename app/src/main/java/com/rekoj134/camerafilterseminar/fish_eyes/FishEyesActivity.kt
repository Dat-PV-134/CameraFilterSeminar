package com.rekoj134.camerafilterseminar.fish_eyes

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.otaliastudios.cameraview.controls.Facing
import com.otaliastudios.cameraview.controls.Mode
import com.otaliastudios.cameraview.frame.Frame
import com.rekoj134.camerafilterseminar.databinding.ActivityFishEyesBinding
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class FishEyesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFishEyesBinding
    private var faceLandmarker: FaceLandmarker? = null
    private lateinit var eyeWarpFilter: EyeWarpFilter

    private val isProcessing = AtomicBoolean(false)
    private var lastProcessTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private data class FramePacket(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val rotationToUser: Int,
        val mirror: Boolean
    )

    companion object {
        private const val TAG = "FishEyesActivity"
        private const val PROCESS_INTERVAL_MS = 50L

        private val LEFT_EYE_IDX = listOf(33, 133, 157, 158, 159, 160, 161, 163)
        private val RIGHT_EYE_IDX = listOf(362, 385, 386, 387, 388, 390, 263, 373)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) initCamera() else {
                Log.e(TAG, "Camera permission denied")
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityFishEyesBinding.inflate(layoutInflater)
            setContentView(binding.root)
            checkCameraPermission()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            finish()
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initCamera() {
        try {
            eyeWarpFilter = EyeWarpFilter()
            initMediaPipe()
            setupCameraView()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing camera", e)
            setupCameraViewOnly()
        }
    }

    private fun initMediaPipe() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(
                this,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setMinFacePresenceConfidence(0.5f)
                    .build()
            )
            Log.d(TAG, "MediaPipe initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe", e)
            faceLandmarker = null
        }
    }

    private fun setupCameraView() {
        binding.cameraView.apply {
            facing = Facing.FRONT
            mode = Mode.VIDEO
            filter = eyeWarpFilter
            setLifecycleOwner(this@FishEyesActivity)

            addFrameProcessor { frame ->
                val now = System.currentTimeMillis()
                if (now - lastProcessTime < PROCESS_INTERVAL_MS) return@addFrameProcessor
                if (!isProcessing.compareAndSet(false, true)) return@addFrameProcessor
                lastProcessTime = now

                val rotation = frame.rotationToUser
                val mirror = (facing == Facing.FRONT)

                try {
                    when (frame.dataClass) {
                        ByteArray::class.java -> {
                            val src = frame.getData<ByteArray>() ?: run {
                                isProcessing.set(false); return@addFrameProcessor
                            }
                            val copy = src.copyOf()
                            val packet = FramePacket(
                                nv21 = copy,
                                width = frame.size.width,
                                height = frame.size.height,
                                rotationToUser = rotation,
                                mirror = mirror
                            )
                            bgExecutor.execute { processPacketNV21(packet) }
                        }

                        Image::class.java -> {
                            val image = frame.getData<Image>() ?: run {
                                isProcessing.set(false); return@addFrameProcessor
                            }
                            val nv21 = yuv420888ToNv21Copy(image)
                            val packet = FramePacket(
                                nv21 = nv21,
                                width = image.width,
                                height = image.height,
                                rotationToUser = rotation,
                                mirror = mirror
                            )
                            bgExecutor.execute { processPacketNV21(packet) }
                        }

                        else -> {
                            Log.w(TAG, "Unsupported frame type: ${frame.dataClass}")
                            isProcessing.set(false)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Frame copy error", t)
                    isProcessing.set(false)
                }
            }
        }
    }

    private fun setupCameraViewOnly() {
        binding.cameraView.apply {
            facing = Facing.FRONT
            mode = Mode.VIDEO
            filter = eyeWarpFilter
            setLifecycleOwner(this@FishEyesActivity)
        }
    }

    /** Xử lý packet trên background thread */
    private fun processPacketNV21(packet: FramePacket) {
        var srcBmp: Bitmap? = null
        var finalBmp: Bitmap? = null
        try {
            srcBmp = nv21ToBitmap(packet.nv21, packet.width, packet.height) ?: return

            finalBmp = rotateAndMirror(srcBmp, packet.rotationToUser, packet.mirror)
            if (finalBmp !== srcBmp) {
                srcBmp.recycle()
                srcBmp = null
            }

            // BẮT BUỘC ARGB_8888 cho MediaPipe
            if (finalBmp.config != Bitmap.Config.ARGB_8888) {
                val converted = finalBmp.copy(Bitmap.Config.ARGB_8888, /*mutable=*/false)
                if (converted == null) {
                    Log.w(TAG, "Failed to convert to ARGB_8888; skip frame")
                    finalBmp.recycle()
                    finalBmp = null
                    return
                } else {
                    finalBmp.recycle()
                    finalBmp = converted
                }
            }

            val mpImage = BitmapImageBuilder(finalBmp!!).build()
            val options = ImageProcessingOptions.builder().build()
            val ts = System.currentTimeMillis()
            val result = faceLandmarker?.detectForVideo(mpImage, options, ts)

            mainHandler.post { handleResult(result) }
        } catch (e: Exception) {
            Log.e(TAG, "processPacketNV21 error", e)
        } finally {
            // Giải phóng bitmap sau khi detect xong
            finalBmp?.recycle()
            isProcessing.set(false)
        }
    }

    /** YUV_420_888 -> NV21 (copy an toàn, không giữ tham chiếu image/planes) */
    private fun yuv420888ToNv21Copy(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val width = image.width
        val height = image.height

        val ySize = width * height
        val uvSize = width * height / 2
        val out = ByteArray(ySize + uvSize)

        // Copy Y theo từng dòng
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yRow = ByteArray(yRowStride)
        var outPos = 0
        for (row in 0 until height) {
            val toRead = minOf(yRowStride, yBuffer.remaining())
            yBuffer.get(yRow, 0, toRead)
            System.arraycopy(yRow, 0, out, outPos, width)
            outPos += width
        }

        // Copy UV (NV21 = interleaved VU)
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        val uvHeight = height / 2

        for (row in 0 until uvHeight) {
            val uRowStart = uBuffer.position()
            val vRowStart = vBuffer.position()

            val uToRead = minOf(uvRowStride, uBuffer.remaining())
            val vToRead = minOf(uvRowStride, vBuffer.remaining())
            // “Đọc” để tiến con trỏ về cuối dòng
            uBuffer.position(uRowStart + uToRead)
            vBuffer.position(vRowStart + vToRead)

            for (col in 0 until width step 2) {
                val uIndex = uRowStart + (col / 2) * uvPixelStride
                val vIndex = vRowStart + (col / 2) * uvPixelStride
                val v = vBuffer.get(vIndex)
                val u = uBuffer.get(uIndex)
                out[outPos++] = v
                out[outPos++] = u
            }
        }

        return out
    }

    /** NV21 -> Bitmap (ARGB_8888) */
    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            val rect = Rect(0, 0, width, height)
            if (!yuv.compressToJpeg(rect, 90, out)) {
                out.close()
                null
            } else {
                val bytes = out.toByteArray()
                out.close()
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888   // bắt buộc
                    inDither = false
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "nv21ToBitmap error", e)
            null
        }
    }

    private fun rotateAndMirror(bitmap: Bitmap, rotation: Int, mirror: Boolean): Bitmap {
        if (rotation == 0 && !mirror) return bitmap
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        if (mirror) m.postScale(-1f, 1f)
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        } catch (e: Exception) {
            Log.e(TAG, "rotate error, fallback original", e)
            bitmap
        }
    }

    private fun handleResult(result: FaceLandmarkerResult?) {
        try {
            val landmarks = result?.faceLandmarks()?.firstOrNull()
            if (landmarks == null || landmarks.isEmpty()) return

            val leftEye = computeEyeCenter(landmarks, LEFT_EYE_IDX)
            val rightEye = computeEyeCenter(landmarks, RIGHT_EYE_IDX)
            val eyeRadius = computeEyeRadius(landmarks, LEFT_EYE_IDX, RIGHT_EYE_IDX)

            eyeWarpFilter.updateEyePositions(
                leftEye.first, leftEye.second,
                rightEye.first, rightEye.second,
                eyeRadius
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error handling result", e)
        }
    }

    private fun computeEyeCenter(
        landmarks: List<NormalizedLandmark>,
        indices: List<Int>
    ): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var c = 0
        for (idx in indices) {
            if (idx < landmarks.size) {
                val lm = landmarks[idx]
                x += lm.x()
                y += lm.y()
                c++
            }
        }
        return if (c > 0) (x / c) to (y / c) else 0.5f to 0.5f
    }

    private fun computeEyeRadius(
        landmarks: List<NormalizedLandmark>,
        leftIndices: List<Int>,
        rightIndices: List<Int>
    ): Float {
        val leftSize = computeEyeSize(landmarks, leftIndices)
        val rightSize = computeEyeSize(landmarks, rightIndices)
        val avg = (leftSize + rightSize) / 2f
        return (avg * 1.5f).coerceIn(0.06f, 0.12f)
    }

    private fun computeEyeSize(
        landmarks: List<NormalizedLandmark>,
        indices: List<Int>
    ): Float {
        var minX = 1f
        var maxX = 0f
        var minY = 1f
        var maxY = 0f
        for (idx in indices) {
            if (idx < landmarks.size) {
                val lm = landmarks[idx]
                minX = min(minX, lm.x())
                maxX = max(maxX, lm.x())
                minY = min(minY, lm.y())
                maxY = max(maxY, lm.y())
            }
        }
        return ((maxX - minX) + (maxY - minY)) / 2f
    }

    override fun onPause() {
        super.onPause()
        isProcessing.set(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { binding.cameraView.destroy() } catch (_: Exception) {}
        try { faceLandmarker?.close(); faceLandmarker = null } catch (_: Exception) {}
    }
}
