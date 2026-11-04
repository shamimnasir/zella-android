@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.cutout

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.zellahq.core.project.BackgroundChoice
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max

/**
 * Puts the person from a matte file over a new background. It runs first on a clip, on the frame as
 * the file holds it, so the matte lines up whatever crop or zoom comes after, and the clip's look
 * then covers person and background alike, as on the iPhone.
 *
 * [sourceSeconds] turns the frame's timestamp into a position in the file.
 */
@OptIn(UnstableApi::class)
class BackgroundEffect(
    private val matte: File,
    private val choice: BackgroundChoice,
    private val sourceSeconds: (Long) -> Double,
    private val timelineSeconds: (Long) -> Double,
) : GlEffect {
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val gl: GlProgram
        private val down: GlProgram
        private val reader = MatteFile(matte)
        private val textures = IntArray(4)
        private val loaded = intArrayOf(-1, -1)
        private val bytes = ByteArray(reader.width * reader.height)
        private val buffer = ByteBuffer.allocateDirect(bytes.size)
        private var width = 1
        private var height = 1
        private var fbo = -1
        private var smallW = 1
        private var smallH = 1
        private var bgBitmap: Bitmap? = null
        private var lastPaint = -1.0
        private val animated = choice.style == "preset" && BackgroundPainter.isAnimated(choice.presetId.orEmpty())

        init {
            try {
                gl = GlProgram(VERTEX, FRAGMENT)
                gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                down = GlProgram(VERTEX, DOWNSCALE)
                down.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                GLES20.glGenTextures(4, textures, 0)
                for (t in 0..1) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[t])
                    params()
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, reader.width, reader.height, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null)
                }
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        private fun params() {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        override fun configure(
            inputWidth: Int,
            inputHeight: Int,
        ): Size {
            width = inputWidth
            height = inputHeight
            smallW = max(2, inputWidth / 8)
            smallH = max(2, inputHeight / 8)
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[2])
                params()
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, smallW, smallH, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                fbo = GlUtil.createFboForTexture(textures[2])
                prepareBackground()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
            return Size(inputWidth, inputHeight)
        }

        private fun prepareBackground() {
            when (choice.style) {
                "image" -> {
                    val src = choice.imagePath?.let { BitmapFactory.decodeFile(it) } ?: return
                    upload(fill(src))
                }
                "preset" -> {
                    if (animated) {
                        // Soft moving gradients: a third of the size draws fast and looks the same.
                        bgBitmap = Bitmap.createBitmap(max(2, width / 3), max(2, height / 3), Bitmap.Config.ARGB_8888)
                    } else {
                        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        BackgroundPainter.paint(choice.presetId.orEmpty(), Canvas(b), width, height, 0.0)
                        upload(b)
                        b.recycle()
                    }
                }
            }
        }

        /** The picture cropped to fill the frame, not stretched. */
        private fun fill(src: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val s = max(width.toFloat() / src.width, height.toFloat() / src.height)
            val m =
                Matrix().apply {
                    postScale(s, s)
                    postTranslate((width - src.width * s) / 2, (height - src.height * s) / 2)
                }
            Canvas(out).drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            return out
        }

        private fun upload(b: Bitmap) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[3])
            params()
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        }

        private fun matteFrame(
            slot: Int,
            index: Int,
        ) {
            if (loaded[slot] == index) return
            reader.read(index, bytes)
            buffer.clear()
            buffer.put(bytes)
            buffer.rewind()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[slot])
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, reader.width, reader.height, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, buffer)
            loaded[slot] = index
        }

        override fun drawFrame(
            inputTexId: Int,
            presentationTimeUs: Long,
        ) {
            val at = (sourceSeconds(presentationTimeUs) * reader.fps).coerceIn(0.0, reader.count - 1.0)
            val a = floor(at).toInt()
            val b = (a + 1).coerceAtMost(reader.count - 1)
            matteFrame(0, a)
            matteFrame(1, b)
            val mix = (at - a).toFloat()
            try {
                if (choice.style == "blur") {
                    // The input drawn into a small picture: smooth when read back, whatever the size.
                    val viewport = IntArray(4)
                    val current = IntArray(1)
                    GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)
                    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, current, 0)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                    GLES20.glViewport(0, 0, smallW, smallH)
                    down.use()
                    down.setSamplerTexIdUniform("uTex", inputTexId, 0)
                    down.setFloatsUniform("uStep", floatArrayOf(4f / width, 4f / height))
                    down.bindAttributesAndUniforms()
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, current[0])
                    GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
                }
                val paintBitmap = bgBitmap
                if (paintBitmap != null) {
                    val t = timelineSeconds(presentationTimeUs)
                    if (kotlin.math.abs(t - lastPaint) > 0.05) {
                        BackgroundPainter.paint(choice.presetId.orEmpty(), Canvas(paintBitmap), paintBitmap.width, paintBitmap.height, t)
                        upload(paintBitmap)
                        lastPaint = t
                    }
                }
                gl.use()
                GLES20.glDisable(GLES20.GL_BLEND)
                gl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                gl.setSamplerTexIdUniform("uA", textures[0], 1)
                gl.setSamplerTexIdUniform("uB", textures[1], 2)
                gl.setSamplerTexIdUniform("uBg", if (choice.style == "blur") textures[2] else textures[3], 3)
                gl.setFloatUniform("uMix", mix)
                gl.setIntUniform(
                    "uMode",
                    when (choice.style) {
                        "blur" -> 1
                        "image", "preset" -> 2
                        else -> 0
                    },
                )
                gl.setFloatsUniform("uColor", BackgroundPainter.colorOf(choice.colorHex))
                val radius = (choice.blurRadius ?: 18.0).toFloat() / 18f
                gl.setFloatsUniform("uBlur", floatArrayOf(radius * 1.5f / smallW, radius * 1.5f / smallH))
                gl.setFloatsUniform("uTexel", floatArrayOf(1.5f / reader.width, 1.5f / reader.height))
                gl.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            reader.close()
            bgBitmap?.recycle()
            try {
                gl.delete()
                down.delete()
                GLES20.glDeleteTextures(4, textures, 0)
                if (fbo != -1) GlUtil.deleteFbo(fbo)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""
        private const val DOWNSCALE = """
precision mediump float;
uniform sampler2D uTex;
uniform vec2 uStep;
varying vec2 vUv;
void main() {
  gl_FragColor = 0.25 * (
    texture2D(uTex, vUv + uStep * vec2(-0.5, -0.5)) + texture2D(uTex, vUv + uStep * vec2(0.5, -0.5)) +
    texture2D(uTex, vUv + uStep * vec2(-0.5, 0.5)) + texture2D(uTex, vUv + uStep * vec2(0.5, 0.5)));
}
"""

        // The matte is low resolution, so it is read through the picture: a neighbour counts only if its
        // colour matches this pixel's. That lands the soft mask on the real edge.
        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uBg;
uniform float uMix;
uniform int uMode;
uniform vec3 uColor;
uniform vec2 uBlur;
uniform vec2 uTexel;
varying vec2 vUv;

float maskAt(vec2 uv) {
  vec2 m = vec2(uv.x, 1.0 - uv.y);
  return mix(texture2D(uA, m).r, texture2D(uB, m).r, uMix);
}

vec3 background(vec3 fallback) {
  if (uMode == 2) return texture2D(uBg, vec2(vUv.x, 1.0 - vUv.y)).rgb;
  if (uMode == 1) {
    vec3 sum = vec3(0.0);
    float total = 0.0;
    for (int x = -3; x <= 3; x++) {
      for (int y = -3; y <= 3; y++) {
        vec2 o = vec2(float(x), float(y));
        float w = exp(-dot(o, o) / 8.0);
        sum += texture2D(uBg, vUv + o * uBlur).rgb * w;
        total += w;
      }
    }
    return sum / total;
  }
  return uColor;
}

void main() {
  vec3 c = texture2D(uTex, vUv).rgb;
  float sum = 0.0;
  float total = 0.0;
  for (int x = -1; x <= 1; x++) {
    for (int y = -1; y <= 1; y++) {
      vec2 o = vec2(float(x), float(y)) * uTexel;
      vec3 d = texture2D(uTex, vUv + o).rgb - c;
      float w = exp(-dot(d, d) * 30.0) * (x == 0 && y == 0 ? 2.0 : 1.0);
      sum += maskAt(vUv + o) * w;
      total += w;
    }
  }
  float alpha = smoothstep(0.35, 0.65, sum / total);
  gl_FragColor = vec4(mix(background(c), c, alpha), 1.0);
}
"""
    }
}
