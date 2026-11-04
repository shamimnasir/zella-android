package com.zellahq.app.captions

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import com.zellahq.core.project.CaptionCue
import com.zellahq.core.project.CaptionTrack
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Draws captions onto the frame, the same in preview and export. Uses the iPhone's style names and
 * its colours. The shown cue is the one under the playhead; the word being spoken is marked.
 * Android's text engine shapes Bangla and Hindi joined letters, so those draw correctly.
 */
@OptIn(UnstableApi::class)
class CaptionOverlay(
    track: CaptionTrack,
    private val width: Int,
    private val height: Int,
    private val typeface: Typeface = Typeface.DEFAULT_BOLD,
) : CanvasOverlay(false) {
    private val cues = track.cues.sortedBy { it.start }
    private val preset = track.extras["preset"]?.jsonPrimitive?.contentOrNull ?: "Word Pop"
    private val y = track.extras["position"]?.jsonObject?.get("y")?.jsonPrimitive?.doubleOrNull ?: 0.72
    private val hidden = track.extras["hidden"]?.jsonPrimitive?.contentOrNull == "true"
    private val scale = width / 1080f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = this@CaptionOverlay.typeface }
    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@CaptionOverlay.typeface
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            color = Color.BLACK
        }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setCanvasSize(width, height)
    }

    // Same sizes and highlight colours as the iPhone's CaptionPreset, in points on a 1080-wide frame.
    private val baseSize =
        when (preset) {
            "Hormozi" -> 86f
            "Beast" -> 88f
            "Clean" -> 58f
            "Highlighter" -> 56f
            "Bounce" -> 64f
            else -> 70f
        } * scale
    private val accent =
        when (preset) {
            "Word Pop", "Hormozi", "Bounce" -> Color.rgb(255, 214, 10)
            "Karaoke", "Neon" -> Color.rgb(57, 255, 136)
            "Pop Box" -> Color.rgb(124, 108, 255)
            else -> Color.WHITE
        }

    override fun onDraw(
        canvas: Canvas,
        presentationTimeUs: Long,
    ) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (hidden) return
        val t = presentationTimeUs / 1e6
        val cue = cueAt(t) ?: return
        val active = cue.words.indexOfLast { it.start <= t }.coerceAtLeast(0)
        if (preset == "Beast") {
            drawWords(canvas, listOf(cue.words[active].text.uppercase()), 0, baseSize * 1.15f, t, cue)
            return
        }
        val words = cue.words.map { if (preset == "Hormozi") it.text.uppercase() else it.text }
        drawWords(canvas, words, active, baseSize, t, cue)
    }

    private fun cueAt(t: Double): CaptionCue? = cues.lastOrNull { it.start <= t && t < it.end + 0.15 }

    private fun drawWords(
        canvas: Canvas,
        words: List<String>,
        active: Int,
        size: Float,
        t: Double,
        cue: CaptionCue,
    ) {
        fill.textSize = size

        // The gap is wider than a space: the spoken word is drawn up to 18% bigger and must not touch
        // its neighbours ("Elemakescaptions" on the emulator, 2026-11-04).
        fun lineWidth() = words.sumOf { fill.measureText(it).toDouble() }.toFloat() + (words.size - 1) * fill.measureText(" ") * 1.6f
        // Shrink a line that would not fit 90% of the width.
        val fit = width * 0.9f / maxOf(1f, lineWidth())
        val s = if (fit < 1f) size * fit else size
        fill.textSize = s
        stroke.textSize = s
        stroke.strokeWidth = if (preset == "Clean") 0f else s * if (preset == "Hormozi" || preset == "Beast") 0.16f else 0.11f
        val total = lineWidth()
        var x = (width - total) / 2f
        val baseY = (height * y).toFloat()
        val space = fill.measureText(" ") * 1.6f
        words.forEachIndexed { i, word ->
            val w = fill.measureText(word)
            val isActive = i == active
            val spoken = i <= active
            val color =
                when (preset) {
                    "Karaoke" -> if (spoken) accent else Color.WHITE
                    "Clean", "Beast", "Highlighter", "Pop Box" -> Color.WHITE
                    else -> if (isActive) accent else Color.WHITE
                }
            if (isActive && preset == "Pop Box") {
                box.color = accent
                canvas.drawRoundRect(RectF(x - s * 0.15f, baseY - s * 0.95f, x + w + s * 0.15f, baseY + s * 0.25f), s * 0.2f, s * 0.2f, box)
            }
            if (isActive && preset == "Highlighter") {
                // The marker sweeps across the word while it is spoken.
                val word0 = cue.words.getOrNull(i)
                val p =
                    if (word0 == null) {
                        1f
                    } else {
                        ((t - word0.start) / (word0.end - word0.start).coerceAtLeast(0.05)).toFloat().coerceIn(
                            0f,
                            1f,
                        )
                    }
                box.color = Color.argb(220, 255, 214, 10)
                canvas.drawRoundRect(
                    RectF(x - s * 0.1f, baseY - s * 0.85f, x + (w + s * 0.2f) * p - s * 0.1f, baseY + s * 0.2f),
                    s * 0.15f,
                    s * 0.15f,
                    box,
                )
            }
            val grow = if (isActive && (preset == "Word Pop" || preset == "Bounce")) (if (preset == "Bounce") 1.18f else 1.08f) else 1f
            canvas.save()
            canvas.scale(grow, grow, x + w / 2, baseY - s * 0.35f)
            if (preset == "Neon") {
                fill.setShadowLayer(
                    s * 0.35f,
                    0f,
                    0f,
                    accent,
                )
            } else {
                fill.setShadowLayer(if (preset == "Clean") s * 0.12f else 0f, 0f, s * 0.04f, Color.argb(160, 0, 0, 0))
            }
            if (stroke.strokeWidth > 0) canvas.drawText(word, x, baseY, stroke)
            fill.color = color
            canvas.drawText(word, x, baseY, fill)
            canvas.restore()
            x += w + space
        }
    }
}
