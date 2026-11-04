package com.zellahq.core.project

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/*
 * Timeline maths and edits, ported rule for rule from the iPhone's Project.swift and
 * ProjectEditing.swift so the same project gives the same timeline on every platform.
 *
 * The rule this file exists to keep: every edit that moves content moves everything pinned to
 * that content (captions, titles, zooms, music, sound effects, and the iPhone-only collections
 * kept in extras: callouts, picture-in-picture, reframe keys, voice windows). That omission is
 * the most repeated bug in the iPhone and Mac history. TimelineRemapTest checks every collection.
 */

/** Largest share of either neighbour one transition may overlap. Same as iPhone `transitionShare`. */
const val TRANSITION_SHARE = 0.45

fun clampedOverlap(
    requested: Double,
    a: Double,
    b: Double,
): Double {
    val d = maxOf(0.0, minOf(requested, minOf(a, b) * TRANSITION_SHARE))
    return if (d < 1.0 / 60.0) 0.0 else d // a sub-frame overlap is not a transition
}

fun Project.transitionAfter(clipId: String) = transitions?.firstOrNull { it.afterClipId == clipId }

fun Project.transitionOverlap(afterIndex: Int): Double {
    if (afterIndex < 0 || afterIndex >= clips.size - 1) return 0.0
    val t = transitionAfter(clips[afterIndex].id) ?: return 0.0
    return clampedOverlap(t.duration, clips[afterIndex].timelineDuration, clips[afterIndex + 1].timelineDuration)
}

/** Clip lengths minus what transitions overlap: the length the player and export really have. */
val Project.duration: Double
    get() {
        val overlap = if (transitions.isNullOrEmpty()) 0.0 else (0 until clips.size - 1).sumOf { transitionOverlap(it) }
        return maxOf(0.0, clips.sumOf { it.timelineDuration } - overlap)
    }

fun Project.startTime(clipId: String): Double {
    var t = 0.0
    clips.forEachIndexed { i, c ->
        if (c.id == clipId) return t
        t += c.timelineDuration - transitionOverlap(i)
    }
    return t
}

/** The clip playing at timeline [time] and its start. */
fun Project.clipAt(time: Double): Pair<Clip, Double>? {
    var t = 0.0
    clips.forEachIndexed { i, c ->
        val end = t + c.timelineDuration - transitionOverlap(i)
        if (time >= t && time < end) return c to t
        t = end
    }
    return clips.lastOrNull()?.let { it to maxOf(0.0, duration - it.timelineDuration) }
}

/** How much editing this is. The store refuses to replace a real project with a blank one. */
val Project.editWeight: Int
    get() {
        var n = if (clips.size > 1) clips.size else 0
        n += transitions.orEmpty().size + titles.orEmpty().size + zoomBlocks.orEmpty().size
        n += musicBeds.orEmpty().size + soundBites.orEmpty().size + (captions?.cues?.size ?: 0)
        n += rawList("callouts").size + rawList("overlayClips").size
        n += clips.count { it.speed != 1.0 || it.reversed }
        if (extras.containsKey("reframe")) n += 1
        return n
    }

// Edits. Each returns a new Project; the caller stamps modifiedAt and pushes undo.

fun Project.append(clip: Clip) = copy(clips = clips + clip).invalidatePolishedAudio()

/** Splits the clip under [time]. No change on a clip boundary. */
fun Project.split(time: Double): Project {
    val (clip, start) = clipAt(time) ?: return this
    val local = time - start
    if (local <= 0.01 || local >= clip.timelineDuration - 0.01) return this
    val idx = clips.indexOfFirst { it.id == clip.id }
    val sourceSplit = clip.trimStart + sourceFraction(clip, local) * clip.trimmedDuration
    var left = clip
    var right = clip.copy(id = newId())
    if (clip.reversed && clip.speedRamp == null) {
        // A reversed clip plays its source backwards: the earlier timeline half holds the later source.
        val rev = clip.trimEnd - local * clip.speed
        left = left.copy(trimStart = rev)
        right = right.copy(trimEnd = rev)
    } else {
        left = left.copy(trimEnd = sourceSplit)
        right = right.copy(trimStart = sourceSplit)
    }
    left = left.copy(extras = rebaseKeyframes(clip.extras, 0.0, local))
    right = right.copy(extras = rebaseKeyframes(clip.extras, local, clip.timelineDuration))
    val newClips =
        clips.toMutableList().apply {
            this[idx] = left
            add(idx + 1, right)
        }
    // A transition after the original now follows the right half.
    val newTransitions = transitions?.map { if (it.afterClipId == clip.id) it.copy(afterClipId = right.id) else it }
    return copy(clips = newClips, transitions = newTransitions)
}

