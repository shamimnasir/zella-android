package com.zellahq.app.sound

import android.content.Context
import com.zellahq.app.captions.AudioExtractor
import com.zellahq.app.editor.Storage
import com.zellahq.core.project.Beats
import com.zellahq.core.project.Clip
import com.zellahq.core.project.KeyframeTrack
import com.zellahq.core.project.Project
import com.zellahq.core.project.keyframes
import com.zellahq.core.project.startTime
import com.zellahq.core.project.withKeyframes
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Small picture punches on the beat, the iPhone's beat sync. The beat lives in the music when there
 * is some, otherwise in the clip's own sound. Punches go on as scale keyframes, per clip, in clip time,
 * so they travel with every edit.
 */
object BeatSync {
    private fun mono22k(file: File): FloatArray? {
        val pcm = AudioExtractor.decode(file, 0.0, 1e9) ?: return null
        return resample(pcm.mono, pcm.rate)
    }

    private fun resample(
        x: FloatArray,
        from: Int,
    ): FloatArray {
        if (from == Beats.RATE) return x
        val ratio = from.toDouble() / Beats.RATE
        return FloatArray((x.size / ratio).toInt()) { i ->
            val at = i * ratio
            val a = at.toInt().coerceAtMost(x.size - 1)
            val b = (a + 1).coerceAtMost(x.size - 1)
            x[a] + (x[b] - x[a]) * (at - a).toFloat()
        }
    }

    fun apply(
        context: Context,
        p: Project,
        strength: Double,
    ): Pair<Project, Int> {
        val store = Storage.store(context)
        val bed = p.musicBeds?.firstOrNull()
        val peak = Beats.peak(strength)
        var total = 0
        // Music: its beats, moved onto the timeline, then onto each clip.
        val onTimeline: List<Double>? =
            bed?.let { b ->
                val file = store.resolve(b.source, p.id).takeIf { it.isFile } ?: return@let null
                mono22k(file)?.let { pcm -> Beats.detect(pcm).map { it + b.start } }
            }
        val clips =
            p.clips.map { c: Clip ->
                val start = p.startTime(c.id)
                val local =
                    if (onTimeline != null) {
                        onTimeline.map { it - start }.filter { it >= 0 && it <= c.timelineDuration }
                    } else {
                        val file = store.resolve(c.source, p.id).takeIf { it.isFile }
                        val pcm = file?.let { mono22k(it) }
                        if (pcm == null) {
                            emptyList()
                        } else {
                            val speed = if (c.speed > 0) c.speed else 1.0
                            Beats.detect(pcm).map { (it - c.trimStart) / speed }.filter { it >= 0 && it <= c.timelineDuration }
                        }
                    }
                total += local.size
                if (local.isEmpty()) c else c.withKeyframes(c.keyframes.copy(scale = Beats.scaleTrack(local, peak, c.timelineDuration)))
            }
        val settings =
            JsonObject(
                mapOf(
                    "strength" to JsonPrimitive(strength),
                    "sensKick" to JsonPrimitive(0.5),
                    "sensSnare" to JsonPrimitive(0.5),
                    "sensHats" to JsonPrimitive(0.5),
                ),
            )
        return p.copy(clips = clips, extras = JsonObject(p.extras + ("beatSync" to settings))) to total
    }

    fun clear(p: Project) =
        p.copy(
            clips = p.clips.map { it.withKeyframes(it.keyframes.copy(scale = KeyframeTrack())) },
            extras = JsonObject(p.extras - "beatSync"),
        )
}
