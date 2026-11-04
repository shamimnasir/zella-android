package com.zellahq.core.auto

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The decisions one-tap editing makes, as pure functions: where to cut, what to place, how much is
 * too much. Numbers and reasons are the iPhone's (EditorViewModel), held to it by a fixture.
 */
object AutoEdit {
    // Pause cuts.
    const val SILENCE_GUARD = 0.85
    const val FILLER_GUARD = 0.20
    const val POLISH_MINIMUM_CUT = 0.12

    // Opening punch.
    const val OPENING_SCALE = 1.3
    const val OPENING_HOLD = 0.8
    const val OPENING_SETTLE = 0.9

    // Emphasis zooms.
    const val EMPHASIS_MIN_GAP = 8.0
    const val EMPHASIS_CLEARANCE = 1.0
    const val EMPHASIS_OPENING = 2.5
    const val EMPHASIS_LEAD = 0.05
    const val EMPHASIS_SPAN = 0.05 + 0.12 + 0.25 + 0.3

    // Pattern interrupts.
    const val INTERRUPT_MIN_WINDOW = 6.0
    const val INTERRUPT_CAP = 6
    const val INTERRUPT_SCALE = 1.055
    const val INTERRUPT_SPAN = 2.9
    val INTERRUPT_ANCHORS = listOf(0.5 to 0.5, 0.5 to 0.40, 0.60 to 0.5)

    // Sounds under cuts.
    const val CUT_SOUND_VOLUME = 0.16
    const val CUT_RISER_VOLUME = 0.12
    const val CUT_MIN_SPACING = 9.0
    const val CUT_MAX_RISERS = 2
    const val CUT_RISER_PAUSE = 1.5
    const val CUT_SECONDS_PER_SOUND = 18.0
    const val CUT_MAX_SOUNDS = 4

    /** Reel transitions: a handful of accents, never one per silence cut. */
    const val MAX_DISSOLVES = 8
    const val DISSOLVE_SECONDS = 0.35

    /** Removes each word's footprint from the silence, so no sample under a word is ever cut. */
    fun subtractingWitnessWords(
        ranges: List<Span>,
        words: List<SpokenWord>,
        slop: Double = 0.08,
        minimumKeep: Double = 0.12,
    ): List<Span> {
        val out = ArrayList<Span>()
        for (r in ranges) {
            var pieces = listOf(r)
            for (w in words) {
                val lo = w.start - slop
                val hi = w.end + slop
                if (hi <= r.start || lo >= r.end) continue
                pieces =
                    pieces.flatMap { p ->
                        if (hi <= p.start || lo >= p.end) {
                            listOf(p)
                        } else {
                            buildList {
                                if (lo > p.start) add(Span(p.start, lo))
                                if (hi < p.end) add(Span(hi, p.end))
                            }
                        }
                    }
            }
            out += pieces.filter { it.end - it.start >= minimumKeep }
        }
        return out
    }

    /** Where to split one long take so transitions have joins to sit on: the longest real pauses, else even pieces. */
    fun naturalCuts(
        silences: List<Span>,
        total: Double,
    ): List<Double>? {
        val cuts =
            silences
                .filter { it.length >= 0.3 && it.start > 0.4 && it.end < total - 0.4 }
                .sortedByDescending { it.length }
                .take(5)
                .map { (it.start + it.end) / 2 }
                .sorted()
        if (cuts.isNotEmpty()) return cuts
        val minPiece = 2.5
        if (total < minPiece * 2) return null
        val pieces = min(6, max(2, (total / 5.0).toInt()))
        return (1 until pieces).map { total * it / pieces }
    }

    /** The joins that get a dissolve: spread over the whole timeline, about eight whatever the length. */
    fun dissolveJoins(
        joinCount: Int,
        most: Int = MAX_DISSOLVES,
    ): List<Int> {
        if (joinCount <= 0 || most <= 0) return emptyList()
        val stride = max(1, ceil(joinCount / most.toDouble()).toInt())
        return (0 until joinCount step stride).toList()
    }

    /** Punch strength from the subject's size. No face, or a face already filling the frame, gets none. */
    fun smartZoomScale(
        faceHeight: Double,
        emphasisDb: Double = 0.0,
    ): Double? {
        if (faceHeight <= 0.02 || faceHeight >= 0.42) return null
        val base = min(1.45, max(1.12, 0.30 / faceHeight))
        return min(1.5, base + min(0.15, max(0.0, emphasisDb / 40)))
    }

    fun tracksAreCalm(
        tracks: List<List<Double>>,
        threshold: Double = 0.22,
    ) = tracks.all { v -> v.isEmpty() || v.max() - v.min() <= threshold }

