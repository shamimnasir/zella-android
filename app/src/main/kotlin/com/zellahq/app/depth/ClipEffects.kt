@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.depth

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.zellahq.core.looks.Lut
import com.zellahq.core.project.ClipTransform
import com.zellahq.core.project.MaskRegion
import com.zellahq.core.project.SpecialEffects
import com.zellahq.core.project.TransformKeyframes
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""

@OptIn(UnstableApi::class)
private abstract class OnePass(useHdr: Boolean, fragment: String) : BaseGlShaderProgram(useHdr, 1) {
    protected val gl: GlProgram =
        try {
            GlProgram(VERTEX, fragment).also {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    protected var width = 1
    protected var height = 1

    override fun configure(
        inputWidth: Int,
        inputHeight: Int,
    ): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    protected abstract fun uniforms(presentationTimeUs: Long)

    override fun drawFrame(
        inputTexId: Int,
        presentationTimeUs: Long,
    ) {
        try {
            gl.use()
            GLES20.glDisable(GLES20.GL_BLEND)
            gl.setSamplerTexIdUniform("uTex", inputTexId, 0)
            uniforms(presentationTimeUs)
            gl.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            gl.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/**
 * A clip's position, size, rotation and opacity, steady plus keyframed, on the iPhone's model: the
 * picture moves over black, and fades to black. [localSeconds] turns a timestamp into clip-local time.
 */
@OptIn(UnstableApi::class)
class ClipTransformEffect(
    private val base: ClipTransform,
    private val opacity: Double,
    private val keys: TransformKeyframes,
    private val localSeconds: (Long) -> Double,
) : GlEffect {
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram =
        object : OnePass(useHdr, FRAGMENT) {
            override fun uniforms(presentationTimeUs: Long) {
                val t = localSeconds(presentationTimeUs)
                val x = base.x + keys.posX.value(t, 0.0)
                val y = base.y + keys.posY.value(t, 0.0)
                val s = max(0.05, base.scale * keys.scale.value(t, 1.0))
                val r = Math.toRadians(base.rotation + keys.rotation.value(t, 0.0))
                val a = opacity * keys.opacity.value(t, 1.0)
                gl.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                // Stored y grows downward; drawing runs upward.
                gl.setFloatsUniform("uMove", floatArrayOf((x * width).toFloat(), (-y * height).toFloat()))
                gl.setFloatUniform("uScale", s.toFloat())
                gl.setFloatsUniform("uRot", floatArrayOf(cos(r).toFloat(), sin(r).toFloat()))
                gl.setFloatUniform("uAlpha", a.toFloat().coerceIn(0f, 1f))
            }
        }

    private companion object {
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform vec2 uMove;
uniform float uScale;
uniform vec2 uRot;
uniform float uAlpha;
varying vec2 vUv;
void main() {
  vec2 c = uSize * 0.5;
  vec2 p = vUv * uSize - c - uMove;
  vec2 q = vec2(uRot.x * p.x + uRot.y * p.y, -uRot.y * p.x + uRot.x * p.y) / uScale + c;
  vec2 uv = q / uSize;
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
  } else {
    gl_FragColor = vec4(texture2D(uTex, uv).rgb * uAlpha, 1.0);
  }
}
"""
    }
}

/** Black and white, vignette, grain, soft glow, pixels, colour fringe, blur and letterbox bars, in one pass. */
@OptIn(UnstableApi::class)
class SpecialEffect(private val s: SpecialEffects, private val seconds: (Long) -> Double) : GlEffect {
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram =
        object : OnePass(useHdr, FRAGMENT) {
            override fun uniforms(presentationTimeUs: Long) {
                gl.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                gl.setIntUniform("uMono", if (s.blackAndWhite) 1 else 0)
                gl.setFloatUniform("uVignette", s.vignette.toFloat())
                gl.setFloatUniform("uGrain", s.grain.toFloat())
                gl.setFloatUniform("uGlow", s.glow.toFloat())
                gl.setFloatUniform("uPixel", if (s.pixelate > 0) max(2.0, s.pixelate * width * 0.05).toFloat() else 0f)
                gl.setFloatUniform("uFringe", (s.chromatic * width * 0.008).toFloat())
                gl.setFloatUniform("uBlur", (s.blur * width / 1080.0).toFloat())
                gl.setIntUniform("uBars", if (s.letterbox) 1 else 0)
                gl.setFloatUniform("uTick", ((seconds(presentationTimeUs) * 24).toLong() % 977).toFloat())
            }
        }

    private companion object {
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform int uMono;
uniform float uVignette;
uniform float uGrain;
uniform float uGlow;
uniform float uPixel;
uniform float uFringe;
uniform float uBlur;
uniform int uBars;
uniform float uTick;
varying vec2 vUv;

vec3 read(vec2 uv) {
  if (uBlur <= 0.01) return texture2D(uTex, uv).rgb;
  vec3 sum = vec3(0.0);
  float total = 0.0;
  for (int x = -4; x <= 4; x++) {
    for (int y = -4; y <= 4; y++) {
      vec2 o = vec2(float(x), float(y));
      float w = exp(-dot(o, o) / 8.0);
      sum += texture2D(uTex, uv + o * uBlur * 0.5 / uSize).rgb * w;
      total += w;
    }
  }
  return sum / total;
}

float random(vec2 p) {
  return fract(sin(dot(p, vec2(12.9898, 78.233)) + uTick) * 43758.5453);
}

void main() {
  vec2 uv = vUv;
  if (uPixel > 0.0) uv = (floor(uv * uSize / uPixel) + 0.5) * uPixel / uSize;
  vec3 c = read(uv);
  if (uFringe > 0.0) {
    vec2 d = (uv - 0.5) * 2.0 * uFringe / uSize;
    c.r = read(uv + d).r;
    c.b = read(uv - d).b;
  }
  if (uGlow > 0.0) {
    vec3 soft = vec3(0.0);
    for (int i = 0; i < 8; i++) {
      float a = float(i) * 0.785398;
      soft += texture2D(uTex, uv + vec2(cos(a), sin(a)) * uSize.x * 0.015 / uSize).rgb;
    }
    soft /= 8.0;
    c = c + max(soft - 0.5, 0.0) * uGlow * 1.5;
  }
  if (uMono == 1) c = vec3(dot(c, vec3(0.2126, 0.7152, 0.0722)));
  if (uVignette > 0.0) {
    float r = distance(vUv, vec2(0.5)) / 0.7071;
    c *= 1.0 - uVignette * smoothstep(0.35, 1.0, r);
  }
  if (uGrain > 0.0) c += (random(floor(vUv * uSize)) - 0.5) * uGrain * 0.25;
  if (uBars == 1) {
    float bar = max(0.0, (1.0 - (uSize.x / 2.39) / uSize.y) * 0.5);
    if (uSize.y > uSize.x) bar = 0.1;
    if (vUv.y < bar || vUv.y > 1.0 - bar) c = vec3(0.0);
  }
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}

/**
 * The grade inside a soft shape and the original outside it, the iPhone's mask. Media3's LUT effect
 * has only one input, so the LUT is read here, from a flat strip, with the original in the same pass.
 */
@OptIn(UnstableApi::class)
class MaskedGradeEffect(private val lut: Lut, private val mask: MaskRegion) : GlEffect {
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram =
        object : OnePass(useHdr, FRAGMENT) {
            private val tex = IntArray(1)

            init {
                val n = lut.size
                val bytes = ByteBuffer.allocateDirect(n * n * n * 4)
                val d = lut.data
                for (i in 0 until n * n * n) {
                    bytes.put((d[i * 3] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    bytes.put((d[i * 3 + 1] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    bytes.put((d[i * 3 + 2] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
                    bytes.put(255.toByte())
                }
                bytes.rewind()
                GLES20.glGenTextures(1, tex, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, n * n, n, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            }

            override fun uniforms(presentationTimeUs: Long) {
                gl.setSamplerTexIdUniform("uLut", tex[0], 1)
                gl.setFloatUniform("uN", lut.size.toFloat())
                gl.setFloatsUniform("uCentre", floatArrayOf(mask.x.toFloat(), (1 - mask.y).toFloat()))
                gl.setFloatsUniform("uHalf", floatArrayOf((mask.w / 2).toFloat(), (mask.h / 2).toFloat()))
                gl.setFloatUniform("uFeather", mask.feather.toFloat().coerceIn(0f, 0.95f))
                gl.setIntUniform("uEllipse", if (mask.shape == "ellipse") 1 else 0)
                gl.setIntUniform("uInvert", if (mask.invert) 1 else 0)
            }

            override fun release() {
                GLES20.glDeleteTextures(1, tex, 0)
                super.release()
            }
        }

    private companion object {
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uLut;
uniform float uN;
uniform vec2 uCentre;
uniform vec2 uHalf;
uniform float uFeather;
uniform int uEllipse;
uniform int uInvert;
varying vec2 vUv;

vec3 slice(float blue, vec2 rg) {
  vec2 p = vec2((blue + (rg.x * (uN - 1.0) + 0.5) / uN) / uN, (rg.y * (uN - 1.0) + 0.5) / uN);
  return texture2D(uLut, p).rgb;
}

vec3 grade(vec3 c) {
  float b = clamp(c.b, 0.0, 1.0) * (uN - 1.0);
  float lo = floor(b);
  float hi = min(lo + 1.0, uN - 1.0);
  return mix(slice(lo, c.rg), slice(hi, c.rg), b - lo);
}

void main() {
  vec3 c = texture2D(uTex, vUv).rgb;
  vec2 d = (vUv - uCentre) / max(uHalf, vec2(0.0005));
  float m;
  if (uEllipse == 1) {
    m = 1.0 - smoothstep(1.0 - uFeather, 1.0, length(d));
  } else {
    vec2 a = abs(d);
    m = 1.0 - smoothstep(1.0 - uFeather, 1.0 + uFeather * 0.5, max(a.x, a.y));
  }
  if (uInvert == 1) m = 1.0 - m;
  gl_FragColor = vec4(mix(c, grade(c), m), 1.0);
}
"""
    }
}
