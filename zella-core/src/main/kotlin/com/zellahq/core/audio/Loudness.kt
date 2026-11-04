package com.zellahq.core.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Integrated loudness (LUFS) per ITU-R BS.1770-4 / EBU R128: K-weighting (a high shelf and a
 * high-pass, designed for any sample rate), 400 ms blocks with 75% overlap, an absolute gate at
 * -70 LUFS and a relative gate 10 LU below the ungated mean. Checked against EBU Tech 3341 signals.
 */
object Loudness {
    const val TARGET_LUFS = -14.0

    private class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    ) {
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

    // Stage 1: high shelf (+4 dB above about 1.5 kHz). Stage 2: RLB high-pass at about 38 Hz.
    private fun shelf(fs: Double): Biquad {
        val g = 3.999843853973347
        val q = 0.7071752369554196
        val fc = 1681.974450955533
        val k = tan(PI * fc / fs)
        val vh = 10.0.pow(g / 20)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1 + k / q + k * k
        return Biquad(
            (vh + vb * k / q + k * k) / a0,
            2 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0,
            2 * (k * k - 1) / a0,
            (1 - k / q + k * k) / a0,
        )
    }

    private fun highpass(fs: Double): Biquad {
        val q = 0.5003270373238773
        val fc = 38.13547087602444
        val k = tan(PI * fc / fs)
        val a0 = 1 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    }

    /**
     * The level of the quiet between words: the 10th percentile of 30 ms windows, leaving out digital
     * silence, which has no noise to lift. Very low when there is nothing but silence.
     */
    fun floorDb(
        mono: FloatArray,
        rate: Int = 48_000,
    ): Double {
        val window = (0.03 * rate).toInt()
        val levels = ArrayList<Double>()
        var i = 0
        while (i + window <= mono.size) {
            var s = 0.0
            for (k in i until i + window) s += mono[k] * mono[k]
            if (s > 0) levels += 10 * log10(s / window)
            i += window
        }
        if (levels.isEmpty()) return -120.0
        levels.sort()
        return levels[levels.size / 10]
    }

    /** [channels] are equal-length sample arrays, -1..1. Returns LUFS, or -inf for silence. */
    fun integrated(
        channels: List<FloatArray>,
        sampleRate: Int,
    ): Double {
        val n = channels.first().size
        val weighted =
            channels.map { ch ->
                val s = shelf(sampleRate.toDouble())
                val h = highpass(sampleRate.toDouble())
                DoubleArray(n) { i -> h.step(s.step(ch[i].toDouble())) }
            }
        val block = (0.4 * sampleRate).toInt()
        val hop = block / 4
        if (n < block) return Double.NEGATIVE_INFINITY
        val powers = ArrayList<Double>()
        var start = 0
        while (start + block <= n) {
            var z = 0.0
            for (ch in weighted) {
                var sum = 0.0
                for (i in start until start + block) sum += ch[i] * ch[i]
                z += sum / block // all channel weights are 1.0 for mono, left, right
            }
            powers += z
            start += hop
        }

        fun lufs(p: Double) = -0.691 + 10 * log10(p)
        val absGated = powers.filter { lufs(it) > -70.0 }
        if (absGated.isEmpty()) return Double.NEGATIVE_INFINITY
        val relGate = lufs(absGated.average()) - 10.0
        val gated = absGated.filter { lufs(it) > relGate }
        return lufs(gated.average())
    }

    /** Gain in dB that brings [lufs] to [target], never more than +12 dB (it would raise noise). */
    fun gainToTarget(
        lufs: Double,
        target: Double = TARGET_LUFS,
    ): Double = if (lufs.isInfinite()) 0.0 else (target - lufs).coerceAtMost(12.0)

    fun dbToLinear(db: Double) = 10.0.pow(db / 20)

    /** Root mean square in dBFS. */
    fun rmsDb(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return 20 * log10(sqrt(s / x.size).coerceAtLeast(1e-12))
    }
}
