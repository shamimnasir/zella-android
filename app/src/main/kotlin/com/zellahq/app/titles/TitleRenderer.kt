package com.zellahq.app.titles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import com.zellahq.core.motion.TextMotion
import com.zellahq.core.motion.UnitInfo
import com.zellahq.core.motion.UnitRect
import com.zellahq.core.project.Rgba
import com.zellahq.core.project.Title
import com.zellahq.core.project.TitleLook
import java.text.BreakIterator
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws titles the way the iPhone's OverlayRenderer does: same layout rules, same entrances, plates,
 * outlines, gradients, extrude, accent word, highlights, text cards and badges. A title with a text
 * animation goes through [TitleMotionBake] while its frame is moving and through the plain draw at
 * rest, so settled pixels never depend on the animation. Canvas coordinates are top-left, y down.
 */
class TitleRenderer(
    private val context: Context,
    private val canvasW: Int,
    private val canvasH: Int,
) {
    /** One line's piece of text in one style. */
    private class Run(val text: String, val start: Int, val paint: TextPaint, val accent: Boolean)

    private class Geometry(
        val look: TitleLook,
        val text: String,
        val fontSize: Float,
        val lines: List<String>,
        val firstWords: List<Int>,
        val leading: Float,
        val cx: Float,
        val cy: Float,
        val accentIndex: Int?,
    )

    private val canvasScale = canvasW / 1080f
    private val bakes = ConcurrentHashMap<String, Baked>()

    private class Baked(val bake: TitleMotionBake, val left: Float, val top: Float)

    private fun words(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun firstWordIndices(lines: List<String>): List<Int> {
        var n = 0
        return lines.map { l ->
            val at = n
            n += words(l).size
            at
        }
    }

    private fun typeface(look: TitleLook): Typeface {
        val name = look.fontName?.takeIf { it.isNotEmpty() } ?: if (look.behindSubject) "system-black" else null
        val weight = if (name.isNullOrEmpty() || name == "system-black") look.fontWeight else null
        return TitleFonts.resolve(context, name, weight)
    }

    private fun kern(look: TitleLook) = if (look.fontName.isNullOrEmpty() && look.behindSubject) 0f else KERN

    private fun basePaint(
        look: TitleLook,
        size: Float,
    ) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typeface(look)
        textSize = size
        letterSpacing = kern(look)
        color = look.fill.toArgb()
    }

    /** Pieces of a line with the accent word styled apart. */
    private fun runs(
        g: Geometry,
        i: Int,
        line: String,
        size: Float,
    ): List<Run> {
        val look = g.look
        val base = basePaint(look, size)
        val accent = look.accent
        val target = g.accentIndex
        if (accent == null || target == null) return listOf(Run(line, 0, base, false))
        val k = target - g.firstWords[i]
        val matches = Regex("\\S+").findAll(line).toList()
        val m = matches.getOrNull(k) ?: return listOf(Run(line, 0, base, false))
        val wSize = size * (accent.scale ?: 1.0).toFloat()
        val wp =
            TextPaint(base).apply {
                textSize = wSize
                accent.fontName?.let { typeface = TitleFonts.resolve(context, it) }
                accent.color?.let { color = it.toArgb() }
            }
        val out = ArrayList<Run>()
        if (m.range.first > 0) out += Run(line.substring(0, m.range.first), 0, base, false)
        out += Run(m.value, m.range.first, wp, true)
        if (m.range.last + 1 < line.length) out += Run(line.substring(m.range.last + 1), m.range.last + 1, base, false)
        return out
    }

    private fun lineWidth(runs: List<Run>) = runs.sumOf { it.paint.measureText(it.text).toDouble() }.toFloat()

    private fun geometry(t: Title): Geometry {
        val look = TitleLook(t)
        val text = if (look.uppercase) t.text.uppercase() else t.text
        val accentIndex = look.accent?.wordIndex(words(text))
        val requested = look.fontSize.toFloat() * canvasScale
        val maxWidth = canvasW * 0.88f
        val leading = 1.18f * max(1f, (look.accent?.scale ?: 1.0).toFloat())
        val cx = canvasW * look.positionX.toFloat()
        val cy = canvasH * look.positionY.toFloat()

        fun probe(
            lines: List<String>,
            size: Float,
        ) = Geometry(look, text, size, lines, firstWordIndices(lines), leading, cx, cy, accentIndex)

        fun width(
            lines: List<String>,
            i: Int,
        ): Float = lineWidth(runs(probe(lines, requested), i, lines[i], requested))

        // Explicit line breaks are the author's own: kept, sized to fit the widest.
        if ('\n' in text) {
            val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size > 1) {
                val widest = lines.indices.maxOf { width(lines, it) }
                return probe(lines, if (widest > maxWidth) requested * maxWidth / widest else requested)
            }
        }
        val one = listOf(text)
        val w = width(one, 0)
        if (w <= maxWidth) return probe(one, requested)
        val needed = requested * maxWidth / w
        val parts = text.split(" ")
        if (needed >= requested * 0.55f || parts.size < 2) return probe(one, needed)
        // Two balanced lines: the break that makes the widest line narrowest.
        var best = one
        var bestW = w
        for (i in 1 until parts.size) {
            val pair = listOf(parts.subList(0, i).joinToString(" "), parts.subList(i, parts.size).joinToString(" "))
            val widest = max(width(pair, 0), width(pair, 1))
            if (widest < bestW) {
                best = pair
                bestW = widest
            }
        }
        return probe(best, min(requested, requested * maxWidth / max(1f, bestW)))
    }

    private fun Geometry.baseline(
        i: Int,
        count: Int,
        pitch: Float,
    ) = cy + (i - (count - 1) / 2f) * pitch

    /** The ink band of a line, up from and down from its baseline. */
    private fun inkBand(runs: List<Run>): Pair<Float, Float> {
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        val r = Rect()
        var descent = 0f
        for (run in runs) {
            descent = max(descent, run.paint.fontMetrics.descent)
            if (run.text.isBlank()) continue
            run.paint.getTextBounds(run.text, 0, run.text.length, r)
            top = min(top, r.top.toFloat())
            bottom = max(bottom, r.bottom.toFloat())
        }
        if (top > bottom) {
            val m = runs.first().paint.fontMetrics
            return m.ascent to m.descent
        }
        return top to max(bottom, descent * 0.55f)
    }

    /** The plate behind the settled text, or null. */
    private fun plateRect(g: Geometry): RectF? {
        val look = g.look
        if (look.card || look.box == null || g.lines.isEmpty()) return null
        val fs = g.fontSize
        val pitch = fs * g.leading
        val attrs = g.lines.indices.map { runs(g, it, g.lines[it], fs) }
        val widths = attrs.map { lineWidth(it) }
        val hl = look.highlightLine
        if (hl != null && g.lines.size > 1 && hl in g.lines.indices) {
            val base = g.baseline(hl, g.lines.size, pitch)
            val (t, b) = inkBand(attrs[hl])
            val pad = fs * 0.3f
            return RectF(
                g.cx - widths[hl] / 2 - pad * 0.6f,
                base + t - pad * 0.35f,
                g.cx + widths[hl] / 2 + pad * 0.6f,
                base + b + pad * 0.35f,
            )
        }
        val widest = widths.max()
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        attrs.forEach {
            val (t, b) = inkBand(listOf(*it.toTypedArray()))
            top = min(top, t)
            bottom = max(bottom, b)
        }
        val (px, py) =
            when (look.plateKind) {
                "tag" -> fs * 0.42f to fs * 0.20f
                "pill" -> fs * 0.62f to fs * 0.26f
                "bubble" -> fs * 0.50f to fs * 0.26f
                "paper" -> fs * 0.55f to fs * 0.42f
                else -> fs * 0.30f to fs * 0.15f
            }
        val first = g.baseline(0, g.lines.size, pitch)
        val last = g.baseline(g.lines.size - 1, g.lines.size, pitch)
        return RectF(g.cx - widest / 2 - px, first + top - py, g.cx + widest / 2 + px, last + bottom + py)
    }

    private fun drawPlate(
        kind: String?,
        r: RectF,
        color: Int,
        fs: Float,
        c: Canvas,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        when (kind) {
            "tag" -> {
                p.setShadowLayer(fs * 0.25f, 0f, fs * 0.07f, Color.argb(115, 0, 0, 0))
                val rad = min(r.height() * 0.30f, fs * 0.45f)
                c.drawRoundRect(r, rad, rad, p)
            }
            "pill" -> {
                val rad = r.height() / 2
                c.drawRoundRect(r, rad, rad, p)
                val lw = max(1f, fs * 0.025f)
                val rim =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = lw
                        this.color = Color.argb(77, 255, 255, 255)
                    }
                val inner = RectF(r).apply { inset(lw / 2, lw / 2) }
                c.drawRoundRect(inner, inner.height() / 2, inner.height() / 2, rim)
            }
            "bubble" -> {
                val rad = min(r.height() * 0.5f, fs * 0.62f)
                val tw = fs * 0.42f
                val w = r.width()
                val h = r.height()
                val tail =
                    Path().apply {
                        moveTo(r.left + w - rad, r.top + h - rad)
                        lineTo(r.left + w - rad, r.top + h)
                        quadTo(r.left + w - rad * 0.15f, r.top + h + tw * 0.02f, r.left + w + tw * 0.45f, r.top + h + tw * 0.10f)
                        quadTo(r.left + w - tw * 0.02f, r.top + h - tw * 0.02f, r.left + w, r.top + h - rad * 0.55f)
                        close()
                    }
                val shape =
                    Path().apply {
                        addRoundRect(r, rad, rad, Path.Direction.CW)
                        op(tail, Path.Op.UNION)
                    }
                p.setShadowLayer(fs * 0.15f, 0f, fs * 0.04f, Color.argb(64, 0, 0, 0))
                c.drawPath(shape, p)
            }
            "paper" -> {
                val tooth = max(4f, fs * 0.16f)
                val n = max(4, (r.width() / tooth).roundToInt())
                val step = r.width() / n
                val depth = tooth * 0.45f
                val path =
                    Path().apply {
                        moveTo(r.left, r.top + depth)
                        for (i in 0 until n) {
                            lineTo(r.left + step * (i + 0.5f), r.top)
                            lineTo(r.left + step * (i + 1), r.top + depth)
                        }
                        lineTo(r.right, r.bottom - depth)
                        for (i in n downTo 1) {
                            lineTo(r.left + step * (i - 0.5f), r.bottom)
                            lineTo(r.left + step * (i - 1), r.bottom - depth)
                        }
                        close()
                    }
                p.setShadowLayer(fs * 0.2f, 0f, fs * 0.06f, Color.argb(102, 0, 0, 0))
                c.drawPath(path, p)
            }
            else -> c.drawRoundRect(r, fs * 0.15f, fs * 0.15f, p)
        }
    }

    private fun drawRuns(
        c: Canvas,
        runs: List<Run>,
        x: Float,
        y: Float,
        configure: (TextPaint, Run) -> Unit,
    ) {
        var at = x
        for (run in runs) {
            val p = TextPaint(run.paint)
            configure(p, run)
            c.drawText(run.text, at, y, p)
            at += run.paint.measureText(run.text)
        }
    }

    /** One line: extrude, outline, then the fill, in the iPhone's order. */
    private fun drawLine(
        c: Canvas,
        look: TitleLook,
        runs: List<Run>,
        x: Float,
        y: Float,
        size: Float,
        alpha: Int,
        shadowLayer: (TextPaint) -> Unit,
    ) {
        look.extrudeDepth?.takeIf { it > 0 }?.let { pct ->
            val depth = size * pct.toFloat() / 100
            if (depth >= 1) {
                val steps = ((depth / max(1f, 1.2f * canvasScale)).roundToInt()).coerceIn(3, 28)
                val base = look.extrudeColor ?: Rgba(0.55, 0.22, 0.0)
                val outlined = look.stroke != null
                for (i in steps downTo 1) {
                    val d = depth * i / steps
                    val k = 1 - 0.30 * i / steps
                    val col = Rgba(base.r * k, base.g * k, base.b * k, base.a).toArgb()
                    drawRuns(c, runs, x + d, y + d) { p, _ ->
                        p.color = col
                        p.alpha = alpha
                        if (outlined) {
                            p.style = Paint.Style.FILL_AND_STROKE
                            p.strokeWidth = size * look.strokeWidth.toFloat() / 100
                        }
                    }
                }
            }
        }
        val strokeW = look.strokeWidth.toFloat()
        val stroke = look.stroke
        val fat = strokeW > FAT_OUTLINE
        val gradient = look.gradient
        // An outline goes under the fill. Inline ones straddle the glyph edge; fat ones are doubled so they show whole.
        if (stroke != null) {
            drawRuns(c, runs, x, y) { p, _ ->
                p.style = Paint.Style.STROKE
                p.strokeWidth = size * (if (fat) strokeW * 2 else strokeW) / 100
                p.strokeJoin = Paint.Join.ROUND
                p.strokeCap = Paint.Cap.ROUND
                p.color = stroke.toArgb()
                p.alpha = alpha
                shadowLayer(p)
            }
        }
        drawRuns(c, runs, x, y) { p, run ->
            p.alpha = alpha
            if (stroke == null) shadowLayer(p)
            if (gradient != null && !(run.accent && look.accent?.color != null)) {
                p.color = Color.WHITE
                p.alpha = alpha
                p.shader =
                    LinearGradient(
                        0f, y - size, 0f, y + size * 0.25f,
                        look.fill.toArgb(), gradient.toArgb(), Shader.TileMode.CLAMP,
                    )
            }
        }
    }

    private class Entrance(
        var alpha: Float = 1f,
        var scale: Float = 1f,
        var dx: Float = 0f,
        var dy: Float = 0f,
        var typeFraction: Float? = null,
        var wipe: Float = 1f,
    )

    /** Whole-title offsets over the 0.5 s entrance window; y is down here, so the iPhone's dy changes sign. */
    private fun entranceOffsets(
        look: TitleLook,
        progress: Float,
        life: Float,
        fontSize: Float,
    ): Entrance {
        val e = Entrance()
        val k = look.effectIntensity.toFloat()
        when (look.entrance) {
            "fade", "blurIn", "neon" -> e.alpha = progress
            "pop" -> {
                val over = 1 + 0.18f * sin(PI.toFloat() * min(1f, progress))
                e.scale = (0.6f + 0.4f * progress) * over
                e.alpha = progress
            }
            "scaleIn" -> {
                e.scale = 0.6f + 0.4f * progress
                e.alpha = progress
            }
            "slideUp" -> {
                e.dy = (1 - progress) * canvasH * 0.1f
                e.alpha = progress
            }
            "slideDown" -> {
                e.dy = -(1 - progress) * canvasH * 0.1f
                e.alpha = progress
            }
            "slideLeft" -> {
                e.dx = (1 - progress) * canvasW * 0.15f
                e.alpha = progress
            }
            "slideRight" -> {
                e.dx = -(1 - progress) * canvasW * 0.15f
                e.alpha = progress
            }
            "dropIn" -> {
                e.dy = -(1 - progress) * canvasH * 0.2f
                e.alpha = progress
            }
            "typewriter" -> e.typeFraction = progress
            "wipe" -> e.wipe = progress
            "bounce" -> {
                val p = progress - 1
                e.scale = 1 + (2.7f * k + 1) * p * p * p + 1.7f * k * p * p
                e.alpha = min(1f, progress * 2)
            }
            "shake" -> e.dx = (1 - progress) * sin(progress * 42) * canvasW * 0.02f * k
            "wave" -> {
                e.dy = -sin(life * 2.2f) * fontSize * 0.08f * k
                e.alpha = progress
            }
            "glitch" -> {
                e.dx = (1 - progress) * sin(life * 53) * fontSize * 0.12f * k
                e.alpha = min(1f, progress * 1.5f)
            }
            "Marker Highlight", "Underline Draw", "Box Trace" -> e.alpha = min(1f, progress * 4)
            "none" -> {}
            else -> e.alpha = progress
        }
        return e
    }

    /** True when something on this title is still moving at [time]. Keeps the overlay redrawing. */
    fun isAtRest(
        t: Title,
        time: Double,
    ): Boolean {
        val look = TitleLook(t)
        if (time < t.start || time > t.start + t.duration) return true
        if (look.badge != null) return false
        val life = time - t.start
        if (look.entrance == "wave" || look.entrance == "neon") return false
        if (life < 0.5 && look.entrance != "none") return false
        if (look.isHighlight && life < 3.0) return false
        val spec = look.animation
        if (spec != null && !spec.isEmpty) return TextMotion.isAtRest(spec, bake(t)?.bake?.units.orEmpty(), life, t.duration)
        return true
    }

    /** Draws [t] at timeline [time] if it is on screen then. */
    fun draw(
        t: Title,
        c: Canvas,
        time: Double,
    ) {
        if (time < t.start || time >= t.start + t.duration) return
        val look = TitleLook(t)
        if (look.badge != null) {
            drawBadge(t, look, c, time)
            return
        }
        val motion = look.animation?.takeIf { !it.isEmpty && !look.card && t.text.isNotBlank() }
        val replaced = motion?.inPhase?.let { com.zellahq.core.motion.TextMotionCatalog.preset(it.preset) != null } == true
        if (motion != null) {
            val b = bake(t)
            if (b != null) {
                val frame = TextMotion.evaluate(motion, b.bake.units, time - t.start, t.duration)
                if (!frame.atRest) {
                    val mean = if (frame.states.isEmpty()) 1.0 else frame.states.sumOf { it.opacity.coerceIn(0.0, 1.0) } / frame.states.size
                    drawBody(t, look, c, time, parts = Parts.Chrome, skipEntrance = replaced, subtitleAlpha = mean.toFloat())
                    val save = c.save()
                    tilt(look, c, geometry(t))
                    c.translate(b.left, b.top)
                    b.bake.draw(frame, c)
                    c.restoreToCount(save)
                    return
                }
            }
        }
        drawBody(t, look, c, time, Parts.All, skipEntrance = replaced)
    }

    private enum class Parts { All, Chrome, Text }

    private fun tilt(
        look: TitleLook,
        c: Canvas,
        g: Geometry,
    ) {
        val deg = look.rotationDegrees ?: return
        if (deg == 0.0 || look.card) return
        // Positive tilts counter-clockwise on screen, as on the iPhone.
        c.rotate(-deg.coerceIn(-45.0, 45.0).toFloat(), g.cx, g.cy)
    }

    private fun drawBody(
        t: Title,
        look: TitleLook,
        c: Canvas,
        time: Double,
        parts: Parts,
        skipEntrance: Boolean = false,
        subtitleAlpha: Float = 1f,
        settled: Boolean = false,
    ) {
        val g = geometry(t)
        val life = if (settled) 0f else (time - t.start).toFloat()
        val progress = if (settled || (skipEntrance && !look.isHighlight)) 1f else (life / 0.5f).coerceIn(0f, 1f)
        val e = if (settled) Entrance() else entranceOffsets(look, progress, life, g.fontSize)
        val visible =
            e.typeFraction?.let { f ->
                var remaining = (g.text.length * f).roundToInt()
                g.lines.map { l ->
                    val take = remaining.coerceIn(0, l.length)
                    remaining -= l.length + 1
                    l.take(take)
                }
            } ?: g.lines
        val size = g.fontSize * e.scale
        val pitch = size * g.leading
        val count = visible.size
        val alpha = (e.alpha.coerceIn(0f, 1f) * 255).roundToInt()
        val save = c.save()
        if (parts != Parts.Text) tilt(look, c, g)
        if (parts != Parts.Text && look.card) drawCard(look, c, alpha)
        if (parts != Parts.Text) {
            val box = look.box
            plateRect(g)?.let { r ->
                r.offset(e.dx, e.dy)
                val col = box!!.toArgb()
                drawPlate(
                    look.plateKind,
                    r,
                    Color.argb((Color.alpha(col) * e.alpha).roundToInt(), Color.red(col), Color.green(col), Color.blue(col)),
                    g.fontSize,
                    c,
                )
            }
        }
        val lines = visible.indices.map { runs(g, it, visible[it], size) }

        fun baselineOf(i: Int) = g.cy + (i - (count - 1) / 2f) * pitch + e.dy

        if (parts != Parts.Text && look.isHighlight && visible.any { it.isNotEmpty() }) {
            drawHighlight(t, look, c, time, lines, ::baselineOf, g, e)
        }
        if (parts == Parts.Chrome) {
            drawSubtitle(look, g, c, baselineOf(count - 1), e, alpha, subtitleAlpha)
            c.restoreToCount(save)
            return
        }
        if (e.wipe < 0.999f) {
            val widest = lines.maxOf { lineWidth(it) }
            val left = g.cx - widest / 2 + e.dx - size
            c.clipRect(left, baselineOf(0) - size * 1.5f, left + (widest + size * 2) * e.wipe, baselineOf(count - 1) + size)
        }
        val neon = look.entrance == "neon"
        val glowColor = (look.neonColor ?: look.fill).toArgb()
        val shadow = look.shadow
        val shadowLayer: (TextPaint) -> Unit = { p ->
            if (neon) {
                val pulse = 0.7f + 0.3f * sin(life * 3)
                p.setShadowLayer(max(0.5f, size * 0.35f * pulse * look.effectIntensity.toFloat()), 0f, 0f, withAlpha(glowColor, 230))
            } else if (shadow != null && shadow > 0) {
                p.setShadowLayer(max(0.5f, shadow.toFloat() * canvasScale), 0f, size * 0.04f, Color.argb(166, 0, 0, 0))
            }
        }
        val blur = if (look.entrance == "blurIn" && !settled) (1 - progress) * size * 0.5f else 0f
        val strength = look.effectIntensity.toFloat()
        val glitch = if (look.entrance == "glitch" && !settled) max(0f, 1 - progress * 1.3f) * g.fontSize * 0.14f * strength else 0f
        lines.forEachIndexed { i, runs ->
            if (visible[i].isEmpty()) return@forEachIndexed
            val x = g.cx - lineWidth(runs) / 2 + e.dx
            val y = baselineOf(i)
            if (blur > 0.5f) {
                val n = 8
                val passAlpha = (min(1f, e.alpha * 1.5f / n) * 255).roundToInt()
                for (k in 0 until n) {
                    val a = k.toFloat() / n * 2 * PI.toFloat()
                    drawLine(c, look, runs, x + kotlin.math.cos(a) * blur, y + sin(a) * blur, size, passAlpha, shadowLayer)
                }
            } else {
                if (glitch > 0.5f) {
                    drawRuns(c, runs, x - glitch, y) { p, _ ->
                        p.color = Color.rgb(0, 242, 255)
                        p.alpha = (alpha * 0.6f).roundToInt()
                    }
                    drawRuns(c, runs, x + glitch, y) { p, _ ->
                        p.color = Color.rgb(255, 38, 191)
                        p.alpha = (alpha * 0.6f).roundToInt()
                    }
                }
                drawLine(c, look, runs, x, y, size, alpha, shadowLayer)
            }
        }
        if (parts == Parts.All) drawSubtitle(look, g, c, baselineOf(count - 1), e, alpha, subtitleAlpha)
        c.restoreToCount(save)
    }

    private fun withAlpha(
        color: Int,
        a: Int,
    ) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    private fun drawSubtitle(
        look: TitleLook,
        g: Geometry,
        c: Canvas,
        lastBaseline: Float,
        e: Entrance,
        alpha: Int,
        extra: Float,
    ) {
        val sub = look.subtitle ?: return
        val p =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = TitleFonts.resolve(context, look.fontName, "medium")
                textSize = g.fontSize * 0.5f
                color = withAlpha(look.fill.toArgb(), (217 * (alpha / 255f) * extra).roundToInt())
            }
        val w = p.measureText(sub)
        c.drawText(sub, g.cx - w / 2 + e.dx, lastBaseline + g.fontSize * 0.9f, p)
    }

    private fun drawCard(
        look: TitleLook,
        c: Canvas,
        alpha: Int,
    ) {
        val corner = (look.cardCorner ?: 0.0).toFloat() * min(canvasW, canvasH)
        val col = (look.box ?: Rgba(0.09, 0.09, 0.12)).toArgb()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(col, (Color.alpha(col) * (alpha / 255f)).roundToInt()) }
        c.drawRoundRect(0f, 0f, canvasW.toFloat(), canvasH.toFloat(), corner, corner, p)
    }

    private fun drawHighlight(
        t: Title,
        look: TitleLook,
        c: Canvas,
        time: Double,
        lines: List<List<Run>>,
        baselineOf: (Int) -> Float,
        g: Geometry,
        e: Entrance,
    ) {
        val rects = ArrayList<RectF>()
        val ink =
            lines.mapIndexedNotNull { i, runs ->
                if (runs.all { it.text.isBlank() }) return@mapIndexedNotNull null
                val w = lineWidth(runs)
                val (top, bottom) = inkBand(runs)
                val base = baselineOf(i)
                RectF(g.cx - w / 2 + e.dx, base + top, g.cx + w / 2 + e.dx, base + bottom)
            }
        if (ink.isEmpty()) return
        val sweep = ((time - t.start - 0.15) / min(2.6, max(0.9, 0.55 * ink.size))).coerceIn(0.0, 1.0).toFloat()
        if (sweep <= 0f) return
        val n = ink.size

        fun share(i: Int) = (sweep * n - i).coerceIn(0f, 1f)
        when (look.entrance) {
            "Marker Highlight" ->
                ink.forEachIndexed { i, l ->
                    val lp = share(i)
                    if (lp > 0.001f) {
                        val pad = l.height() * 0.35f
                        val w = (l.width() + pad * 2) * lp
                        val y = l.top - l.height() * 0.12f
                        rects += RectF(l.left - pad, y, l.left - pad + w, y + l.height() * 1.24f)
                    }
                }
            "Underline Draw" ->
                ink.forEachIndexed { i, l ->
                    val lp = share(i)
                    if (lp > 0.001f) {
                        val h = max(2.5f, l.height() * 0.10f)
                        rects += RectF(l.left, l.bottom + 2, l.left + l.width() * lp, l.bottom + 2 + h)
                    }
                }
            else -> {
                val u = RectF(ink[0])
                ink.drop(1).forEach { u.union(it) }
                val pad = u.height() * (if (ink.size > 1) 0.12f else 0.28f)
                val box = RectF(u.left - pad, u.top - pad, u.right + pad, u.bottom + pad)
                val th = max(2.5f, ink[0].height() * 0.09f)
                val seg = sweep * 4

                fun amt(i: Int) = (seg - i).coerceIn(0f, 1f)
                if (amt(0) > 0.001f) rects += RectF(box.left, box.top, box.left + box.width() * amt(0), box.top + th)
                if (amt(1) > 0.001f) rects += RectF(box.right - th, box.top, box.right, box.top + box.height() * amt(1))
                if (amt(2) > 0.001f) rects += RectF(box.right - box.width() * amt(2), box.bottom - th, box.right, box.bottom)
                if (amt(3) > 0.001f) rects += RectF(box.left, box.bottom - box.height() * amt(3), box.left + th, box.bottom)
            }
        }
        val col = (look.neonColor ?: Rgba.HighlightGold).toArgb()
        val marker = look.entrance == "Marker Highlight"
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(col, if (marker) 209 else 255) }
        rects.forEach { r ->
            val rad = min(r.height(), r.width()) * (if (marker) 0.32f else 0.5f)
            c.drawRoundRect(r, rad, rad, p)
        }
    }

    private fun drawBadge(
        t: Title,
        look: TitleLook,
        c: Canvas,
        time: Double,
    ) {
        val life = (time - t.start).toFloat()
        val progress = (life / 0.5f).coerceIn(0f, 1f)
        var scale = 1f
        var dx = 0f
        var dy = 0f
        var alpha = min(1f, progress * 2)
        when (look.entrance) {
            "bounce", "pop", "scaleIn" -> {
                val p = progress - 1
                scale = 1 + 2.7f * p * p * p + 1.7f * p * p
            }
            "slideUp" -> dy = (1 - progress) * canvasH * 0.1f
            "slideDown" -> dy = -(1 - progress) * canvasH * 0.1f
            "slideLeft" -> dx = (1 - progress) * canvasW * 0.15f
            "slideRight" -> dx = -(1 - progress) * canvasW * 0.15f
            "dropIn" -> dy = -(1 - progress) * canvasH * 0.2f
            "shake" -> dx = (1 - progress) * sin(progress * 42) * canvasW * 0.02f
            "none" -> alpha = 1f
            else -> alpha = progress
        }
        var rotation = 0f
        var wiggle = 0f
        when (look.badge) {
            "follow" -> if (progress >= 1) rotation = sin(life * 1.6f) * 0.02f
            "like" ->
                if (progress >= 1) {
                    val beat = life % 1.8f
                    if (beat < 0.5f) scale *= 1 + 0.08f * sin(beat / 0.5f * PI.toFloat())
                }
            "bell" ->
                if (progress >= 1) {
                    val ring = life % 2.5f
                    if (ring < 0.7f) wiggle = sin(ring * 24) * 0.24f * (1 - ring / 0.7f)
                }
            else ->
                if (progress >= 1) {
                    val pulse = life % 3f
                    if (pulse < 0.4f) scale *= 1 + 0.05f * sin(pulse / 0.4f * PI.toFloat())
                }
        }
        val fs = look.fontSize.toFloat() * canvasScale
        val text = t.text.uppercase()
        val tp =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SANS_SERIF, 800, false)
                textSize = fs
                color = look.fill.toArgb()
            }
        val tw = tp.measureText(text)
        val iconSide = fs * 1.05f
        val gap = fs * 0.35f
        val padX = fs * 0.6f
        val pillW = padX * 2 + iconSide + gap + tw
        val pillH = fs * 2f
        val cx = (canvasW * look.positionX.toFloat()).coerceIn(pillW / 2 + 4, max(pillW / 2 + 4, canvasW - pillW / 2 - 4))
        val cy = (canvasH * look.positionY.toFloat()).coerceIn(pillH / 2 + 4, max(pillH / 2 + 4, canvasH - pillH / 2 - 4))
        val a = (alpha * 255).roundToInt()
        val save = c.save()
        c.translate(cx + dx, cy + dy)
        c.scale(scale, scale)
        c.rotate(Math.toDegrees(rotation.toDouble()).toFloat())
        c.translate(-cx, -cy)
        val pill = RectF(cx - pillW / 2, cy - pillH / 2, cx + pillW / 2, cy + pillH / 2)
        val pp =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = withAlpha((look.box ?: Rgba(0.90, 0.20, 0.20)).toArgb(), a)
                setShadowLayer(pillH * 0.18f, 0f, pillH * 0.06f, Color.argb((102 * alpha).roundToInt(), 0, 0, 0))
            }
        c.drawRoundRect(pill, pillH / 2, pillH / 2, pp)
        val ic = c.save()
        c.translate(pill.left + padX + iconSide / 2, cy)
        if (wiggle != 0f) c.rotate(Math.toDegrees(wiggle.toDouble()).toFloat())
        BadgeIcons.draw(
            look.badge,
            c,
            iconSide,
            withAlpha(look.fill.toArgb(), a),
            withAlpha((look.box ?: Rgba(0.90, 0.20, 0.20)).toArgb(), a),
        )
        c.restoreToCount(ic)
        tp.alpha = a
        val bounds = Rect()
        tp.getTextBounds("H", 0, 1, bounds)
        c.drawText(text, pill.left + padX + iconSide + gap, cy + bounds.height() / 2f, tp)
        c.restoreToCount(save)
    }

    /** The settled text alone as a picture, with its letter layout, for the motion compositor. */
    private fun bake(t: Title): Baked? {
        val key = "${t.id}|${t.text}|${t.extras}"
        bakes[key]?.let { return it }
        if (t.text.isBlank()) return null
        val look = TitleLook(t)
        val g = geometry(t)
        val fs = g.fontSize
        val pitch = fs * g.leading
        val attrs = g.lines.indices.map { runs(g, it, g.lines[it], fs) }
        val widest = attrs.maxOf { lineWidth(it) }
        val pad =
            ceil(
                fs * 0.9f + (if (look.stroke != null) fs * look.strokeWidth.toFloat() / 50 else 0f) + (
                    look.extrudeDepth?.let {
                        fs * it.toFloat() / 100
                    } ?: 0f
                ),
            )
        val left = g.cx - widest / 2 - pad
        val top = g.baseline(0, g.lines.size, pitch) - fs * 1.1f - pad
        val w = ceil(widest + 2 * pad).toInt().coerceAtLeast(1)
        val h = ceil(fs * 1.5f + pitch * (g.lines.size - 1) + 2 * pad).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.translate(-left, -top)
        drawBody(t, look, c, t.start + t.duration, Parts.Text, settled = true)
        val units = ArrayList<UnitInfo>()
        val paths = ArrayList<Path?>()
        var word = 0
        attrs.forEachIndexed { li, runs ->
            val base = g.baseline(li, g.lines.size, pitch) - top
            var x = g.cx - lineWidth(runs) / 2 - left
            val (inkTop, inkBottom) = inkBand(runs)
            for (run in runs) {
                val it = BreakIterator.getCharacterInstance()
                it.setText(run.text)
                var s = it.first()
                var e = it.next()
                while (e != BreakIterator.DONE) {
                    val piece = run.text.substring(s, e)
                    if (piece.isBlank()) {
                        word++
                    } else {
                        val before = run.paint.measureText(run.text, 0, s)
                        val after = run.paint.measureText(run.text, 0, e)
                        units +=
                            UnitInfo(
                                UnitRect(
                                    (x + min(before, after)).toDouble(),
                                    (base + inkTop).toDouble(),
                                    abs(after - before).toDouble(),
                                    (inkBottom - inkTop).toDouble(),
                                ),
                                word,
                                li,
                                piece,
                            )
                        val path = Path()
                        run.paint.getTextPath(run.text, s, e, x + before, base, path)
                        paths += if (path.isEmpty) null else path
                    }
                    s = e
                    e = it.next()
                }
                x += run.paint.measureText(run.text)
            }
            word = 0
        }
        val ink = (look.gradient ?: look.fill).let { com.zellahq.core.motion.MotionRGB(it.r, it.g, it.b) }
        val made = Baked(TitleMotionBake(bitmap, units, fs, paths, inkColor = ink), left, top)
        bakes[key] = made
        return made
    }

    /** The letter layout a title animates by, for tests and the picker. */
    fun unitsOf(t: Title): List<UnitInfo> = bake(t)?.bake?.units.orEmpty()

    /** Clears a picture to transparent. */
    fun clear(c: Canvas) = c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

    private companion object {
        const val KERN = 0.015f
        const val FAT_OUTLINE = 4f

        @Suppress("unused")
        val CLEAR = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
}

