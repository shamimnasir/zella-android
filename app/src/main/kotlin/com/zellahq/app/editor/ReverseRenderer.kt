package com.zellahq.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.zellahq.core.project.Clip
import com.zellahq.core.project.ProjectStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes the backwards copy a reversed clip plays from. Media3 cannot play video backwards, and the
 * iPhone pre-renders too. Frames are read 8 at a time from the end of the trimmed range, drawn in
 * reverse into an encoder and given evenly spaced times, so memory holds one chunk at a time.
 * The copy has no sound, as on the iPhone (reversed speech is noise).
 *
 * Copies are cached by source and trim, so undo and redo reuse them.
 */
object ReverseRenderer {
    private const val CHUNK = 8
    private const val INPUT_TRIES = 1000
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun key(clip: Clip) = "${clip.source}|${clip.trimStart}|${clip.trimEnd}".hashCode().toUInt().toString(16)

    fun file(
        context: Context,
        projectId: String,
        clip: Clip,
    ) = File(context.cacheDir, "reversed/$projectId/${key(clip)}.mp4")

    fun cached(
        context: Context,
        store: ProjectStore,
        projectId: String,
        clip: Clip,
    ): File? = file(context, projectId, clip).takeIf { it.isFile }

    /** Blocking. Returns the reversed file, or null when the source cannot be read. */
    fun render(
        context: Context,
        store: ProjectStore,
        projectId: String,
        clip: Clip,
        onProgress: (Float) -> Unit = {},
    ): File? {
        val out = file(context, projectId, clip)
        if (out.isFile) return out
        // One at a time per file. An export asking while the preview is making it waits, then uses it.
        synchronized(locks.computeIfAbsent(out.path) { Any() }) {
            if (out.isFile) return out
            out.parentFile!!.mkdirs()
            val src = store.resolve(clip.source, projectId)
            val tmp = File(out.parentFile, out.name + ".part")
            val ok =
                runCatching {
                    reverse(src, clip.trimStart, clip.trimEnd, tmp, onProgress)
                }.onFailure { Log.e("ZellaReverse", "reverse", it) }.getOrDefault(false)
            return if (ok && tmp.renameTo(out)) out else null.also { tmp.delete() }
        }
    }

    /** Every video frame's time in the file, in display order. */
    private fun sampleTimes(src: File): LongArray {
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(src.path)
            val t =
                (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return LongArray(0)
            ex.selectTrack(t)
            val out = ArrayList<Long>()
            while (ex.sampleTime >= 0) {
                out += ex.sampleTime
                if (!ex.advance()) break
            }
            return out.sorted().toLongArray()
        } finally {
            ex.release()
        }
    }

    private fun reverse(
        src: File,
        fromSec: Double,
        toSec: Double,
        out: File,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(src.path)
        try {
            // The file's own frame times. Screen recordings vary their rate, so an average lands on the wrong frames.
            val times = sampleTimes(src)
            if (times.isEmpty()) return false
            val fromUs = (fromSec * 1e6).toLong()
            val toUs = (toSec * 1e6).toLong()
            val first = times.indexOfFirst { it >= fromUs - 500 }.coerceAtLeast(0)
            val last = times.indexOfLast { it < toUs - 500 }.coerceAtLeast(first)
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
            val probe = mmr.getFrameAtIndex(first, params) ?: return false
            val w = probe.width / 2 * 2
            val h = probe.height / 2 * 2
            probe.recycle()

            // Going backwards, each frame comes as long after the last as the original gap between them.
            val outTimes = LongArray(last - first + 1) { k -> times[last] - times[last - k] }
            val rate = if (outTimes.size > 1 && outTimes.last() > 0) (outTimes.size - 1) * 1e6 / outTimes.last() else 30.0
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val fmt =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    // Frames are handed over one by one with their exact time. A drawing surface skipped frames under
                    // pressure and gave no way to say when each belonged, which made a reversed clip a third too short.
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, rate.toInt().coerceAtLeast(1))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                }
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val written = java.util.concurrent.atomic.AtomicInteger()
            var drained = false
            val stopDrain = booleanArrayOf(false)
            val drain =
                Thread {
                    val info = MediaCodec.BufferInfo()
                    var track = -1
                    while (!stopDrain[0]) {
                        val i = runCatching { enc.dequeueOutputBuffer(info, 10_000) }.getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                        if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(enc.outputFormat)
                            muxer.start()
                        } else if (i >= 0) {
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                                muxer.writeSampleData(track, enc.getOutputBuffer(i)!!, info)
                                written.incrementAndGet()
                            }
                            enc.releaseOutputBuffer(i, false)
                            if (eos) break
                        }
                    }
                }.apply { start() }

            try {
                val row = IntArray(w)
                var end = last
                val total = last - first + 1
                var done = 0
                while (end >= first) {
                    val start = maxOf(first, end - CHUNK + 1)
                    val frames = mmr.getFramesAtIndex(start, end - start + 1, params)
                    for (bmp in frames.asReversed()) {
                        val input = nextInput(enc)
                        val image = enc.getInputImage(input)!!
                        fillYuv(bmp, image, row)
                        enc.queueInputBuffer(input, 0, w * h * 3 / 2, outTimes[done], 0)
                        bmp.recycle()
                        done++
                    }
                    onProgress(done.toFloat() / total)
                    end = start - 1
                }
                enc.queueInputBuffer(nextInput(enc), 0, 0, outTimes.last() + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain.join(10_000)
                drained = !drain.isAlive
                return written.get() > 0 && drained
            } finally {
                // A failure part way must not leave the encoder running or its thread spinning.
                if (!drained) drain.interrupt()
                stopDrain[0] = true
                runCatching { drain.join(2_000) }
                runCatching { muxer.stop() }
                runCatching { muxer.release() }
                runCatching { enc.stop() }
                runCatching { enc.release() }
            }
        } finally {
            mmr.release()
        }
    }

    private fun nextInput(enc: MediaCodec): Int {
        repeat(INPUT_TRIES) {
            val i = enc.dequeueInputBuffer(10_000)
            if (i >= 0) return i
        }
        error("the encoder stopped taking frames")
    }

    /** RGB to the encoder's own YUV layout, BT.709 limited range as the format above says. */
    private fun fillYuv(
        bmp: Bitmap,
        image: android.media.Image,
        row: IntArray,
    ) {
        val (yp, up, vp) = image.planes
        val y = yp.buffer
        val u = up.buffer
        val v = vp.buffer
        val w = image.width
        for (line in 0 until image.height) {
            bmp.getPixels(row, 0, w, 0, line.coerceAtMost(bmp.height - 1), w, 1)
            val yRow = line * yp.rowStride
            val halfLine = line / 2
            for (x in 0 until w) {
                val p = row[x]
                val r = p shr 16 and 255
                val g = p shr 8 and 255
                val b = p and 255
                y.put(yRow + x * yp.pixelStride, (((47 * r + 157 * g + 16 * b + 128) shr 8) + 16).toByte())
                if (line % 2 == 0 && x % 2 == 0) {
                    val half = x / 2
                    u.put(halfLine * up.rowStride + half * up.pixelStride, (((-26 * r - 87 * g + 112 * b + 128) shr 8) + 128).toByte())
                    v.put(halfLine * vp.rowStride + half * vp.pixelStride, (((112 * r - 102 * g - 10 * b + 128) shr 8) + 128).toByte())
                }
            }
        }
    }
}