/** Source position (0..1 of the trimmed range) at a local timeline time, honouring the iPhone ramps. */
private fun sourceFraction(
    clip: Clip,
    local: Double,
): Double {
    val ramp = clip.speedRamp ?: return local * clip.speed / maxOf(1e-9, clip.trimmedDuration)
    // Same 12-chunk 1/multiplier weighting as the iPhone's CompositionBuilder.
    val fT = local / clip.timelineDuration
    val n = 12
    val weights = (0 until n).map { 1.0 / maxOf(0.1, rampMultiplier(ramp, (it + 0.5) / n)) }
    val sum = weights.sum()
    var acc = 0.0
    for (k in 0 until n) {
        val share = weights[k] / sum
        if (fT <= acc + share) return (k + (fT - acc) / share) / n
        acc += share
    }
    return fT
}

internal fun Clip.sourceFractionAt(local: Double) = sourceFraction(this, local)

fun rampMultiplier(
    ramp: String,
    p: Double,
): Double {
    val x = p.coerceIn(0.0, 1.0)
    return when (ramp) {
        "Slow-Fast-Slow" -> 0.5 + 1.5 * (1 - abs(x - 0.5) * 2)
        "Fast-Slow-Fast" -> 0.5 + 1.5 * (abs(x - 0.5) * 2)
        "Ramp Up" -> 0.5 + 1.5 * x
        "Ramp Down" -> 2.0 - 1.5 * x
        else -> 1.0
    }
}

fun Project.rippleDelete(clipId: String): Project {
    val clip = clips.firstOrNull { it.id == clipId } ?: return this
    val s = startTime(clipId)
    return copy(clips = clips.filterNot { it.id == clipId })
        .pruneTransitions()
        .compactTimeline(s, s + clip.timelineDuration)
        .invalidatePolishedAudio()
}

/** Moves a clip to [newIndex]. Everything pinned inside each moved clip travels with it. */
fun Project.move(
    clipId: String,
    newIndex: Int,
): Project {
    val from = clips.indexOfFirst { it.id == clipId }
    if (from < 0) return this
    val to = newIndex.coerceIn(0, clips.size - 1)
    if (to == from) return this
    val oldStart = clips.associate { it.id to startTime(it.id) }
    val len = clips.associate { it.id to it.timelineDuration }
    val list = clips.toMutableList().apply { add(to, removeAt(from)) }
    val moved = copy(clips = list)
    val blocks =
        list.mapNotNull { c ->
            val os = oldStart.getValue(c.id)
            val l = len.getValue(c.id)
            val delta = moved.startTime(c.id) - os
            if (l > 0 && abs(delta) > 1e-4) Triple(os, os + l, delta) else null
        }
    // Ranged items move by their midpoint's block, so a cue keeps its word spacing.
    val shift = { t: Double -> blocks.firstOrNull { t >= it.first && t < it.second }?.third ?: 0.0 }
    // Reframe keys point at faces at timeline times; a reorder scrambles them. The iPhone drops the plan.
    val remapped = moved.remapByMidpoint(shift)
    return remapped
        .copy(extras = JsonObject(remapped.extras - "reframe")) // from the remapped extras, or the remap is lost
        .pruneTransitions()
        .invalidatePolishedAudio()
}

