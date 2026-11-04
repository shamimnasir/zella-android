package com.zellahq.core.captions

import com.zellahq.core.project.CaptionCue
import com.zellahq.core.project.CaptionTrack
import com.zellahq.core.project.CaptionWord
import com.zellahq.core.project.Clip
import com.zellahq.core.project.Project
import com.zellahq.core.project.newId
import com.zellahq.core.project.startTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A recognised word with times in seconds of the clip's SOURCE file. */
data class SourceWord(val text: String, val start: Double, val end: Double)

/** Turns recognised words into the caption cues the iPhone app stores. Pure, so the rules are tested. */
object CaptionBuilder {
    /** The iPhone's caption styles. The stored value is the display name, as on the iPhone. */
    val PRESETS = listOf("Word Pop", "Hormozi", "Karaoke", "Pop Box", "Neon", "Clean", "Beast", "Highlighter", "Bounce")
    const val DEFAULT_PRESET = "Word Pop"

    /**
     * Maps a clip's words from source time onto the timeline: trim, then speed. Words outside the
     * trimmed range are dropped. A reversed clip has no captions: reversed speech is not words.
     */
    fun place(
        project: Project,
        clip: Clip,
        words: List<SourceWord>,
    ): List<CaptionWord> {
        if (clip.reversed) return emptyList()
        val start = project.startTime(clip.id)
        return words
            .filter { it.end > clip.trimStart && it.start < clip.trimEnd }
            .map { w ->
                val s = start + (maxOf(w.start, clip.trimStart) - clip.trimStart) / clip.speed
                val e = start + (minOf(w.end, clip.trimEnd) - clip.trimStart) / clip.speed
                CaptionWord(w.text, s, maxOf(e, s + 0.05))
            }
    }

    /**
     * Groups words into short cues: at most [maxWords] words and [maxSeconds] long, and a new cue
     * after a pause of [pause] seconds or after a word that ends a sentence (including the Bangla
     * and Hindi full stop, U+0964).
     */
    fun cues(
        words: List<CaptionWord>,
        maxWords: Int = 3,
        maxSeconds: Double = 1.8,
        pause: Double = 0.4,
    ): List<CaptionCue> {
        val out = ArrayList<CaptionCue>()
        var current = ArrayList<CaptionWord>()

        fun flush() {
            if (current.isNotEmpty()) out += CaptionCue(newId(), current)
            current = ArrayList()
        }
        for (w in words.sortedBy { it.start }) {
            val last = current.lastOrNull()
            val breakHere =
                last != null &&
                    (
                        current.size >= maxWords ||
                            w.end - current.first().start > maxSeconds ||
                            w.start - last.end > pause ||
                            last.text.trimEnd().lastOrNull() in SENTENCE_END
                    )
            if (breakHere) flush()
            current += w
        }
        flush()
        return out
    }

    private val SENTENCE_END = setOf('.', '!', '?', '।', '॥')

    /** A new track that keeps the style of [old] (preset, position, font) and replaces the words. */
    fun track(
        cues: List<CaptionCue>,
        old: CaptionTrack?,
    ): CaptionTrack {
        val extras = old?.extras ?: JsonObject(mapOf("preset" to JsonPrimitive(DEFAULT_PRESET), "position" to position(0.5, 0.72)))
        return CaptionTrack(cues, extras)
    }

    fun position(
        x: Double,
        y: Double,
    ) = JsonObject(mapOf("x" to JsonPrimitive(x), "y" to JsonPrimitive(y)))

    /** Replaces a cue's words with [text], spreading the cue's time evenly over the new words. */
    fun editCue(
        cue: CaptionCue,
        text: String,
    ): CaptionCue? {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val step = (cue.end - cue.start) / parts.size
        return cue.copy(words = parts.mapIndexed { i, t -> CaptionWord(t, cue.start + i * step, cue.start + (i + 1) * step) })
    }
}
