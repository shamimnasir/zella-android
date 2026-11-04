@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.cutout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One ready-made background, with the iPhone's id, which is what a project stores. */
data class BackdropPreset(val id: String, val title: String, val animated: Boolean)

/**
 * The iPhone's procedural backgrounds (BackgroundCatalog.swift and BackgroundPreset), painted with
 * Android's canvas. No image files, no network. Their positions are y-up, as in Core Image, so a
 * point is flipped once in [spot]; everything else reads like the Swift it came from.
 */
object BackgroundPainter {
    private data class C(val r: Double, val g: Double, val b: Double, val a: Double = 1.0) {
        fun argb(alpha: Double = a) =
            Color.argb(
                (alpha * 255).toInt().coerceIn(0, 255),
                (r * 255).toInt().coerceIn(0, 255),
                (g * 255).toInt().coerceIn(0, 255),
                (b * 255).toInt().coerceIn(0, 255),
            )
    }

    val images =
        listOf(
            "img.studio.ivory" to "Ivory", "img.studio.slate" to "Slate", "img.studio.navy" to "Navy", "img.studio.charcoal" to "Charcoal",
            "img.studio.blush" to "Blush", "img.studio.sage" to "Sage", "img.bokeh.dark" to "Dark Bokeh", "img.bokeh.warm" to "Warm Bokeh",
            "img.bokeh.cool" to "Cool Bokeh", "img.mesh.twilight" to "Twilight", "img.mesh.sunrise" to "Sunrise", "img.mesh.deepsea" to "Deep Sea",
            "img.mesh.grape" to "Grape", "img.scenic.sunset" to "Sunset", "img.scenic.sky" to "Sky", "img.scenic.nature" to "Nature",
            "img.scenic.dusk" to "Dusk", "img.tex.office" to "Office", "img.tex.brick" to "Brick", "img.tex.stage" to "Stage",
        ).map { BackdropPreset(it.first, it.second, false) }

    val videos =
        listOf(
            "vid.flow.aurora" to "Aurora Flow", "vid.flow.ocean" to "Ocean Flow", "vid.flow.ember" to "Ember Flow", "vid.flow.candy" to "Candy Flow",
            "vid.flow.mono" to "Mono Flow", "vid.bokeh.mixed" to "Bokeh", "vid.bokeh.warm" to "Warm Bokeh", "vid.bokeh.cool" to "Cool Bokeh",
            "vid.bokeh.gold" to "Gold Bokeh", "vid.neon.violet" to "Neon Violet", "vid.neon.magenta" to "Neon Magenta", "vid.neon.lime" to "Neon Lime",
            "vid.aurora.emerald" to "Emerald", "vid.aurora.azure" to "Azure", "vid.aurora.rose" to "Rose", "vid.mesh.twilight" to "Twilight",
            "vid.mesh.sunrise" to "Sunrise", "vid.mesh.deepsea" to "Deep Sea", "vid.orbit.warm" to "Spotlight", "vid.orbit.cool" to "Cool Spot",
        ).map { BackdropPreset(it.first, it.second, true) }

    /** The older ten, which projects from the iPhone may still name. */
    private val lean =
        mapOf(
            "charcoal" to Triple("solid", "#2B2E33", "#2B2E33"),
            "slate" to Triple("solid", "#3A4A63", "#3A4A63"),
            "sand" to Triple("solid", "#C9A87C", "#C9A87C"),
            "forest" to Triple("solid", "#2E4034", "#2E4034"),
            "plum" to Triple("solid", "#4A2E42", "#4A2E42"),
            "sunset" to Triple("gradient", "#FF7E5F", "#FEB47B"),
            "ocean" to Triple("gradient", "#2193B0", "#6DD5ED"),
            "aurora" to Triple("gradient", "#7F00FF", "#00C9A7"),
            "bokehWarm" to Triple("bokeh", "#3A2A1A", "#FFC98B"),
            "bokehCool" to Triple("bokeh", "#141F2E", "#7FB8FF"),
        )

