package com.rekoj134.camerafilterseminar.filter_camera

import android.R.attr.viewportHeight
import android.R.attr.viewportWidth
import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import com.otaliastudios.cameraview.filter.BaseFilter

class RefractionFilter(
    private val context: Context,
    private val bumpResId: Int
) : BaseFilter() {

    private var bumpTexture = 0

    private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;

        varying vec2 $DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME;

        uniform samplerExternalOES sTexture; // camera feed
        uniform sampler2D uBump;             // bump map
        uniform float uTime;                 
        uniform vec2 uResolution;            

        void main() {
            vec2 uv = $DEFAULT_FRAGMENT_TEXTURE_COORDINATE_NAME;

            // sample bump map
            vec4 bump = texture2D(uBump, uv + uTime * 0.05);

            // scale distortion
            vec2 vScale = vec2(0.02, 0.02); 
            vec2 newUV = uv + bump.xy * vScale;

            newUV = clamp(newUV, 0.0, 1.0);

            // sample camera texture
            vec4 col = texture2D(sTexture, newUV);

            gl_FragColor = col;
        }
    """

    private var uTimeHandle = 0
    private var uResolutionHandle = 0
    private var uBumpHandle = 0
    private var time = 0f

    override fun getFragmentShader(): String = FRAGMENT_SHADER

    override fun onCreate(programHandle: Int) {
        super.onCreate(programHandle)
        uTimeHandle = GLES20.glGetUniformLocation(programHandle, "uTime")
        uResolutionHandle = GLES20.glGetUniformLocation(programHandle, "uResolution")
        uBumpHandle = GLES20.glGetUniformLocation(programHandle, "uBump")

        bumpTexture = loadTexture(bumpResId)
    }

    override fun onPreDraw(timestampUs: Long, transformMatrix: FloatArray) {
        super.onPreDraw(timestampUs, transformMatrix)
        time += 0.016f * 5 // simulate ~60fps

        // gán uniforms
        GLES20.glUniform1f(uTimeHandle, time)

        val width = viewportWidth.toFloat()
        val height = viewportHeight.toFloat()
        GLES20.glUniform2f(uResolutionHandle, width, height)

        // bind bump map vào TEXTURE1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bumpTexture)
        GLES20.glUniform1i(uBumpHandle, 1)

        // CameraView tự bind camera feed vào TEXTURE0
    }

    private fun loadTexture(resId: Int): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
        if (textureHandle[0] == 0) throw RuntimeException("Error generating texture")

        val bitmap = BitmapFactory.decodeResource(context.resources, resId)
            ?: throw RuntimeException("Failed to decode bitmap $resId")

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        bitmap.recycle()

        return textureHandle[0]
    }
}
