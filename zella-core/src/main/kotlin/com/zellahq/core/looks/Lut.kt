package com.zellahq.core.looks

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A 3D colour lookup table: every look, an imported .cube file, and the colour board all become one
 * of these, so preview and export apply colour in one step. Values are sRGB 0..1; index = r + n*g + n*n*b.
 */
class Lut(val size: Int, val data: FloatArray) {
    init {
        require(data.size == size * size * size * 3)
    }

    companion object {
        fun identity(size: Int = 33) =
            Lut(
                size,
                FloatArray(size * size * size * 3).also { d ->
                    for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                        val i = (r + size * g + size * size * b) * 3
                        d[i] = r / (size - 1f)
                        d[i + 1] = g / (size - 1f)
                        d[i + 2] = b / (size - 1f)
                    }
                },
            )

        /** The iPhone-baked format: n^3 RGB bytes. */
        fun fromBytes(
            bytes: ByteArray,
            size: Int = 33,
        ) = Lut(size, FloatArray(bytes.size) { (bytes[it].toInt() and 255) / 255f })

        /** Parses an Adobe/Resolve .cube file (3D only). Null when it is not one. */
        fun fromCube(text: String): Lut? =
            runCatching {
                var n = 0
                val values = ArrayList<Float>()
                var min = floatArrayOf(0f, 0f, 0f)
                var max = floatArrayOf(1f, 1f, 1f)
                for (raw in text.lineSequence()) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE")) continue
                    val parts = line.split(Regex("\\s+"))
                    when (parts[0]) {
                        "LUT_3D_SIZE" -> n = parts[1].toInt()
                        "LUT_1D_SIZE" -> return null
                        "DOMAIN_MIN" -> min = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                        "DOMAIN_MAX" -> max = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                        else -> if (parts.size == 3) parts.forEach { values += it.toFloat() }
                    }
                }
                if (n < 2 || n > 129 || values.size != n * n * n * 3) return null
                // Values are already in .cube order: red fastest, then green, then blue.
                Lut(n, FloatArray(values.size) { i -> ((values[i] - min[i % 3]) / (max[i % 3] - min[i % 3])).coerceIn(0f, 1f) })
            }.getOrNull()
    }

    /** Trilinear sample. */
    fun apply(
        r: Float,
        g: Float,
        b: Float,
    ): FloatArray {
        val m = size - 1
        val x = r.coerceIn(0f, 1f) * m
        val y = g.coerceIn(0f, 1f) * m
        val z = b.coerceIn(0f, 1f) * m
        val x0 = x.toInt().coerceAtMost(m - 1)
        val y0 = y.toInt().coerceAtMost(m - 1)
        val z0 = z.toInt().coerceAtMost(m - 1)
        val fx = x - x0
        val fy = y - y0
        val fz = z - z0
        val out = FloatArray(3)
        for (c in 0 until 3) {
            fun v(
                i: Int,
                j: Int,
                k: Int,
            ) = data[((x0 + i) + size * (y0 + j) + size * size * (z0 + k)) * 3 + c]
            val c00 = v(0, 0, 0) * (1 - fx) + v(1, 0, 0) * fx
            val c10 = v(0, 1, 0) * (1 - fx) + v(1, 1, 0) * fx
            val c01 = v(0, 0, 1) * (1 - fx) + v(1, 0, 1) * fx
            val c11 = v(0, 1, 1) * (1 - fx) + v(1, 1, 1) * fx
            out[c] = (c00 * (1 - fy) + c10 * fy) * (1 - fz) + (c01 * (1 - fy) + c11 * fy) * fz
        }
        return out
    }

    /** This look at [t] strength: a blend toward the original colour, as the iPhone's slider does. */
    fun atIntensity(t: Float): Lut {
        if (t >= 0.999f) return this
        val id = identity(size)
        return Lut(size, FloatArray(data.size) { id.data[it] + (data[it] - id.data[it]) * t })
    }

    /** [then] applied after this one, at the same size. */
    fun then(next: (FloatArray) -> FloatArray): Lut {
        val d = data.copyOf()
        for (i in d.indices step 3) {
            val o = next(floatArrayOf(d[i], d[i + 1], d[i + 2]))
            d[i] = o[0]
            d[i + 1] = o[1]
            d[i + 2] = o[2]
        }
        return Lut(size, d)
    }

    fun then(next: Lut) = then { next.apply(it[0], it[1], it[2]) }
}