/** The four badge icons, drawn from shapes around the origin in the fill colour. */
internal object BadgeIcons {
    fun draw(
        badge: String?,
        c: Canvas,
        side: Float,
        fill: Int,
        back: Int,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        val s = side / 2
        when (badge) {
            "follow" -> {
                c.drawCircle(-s * 0.15f, -s * 0.38f, s * 0.38f, p)
                c.drawRoundRect(-s * 0.8f, s * 0.08f, s * 0.5f, s * 0.8f, s * 0.35f, s * 0.35f, p)
                c.drawRect(s * 0.5f, -s * 0.5f, s * 0.68f, s * 0.1f, p)
                c.drawRect(s * 0.3f, -s * 0.29f, s * 0.88f, -s * 0.11f, p)
            }
            "like" -> {
                c.drawRoundRect(-s * 0.85f, -s * 0.1f, -s * 0.4f, s * 0.8f, s * 0.1f, s * 0.1f, p)
                val hand =
                    Path().apply {
                        moveTo(-s * 0.3f, -s * 0.1f)
                        lineTo(s * 0.05f, -s * 0.85f)
                        quadTo(s * 0.32f, -s * 0.8f, s * 0.25f, -s * 0.45f)
                        lineTo(s * 0.2f, -s * 0.15f)
                        lineTo(s * 0.72f, -s * 0.15f)
                        quadTo(s * 0.92f, -s * 0.12f, s * 0.82f, s * 0.15f)
                        lineTo(s * 0.62f, s * 0.68f)
                        quadTo(s * 0.55f, s * 0.8f, s * 0.42f, s * 0.8f)
                        lineTo(-s * 0.3f, s * 0.8f)
                        close()
                    }
                c.drawPath(hand, p)
            }
            "bell" -> {
                val bell =
                    Path().apply {
                        moveTo(0f, -s * 0.85f)
                        cubicTo(s * 0.55f, -s * 0.85f, s * 0.6f, -s * 0.3f, s * 0.6f, 0f)
                        cubicTo(s * 0.6f, s * 0.3f, s * 0.85f, s * 0.45f, s * 0.85f, s * 0.55f)
                        lineTo(-s * 0.85f, s * 0.55f)
                        cubicTo(-s * 0.85f, s * 0.45f, -s * 0.6f, s * 0.3f, -s * 0.6f, 0f)
                        cubicTo(-s * 0.6f, -s * 0.3f, -s * 0.55f, -s * 0.85f, 0f, -s * 0.85f)
                        close()
                    }
                c.drawPath(bell, p)
                c.drawCircle(0f, s * 0.72f, s * 0.2f, p)
            }
            else -> {
                c.drawRoundRect(-s * 0.9f, -s * 0.62f, s * 0.9f, s * 0.62f, s * 0.22f, s * 0.22f, p)
                val play =
                    Path().apply {
                        moveTo(-s * 0.28f, -s * 0.34f)
                        lineTo(s * 0.4f, 0f)
                        lineTo(-s * 0.28f, s * 0.34f)
                        close()
                    }
                c.drawPath(play, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = back })
            }
        }
    }
}

/** Titles as a video overlay, the same in preview and export. Titles keep the order they were added in. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TitlesOverlay(
    context: Context,
    titles: List<Title>,
    width: Int,
    height: Int,
) : androidx.media3.effect.CanvasOverlay(false) {
    private val renderer = TitleRenderer(context, width, height)
    private val ordered = titles.filterNot { TitleLook(it).behindSubject }

    init {
        setCanvasSize(width, height)
    }

    override fun onDraw(
        canvas: Canvas,
        presentationTimeUs: Long,
    ) {
        val t = presentationTimeUs / 1e6
        renderer.clear(canvas)
        ordered.forEach { renderer.draw(it, canvas, t) }
    }
}