/** Drag the in-point by [delta] source seconds. */
fun Project.trimStart(
    clipId: String,
    delta: Double,
): Project {
    val i = clips.indexOfFirst { it.id == clipId }
    if (i < 0) return this
    val c = clips[i]
    val newStart = (c.trimStart + delta).coerceIn(0.0, c.trimEnd - 0.1)
    if (abs(newStart - c.trimStart) <= 1e-4) return this // a touched but undragged grip changes nothing
    val clipStart = startTime(clipId)
    val timelineDelta = (newStart - c.trimStart) / maxOf(0.1, c.speed)
    val trimmed = c.copy(trimStart = newStart, extras = rebaseKeyframes(c.extras, timelineDelta, Double.MAX_VALUE))
    val p = copy(clips = clips.toMutableList().apply { this[i] = trimmed })
    val shifted =
        when {
            timelineDelta > 1e-4 -> p.compactTimeline(clipStart, clipStart + timelineDelta)
            timelineDelta < -1e-4 -> p.insertGap(clipStart, -timelineDelta)
            else -> p
        }
    return shifted.invalidatePolishedAudio()
}

/** Drag the out-point by [delta] source seconds. */
fun Project.trimEnd(
    clipId: String,
    delta: Double,
): Project {
    val i = clips.indexOfFirst { it.id == clipId }
    if (i < 0) return this
    val c = clips[i]
    val end = (c.trimEnd + delta).coerceAtLeast(c.trimStart + 0.1).coerceAtMost(c.sourceDuration)
    if (abs(end - c.trimEnd) <= 1e-4) return this
    val clipStart = startTime(clipId)
    val oldDur = c.timelineDuration
    val trimmed = c.copy(trimEnd = end)
    val newDur = trimmed.timelineDuration
    val p = copy(clips = clips.toMutableList().apply { this[i] = trimmed })
    val shifted =
        when {
            oldDur - newDur > 1e-4 -> p.compactTimeline(clipStart + newDur, clipStart + oldDur)
            newDur - oldDur > 1e-4 -> p.insertGap(clipStart + oldDur, newDur - oldDur)
            else -> p
        }
    return shifted.invalidatePolishedAudio()
}

/** 0.1x to 8x. Pinned items inside the clip stretch with it; later ones shift. */
fun Project.setSpeed(
    clipId: String,
    speed: Double,
): Project {
    val i = clips.indexOfFirst { it.id == clipId }
    if (i < 0) return this
    val start = startTime(clipId)
    val oldDur = clips[i].timelineDuration
    val c = clips[i].copy(speed = speed.coerceIn(0.1, 8.0))
    val p = copy(clips = clips.toMutableList().apply { this[i] = c })
    val newDur = c.timelineDuration
    if (abs(newDur - oldDur) <= 1e-4) return p.invalidatePolishedAudio()
    val k = newDur / oldDur
    return p
        .remapTimes { x ->
            when {
                x <= start -> x
                x >= start + oldDur -> x + (newDur - oldDur)
                else -> start + (x - start) * k
            }
        }.invalidatePolishedAudio()
}

/** 0 mutes, 1 is unchanged. Matches the iPhone clamp. */
fun Project.setVolume(
    clipId: String,
    volume: Double,
) = mapClip(clipId) { it.copy(volume = volume.coerceIn(0.0, 1.0)) }.invalidatePolishedAudio()

fun Project.reverse(clipId: String) = mapClip(clipId) { it.copy(reversed = !it.reversed) }.invalidatePolishedAudio()

fun Project.duplicate(clipId: String) = insertCopyAfter(clipId) { it }

/** Plays the moment twice: a forward copy right after it. */
fun Project.instantReplay(clipId: String) = insertCopyAfter(clipId) { it.copy(reversed = false) }

/** A fast reversed copy right after it, like winding a tape back. */
fun Project.rewind(clipId: String) = insertCopyAfter(clipId) { it.copy(reversed = true, speed = minOf(8.0, maxOf(it.speed * 2, 2.0))) }

private fun Project.insertCopyAfter(
    clipId: String,
    change: (Clip) -> Clip,
): Project {
    val i = clips.indexOfFirst { it.id == clipId }
    if (i < 0) return this
    val copyClip = change(clips[i]).copy(id = newId())
    val at = startTime(clipId) + clips[i].timelineDuration
    return copy(clips = clips.toMutableList().apply { add(i + 1, copyClip) })
        .insertGap(at, copyClip.timelineDuration)
        .invalidatePolishedAudio()
}

