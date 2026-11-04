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
    data class Built(val composition: Composition, val missing: List<Clip>, val needsReverse: List<Clip>)

    fun build(
        p: Project,
        canvas: Size = canvasSize(p),
    ): Built {
        val missing = ArrayList<Clip>()
        val needsReverse = ArrayList<Clip>()
        val fade = !p.transitions.isNullOrEmpty() && p.clips.size > 1
        val tracks = if (fade) listOf(ArrayList<Any>(), ArrayList()) else listOf(ArrayList<Any>())
        val cursor = LongArray(tracks.size)

        p.clips.forEachIndexed { i, clip ->
            val track = if (fade) i % 2 else 0
            val startUs = (p.startTime(clip.id) * 1e6).toLong()
            if (startUs > cursor[track]) tracks[track] += startUs - cursor[track] // a gap
            val item = item(p, clip, i, canvas, missing, needsReverse)
            tracks[track] += item
            cursor[track] = startUs + (clip.timelineDuration * 1e6).toLong()
        }

        // Media3 takes the video's length from the FIRST track. With two clips and a fade, track 0
        // ends with clip 1 and clip 2 was cut off (export of a real edit, 2026-11-04). Pad track 0
        // to the full timeline; its gaps are fully transparent in CrossfadeSettings.
        val totalUs = (p.duration * 1e6).toLong()
        if (fade && cursor[0] < totalUs) tracks[0] += totalUs - cursor[0]

        val sequences =
            tracks.map { list ->
                // Declaring both track types makes Media3 fill silence for a clip without sound (a reversed copy).
                val b =
                    EditedMediaItemSequence.Builder(
                        setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO),
                    )
                list.forEach { if (it is Long) b.addGap(it) else b.addItem(it as EditedMediaItem) }
                b.build()
            }.toMutableList()

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

        // Each sound effect on its own short audio track, at its time, cut off at the end of the video.
        p.soundBites?.forEach { bite ->
            val file = soundEffectFile(bite.effectId) ?: return@forEach
            if (bite.atTime >= total) return@forEach
            val len = com.zellahq.app.editor.Storage.durationSeconds(file) ?: 1.0
            val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            if (bite.atTime > 0) b.addGap((bite.atTime * 1e6).toLong())
            val media =
                MediaItem
                    .Builder()
                    .setUri(Uri.fromFile(file))
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setEndPositionUs(
                            (minOf(len, total - bite.atTime) * 1e6).toLong(),
                        ).build(),
                    ).build()
            b.addItem(
                EditedMediaItem
                    .Builder(media)
                    .setDurationUs((len * 1e6).toLong())
                    .setRemoveVideo(true)
                    .setEffects(Effects(listOf(GainProcessor { bite.volume.coerceIn(0.0, 1.0).toFloat() }), emptyList()))
                    .build(),
            )
            sequences += b.build()
        }

        val composition =
            Composition
                .Builder(sequences)
                // Zoom first, captions after: a zoom must not enlarge the captions.
                .setEffects(
                    Effects(
                        // Evened-out loudness for the whole video, with a soft limit so a boost never clips.
                        listOfNotNull(loudness(p)),
                        listOfNotNull(zoomEffect(p.zoomBlocks.orEmpty(), canvas), titleEffect(p, canvas), captionEffect(p, canvas)),
                    ),
                )
                .apply { if (fade) setVideoCompositorSettings(CrossfadeSettings(p, canvas)) }
                .build()
        return Built(composition, missing, needsReverse)
    }

    private fun item(
        p: Project,
        clip: Clip,
        index: Int,
        canvas: Size,
        missing: MutableList<Clip>,
        needsReverse: MutableList<Clip>,
    ): EditedMediaItem {
        val forward = store.resolve(clip.source, p.id)
        val reversed = if (clip.reversed) reversedFile(clip) else null
        if (clip.reversed && reversed == null) needsReverse += clip
        val file = reversed ?: forward
        if (!file.isFile) missing += clip
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
        val video = ArrayList<Effect>()
        // Fill the canvas, cropping the edges, like the iPhone's default fill mode.
        video += Presentation.createForWidthAndHeight(canvas.width, canvas.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        // The clip's look, colour board and imported LUT (the iPhone's ClipEffects), as one LUT.
        video += clipLook(clip, canvas)
        val audio = ArrayList<AudioProcessor>()
        val fadeIn = if (index > 0) p.transitionOverlap(index - 1) else 0.0
        val fadeOut = p.transitionOverlap(index)
        val len = clip.timelineDuration
        // Voice clean-up on this clip (the iPhone's per-clip voiceNoise flag): resample to 48 kHz, then RNNoise.
        if ((clip.extras["voiceNoise"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true") {
            audio += androidx.media3.common.audio.SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
            audio += com.zellahq.app.sound.CleanupProcessor()
        }
        audio += GainProcessor { tSec -> (clip.volume * crossfadeGain(tSec, len, fadeIn, fadeOut)).toFloat() }
        // The preview player needs each source's full length up front; export works without it, so
        // only the preview crashed when this was missing (lessons.md, 2026-11-04).
        val sourceUs = ((if (reversed != null) clip.trimmedDuration else clip.sourceDuration) * 1e6).toLong()
        return EditedMediaItem
            .Builder(media)
            .setDurationUs(sourceUs)
            .setSpeed(speedProvider(clip, reversed != null))
            .setEffects(Effects(audio, video))
            .build()
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

    private fun loudness(p: Project): AudioProcessor? {
        val key = com.zellahq.app.sound.Sounds.LOUDNESS_KEY
        val db = (p.extras[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
        if (db == null || kotlin.math.abs(db) < 0.1) return null
        val g = com.zellahq.core.audio.Loudness.dbToLinear(db).toFloat()
        return GainProcessor(softLimit = true) { g }
    }

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
     * Cross-fade. Measured on the emulator (ExportRenderTest, 2026-11-04): track 0 draws ON TOP of
     * track 1. So the bottom track is always opaque where it has a clip, and only the top track
     * carries the fade: rising when its clip comes in, falling when its clip goes out. Giving the
     * incoming clip the alpha instead rendered no fade at all when the incoming clip was underneath.
     */
    private inner class CrossfadeSettings(
        private val p: Project,
        private val canvas: Size,
    ) : VideoCompositorSettings {
        override fun getOutputSize(inputSizes: List<Size>) = canvas

        override fun getOverlaySettings(
            inputId: Int,
            presentationTimeUs: Long,
        ): OverlaySettings {
            val t = presentationTimeUs / 1e6
            // Track 0 holds clips 0, 2, 4; track 1 holds 1, 3, 5.
            val playing =
                p.clips.withIndex().firstOrNull { (i, c) ->
                    i % 2 == inputId && t >= p.startTime(c.id) && t < p.startTime(c.id) + c.timelineDuration
                }
            val alpha =
                when {
                    playing == null -> 0f
                    inputId != 0 -> 1f // the bottom track never fades; the top one blends over it
                    else -> {
                        val (i, c) = playing
                        val s = p.startTime(c.id)
                        val e = s + c.timelineDuration
                        val inD = if (i > 0) p.transitionOverlap(i - 1) else 0.0
                        val outD = p.transitionOverlap(i)
                        when {
                            inD > 0 && t < s + inD -> ((t - s) / inD).toFloat()
                            outD > 0 && t > e - outD -> ((e - t) / outD).toFloat()
                            else -> 1f
                        }
                    }
                }
            return StaticOverlaySettings.Builder().setAlphaScale(alpha).build()
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
