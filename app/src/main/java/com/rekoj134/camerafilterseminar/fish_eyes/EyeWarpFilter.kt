package com.rekoj134.camerafilterseminar.fish_eyes

import android.opengl.GLES20
import android.util.Log
import com.otaliastudios.cameraview.filter.BaseFilter
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

class EyeWarpFilter : BaseFilter() {

    companion object {
        private const val TAG = "EyeWarpFilter"
    }

    // Simplified shader with better error handling
    private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 $DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME;
        uniform samplerExternalOES sTexture;

        uniform vec2  uLeftEye;
        uniform vec2  uRightEye;
        uniform float uRadius;
        uniform float uStrength;

        vec2 bulge(vec2 uv, vec2 center, float radius, float strength) {
            vec2 offset = uv - center;
            float distance = length(offset);
            
            if (distance < radius && distance > 0.001) {
                float factor = distance / radius;
                float bulgeAmount = strength * (1.0 - factor * factor);
                float newDistance = distance * (1.0 - bulgeAmount);
                return center + normalize(offset) * newDistance;
            }
            
            return uv;
        }

        void main() {
            vec2 uv = $DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME;
            
            uv = bulge(uv, uLeftEye, uRadius, uStrength);
            uv = bulge(uv, uRightEye, uRadius, uStrength);
            
            uv = clamp(uv, 0.0, 1.0);
            gl_FragColor = texture2D(sTexture, uv);
        }
    """.trimIndent()

    // Uniform handles
    private var uLeftEyeHandle = -1
    private var uRightEyeHandle = -1
    private var uRadiusHandle = -1
    private var uStrengthHandle = -1

    // Eye data with safe defaults
    private var leftEyeX = 0.3f
    private var leftEyeY = 0.5f
    private var rightEyeX = 0.7f
    private var rightEyeY = 0.5f
    private var eyeRadius = 0.08f

    // Target values for smooth animation
    private var targetLeftEyeX = 0.3f
    private var targetLeftEyeY = 0.5f
    private var targetRightEyeX = 0.7f
    private var targetRightEyeY = 0.5f
    private var targetRadius = 0.08f

    // Effect parameters
    var bulgeStrength = 0.5f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
        }

    override fun getFragmentShader(): String = FRAGMENT_SHADER

    override fun onCreate(programHandle: Int) {
        super.onCreate(programHandle)

        try {
            uLeftEyeHandle = GLES20.glGetUniformLocation(programHandle, "uLeftEye")
            uRightEyeHandle = GLES20.glGetUniformLocation(programHandle, "uRightEye")
            uRadiusHandle = GLES20.glGetUniformLocation(programHandle, "uRadius")
            uStrengthHandle = GLES20.glGetUniformLocation(programHandle, "uStrength")

            Log.d(TAG, "Shader uniforms: left=$uLeftEyeHandle, right=$uRightEyeHandle, radius=$uRadiusHandle, strength=$uStrengthHandle")

        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
        }
    }

    fun updateEyePositions(leftX: Float, leftY: Float, rightX: Float, rightY: Float, radius: Float) {
        try {
            // Validate inputs
            if (!isValidFloat(leftX) || !isValidFloat(leftY) ||
                !isValidFloat(rightX) || !isValidFloat(rightY) ||
                !isValidFloat(radius)) {
                return
            }

            targetLeftEyeX = leftX.coerceIn(0f, 1f)
            targetLeftEyeY = leftY.coerceIn(0f, 1f)
            targetRightEyeX = rightX.coerceIn(0f, 1f)
            targetRightEyeY = rightY.coerceIn(0f, 1f)
            targetRadius = radius.coerceIn(0.04f, 0.15f)

        } catch (e: Exception) {
            Log.e(TAG, "Error updating eye positions", e)
        }
    }

    private fun isValidFloat(value: Float): Boolean {
        return !value.isNaN() && !value.isInfinite()
    }

    override fun onPreDraw(timestampUs: Long, transformMatrix: FloatArray) {
        super.onPreDraw(timestampUs, transformMatrix)

        try {
            // Smooth interpolation
            val alpha = 0.3f
            leftEyeX += alpha * (targetLeftEyeX - leftEyeX)
            leftEyeY += alpha * (targetLeftEyeY - leftEyeY)
            rightEyeX += alpha * (targetRightEyeX - rightEyeX)
            rightEyeY += alpha * (targetRightEyeY - rightEyeY)
            eyeRadius += alpha * (targetRadius - eyeRadius)

            // Convert coordinates
            val leftUV = convertCoordinates(leftEyeX, leftEyeY, transformMatrix)
            val rightUV = convertCoordinates(rightEyeX, rightEyeY, transformMatrix)
            val scaledRadius = scaleRadius(eyeRadius, transformMatrix)

            // Set uniforms safely
            setUniformSafely(uLeftEyeHandle) {
                GLES20.glUniform2f(it, leftUV.first, leftUV.second)
            }
            setUniformSafely(uRightEyeHandle) {
                GLES20.glUniform2f(it, rightUV.first, rightUV.second)
            }
            setUniformSafely(uRadiusHandle) {
                GLES20.glUniform1f(it, scaledRadius)
            }
            setUniformSafely(uStrengthHandle) {
                GLES20.glUniform1f(it, bulgeStrength)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in onPreDraw", e)
        }
    }

    private fun setUniformSafely(handle: Int, action: (Int) -> Unit) {
        try {
            if (handle >= 0) {
                action(handle)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting uniform $handle", e)
        }
    }

    private fun convertCoordinates(x: Float, y: Float, matrix: FloatArray): Pair<Float, Float> {
        return try {
            // Convert MediaPipe coords (top-left) to OpenGL UV (bottom-left)
            val u = x
            val v = 1f - y

            // Apply transformation matrix
            val newU = matrix[0] * u + matrix[4] * v + matrix[12]
            val newV = matrix[1] * u + matrix[5] * v + matrix[13]

            newU.coerceIn(0f, 1f) to newV.coerceIn(0f, 1f)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting coordinates", e)
            x to (1f - y)
        }
    }

    private fun scaleRadius(radius: Float, matrix: FloatArray): Float {
        return try {
            val scaleX = sqrt(matrix[0] * matrix[0] + matrix[1] * matrix[1])
            val scaleY = sqrt(matrix[4] * matrix[4] + matrix[5] * matrix[5])
            val scale = max(scaleX, scaleY)
            (radius * scale).coerceIn(0.04f, 0.2f)
        } catch (e: Exception) {
            Log.e(TAG, "Error scaling radius", e)
            radius
        }
    }
}