// Media3's composition preview and export are marked unstable and experimental. Zella depends on
// them on purpose: they are the only way to preview exactly what exports. Pinned to 1.10.1.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.zellahq.app.editor

import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.zellahq.core.project.Clip
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.ZoomBlock
import com.zellahq.core.project.background
import com.zellahq.core.project.duration
import com.zellahq.core.project.keyframes
import com.zellahq.core.project.opacity
import com.zellahq.core.project.overlays
import com.zellahq.core.project.rampMultiplier
import com.zellahq.core.project.sourceFractionAt
import com.zellahq.core.project.sourceTimeAt
import com.zellahq.core.project.special
import com.zellahq.core.project.startTime
import com.zellahq.core.project.transform
import com.zellahq.core.project.transitionAfter
import com.zellahq.core.project.transitionOverlap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The output frame for an aspect: 1080 on the short side, as the free tier allows. */
fun canvasSize(
    project: Project,
    shortSide: Int = 1080,
): Size {
    val r = project.aspect.ratio
    return if (r < 1) Size(shortSide, even(shortSide / r)) else Size(even(shortSide * r), shortSide)
}

private fun even(v: Double) = (Math.round(v / 2) * 2).toInt()

/**
 * Turns a project into one Media3 Composition. Preview (CompositionPlayer) and export (Transformer)
 * both use it, so what plays is what exports.
 *
 * Layout: clips sit on one track. When the project has transitions, clips alternate between two
 * tracks with gaps between them, so neighbours overlap by exactly the transition's overlap and the
 * compositor fades between them. Times come from the same Project.startTime the timeline uses.
 */