/** Inserts [clip] exactly at [time], splitting the clip there first. */
fun Project.insertClip(
    clip: Clip,
    time: Double,
): Project {
    val t = time.coerceIn(0.0, duration)
    val p = split(t)
    var acc = 0.0
    var at = p.clips.size
    for ((i, c) in p.clips.withIndex()) {
        if (acc >= t - 0.0005) {
            at = i
            break
        }
        acc += c.timelineDuration - p.transitionOverlap(i)
    }
    return p
        .copy(clips = p.clips.toMutableList().apply { add(at, clip) })
        .insertGap(t, clip.timelineDuration)
        .invalidatePolishedAudio()
}

fun Project.setTransition(
    afterClipId: String,
    type: String,
    duration: Double = 0.5,
): Project {
    val i = clips.indexOfFirst { it.id == afterClipId }
    if (i < 0 || i >= clips.size - 1) return this
    val next = clips[i + 1].id
    val before = startTime(next)
    val maxD = minOf(clips[i].timelineDuration, clips[i + 1].timelineDuration) * TRANSITION_SHARE
    val d = minOf(maxOf(0.1, duration), maxOf(0.01, maxD))
    val list =
        transitions.orEmpty().filterNot { it.afterClipId == afterClipId } +
            Transition(newId(), afterClipId, type, d)
    val p = copy(transitions = list)
    val delta = p.startTime(next) - before
    return p.shiftFrom(before, delta).invalidatePolishedAudio()
}

fun Project.removeTransition(afterClipId: String): Project {
    if (transitionAfter(afterClipId) == null) return this
    val i = clips.indexOfFirst { it.id == afterClipId }
    val next = clips.getOrNull(i + 1)?.id
    val before = next?.let { startTime(it) }
    val list = transitions.orEmpty().filterNot { it.afterClipId == afterClipId }.ifEmpty { null }
    val p = copy(transitions = list)
    if (next == null || before == null) return p
    val delta = p.startTime(next) - before
    return p.shiftFrom(before, delta).invalidatePolishedAudio()
}

/** Removes one stretch of the timeline (silence or filler cleanup), keeping pinned items glued. */
fun Project.removeRange(
    from: Double,
    to: Double,
): Project {
    val lo = maxOf(0.0, from)
    val hi = minOf(to, duration)
    if (hi <= lo) return this
    val p = split(lo).split(hi)
    val drop =
        p.clips
            .filter { c ->
                val s = p.startTime(c.id)
                val mid = s + c.timelineDuration / 2
                mid > lo && mid < hi
            }.map { it.id }
            .toSet()
    return p
        .copy(clips = p.clips.filterNot { it.id in drop })
        .pruneTransitions()
        .compactTimeline(lo, hi)
        .invalidatePolishedAudio()
}

/** Latest first, so earlier removals do not shift later ones. */
fun Project.removeRanges(ranges: List<ClosedFloatingPointRange<Double>>) =
    ranges.sortedByDescending { it.start }.fold(this) { p, r -> p.removeRange(r.start, r.endInclusive) }

fun Project.pruneTransitions(): Project {
    val t = transitions ?: return this
    val ok = clips.dropLast(1).map { it.id }.toSet() // the last clip cannot have one
    return copy(transitions = t.filter { it.afterClipId in ok }.ifEmpty { null })
}

private val POLISH_KEYS =
    setOf("polishedAudioURL", "polishedAudioFingerprint", "polishedVoiceKey", "voiceChangedAudioURL")

/** Moves everything at or after [from] by [delta] (a transition added or removed). */
private fun Project.shiftFrom(
    from: Double,
    delta: Double,
) = if (abs(delta) > 1e-4) remapTimes { if (it < from) it else it + delta } else this

/** The processed voice file was rendered for one exact timeline; any edit makes it stale. */
fun Project.invalidatePolishedAudio() =
    copy(
        extras =
            JsonObject(
                extras - POLISH_KEYS,
            ),
    )

private fun Project.mapClip(
    id: String,
    f: (Clip) -> Clip,
) = copy(clips = clips.map { if (it.id == id) f(it) else it })

// Remapping. One list of collections, used by all three remappers below.

