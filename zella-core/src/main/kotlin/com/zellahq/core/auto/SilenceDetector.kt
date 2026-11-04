package com.zellahq.core.auto

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A stretch of timeline, seconds. */
data class Span(val start: Double, val end: Double) {
    val length get() = end - start
}

/** One spoken word from the transcript, timeline seconds. */
data class SpokenWord(val text: String, val start: Double, val end: Double)

/**
 * Finds silent stretches by short-window energy, with the iPhone's numbers and the reasons they ended
 * up there (SilenceDetector.swift). The level that counts as silence comes from the recording itself.
 * The pads are lopsided: a word's first sound is quiet and needs more guard than its tail.
 */
class SilenceDetector(
    private val windowSeconds: Double = 0.03,
    private val thresholdDb: Float = -38f,
    private val minSilenceSeconds: Double = 0.12,
    private val keepPadding: Double = 0.15,
) {
    data class Energy(val t: Double, val db: Float)

    /** Kept before speech resumes. */
    val onsetPadSeconds get() = max(0.12, keepPadding + 0.08)

    /** Kept after speech ends. */
    val tailPadSeconds get() = max(0.06, keepPadding)

    fun energies(
        mono: FloatArray,
        rate: Int = 16_000,
    ): List<Energy> {
        val window = max(1, (windowSeconds * rate).toInt())
        val out = ArrayList<Energy>(mono.size / window)
        var i = 0
        while (i + window <= mono.size) {
            var s = 0.0
            for (k in i until i + window) s += mono[k] * mono[k]
            val rms = sqrt(s / window).toFloat()
            out += Energy(i.toDouble() / rate, if (rms > 0) 20 * log10(rms) else -120f)
            i += window
        }
        return out
    }

    fun detect(
        mono: FloatArray,
        rate: Int = 16_000,
    ): List<Span> {
        val e = energies(mono, rate)
        return collapse(e, adaptiveThreshold(e))
    }

    /** floor = 10th percentile, speech = 90th. The line sits 6 to 12 dB above the floor, kept within -55 to -26 dBFS. */
    fun adaptiveThreshold(energies: List<Energy>): Float {
        val levels = energies.map { it.db }.filter { it > -119 }.sorted()
        if (levels.size < 20) return thresholdDb

        fun pct(p: Double) = levels[min(levels.size - 1, max(0, ((levels.size - 1) * p).toInt()))]
        val floor = pct(0.10)
        val speech = pct(0.90)
        val sep = speech - floor
        if (sep < 10) return thresholdDb
        val margin = min(12f, max(6f, sep * 0.35f))
        return max(-55f, min(-26f, floor + margin))
    }

    /**
     * Groups quiet windows into ranges, pads them inward, and keeps a cut only if what remains after
     * the pads is still a real pause. A click or a keyboard tap too short to be speech, with quiet on
     * both sides, counts as quiet, or it would break one pause into pieces too small to cut.
     */
    fun collapse(
        energies: List<Energy>,
        threshold: Float,
        minSpeechRun: Double = 0.12,
    ): List<Span> {
        val quiet = BooleanArray(energies.size) { energies[it].db < threshold }
        var q = 0
        while (q < quiet.size) {
            if (quiet[q]) {
                q++
                continue
            }
            var j = q
            while (j < quiet.size && !quiet[j]) j++
            val runEnd = if (j < quiet.size) energies[j].t else energies[j - 1].t + windowSeconds
            if (q > 0 && j < quiet.size && runEnd - energies[q].t < minSpeechRun) {
                for (k in q until j) quiet[k] = true
            }
            q = j
        }
        val ranges = ArrayList<Span>()
        var start: Double? = null
        energies.forEachIndexed { i, e ->
            val silent = quiet[i]
            if (silent && start == null) start = e.t
            if (!silent || i == energies.size - 1) {
                start?.let { s ->
                    val end = if (i == energies.size - 1 && silent) e.t + windowSeconds else e.t
                    val lo = s + tailPadSeconds
                    val hi = end - onsetPadSeconds
                    if (hi - lo >= minSilenceSeconds) ranges += Span(lo, hi)
                    start = null
                }
            }
        }
        return ranges
    }
}
