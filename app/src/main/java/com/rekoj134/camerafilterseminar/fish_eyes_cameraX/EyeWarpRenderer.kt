package com.rekoj134.camerafilterseminar.fish_eyes_cameraX

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

class EyeWarpRenderer : GLSurfaceView.Renderer {

    var textureId = 0
    var surfaceTexture: SurfaceTexture? = null
    var surfaceReadyCallback: ((SurfaceTexture) -> Unit)? = null

    // Eye params (world-space uv 0..1)
    private var leftEyeX = 0.3f
    private var leftEyeY = 0.5f
    private var rightEyeX = 0.7f
    private var rightEyeY = 0.5f
    private var eyeRadius = 0.08f
    private var bulgeStrength = 0.5f
    private var mirrorX = 1 // 1 = mirror X (selfie), 0 = no mirror

    // Smooth target
    private var targetLeftEyeX = leftEyeX
    private var targetLeftEyeY = leftEyeY
    private var targetRightEyeX = rightEyeX
    private var targetRightEyeY = rightEyeY
    private var targetRadius = eyeRadius

    // GL program & uniforms
    private var program = 0
    private var uLeftEye = 0
    private var uRightEye = 0
    private var uRadius = 0
    private var uStrength = 0
    private var uTexMatrix = 0
    private var uMirror = 0

    // Transform matrix from SurfaceTexture
    private val texMatrix = FloatArray(16)

    private lateinit var vertexBuffer: FloatBuffer

    private val vertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        uniform mat4 uTexMatrix;

        void main() {
            gl_Position = aPosition;
            vec4 tex = uTexMatrix * vec4(aTexCoord, 0.0, 1.0);
            vTexCoord = tex.xy;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;

        varying vec2 vTexCoord;
        uniform samplerExternalOES sTexture;
        uniform vec2 uLeftEye;
        uniform vec2 uRightEye;
        uniform float uRadius;
        uniform float uStrength;
        uniform int uMirror;

        vec2 bulge(vec2 uv, vec2 center, float radius, float strength){
            vec2 d = uv - center;
            float dist = length(d);
            if (dist < radius && dist > 1e-4) {
                float f = dist / radius;
                float bulgeAmount = strength * (1.0 - f * f);
                float newDist = dist * (1.0 - bulgeAmount);
                return center + normalize(d) * newDist;
            }
            return uv;
        }

        void main() {
            vec2 uv = vTexCoord;
            if (uMirror == 1) {
                uv.x = 1.0 - uv.x;
            }

            uv = bulge(uv, uLeftEye,  uRadius, uStrength);
            uv = bulge(uv, uRightEye, uRadius, uStrength);
            uv = clamp(uv, 0.0, 1.0);

            gl_FragColor = texture2D(sTexture, uv);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        textureId = generateOESTexture()
        surfaceTexture = SurfaceTexture(textureId)
        // KHÔNG setDefaultBufferSize ở đây. Sẽ set theo request.resolution ở Activity.
        surfaceReadyCallback?.invoke(surfaceTexture!!)

        program = compileShader(vertexShaderCode, fragmentShaderCode)
        uLeftEye = GLES20.glGetUniformLocation(program, "uLeftEye")
        uRightEye = GLES20.glGetUniformLocation(program, "uRightEye")
        uRadius = GLES20.glGetUniformLocation(program, "uRadius")
        uStrength = GLES20.glGetUniformLocation(program, "uStrength")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uMirror = GLES20.glGetUniformLocation(program, "uMirror")

        // Fullscreen quad (x,y | u,v)
        val coords = floatArrayOf(
            -1f,  1f, 0f, 0f,
            -1f, -1f, 0f, 1f,
            1f,  1f, 1f, 0f,
            1f, -1f, 1f, 1f
        )
        vertexBuffer = ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(coords); position(0) }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        // Smooth
        val alpha = 0.3f
        leftEyeX += alpha * (targetLeftEyeX - leftEyeX)
        leftEyeY += alpha * (targetLeftEyeY - leftEyeY)
        rightEyeX += alpha * (targetRightEyeX - rightEyeX)
        rightEyeY += alpha * (targetRightEyeY - rightEyeY)
        eyeRadius += alpha * (targetRadius - eyeRadius)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1i(uMirror, mirrorX)
        GLES20.glUniform2f(uLeftEye, leftEyeX, leftEyeY)
        GLES20.glUniform2f(uRightEye, rightEyeX, rightEyeY)
        GLES20.glUniform1f(uRadius, eyeRadius)
        GLES20.glUniform1f(uStrength, bulgeStrength)

        drawOESTexture(textureId)
    }

    fun updateEyePositions(lx: Float, ly: Float, rx: Float, ry: Float, radius: Float) {
        targetLeftEyeX = lx
        targetLeftEyeY = ly
        targetRightEyeX = rx
        targetRightEyeY = ry
        targetRadius = radius
    }

    fun setBulgeStrength(s: Float) {
        bulgeStrength = s.coerceIn(0f, 1.2f)
    }

    fun setMirror(enabled: Boolean) {
        mirrorX = if (enabled) 1 else 0
    }

    private fun generateOESTexture(): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex[0]
    }

    private fun drawOESTexture(texture: Int) {
        // Attributes
        val aPos = GLES20.glGetAttribLocation(program, "aPosition")
        val aTex = GLES20.glGetAttribLocation(program, "aTexCoord")

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        // Texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)

        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Cleanup
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun compileShader(vertexSrc: String, fragmentSrc: String): Int {
        fun build(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compile error: $log")
            }
            return shader
        }
        val vs = build(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = build(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link error: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }
}
