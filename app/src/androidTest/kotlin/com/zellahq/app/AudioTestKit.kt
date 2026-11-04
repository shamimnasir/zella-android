package com.zellahq.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Sound decoded from a file: one array per channel, plus where the first sample sits in seconds. */
class Decoded(val channels: List<FloatArray>, val rate: Int, val startSeconds: Double) {
    val mono: FloatArray by lazy { FloatArray(channels[0].size) { i -> channels.sumOf { it[i].toDouble() }.toFloat() / channels.size } }
    val seconds: Double get() = channels[0].size.toDouble() / rate

    /** Level in dBFS of the stretch between two times on the file's own clock. */
    fun rmsDb(
        from: Double,
        to: Double,
    ): Double {
        val a = (((from - startSeconds) * rate).toInt()).coerceIn(0, mono.size)
        val b = (((to - startSeconds) * rate).toInt()).coerceIn(a, mono.size)
        if (b == a) return -200.0
        var s = 0.0
        for (i in a until b) s += mono[i] * mono[i]
        return 10 * log10((s / (b - a)).coerceAtLeast(1e-20))
    }

    /** The first time on the file's clock where the level passes [db], or null. */
    fun firstAbove(
        db: Double,
        after: Double = 0.0,
    ): Double? {
        val limit = Math.pow(10.0, db / 20)
        val from = (((after - startSeconds) * rate).toInt()).coerceAtLeast(0)
        for (i in from until mono.size) if (kotlin.math.abs(mono[i]) >= limit) return startSeconds + i.toDouble() / rate
        return null
    }
}

object AudioTestKit {
    /** Decodes the first sound track of a file with the phone's own decoder. */
    fun decode(f: File): Decoded {
        val ex = MediaExtractor().apply { setDataSource(f.path) }
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcm = ShortArray(1 shl 20)
        var n = 0
        var first = -1L
        var inputDone = false
        while (true) {
            if (!inputDone) {
                val i = codec.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (o >= 0) {
                if (first < 0 && info.size > 0) first = info.presentationTimeUs
                val s = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                val count = info.size / 2
                if (n + count > pcm.size) pcm = pcm.copyOf(maxOf(pcm.size * 2, n + count))
                s.get(pcm, n, count)
                n += count
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(o, false)
                if (end) break
            }
        }
        codec.stop()
        codec.release()
        ex.release()
        val frames = n / ch
        return Decoded(List(ch) { c -> FloatArray(frames) { pcm[it * ch + c] / 32768f } }, rate, first.coerceAtLeast(0) / 1e6)
    }

    /** A 16-bit mono WAV file. */
    fun wav(
        f: File,
        samples: FloatArray,
        rate: Int = 48_000,
    ): File {
        val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { b.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        f.writeBytes(b.array())
        return f
    }

    fun readWav(bytes: ByteArray): FloatArray {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var off = 12
        while (off + 8 <= bytes.size) {
            val id = String(bytes, off, 4)
            val len = b.getInt(off + 4)
            if (id == "data") return FloatArray(len / 2) { b.getShort(off + 8 + it * 2) / 32768f }
            off += 8 + len
        }
        error("no data chunk")
    }

    /** Mean power per band over 4096-sample Hann frames (radix-2 FFT). */
    fun bandPower(
        x: FloatArray,
        lo: Double,
        hi: Double,
        fs: Int = 48_000,
    ): Double {
        val n = 4096
        var total = 0.0
        var frames = 0
        var start = 0
        while (start + n <= x.size) {
            val re = DoubleArray(n) { x[start + it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }
            val im = DoubleArray(n)
            fft(re, im)
            for (k in (lo * n / fs).toInt()..(hi * n / fs).toInt()) total += re[k] * re[k] + im[k] * im[k]
            frames++
            start += n
        }
        return total / frames
    }

    fun fft(
        re: DoubleArray,
        im: DoubleArray,
    ) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k)
                val wi = sin(ang * k)
                val ur = re[i + k]
                val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr
                im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr
                im[i + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }

    fun db(p: Double) = 10 * log10(p.coerceAtLeast(1e-30))

    /** How far [b] sits after [a], in samples, by the best match of their shapes over [range]. */
    fun lagSamples(
        a: FloatArray,
        b: FloatArray,
        range: Int = 2_000,
    ): Int {
        val n = minOf(a.size, b.size) - range * 2
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in -range..range) {
            var s = 0.0
            var i = range
            while (i < n) {
                s += a[i] * b[i + lag]
                i += 7
            }
            if (s > bestScore) {
                bestScore = s
                best = lag
            }
        }
        return best
    }

    /** Straight-line resampling, enough for lining two takes up in time. */
    fun resample(
        x: FloatArray,
        from: Int,
        to: Int,
    ): FloatArray {
        if (from == to) return x
        val step = from.toDouble() / to
        return FloatArray((x.size / step).toInt()) { i ->
            val at = i * step
            val a = at.toInt()
            val f = (at - a).toFloat()
            x[a] * (1 - f) + x[minOf(a + 1, x.size - 1)] * f
        }
    }

    fun rms(x: FloatArray): Double {
        var s = 0.0
        x.forEach { s += it * it }
        return sqrt(s / x.size.coerceAtLeast(1))
    }
}