/** Lift, gamma, gain wheels, the iPhone's EffectChain.applyColorBoard maths, in sRGB values. */
data class ColorBoard(
    val liftR: Float = 0f,
    val liftG: Float = 0f,
    val liftB: Float = 0f,
    val liftL: Float = 0f,
    val gammaR: Float = 0f,
    val gammaG: Float = 0f,
    val gammaB: Float = 0f,
    val gammaL: Float = 0f,
    val gainR: Float = 0f,
    val gainG: Float = 0f,
    val gainB: Float = 0f,
    val gainL: Float = 0f,
) {
    val isIdentity get() = this == ColorBoard()

    fun apply(rgb: FloatArray): FloatArray {
        val lifts = floatArrayOf(liftR + liftL, liftG + liftL, liftB + liftL)
        val gains = floatArrayOf(1 + gainR, 1 + gainG, 1 + gainB)
        val gammas = floatArrayOf(gammaL + gammaR, gammaL + gammaG, gammaL + gammaB)
        return FloatArray(3) { c ->
            var x = rgb[c]
            val l = lifts[c]
            // Lift is a shadow control: zero at black and at white.
            if (l >= 0) {
                x += l * (1 - x)
            } else {
                x = x.coerceIn(0f, 1f)
                val k = maxOf(-0.9f, 6.75f * l)
                x += k * x * (1 - x) * (1 - x)
            }
            x *= gains[c] * (1 + gainL)
            x = x.coerceIn(0f, 1f).toDouble().pow(2.0.pow(-gammas[c] * 2.0)).toFloat()
            x.coerceIn(0f, 1f)
        }
    }
}

/** The iPhone's colour distance rules for the look catalogue (ColorMath in ColorPresets.swift). */
object ColorMath {
    const val LOOK_MIN_DELTA_E = 3.5
    val EXEMPT = setOf("Log Flat")
    val PROBES =
        listOf(
            floatArrayOf(0.86f, 0.68f, 0.58f), floatArrayOf(0.72f, 0.50f, 0.36f), floatArrayOf(0.42f, 0.27f, 0.20f),
            floatArrayOf(0.42f, 0.62f, 0.85f), floatArrayOf(0.32f, 0.52f, 0.26f), floatArrayOf(0.55f, 0.38f, 0.22f),
            floatArrayOf(0.18f, 0.18f, 0.20f), floatArrayOf(0.50f, 0.50f, 0.50f), floatArrayOf(0.94f, 0.94f, 0.94f),
        )

    /** sRGB to CIE Lab (D65), same constants as the iPhone. */
    fun lab(rgb: FloatArray): DoubleArray {
        fun lin(c: Float): Double {
            val v = c.coerceIn(0f, 1f).toDouble()
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(rgb[0])
        val g = lin(rgb[1])
        val b = lin(rgb[2])
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    /** CIE76. */
    fun deltaE(
        a: DoubleArray,
        b: DoubleArray,
    ) = sqrt((a[0] - b[0]).pow(2) + (a[1] - b[1]).pow(2) + (a[2] - b[2]).pow(2))

    /**
     * The probes the look gate measures: the six non-skin surfaces. Every look carries a skin guard
     * that keeps faces natural, so on skin the looks are meant to agree (the iPhone gate skips them too).
     */
    val GATE_PROBES = PROBES.drop(3)

    /** Mean ΔE between two looks over the gate probes. */
    fun distance(
        a: Lut,
        b: Lut,
        probes: List<FloatArray> = GATE_PROBES,
    ) = probes.map { p -> deltaE(lab(a.apply(p[0], p[1], p[2])), lab(b.apply(p[0], p[1], p[2]))) }.average()
}
