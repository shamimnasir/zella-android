package com.zellahq.app.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
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
    private val busy = ConcurrentHashMap.newKeySet<String>()

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
        if (!busy.add(out.path)) return null // already being made
        try {
            out.parentFile!!.mkdirs()
            val src = store.resolve(clip.source, projectId)
            val tmp = File(out.parentFile, out.name + ".part")
            val ok =
                runCatching {
                    reverse(src, clip.trimStart, clip.trimEnd, tmp, onProgress)
                }.onFailure { Log.e("ZellaReverse", "reverse", it) }.getOrDefault(false)
            return if (ok && tmp.renameTo(out)) out else null.also { tmp.delete() }
        } finally {
            busy.remove(out.path)
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
            val count = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: return false
            val durSec = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: return false) / 1000.0
            val fps = count / durSec
            val first = (fromSec * fps).toInt().coerceIn(0, count - 1)
            val last = (kotlin.math.ceil(toSec * fps).toInt() - 1).coerceIn(first, count - 1)
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
            val probe = mmr.getFrameAtIndex(first, params) ?: return false
            val w = probe.width / 2 * 2
            val h = probe.height / 2 * 2
            probe.recycle()

            val frameUs = (1_000_000 / fps).toLong()
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val fmt =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt().coerceAtLeast(1))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var written = 0
            // Frames come out in the order they were drawn, one per draw. Their time is their position.
            val drain =
                Thread {
                    val info = MediaCodec.BufferInfo()
                    var track = -1
                    while (true) {
                        val i = enc.dequeueOutputBuffer(info, 10_000)
                        if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            track = muxer.addTrack(enc.outputFormat)
                            muxer.start()
                        } else if (i >= 0) {
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                                info.presentationTimeUs = written * frameUs
                                muxer.writeSampleData(track, enc.getOutputBuffer(i)!!, info)
                                written++
                            }
                            enc.releaseOutputBuffer(i, false)
                            if (eos) break
                        }
                    }
                }.apply { start() }

            val dst = Rect(0, 0, w, h)
            var end = last
            val total = last - first + 1
            var done = 0
            while (end >= first) {
                val start = maxOf(first, end - CHUNK + 1)
                val frames = mmr.getFramesAtIndex(start, end - start + 1, params)
                for (bmp in frames.asReversed()) {
                    val c = surface.lockHardwareCanvas()
                    c.drawBitmap(bmp, null, dst, null)
                    surface.unlockCanvasAndPost(c)
                    bmp.recycle()
                    done++
                }
                onProgress(done.toFloat() / total)
                end = start - 1
            }
            enc.signalEndOfInputStream()
            drain.join(10_000)
            runCatching { muxer.stop() }
            muxer.release()
            enc.stop()
            enc.release()
            surface.release()
            return written > 0
        } finally {
            mmr.release()
        }
    }
}
