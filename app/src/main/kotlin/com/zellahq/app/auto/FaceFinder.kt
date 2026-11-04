package com.zellahq.app.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.media.MediaMetadataRetriever
import com.zellahq.app.editor.Storage
import com.zellahq.core.auto.FaceSample
import com.zellahq.core.project.Project
import com.zellahq.core.project.clipAt
import com.zellahq.core.project.duration
import java.io.File

/**
 * Finds the largest face in a picture, with Android's own detector. It needs no library and no model,
 * and nothing leaves the phone. MediaPipe was tried first and dropped: it crashed the process with an
 * illegal instruction on the emulator, a native crash no code can catch, and its package pulls in
 * Google's data transport libraries, which has no place in an app that promises no analytics.
 */
class FaceFinder : AutoCloseable {
    data class Face(val centerX: Double, val centerY: Double, val height: Double)

    fun find(picture: Bitmap): Face? {
        // The detector wants an even width and 16-bit colour.
        val w = picture.width and 1.inv()
        if (w < 2 || picture.height < 2) return null
        val copy = Bitmap.createBitmap(w, picture.height, Bitmap.Config.RGB_565)
        Canvas(copy).drawBitmap(picture, 0f, 0f, null)
        val found = arrayOfNulls<android.media.FaceDetector.Face>(MOST)
        val n = android.media.FaceDetector(w, copy.height, MOST).findFaces(copy, found)
        copy.recycle()
        val largest =
            found.take(n).filterNotNull().filter { it.confidence() >= MIN_CONFIDENCE }.maxByOrNull { it.eyesDistance() } ?: return null
        val mid = PointF()
        largest.getMidPoint(mid)
        // From the eyes: a face runs about 2.6 eye distances tall, and its middle sits just below the eyes.
        val eyes = largest.eyesDistance()
        return Face(mid.x.toDouble() / w, (mid.y + eyes * 0.25) / picture.height.toDouble(), eyes * 2.6 / picture.height.toDouble())
    }

    override fun close() = Unit

    private companion object {
        const val MOST = 4
        const val MIN_CONFIDENCE = 0.3f
    }
}

object FaceSampler {
    /** Face positions along the timeline, in source frame fractions, sampled evenly and capped at [most]. */
    fun sample(
        context: Context,
        p: Project,
        from: Double = 0.0,
        to: Double = p.duration,
        fps: Double = 2.0,
        most: Int = 120,
        stopped: () -> Boolean = { false },
    ): List<FaceSample> {
        val store = Storage.store(context)
        val span = (to - from).coerceAtLeast(0.0)
        if (span <= 0) return emptyList()
        val count = minOf(most, maxOf(1, (span * fps).toInt()))
        val out = ArrayList<FaceSample>()
        val readers = HashMap<String, MediaMetadataRetriever>()
        try {
            FaceFinder().use { finder ->
                for (i in 0 until count) {
                    if (stopped()) break
                    val t = from + span * (i + 0.5) / count
                    val (clip, clipStart) = p.clipAt(t) ?: continue
                    if (clip.reversed) continue
                    val file: File = store.resolve(clip.source, p.id)
                    if (!file.isFile) continue
                    val mmr = readers.getOrPut(file.path) { MediaMetadataRetriever().apply { setDataSource(file.path) } }
                    val sourceTime = clip.trimStart + (t - clipStart) * clip.speed
                    val frame =
                        runCatching {
                            mmr.getScaledFrameAtTime((sourceTime * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
                        }.getOrNull() ?: continue
                    finder.find(frame)?.let { out += FaceSample(t, it.centerX, it.centerY, it.height) }
                }
            }
        } finally {
            readers.values.forEach { runCatching { it.release() } }
        }
        return out
    }
}
