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
import com.zellahq.core.project.duration
import com.zellahq.core.project.rampMultiplier
import com.zellahq.core.project.startTime
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
private const val BLACK_VIDEO_MS = 10_000L

@OptIn(UnstableApi::class)
class CompositionBuilder(
    private val store: ProjectStore,
    /** Reversed copies, made ahead by [ReverseRenderer]. A clip without one plays forward. */
    private val reversedFile: (Clip) -> File? = { null },
) {
    data class Built(val composition: Composition, val missing: List<Clip>, val needsReverse: List<Clip>)

    fun build(
        p: Project,
        canvas: Size = canvasSize(p),
    ): Built {
        val missing = ArrayList<Clip>()
        val needsReverse = ArrayList<Clip>()
        val fade = !p.transitions.isNullOrEmpty() && p.clips.size > 1
        val sequences =
            if (fade) {
                twoTrackSequences(p, canvas, missing, needsReverse)
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
                        listOfNotNull(zoomEffect(p.zoomBlocks.orEmpty(), canvas), titleEffect(p, canvas), captionEffect(p, canvas)),
                    ),
                )
                .apply { if (fade) setVideoCompositorSettings(CrossfadeSettings(p, canvas)) }
                .build()
        return Built(composition, missing, needsReverse)
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
            .setEffects(Effects(audioEffects(p, clip, index), videoEffects(p, clip, index, canvas)))
            .build()
    }

    private class Resolved(val media: MediaItem, val file: File, val sourceUs: Long, val isReversedCopy: Boolean)

    private fun resolved(
        p: Project,
        clip: Clip,
        missing: MutableList<Clip>,
        needsReverse: MutableList<Clip>,
    ): Resolved {
        val forward = store.resolve(clip.source, p.id)
        val reversed = if (clip.reversed) reversedFile(clip) else null
        if (clip.reversed && reversed == null && clip !in needsReverse) needsReverse += clip
        val file = reversed ?: forward
        if (!file.isFile && clip !in missing) missing += clip
        // A reversed copy holds only the trimmed range, already backwards, starting at zero.
        val (fromUs, toUs) =
            if (reversed != null) {
                0L to (clip.trimmedDuration * 1e6).toLong()
            } else {
                (clip.trimStart * 1e6).toLong() to (clip.trimEnd * 1e6).toLong()
            }
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

    private fun videoEffects(
        p: Project,
        clip: Clip,
        index: Int,
        canvas: Size,
    ): List<Effect> {
        val video = ArrayList<Effect>()
        reframeEffect(p, clip, canvas)?.let { video += it }
        // Fill the canvas, cropping the edges, like the iPhone's default fill mode.
        video += Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        // The clip's look, colour board and imported LUT (the iPhone's ClipEffects), as one LUT.
        video += clipLook(clip, canvas)
        transitionEffect(p, clip, index)?.let { video += it }
        return video
    }

    /** Slides the source under the crop so the speaker stays framed. Runs ahead of the fill, in full-frame terms. */
    private fun reframeEffect(
        p: Project,
        clip: Clip,
        canvas: Size,
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
            val (cx, cy) = com.zellahq.core.auto.Reframe.centerAt(keys, us / 1e6)
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
        audio +=
            GainProcessor(softLimit = boost > 1.0) { tSec ->
                (clip.volume * boost * crossfadeGain(tSec, len, fadeIn, fadeOut)).toFloat()
            }
        return audio
    }

    /**
     * Clips with a transition need two picture tracks, so neighbours can overlap. Picture and sound go
     * on separate tracks here. When a track's first item has no sound Media3 invents a silent audio
     * track for it, and crashes if the other track has not started yet (SequenceAssetLoader:343), which
     * made exports fail at random on silent clips. Tracks with only one kind of media never go there.
     */
    private fun twoTrackSequences(
        p: Project,
        canvas: Size,
        missing: MutableList<Clip>,
        needsReverse: MutableList<Clip>,
    ): MutableList<EditedMediaItemSequence> {
        val pictures = List(2) { EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)) }
        val sounds = List(2) { EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)) }
        val pictureAt = LongArray(2)
        val soundAt = LongArray(2)
        val heard = BooleanArray(2)
        val trackOf = trackLayout(p)
        p.clips.forEachIndexed { i, clip ->
            val t = trackOf[i]
            val startUs = (p.startTime(clip.id) * 1e6).toLong()
            val lengthUs = (clip.timelineDuration * 1e6).toLong()
            val r = resolved(p, clip, missing, needsReverse)

            val gapMs = (startUs - pictureAt[t]) / 1000
            if (gapMs > 0) {
                addGap(pictures[t], gapMs, canvas)
                pictureAt[t] += gapMs * 1000
            }
            pictures[t].addItem(
                EditedMediaItem
                    .Builder(r.media)
                    .setDurationUs(r.sourceUs)
                    .setRemoveAudio(true)
                    .setSpeed(speedProvider(clip, r.isReversedCopy))
                    .setEffects(Effects(emptyList(), videoEffects(p, clip, i, canvas)))
                    .build(),
            )
            pictureAt[t] += lengthUs

            if (hasSound(r.file)) {
                if (startUs > soundAt[t]) sounds[t].addGap(startUs - soundAt[t])
                sounds[t].addItem(
                    EditedMediaItem
                        .Builder(r.media)
                        .setDurationUs(r.sourceUs)
                        .setRemoveVideo(true)
                        .setSpeed(speedProvider(clip, r.isReversedCopy))
                        .setEffects(Effects(audioEffects(p, clip, i), emptyList()))
                        .build(),
                )
                soundAt[t] = startUs + lengthUs
                heard[t] = true
            }
        }
        // Media3 takes the video's length from the FIRST track. With two clips and a fade, track 0
        // ends with clip 1 and clip 2 was cut off (export of a real edit, 2026-11-04). Pad track 0
        // to the full timeline; its gaps are fully transparent in CrossfadeSettings.
        val totalUs = (p.duration * 1e6).toLong()
        if (pictureAt[0] < totalUs) {
            val ms = (totalUs - pictureAt[0]) / 1000
            if (ms > 0) addGap(pictures[0], ms, canvas)
        }
        val out = pictures.map { it.build() }.toMutableList()
        sounds.forEachIndexed { t, b -> if (heard[t]) out += b.build() }
        return out
    }

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

    /**
     * Empty time on a video track, as a short black video at the canvas size. Media3's own gap frames
     * are 1 by 1, and on two tracks every change of frame size rebuilds the texture pool under frames
     * still waiting to be composited: from then on each picture was drawn 2 to 4 frames early, and a
     * clip's last frames came out black (timestamps burnt in before and after the compositor showed
     * it). A black image item fixed that but then failed exports at random with "The surface has been
     * released" when the input switched between image and video, so this stays video all the way.
     */
    private fun addGap(
        track: EditedMediaItemSequence.Builder,
        totalMs: Long,
        canvas: Size,
    ) {
        val file = blackVideo()
        var left = totalMs
        while (left > 0) {
            val ms = minOf(left, BLACK_VIDEO_MS)
            val media =
                MediaItem
                    .Builder()
                    .setUri(Uri.fromFile(file))
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(ms).build())
                    .build()
            track.addItem(
                EditedMediaItem
                    .Builder(media)
                    .setDurationUs(BLACK_VIDEO_MS * 1000)
                    .setEffects(
                        Effects(
                            emptyList(),
                            listOf(
                                Presentation.createForWidthAndHeight(
                                    canvas.width,
                                    canvas.height,
                                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                                ),
                            ),
                        ),
                    ).build(),
            )
            left -= ms
        }
    }

    /** The black video to fill empty time with. Set by the app, which has the assets. */
    var blackVideo: () -> File = { error("set by appComposition") }

    /**
     * Which picture track each clip goes on. A plain cut stays on the same track and only a transition
     * changes it, so a long take with a few dissolves does not start a decoder for every empty stretch.
     */
    private fun trackLayout(p: Project): IntArray {
        val out = IntArray(p.clips.size)
        for (i in 1 until p.clips.size) {
            out[i] = if (p.transitionAfter(p.clips[i - 1].id) != null) 1 - out[i - 1] else out[i - 1]
        }
        return out
    }

    private fun transitionEffect(
        p: Project,
        clip: Clip,
        index: Int,
    ): Effect? {
        val start = p.startTime(clip.id)
        val incoming =
            p.clips.getOrNull(index - 1)?.let { prev ->
                val t = p.transitionAfter(prev.id) ?: return@let null
                val d = p.transitionOverlap(index - 1)
                if (d > 0) TransitionEffect.Window(TransitionEffect.typeIndex(t.type), start, d) else null
            }
        val outgoing =
            p.transitionAfter(clip.id)?.let { t ->
                val d = p.transitionOverlap(index)
                if (d > 0) TransitionEffect.Window(TransitionEffect.typeIndex(t.type), start + clip.timelineDuration - d, d) else null
            }
        if (incoming == null && outgoing == null) return null
        return TransitionEffect(incoming, outgoing, isTop = trackLayout(p)[index] == 0)
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
            override fun getSpeed(timeUs: Long) = speeds[(timeUs / srcChunkUs).toInt().coerceIn(0, n - 1)]

            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
                val k = (timeUs / srcChunkUs).toInt() + 1
                return if (k >= n) C.TIME_UNSET else (k * srcChunkUs).toLong()
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

    /**
     * Two tracks. Measured on the emulator (ExportRenderTest, 2026-11-04): track 0 draws ON TOP of
     * track 1. Each transition's shape lives in [TransitionEffect], which writes it into the top
     * clip's alpha; a track with nothing on it at this moment is fully transparent.
     */
    private inner class CrossfadeSettings(
        private val p: Project,
        private val canvas: Size,
    ) : VideoCompositorSettings {
        private val layout = trackLayout(p)

        override fun getOutputSize(inputSizes: List<Size>) = canvas

        override fun getOverlaySettings(
            inputId: Int,
            presentationTimeUs: Long,
        ): OverlaySettings {
            val t = presentationTimeUs / 1e6
            val playing =
                p.clips.withIndex().any { (i, c) ->
                    layout[i] == inputId && t >= p.startTime(c.id) && t < p.startTime(c.id) + c.timelineDuration
                }
            return StaticOverlaySettings.Builder().setAlphaScale(if (playing) 1f else 0f).build()
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
