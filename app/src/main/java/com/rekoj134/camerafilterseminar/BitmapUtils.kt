package com.rekoj134.camerafilterseminar

import android.graphics.*
import android.media.Image
import java.io.ByteArrayOutputStream

object BitmapUtils {

    fun yuvToBitmap(image: Image): Bitmap? {
        val nv21 = yuv420888ToNv21(image)
        return try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            // JPEG 100 để tránh banding; có thể hạ xuống 90 nếu cần tốc độ
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
            val bytes = out.toByteArray()
            out.close()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    // Chuyển YUV_420_888 (3 plane) -> NV21 (VU interleaved), xử lý đúng rowStride/pixelStride
    private fun yuv420888ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = width * height
        val uvSize = width * height / 2
        val out = ByteArray(ySize + uvSize)

        // ----- Copy Y -----
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var outPos = 0
        var ySrcPos = 0
        for (row in 0 until height) {
            yBuffer.position(ySrcPos)
            yBuffer.get(out, outPos, width)
            outPos += width
            ySrcPos += yRowStride
        }

        // ----- Copy VU (NV21 = V rồi U) -----
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        var uvOutPos = ySize
        for (row in 0 until height / 2) {
            val uRowStart = row * uRowStride
            val vRowStart = row * vRowStride
            for (col in 0 until width / 2) {
                val u = uBuffer.get(uRowStart + col * uPixelStride)
                val v = vBuffer.get(vRowStart + col * vPixelStride)
                out[uvOutPos++] = v
                out[uvOutPos++] = u
            }
        }

        return out
    }

    fun rotateBitmap(src: Bitmap, degree: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degree.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    fun mirrorBitmap(src: Bitmap): Bitmap {
        val matrix = Matrix().apply { preScale(-1f, 1f) } // mirror ngang
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
