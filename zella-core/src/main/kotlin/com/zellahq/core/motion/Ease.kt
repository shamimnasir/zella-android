package com.zellahq.core.motion

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Easing curves, the iPhone's ZellaMotion Ease. Every curve is exactly 0 at t = 0 and exactly 1 at
 * t = 1 (pulse2, for heartbeat loops, is 0 at both ends), so a phase lands exactly on rest.
 */
sealed class Ease {
    data object Linear : Ease()

    data object SineIn : Ease()

    data object SineOut : Ease()

    data object SineInOut : Ease()

    data object QuadIn : Ease()

    data object QuadOut : Ease()

    data object QuadInOut : Ease()

    data object CubicIn : Ease()

    data object CubicOut : Ease()

    data object CubicInOut : Ease()

    data object QuartIn : Ease()

    data object QuartOut : Ease()

    data object QuartInOut : Ease()

    data object QuintIn : Ease()

    data object QuintOut : Ease()

    data object QuintInOut : Ease()

    data object ExpoIn : Ease()

    data object ExpoOut : Ease()

    data object ExpoInOut : Ease()

    data class BackOut(val s: Double) : Ease()

    data class ElasticOut(val amplitude: Double, val period: Double) : Ease()

    data object BounceOut : Ease()

    data class Spring(val zeta: Double, val omega: Double) : Ease()

    data class Steps(val n: Int) : Ease()

    data object Pulse2 : Ease()

    operator fun invoke(tIn: Double): Double {
        if (this is Pulse2) return pulse2Value(tIn)
        if (!(tIn > 0)) return 0.0
        if (tIn >= 1) return 1.0
        val t = tIn
        return when (this) {
            Linear -> t
            SineIn -> 1 - cos(t * PI / 2)
            SineOut -> sin(t * PI / 2)
            SineInOut -> -(cos(PI * t) - 1) / 2
            QuadIn -> t * t
            QuadOut -> 1 - (1 - t) * (1 - t)
            QuadInOut -> if (t < 0.5) 2 * t * t else 1 - (-2 * t + 2).pow(2) / 2
            CubicIn -> t * t * t
            CubicOut -> 1 - (1 - t).pow(3)
            CubicInOut -> if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
            QuartIn -> t.pow(4)
            QuartOut -> 1 - (1 - t).pow(4)
            QuartInOut -> if (t < 0.5) 8 * t.pow(4) else 1 - (-2 * t + 2).pow(4) / 2
            QuintIn -> t.pow(5)
            QuintOut -> 1 - (1 - t).pow(5)
            QuintInOut -> if (t < 0.5) 16 * t.pow(5) else 1 - (-2 * t + 2).pow(5) / 2
            ExpoIn -> 2.0.pow(10 * t - 10)
            ExpoOut -> 1 - 2.0.pow(-10 * t)
            ExpoInOut -> if (t < 0.5) 2.0.pow(20 * t - 10) / 2 else (2 - 2.0.pow(-20 * t + 10)) / 2
            is BackOut -> {
                val u = t - 1
                1 + (s + 1) * u * u * u + s * u * u
            }
            is ElasticOut -> {
                val a = max(1.0, amplitude)
                val p = if (period > 0) period else 0.3
                val sh = p / (2 * PI) * asin(1 / a)
                a * 2.0.pow(-10 * t) * sin((t - sh) * 2 * PI / p) + 1
            }
            BounceOut -> bounce(t)
            is Spring -> {
                fun raw(x: Double): Double {
                    val z = min(max(zeta, 0.01), 0.99)
                    val wd = omega * sqrt(1 - z * z)
                    return 1 - exp(-z * omega * x) * (cos(wd * x) + (z * omega / wd) * sin(wd * x))
                }
                raw(t) + (1 - raw(1.0)) * t
            }
            is Steps -> {
                val k = max(1, n).toDouble()
                floor(t * k) / k
            }
            Pulse2 -> pulse2Value(t)
        }
    }

    companion object {
        val BackOutDefault = BackOut(1.70158)
        val ElasticOutDefault = ElasticOut(1.0, 0.3)

        private fun bounce(x: Double): Double {
            val n1 = 7.5625
            val d1 = 2.75
            if (x < 1 / d1) return n1 * x * x
            if (x < 2 / d1) {
                val y = x - 1.5 / d1
                return n1 * y * y + 0.75
            }
            if (x < 2.5 / d1) {
                val y = x - 2.25 / d1
                return n1 * y * y + 0.9375
            }
            val y = x - 2.625 / d1
            return n1 * y * y + 0.984375
        }

        fun pulse2Value(tIn: Double): Double {
            if (!tIn.isFinite()) return 0.0
            val t = tIn - floor(tIn)

            fun bump(
                a: Double,
                w: Double,
            ): Double {
                if (t <= a || t >= a + w) return 0.0
                val s = sin(PI * (t - a) / w)
                return s * s
            }
            return bump(0.0, 0.22) + 0.6 * bump(0.26, 0.22)
        }

        /** Names like cubicOut, backOut(1.7), elasticOut(1,0.3), spring(0.35,14), steps(3). Null for anything unknown. */
        fun named(raw: String): Ease? {
            val s = raw.trim()
            var base = s
            var args: List<Double> = emptyList()
            val open = s.indexOf('(')
            if (open >= 0 && s.endsWith(")")) {
                base = s.substring(0, open)
                args = s.substring(open + 1, s.length - 1).split(",").mapNotNull { it.trim().toDoubleOrNull() }
            }
            return when (base) {
                "linear" -> Linear
                "sineIn" -> SineIn
                "sineOut" -> SineOut
                "sineInOut" -> SineInOut
                "quadIn" -> QuadIn
                "quadOut" -> QuadOut
                "quadInOut" -> QuadInOut
                "cubicIn" -> CubicIn
                "cubicOut" -> CubicOut
                "cubicInOut" -> CubicInOut
                "quartIn" -> QuartIn
                "quartOut" -> QuartOut
                "quartInOut" -> QuartInOut
                "quintIn" -> QuintIn
                "quintOut" -> QuintOut
                "quintInOut" -> QuintInOut
                "expoIn" -> ExpoIn
                "expoOut" -> ExpoOut
                "expoInOut" -> ExpoInOut
                "backOut" -> BackOut(args.firstOrNull() ?: 1.70158)
                "elasticOut" -> ElasticOut(args.firstOrNull() ?: 1.0, args.getOrNull(1) ?: 0.3)
                "bounceOut" -> BounceOut
                "spring" -> Spring(args.firstOrNull() ?: 0.35, args.getOrNull(1) ?: 14.0)
                "steps" -> Steps((args.firstOrNull() ?: 1.0).toInt())
                "pulse2" -> Pulse2
                else -> null
            }
        }
    }
}
