package com.zellahq.core.auto

import com.zellahq.core.project.Clip
import com.zellahq.core.project.Project
import com.zellahq.core.project.Rgba
import com.zellahq.core.project.SoundBite
import com.zellahq.core.project.Title
import com.zellahq.core.project.TitleLook
import com.zellahq.core.project.ZoomBlock
import com.zellahq.core.project.duration
import com.zellahq.core.project.newId
import com.zellahq.core.project.newTitle
import com.zellahq.core.project.position
import com.zellahq.core.project.removeRanges
import com.zellahq.core.project.setTransition
import com.zellahq.core.project.split
import com.zellahq.core.project.startTime
import com.zellahq.core.project.transitionAfter
import com.zellahq.core.project.transitionOverlap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The steps of Auto-Polish and Make it Viral, in the order they run. The "See what it changes" sheet
 * and the run both read this one list, so what is promised is what happens. The order matters: all
 * cutting comes before the voice work, so nothing moves under it afterwards.
 */
enum class PolishStep {
    Silences,
    Fillers,
    HookAndTransitions,
    Voice,
    Captions,
    Framing,
    Music,
    PatternInterrupts,
    ColorsPop,
    CutSounds,
    Reframe,
    ;

    /** Make it Viral adds these. Auto-Polish never adds captions, a colour grade or sound effects. */
    val isViralOnly get() = this == ColorsPop || this == CutSounds || this == Captions

    companion object {
        fun plan(
            viral: Boolean,
            reframing: Boolean,
        ) = entries.filter { step -> if (step == Reframe) reframing else viral || !step.isViralOnly }
    }
}

/** What a run did, for the honest receipt shown when it finishes. */
data class PolishReceipt(
    val silencesCut: Int = 0,
    val silenceSeconds: Double = 0.0,
    val fillersCut: Int = 0,
    val dissolves: Int = 0,
    val zooms: Int = 0,
    val interrupts: Int = 0,
    val sounds: Int = 0,
)

object Polish {
    /** The most moves a viewer should get per second of video. */
    const val MAX_EFFECT_DENSITY = 0.35

    /** Cuts the pauses. Declines when nearly everything reads as silence: a quiet microphone must not empty the video. */
    fun cutSilences(
        p: Project,
        silences: List<Span>,
        words: List<SpokenWord>,
        minimumCut: Double = AutoEdit.POLISH_MINIMUM_CUT,
    ): Pair<Project, List<Span>> {
        var ranges = if (words.isEmpty()) silences else AutoEdit.subtractingWitnessWords(silences, words)
        if (minimumCut > 0) ranges = ranges.filter { it.length >= minimumCut }
        val cut = ranges.sumOf { it.length }
        if (p.duration > 0 && cut >= p.duration * AutoEdit.SILENCE_GUARD) return p to emptyList()
        return p.removeRanges(ranges.map { it.start..it.end }) to ranges
    }

    /**
     * Cuts filler words, in English only. Elsewhere the list means nothing, and repeating a word is
     * grammar in Bangla and Hindi rather than a stumble. Declines at a fifth of the video: that is not
     * filler, it is the speech model failing to follow the speaker.
     */
    fun cutFillers(
        p: Project,
        words: List<SpokenWord>,
        language: String?,
    ): Pair<Project, List<Span>> {
        if (language != null && language != "en") return p to emptyList()
        val ranges = TranscriptAnalysis.allFillerRanges(words)
        val cut = ranges.sumOf { it.length }
        if (p.duration > 0 && cut >= p.duration * AutoEdit.FILLER_GUARD) return p to emptyList()
        return p.removeRanges(ranges.map { it.start..it.end }) to ranges
    }

    private fun isHook(t: Title): Boolean {
        // By its look and place, not its length: a silence cut can shorten it, and a second run would then stack one.
        val l = TitleLook(t)
        return t.start < 0.05 && t.duration <= HOOK_SECONDS + 0.001 && l.positionY == 0.14 && l.positionX == 0.5
    }