    fun emphasisRank(word: String) = CaptionEmphasis.rank(word)

    /** Start times for emphasis zooms: strongest first, clear of cuts, zooms and the opening, at most one per 8 s. */
    fun emphasisZoomStarts(
        candidates: List<Pair<Double, Int>>,
        duration: Double,
        transitionTimes: List<Double>,
        occupied: List<Span>,
        minGap: Double = EMPHASIS_MIN_GAP,
        openingExclusion: Double = EMPHASIS_OPENING,
        clearance: Double = EMPHASIS_CLEARANCE,
    ): List<Double> {
        val accepted = ArrayList<Double>()
        val ranked = candidates.sortedWith(compareByDescending<Pair<Double, Int>> { it.second }.thenBy { it.first })
        for ((time, _) in ranked) {
            val start = time - EMPHASIS_LEAD
            if (start < openingExclusion || start + EMPHASIS_SPAN > duration) continue
            if (!transitionTimes.all { it <= start - clearance || it >= start + EMPHASIS_SPAN + clearance }) continue
            if (!occupied.all { start + EMPHASIS_SPAN <= it.start || start >= it.end }) continue
            if (!accepted.all { abs(it - start) >= minGap }) continue
            accepted += start
        }
        return accepted.sorted()
    }

    /** One gentle move in the middle of each stretch of 6 s or more with nothing happening. At most six. */
    fun interruptPlan(
        duration: Double,
        covered: List<Span>,
        minWindow: Double = INTERRUPT_MIN_WINDOW,
        cap: Int = INTERRUPT_CAP,
    ): List<Triple<Double, Double, Double>> {
        if (duration < minWindow) return emptyList()
        val clamped =
            covered
                .map { Span(max(0.0, it.start), min(duration, it.end)) }
                .filter { it.end >= it.start }
                .sortedBy { it.start }
        val merged = ArrayList<Span>()
        for (s in clamped) {
            val last = merged.lastOrNull()
            if (last != null && s.start <= last.end) merged[merged.size - 1] = Span(last.start, max(last.end, s.end)) else merged += s
        }
        val gaps = ArrayList<Span>()
        var cursor = 0.0
        for (s in merged) {
            if (s.start - cursor >= minWindow) gaps += Span(cursor, s.start)
            cursor = max(cursor, s.end)
        }
        if (duration - cursor >= minWindow) gaps += Span(cursor, duration)
        val out = ArrayList<Triple<Double, Double, Double>>()
        gaps.forEachIndexed { i, g ->
            if (out.size < cap) {
                val (x, y) = INTERRUPT_ANCHORS[i % INTERRUPT_ANCHORS.size]
                out += Triple(max(0.0, (g.start + g.end) / 2 - INTERRUPT_SPAN / 2), x, y)
            }
        }
        return out
    }

    /** Impact 0, whoosh 1, blend 2. */
    fun cutSoundPriority(type: String) =
        when (type) {
            "Zoom Punch", "Zoom In", "Zoom Out", "Glitch Cut", "Velocity Cut", "Shake Cut", "Pixelate" -> 0
            "Whip Pan", "Whip Up", "Slide Push", "Spin", "Cube Flip", "Warp", "Circle Wipe" -> 1
            else -> 2
        }

    fun cutSound(type: String): Pair<String, Double> =
        when (type) {
            "Whip Pan", "Whip Up", "Slide Push", "Spin", "Cube Flip", "Warp", "Circle Wipe" -> "Whoosh" to 0.15
            "Zoom Punch", "Zoom In", "Zoom Out" -> "Boom" to 0.0
            "Glitch Cut", "Velocity Cut", "Shake Cut", "Pixelate" -> "Punch" to 0.0
            else -> "Riser" to 0.4
        }

    fun cutSoundBudget(
        cutCount: Int,
        timelineDuration: Double,
    ): Int {
        if (cutCount <= 0) return 0
        val byClock = (timelineDuration / CUT_SECONDS_PER_SOUND).toInt()
        val byCuts = (cutCount + 1) / 2
        return max(1, min(CUT_MAX_SOUNDS, min(byCuts, max(1, byClock))))
    }

    data class CutSound(val effect: String, val time: Double, val volume: Double)

