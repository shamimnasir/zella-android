package com.zellahq.core.project

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Zella project, the same document the iPhone and Mac apps write as `project.json` inside a
 * `.zella` package. docs/ZELLA_FORMAT.md is the contract.
 *
 * Fields Android understands are typed. Everything else the other apps write sits in `extras`
 * and is written back unchanged, so opening an iPhone project here and saving it never loses
 * iPhone-only work. Optional lists mirror Swift optionals exactly: null means the key is absent.
 */
data class Project(
    val id: String,
    val formatVersion: Int = FORMAT_VERSION,
    val name: String,
    val createdAt: String,
    val modifiedAt: String,
    val aspect: Aspect = Aspect.Vertical,
    val platformPresetId: String? = null,
    val clips: List<Clip> = emptyList(),
    val transitions: List<Transition>? = null,
    val captions: CaptionTrack? = null,
    val captionLanguage: String? = null,
    val titles: List<Title>? = null,
    val zoomBlocks: List<ZoomBlock>? = null,
    val musicBeds: List<MusicBed>? = null,
    val soundBites: List<SoundBite>? = null,
    val audioLeadOffset: Double? = null,
    val extras: JsonObject = EMPTY,
) {
    companion object {
        /** The newest format this build reads. A newer file is refused, never "healed". */
        const val FORMAT_VERSION = 1
    }
}

enum class Aspect(val raw: String, val ratio: Double) {
    Vertical("vertical", 9.0 / 16.0),
    Square("square", 1.0),
    Landscape("landscape", 16.0 / 9.0),
    Portrait45("portrait45", 4.0 / 5.0),
    ;

    companion object {
        fun of(raw: String) = entries.firstOrNull { it.raw == raw } ?: Vertical
    }
}

/** Swift encodes these as `{"packaged":{"_0":"name"}}` and `{"absolute":{"_0":"file:///..."}}`. */
sealed interface MediaRef {
    /** A file name inside the package's media folder. Untrusted: see [safeLeaf]. */
    data class Packaged(val path: String) : MediaRef

    /** A `file:///` URL on this device. */
    data class Absolute(val url: String) : MediaRef

    companion object {
        /**
         * Reduces an untrusted packaged path to one safe file name, as the iPhone app does: a crafted
         * `../../x` must never reach outside the package's media folder.
         */
        fun safeLeaf(rel: String): String {
            val leaf = rel.substringAfterLast('/')
            return if (leaf.isEmpty() || leaf == "." || leaf == ".." || leaf.contains("..")) "__invalid__" else leaf
        }
    }
}

data class Clip(
    val id: String,
    val source: MediaRef,
    val sourceDuration: Double,
    val trimStart: Double = 0.0,
    val trimEnd: Double = sourceDuration,
    val speed: Double = 1.0,
    val reversed: Boolean = false,
    val isBRoll: Boolean = false,
    val volume: Double = 1.0,
    /** effects, speedRamp, voiceNoise, voicePolish and anything newer. */
    val extras: JsonObject = EMPTY,
) {
    val trimmedDuration get() = maxOf(0.0, trimEnd - trimStart)
    val timelineDuration get() = if (speed > 0) trimmedDuration / speed else trimmedDuration
    val speedRamp: String? get() = extras["speedRamp"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "None" }
}

data class Transition(
    val id: String,
    val afterClipId: String,
    /** The iPhone's display names are the stored values, e.g. "Cross Dissolve". */
    val type: String,
    val duration: Double = 0.5,
    val extras: JsonObject = EMPTY,
)

data class CaptionWord(
    val text: String,
    val start: Double,
    val end: Double,
    val emphasized: Boolean? = null,
    val emoji: String? = null,
    val extras: JsonObject = EMPTY,
)

data class CaptionCue(val id: String, val words: List<CaptionWord>, val extras: JsonObject = EMPTY) {
    val start get() = words.firstOrNull()?.start ?: 0.0
    val end get() = words.lastOrNull()?.end ?: 0.0
    val text get() = words.joinToString(" ") { it.text }
}

/** preset, position, font and style stay in extras until Phase 4 types them. */
data class CaptionTrack(val cues: List<CaptionCue>, val extras: JsonObject = EMPTY)

data class Title(
    val id: String,
    val text: String,
    val start: Double,
    val duration: Double,
    val extras: JsonObject = EMPTY,
)

data class ZoomBlock(
    val id: String,
    val start: Double,
    val inDur: Double = 0.4,
    val hold: Double = 1.0,
    val outDur: Double = 0.4,
    val scale: Double = 1.6,
    val anchorX: Double = 0.5,
    val anchorY: Double = 0.5,
    val extras: JsonObject = EMPTY,
) {
    val end get() = start + inDur + hold + outDur
}

data class MusicBed(
    val id: String,
    val source: MediaRef,
    val sourceDuration: Double,
    val start: Double = 0.0,
    val volume: Double = 0.6,
    val duck: Boolean = true,
    val name: String = "Music",
    val extras: JsonObject = EMPTY,
)

data class SoundBite(
    val id: String,
    val effectId: String,
    val atTime: Double,
    val volume: Double = 0.9,
    val extras: JsonObject = EMPTY,
)

internal val EMPTY = JsonObject(emptyMap())

/** Uppercase, like Swift's UUID().uuidString. */
fun newId(): String =
    java.util.UUID
        .randomUUID()
        .toString()
        .uppercase()

/** Whole seconds, UTC, no fraction: what Swift's `.iso8601` strategy writes and every iOS reads. */
fun isoNow(nowMs: Long = System.currentTimeMillis()): String =
    java.time.Instant
        .ofEpochMilli(nowMs - nowMs % 1000)
        .toString()
