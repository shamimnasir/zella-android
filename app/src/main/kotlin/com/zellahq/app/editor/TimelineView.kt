package com.zellahq.app.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.zellahq.core.project.Clip
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.startTime
import com.zellahq.core.project.transitionAfter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * The timeline, drawn by hand on a Canvas: Compose recomposition is too slow to hold the display
 * rate while scrubbing. The playhead stays in the middle; dragging moves the timeline under it.
 * Tap a clip to pick it, drag the white grips at its ends to trim, pinch to zoom in and out.
 *
 * It never edits the project itself: every gesture becomes a callback, so undo sees every change.
 */
@SuppressLint("ViewConstructor")
class TimelineView(
    context: Context,
    private val store: ProjectStore,
) : View(context) {
    var onScrub: (Double) -> Unit = {}
    var onSelect: (String?) -> Unit = {}

    /** Trim grip let go: clip, which end, and how far in source seconds. */
    var onTrim: (clipId: String, start: Boolean, deltaSource: Double) -> Unit = { _, _, _ -> }

    var project: Project? = null
        set(v) {
            field = v
            invalidate()
        }
    var position = 0.0
        set(v) {
            field = v
            invalidate()
        }
    var selectedId: String? = null
        set(v) {
            field = v
            invalidate()
        }

    /** Labels drawn on the blocks, so a beginner sees words, not just shapes. */
    var labelFor: (index: Int, clip: Clip) -> String = { i, _ -> "Clip ${i + 1}" }
    var fadeLabel = "Fade"

    private val density = resources.displayMetrics.density
    private var pxPerSec = 80f * density

    /** Room is kept above the blocks for the transition chips, also when the timeline is short because a panel is open. */
    private val clipHeight get() = minOf(64f * density, height - 40f * density)
    private val grip = 14f * density
    private val corner = 10f * density

    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            color = Color.WHITE
        }
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 18, 31)
            textSize = 13f * density
            isFakeBoldText = true
        }
    private val playheadPaint =
        Paint().apply {
            color = Color.WHITE
            strokeWidth = 2.5f * density
        }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val background = Paint().apply { color = Color.rgb(14, 13, 22) }
    private val colours =
        intArrayOf(Color.rgb(124, 108, 255), Color.rgb(94, 224, 176), Color.rgb(255, 200, 87), Color.rgb(255, 122, 107))

    // Thumbnails, one per clip, read off the main thread and drawn when ready.
    private val thumbs = ConcurrentHashMap<String, Bitmap>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val thumbIo = Executors.newSingleThreadExecutor()

    private var dragging: Drag? = null

    private sealed interface Drag {
        data class Scrub(val startX: Float, val startPos: Double) : Drag

        data class Trim(val clipId: String, val start: Boolean, val startX: Float, var dx: Float) : Drag
    }

    private val scale =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    pxPerSec = (pxPerSec * d.scaleFactor).coerceIn(10f * density, 600f * density)
                    invalidate()
                    return true
                }
            },
        )

    private val taps =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    onSelect(hit(e.x, e.y)?.id)
                    performClick()
                    return true
                }
            },
        )

    override fun performClick() = super.performClick()

    private fun xFor(t: Double) = width / 2f + ((t - position) * pxPerSec).toFloat()

    private fun clipRect(
        p: Project,
        c: Clip,
    ): RectF {
        val top = (height - clipHeight) / 2f
        val s = p.startTime(c.id)
        return RectF(xFor(s), top, xFor(s + c.timelineDuration), top + clipHeight)
    }

    private fun hit(
        x: Float,
        y: Float,
    ): Clip? {
        val p = project ?: return null
        return p.clips.firstOrNull { clipRect(p, it).contains(x, y) }
    }

    override fun onDraw(canvas: Canvas) {
        val p = project ?: return
        // Fill only this view. drawColor() paints the whole canvas, and inside Compose's AndroidView
        // host that canvas is not clipped to the view: it covered the whole editor (2026-11-04).
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        p.clips.forEachIndexed { i, c ->
            var r = clipRect(p, c)
            val d = dragging
            // While a grip is dragged, draw the clip at its new length before the trim is applied.
            if (d is Drag.Trim && d.clipId == c.id) {
                r = if (d.start) RectF(r.left + d.dx, r.top, r.right, r.bottom) else RectF(r.left, r.top, r.right + d.dx, r.bottom)
            }
            if (r.right < 0 || r.left > width) return@forEachIndexed
            clipPaint.color = colours[i % colours.size]
            canvas.drawRoundRect(r, corner, corner, clipPaint)
            thumbFor(c)?.let { bmp ->
                val th = r.height() - 22f * density
                val tw = th * bmp.width / bmp.height
                var x = r.left + 4 * density
                canvas.save()
                canvas.clipRect(r.left, r.top, r.right, r.bottom)
                while (x < r.right) {
                    canvas.drawBitmap(bmp, null, RectF(x, r.top + 4 * density, x + tw, r.top + 4 * density + th), null)
                    x += tw + 2 * density
                }
                canvas.restore()
            }
            // The label starts clear of the white grip and stops at the block's end, so no letter is hidden or spills over.
            val inset = if (c.id == selectedId) grip + 4 * density else 8 * density
            canvas.save()
            canvas.clipRect(r.left + inset, r.top, r.right - inset, r.bottom)
            canvas.drawText(labelFor(i, c), r.left + inset, r.bottom - 6 * density, textPaint)
            canvas.restore()
            if (c.id == selectedId) {
                canvas.drawRoundRect(r, corner, corner, strokePaint)
                canvas.drawRoundRect(RectF(r.left, r.top, r.left + grip, r.bottom), corner, corner, chipPaint)
                canvas.drawRoundRect(RectF(r.right - grip, r.top, r.right, r.bottom), corner, corner, chipPaint)
            }
        }
        canvas.drawLine(width / 2f, 8 * density, width / 2f, height - 8 * density, playheadPaint)
        // After the playhead, so its line never runs through the word. The chip is as wide as the word in any language.
        p.clips.forEachIndexed { i, c ->
            if (p.transitionAfter(c.id) != null && i < p.clips.size - 1) {
                val r = clipRect(p, c)
                val half = textPaint.measureText(fadeLabel) / 2 + 8 * density
                val chip = RectF(r.right - half, r.top - 18 * density, r.right + half, r.top - 2 * density)
                if (chip.right >= 0 && chip.left <= width) {
                    canvas.drawRoundRect(chip, 8 * density, 8 * density, chipPaint)
                    canvas.drawText(fadeLabel, chip.left + 8 * density, chip.bottom - 4 * density, textPaint)
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        if (scale.isInProgress) {
            dragging = null
            return true
        }
        taps.onTouchEvent(e)
        val p = project ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val sel = p.clips.firstOrNull { it.id == selectedId }
                val r = sel?.let { clipRect(p, it) }
                dragging =
                    when {
                        r != null && abs(e.x - r.left) < grip * 1.6f && e.y in r.top..r.bottom -> Drag.Trim(sel.id, true, e.x, 0f)
                        r != null && abs(e.x - r.right) < grip * 1.6f && e.y in r.top..r.bottom -> Drag.Trim(sel.id, false, e.x, 0f)
                        else -> Drag.Scrub(e.x, position)
                    }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE ->
                when (val d = dragging) {
                    is Drag.Scrub -> onScrub(d.startPos - (e.x - d.startX) / pxPerSec)
                    is Drag.Trim -> {
                        d.dx = e.x - d.startX
                        invalidate()
                    }
                    null -> {}
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val d = dragging
                if (d is Drag.Trim && abs(d.dx) > 2 * density) {
                    val clip = p.clips.first { it.id == d.clipId }
                    // Timeline pixels to source seconds: through the zoom, then the clip's speed.
                    val deltaSource = d.dx / pxPerSec * clip.speed
                    onTrim(d.clipId, d.start, deltaSource.toDouble())
                }
                dragging = null
                invalidate()
            }
        }
        return true
    }

    private fun thumbFor(c: Clip): Bitmap? {
        val key = "${c.source}|${c.trimStart}"
        thumbs[key]?.let { return it }
        if (loading.add(key)) {
            val pid = project?.id ?: return null
            thumbIo.execute {
                runCatching {
                    MediaMetadataRetriever().use { m ->
                        m.setDataSource(store.resolve(c.source, pid).path)
                        m.getScaledFrameAtTime(
                            ((c.trimStart + 0.1) * 1e6).toLong(),
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            160,
                            160,
                        )
                    }
                }.getOrNull()?.let {
                    thumbs[key] = it
                    postInvalidate()
                }
            }
        }
        return null
    }

    // TalkBack: describe the timeline in words. Editing is done with the labelled buttons below it.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val p = project ?: return
        info.contentDescription = contentDescription ?: "Timeline, ${p.clips.size} clips"
    }

    fun release() = thumbIo.shutdown()
}