/** A monotonic retime of every pinned time. Nothing is dropped. */
fun Project.remapTimes(map: (Double) -> Double): Project =
    copy(
        captions =
            captions?.let {
                    t ->
                t.copy(
                    cues =
                        t.cues.map {
                                c ->
                            c.copy(words = c.words.map { it.copy(start = map(it.start), end = map(it.end)) })
                        },
                )
            },
        titles = titles?.map { it.copy(start = map(it.start)) },
        zoomBlocks = zoomBlocks?.map { it.copy(start = map(it.start)) },
        musicBeds = musicBeds?.map { it.copy(start = map(it.start)) },
        soundBites = soundBites?.map { it.copy(atTime = map(it.atTime)) },
        extras =
            extras
                .mapRawList("callouts") { it.retime("start", map).retimeList("trackTimes", map) }
                .mapRawList("overlayClips") { it.retime("start", map) }
                .mapRawList("voiceWindows") { it.retime("start", map).retime("end", map) }
                .mapReframeKeys(map),
    )

/** Shifts everything at or after [at] right by [length]. */
fun Project.insertGap(
    at: Double,
    length: Double,
) = if (length <= 0) this else remapTimes { if (it < at) it else it + length }

/**
 * Content between [lo] and [hi] was removed. Later items shift left; items inside shrink or are
 * dropped (a word fully cut, a title under 0.05 s, a sound effect whose cut was cut).
 */
fun Project.compactTimeline(
    lo: Double,
    hi: Double,
): Project {
    val len = hi - lo
    if (len <= 0) return this
    val map = { t: Double ->
        if (t <= lo) {
            t
        } else if (t >= hi) {
            t - len
        } else {
            lo
        }
    }
    val cues =
        captions?.cues?.mapNotNull { cue ->
            val words =
                cue.words.mapNotNull { w ->
                    val s = map(w.start)
                    val e = map(w.end)
                    if (e - s > 0.01) w.copy(start = s, end = e) else null
                }
            if (words.isEmpty()) null else cue.copy(words = words)
        }
    val ranged = { start: Double, dur: Double -> map(start) to map(start + dur) - map(start) }
    return copy(
        captions = captions?.copy(cues = cues.orEmpty()),
        titles =
            titles?.mapNotNull { t ->
                val (s, d) = ranged(t.start, t.duration)
                if (d > 0.05) t.copy(start = s, duration = d) else null
            },
        zoomBlocks =
            zoomBlocks?.mapNotNull {
                    z ->
                if (map(z.end) - map(z.start) > 0.05) z.copy(start = map(z.start)) else null
            },
        musicBeds = musicBeds?.map { it.copy(start = map(it.start)) },
        soundBites =
            soundBites?.filterNot {
                it.atTime > lo && it.atTime < hi
            }?.map { it.copy(atTime = map(it.atTime)) }?.ifEmpty { null },
        extras =
            extras
                .filterRawList("callouts") { it.shrink(map)?.retimeList("trackTimes", map) }
                .filterRawList("overlayClips") { it.shrink(map) }
                .filterRawList("voiceWindows") { w ->
                    val s = map(w.num("start"))
                    val e = map(w.num("end"))
                    if (e - s > 0.01) w.with("start", s).with("end", e) else null
                }.mapReframeKeys(map),
    )
}

private fun Project.remapByMidpoint(shift: (Double) -> Double): Project {
    val mid = { s: Double, e: Double -> shift((s + e) / 2) }
    return copy(
        captions =
            captions?.let { t ->
                t.copy(
                    cues =
                        t.cues.map { c ->
                            val d = mid(c.start, c.end)
                            if (d == 0.0) {
                                c
                            } else {
                                c.copy(
                                    words = c.words.map { it.copy(start = it.start + d, end = it.end + d) },
                                )
                            }
                        },
                )
            },
        titles = titles?.map { it.copy(start = it.start + mid(it.start, it.start + it.duration)) },
        zoomBlocks = zoomBlocks?.map { it.copy(start = it.start + mid(it.start, it.end)) },
        musicBeds = musicBeds?.map { it.copy(start = it.start + shift(it.start)) },
        soundBites = soundBites?.map { it.copy(atTime = it.atTime + shift(it.atTime)) },
        extras =
            extras
                .mapRawList(
                    "callouts",
                ) { o ->
                    val d = mid(o.num("start"), o.num("start") + o.num("duration"))
                    o.with("start", o.num("start") + d).retimeList("trackTimes") { it + d }
                }
                .mapRawList(
                    "overlayClips",
                ) { o -> o.with("start", o.num("start") + mid(o.num("start"), o.num("start") + o.num("duration"))) }
                .mapRawList("voiceWindows") { o ->
                    val d = mid(o.num("start"), o.num("end"))
                    o.with("start", o.num("start") + d).with("end", o.num("end") + d)
                },
    )
}

