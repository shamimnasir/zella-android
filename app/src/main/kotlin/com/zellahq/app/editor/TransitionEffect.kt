package com.zellahq.app.editor

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
import com.zellahq.core.project.TransitionTypes

/**
 * The 25 transitions, with the iPhone's maths (TransitionRender.swift). Media3 composites two tracks
 * by alpha and has no two-input shader, so each transition is written as three things a single clip
 * can work out by itself: what the outgoing clip looks like at this point, what the incoming clip
 * looks like, and how far this pixel has moved over to the incoming clip. The clip on the top track
 * puts that amount in its alpha; the one underneath stays opaque.
 *
 * Each transition is rendered on its own (see TransitionBaker), with the outgoing clip always on top,
 * so Luma Fade reads the outgoing clip's brightness exactly as the iPhone does.
 */
@OptIn(UnstableApi::class)
class TransitionEffect(
    /** The transition this clip comes in through, or null. */
    private val incoming: Window?,
    /** The transition this clip goes out through, or null. */
    private val outgoing: Window?,
    /** True on track 0, which draws over track 1. */
    private val isTop: Boolean,
) : GlEffect {
    /** Timeline seconds. */
    data class Window(val type: Int, val start: Double, val duration: Double)

    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = Program(useHdr)

    private inner class Program(useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val gl: GlProgram
        private var width = 1
        private var height = 1

        init {
            try {
                gl = GlProgram(VERTEX, FRAGMENT)
                gl.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }

        override fun configure(
            inputWidth: Int,
            inputHeight: Int,
        ): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(
            inputTexId: Int,
            presentationTimeUs: Long,
        ) {
            val t = presentationTimeUs / 1e6
            var type = -1
            var side = 0
            var progress = 0f
            val i = incoming
            val o = outgoing
            if (i != null && t < i.start + i.duration) {
                type = i.type
                side = 1
                progress = ((t - i.start) / i.duration).toFloat().coerceIn(0f, 1f)
            } else if (o != null && t > o.start) {
                type = o.type
                side = 0
                progress = ((t - o.start) / o.duration).toFloat().coerceIn(0f, 1f)
            }
            try {
                gl.use()
                // The compositor on this GL thread leaves blending on; the alpha here must be written, not blended.
                GLES20.glDisable(GLES20.GL_BLEND)
                gl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                gl.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                gl.setFloatUniform("uP", progress)
                gl.setIntUniform("uType", type)
                gl.setIntUniform("uSide", side)
                gl.setIntUniform("uTop", if (isTop) 1 else 0)
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

    companion object {
        /** The shader's number for a transition's stored name. Unknown names play as a cross dissolve. */
        fun typeIndex(name: String) = TransitionTypes.all.indexOf(name).coerceAtLeast(0)

        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""

        // Types follow TransitionTypes.all. q is a canvas point in pixels with y up, like Core Image,
        // so the iPhone's numbers carry straight over. Radii there are in pixels of a 1080-wide canvas.
        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform float uP;
uniform int uType;
uniform int uSide;
uniform int uTop;
varying vec2 vUv;
const float PI = 3.14159265;

float ease(float p) { return p * p * (3.0 - 2.0 * p); }
float easeCubic(float p) { return p < 0.5 ? 4.0 * p * p * p : 1.0 - pow(-2.0 * p + 2.0, 3.0) / 2.0; }
float easeHard(float p) { return p < 0.5 ? 2.0 * p * p : 1.0 - pow(-2.0 * p + 2.0, 2.0) / 2.0; }
float easeQuint(float p) { return p < 0.5 ? 16.0 * pow(p, 5.0) : 1.0 - pow(-2.0 * p + 2.0, 5.0) / 2.0; }
float bell(float p) { return 1.0 - abs(p - 0.5) * 2.0; }
float sinSq(float p) { float s = sin(PI * p); return s * s; }

vec3 tex(vec2 q) { return texture2D(uTex, clamp(q / uSize, 0.0, 1.0)).rgb; }
bool inFrame(vec2 s) { return s.x >= 0.0 && s.y >= 0.0 && s.x <= uSize.x && s.y <= uSize.y; }
vec2 unscale(vec2 q, float s) { vec2 c = uSize * 0.5; return c + (q - c) / s; }
vec2 unrotate(vec2 q, float a) {
  vec2 c = uSize * 0.5;
  vec2 d = q - c;
  float cs = cos(a);
  float sn = sin(a);
  return c + vec2(cs * d.x + sn * d.y, -sn * d.x + cs * d.y);
}
float k() { return uSize.x / 1080.0; }

vec3 motion(vec2 s, float r, vec2 dir) {
  if (r < 0.5) return tex(s);
  vec3 sum = vec3(0.0);
  for (int i = 0; i < 13; i++) {
    float f = float(i) / 12.0 * 2.0 - 1.0;
    sum += tex(s + dir * r * f);
  }
  return sum / 13.0;
}

vec3 soft(vec2 s, float r) {
  if (r < 0.5) return tex(s);
  vec3 sum = tex(s) * 2.0;
  for (int i = 0; i < 8; i++) {
    float a = float(i) * PI / 4.0;
    vec2 d = vec2(cos(a), sin(a));
    sum += tex(s + d * r * 0.5) + tex(s + d * r);
  }
  return sum / 18.0;
}

vec3 screen(vec3 x, vec3 f) { return 1.0 - (1.0 - x) * (1.0 - f); }

// What this clip shows at q. Side 0 is the outgoing clip, side 1 the incoming one.
vec3 layer(vec2 q, float p) {
  bool from = uSide == 0;
  vec3 black = vec3(0.0);
  if (uType == 1 || uType == 2) {
    vec3 c = uType == 1 ? black : vec3(1.0);
    if (from) return p < 0.5 ? mix(tex(q), c, ease(p * 2.0)) : c;
    return p < 0.5 ? c : mix(c, tex(q), ease((p - 0.5) * 2.0));
  }
  if (uType == 3) return from ? tex(q) : tex(unscale(q, 1.6 - 0.6 * easeCubic(p)));
  if (uType == 4) return from ? tex(unscale(q, 1.0 - 0.4 * easeCubic(p))) : tex(q);
  if (uType == 5) {
    float dx = uSize.x * ease(p);
    vec2 s = from ? q + vec2(dx, 0.0) : q - vec2(uSize.x - dx, 0.0);
    return motion(s, 40.0 * k() * bell(p), vec2(1.0, 0.0));
  }
  if (uType == 6) return from ? tex(unrotate(q, easeCubic(p) * PI / 2.0)) : tex(q);
  if (uType == 7) {
    float strength = bell(p);
    vec3 img = tex(q);
    vec3 shifted = img + vec3(tex(q - vec2(8.0 * k() * strength, 0.0)).r, 0.0, 0.0);
    return mix(img, min(shifted, vec3(1.0)), 0.55 * strength);
  }
  if (uType == 8) return tex(unscale(q, from ? 1.0 + 0.25 * p : 1.25 - 0.25 * p));
  if (uType == 9) {
    float e = easeCubic(p);
    vec2 s = from ? unscale(q, 1.0 - 0.2 * e) + vec2(uSize.x * e, 0.0)
                  : unscale(q, 0.8 + 0.2 * e) - vec2(uSize.x * (1.0 - e), 0.0);
    return inFrame(s) ? tex(s) : black;
  }
  if (uType == 10) {
    float dx = uSize.x * easeCubic(p);
    return tex(from ? q + vec2(dx, 0.0) : q - vec2(uSize.x - dx, 0.0));
  }
  if (uType == 11) return motion(q, 40.0 * k() * bell(p), vec2(1.0, 0.0));
  if (uType == 12) return motion(q, 60.0 * k() * bell(p), vec2(1.0, 0.0));
  if (uType == 13) {
    float amp = uSize.x * 0.04 * bell(p);
    vec2 j = vec2(amp * sin(p * PI * 12.0), amp * cos(p * PI * 9.0));
    return tex(from ? q - j : q + j);
  }
  if (uType == 15) return mix(tex(q), vec3(1.0, 0.7, 0.3), 0.6 * bell(p));
  if (uType == 16) return screen(tex(q), vec3(1.0, 1.0, 0.9) * 0.7 * sinSq(p));
  if (uType == 17) {
    float wobble = 1.0 + 0.15 * sin(p * PI);
    return tex(unscale(q, from ? wobble : 2.0 - wobble));
  }
  if (uType == 18) {
    float bloom = sinSq(p);
    vec3 c = soft(q - vec2(0.0, bloom * uSize.y * 0.02), bloom * 14.0 * k());
    return mix(c, vec3(1.0), bloom);
  }
  if (uType == 20) {
    float i = sin(PI * p);
    vec3 c = tex(q);
    float l = dot(c, vec3(0.2125, 0.7154, 0.0721));
    c = clamp(mix(vec3(l), c, 1.0 + i * 0.5) + i * 0.08, 0.0, 1.0);
    float r1 = max(1.0, uSize.x * (0.35 + 0.9 * p));
    float d = distance(q, vec2(uSize.x * 0.72, uSize.y * 0.68));
    vec3 burn = vec3(i, 0.32 * i, 0.06 * i) * clamp(1.0 - d / r1, 0.0, 1.0);
    return screen(c, burn);
  }
  if (uType == 21) {
    float dy = uSize.y * easeQuint(p);
    vec2 s = from ? q - vec2(0.0, dy) : q + vec2(0.0, uSize.y - dy);
    float m = min(p, 1.0 - p);
    return motion(s, 16.0 * m * m * m * m * uSize.y * 0.035, vec2(0.0, 1.0));
  }
  if (uType == 22) {
    if (from) {
      float t = min(p * 2.0, 1.0);
      return soft(unscale(q, 1.0 + 0.8 * t * t), t * 10.0 * k());
    }
    float t = max((p - 0.5) * 2.0, 0.0);
    return tex(unscale(q, 1.0 + 0.30 * (1.0 - t) * abs(cos(t * PI * 1.5))));
  }
  if (uType == 24) {
    float amt = (sin(PI * p) * 42.0 + 1.0) * k();
    vec2 c = uSize * 0.5;
    return tex(c + (floor((q - c) / amt) + 0.5) * amt);
  }
  return tex(q);
}

// How far this pixel has moved over to the incoming clip, 0 to 1.
float amount(vec2 q, float p) {
  if (uType == 1 || uType == 2 || uType == 7 || uType == 13 || uType == 22) return p < 0.5 ? 0.0 : 1.0;
  if (uType == 3) return easeCubic(p);
  if (uType == 4) return inFrame(unscale(q, 1.0 - 0.4 * easeCubic(p))) ? easeCubic(p) : 1.0;
  if (uType == 5) {
    float r = max(40.0 * k() * bell(p), 0.5);
    return smoothstep(uSize.x - r, uSize.x + r, q.x + uSize.x * ease(p));
  }
  if (uType == 6) return inFrame(unrotate(q, easeCubic(p) * PI / 2.0)) ? easeCubic(p) : 1.0;
  if (uType == 8) return ease(p);
  if (uType == 9) {
    float e = easeCubic(p);
    return inFrame(unscale(q, 1.0 - 0.2 * e) + vec2(uSize.x * e, 0.0)) ? 0.0 : 1.0;
  }
  if (uType == 10) return q.x + uSize.x * easeCubic(p) <= uSize.x ? 0.0 : 1.0;
  if (uType == 12) return easeHard(p);
  if (uType == 14) {
    float kk = 3.0;
    float l = dot(tex(q), vec3(0.2126, 0.7152, 0.0722));
    return clamp(kk * l + (1.0 + kk) * p - kk, 0.0, 1.0);
  }
  if (uType == 17) {
    float wobble = 1.0 + 0.15 * sin(p * PI);
    return inFrame(unscale(q, 2.0 - wobble)) ? p : 0.0;
  }
  if (uType == 19 || uType == 23) {
    float maxR = length(uSize) * 0.5;
    float sf = uType == 19 ? maxR * 0.22 : max(2.0, maxR * 0.03);
    float r0 = easeCubic(p) * (maxR + sf) - sf;
    float in0 = max(0.0, r0);
    float out0 = max(in0 + 0.5, r0 + sf);
    return 1.0 - clamp((distance(q, uSize * 0.5) - in0) / (out0 - in0), 0.0, 1.0);
  }
  if (uType == 21) {
    float dy = uSize.y * easeQuint(p);
    float m = min(p, 1.0 - p);
    float r = max(16.0 * m * m * m * m * uSize.y * 0.035, 0.5);
    return 1.0 - smoothstep(-r, r, q.y - dy);
  }
  return p;
}

void main() {
  if (uType < 0) {
    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
    return;
  }
  vec2 q = vUv * uSize;
  float m = uTop == 1 ? amount(q, uP) : 0.0;
  float alpha = uTop == 1 ? (uSide == 0 ? 1.0 - m : m) : 1.0;
  gl_FragColor = vec4(layer(q, uP), alpha);
}
"""
    }
}