    fun isAnimated(id: String) = id.startsWith("vid.")

    private fun rgb(
        r: Double,
        g: Double,
        b: Double,
        a: Double = 1.0,
    ) = C(r, g, b, a)

    private class Painter(val canvas: Canvas, val w: Float, val h: Float) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        private fun fill(s: Shader?) {
            paint.shader = s
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        fun solid(color: Int) {
            paint.color = color
            canvas.drawRect(0f, 0f, w, h, paint)
        }

        fun linear(
            top: C,
            bot: C,
        ) = fill(LinearGradient(0f, 0f, 0f, h, top.argb(), bot.argb(), Shader.TileMode.CLAMP))

        fun diag(
            tl: C,
            br: C,
        ) = fill(LinearGradient(0f, 0f, w, h, tl.argb(), br.argb(), Shader.TileMode.CLAMP))

        /** Core Image's cy is measured from the bottom. */
        fun spot(
            cx: Double,
            cy: Double,
            r: Double,
            c: C,
        ) {
            val radius = (min(w, h) * r).toFloat().coerceAtLeast(1f)
            fill(RadialGradient(w * cx.toFloat(), h * (1 - cy.toFloat()), radius, c.argb(), c.argb(0.0), Shader.TileMode.CLAMP))
        }

        fun vignette(opacity: Double) {
            val inner = min(w, h) * 0.35f
            val outer = max(w, h) * 0.75f
            val stops = floatArrayOf(inner / outer, 1f)
            fill(
                RadialGradient(
                    w / 2,
                    h / 2,
                    outer,
                    intArrayOf(rgb(0.0, 0.0, 0.0).argb(0.0), rgb(0.0, 0.0, 0.0).argb(opacity)),
                    stops,
                    Shader.TileMode.CLAMP,
                ),
            )
        }

        fun fadeFromTop(
            c: C,
            fraction: Double,
        ) = fill(LinearGradient(0f, 0f, 0f, h * fraction.toFloat(), c.argb(), c.argb(0.0), Shader.TileMode.CLAMP))

        fun blinds(
            lines: Int,
            opacity: Double,
        ) {
            val step = h / lines
            paint.color = rgb(1.0, 1.0, 1.0).argb(opacity)
            var y = 0f
            while (y < h) {
                canvas.drawRect(0f, y, w, y + step * 0.4f, paint)
                y += step * 0.8f
            }
        }

        fun leftEdge(opacity: Double) =
            fill(
                LinearGradient(
                    0f,
                    0f,
                    w * 0.35f,
                    0f,
                    rgb(1.0, 1.0, 1.0).argb(opacity),
                    rgb(1.0, 1.0, 1.0).argb(0.0),
                    Shader.TileMode.CLAMP,
                ),
            )
    }

