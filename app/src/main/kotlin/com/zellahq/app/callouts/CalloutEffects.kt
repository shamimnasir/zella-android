package com.zellahq.app.callouts

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlayEffect
import com.zellahq.core.project.Callout
import com.zellahq.core.project.CalloutKind
import com.zellahq.core.project.Project
import com.zellahq.core.project.callouts
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Everything callouts add to the picture, in order: what changes the video's pixels, then what is drawn over it. */
@OptIn(UnstableApi::class)
fun calloutEffects(
    p: Project,
    size: Size,
): List<Effect> {
    val all = p.callouts.filter { it.kind != null }
    if (all.isEmpty()) return emptyList()
    val frame = all.filter { it.kind!!.isFrameEffect }
    return buildList {
        frame.chunked(FrameCalloutEffect.MAX_ACTIVE).forEach { add(FrameCalloutEffect(it)) }
        if (all.any { it.kind != CalloutKind.Blur && it.kind != CalloutKind.Pixelate && it.kind != CalloutKind.Glitch }) {
            add(OverlayEffect(listOf(CalloutsOverlay(all, size.width, size.height))))
        }
    }
}

/**
 * Blur, pixelate, magnifier and glitch, on the video's own pixels with the iPhone's amounts. A chunk
 * holds at most [MAX_ACTIVE] callouts, so none can ever be dropped for lack of room: a blur over a
 * password must not go missing because many others share the frame.
 */
@OptIn(UnstableApi::class)
class FrameCalloutEffect(private val callouts: List<Callout>) : GlEffect {
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
            val active = callouts.filter { it.isActive(t) }
            val rects = FloatArray(MAX_ACTIVE * 4)
            val kinds = FloatArray(MAX_ACTIVE)
            val params = FloatArray(MAX_ACTIVE)
            active.forEachIndexed { i, c ->
                val r = c.rectAt(t)
                // Stored top-left, drawn with the origin at the bottom.
                rects[i * 4] = r[0].toFloat()
                rects[i * 4 + 1] = (1 - r[1] - r[3]).toFloat()
                rects[i * 4 + 2] = (r[0] + r[2]).toFloat()
                rects[i * 4 + 3] = (1 - r[1]).toFloat()
                when (c.kind) {
                    CalloutKind.Blur -> kinds[i] = 1f
                    CalloutKind.Pixelate -> kinds[i] = 2f
                    CalloutKind.Magnifier -> {
                        kinds[i] = 3f
                        params[i] = c.magnifyScale.toFloat()
                    }
                    else -> {
                        kinds[i] = 4f
                        val tick = max(0, (t * 24).toInt())
                        params[i] = (max(2.0, r[2] * width * 0.025) * JITTER[tick % JITTER.size]).toFloat()
                    }
                }
            }
            try {
                gl.use()
                GLES20.glDisable(GLES20.GL_BLEND)
                gl.setSamplerTexIdUniform("uTex", inputTexId, 0)
                gl.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                gl.setIntUniform("uCount", active.size)
                gl.bindAttributesAndUniforms()
                // After binding: GlProgram writes its own zeros to the first element of any array uniform.
                val p = gl.glProgramId()
                GLES20.glUniform4fv(GLES20.glGetUniformLocation(p, "uRects"), MAX_ACTIVE, rects, 0)
                GLES20.glUniform1fv(GLES20.glGetUniformLocation(p, "uKinds"), MAX_ACTIVE, kinds, 0)
                GLES20.glUniform1fv(GLES20.glGetUniformLocation(p, "uParams"), MAX_ACTIVE, params, 0)
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

    private fun GlProgram.glProgramId(): Int {
        val out = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, out, 0)
        return out[0]
    }

