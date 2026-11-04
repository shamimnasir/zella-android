package com.zellahq.core.audio

import com.zellahq.core.project.Project
import kotlin.math.log10
import kotlin.math.sqrt

/** A stretch of the timeline where someone is talking, in seconds. */
data class Window(val start: Double, val end: Double)

/** Where the voice is, for lowering music under it. The iPhone stores the same list as voiceWindows. */
object Voice {
    /** From caption words: words closer than [join] seconds merge, padded by [pad] each side. */
    fun fromCaptions(
        p: Project,
        join: Double = 0.4,
        pad: Double = 0.12,
    ): List<Window> {
        val words = p.captions?.cues.orEmpty().flatMap { it.words }.sortedBy { it.start }
        return merge(words.map { Window(it.start - pad, it.end + pad) }, join)
    }

    /**
     * From sound energy, 20 ms frames: a frame is voice when it is 12 dB above the quietest tenth of
     * the clip and above -50 dBFS. Short gaps are bridged so music does not pump between words.
     */
    fun fromEnergy(
        mono: FloatArray,
        rate: Int,
        offset: Double = 0.0,
        join: Double = 0.35,
    ): List<Window> {
        val frame = rate / 50
        if (mono.size < frame) return emptyList()
        val db =
            (0 until mono.size / frame).map { f ->
                var s = 0.0
                for (i in f * frame until (f + 1) * frame) s += mono[i] * mono[i]
                20 * log10(sqrt(s / frame).coerceAtLeast(1e-9))
            }
        val floor = db.sorted()[db.size / 10]
        val out = ArrayList<Window>()
        db.forEachIndexed { f, d ->
            if (d > floor + 12 && d > -50) out += Window(offset + f * 0.02, offset + (f + 1) * 0.02)
        }
        return merge(out, join)
    }

    fun merge(
        windows: List<Window>,
        join: Double,
    ): List<Window> {
        val out = ArrayList<Window>()
        for (w in windows.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && w.start - last.end <= join) out[out.size - 1] = Window(last.start, maxOf(last.end, w.end)) else out += w
        }
        return out.filter { it.end - it.start > 0.05 }
    }
}

/**
 * How loud a music bed is at each moment, exactly as the iPhone's makeAudioMix builds it. A bed that
 * steps back for the voice opens a little louder, dips to 18% under speech with 0.12 s ramps, and
 * fades out over the last 1.5 s. A bed that does not step back (a voiceover) stays flat.
 */
class MusicEnvelope private constructor(private val points: List<Pair<Double, Double>>) {
    /** One ducked span: the level starts falling at [down] and starts rising at [up]. */
    data class Ramp(val down: Double, val up: Double)

    /** Gain at timeline second [t], straight lines between the points. */
    fun gain(t: Double): Double {
        if (t <= points.first().first) return points.first().second
        for (i in 1 until points.size) {
            val (t1, v1) = points[i]
            if (t < t1) {
                val (t0, v0) = points[i - 1]
                return if (t1 <= t0) v1 else v0 + (v1 - v0) * (t - t0) / (t1 - t0)
            }
        }
        return points.last().second
    }

    companion object {
        const val DUCK_LEVEL = 0.18
        const val RAMP = 0.12
        const val INTRO_BOOST = 1.25
        const val FADE_OUT = 1.5
        const val RAMP_GAP = 0.15

        /** The iPhone's CompositionBuilder.duckRamps: windows too close for the ramps merge, so the music stays down. */
        fun ramps(
            voice: List<Window>,
            rampDur: Double,
            fadeStart: Double,
            rampGap: Double,
        ): List<Ramp> {
            val merged = ArrayList<DoubleArray>()
            for (w in voice.sortedBy { it.start }) {
                if (w.end <= w.start) continue
                val s = maxOf(0.0, w.start)
                val last = merged.lastOrNull()
                if (last != null && s <= last[1] + rampDur * 2 + 0.0001) {
                    last[1] = maxOf(last[1], w.end)
                } else {
                    merged += doubleArrayOf(s, w.end)
                }
            }
            val applied = ArrayList<Ramp>()
            var lastEnd = -Double.MAX_VALUE
            for ((start, end) in merged.map { it[0] to it[1] }) {
                val up = minOf(end, fadeStart - rampGap - rampDur)
                if (!(start - rampDur > lastEnd || applied.isEmpty())) continue
                if (start < lastEnd) continue
                if (up - start <= rampDur * 2) continue
                if (up + rampDur > fadeStart - rampGap) continue
                applied += Ramp(start, up)
                lastEnd = up + rampDur
            }
            return applied
        }

        fun of(
            volume: Double,
            duck: Boolean,
            voice: List<Window>,
            total: Double,
        ): MusicEnvelope {
            if (!duck) return MusicEnvelope(listOf(0.0 to volume))
            val ducked = volume * DUCK_LEVEL
            val fadeDur = minOf(FADE_OUT, maxOf(0.0, total * 0.5))
            val fadeStart = total - fadeDur
            val applied = ramps(voice, RAMP, fadeStart, RAMP_GAP)
            val lastEnd = applied.lastOrNull()?.let { it.up + RAMP } ?: 0.0
            var level = if (applied.isEmpty()) volume else minOf(1.0, volume * INTRO_BOOST)
            val out = arrayListOf(0.0 to level)
            for ((down, up) in applied) {
                out += down to level
                out += down + RAMP to ducked
                out += up to ducked
                out += up + RAMP to volume
                level = volume
            }
            if (fadeDur > 0.2 && fadeStart > lastEnd + RAMP_GAP) {
                out += fadeStart to level
                out += fadeStart + fadeDur to 0.0
            }
            return MusicEnvelope(out)
        }
    }
}
