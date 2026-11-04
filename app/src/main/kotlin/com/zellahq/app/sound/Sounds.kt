package com.zellahq.app.sound

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.zellahq.app.captions.AudioExtractor
import com.zellahq.app.editor.Storage
import com.zellahq.core.audio.Loudness
import com.zellahq.core.audio.Voice
import com.zellahq.core.audio.Window
import com.zellahq.core.project.Project
import com.zellahq.core.project.startTime
import com.zellahq.whisper.Denoiser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A music loop or sound effect from the iPhone's synthesiser, baked by tools/ios-conformance. */
data class Sound(val id: String, val file: String, val seconds: Double)

object Sounds {
    /** Clean-up strength: measured on real speech, 80% sits closest to the natural voice (DenoiseHissTest). */
    const val CLEANUP_WET = 0.8f

    /** Project key for the whole-video loudness gain. Android only; the iPhone ignores it. */
    const val LOUDNESS_KEY = "zellaLoudnessGainDb"

    @Volatile private var cache: Pair<List<Sound>, List<Sound>>? = null

    private fun load(context: Context): Pair<List<Sound>, List<Sound>> =
        cache ?: synchronized(this) {
            cache ?: run {
                val raw = context.assets.open("sounds/sounds.json").use { it.readBytes().decodeToString() }
                val o = Json.parseToJsonElement(raw).jsonObject

                fun list(k: String) =
                    o[k]!!.jsonArray.map {
                        val e = it.jsonObject
                        Sound(
                            e["id"]!!.jsonPrimitive.content,
                            e["file"]!!.jsonPrimitive.content,
                            e["seconds"]!!.jsonPrimitive.doubleOrNull ?: 0.0,
                        )
                    }
                (list("music") to list("sfx")).also { cache = it }
            }
        }

    fun music(context: Context) = load(context).first

    fun effects(context: Context) = load(context).second

    /** Copies a sound out of the app once, so it can be referenced like any other media file. */
    fun file(
        context: Context,
        s: Sound,
        kind: String,
    ): File {
        val f = File(context.filesDir, "sounds/$kind/${s.file}.m4a")
        if (!f.isFile) {
            f.parentFile!!.mkdirs()
            context.assets.open("sounds/$kind/${s.file}.m4a").use { i -> f.outputStream().use { i.copyTo(it) } }
        }
        return f
    }

    /** Whether the project already knows where the voice is. Without it, music has nothing to step back for. */
    fun hasVoiceWindows(p: Project) = !(p.extras["voiceWindows"] as? JsonArray).isNullOrEmpty() || !p.captions?.cues.isNullOrEmpty()

    /** Voice windows the iPhone stores in the project (`voiceWindows`). */
    fun voiceWindows(p: Project): List<Window> =
        (p.extras["voiceWindows"] as? JsonArray)?.mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val start = o["start"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val end = o["end"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            Window(start, end)
        } ?: Voice.fromCaptions(p)

    private const val MEASURE_RATE = 48_000

    private fun toMeasureRate(
        x: FloatArray,
        rate: Int,
    ): FloatArray {
        if (rate == MEASURE_RATE || x.isEmpty()) return x
        val step = rate.toDouble() / MEASURE_RATE
        return FloatArray((x.size / step).toInt()) { i ->
            val at = i * step
            val a = at.toInt()
            val f = (at - a).toFloat()
            x[a] * (1 - f) + x[minOf(a + 1, x.size - 1)] * f
        }
    }

    private fun join(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (a in parts) {
            System.arraycopy(a, 0, out, o, a.size)
            o += a.size
        }
        return out
    }

    /**
     * Listens to the project: where the voice is (for ducking) and how loud it is (for evening out).
     * Each clip's sound at normal speed; times placed on the timeline through trim and speed.
     */
    fun analyse(
        context: Context,
        p: Project,
    ): Project {
        val store = Storage.store(context)
        val windows = ArrayList<Window>()
        val left = ArrayList<FloatArray>()
        val right = ArrayList<FloatArray>()
        for (clip in p.clips) {
            if (clip.reversed || clip.volume <= 0.0) continue
            val until = minOf(clip.trimEnd, clip.trimStart + 600)
            val pcm = AudioExtractor.decode(store.resolve(clip.source, p.id), clip.trimStart, until) ?: continue
            val start = p.startTime(clip.id)
            Voice.fromEnergy(pcm.mono, pcm.rate).forEach { w ->
                windows += Window(start + w.start / clip.speed, start + w.end / clip.speed)
            }
            // Loudness is measured the way the export carries it: stereo, at one rate, a mono clip on both channels.
            // BS.1770 adds the channels up, so measuring a mono mix read 3 dB low and every video came out 3 dB hot.
            val l = toMeasureRate(pcm.channels[0], pcm.rate)
            val r = if (pcm.channels.size > 1) toMeasureRate(pcm.channels[1], pcm.rate) else l
            left += FloatArray(l.size) { (l[it] * clip.volume).toFloat() }
            right += FloatArray(r.size) { (r[it] * clip.volume).toFloat() }
        }
        val measured = listOf(join(left), join(right))
        val lufs = if (measured[0].isEmpty()) Double.NEGATIVE_INFINITY else Loudness.integrated(measured, MEASURE_RATE)
        val gain = Loudness.gainToTarget(lufs)
        val vw =
            JsonArray(
                Voice.merge(windows, 0.35).map { JsonObject(mapOf("start" to JsonPrimitive(it.start), "end" to JsonPrimitive(it.end))) },
            )
        return p.copy(extras = JsonObject(p.extras + ("voiceWindows" to vw) + (LOUDNESS_KEY to JsonPrimitive(gain))))
    }
}

/**
 * Voice clean-up in the audio pipeline: RNNoise on 48 kHz audio, each channel with its own state.
 * Comes after a resample to 48 kHz. RNNoise lags by one frame, so the first frame out is dropped and
 * the tail is pushed through at the end: the voice stays where it was against the picture, and the
 * output length always equals the input length.
 */
@OptIn(UnstableApi::class)
class CleanupProcessor(private val wet: Float = Sounds.CLEANUP_WET) : BaseAudioProcessor() {
    private var denoisers: List<Denoiser> = emptyList()
    private var pending: Array<FloatArray> = emptyArray()
    private var previous: Array<FloatArray> = emptyArray()
    private var work: Array<FloatArray> = emptyArray()
    private var filled = 0
    private var started = false
    private var ended = false

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.sampleRate != Denoiser.RATE) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    private fun begin() {
        val ch = inputAudioFormat.channelCount
        denoisers.forEach { it.close() }
        denoisers = List(ch) { Denoiser() }
        pending = Array(ch) { FloatArray(Denoiser.FRAME) }
        previous = Array(ch) { FloatArray(Denoiser.FRAME) }
        work = Array(ch) { FloatArray(Denoiser.FRAME) }
        filled = 0
        started = false
        ended = false
    }