    companion object {
        const val MAX_ACTIVE = 12
        private val JITTER = doubleArrayOf(1.0, 0.35, 0.8, 0.15, 0.6, 0.9, 0.25, 0.7)

        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""

        // Blur radius and pixel block are the iPhone's: 2 percent of the canvas width, blocks at least 8 px.
        private const val FRAGMENT = """
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
uniform int uCount;
uniform vec4 uRects[12];
uniform float uKinds[12];
uniform float uParams[12];
varying vec2 vUv;

vec3 blur() {
  float sigma = uSize.x * 0.02;
  vec3 sum = vec3(0.0);
  float total = 0.0;
  for (int x = -6; x <= 6; x++) {
    for (int y = -6; y <= 6; y++) {
      vec2 o = vec2(float(x), float(y)) * sigma * 0.45;
      float w = exp(-dot(o, o) / (2.0 * sigma * sigma));
      sum += texture2D(uTex, clamp(vUv + o / uSize, 0.0, 1.0)).rgb * w;
      total += w;
    }
  }
  return sum / total;
}

vec3 pixelate(vec4 r) {
  float block = max(8.0, uSize.x * 0.02);
  vec2 centre = (r.xy + r.zw) * 0.5 * uSize;
  vec2 q = vUv * uSize;
  vec2 snapped = centre + (floor((q - centre) / block) + 0.5) * block;
  return texture2D(uTex, clamp(snapped / uSize, 0.0, 1.0)).rgb;
}

void main() {
  vec3 c = texture2D(uTex, vUv).rgb;
  for (int i = 11; i >= 0; i--) {
    if (i >= uCount) continue;
    vec4 r = uRects[i];
    if (vUv.x < r.x || vUv.x > r.z || vUv.y < r.y || vUv.y > r.w) continue;
    float kind = uKinds[i];
    if (kind < 1.5) {
      c = blur();
    } else if (kind < 2.5) {
      c = pixelate(r);
    } else if (kind < 3.5) {
      vec2 centre = (r.xy + r.zw) * 0.5;
      vec2 q = vUv * uSize;
      float radius = min((r.z - r.x) * uSize.x, (r.w - r.y) * uSize.y) * 0.5;
      float d = distance(q, centre * uSize);
      if (d > radius) continue;
      vec2 zoomed = centre + (vUv - centre) / max(1.01, uParams[i]);
      c = mix(texture2D(uTex, zoomed).rgb, c, smoothstep(radius - 1.5, radius, d));
    } else {
      float dx = uParams[i] / uSize.x;
      c = vec3(texture2D(uTex, vUv - vec2(dx, 0.0)).r, texture2D(uTex, vUv).g, texture2D(uTex, vUv + vec2(dx, 0.0)).b);
    }
    break;
  }
  gl_FragColor = vec4(c, 1.0);
}
"""
    }
}

