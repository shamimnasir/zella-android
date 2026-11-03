package com.zellahq.whisper

/** Raw JNI calls. Use [Whisper] instead. */
internal object WhisperNative {
    init {
        System.loadLibrary("zella_whisper")
    }

    fun interface ProgressListener {
        fun onProgress(percent: Int)
    }

    external fun init(modelPath: String): Long

    external fun free(handle: Long)

    external fun cancel(handle: Long)

    external fun transcribe(
        handle: Long,
        pcm16k: FloatArray,
        language: String,
        translate: Boolean,
        threads: Int,
        listener: ProgressListener?,
    ): Array<String>?

    external fun detectLanguage(
        handle: Long,
        pcm16k: FloatArray,
        threads: Int,
    ): Array<String>?
}

/** One recognised word, in milliseconds from the start of the audio passed in. */
data class Word(val text: String, val startMs: Long, val endMs: Long, val probability: Float)

data class Transcript(val language: String, val words: List<Word>)

/**
 * A loaded Whisper model. Not thread-safe: one transcription at a time. [close] frees the model.
 * Threads default to the phone's big cores: running on the small cores too is slower, not faster.
 */
class Whisper private constructor(private var handle: Long) : AutoCloseable {
    companion object {
        fun load(modelPath: String): Whisper? = WhisperNative.init(modelPath).takeIf { it != 0L }?.let { Whisper(it) }

        /** Big cores only, counted from the highest max frequency. At least 2, at most 6. */
        fun performanceCores(): Int {
            val freqs =
                (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
                    runCatching {
                        java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                    }.getOrNull()
                }
            if (freqs.isEmpty()) return (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
            val top = freqs.max()
            return freqs.count { it >= top * 0.8 }.coerceIn(2, 6)
        }
    }

    fun transcribe(
        pcm16k: FloatArray,
        language: String = "auto",
        translate: Boolean = false,
        threads: Int = performanceCores(),
        onProgress: (Int) -> Unit = {},
    ): Transcript? {
        val raw = WhisperNative.transcribe(handle, pcm16k, language, translate, threads) { onProgress(it) } ?: return null
        val words =
            (1 until raw.size step 4).mapNotNull { i ->
                val text = raw[i].trim()
                if (text.isEmpty()) null else Word(text, raw[i + 1].toLong(), raw[i + 2].toLong(), raw[i + 3].toFloat())
            }
        return Transcript(raw[0], words)
    }

    /** Most likely languages in the first 30 s, highest first. Probabilities, not logs. */
    fun detectLanguage(
        pcm16k: FloatArray,
        threads: Int = performanceCores(),
    ): List<Pair<String, Float>> =
        WhisperNative.detectLanguage(handle, pcm16k, threads).orEmpty().map {
            val (code, p) = it.split("=")
            code to p.toFloat()
        }

    /** Stops a running [transcribe] from another thread; it returns null. */
    fun cancel() = WhisperNative.cancel(handle)

    override fun close() {
        if (handle != 0L) WhisperNative.free(handle)
        handle = 0
    }
}
