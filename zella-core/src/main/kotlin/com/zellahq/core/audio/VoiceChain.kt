package com.zellahq.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Polish voice", the iPhone's AudioPolish chain in the same order: rumble cut, five tone bands, a
 * de-esser, a compressor, a peak limiter and a mild expander for the gaps between words. Runs live on
 * the clip's sound, so preview and export are the same and nothing is rendered ahead.
 */
class VoiceChain(
    rate: Int,
    channels: Int,
    voicing: Voicing = Voicing.Podcast,
    /** True when RNNoise runs in front of this. Its leftovers sit right under the presence band, so that band is held back. */
    denoised: Boolean = false,
    /** Boost towards the loudness target, ahead of the limiter so reaching the target never clips the voice. */
    makeupDb: Double = 0.0,
) {
    /** The iPhone's five voicings: rumble cut, chest, body, mud, presence, air, de-ess, compressor threshold, ratio. */
    enum class Voicing(
        val hpf: Double,
        val shelf: Double,
        val body: Double,
        val mud: Double,
        val pres: Double,
        val air: Double,
        val deess: Double,
        val compTh: Double,
        val ratio: Double,
    ) {
        Natural(80.0, 4.5, 2.5, -2.0, 3.0, 2.0, -3.0, -20.0, 2.5),
        Warm(72.0, 12.0, 6.0, -3.5, 3.0, 2.0, -3.0, -26.0, 4.0),
        Bright(110.0, 1.0, 1.5, -3.0, 6.5, 5.0, -6.0, -24.0, 3.5),
        Podcast(100.0, 3.0, 2.0, -3.0, 4.0, 2.5, -4.0, -28.0, 4.0),
        Cinematic(60.0, 13.0, 6.5, -3.5, 4.0, 3.5, -5.0, -30.0, 5.0),
        ;

        companion object {
            fun named(s: String?) = entries.firstOrNull { it.name == s } ?: Podcast
        }
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun step(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }

    private fun bands(): Array<Biquad> {
        val f = rate.toDouble()
        val pres = if (denoised) max(0.0, voicing.pres - 4.0) else voicing.pres
        val air = if (denoised) max(0.0, voicing.air - 4.0) else voicing.air
        return arrayOf(
            highPass(f, voicing.hpf),
            lowShelf(f, 105.0, voicing.shelf),
            peak(f, 190.0, voicing.body, 0.8),
            peak(f, 480.0, voicing.mud, 1.4),
            peak(f, 3500.0, pres, 1.0),
            highShelf(f, 9000.0, air),
            peak(f, 6500.0, voicing.deess, 3.0),
        )
    }

    private val rate = rate
    private val voicing = voicing
    private val denoised = denoised
    private val eq = Array(channels) { bands() }

    // Compressor, linked across channels.
    private val threshold = voicing.compTh
    private val ratio = voicing.ratio
    private val cAttack = exp(-1.0 / (0.008 * rate))
    private val cRelease = exp(-1.0 / (0.18 * rate))
    private var cEnv = 0.0

    // Limiter.
    private val preGain = 10.0.pow((3.0 + makeupDb) / 20)
    private val ceiling = 10.0.pow(-1.0 / 20)
    private val lAttack = exp(-1.0 / (0.002 * rate))
    private val lRelease = exp(-1.0 / (0.05 * rate))
    private var lGain = 1.0

    // Expander for the gaps between words.
    private val gAttack = exp(-1.0 / (0.006 * rate))
    private val gRelease = exp(-1.0 / (0.18 * rate))
    private val lpCoef = 1 - exp(-2 * PI * 160 / rate)
    private val bbEnv = DoubleArray(channels)
    private val lbEnv = DoubleArray(channels)
    private val lpState = DoubleArray(channels)

    private fun expand(
        level: Double,
        env: DoubleArray,
        c: Int,
    ): Double {
        env[c] = if (level > env[c]) gAttack * env[c] + (1 - gAttack) * level else gRelease * env[c] + (1 - gRelease) * level
        val db = 20 * log10(max(env[c], 1e-6))
        if (db >= GATE_DB) return 1.0
        return 10.0.pow(max(-GATE_RANGE, (db - GATE_DB) * (GATE_RATIO - 1)) / 20)
    }

    /** One frame in place: x[c] is channel c, -1 to 1. */
    fun frame(x: DoubleArray) {
        val ch = x.size
        var peakIn = 0.0
        for (c in 0 until ch) {
            var s = x[c]
            for (b in eq[c]) s = b.step(s)
            x[c] = s
            peakIn = max(peakIn, abs(s))
        }
        // Compress on the linked level, with a soft knee of 6 dB.
        cEnv = if (peakIn > cEnv) cAttack * cEnv + (1 - cAttack) * peakIn else cRelease * cEnv + (1 - cRelease) * peakIn
        val over = 20 * log10(max(cEnv, 1e-6)) - threshold
        val knee = 6.0
        val reduction =
            when {
                over <= -knee / 2 -> 0.0
                over >= knee / 2 -> over * (1 - 1 / ratio)
                else -> (1 - 1 / ratio) * (over + knee / 2).pow(2) / (2 * knee)
            }
        val cGain = 10.0.pow(-reduction / 20) * preGain
        var peakOut = 0.0
        for (c in 0 until ch) {
            x[c] *= cGain
            peakOut = max(peakOut, abs(x[c]))
        }
        // Limit: a look at the peak now, falling fast and recovering slowly.
        val want = if (peakOut > ceiling) ceiling / peakOut else 1.0
        lGain = if (want < lGain) lAttack * lGain + (1 - lAttack) * want else lRelease * lGain + (1 - lRelease) * want
        if (want < lGain) lGain = want
        for (c in 0 until ch) {
            val s = x[c] * lGain
            lpState[c] += lpCoef * (s - lpState[c])
            val lo = lpState[c]
            val hi = s - lo
            x[c] = (lo * expand(abs(lo), lbEnv, c) + hi) * expand(abs(s), bbEnv, c)
        }
    }

    /** A whole mono signal, for measuring what the chain will do. */
    fun processAll(mono: FloatArray): FloatArray {
        val one = DoubleArray(1)
        return FloatArray(mono.size) {
            one[0] = mono[it].toDouble()
            frame(one)
            one[0].toFloat()
        }
    }

    private companion object {
        const val GATE_DB = -44.0
        const val GATE_RATIO = 3.0
        const val GATE_RANGE = 18.0

        fun highPass(
            fs: Double,
            f: Double,
        ): Biquad {
            val w = 2 * PI * f / fs
            val alpha = sin(w) / (2 * 0.7071)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun peak(
            fs: Double,
            f: Double,
            gain: Double,
            q: Double,
        ): Biquad {
            val a = 10.0.pow(gain / 40)
            val w = 2 * PI * f / fs
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha / a
            return Biquad((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
        }

        fun lowShelf(
            fs: Double,
            f: Double,
            gain: Double,
        ): Biquad {
            val a = 10.0.pow(gain / 40)
            val w = 2 * PI * f / fs
            val c = cos(w)
            val alpha = sin(w) / (2 * 0.7071)
            val r = 2 * sqrt(a) * alpha
            val a0 = (a + 1) + (a - 1) * c + r
            return Biquad(
                a * ((a + 1) - (a - 1) * c + r) / a0,
                2 * a * ((a - 1) - (a + 1) * c) / a0,
                a * ((a + 1) - (a - 1) * c - r) / a0,
                -2 * ((a - 1) + (a + 1) * c) / a0,
                ((a + 1) + (a - 1) * c - r) / a0,
            )
        }

        fun highShelf(
            fs: Double,
            f: Double,
            gain: Double,
        ): Biquad {
            val a = 10.0.pow(gain / 40)
            val w = 2 * PI * min(f, fs * 0.45) / fs
            val c = cos(w)
            val alpha = sin(w) / (2 * 0.7071)
            val r = 2 * sqrt(a) * alpha
            val a0 = (a + 1) - (a - 1) * c + r
            return Biquad(
                a * ((a + 1) + (a - 1) * c + r) / a0,
                -2 * a * ((a - 1) + (a + 1) * c) / a0,
                a * ((a + 1) + (a - 1) * c - r) / a0,
                2 * ((a - 1) - (a + 1) * c) / a0,
                ((a + 1) - (a - 1) * c - r) / a0,
            )
        }
    }
}
