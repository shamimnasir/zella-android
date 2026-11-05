@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.core.auto

import com.zellahq.core.project.CaptionCue
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.insertClip
import com.zellahq.core.project.newId
import com.zellahq.core.project.rippleDelete
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.max
import kotlin.math.min

/** One cutaway to place: the word it is about, and when in the timeline. */
data class BRollPlacement(val keyword: String, val time: Double)

/** A stock clip a provider offered. */
data class StockClip(val id: String, val url: String, val width: Int, val height: Int, val duration: Double)

/**
 * Auto B-Roll's decisions, ported from the iPhone's EditorViewModel+BRoll and BRollService. Android has
 * no part-of-speech tagger like Apple's, so keywords come from a stop list plus length, with the
 * iPhone's longest-first order. That is the one known difference.
 */
object BRoll {
    const val CARD_SECONDS = 2.5
    const val DEFAULT_COUNT = 4
    const val MIN_GAP = 4.5

    private val STOP =
        setOf(
            "thing", "things", "stuff", "guys", "guy", "video", "today", "time", "way", "part", "kind", "lot", "bit", "one", "something",
            "anything", "everything", "someone",
            // What Apple's tagger drops as not a noun, verb or adjective.
            "the", "and", "but", "for", "nor", "yet", "with", "from", "into", "onto", "over", "under", "about", "after", "before",
            "this", "that", "these", "those", "there", "here", "where", "when", "what", "which", "while", "who", "whom", "whose", "why", "how",
            "have", "has", "had", "having", "was", "were", "are", "been", "being", "does", "did", "doing", "done", "will", "would", "could",
            "should", "shall", "might", "must", "can", "cannot", "not", "don't", "didn't", "isn't", "aren't", "wasn't", "weren't", "won't",
            "you", "your", "yours", "they", "them", "their", "theirs", "she", "her", "hers", "him", "his", "its", "our", "ours", "mine", "myself",
            "yourself", "because", "though", "although", "just", "very", "really", "actually", "basically", "literally", "maybe", "also", "then",
            "than", "them", "some", "any", "all", "more", "most", "much", "many", "such", "only", "own", "same", "too", "again", "ever", "never",
            "always", "going", "gonna", "wanna", "like", "know", "think", "right", "okay", "yeah", "well", "let's", "it's", "i'm", "you're",
            "we're", "they're", "that's", "there's", "here's", "what's", "got", "get", "gets", "out", "off", "down", "back", "now", "see", "say",
            "said", "says", "make", "makes", "made", "want", "need",
        )

    /** Ranked words for a caption line, best first: longer words are more specific and more visual. */
    fun keywords(
        text: String,
        limit: Int = 12,
    ): List<String> =
        Regex("[\\p{L}\\p{M}']+")
            .findAll(text)
            .map { it.value.lowercase().trim('\'') }
            .filter { it.length >= 3 && it !in STOP }
            .distinct()
            .sortedByDescending { it.length }
            .take(limit)
            .toList()

    /** One per eligible caption line, kept apart by [minGap] and with no word used twice, up to [limit]. */
    fun placements(
        cues: List<CaptionCue>,
        limit: Int,
        timelineDuration: Double,
        minGap: Double = MIN_GAP,
    ): List<BRollPlacement> {
        val out = ArrayList<BRollPlacement>()
        val used = HashSet<String>()
        var last = -minGap
        val upper = max(0.5, timelineDuration - 0.5)
        for (cue in cues.sortedBy { it.start }) {
            if (out.size >= limit) break
            val t = cue.start
            if (t < 0.3 || t < last + minGap || t >= upper) continue
            val kw = keywords(cue.text).firstOrNull { it !in used } ?: continue
            out += BRollPlacement(kw, t)
            used += kw
            last = t
        }
        return out
    }

    /** With a clip picked, only that clip's lines count, and one cutaway per seven seconds of it. */
    fun scoped(
        cues: List<CaptionCue>,
        scope: ClosedFloatingPointRange<Double>?,
    ) = if (scope == null) cues else cues.filter { it.start >= scope.start - 0.001 && it.start < scope.endInclusive - 0.001 }