    /** Cleans [pending]. Writes the first [limit] samples lined up with the frame before it, once there is one. */
    private fun step(
        out: ShortArray,
        at: Int,
        limit: Int = Denoiser.FRAME,
    ): Int {
        val ch = pending.size
        var o = at
        for (c in 0 until ch) {
            System.arraycopy(pending[c], 0, work[c], 0, Denoiser.FRAME)
            denoisers[c].frame(work[c])
        }
        if (started) {
            for (k in 0 until limit) {
                for (c in 0 until ch) {
                    val v = wet * work[c][k] + (1 - wet) * previous[c][k]
                    out[o++] = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
            }
        }
        for (c in 0 until ch) System.arraycopy(pending[c], 0, previous[c], 0, Denoiser.FRAME)
        started = true
        return o
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // The pipeline also calls this with nothing, and replacing the output then would lose the tail.
        if (!inputBuffer.hasRemaining()) return
        if (denoisers.isEmpty()) begin()
        val ch = inputAudioFormat.channelCount
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frames = input.remaining() / 2 / ch
        val out = ShortArray((frames + Denoiser.FRAME) * ch)
        var o = 0
        for (f in 0 until frames) {
            for (c in 0 until ch) pending[c][filled] = input.short / 32768f
            filled++
            if (filled == Denoiser.FRAME) {
                o = step(out, o)
                filled = 0
            }
        }
        write(out, o)
    }

    private fun write(
        out: ShortArray,
        count: Int,
    ) {
        val buf = replaceOutputBuffer(count * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until count) buf.putShort(out[i])
        buf.flip()
    }

    override fun onQueueEndOfStream() {
        // Media3 calls this once per pass until the processor reports it has ended. Writing a tail
        // each time made an endless stream of audio: an export of 10 s grew past 139 MB and never finished.
        if (ended) return
        ended = true
        if (denoisers.isEmpty()) begin()
        val ch = inputAudioFormat.channelCount
        val rest = filled
        val out = ShortArray(Denoiser.FRAME * 2 * ch)
        // What is still inside: the frame before this one, then the part frame collected so far.
        pending.forEach { java.util.Arrays.fill(it, rest, Denoiser.FRAME, 0f) }
        var o = step(out, 0)
        if (rest > 0) {
            pending.forEach { java.util.Arrays.fill(it, 0f) }
            o = step(out, o, rest)
        }
        write(out, o)
        filled = 0
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        ended = false
        if (denoisers.isNotEmpty()) begin()
    }

    override fun onReset() {
        denoisers.forEach { it.close() }
        denoisers = emptyList()
        filled = 0
        started = false
        ended = false
    }
}