/** The drawn callouts, each one the iPhone's drawing moved to a y-down canvas. */
@OptIn(UnstableApi::class)
class CalloutsOverlay(
    private val callouts: List<Callout>,
    private val w: Int,
    private val h: Int,
) : CanvasOverlay(false) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setCanvasSize(w, h)
    }

    override fun onDraw(
        canvas: Canvas,
        presentationTimeUs: Long,
    ) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val t = presentationTimeUs / 1e6
        callouts.filter { it.isActive(t) }.forEach { draw(canvas, it, t) }
    }

    private fun draw(
        canvas: Canvas,
        c: Callout,
        time: Double,
    ) {
        val kind = c.kind ?: return
        if (kind == CalloutKind.Blur || kind == CalloutKind.Pixelate || kind == CalloutKind.Glitch) return
        val n = c.rectAt(time)
        val rect = RectF((n[0] * w).toFloat(), (n[1] * h).toFloat(), ((n[0] + n[2]) * w).toFloat(), ((n[1] + n[3]) * h).toFloat())
        val color = c.color.toArgb()
        val lw = (max(3.0, w * 0.006) * c.strokeScale).toFloat()
        val phase = c.phase(time)
        val alpha = (phase.alpha.coerceAtLeast(0.0) * c.opacity * 255).toInt().coerceIn(0, 255)
        if (alpha == 0) return
        val saved = canvas.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), alpha)
        c.rotationDegrees?.takeIf { kotlin.math.abs(it) > 0.01 && kind != CalloutKind.Spotlight }?.let {
            canvas.rotate(it.toFloat(), rect.centerX(), rect.centerY())
        }
        if (c.entrance == "drawOn" && kind != CalloutKind.Spotlight) {
            val p = c.drawOnProgress(time).toFloat()
            if (p <= 0.001f) {
                canvas.restoreToCount(saved)
                return
            }
            if (p < 0.999f) {
                val padY = rect.height() * 0.5f + lw * 2
                canvas.clipRect(rect.left - lw, rect.top - padY, rect.left - lw + (rect.width() + lw * 2) * p, rect.bottom + padY)
            }
        }
        if (kind != CalloutKind.Spotlight && (phase.scale != 1.0 || phase.dx != 0.0 || phase.dy != 0.0)) {
            canvas.translate((phase.dx * w).toFloat(), (phase.dy * h).toFloat())
            canvas.scale(phase.scale.toFloat(), phase.scale.toFloat(), rect.centerX(), rect.centerY())
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.color = color
        paint.strokeWidth = lw
        paint.style = Paint.Style.STROKE
        when (kind) {
            CalloutKind.Box -> canvas.drawRect(rect, paint)
            CalloutKind.Ellipse -> canvas.drawOval(rect, paint)
            CalloutKind.Spotlight -> {
                canvas.drawColor(Color.argb(140, 0, 0, 0))
                paint.style = Paint.Style.FILL
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                canvas.drawOval(rect, paint)
                paint.xfermode = null
            }
            CalloutKind.Arrow -> arrow(canvas, rect, lw, color)
            CalloutKind.Label, CalloutKind.Keystroke, CalloutKind.Number -> pill(canvas, c, kind, rect, color, time)
            CalloutKind.Magnifier -> magnifier(canvas, rect, lw, color)
            CalloutKind.LeaderLine -> leader(canvas, c, rect, lw, color)
            CalloutKind.Popup -> popup(canvas, c, rect, color, time)
            CalloutKind.Highlight -> {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(color, 0.35f)
                canvas.drawRoundRect(rect, rect.height() * 0.18f, rect.height() * 0.18f, paint)
            }
            CalloutKind.Underline -> {
                paint.style = Paint.Style.FILL
                paint.color = withAlpha(color, 0.85f)
                val bar = max(5f, w * 0.009f)
                canvas.drawRoundRect(RectF(rect.left, rect.bottom - bar, rect.right, rect.bottom), bar / 2, bar / 2, paint)
            }
            CalloutKind.Emoji -> {
                text.reset()
                text.isAntiAlias = true
                text.textSize = max(24f, w * 0.12f)
                text.setShadowLayer(8f, 0f, 3f, Color.argb(128, 0, 0, 0))
                val s = c.text?.ifEmpty { null } ?: "👉"
                canvas.drawText(s, rect.centerX() - text.measureText(s) / 2, rect.centerY() + text.textSize * 0.4f, text)
            }
            CalloutKind.StickyNote -> sticky(canvas, c, rect)
            CalloutKind.ScribbleCircle -> scribble(canvas, rect, max(3f, lw))
            CalloutKind.Timer -> timer(canvas, c, rect, lw, color, time)
            else -> {}
        }
        canvas.restoreToCount(saved)
    }

    private fun withAlpha(
        color: Int,
        a: Float,
    ) = Color.argb((a * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun label(
        size: Float,
        bold: Boolean = true,
    ) {
        text.reset()
        text.isAntiAlias = true
        text.color = Color.WHITE
        text.textSize = size
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun arrow(
        canvas: Canvas,
        r: RectF,
        lw: Float,
        color: Int,
    ) {
        // From the top-left of the box to its bottom-right, with a filled head.
        val head = max(lw * 4, min(r.width(), r.height()) * 0.25f)
        val angle = kotlin.math.atan2(r.height(), r.width())
        val endX = r.right - cos(angle) * head * 0.8f
        val endY = r.bottom - sin(angle) * head * 0.8f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(r.left, r.top, endX, endY, paint)
        val path = Path()
        path.moveTo(r.right, r.bottom)
        path.lineTo(r.right - cos(angle - 0.45f) * head, r.bottom - sin(angle - 0.45f) * head)
        path.lineTo(r.right - cos(angle + 0.45f) * head, r.bottom - sin(angle + 0.45f) * head)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(path, paint)
    }

    private fun pill(
        canvas: Canvas,
        c: Callout,
        kind: CalloutKind,
        r: RectF,
        color: Int,
        time: Double,
    ) {
        val size = w * 0.04f
        label(size)
        val s0 = if (kind == CalloutKind.Number) c.numberText(time) else c.text ?: "Label"
        val s = if (kind == CalloutKind.Keystroke) " $s0 " else s0
        val tw = text.measureText(s)
        val pillH = size * 1.5f
        val pill = RectF(r.left, r.top, r.left + max(pillH, tw + size), r.top + pillH)
        paint.style = Paint.Style.FILL
        paint.color = color
        val radius = if (kind == CalloutKind.Number) pillH / 2 else size * 0.3f
        canvas.drawRoundRect(pill, radius, radius, paint)
        canvas.drawText(s, pill.centerX() - tw / 2, pill.centerY() + size * 0.35f, text)
    }

    private fun magnifier(
        canvas: Canvas,
        r: RectF,
        lw: Float,
        color: Int,
    ) {
        val rad = min(r.width(), r.height()) / 2
        canvas.drawCircle(r.centerX(), r.centerY(), rad, paint)
        paint.color = Color.argb(140, 255, 255, 255)
        paint.strokeWidth = max(1f, lw * 0.4f)
        canvas.drawCircle(r.centerX(), r.centerY(), rad - lw, paint)
        val ch = rad * 0.22f
        paint.color = color
        paint.strokeWidth = max(1f, lw * 0.5f)
        canvas.drawLine(r.centerX() - ch, r.centerY(), r.centerX() + ch, r.centerY(), paint)
        canvas.drawLine(r.centerX(), r.centerY() - ch, r.centerX(), r.centerY() + ch, paint)
    }

    private fun leader(
        canvas: Canvas,
        c: Callout,
        r: RectF,
        lw: Float,
        color: Int,
    ) {
        val chipX = r.left + r.width() * 0.15f
        val chipY = r.top
        val targetX = r.right
        val targetY = r.bottom
        paint.strokeWidth = max(1.5f, lw * 0.6f)
        canvas.drawLine(chipX, chipY, targetX, targetY, paint)
        paint.style = Paint.Style.FILL
        val dot = max(4f, lw * 1.2f)
        canvas.drawCircle(targetX, targetY, dot, paint)
        val size = w * 0.035f
        label(size)
        val s = c.text?.ifEmpty { null } ?: "Detail"
        val tw = text.measureText(s)
        val padX = size * 0.5f
        val chip = RectF(chipX - tw / 2 - padX, chipY - size * 0.85f, chipX + tw / 2 + padX, chipY + size * 0.85f)
        paint.color = Color.argb(235, 26, 26, 31)
        canvas.drawRoundRect(chip, chip.height() * 0.25f, chip.height() * 0.25f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = max(1f, lw * 0.4f)
        canvas.drawRoundRect(chip, chip.height() * 0.25f, chip.height() * 0.25f, paint)
        canvas.drawText(s, chipX - tw / 2, chipY + size * 0.35f, text)
    }

    private fun popup(
        canvas: Canvas,
        c: Callout,
        r: RectF,
        color: Int,
        time: Double,
    ) {
        val p = if (c.entrance == "none") ((time - c.start) / 0.25).coerceIn(0.0, 1.0).toFloat() else 1f
        val size = w * 0.04f * (0.6f + 0.4f * p)
        label(size)
        val s = c.text?.ifEmpty { null } ?: "Click"
        val tw = text.measureText(s)
        val bw = tw + size * 1.4f
        val bh = size + size * 0.9f
        val bubble = RectF(r.centerX() - bw / 2, r.centerY() - bh / 2, r.centerX() + bw / 2, r.centerY() + bh / 2)
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.setShadowLayer(8f, 0f, 3f, Color.argb(128, 0, 0, 0))
        canvas.drawRoundRect(bubble, bh * 0.4f, bh * 0.4f, paint)
        val tail = Path()
        tail.moveTo(bubble.left + bw * 0.22f, bubble.bottom - 2)
        tail.lineTo(bubble.left + bw * 0.40f, bubble.bottom - 2)
        tail.lineTo(bubble.left + bw * 0.24f, bubble.bottom + bh * 0.45f)
        tail.close()
        canvas.drawPath(tail, paint)
        paint.clearShadowLayer()
        canvas.drawText(s, r.centerX() - tw / 2, r.centerY() + size * 0.35f, text)
    }

    private fun sticky(
        canvas: Canvas,
        c: Callout,
        r: RectF,
    ) {
        canvas.save()
        canvas.rotate(-2.9f, r.centerX(), r.centerY())
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 224, 89)
        paint.setShadowLayer(12f, 0f, 5f, Color.argb(115, 0, 0, 0))
        canvas.drawRoundRect(r, 4f, 4f, paint)
        paint.clearShadowLayer()
        label(w * 0.035f)
        text.color = Color.rgb(56, 46, 13)
        val s = c.text?.ifEmpty { null } ?: "Note"
        canvas.drawText(s, r.centerX() - text.measureText(s) / 2, r.centerY() + text.textSize * 0.35f, text)
        canvas.restore()
    }

    /** The same fixed wobble as the iPhone, so every device draws the same scribble. */
    private fun scribble(
        canvas: Canvas,
        r: RectF,
        lw: Float,
    ) {
        paint.strokeWidth = lw
        val rx = r.width() / 2
        val ry = r.height() / 2
        val turns = 1.7
        val steps = (140 * turns).toInt()
        val path = Path()
        for (i in 0..steps) {
            val p = i.toDouble() / steps
            val a = p * turns * 2 * PI - PI / 3
            val wob = 1 + 0.045 * sin(3 * a + 1.1) + 0.030 * sin(7 * a + 2.6) + 0.050 * (p - 0.5)
            val x = r.centerX() + (0.04 * rx * sin(2 * a + 0.7)).toFloat() + rx * (wob * cos(a)).toFloat()
            val y = r.centerY() + (0.04 * ry * cos(3 * a + 1.9)).toFloat() + ry * (wob * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun timer(
        canvas: Canvas,
        c: Callout,
        r: RectF,
        lw: Float,
        color: Int,
        time: Double,
    ) {
        val size = w * 0.05f
        label(size)
        text.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val s = c.timerText(time)
        val tw = text.measureText(s)
        val chip =
            RectF(
                r.centerX() - tw / 2 - size * 0.6f,
                r.centerY() - size / 2 - size * 0.35f,
                r.centerX() + tw / 2 + size * 0.6f,
                r.centerY() + size / 2 + size * 0.35f,
            )
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(230, 20, 20, 26)
        canvas.drawRoundRect(chip, chip.height() * 0.3f, chip.height() * 0.3f, paint)
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = max(2f, lw * 0.6f)
        canvas.drawRoundRect(chip, chip.height() * 0.3f, chip.height() * 0.3f, paint)
        canvas.drawText(s, r.centerX() - tw / 2, r.centerY() + size * 0.35f, text)
    }
}