@OptIn(UnstableApi::class)
class CompositionBuilder(
    private val store: ProjectStore,
    /** Reversed copies, made ahead by [ReverseRenderer]. A clip without one plays forward. */
    private val reversedFile: (Clip) -> File? = { null },
) {
    data class Built(
        val composition: Composition,
        val missing: List<Clip>,
        val needsReverse: List<Clip>,
        /** Joins whose transition has not been rendered yet. Until then they play as a plain cut. */
        val needsBake: List<Int>,
        /** Source files with a new background chosen whose person mask is not made yet. The clip plays as it was until then. */
        val needsMatte: List<java.io.File> = emptyList(),
    )

    fun build(
        p: Project,
        canvas: Size = canvasSize(p),
        /** The most frames a second to keep, for an export. Null keeps them all. */
        maxFps: Int? = null,
    ): Built {
        val missing = ArrayList<Clip>()
        val needsReverse = ArrayList<Clip>()
        val needsBake = ArrayList<Int>()
        val fade = !p.transitions.isNullOrEmpty() && p.clips.size > 1
        val sequences =
            if (fade) {
                transitionSequences(p, canvas, missing, needsReverse, needsBake)
            } else {
                // Declaring both track types makes Media3 fill silence for a clip without sound (a reversed copy).
                val one = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                p.clips.forEachIndexed { i, clip -> one.addItem(item(p, clip, i, canvas, missing, needsReverse)) }
                mutableListOf(one.build())
            }

        // Music on its own audio-only track. The iPhone's rule: a bed that steps back for the voice
        // repeats to cover its span; one that does not (a voiceover) plays once. A stored play length caps both.
        val voice = voiceWindows(p)
        val total = p.duration
        p.musicBeds?.forEach { bed ->
            val file = store.resolve(bed.source, p.id)
            if (!file.isFile || bed.sourceDuration <= 0.05) return@forEach
            val remain = total - maxOf(0.0, bed.start)
            val trim = (bed.extras["duration"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            val want = minOf(trim ?: remain, remain).let { if (bed.duck) it else minOf(it, bed.sourceDuration) }
            if (want <= 0.05) return@forEach
            val envelope = com.zellahq.core.audio.MusicEnvelope.of(bed.volume, bed.duck, voice, total)
            val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            if (bed.start > 0) b.addGap((bed.start * 1e6).toLong())
            var at = maxOf(0.0, bed.start)
            val end = at + want
            while (at < end - 0.05) {
                val len = minOf(bed.sourceDuration, end - at)
                val startAt = at
                val media =
                    MediaItem
                        .Builder()
                        .setUri(Uri.fromFile(file))
                        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionUs((len * 1e6).toLong()).build())
                        .build()
                b.addItem(
                    EditedMediaItem
                        .Builder(media)
                        .setDurationUs((bed.sourceDuration * 1e6).toLong())
                        .setRemoveVideo(true)
                        .setEffects(Effects(listOf(GainProcessor { t -> envelope.gain(startAt + t).toFloat() }), emptyList()))
                        .build(),
                )
                at += len
            }
            sequences += b.build()
        }

        // Each picture-in-picture clip's own sound, on an audio-only track.
        p.overlays.forEach { o ->
            if (o.audioMuted || o.volume <= 0.0) return@forEach
            val file = o.source?.let { store.resolve(it, p.id) }?.takeIf { it.isFile } ?: return@forEach
            if (o.start >= total || !hasSound(file)) return@forEach
            val played = minOf(o.duration, total - o.start)
            if (played <= 0.05) return@forEach
            val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            if (o.start > 0) b.addGap((o.start * 1e6).toLong())
            val media =
                MediaItem
                    .Builder()
                    .setUri(Uri.fromFile(file))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionUs(
                            (o.sourceOffset * 1e6).toLong(),
                        ).setEndPositionUs(((o.sourceOffset + played) * 1e6).toLong()).build(),
                    ).build()
            b.addItem(
                EditedMediaItem
                    .Builder(media)
                    .setDurationUs((o.sourceDuration * 1e6).toLong())
                    .setRemoveVideo(true)
                    .setEffects(Effects(listOf(GainProcessor { o.volume.coerceIn(0.0, 1.0).toFloat() }), emptyList()))
                    .build(),
            )
            sequences += b.build()
        }

        // Sound effects share tracks where they do not overlap: every track is a player of its own,
        // and Make it Viral can place many effects.
        val effects =
            p.soundBites.orEmpty().mapNotNull { bite ->
                val file = soundEffectFile(bite.effectId) ?: return@mapNotNull null
                if (bite.atTime >= total) return@mapNotNull null
                val len = com.zellahq.app.editor.Storage.durationSeconds(file) ?: 1.0
                Triple(bite, file, len)
            }.sortedBy { it.first.atTime }
        val effectTracks = ArrayList<Pair<EditedMediaItemSequence.Builder, DoubleArray>>()
        for ((bite, file, len) in effects) {
            val played = minOf(len, total - bite.atTime)
            val lane =
                effectTracks.firstOrNull { it.second[0] <= bite.atTime + 1e-6 }
                    ?: (EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)) to doubleArrayOf(0.0)).also { effectTracks += it }
            val gapUs = ((bite.atTime - lane.second[0]) * 1e6).toLong()
            if (gapUs > 0) lane.first.addGap(gapUs)
            val media =
                MediaItem
                    .Builder()
                    .setUri(Uri.fromFile(file))
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionUs((played * 1e6).toLong()).build())
                    .build()
            lane.first.addItem(
                EditedMediaItem
                    .Builder(media)
                    .setDurationUs((len * 1e6).toLong())
                    .setRemoveVideo(true)
                    .setEffects(Effects(listOf(GainProcessor { bite.volume.coerceIn(0.0, 1.0).toFloat() }), emptyList()))
                    .build(),
            )
            lane.second[0] = bite.atTime + played
        }
        effectTracks.forEach { sequences += it.first.build() }

        val composition =
            Composition
                .Builder(sequences)
                // Zoom first, captions after: a zoom must not enlarge the captions.
                .setEffects(
                    Effects(
                        emptyList(),
                        listOfNotNull(
                            maxFps?.let { androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(it.toFloat()) },
                            zoomEffect(p.zoomBlocks.orEmpty(), canvas),
                        ) + pipEffect(p, canvas) + calloutEffects(p, canvas) +
                            listOfNotNull(titleEffect(p, canvas), captionEffect(p, canvas)),
                    ),
                )
                .build()
        val needsMatte =
            p.clips
                .filter { it.background != null }
                .map { store.resolve(it.source, p.id) }
                .distinct()
                .filter { it.isFile && matteFile(it) == null }
        return Built(composition, missing, needsReverse, needsBake, needsMatte)
    }

    /** A clip with its picture and sound together, for the single track path. */
    private fun item(
        p: Project,
        clip: Clip,
        index: Int,
        canvas: Size,
        missing: MutableList<Clip>,
        needsReverse: MutableList<Clip>,
    ): EditedMediaItem {
        val r = resolved(p, clip, missing, needsReverse)
        return EditedMediaItem
            .Builder(r.media)
            .setDurationUs(r.sourceUs)
            .setSpeed(speedProvider(clip, r.isReversedCopy))
            .setEffects(Effects(audioEffects(p, clip, index), videoEffects(p, clip, canvas)))
            .build()
    }

    private class Resolved(val media: MediaItem, val file: File, val sourceUs: Long, val isReversedCopy: Boolean)

    /** Source seconds played in the first [local] seconds on the timeline. A ramp does not run at one speed. */
    private fun sourceSpan(
        clip: Clip,
        reversedCopy: Boolean,
        local: Double,
    ) = if (clip.speedRamp != null && !reversedCopy) {
        clip.sourceFractionAt(local).coerceIn(0.0, 1.0) * clip.trimmedDuration
    } else {
        local * clip.speed
    }

    /** [cutHead] and [cutTail] take timeline seconds off the ends of the clip's trimmed range. */
    private fun resolved(
        p: Project,
        clip: Clip,
        missing: MutableList<Clip>,
        needsReverse: MutableList<Clip>,
        cutHead: Double = 0.0,
        cutTail: Double = 0.0,
    ): Resolved {
        val forward = store.resolve(clip.source, p.id)
        val reversed = if (clip.reversed) reversedFile(clip) else null
        if (clip.reversed && reversed == null && clip !in needsReverse) needsReverse += clip
        val file = reversed ?: forward
        if (!file.isFile && clip !in missing) missing += clip
        // A reversed copy holds only the trimmed range, already backwards, starting at zero.
        val base = if (reversed != null) 0.0 else clip.trimStart
        val fromUs = ((base + sourceSpan(clip, reversed != null, cutHead)) * 1e6).toLong()
        val toUs = ((base + sourceSpan(clip, reversed != null, clip.timelineDuration - cutTail)) * 1e6).toLong()
        val media =
            MediaItem
                .Builder()
                .setUri(Uri.fromFile(file))
                .setMediaId(clip.id)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration
                        .Builder()
                        .setStartPositionUs(fromUs)
                        .setEndPositionUs(toUs)
                        .build(),
                ).build()
        // The preview player needs each source's full length up front; export works without it, so
        // only the preview crashed when this was missing (lessons.md, 2026-11-04).
        val sourceUs = ((if (reversed != null) clip.trimmedDuration else clip.sourceDuration) * 1e6).toLong()
        return Resolved(media, file, sourceUs, reversed != null)
    }

    /** Timeline seconds at this item's time zero, for anything that follows the project's clock. */
    private fun videoEffects(
        p: Project,
        clip: Clip,
        canvas: Size,
        clockOffset: Double = 0.0,
    ): List<Effect> {
        val video = ArrayList<Effect>()
        // First of all, on the frame as the file holds it: the matte lines up with the source.
        backgroundEffect(p, clip, clockOffset)?.let { video += it }
        reframeEffect(p, clip, canvas, clockOffset)?.let { video += it }
        // Fill the canvas, cropping the edges, like the iPhone's default fill mode.
        video += Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        // The clip's look, colour board and imported LUT (the iPhone's ClipEffects), as one LUT.
        video += clipLook(clip, canvas)
        val start = p.startTime(clip.id)
        val special = clipSpecial(clip)
        if (!special.isPlain) video += com.zellahq.app.depth.SpecialEffect(special) { us -> clockOffset + us / 1e6 }
        val keys = clip.keyframes
        if (!keys.isEmpty || !clip.transform.isIdentity || clip.opacity < 0.999) {
            video +=
                com.zellahq.app.depth.ClipTransformEffect(clip.transform, clip.opacity, keys) { us ->
                    (clockOffset + us / 1e6 - start).coerceAtLeast(0.0)
                }
        }
        return video
    }

    private fun backgroundEffect(
        p: Project,
        clip: Clip,
        clockOffset: Double,
    ): Effect? {
        val choice = clip.background ?: return null
        val video = store.resolve(clip.source, p.id)
        val matte = matteFile(video) ?: return null
        val start = p.startTime(clip.id)
        return com.zellahq.app.cutout.BackgroundEffect(
            matte,
            choice,
            { us -> clip.sourceTimeAt(clockOffset + us / 1e6 - start) },
            { us -> clockOffset + us / 1e6 },
        )
    }

    /** Slides the source under the crop so the speaker stays framed. Runs ahead of the fill, in full-frame terms. */
    private fun reframeEffect(
        p: Project,
        clip: Clip,
        canvas: Size,
        clockOffset: Double,
    ): Effect? {
        val plan = p.extras["reframe"] as? kotlinx.serialization.json.JsonObject ?: return null
        val raw = plan["keys"] as? kotlinx.serialization.json.JsonArray ?: return null
        val keys =
            raw.mapNotNull { e ->
                val o = e as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null

                fun v(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
                com.zellahq.core.auto.Reframe.Key(
                    v("time") ?: return@mapNotNull null,
                    v("centerX") ?: return@mapNotNull null,
                    v("centerY") ?: return@mapNotNull null,
                )
            }
        if (keys.isEmpty()) return null
        val size = Storage.videoSize(store.resolve(clip.source, p.id)) ?: return null
        val sourceAspect = size.first.toDouble() / size.second
        val canvasAspect = canvas.width.toDouble() / canvas.height
        val roomX = (1 - minOf(1.0, canvasAspect / sourceAspect)) / 2
        val roomY = (1 - minOf(1.0, sourceAspect / canvasAspect)) / 2
        if (roomX <= 0 && roomY <= 0) return null
        return androidx.media3.effect.MatrixTransformation { us ->
            val (cx, cy) = com.zellahq.core.auto.Reframe.centerAt(keys, clockOffset + us / 1e6)
            android.graphics.Matrix().apply {
                setTranslate(
                    ((0.5 - cx).coerceIn(-roomX, roomX) * 2).toFloat(),
                    ((cy - 0.5).coerceIn(-roomY, roomY) * 2).toFloat(),
                )
            }
        }
    }

    private fun audioEffects(
        p: Project,
        clip: Clip,
        index: Int,
    ): List<AudioProcessor> {
        val audio = ArrayList<AudioProcessor>()
        // A voice effect replaces the voice's sound on every clip, as the iPhone's project-wide setting does.
        val voiceFx = com.zellahq.app.sound.VoiceEffect.of((p.extras["voiceChanger"] as? kotlinx.serialization.json.JsonPrimitive)?.content)
        val fadeIn = if (index > 0) p.transitionOverlap(index - 1) else 0.0
        val fadeOut = p.transitionOverlap(index)
        val len = clip.timelineDuration
        // Voice clean-up on this clip (the iPhone's per-clip voiceNoise flag): resample to 48 kHz, then RNNoise.
        val cleaned = com.zellahq.app.sound.Sounds.isClean(clip)
        if (cleaned) {
            audio += androidx.media3.common.audio.SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
            audio += com.zellahq.app.sound.CleanupProcessor()
        }
        // The iPhone's voicePolish flag: the studio chain on the cleaned voice.
        if (com.zellahq.app.sound.Sounds.isPolished(clip)) audio += com.zellahq.app.sound.VoicePolishProcessor(p, denoised = cleaned)
        // Loudness belongs to the voice, not the mix: boosting everything lifted the music with it, 3 dB over
        // the target and far too loud against the speech. A polished clip took its boost inside the chain above.
        val polished = com.zellahq.app.sound.Sounds.isPolished(clip)
        val boost = if (polished) 1.0 else com.zellahq.core.audio.Loudness.dbToLinear(com.zellahq.app.sound.Sounds.boostDb(p))
        voiceFx?.let { audio += com.zellahq.app.sound.voiceEffectChain(it) }
        audio +=
            GainProcessor(softLimit = boost > 1.0) { tSec ->
                (clip.volume * boost * crossfadeGain(tSec, len, fadeIn, fadeOut)).toFloat()
            }
        return audio
    }

    /**
     * A project with transitions. The picture stays on one track: the clips with their overlaps taken
     * off, and between them each transition as a short video rendered ahead by [TransitionBaker]. A join
     * not rendered yet plays as a plain cut at its middle, so the length never changes. Media3's two
     * track path was tried for this and dropped after three separate faults: pictures drawn ahead of
     * their timestamps, random crashes on silent clips, and black frames at hard cuts.
     *
     * Sound overlaps at a transition, so it does use two tracks, sound only, which have none of those faults.
     */
    private fun transitionSequences(
        p: Project,
        canvas: Size,
        missing: MutableList<Clip>,
        needsReverse: MutableList<Clip>,
        needsBake: MutableList<Int>,
    ): MutableList<EditedMediaItemSequence> {
        val picture = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        val sounds = List(2) { EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)) }
        val soundAt = LongArray(2)
        val heard = BooleanArray(2)
        var track = 0

        fun segmentOf(i: Int) = if (p.transitionOverlap(i) > 0) transitionFile(p, i, canvas) else null
        p.clips.forEachIndexed { i, clip ->
            val overlapIn = if (i > 0) p.transitionOverlap(i - 1) else 0.0
            val overlapOut = p.transitionOverlap(i)
            val segmentIn = if (i > 0) segmentOf(i - 1) else null
            val segmentOut = segmentOf(i)
            // A rendered transition can be a frame shorter than asked. The clip after it starts that much
            // earlier, or each join would pull everything after it forward: 3.4 ms a join, measured.
            val cutHead = if (segmentIn != null) minOf(overlapIn, lengthOf(segmentIn)) else overlapIn / 2
            val cutTail = if (segmentOut != null) overlapOut else overlapOut / 2
            val body = resolved(p, clip, missing, needsReverse, cutHead, cutTail)
            val offsetUs = (sourceSpan(clip, body.isReversedCopy, cutHead) * 1e6).toLong()
            picture.addItem(
                EditedMediaItem
                    .Builder(body.media)
                    .setDurationUs(body.sourceUs)
                    .setRemoveAudio(true)
                    .setSpeed(speedProvider(clip, body.isReversedCopy, offsetUs))
                    .setEffects(Effects(emptyList(), videoEffects(p, clip, canvas)))
                    .build(),
            )
            if (overlapOut > 0) {
                if (segmentOut != null) {
                    val lengthUs = (minOf(overlapOut, lengthOf(segmentOut)) * 1e6).toLong()
                    picture.addItem(
                        EditedMediaItem
                            .Builder(
                                MediaItem
                                    .Builder()
                                    .setUri(Uri.fromFile(segmentOut))
                                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionUs(lengthUs).build())
                                    .build(),
                            ).setDurationUs(lengthUs)
                            .setRemoveAudio(true)
                            .build(),
                    )
                } else {
                    needsBake += i
                }
            }

            // Sound keeps the clip's whole range and crosses over on the other track at a transition.
            val whole = resolved(p, clip, missing, needsReverse)
            if (hasSound(whole.file)) {
                val startUs = (p.startTime(clip.id) * 1e6).toLong()
                if (startUs > soundAt[track]) sounds[track].addGap(startUs - soundAt[track])
                sounds[track].addItem(
                    EditedMediaItem
                        .Builder(whole.media)
                        .setDurationUs(whole.sourceUs)
                        .setRemoveVideo(true)
                        .setSpeed(speedProvider(clip, whole.isReversedCopy))
                        .setEffects(Effects(audioEffects(p, clip, i), emptyList()))
                        .build(),
                )
                soundAt[track] = startUs + (clip.timelineDuration * 1e6).toLong()
                heard[track] = true
            }
            if (overlapOut > 0) track = 1 - track
        }
        val out = mutableListOf(picture.build())
        sounds.forEachIndexed { t, b -> if (heard[t]) out += b.build() }
        return out
    }

    /**
     * The composition for one transition, to be rendered to a short video: the end of one clip on top
     * and the start of the next underneath, nothing else. Null while a source is not ready.
     */
    fun transitionComposition(
        p: Project,
        join: Int,
        canvas: Size,
    ): Composition? {
        val a = p.clips.getOrNull(join) ?: return null
        val b = p.clips.getOrNull(join + 1) ?: return null
        val t = p.transitionAfter(a.id) ?: return null
        val d = p.transitionOverlap(join)
        if (d <= 0) return null
        val missing = ArrayList<Clip>()
        val waiting = ArrayList<Clip>()
        val tail = resolved(p, a, missing, waiting, cutHead = a.timelineDuration - d)
        val head = resolved(p, b, missing, waiting, cutTail = b.timelineDuration - d)
        if (missing.isNotEmpty() || waiting.isNotEmpty()) return null
        val clock = p.startTime(b.id)
        val type = TransitionEffect.typeIndex(t.type)
        val window = TransitionEffect.Window(type, 0.0, d)

        fun item(
            r: Resolved,
            clip: Clip,
            effect: TransitionEffect,
            offsetUs: Long,
        ) = EditedMediaItemSequence
            .Builder(setOf(C.TRACK_TYPE_VIDEO))
            .addItem(
                EditedMediaItem
                    .Builder(r.media)
                    .setDurationUs(r.sourceUs)
                    .setRemoveAudio(true)
                    .setSpeed(speedProvider(clip, r.isReversedCopy, offsetUs))
                    .setEffects(Effects(emptyList(), videoEffects(p, clip, canvas, clock) + effect))
                    .build(),
            ).build()
        return Composition
            .Builder(
                listOf(
                    item(
                        tail,
                        a,
                        TransitionEffect(null, window, isTop = true),
                        (sourceSpan(a, tail.isReversedCopy, a.timelineDuration - d) * 1e6).toLong(),
                    ),
                    item(head, b, TransitionEffect(window, null, isTop = false), 0L),
                ),
            ).setVideoCompositorSettings(
                object : VideoCompositorSettings {
                    override fun getOutputSize(inputSizes: List<Size>) = canvas

                    override fun getOverlaySettings(
                        inputId: Int,
                        presentationTimeUs: Long,
                    ): OverlaySettings = StaticOverlaySettings.Builder().build()
                },
            ).build()
    }

    /** The rendered video for a transition, or null until it exists. Set by the app, which has the cache. */
    var transitionFile: (Project, Int, Size) -> File? = { _, _, _ -> null }

    private val lengths = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private fun lengthOf(file: File) = lengths.getOrPut(file.path) { Storage.durationSeconds(file) ?: Double.MAX_VALUE }

    private val soundKnown = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun hasSound(file: File): Boolean =
        soundKnown.getOrPut(file.path + "|" + file.length()) {
            if (!file.isFile) return@getOrPut false
            val ex = android.media.MediaExtractor()
            try {
                ex.setDataSource(file.path)
                (0 until ex.trackCount).any {
                    ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
            } catch (_: Exception) {
                false
            } finally {
                ex.release()
            }
        }

    private fun captionEffect(
        p: Project,
        canvas: Size,
    ): Effect? {
        val track = p.captions?.takeIf { it.cues.isNotEmpty() } ?: return null
        return androidx.media3.effect.OverlayEffect(
            listOf(com.zellahq.app.captions.CaptionOverlay(track, canvas.width, canvas.height, typeface(track))),
        )
    }

    private fun pipEffect(
        p: Project,
        canvas: Size,
    ): List<Effect> {
        val list = p.overlays
        if (list.isEmpty()) return emptyList()
        val pip =
            com.zellahq.app.depth.PipOverlay(list, { o ->
                o.source?.let {
                        s ->
                    store.resolve(s, p.id).takeIf { it.isFile }
                }
            }, canvas.width, canvas.height)
        return listOf(androidx.media3.effect.OverlayEffect(listOf(pip)))
    }

    /** The clip's effects beyond those its look already baked in. Set by the app, which has the looks. */
    var clipSpecial: (Clip) -> com.zellahq.core.project.SpecialEffects = { it.special }

    /** The worked-out person mask for a source file, or null until it is made. Set by the app. */
    var matteFile: (java.io.File) -> java.io.File? = { null }

    /** Blur boxes and drawn callouts, after the zoom so a box stays where it was put on the canvas. */
    var calloutEffects: (Project, Size) -> List<Effect> = { _, _ -> emptyList() }

    /** The titles over the whole video. Set by the app, which has the fonts. */
    var titleEffect: (Project, Size) -> Effect? = { _, _ -> null }

    /** Where the voice is, for ducking music. */
    var voiceWindows: (Project) -> List<com.zellahq.core.audio.Window> = { com.zellahq.core.audio.Voice.fromCaptions(it) }

    /** The file for a sound effect id, or null when it is unknown. */
    var soundEffectFile: (String) -> java.io.File? = { null }

    /** Colour effects for a clip. Set by the app (needs assets); empty in tests that do not set it. */
    var clipLook: (Clip, Size) -> List<Effect> = { _, _ -> emptyList() }

    /** The caption font: an imported one by name when the person picked it, else bold system text. */
    var typeface: (com.zellahq.core.project.CaptionTrack) -> android.graphics.Typeface = { android.graphics.Typeface.DEFAULT_BOLD }

    /** A constant speed, or the iPhone's ramp curves sampled in 12 steps like its CompositionBuilder. */
    private fun speedProvider(
        clip: Clip,
        reversedCopy: Boolean,
        offsetUs: Long = 0,
    ): SpeedProvider {
        val ramp = clip.speedRamp
        if (ramp == null || reversedCopy) return ConstantSpeed(clip.speed.toFloat())
        val n = 12
        val srcChunkUs = clip.trimmedDuration * 1e6 / n
        val mult = (0 until n).map { rampMultiplier(ramp, (it + 0.5) / n) }
        // Normalise so the clip keeps its length on the timeline (trimmed / speed).
        val avgInv = mult.sumOf { 1 / it } / n
        val speeds = mult.map { (it * clip.speed * avgInv).toFloat() }
        return object : SpeedProvider {
            override fun getSpeed(timeUs: Long) = speeds[((timeUs + offsetUs) / srcChunkUs).toInt().coerceIn(0, n - 1)]

            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
                val k = ((timeUs + offsetUs) / srcChunkUs).toInt() + 1
                // Rounded up: rounded down, the next change can land on this very time, and Media3 refuses that with a crash.
                return if (k >= n) C.TIME_UNSET else kotlin.math.ceil(k * srcChunkUs).toLong() - offsetUs
            }
        }
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long) = speed

        override fun getNextSpeedChangeTimeUs(timeUs: Long) = C.TIME_UNSET
    }

    /**
     * Zoom blocks: ease in to [ZoomBlock.scale] around the anchor, hold, ease out. Applied to the
     * finished frame, at timeline time, so it follows the same clock as captions.
     */
    private fun zoomEffect(
        blocks: List<ZoomBlock>,
        canvas: Size,
    ): Effect? {
        if (blocks.isEmpty()) return null
        return MatrixTransformation { timeUs ->
            val t = timeUs / 1e6
            val m = Matrix()
            val z = blocks.firstOrNull { t >= it.start && t <= it.end } ?: return@MatrixTransformation m
            val s = 1 + (z.scale - 1) * zoomEnvelope(t, z)
            // GL coordinates run -1..1; move the anchor to the centre, scale, move back.
            val ax = (z.anchorX * 2 - 1).toFloat()
            val ay = (1 - z.anchorY * 2).toFloat()
            m.postTranslate(-ax, -ay)
            m.postScale(s.toFloat(), s.toFloat())
            m.postTranslate(ax, ay)
            m
        }
    }
}

