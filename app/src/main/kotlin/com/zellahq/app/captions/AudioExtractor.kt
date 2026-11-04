package com.zellahq.app.captions

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** Reads a stretch of a file's sound as 16 kHz mono floats, what Whisper takes. */
object AudioExtractor {
    const val RATE = 16_000

    /** Null when the file has no sound track. */
    fun pcm16k(
        file: File,
        fromSec: Double,
        toSec: Double,
    ): FloatArray? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val track =
                (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0)
            codec.start()
            ex.seekTo((fromSec * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mono = FloatArrayBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val fromUs = (fromSec * 1e6).toLong()
            val toUs = (toSec * 1e6).toLong()
            try {
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0 || ex.sampleTime > toUs) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (o >= 0) {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val frames = buf.remaining() / channels
                        for (f in 0 until frames) {
                            val t = info.presentationTimeUs + f * 1_000_000L / rate
                            if (t < fromUs || t >= toUs) continue
                            var sum = 0f
                            for (c in 0 until channels) sum += buf.get(f * channels + c)
                            mono.add(sum / channels / 32768f)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(o, false)
                        if (eos) break
                    }
                }
            } finally {
                codec.stop()
                codec.release()
            }
            return resample(mono.toArray(), rate)
        } finally {
            ex.release()
        }
    }

    /** Averages over each output step (a simple low-pass), then interpolates. Good enough for speech. */
    fun resample(
        input: FloatArray,
        from: Int,
    ): FloatArray {
        if (from == RATE) return input
        val ratio = from.toDouble() / RATE
        val out = FloatArray((input.size / ratio).toInt())
        val half = maxOf(0, (ratio / 2).toInt())
        for (i in out.indices) {
            val center = i * ratio
            val c = center.toInt()
            var sum = 0f
            var n = 0
            for (k in c - half..c + half) {
                if (k in input.indices) {
                    sum += input[k]
                    n++
                }
            }
            out[i] = if (n > 0) sum / n else 0f
        }
        return out
    }

    private class FloatArrayBuilder {
        private var a = FloatArray(1 shl 16)
        private var n = 0

        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }

        fun toArray() = a.copyOf(n)
    }
}
