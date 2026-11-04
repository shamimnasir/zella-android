@file:Suppress("ktlint:standard:max-line-length")
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zellahq.app.sound

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * The iPhone's 16 voice effects, under its names. They are built here from plain filters, pitch, echo and
 * tone rather than Apple's audio units, so each sounds alike in kind, not identical. Nothing has been
 * listened to on a phone yet.
 */
enum class VoiceEffect(
    val raw: String,
    /** Pitch multiplier. */
    val pitch: Float = 1f,
    val ring: Float = 0f,
    val echoMs: Int = 0,
    val echoLevel: Float = 0f,
    val lowHz: Float = 0f,
    val highHz: Float = 0f,
    val drive: Float = 1f,
    val crush: Int = 0,
    val wobbleHz: Float = 0f,
) {
    Chipmunk("Chipmunk", pitch = 1.6f),
    Deep("Deep", pitch = 0.72f),
    Robot("Robot", ring = 90f, drive = 1.2f),
    Echo("Echo", echoMs = 280, echoLevel = 0.5f),
    Reverb("Reverb", echoMs = 45, echoLevel = 0.62f),
    Megaphone("Megaphone", lowHz = 3000f, highHz = 500f, drive = 3f),
    Telephone("Telephone", lowHz = 3400f, highHz = 300f, drive = 1.4f),
    Underwater("Underwater", lowHz = 550f, wobbleHz = 3f),
    Monster("Monster", pitch = 0.6f, ring = 30f, drive = 1.5f),
    Helium("Helium", pitch = 1.9f),
    Alien("Alien", pitch = 1.15f, ring = 300f),
    Ghost("Ghost", pitch = 0.9f, echoMs = 180, echoLevel = 0.6f, wobbleHz = 5f),
    EightBit("8-Bit", lowHz = 5000f, crush = 6),
    Duck("Duck", pitch = 1.35f, highHz = 800f, drive = 2f),
    Cartoon("Cartoon", pitch = 1.45f, wobbleHz = 6f),
    Ogre("Ogre", pitch = 0.65f, drive = 2f, lowHz = 2500f),
    ;

    companion object {
        fun of(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/** The whole effect for a clip's sound, in the order it runs. */
fun voiceEffectChain(e: VoiceEffect): List<AudioProcessor> =
    buildList {
        if (e.pitch != 1f) add(SonicAudioProcessor().apply { setPitch(e.pitch) })
        add(VoiceEffectProcessor(e))
    }

/** Ring, tone filters, drive, crush, wobble and echo, on 16 bit sound. */
class VoiceEffectProcessor(private val e: VoiceEffect) : BaseAudioProcessor() {
    private var rate = 48_000
    private var channels = 2
    private var frame = 0L
    private var lp = FloatArray(0)
    private var hp = FloatArray(0)
    private var echo = FloatArray(0)
    private var echoAt = 0

    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
        rate = f.sampleRate
        channels = f.channelCount
        return f
    }

    private fun begin() {
        lp = FloatArray(channels)
        hp = FloatArray(channels)
        echo = FloatArray(maxOf(1, e.echoMs * rate / 1000 * channels))
        echoAt = 0
        frame = 0
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) = begin()

    override fun onReset() {
        lp = FloatArray(0)
    }

    override fun queueInput(input: ByteBuffer) {
        if (lp.size != channels) begin()
        val n = input.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        val pcm = input.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val w = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val aLow = if (e.lowHz > 0) (2 * PI.toFloat() * e.lowHz / rate).coerceAtMost(1f) else 1f
        val aHigh = if (e.highHz > 0) (2 * PI.toFloat() * e.highHz / rate).coerceAtMost(1f) else 0f
        val samples = n / 2
        for (i in 0 until samples) {
            val ch = i % channels
            var x = pcm.get(i) / 32768f
            if (e.ring > 0) x *= sin(2 * PI * e.ring * frame / rate).toFloat()
            if (e.lowHz > 0) {
                lp[ch] += aLow * (x - lp[ch])
                x = lp[ch]
            }
            if (e.highHz > 0) {
                hp[ch] += aHigh * (x - hp[ch])
                x -= hp[ch]
            }
            if (e.drive != 1f) x = (x * e.drive).coerceIn(-1f, 1f) * 0.8f
            if (e.crush > 0) {
                val steps = (1 shl e.crush).toFloat()
                x = kotlin.math.round(x * steps) / steps
            }
            if (e.wobbleHz > 0) x *= 0.75f + 0.25f * sin(2 * PI * e.wobbleHz * frame / rate).toFloat()
            if (e.echoLevel > 0) {
                val back = echo[echoAt]
                val y = x + back * e.echoLevel
                echo[echoAt] = y
                echoAt = (echoAt + 1) % echo.size
                x = y.coerceIn(-1f, 1f)
            }
            w.put(i, (x * 32767f).toInt().toShort())
            if (ch == channels - 1) frame++
        }
        input.position(input.limit())
        out.position(0)
        out.limit(n)
    }
}
