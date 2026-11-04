package com.zellahq.app.titles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import com.zellahq.core.motion.MotionAnchor
import com.zellahq.core.motion.MotionCaret
import com.zellahq.core.motion.MotionFrame
import com.zellahq.core.motion.MotionRGB
import com.zellahq.core.motion.SplitMix64
import com.zellahq.core.motion.UnitInfo
import com.zellahq.core.motion.UnitState
import java.nio.IntBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * One title baked for motion: the text alone as a picture, cut into slabs that tile it back together,
 * one per letter cluster. Each frame draws every group under its own transform, the iPhone's
 * GlyphCompositor. All layout is in the bake's pixels, top-left origin. Android's canvas has a real
 * perspective matrix, so 3D turns use one instead of the iPhone's strips.
 */
class TitleMotionBake(
    private val textOnly: Bitmap,
    val units: List<UnitInfo>,
    private val emPixels: Float,
    private val glyphPaths: List<Path?>?,
    private val accent: MotionRGB = MotionRGB(1.0, 0.78, 0.24),
    private val inkColor: MotionRGB = MotionRGB.White,
) {
    private val width = textOnly.width
    private val height = textOnly.height
    private val slabs: List<Rect> = computeSlabs(units, width, height)
    private val sharp = ConcurrentHashMap<Long, Bitmap>()
    private val blurred = ConcurrentHashMap<Long, Pair<Bitmap, Int>>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pathLengths = HashMap<Int, Float>()

    private fun key(r: Rect) = (r.left.toLong() shl 48) or (r.top.toLong() shl 32) or (r.width().toLong() shl 16) or r.height().toLong()

    private fun sprite(r: Rect): Bitmap? {
        if (r.width() < 1 || r.height() < 1) return null
        return sharp.getOrPut(key(r)) { Bitmap.createBitmap(textOnly, r.left, r.top, r.width(), r.height()) }
    }

    private fun blurLevel(
        r: Rect,
        level: Int,
    ): Pair<Bitmap, Int>? {
        val k = key(r) xor (level.toLong() shl 60)
        blurred[k]?.let { return it }
        val s = sprite(r) ?: return null
        val radius = BLUR_LEVELS[level] * emPixels
        val pad = ceil(radius * 2.2f).toInt() + 2
        val made = BoxBlur.blurred(s, radius, pad) to pad
        blurred[k] = made
        return made
    }

    private fun blurPad(b: Float): Float {
        var level = BLUR_LEVELS.size - 1
        for (i in BLUR_LEVELS.indices) {
            if (b <= BLUR_LEVELS[i]) {
                level = i
                break
            }
        }
        return ceil(BLUR_LEVELS[level] * emPixels * 2.2f) + 2
    }

    private fun slabRect(members: List<Int>): Rect {
        val r = Rect()
        members.forEach { if (it < slabs.size) r.union(slabs[it]) }
        return r
    }

    private fun inkRect(members: List<Int>): RectF {
        val r = RectF()
        members.forEach {
            if (it < units.size) {
                val u = units[it].rect
                r.union(RectF(u.left.toFloat(), u.top.toFloat(), u.right.toFloat(), u.bottom.toFloat()))
            }
        }
        return r
    }

    private fun pivot(
        ink: RectF,
        anchor: MotionAnchor,
    ) = when (anchor) {
        MotionAnchor.Center -> floatArrayOf(ink.centerX(), ink.centerY())
        MotionAnchor.Baseline -> floatArrayOf(ink.centerX(), ink.bottom)
        MotionAnchor.Top -> floatArrayOf(ink.centerX(), ink.top)
        MotionAnchor.Left -> floatArrayOf(ink.left, ink.centerY())
        MotionAnchor.TopLeft -> floatArrayOf(ink.left, ink.top)
    }

    private fun noise(
        seed: ULong,
        k: ULong,
    ) = (SplitMix64.hash01(seed, k, 0x1234uL) * 2 - 1).toFloat()

    /** Draws one frame with the bake's top-left corner at the canvas's current origin. */
    fun draw(
        frame: MotionFrame,
        canvas: Canvas,
    ) {
        frame.groups.forEachIndexed { g, members ->
            if (g < frame.states.size) drawGroup(members, frame.states[g], canvas)
        }
        frame.caret?.let { drawCaret(it, frame, canvas) }
    }

    private fun drawGroup(
        members: List<Int>,
        st: UnitState,
        canvas: Canvas,
    ) {
        if (st.opacity <= 0.0005) return
        val slab = slabRect(members)
        if (slab.width() < 1 || slab.height() < 1) return
        val ink = inkRect(members).let { if (it.isEmpty) RectF(slab) else it }
        val em = emPixels
        val p = pivot(ink, st.anchor)
        var tx = ((st.dx + st.spreadX * st.spreadRank) * em).toFloat()
        var ty = (-st.dy * em).toFloat()
        if (st.jitter > 0) {
            tx += noise(st.noiseSeed, 1uL) * st.jitter.toFloat() * em
            ty += noise(st.noiseSeed, 2uL) * st.jitter.toFloat() * em
        }
        val m = Matrix()
        if (abs(st.rotX) > 1e-6 || abs(st.rotY) > 1e-6) {
            // Project the slab's corners exactly, then let the canvas do the perspective.
            val src =
                floatArrayOf(
                    slab.left.toFloat(),
                    slab.top.toFloat(),
                    slab.right.toFloat(),
                    slab.top.toFloat(),
                    slab.right.toFloat(),
                    slab.bottom.toFloat(),
                    slab.left.toFloat(),
                    slab.bottom.toFloat(),
                )
            val dst = FloatArray(8)
            val cam = max(CAMERA_DISTANCE * em, 1.2f * max(slab.width(), slab.height()))
            val lin = linear(st)
            for (i in 0 until 4) {
                val q = project(src[i * 2], src[i * 2 + 1], p, lin, tx, ty, st.rotX, st.rotY, cam) ?: return
                dst[i * 2] = q[0]
                dst[i * 2 + 1] = q[1]
            }
            if (!m.setPolyToPoly(src, 0, dst, 0, 4)) return
        } else {
            val lin = linear(st)
            val px = p[0] + tx - lin[0] * p[0] - lin[2] * p[1]
            val py = p[1] + ty - lin[1] * p[0] - lin[3] * p[1]
            m.setValues(floatArrayOf(lin[0], lin[2], px, lin[1], lin[3], py, 0f, 0f, 1f))
        }
        val save = canvas.save()
        if (st.clipReveal < 1) {
            // The line box: the unit's rest band, a little wider for overhangs.
            canvas.clipRect(ink.left - 0.08f * em, ink.top, ink.right + 0.08f * em, ink.bottom)
        }
        canvas.concat(m)
        if (st.sliceJitter > 0) {
            val bands = 6
            for (k in 0 until bands) {
                val y0 = slab.top + slab.height() * k / bands.toFloat()
                val y1 = slab.top + slab.height() * (k + 1) / bands.toFloat()
                val off = noise(st.noiseSeed, (10 + k).toULong()) * st.sliceJitter.toFloat() * em
                val s = canvas.save()
                canvas.clipRect(slab.left - 4 * em, y0, slab.right + 4 * em, y1)
                canvas.translate(off, 0f)
                drawLayers(members, st, slab, canvas)
                canvas.restoreToCount(s)
            }
        } else {
            drawLayers(members, st, slab, canvas)
        }
        canvas.restoreToCount(save)
    }

    /** Rotate times skew times scale, as (a, b, c, d) with x' = a x + c y, y' = b x + d y. */
    private fun linear(st: UnitState): FloatArray {
        val rot = Math.toRadians(st.rotation)
        val skew = tan(Math.toRadians(st.skewX))
        // scale, then skew with x' = x - skew * y, then rotate.
        val a0 = st.scaleX
        val d0 = st.scaleY
        val c1 = -skew * d0
        val cr = cos(rot)
        val sr = sin(rot)
        return floatArrayOf(
            (cr * a0).toFloat(),
            (sr * a0).toFloat(),
            (cr * c1 - sr * d0).toFloat(),
            (sr * c1 + cr * d0).toFloat(),
        )
    }

    private fun project(
        x: Float,
        y: Float,
        p: FloatArray,
        lin: FloatArray,
        tx: Float,
        ty: Float,
        rotX: Double,
        rotY: Double,
        cam: Float,
    ): FloatArray? {
        val ax = Math.toRadians(rotX)
        val ay = Math.toRadians(rotY)
        val rx = x - p[0]
        val ry = y - p[1]
        val vx = lin[0] * rx + lin[2] * ry
        val vy = lin[1] * rx + lin[3] * ry
        val y1 = vy * cos(ax)
        val z1 = vy * sin(ax)
        val x2 = vx * cos(ay) + z1 * sin(ay)
        val z2 = -vx * sin(ay) + z1 * cos(ay)
        val den = cam + z2
        if (den <= cam * 0.05) return null
        val f = cam / den
        return floatArrayOf((p[0] + tx + x2 * f).toFloat(), (p[1] + ty + y1 * f).toFloat())
    }

    private fun rgb(c: MotionRGB) = Color.rgb((c.r * 255).roundToInt(), (c.g * 255).roundToInt(), (c.b * 255).roundToInt())

    private fun drawSprite(
        canvas: Canvas,
        b: Bitmap,
        left: Float,
        top: Float,
        alpha: Float,
    ) {
        paint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        paint.colorFilter = null
        canvas.drawBitmap(b, left, top, paint)
    }

    private fun drawTinted(
        canvas: Canvas,
        b: Bitmap,
        left: Float,
        top: Float,
        color: Int,
        alpha: Float,
    ) {
        tintPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        tintPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        canvas.drawBitmap(b, left, top, tintPaint)
    }

    private fun drawLayers(
        members: List<Int>,
        st: UnitState,
        slab: Rect,
        canvas: Canvas,
    ) {
        val opacity = st.opacity.toFloat()
        val fillAlpha = opacity * st.fillOpacity.toFloat()
        val strokeAlpha = opacity * (1 - st.fillOpacity.toFloat())
        val tint = st.tintColor ?: accent

        // Scramble: show another cluster of the title, one per member glyph.
        if (st.scramble > 0.5 && units.size > 1) {
            members.forEachIndexed { k, m ->
                if (m >= units.size || m >= slabs.size) return@forEachIndexed
                var idx = (st.scrambleIndex + k * 7919) % units.size
                val same = if (members.size == 1) idx in members else idx == m
                if (same) idx = (idx + 1) % units.size
                val other = slabs[idx]
                val img = sprite(other) ?: return@forEachIndexed
                val inkM = units[m].rect
                val inkO = units[idx].rect
                val left = other.left + ((inkM.left + inkM.right) / 2 - (inkO.left + inkO.right) / 2).toFloat()
                val top = slabs[m].top.toFloat()
                drawSprite(canvas, img, left, top, fillAlpha)
                if (st.tint > 0) drawTinted(canvas, img, left, top, rgb(tint), fillAlpha * st.tint.toFloat())
            }
            return
        }

        val img = sprite(slab) ?: return
        val left = slab.left.toFloat()
        val top = slab.top.toFloat()
        if (st.rgbSplit > 0) {
            val d = (st.rgbSplit * emPixels).roundToInt().toFloat()
            drawTinted(canvas, img, left - d, top, Color.rgb(255, 26, 38), opacity * 0.85f)
            drawTinted(canvas, img, left + d, top, Color.rgb(0, 242, 255), opacity * 0.85f)
        }
        if (st.glow > 0) {
            blurLevel(slab, 1)?.let { (g, pad) ->
                var remaining = st.glow.toFloat()
                while (remaining > 0.0005f) {
                    drawSprite(canvas, g, left - pad, top - pad, min(1f, remaining) * opacity)
                    remaining -= 1
                }
            }
        }
        if (fillAlpha > 0.0005f) {
            if (st.blur > 0) drawBody(slab, st.blur.toFloat(), fillAlpha, canvas) else drawSprite(canvas, img, left, top, fillAlpha)
            if (st.tint > 0) drawTinted(canvas, img, left, top, rgb(tint), fillAlpha * st.tint.toFloat())
        }
        if (strokeAlpha > 0.0005f && st.strokeReveal > 0.0005) drawStroke(members, st, strokeAlpha, canvas, slab)
    }

    /** The fill as a crossfade between the two nearest blur levels, so the blur never jumps. */
    private fun drawBody(
        slab: Rect,
        b: Float,
        alpha: Float,
        canvas: Canvas,
    ) {
        val levels = floatArrayOf(0f) + BLUR_LEVELS
        var lo = levels.size - 1
        for (i in 0 until levels.size - 1) {
            if (b < levels[i + 1]) {
                lo = i
                break
            }
        }
        if (lo == levels.size - 1) {
            drawLevel(slab, lo, alpha, canvas)
            return
        }
        val f = (b - levels[lo]) / (levels[lo + 1] - levels[lo])
        drawLevel(slab, lo + 1, alpha * f, canvas)
        drawLevel(slab, lo, alpha * (1 - f), canvas)
    }

    private fun drawLevel(
        slab: Rect,
        level: Int,
        alpha: Float,
        canvas: Canvas,
    ) {
        if (alpha <= 0.0005f) return
        if (level == 0) {
            sprite(slab)?.let { drawSprite(canvas, it, slab.left.toFloat(), slab.top.toFloat(), alpha) }
        } else {
            blurLevel(slab, level - 1)?.let {
                    (img, pad) ->
                drawSprite(canvas, img, slab.left.toFloat() - pad, slab.top.toFloat() - pad, alpha)
            }
        }
    }

    /** Stroke-on: glyph outlines with a moving dash. */
    private fun drawStroke(
        members: List<Int>,
        st: UnitState,
        alpha: Float,
        canvas: Canvas,
        slab: Rect,
    ) {
        val paths = glyphPaths
        val reveal = st.strokeReveal.toFloat()
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1f, emPixels * 0.035f)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                color = rgb(inkColor)
                this.alpha = (alpha * 255).roundToInt()
            }
        if (paths == null) {
            // No outlines for this text: a soft edge of the letters, revealed left to right.
            blurLevel(slab, 0)?.let { (img, pad) ->
                val s = canvas.save()
                canvas.clipRect(slab.left.toFloat(), slab.top.toFloat(), slab.left + slab.width() * reveal, slab.bottom.toFloat())
                drawTinted(canvas, img, slab.left.toFloat() - pad, slab.top.toFloat() - pad, rgb(inkColor), alpha)
                canvas.restoreToCount(s)
            }
            return
        }
        for (i in members) {
            val path = paths.getOrNull(i) ?: continue
            val len = pathLengths.getOrPut(i) { pathLength(path) }
            if (len <= 0) continue
            p.pathEffect = if (reveal < 1) DashPathEffect(floatArrayOf(max(0.01f, len * reveal), len + 1), 0f) else null
            canvas.drawPath(path, p)
        }
    }

    private fun pathLength(path: Path): Float {
        val m = PathMeasure(path, false)
        var total = 0f
        do {
            total += m.length
        } while (m.nextContour())
        return total
    }

    private fun drawCaret(
        caret: MotionCaret,
        frame: MotionFrame,
        canvas: Canvas,
    ) {
        if (units.isEmpty()) return
        val band: RectF
        val x: Float
        if (caret.afterGroup >= 0 && caret.afterGroup < frame.groups.size) {
            band = inkRect(frame.groups[caret.afterGroup])
            x = band.right + emPixels * 0.06f
        } else {
            band = inkRect(listOf(0))
            x = band.left - emPixels * 0.1f
        }
        val p =
            Paint().apply {
                color = rgb(inkColor)
                alpha = (caret.opacity * 255).roundToInt()
            }
        canvas.drawRect(x, band.top, x + max(1f, emPixels * 0.07f), band.bottom, p)
    }

    companion object {
        /** Blur level radii in em, about 4, 10 and 24 px at a 1080p title size. */
        val BLUR_LEVELS = floatArrayOf(0.07f, 0.17f, 0.4f)

        /** Perspective camera distance in em. */
        const val CAMERA_DISTANCE = 4f

        /** Slabs tile the raster: cuts halfway between neighbouring lines and neighbouring letters. */
        fun computeSlabs(
            units: List<UnitInfo>,
            w: Int,
            h: Int,
        ): List<Rect> {
            if (units.isEmpty()) return emptyList()
            val lineIds = units.map { it.line }.distinct()
            val bands =
                lineIds.map { id ->
                    val m = units.filter { it.line == id }
                    m.minOf { it.rect.top } to m.maxOf { it.rect.bottom }
                }
            val order = bands.indices.sortedBy { (bands[it].first + bands[it].second) / 2 }
            val top = IntArray(bands.size)
            val bottom = IntArray(bands.size) { h }
            order.forEachIndexed { k, bi ->
                if (k + 1 < order.size) {
                    val next = order[k + 1]
                    val cut = (((bands[bi].second + bands[next].first) / 2).roundToInt()).coerceIn(top[bi], h)
                    bottom[bi] = cut
                    top[next] = cut
                } else {
                    bottom[bi] = h
                }
            }
            val slabs = arrayOfNulls<Rect>(units.size)
            lineIds.forEachIndexed { li, id ->
                val members = units.indices.filter { units[it].line == id }.sortedBy { units[it].rect.left }
                var left = 0
                members.forEachIndexed { k, i ->
                    var right = w
                    if (k + 1 < members.size) {
                        right = (((units[i].rect.right + units[members[k + 1]].rect.left) / 2).roundToInt()).coerceIn(left, w)
                    }
                    slabs[i] = Rect(left, top[li], right, bottom[li])
                    left = right
                }
            }
            return slabs.map { it ?: Rect() }
        }
    }
}