    /** The opener: one title from the first spoken words. A second run replaces it rather than stacking one on top. */
    fun addHookTitle(
        p: Project,
        firstWords: List<String>,
    ): Project {
        val line = firstWords.take(6).joinToString(" ").trim()
        val kept = p.titles.orEmpty().filterNot { isHook(it) }
        // With no words heard there is no first line. English words of ours do not belong on the person's video.
        if (line.isEmpty()) return p.copy(titles = kept.ifEmpty { null })
        val text = line.uppercase()
        val hook =
            newTitle(
                text,
                0.0,
                HOOK_SECONDS,
                mapOf(
                    "position" to position(0.5, 0.14),
                    "fontSize" to JsonPrimitive(84.0),
                    "fill" to Rgba.White.json(),
                    "stroke" to Rgba.Black.json(),
                    "entrance" to JsonPrimitive("pop"),
                ),
            )
        return p.copy(titles = kept + hook)
    }

    private const val HOOK_SECONDS = 2.5

    /** Splits a single long take at its real pauses so a dissolve has somewhere to sit. */
    fun cutAtNaturalPauses(
        p: Project,
        silences: List<Span>,
    ): Project {
        if (p.clips.size > 1) return p
        val cuts = AutoEdit.naturalCuts(silences, p.duration) ?: return p
        return cuts.sortedDescending().fold(p) { acc, t -> acc.split(t) }
    }

    /** A cross dissolve on about eight widely spaced joins, leaving any transition already there alone. */
    fun placeDissolves(
        p: Project,
        /** Moves to leave room for: Make it Viral keeps space for its sounds under the cuts. */
        reserve: Int = 0,
    ): Pair<Project, Int> {
        var out = p
        var placed = 0
        val existing = p.transitions.orEmpty().size
        // The density cap decides how many fit, after the opener and the opening zoom have their place.
        val budget = minOf(AutoEdit.MAX_DISSOLVES, maxOf(0, (p.duration * MAX_EFFECT_DENSITY).toInt() - 2 - reserve))
        val room = (budget - existing).coerceAtLeast(0)
        for (i in AutoEdit.dissolveJoins(p.clips.size - 1, budget)) {
            if (placed >= room) break
            val id = p.clips[i].id
            if (out.transitionAfter(id) != null) continue
            out = out.setTransition(id, "Cross Dissolve", AutoEdit.DISSOLVE_SECONDS)
            placed++
        }
        return out to placed
    }

    private fun ZoomBlock.ownedBy(flag: String) = (extras[flag] as? JsonPrimitive)?.booleanOrNull == true

    /** Starts punched in and settles out. Steps aside if a zoom already owns the opening. */
    fun addOpeningPunch(
        p: Project,
        anchorX: Double = 0.5,
        anchorY: Double = 0.5,
        scale: Double? = null,
    ): Project {
        val duration = p.duration
        if (p.clips.isEmpty() || duration <= 1.0) return p
        if (p.zoomBlocks.orEmpty().any { it.start < 2.0 && it.end > 0.0 }) return p
        var hold = AutoEdit.OPENING_HOLD
        var settle = AutoEdit.OPENING_SETTLE
        val total = hold + settle
        if (duration < total + 0.5) {
            val k = maxOf(0.3, (duration - 0.5) / total)
            hold *= k
            settle *= k
        }
        val z =
            ZoomBlock(
                newId(),
                start = 0.0,
                inDur = 0.0,
                hold = hold,
                outDur = settle,
                scale = scale ?: AutoEdit.OPENING_SCALE,
                anchorX = anchorX.coerceIn(0.0, 1.0),
                anchorY = anchorY.coerceIn(0.0, 1.0),
                extras = JsonObject(mapOf("motionBlur" to JsonPrimitive(0.0), AUTO_OPENING to JsonPrimitive(true))),
            )
        return p.copy(zoomBlocks = p.zoomBlocks.orEmpty() + z)
    }