/** 0 at the block's start and end, 1 through the hold, smoothstep in and out. */
fun zoomEnvelope(
    t: Double,
    z: ZoomBlock,
): Double {
    val inEnd = z.start + z.inDur
    val holdEnd = inEnd + z.hold

    fun smooth(x: Double) = x.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    return when {
        t < z.start || t > z.end -> 0.0
        t < inEnd -> smooth((t - z.start) / z.inDur)
        t <= holdEnd -> 1.0
        else -> 1 - smooth((t - holdEnd) / z.outDur)
    }
}

/** Equal-power fade in and out over the transition overlaps at either end of a clip. */
fun crossfadeGain(
    t: Double,
    length: Double,
    fadeIn: Double,
    fadeOut: Double,
): Double {
    var g = 1.0
    if (fadeIn > 0 && t < fadeIn) g *= kotlin.math.sin(Math.PI / 2 * (t / fadeIn))
    if (fadeOut > 0 && t > length - fadeOut) g *= kotlin.math.cos(Math.PI / 2 * ((t - (length - fadeOut)) / fadeOut).coerceIn(0.0, 1.0))
    return g
}

/** Per-sample gain from a function of time in the item (seconds). 16-bit PCM in, 16-bit out. */
@OptIn(UnstableApi::class)
class GainProcessor(
    private val softLimit: Boolean = false,
    private val gainAt: (Double) -> Float,
) : BaseAudioProcessor() {
    private var frames = 0L

    private companion object {
        /** Samples above this bend smoothly towards full scale instead of clipping. */
        const val KNEE = 29_000f
        const val ROOM = 3_767f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(
                inputAudioFormat,
            )
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val ch = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        val bytes = inputBuffer.remaining()
        val out = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        var g = gainAt(frames.toDouble() / rate)
        var k = 0
        while (input.remaining() >= 2) {
            if (k % ch == 0) {
                if ((frames and 255L) == 0L) g = gainAt(frames.toDouble() / rate) // recompute every 256 frames
                frames++
            }
            var v = input.short * g
            // Above about -1 dBFS, bend instead of clip: a boost must never crackle.
            val size = kotlin.math.abs(v)
            if (softLimit && size > KNEE) v = kotlin.math.sign(v) * (KNEE + ROOM * kotlin.math.tanh((size - KNEE) / ROOM))
            out.putShort(v.toInt().coerceIn(-32768, 32767).toShort())
            k++
        }
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        frames = streamMetadata.positionOffsetUs * inputAudioFormat.sampleRate / 1_000_000
    }

    override fun onReset() {
        frames = 0
    }
}