    /** Sounds under the strongest cuts only, spaced out, with risers rationed. */
    fun cutSoundPlan(
        cuts: List<Pair<String, Double>>,
        pauses: List<Span> = emptyList(),
        timelineDuration: Double = 0.0,
    ): List<CutSound> {
        if (cuts.isEmpty()) return emptyList()
        val span = if (timelineDuration > 0) timelineDuration else cuts.maxOf { it.second } + 1
        val budget = cutSoundBudget(cuts.size, span)
        val lastCut = cuts.maxOf { it.second }
        val ranked = cuts.sortedWith(compareBy<Pair<String, Double>> { cutSoundPriority(it.first) }.thenBy { it.second })
        val accepted = ArrayList<Double>()
        var risers = 0
        val out = ArrayList<CutSound>()
        for ((type, time) in ranked) {
            if (out.size >= budget) break
            if (!accepted.all { abs(it - time) >= CUT_MIN_SPACING }) continue
            var volume = CUT_SOUND_VOLUME
            if (cutSoundPriority(type) == 2) {
                val afterPause = pauses.any { it.length >= CUT_RISER_PAUSE && time - it.end >= -0.25 && time - it.end <= 1.0 }
                val intoFinale = abs(time - lastCut) < 0.001
                if (!(afterPause || intoFinale) || risers >= CUT_MAX_RISERS) continue
                risers++
                volume = CUT_RISER_VOLUME
            }
            val (fx, leadIn) = cutSound(type)
            accepted += time
            out += CutSound(fx, max(0.0, time - leadIn), volume)
        }
        return out.sortedBy { it.time }
    }

    /** The music's mood from how fast the person talks and how fast the edit cuts. */
    fun musicMood(
        wordsPerSecond: Double,
        averageClip: Double,
    ): String {
        if (wordsPerSecond > 2.6 || (averageClip > 0 && averageClip < 3.0)) {
            return if (averageClip > 0 && averageClip < 2.0) "trap" else "uplift"
        }
        if (wordsPerSecond > 0 && wordsPerSecond < 1.6) return if (averageClip > 8.0) "chill" else "lofi"
        return if (averageClip > 0 && averageClip < 6.0 && wordsPerSecond > 0) "cinematic" else "ambient"
    }

    /** The iPhone's mood ids mapped onto the bundled loops it renders them from. */
    fun musicLoopFor(mood: String) =
        when (mood) {
            "chill" -> "Corporate Clean"
            "lofi" -> "Lo-Fi Chill"
            "uplift" -> "Upbeat Pop"
            "cinematic" -> "Cinematic Rise"
            "trap" -> "Hip-Hop Head-Nod"
            else -> "Acoustic Feel"
        }
}

/** A face seen at a timeline moment. Positions are fractions of the source frame, top-left origin. */
data class FaceSample(val time: Double, val x: Double, val y: Double, val height: Double)

/** Keyed crop centres that keep the speaker in the frame, the iPhone's ReframeService. */
object Reframe {
    data class Key(val time: Double, val x: Double, val y: Double)

    /** Moves only for shifts bigger than the dead zone, and eases towards them, so the crop never jitters. */
    fun plan(
        faces: List<FaceSample>,
        smoothing: Double = 0.6,
        deadZone: Double = 0.04,
    ): List<Key> {
        if (faces.isEmpty()) return listOf(Key(0.0, 0.5, 0.5))
        var x = faces[0].x
        var y = faces[0].y
        return faces.map { f ->
            if (abs(f.x - x) > deadZone) x += (f.x - x) * (1 - smoothing)
            if (abs(f.y - y) > deadZone) y += (f.y - y) * (1 - smoothing)
            Key(f.time, x.coerceIn(0.1, 0.9), y.coerceIn(0.1, 0.9))
        }
    }

    fun centerAt(
        keys: List<Key>,
        t: Double,
    ): Pair<Double, Double> {
        if (keys.isEmpty()) return 0.5 to 0.5
        if (t <= keys.first().time) return keys.first().x to keys.first().y
        if (t >= keys.last().time) return keys.last().x to keys.last().y
        for (i in 1 until keys.size) {
            if (keys[i].time >= t) {
                val a = keys[i - 1]
                val b = keys[i]
                val f = (t - a.time) / max(0.0001, b.time - a.time)
                return a.x + (b.x - a.x) * f to a.y + (b.y - a.y) * f
            }
        }
        return keys.last().x to keys.last().y
    }

    /** Where a face at (x, y) in the source lands in a canvas the source fills by cropping, centre-anchored. */
    fun canvasPoint(
        x: Double,
        y: Double,
        sourceAspect: Double,
        canvasAspect: Double,
    ): Pair<Double, Double> {
        val keptX = min(1.0, canvasAspect / sourceAspect)
        val keptY = min(1.0, sourceAspect / canvasAspect)
        return ((x - 0.5) / keptX + 0.5).coerceIn(0.0, 1.0) to ((y - 0.5) / keptY + 0.5).coerceIn(0.0, 1.0)
    }
}
