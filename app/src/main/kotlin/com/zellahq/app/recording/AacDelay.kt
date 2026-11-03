package com.zellahq.app.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlin.math.abs

/**
 * AAC encoders pad the start of the sound, so everything decodes late by a fixed amount: 42.6 ms
 * (2048 samples) on the emulator's encoder, and it differs between phones. The encoder does not
 * report it. So measure it: encode a click at exactly 1.000 s, decode it, see where it lands.
 * About 200 ms, done once per encoder and remembered.
 */
object AacDelay {
    private const val PREFS = "aac_delay"

    /** Cached delay in microseconds for this phone's AAC encoder, measuring it on first use. */
    fun forDevice(
        context: Context,
        rate: Int,
    ): Long {
        val name =
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).let {
                it.name.also {
                        _ ->
                    it.release()
                }
            }
        val key = "$name@$rate"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(key)) return prefs.getLong(key, 0)
        val us = runCatching { measure(rate) }.onFailure { Log.e("ZellaRecorder", "aac delay", it) }.getOrNull()
        // A failed or absurd measurement is not saved, and no correction is applied: better a known
        // small offset than a wrong large one.
        if (us == null || us !in 0..200_000) return 0
        prefs.edit().putLong(key, us).apply()
        Log.i("ZellaRecorder", "aac delay $key = $us us")
        return us
    }

    fun measure(rate: Int): Long {
        val pcm = ShortArray(rate * 2)
        for (i in 0 until rate / 1000) pcm[rate + i] = if (i % 2 == 0) 30_000 else -30_000

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val f =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, rate / 50 * 2)
            }
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        val packets = ArrayList<Pair<Long, ByteArray>>()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        var fed = 0
        var eosSent = false
        var done = false
        try {
            while (!done) {
                if (!eosSent) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = minOf(rate / 50, pcm.size - fed)
                        enc.getInputBuffer(i)!!.apply { clear() }.asShortBuffer().put(pcm, fed, n)
                        eosSent = n == 0
                        enc.queueInputBuffer(
                            i,
                            0,
                            n * 2,
                            fed * 1_000_000L / rate,
                            if (eosSent) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                        fed += n
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outFormat = enc.outputFormat
                if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val bytes = ByteArray(info.size).also { enc.getOutputBuffer(o)!!.get(it) }
                        packets += info.presentationTimeUs to bytes
                    }
                    done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    enc.releaseOutputBuffer(o, false)
                }
            }
        } finally {
            enc.release()
        }

        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        dec.configure(outFormat, null, null, 0)
        dec.start()
        var next = 0
        try {
            while (true) {
                if (next <= packets.size) {
                    val i = dec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (next == packets.size) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val (pts, data) = packets[next]
                            dec.getInputBuffer(i)!!.apply { clear() }.put(data)
                            dec.queueInputBuffer(i, 0, data.size, pts, 0)
                        }
                        next++
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val s = dec.getOutputBuffer(o)!!.asShortBuffer()
                    val ch = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    for (k in 0 until s.remaining() / ch) {
                        if (abs(s.get(k * ch).toInt()) > 8_000) {
                            return info.presentationTimeUs + k * 1_000_000L / rate - 1_000_000
                        }
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    dec.releaseOutputBuffer(o, false)
                    if (eos) error("click not found after decoding")
                }
            }
        } finally {
            dec.release()
        }
    }
}