    fun budget(
        defaultCount: Int,
        scope: ClosedFloatingPointRange<Double>?,
    ) = if (scope == null) max(1, defaultCount) else max(1, min(defaultCount, ((scope.endInclusive - scope.start) / 7).toInt()))

    /**
     * The picked clip's lines first. The editor always has a clip picked once there are two, and that clip may be a
     * fraction of a second with no words in it. Then the whole video's lines count, so the button is never a dead end.
     */
    fun plan(
        cues: List<CaptionCue>,
        scope: ClosedFloatingPointRange<Double>?,
        count: Int,
        timelineDuration: Double,
    ): List<BRollPlacement> {
        val picked = placements(scoped(cues, scope), budget(count, scope), timelineDuration)
        return if (picked.isEmpty() && scope != null) placements(cues, budget(count, null), timelineDuration) else picked
    }

    fun clip(
        file: String,
        sourceDuration: Double,
        maxDuration: Double = 4.0,
    ): Clip {
        val src = max(0.5, sourceDuration)
        return Clip(newId(), MediaRef.Absolute(file), src, trimEnd = min(maxDuration, max(1.0, src)), isBRoll = true)
    }

    /** Every insert lengthens the timeline, so each later placement moves by the time inserted so far. */
    fun insert(
        p: Project,
        cutaways: List<Pair<Clip, Double>>,
    ): Project {
        var out = p
        var shift = 0.0
        cutaways.forEach { (c, t) ->
            out = out.insertClip(c, t + shift)
            shift += c.timelineDuration
        }
        return out
    }

    /** Never empties the timeline: when b-roll is all there is, the clips stay. */
    fun removeAll(p: Project): Pair<Project, Int> {
        val n = p.clips.count { it.isBRoll }
        if (n == 0 || n == p.clips.size) return p to 0
        // Through ripple delete, so everything after each cutaway moves back up.
        return p.clips.filter { it.isBRoll }.fold(p) { q, c -> q.rippleDelete(c.id) } to n
    }

    private fun stockScore(w: Int?): Int =
        when {
            w == null || w <= 0 -> 4
            w >= 3000 -> 2
            w >= 1700 -> 8
            w >= 1100 -> 10
            w >= 640 -> 6
            else -> 3
        }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

    fun parsePexels(text: String): List<StockClip> =
        (Json.parseToJsonElement(text).jsonObject["videos"] as? JsonArray).orEmpty().mapNotNull { e ->
            val v = e.jsonObject
            val files =
                (v["video_files"] as? JsonArray).orEmpty().map { it.jsonObject }.filter { it.str("file_type").orEmpty().contains("mp4") }
            val best = files.maxByOrNull { stockScore(it.int("width")) } ?: return@mapNotNull null
            val link = best.str("link") ?: return@mapNotNull null
            StockClip(
                v.str("id").orEmpty(),
                link,
                best.int("width") ?: v.int("width") ?: 0,
                best.int("height") ?: v.int("height") ?: 0,
                max(2, v.int("duration") ?: 0).toDouble(),
            )
        }

    fun parsePixabay(text: String): List<StockClip> =
        (Json.parseToJsonElement(text).jsonObject["hits"] as? JsonArray).orEmpty().mapNotNull { e ->
            val h = e.jsonObject
            val videos = h["videos"] as? JsonObject ?: return@mapNotNull null
            val f = listOf("medium", "small", "large", "tiny").firstNotNullOfOrNull { videos[it] as? JsonObject } ?: return@mapNotNull null
            val url = f.str("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            StockClip(h.str("id").orEmpty(), url, f.int("width") ?: 0, f.int("height") ?: 0, max(2, h.int("duration") ?: 0).toDouble())
        }

    /** Same stable hash as the iPhone, so a word gets the same colours everywhere. */
    fun stableHash(s: String): Long {
        var h = 5381L
        s.encodeToByteArray().forEach { b -> h = h * 33 + (b.toInt() and 255) }
        return if (h == Long.MIN_VALUE) 0 else kotlin.math.abs(h)
    }
}