    /** Slow, shallow pushes on the strongest words. Depths alternate so no two neighbours feel the same. */
    fun addEmphasisZooms(
        p: Project,
        words: List<SpokenWord>,
        anchorX: Double = 0.5,
        anchorY: Double = 0.5,
    ): Pair<Project, Int> {
        if (words.isEmpty() || p.duration <= AutoEdit.EMPHASIS_OPENING + AutoEdit.EMPHASIS_SPAN) return p to 0
        val candidates = ArrayList<Pair<Double, Int>>()
        words.chunked(3).forEach { line ->
            CaptionEmphasis.chosen(line.map { it.text }).forEach { i -> candidates += line[i].start to AutoEdit.emphasisRank(line[i].text) }
        }
        if (candidates.isEmpty()) return p to 0
        // A re-run plans from scratch. Keeping last run's zooms as "occupied" let the runners-up from
        // that run land in the gaps, so every tap added more (found by the re-run gate; the iPhone's
        // addEmphasisPunchZooms has the same hole).
        val existing = p.zoomBlocks.orEmpty().filterNot { it.ownedBy(AUTO_EMPHASIS) || it.ownedBy(AUTO_INTERRUPT) }
        val starts =
            AutoEdit.emphasisZoomStarts(
                candidates,
                p.duration,
                transitionCuts(p).map { it.second },
                existing.map { Span(it.start, it.end) },
            )
        if (starts.isEmpty()) return p to 0
        val blocks =
            starts.mapIndexed { i, s ->
                ZoomBlock(
                    newId(),
                    start = s,
                    inDur = 0.9,
                    hold = 0.45,
                    outDur = 1.0,
                    scale = if (i % 2 == 0) 1.05 else 1.075,
                    anchorX = anchorX.coerceIn(0.0, 1.0),
                    anchorY = anchorY.coerceIn(0.0, 1.0),
                    extras = JsonObject(mapOf("motionBlur" to JsonPrimitive(0.0), AUTO_EMPHASIS to JsonPrimitive(true))),
                )
            }
        return p.copy(zoomBlocks = existing + blocks) to starts.size
    }

    /** Small moves where the picture would sit still for 6 s or more. A re-run replaces its own. */
    fun applyPatternInterrupts(p: Project): Pair<Project, Int> {
        val manual = p.zoomBlocks.orEmpty().filterNot { it.ownedBy(AUTO_INTERRUPT) }
        val duration = p.duration
        if (duration < AutoEdit.INTERRUPT_MIN_WINDOW) return p.copy(zoomBlocks = manual.ifEmpty { null }) to 0
        val covered = ArrayList<Span>()
        transitionCuts(p).forEach { covered += Span(it.second, it.second) }
        manual.forEach { covered += Span(it.start, it.end) }
        p.titles.orEmpty().forEach { covered += Span(it.start, it.start + it.duration) }
        val plan = AutoEdit.interruptPlan(duration, covered)
        val added =
            plan.map { (start, x, y) ->
                ZoomBlock(
                    newId(),
                    start = start,
                    inDur = 1.1,
                    hold = 0.6,
                    outDur = 1.2,
                    scale = AutoEdit.INTERRUPT_SCALE,
                    anchorX = x,
                    anchorY = y,
                    extras = JsonObject(mapOf("motionBlur" to JsonPrimitive(0.0), AUTO_INTERRUPT to JsonPrimitive(true))),
                )
            }
        return p.copy(zoomBlocks = (manual + added).ifEmpty { null }) to plan.size
    }

    /** Each transition as (type, the moment of the cut). */
    fun transitionCuts(p: Project): List<Pair<String, Double>> {
        if (p.transitions.isNullOrEmpty() || p.clips.size < 2) return emptyList()
        val out = ArrayList<Pair<String, Double>>()
        for (i in 0 until p.clips.size - 1) {
            val t = p.transitionAfter(p.clips[i].id) ?: continue
            out += t.type to p.startTime(p.clips[i + 1].id) + p.transitionOverlap(i) / 2
        }
        return out
    }