    /** Rotates a colour's hue by [angle] radians, the same matrix Core Image's hue filter uses. */
    private fun hue(
        c: C,
        angle: Double,
    ): C {
        val cosA = cos(angle)
        val sinA = sin(angle)
        val r = c.r * (0.299 + 0.701 * cosA + 0.168 * sinA) + c.g * (0.587 - 0.587 * cosA + 0.330 * sinA) + c.b * (0.114 - 0.114 * cosA - 0.497 * sinA)
        val g = c.r * (0.299 - 0.299 * cosA - 0.328 * sinA) + c.g * (0.587 + 0.413 * cosA + 0.035 * sinA) + c.b * (0.114 - 0.114 * cosA + 0.292 * sinA)
        val b = c.r * (0.299 - 0.300 * cosA + 1.250 * sinA) + c.g * (0.587 - 0.588 * cosA - 1.050 * sinA) + c.b * (0.114 + 0.886 * cosA - 0.203 * sinA)
        return C(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0), c.a)
    }

    private fun Painter.flow(
        tl: C,
        br: C,
        t: Double,
    ) {
        diag(hue(tl, t * 0.5), hue(br, t * 0.5))
        vignette(0.30)
    }

    private fun Painter.neon(
        a: C,
        b: C,
        t: Double,
    ) {
        linear(rgb(0.04, 0.03, 0.08), rgb(0.01, 0.01, 0.04))
        spot(0.28, 0.70, 0.5, a.copy(a = 0.35 + 0.25 * sin(t * 2.0)))
        spot(0.78, 0.35, 0.5, b.copy(a = 0.35 + 0.25 * sin(t * 2.0 + 2.5)))
        vignette(0.40)
    }

    private fun Painter.bokeh(
        t: Double,
        animated: Boolean,
        top: C,
        bot: C,
        palette: List<C>,
    ) {
        linear(top, bot)
        val spots =
            listOf(
                doubleArrayOf(0.25, 0.65, 0.18, 0.26, 0.04, 0.50, 0.0),
                doubleArrayOf(0.72, 0.72, 0.15, 0.30, 0.05, 0.40, 1.7),
                doubleArrayOf(0.55, 0.28, 0.20, 0.20, 0.06, 0.30, 3.1),
                doubleArrayOf(0.85, 0.40, 0.13, 0.24, 0.04, 0.60, 0.8),
                doubleArrayOf(0.18, 0.30, 0.12, 0.22, 0.05, 0.45, 2.2),
                doubleArrayOf(0.45, 0.85, 0.11, 0.20, 0.05, 0.50, 4.0),
            )
        spots.forEachIndexed { i, s ->
            val cx = if (animated) s[0] + sin(t * s[5] + s[6]) * s[4] else s[0]
            val cy = if (animated) s[1] + cos(t * s[5] * 0.8 + s[6]) * (s[4] * 0.8) else s[1]
            val op = if (animated) s[3] * (0.8 + 0.2 * sin(t * 1.3 + s[6])) else s[3]
            spot(cx, cy, s[2], palette[i % palette.size].copy(a = op))
        }
        vignette(0.45)
    }

    private fun Painter.aurora(
        t: Double,
        colors: List<C>,
    ) {
        linear(rgb(0.02, 0.04, 0.10), rgb(0.01, 0.02, 0.06))
        colors.forEachIndexed { i, c ->
            val phase = i * 2.0
            spot(0.5 + sin(t * 0.30 + phase) * 0.35, 0.6 + cos(t * 0.22 + phase) * 0.18, 0.5, c)
        }
        vignette(0.35)
    }

    private fun Painter.mesh(
        t: Double,
        animated: Boolean,
        base: C,
        c1: C,
        c2: C,
    ) {
        diag(base, rgb(base.r * 0.45, base.g * 0.45, base.b * 0.45))
        val a = if (animated) 1.0 else 0.0
        spot(0.30 + sin(t * 0.25) * a * 0.12, 0.40 + cos(t * 0.20) * a * 0.10, 0.60, c1)
        spot(0.72 + sin(t * 0.22 + 2.0) * a * 0.12, 0.66 + cos(t * 0.18 + 1.0) * a * 0.10, 0.55, c2)
        vignette(0.32)
    }

    private fun Painter.orbit(
        t: Double,
        b1: C,
        b2: C,
        s: C,
    ) {
        diag(b1, b2)
        spot(0.5 + sin(t * 0.40) * 0.28, 0.5 + cos(t * 0.40) * 0.22, 0.50, s)
        vignette(0.42)
    }

    private fun Painter.studio(
        top: C,
        bot: C,
        glow: Double,
        vig: Double,
    ) {
        linear(top, bot)
        spot(0.5, 0.55, 0.50, rgb(1.0, 1.0, 1.0, glow))
        vignette(vig)
    }

    /** Paints a preset. False for an id this build does not know. */
    fun paint(
        id: String,
        canvas: Canvas,
        width: Int,
        height: Int,
        t: Double,
    ): Boolean {
        val p = Painter(canvas, width.toFloat(), height.toFloat())
        when (id) {
            "vid.flow.aurora" -> p.flow(rgb(0.20, 0.10, 0.55), rgb(0.08, 0.32, 0.62), t)
            "vid.flow.ocean" -> p.flow(rgb(0.05, 0.25, 0.55), rgb(0.10, 0.45, 0.55), t)
            "vid.flow.ember" -> p.flow(rgb(0.55, 0.15, 0.10), rgb(0.30, 0.10, 0.25), t)
            "vid.flow.candy" -> p.flow(rgb(0.55, 0.15, 0.45), rgb(0.30, 0.15, 0.60), t)
            "vid.flow.mono" -> p.flow(rgb(0.25, 0.27, 0.32), rgb(0.10, 0.11, 0.14), t)
            "vid.bokeh.warm" ->
                p.bokeh(
                    t,
                    true,
                    rgb(0.08, 0.05, 0.03),
                    rgb(0.03, 0.02, 0.01),
                    listOf(rgb(1.0, 0.72, 0.34), rgb(1.0, 0.55, 0.25)),
                )
            "vid.bokeh.cool" ->
                p.bokeh(
                    t,
                    true,
                    rgb(0.02, 0.05, 0.12),
                    rgb(0.01, 0.02, 0.06),
                    listOf(rgb(0.40, 0.70, 1.0), rgb(0.30, 0.90, 0.90)),
                )
            "vid.bokeh.mixed" ->
                p.bokeh(
                    t,
                    true,
                    rgb(0.05, 0.05, 0.13),
                    rgb(0.02, 0.02, 0.08),
                    listOf(rgb(1.0, 0.82, 0.45), rgb(0.35, 0.55, 1.0)),
                )
            "vid.bokeh.gold" ->
                p.bokeh(
                    t,
                    true,
                    rgb(0.08, 0.06, 0.02),
                    rgb(0.03, 0.02, 0.00),
                    listOf(rgb(1.0, 0.84, 0.40), rgb(1.0, 0.70, 0.25)),
                )
            "vid.neon.violet" -> p.neon(rgb(0.65, 0.20, 1.0), rgb(0.10, 0.85, 1.0), t)
            "vid.neon.magenta" -> p.neon(rgb(1.0, 0.20, 0.65), rgb(0.30, 0.40, 1.0), t)
            "vid.neon.lime" -> p.neon(rgb(0.40, 1.0, 0.40), rgb(0.10, 0.80, 0.85), t)
            "vid.aurora.emerald" -> p.aurora(t, listOf(rgb(0.20, 0.95, 0.55, 0.28), rgb(0.30, 0.55, 1.0, 0.24), rgb(0.75, 0.30, 1.0, 0.20)))
            "vid.aurora.azure" -> p.aurora(t, listOf(rgb(0.20, 0.60, 1.0, 0.28), rgb(0.20, 0.90, 0.90, 0.24), rgb(0.50, 0.40, 1.0, 0.20)))
            "vid.aurora.rose" -> p.aurora(t, listOf(rgb(1.0, 0.40, 0.60, 0.28), rgb(1.0, 0.50, 0.30, 0.24), rgb(0.80, 0.30, 0.70, 0.20)))
            "vid.mesh.twilight" -> p.mesh(t, true, rgb(0.10, 0.08, 0.22), rgb(0.40, 0.20, 0.80, 0.45), rgb(0.20, 0.40, 0.90, 0.40))
            "vid.mesh.sunrise" -> p.mesh(t, true, rgb(0.20, 0.10, 0.10), rgb(1.0, 0.60, 0.30, 0.45), rgb(1.0, 0.40, 0.50, 0.40))
            "vid.mesh.deepsea" -> p.mesh(t, true, rgb(0.02, 0.10, 0.16), rgb(0.10, 0.50, 0.70, 0.45), rgb(0.20, 0.70, 0.60, 0.40))
            "vid.orbit.warm" -> p.orbit(t, rgb(0.10, 0.08, 0.06), rgb(0.03, 0.02, 0.02), rgb(1.0, 0.85, 0.50, 0.50))
            "vid.orbit.cool" -> p.orbit(t, rgb(0.05, 0.07, 0.12), rgb(0.02, 0.03, 0.06), rgb(0.50, 0.80, 1.0, 0.50))
            "img.studio.ivory" -> p.studio(rgb(0.94, 0.91, 0.86), rgb(0.76, 0.72, 0.66), 0.22, 0.28)
            "img.studio.slate" -> p.studio(rgb(0.42, 0.44, 0.48), rgb(0.22, 0.24, 0.28), 0.16, 0.38)
            "img.studio.navy" -> p.studio(rgb(0.12, 0.17, 0.34), rgb(0.03, 0.05, 0.16), 0.30, 0.45)
            "img.studio.charcoal" -> p.studio(rgb(0.22, 0.22, 0.25), rgb(0.08, 0.08, 0.10), 0.20, 0.45)
            "img.studio.blush" -> p.studio(rgb(0.90, 0.78, 0.80), rgb(0.65, 0.52, 0.57), 0.20, 0.30)
            "img.studio.sage" -> p.studio(rgb(0.62, 0.70, 0.58), rgb(0.34, 0.42, 0.32), 0.18, 0.35)
            "img.bokeh.dark" ->
                p.bokeh(
                    0.0,
                    false,
                    rgb(0.05, 0.05, 0.13),
                    rgb(0.02, 0.02, 0.08),
                    listOf(rgb(1.0, 0.82, 0.45), rgb(0.35, 0.55, 1.0)),
                )
            "img.bokeh.warm" ->
                p.bokeh(
                    0.0,
                    false,
                    rgb(0.10, 0.06, 0.04),
                    rgb(0.04, 0.02, 0.01),
                    listOf(rgb(1.0, 0.70, 0.30), rgb(1.0, 0.50, 0.20)),
                )
            "img.bokeh.cool" ->
                p.bokeh(
                    0.0,
                    false,
                    rgb(0.03, 0.06, 0.12),
                    rgb(0.01, 0.02, 0.06),
                    listOf(rgb(0.40, 0.70, 1.0), rgb(0.30, 0.90, 0.90)),
                )
            "img.mesh.twilight" -> p.mesh(0.0, false, rgb(0.10, 0.08, 0.22), rgb(0.40, 0.20, 0.80, 0.45), rgb(0.20, 0.40, 0.90, 0.40))
            "img.mesh.sunrise" -> p.mesh(0.0, false, rgb(0.20, 0.10, 0.10), rgb(1.0, 0.60, 0.30, 0.45), rgb(1.0, 0.40, 0.50, 0.40))
            "img.mesh.deepsea" -> p.mesh(0.0, false, rgb(0.02, 0.10, 0.16), rgb(0.10, 0.50, 0.70, 0.45), rgb(0.20, 0.70, 0.60, 0.40))
            "img.mesh.grape" -> p.mesh(0.0, false, rgb(0.14, 0.06, 0.18), rgb(0.60, 0.20, 0.70, 0.45), rgb(0.30, 0.20, 0.60, 0.40))
            "img.scenic.sunset" -> {
                p.fillThree(rgb(0.90, 0.45, 0.15), rgb(0.65, 0.20, 0.40), rgb(0.22, 0.08, 0.42))
                p.spot(0.5, 0.92, 0.22, rgb(1.0, 0.88, 0.35, 0.50))
                p.spot(0.5, 0.55, 0.40, rgb(1.0, 0.55, 0.20, 0.20))
                p.vignette(0.35)
            }
            "img.scenic.sky" -> {
                p.linear(rgb(0.38, 0.62, 0.92), rgb(0.62, 0.80, 0.98))
                p.spot(0.30, 0.70, 0.20, rgb(1.0, 1.0, 1.0, 0.25))
                p.spot(0.65, 0.78, 0.16, rgb(1.0, 1.0, 1.0, 0.22))
                p.spot(0.50, 0.55, 0.14, rgb(1.0, 1.0, 1.0, 0.18))
                p.vignette(0.25)
            }
            "img.scenic.nature" -> {
                p.linear(rgb(0.11, 0.29, 0.13), rgb(0.05, 0.16, 0.07))
                listOf(
                    doubleArrayOf(0.20, 0.80, 0.12, 0.22),
                    doubleArrayOf(0.65, 0.85, 0.16, 0.18),
                    doubleArrayOf(0.45, 0.45, 0.13, 0.16),
                    doubleArrayOf(0.80, 0.65, 0.09, 0.20),
                    doubleArrayOf(0.30, 0.20, 0.14, 0.18),
                    doubleArrayOf(0.60, 0.25, 0.11, 0.15),
                ).forEach { p.spot(it[0], it[1], it[2], rgb(0.50, 0.88, 0.30, it[3])) }
                p.fadeFromTop(rgb(0.45, 0.68, 0.90, 0.28), 0.35)
                p.vignette(0.40)
            }
            "img.scenic.dusk" -> p.mesh(0.0, false, rgb(0.12, 0.06, 0.20), rgb(0.90, 0.40, 0.20, 0.42), rgb(0.40, 0.20, 0.70, 0.42))
            "img.tex.office" -> {
                p.diag(rgb(0.25, 0.30, 0.43), rgb(0.12, 0.16, 0.28))
                p.blinds(18, 0.07)
                p.leftEdge(0.10)
                p.spot(0.5, 0.95, 0.30, rgb(0.80, 0.88, 1.0, 0.12))
                p.vignette(0.35)
            }
            "img.tex.brick" -> {
                p.diag(rgb(0.42, 0.22, 0.16), rgb(0.26, 0.12, 0.09))
                p.blinds(14, 0.16)
                p.spot(0.5, 0.55, 0.5, rgb(1.0, 0.6, 0.3, 0.12))
                p.vignette(0.45)
            }
            "img.tex.stage" -> {
                p.linear(rgb(0.30, 0.31, 0.34), rgb(0.14, 0.15, 0.17))
                p.spot(0.5, 0.62, 0.45, rgb(1.0, 1.0, 1.0, 0.30))
                p.fadeFromTop(rgb(0.0, 0.0, 0.0, 0.45), 0.30)
                p.vignette(0.40)
            }
            else -> {
                val l = lean[id] ?: return false
                val a = hex(l.second)
                val b = hex(l.third)
                when (l.first) {
                    "solid" -> p.solid(a.argb())
                    "gradient" -> p.diag(a, b)
                    else -> p.bokeh(0.0, false, a, rgb(a.r * 0.4, a.g * 0.4, a.b * 0.4), listOf(b))
                }
            }
        }
        return true
    }

    private fun Painter.fillThree(
        a: C,
        b: C,
        c: C,
    ) {
        canvas.save()
        canvas.clipRect(0f, 0f, w, h / 2)
        canvas.scale(1f, 0.5f)
        linear(a, b)
        canvas.restore()
        canvas.save()
        canvas.clipRect(0f, h / 2, w, h)
        canvas.translate(0f, h / 2)
        canvas.scale(1f, 0.5f)
        linear(b, c)
        canvas.restore()
    }

    private fun hex(s: String): C {
        val v = s.removePrefix("#").toIntOrNull(16) ?: 0
        return C((v shr 16 and 255) / 255.0, (v shr 8 and 255) / 255.0, (v and 255) / 255.0)
    }

    fun colorOf(hex: String?): FloatArray {
        val c = hex(hex ?: "#000000")
        return floatArrayOf(c.r.toFloat(), c.g.toFloat(), c.b.toFloat())
    }

    /** A small picture of a preset, for the picker. */
    fun swatch(
        id: String,
        w: Int = 96,
        h: Int = 96,
    ): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { paint(id, Canvas(it), w, h, 0.0) }
}
