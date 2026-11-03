package com.zellahq.app.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.zellahq.core.SampleJournal
import java.io.File
import java.nio.ByteBuffer

/** Converts between MediaFormat and the plain map a [SampleJournal] stores. */
object JournalFormats {
    /** Not a MediaFormat key the muxer reads; the remuxer turns it into setOrientationHint. */
    const val ROTATION = "zella-rotation-degrees"
    private val keys =
        listOf(
            MediaFormat.KEY_MIME,
            MediaFormat.KEY_WIDTH,
            MediaFormat.KEY_HEIGHT,
            MediaFormat.KEY_SAMPLE_RATE,
            MediaFormat.KEY_CHANNEL_COUNT,
            MediaFormat.KEY_BIT_RATE,
            MediaFormat.KEY_PROFILE,
            MediaFormat.KEY_LEVEL,
        )

    fun toMap(f: MediaFormat): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        for (k in keys) {
            if (!f.containsKey(k)) continue
            when (f.getValueTypeForKey(k)) {
                MediaFormat.TYPE_STRING -> m[k] = f.getString(k)!!
                MediaFormat.TYPE_INTEGER -> m[k] = f.getInteger(k)
                MediaFormat.TYPE_LONG -> m[k] = f.getLong(k)
            }
        }
        for (k in listOf("csd-0", "csd-1", "csd-2")) {
            f.getByteBuffer(k)?.let { b -> m[k] = ByteArray(b.remaining()).also { b.duplicate().get(it) } }
        }
        return m
    }

    fun fromMap(m: Map<String, Any>): MediaFormat {
        val f = MediaFormat()
        for ((k, v) in m) {
            when (v) {
                is String -> f.setString(k, v)
                is Int -> f.setInteger(k, v)
                is Long -> f.setLong(k, v)
                is ByteArray -> f.setByteBuffer(k, ByteBuffer.wrap(v))
            }
        }
        return f
    }
}

data class RemuxResult(val samples: Int, val tornBytes: Long, val millis: Long)

/**
 * Writes a normal MP4 from a [SampleJournal]. No re-encoding: every sample is copied with its exact
 * timestamp, so picture and sound stay where they were captured. Used by a clean stop and by
 * crash recovery alike, so the two paths cannot disagree.
 */
object Remuxer {
    fun toMp4(
        journal: File,
        out: File,
    ): RemuxResult {
        val t0 = System.nanoTime()
        // Pass 1: tracks only. MediaMuxer needs every track before the first sample.
        val contents = SampleJournal.read(journal)
        require(contents.tracks.isNotEmpty() && contents.sampleCount > 0) { "nothing recorded in ${journal.name}" }
        val tmp = File(out.parentFile, out.name + ".part").apply { delete() }
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val ids = HashMap<Int, Int>()
        for (t in contents.tracks) {
            (t.format[JournalFormats.ROTATION] as? Int)?.let { muxer.setOrientationHint(it) }
            ids[t.id] = muxer.addTrack(JournalFormats.fromMap(t.format - JournalFormats.ROTATION))
        }
        muxer.start()
        val info = MediaCodec.BufferInfo()
        var written = 0
        try {
            SampleJournal.read(journal, onSample = { s ->
                val id = ids[s.track] ?: return@read
                info.set(0, s.data.size, s.ptsUs, s.flags)
                muxer.writeSampleData(id, ByteBuffer.wrap(s.data), info)
                written++
            })
            muxer.stop()
        } finally {
            muxer.release()
        }
        // Rename last, so a crash during the copy never leaves a half-written MP4 under the real name.
        check(tmp.renameTo(out)) { "rename failed" }
        return RemuxResult(written, contents.tornBytes, (System.nanoTime() - t0) / 1_000_000)
    }
}