    /** Sounds under the strongest cuts. A re-run replaces its own and leaves hand-placed ones alone. */
    fun placeCutSounds(
        p: Project,
        pauses: List<Span> = emptyList(),
    ): Pair<Project, Int> {
        val plan = AutoEdit.cutSoundPlan(transitionCuts(p), pauses, p.duration)
        val kept = p.soundBites.orEmpty().filterNot { (it.extras["autoCut"] as? JsonPrimitive)?.booleanOrNull == true }
        val placed =
            plan.map {
                SoundBite(newId(), it.effect, it.time, it.volume, JsonObject(mapOf("autoCut" to JsonPrimitive(true))))
            }
        return p.copy(soundBites = (kept + placed).ifEmpty { null }) to plan.size
    }

    /** True when the person has done their own colour work on a clip. One-tap colour never overrides it. */
    fun hasOwnGrade(clip: Clip): Boolean {
        val fx = clip.extras["effects"] as? JsonObject ?: return false
        val name = (fx["presetName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return (name != null && name != BUNDLE_GRADE) || fx["colorBoard"] != null || fx["colorAdjustments"] != null && name == null
    }

    const val BUNDLE_GRADE = "Bright & Sunny"

    /** The receipt, read from the finished project so it can only say what is really there. */
    fun receiptOf(
        p: Project,
        silencesCut: Int,
        silenceSeconds: Double,
        fillersCut: Int,
    ): PolishReceipt {
        val zooms = p.zoomBlocks.orEmpty()
        return PolishReceipt(
            silencesCut,
            silenceSeconds,
            fillersCut,
            p.transitions.orEmpty().size,
            zooms.count { it.ownedBy(AUTO_OPENING) || it.ownedBy(AUTO_EMPHASIS) },
            zooms.count { it.ownedBy(AUTO_INTERRUPT) },
            p.soundBites.orEmpty().count { (it.extras["autoCut"] as? JsonPrimitive)?.booleanOrNull == true },
        )
    }

    /** Every move a viewer sees or hears, per second of video. */
    fun effectDensity(p: Project): Double {
        val d = p.duration
        if (d <= 0) return 0.0
        return (p.transitions.orEmpty().size + p.zoomBlocks.orEmpty().size + p.soundBites.orEmpty().size + p.titles.orEmpty().size) / d
    }

    /**
     * Restraint is a rule: past 0.35 moves per second a video reads as noise. Takes back what one-tap
     * editing added, the least needed first, until it fits. What the person placed is never touched,
     * and neither is the opener, so a very short clip with only its hook may stay above the line.
     */
    fun capDensity(p: Project): Project {
        var out = p

        fun over() = effectDensity(out) > MAX_EFFECT_DENSITY

        fun dropLastSound(): Boolean {
            val all = out.soundBites.orEmpty()
            val i = all.indexOfLast { (it.extras["autoCut"] as? JsonPrimitive)?.booleanOrNull == true }
            if (i < 0) return false
            out = out.copy(soundBites = (all.take(i) + all.drop(i + 1)).ifEmpty { null })
            return true
        }

        fun dropLastZoom(flag: String): Boolean {
            val all = out.zoomBlocks.orEmpty()
            val i = all.indexOfLast { it.ownedBy(flag) }
            if (i < 0) return false
            out = out.copy(zoomBlocks = (all.take(i) + all.drop(i + 1)).ifEmpty { null })
            return true
        }
        while (over() && dropLastZoom(AUTO_INTERRUPT)) Unit
        while (over() && dropLastZoom(AUTO_EMPHASIS)) Unit
        while (over() && dropLastSound()) Unit
        while (over() && dropLastZoom(AUTO_OPENING)) Unit
        return out
    }

    private const val AUTO_OPENING = "autoOpening"
    private const val AUTO_INTERRUPT = "autoInterrupt"
    private const val AUTO_EMPHASIS = "autoEmphasis"
}