/**
 * A three-pass box blur, close to a Gaussian. Software, because video overlays draw on a software
 * canvas. It works on the bitmap's raw premultiplied pixels: getPixels hands back straight colour,
 * and blurring that pulls transparent black into every edge, which draws as a dark box.
 */
object BoxBlur {
    fun blurred(
        src: Bitmap,
        radius: Float,
        pad: Int,
    ): Bitmap {
        val w = src.width + 2 * pad
        val h = src.height + 2 * pad
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        val px = IntArray(w * h)
        out.copyPixelsToBuffer(IntBuffer.wrap(px))
        val tmp = IntArray(w * h)
        // Three box passes of this width match a Gaussian of the asked radius.
        val r = max(1, ((radius * 1.73f - 1) / 2 + 0.5f).toInt())
        repeat(3) {
            run(px, tmp, count = h, length = w, stride = w, step = 1, r = r)
            run(tmp, px, count = w, length = h, stride = 1, step = w, r = r)
        }
        out.copyPixelsFromBuffer(IntBuffer.wrap(px))
        return out
    }

    /**
     * Averages along [count] lines of [length] pixels. A line starts every [stride] pixels and walks
     * in steps of [step]. Each of the four bytes is averaged by itself, whatever order they are in.
     */
    private fun run(
        src: IntArray,
        dst: IntArray,
        count: Int,
        length: Int,
        stride: Int,
        step: Int,
        r: Int,
    ) {
        val div = 2 * r + 1
        for (line in 0 until count) {
            val base = line * stride
            var c0 = 0
            var c1 = 0
            var c2 = 0
            var c3 = 0
            for (k in -r..r) {
                val v = src[base + k.coerceIn(0, length - 1) * step]
                c0 += v ushr 24
                c1 += (v shr 16) and 255
                c2 += (v shr 8) and 255
                c3 += v and 255
            }
            for (k in 0 until length) {
                dst[base + k * step] = ((c0 / div) shl 24) or ((c1 / div) shl 16) or ((c2 / div) shl 8) or (c3 / div)
                val gone = src[base + (k - r).coerceIn(0, length - 1) * step]
                val next = src[base + (k + r + 1).coerceIn(0, length - 1) * step]
                c0 += (next ushr 24) - (gone ushr 24)
                c1 += ((next shr 16) and 255) - ((gone shr 16) and 255)
                c2 += ((next shr 8) and 255) - ((gone shr 8) and 255)
                c3 += (next and 255) - (gone and 255)
            }
        }
    }
}