// Raw JSON helpers for collections Android stores but does not model yet.

internal fun Project.rawList(key: String) = (extras[key] as? JsonArray)?.map { it.jsonObject }.orEmpty()

private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.doubleOrNull ?: 0.0

private fun JsonObject.with(
    k: String,
    v: Double,
) = JsonObject(this + (k to JsonPrimitive(v)))

private fun JsonObject.retime(
    k: String,
    map: (Double) -> Double,
) = if (containsKey(k)) with(k, map(num(k))) else this

/** A tracked path's times move with the content too, or the box drifts off what it is hiding. */
private fun JsonObject.retimeList(
    k: String,
    map: (Double) -> Double,
): JsonObject {
    val list = this[k] as? JsonArray ?: return this
    return JsonObject(this + (k to JsonArray(list.map { JsonPrimitive(map(it.jsonPrimitive.doubleOrNull ?: 0.0)) })))
}

/** Callout or picture-in-picture: shrink through a cut, drop when nothing is left. */
private fun JsonObject.shrink(map: (Double) -> Double): JsonObject? {
    val s = map(num("start"))
    val d = map(num("start") + num("duration")) - s
    return if (d > 0.05) with("start", s).with("duration", d) else null
}

private fun JsonObject.mapRawList(
    key: String,
    f: (JsonObject) -> JsonObject,
): JsonObject {
    val list = this[key] as? JsonArray ?: return this
    return JsonObject(this + (key to JsonArray(list.map { f(it.jsonObject) })))
}

private fun JsonObject.filterRawList(
    key: String,
    f: (JsonObject) -> JsonObject?,
): JsonObject {
    val list = this[key] as? JsonArray ?: return this
    val kept = list.mapNotNull { f(it.jsonObject) }
    // The iPhone stores an emptied list as absent.
    return if (kept.isEmpty() && key != "callouts") {
        JsonObject(
            this - key,
        )
    } else {
        JsonObject(this + (key to JsonArray(kept)))
    }
}

private fun JsonObject.mapReframeKeys(map: (Double) -> Double): JsonObject {
    val reframe = this["reframe"] as? JsonObject ?: return this
    val keys = reframe["keys"] as? JsonArray ?: return this
    val moved = JsonArray(keys.map { it.jsonObject.retime("time", map) })
    return JsonObject(this + ("reframe" to JsonObject(reframe + ("keys" to moved))))
}

/** Keyframes are clip-local: keep those inside [lo, hi] and move them to start at zero. */
private fun rebaseKeyframes(
    extras: JsonObject,
    lo: Double,
    hi: Double,
): JsonObject {
    val effects = extras["effects"] as? JsonObject ?: return extras
    val kf = effects["keyframes"] as? JsonObject ?: return extras
    val cut =
        kf.mapValues { (_, track) ->
            val keys = (track as? JsonObject)?.get("keys") as? JsonArray ?: return@mapValues track
            val kept =
                keys
                    .map { it.jsonObject }
                    .filter {
                        val t = it.num("time")
                        t >= lo - 1e-4 && t <= hi + 1e-4
                    }
                    .map { it.with("time", maxOf(0.0, it.num("time") - lo)) }
            JsonObject(track + ("keys" to JsonArray(kept)))
        }
    val empty = cut.values.all { ((it as? JsonObject)?.get("keys") as? JsonArray)?.isEmpty() ?: true }
    val newEffects: JsonElement? = if (empty) null else JsonObject(cut)
    val fx =
        if (newEffects == null) {
            JsonObject(
                effects - "keyframes",
            )
        } else {
            JsonObject(effects + ("keyframes" to newEffects))
        }
    return JsonObject(extras + ("effects" to fx))
}
