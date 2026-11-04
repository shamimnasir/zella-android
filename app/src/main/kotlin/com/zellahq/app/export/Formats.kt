@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.export

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer

/** The other things an export can save, each made from the finished video. MOV is not offered: Android has no writer for it. */
object Formats {
    const val VIDEO = "video"
    const val GIF = "gif"
    const val SOUND = "sound"
    private const val GIF_WIDTH = 480
    private const val GIF_FPS = 12

    fun mime(file: File) =
        when (file.extension) {
            "gif" -> "image/gif"
            "m4a" -> "audio/mp4"
            else -> "video/mp4"
        }

    /** The sound track copied as it is, with no second encode. False when the video has no sound. */
    fun soundOnly(
        video: File,
        out: File,
    ): Boolean {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(video.absolutePath)
            val t =
                (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return false
            ex.selectTrack(t)
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(ex.getTrackFormat(t))
            muxer.start()
            val buffer = ByteBuffer.allocate(1 shl 18)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = ex.readSampleData(buffer, 0)
                if (n < 0) break
                info.set(
                    0,
                    n,
                    ex.sampleTime,
                    if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                )
                muxer.writeSampleData(track, buffer, info)
                ex.advance()
            }
            muxer.stop()
            return out.length() > 0
        } finally {
            runCatching { muxer?.release() }
            ex.release()
        }
    }

    /** 480 wide, 12 frames a second, a palette per frame. */
    fun gif(
        video: File,
        out: File,
    ): Boolean {
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(video.absolutePath)
            val ms = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return false
            val count = (ms * GIF_FPS / 1000).toInt().coerceAtLeast(1)
            out.outputStream().buffered().use { o ->
                var writer: GifWriter? = null
                for (i in 0 until count) {
                    val us = i * 1_000_000L / GIF_FPS
                    val raw = m.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    val h = (raw.height * GIF_WIDTH / raw.width).coerceAtLeast(2)
                    val small = Bitmap.createScaledBitmap(raw, GIF_WIDTH, h, true)
                    if (small !== raw) raw.recycle()
                    val w = writer ?: GifWriter(o, GIF_WIDTH, h).also { writer = it }
                    w.frame(small, 100 / GIF_FPS)
                    small.recycle()
                }
                writer?.finish() ?: return false
            }
            return out.length() > 0
        } finally {
            m.release()
        }
    }
}

/** A small GIF89a encoder: a median-cut palette per frame, then LZW. */
class GifWriter(private val o: OutputStream, private val w: Int, private val h: Int) {
    init {
        o.write("GIF89a".toByteArray())
        short(w)
        short(h)
        o.write(0x70)
        o.write(0)
        o.write(0)
        // Loop for ever.
        o.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        o.write("NETSCAPE2.0".toByteArray())
        o.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    private fun short(v: Int) {
        o.write(v and 255)
        o.write(v shr 8 and 255)
    }

    fun frame(
        b: Bitmap,
        delayCs: Int,
    ) {
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val palette = palette(px)
        val lookup = ShortArray(1 shl 15) { -1 }
        val indexed = ByteArray(px.size)
        for (i in px.indices) {
            val key = (px[i] shr 9 and 0x7C00) or (px[i] shr 6 and 0x3E0) or (px[i] shr 3 and 0x1F)
            var v = lookup[key].toInt()
            if (v < 0) {
                v = nearest(palette, px[i])
                lookup[key] = v.toShort()
            }
            indexed[i] = v.toByte()
        }
        o.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0))
        short(delayCs)
        o.write(0)
        o.write(0)
        o.write(0x2C)
        short(0)
        short(0)
        short(w)
        short(h)
        o.write(0x87)
        for (i in 0 until 256) {
            val c = palette.getOrElse(i) { 0 }
            o.write(c shr 16 and 255)
            o.write(c shr 8 and 255)
            o.write(c and 255)
        }
        lzw(indexed)
    }

    fun finish() {
        o.write(0x3B)
        o.flush()
    }

    private fun nearest(
        pal: IntArray,
        c: Int,
    ): Int {
        var best = 0
        var bestD = Int.MAX_VALUE
        val r = c shr 16 and 255
        val g = c shr 8 and 255
        val b = c and 255
        for (i in pal.indices) {
            val dr = r - (pal[i] shr 16 and 255)
            val dg = g - (pal[i] shr 8 and 255)
            val db = b - (pal[i] and 255)
            val d = dr * dr * 2 + dg * dg * 4 + db * db
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** Median cut over a sample of the frame's pixels. */
    private fun palette(px: IntArray): IntArray {
        val sample = IntArray(px.size / 8 + 1) { px[(it * 8).coerceAtMost(px.size - 1)] }
        val boxes = ArrayList<List<Int>>()
        boxes += sample.toList()
        while (boxes.size < 256) {
            val i = boxes.indices.maxByOrNull { spread(boxes[it]) } ?: break
            val box = boxes[i]
            if (box.size < 2 || spread(box) == 0) break
            val shift = widest(box)
            val sorted = box.sortedBy { it shr shift and 255 }
            boxes[i] = sorted.subList(0, sorted.size / 2)
            boxes += sorted.subList(sorted.size / 2, sorted.size)
        }
        return IntArray(boxes.size) { k ->
            val box = boxes[k]
            val r = box.sumOf { it shr 16 and 255 } / box.size
            val g = box.sumOf { it shr 8 and 255 } / box.size
            val b = box.sumOf { it and 255 } / box.size
            (r shl 16) or (g shl 8) or b
        }
    }

    private fun range(
        box: List<Int>,
        shift: Int,
    ) = box.maxOf { it shr shift and 255 } - box.minOf { it shr shift and 255 }

    private fun spread(box: List<Int>) = if (box.isEmpty()) 0 else maxOf(range(box, 16), range(box, 8), range(box, 0))

    private fun widest(box: List<Int>) = listOf(16, 8, 0).maxBy { range(box, it) }

    private fun lzw(data: ByteArray) {
        val minCode = 8
        o.write(minCode)
        val clear = 1 shl minCode
        val end = clear + 1
        val block = ByteArray(255)
        var blockLen = 0
        var acc = 0
        var bits = 0

        fun emit(
            code: Int,
            size: Int,
        ) {
            acc = acc or (code shl bits)
            bits += size
            while (bits >= 8) {
                block[blockLen++] = (acc and 255).toByte()
                acc = acc ushr 8
                bits -= 8
                if (blockLen == 255) {
                    o.write(255)
                    o.write(block, 0, 255)
                    blockLen = 0
                }
            }
        }
        val table = HashMap<Int, Int>(8192)
        var next = end + 1
        var size = minCode + 1
        emit(clear, size)
        var cur = data[0].toInt() and 255
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 255
            val key = (cur shl 8) or k
            val hit = table[key]
            if (hit != null) {
                cur = hit
            } else {
                emit(cur, size)
                if (next < 4096) {
                    table[key] = next
                    if (next == (1 shl size) && size < 12) size++
                    next++
                } else {
                    emit(clear, size)
                    table.clear()
                    next = end + 1
                    size = minCode + 1
                }
                cur = k
            }
        }
        emit(cur, size)
        emit(end, size)
        if (bits > 0) block[blockLen++] = (acc and 255).toByte()
        if (blockLen > 0) {
            o.write(blockLen)
            o.write(block, 0, blockLen)
        }
        o.write(0)
    }
}
