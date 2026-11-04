package com.zellahq.app.captions

import android.content.Context
import android.util.Log
import com.zellahq.app.editor.Storage
import com.zellahq.core.captions.CaptionBuilder
import com.zellahq.core.captions.SourceWord
import com.zellahq.core.project.CaptionWord
import com.zellahq.core.project.Project
import com.zellahq.whisper.Whisper
import java.io.File

/**
 * Makes captions on the phone with Whisper. Each clip's own sound is read at normal speed in
 * 30-second windows; the words are then placed on the timeline through the clip's trim and speed.
 * Finished windows are kept if the person stops, and progress is real (seconds done of seconds total).
 */
class CaptionEngine(private val context: Context) {
    sealed interface Outcome {
        data class Done(val project: Project, val language: String, val words: Int, val tookMs: Long) : Outcome

        /** Speech was there, but nothing came out in the chosen language. */
        data class NoWords(val language: String) : Outcome

        data object NoSound : Outcome

        data object Stopped : Outcome

        data class Failed(val message: String) : Outcome
    }

    companion object {
        const val MODEL = "ggml-base-q5_1.bin"
        const val WINDOW = 30.0

        /** Languages offered by name. Telugu is left out on purpose: it does not work on-device yet. */
        val LANGUAGES = listOf("en", "bn", "hi", "es", "fr", "vi", "ar", "ur", "zh")
    }

    @Volatile private var whisper: Whisper? = null

    @Volatile private var stopped = false

    /** A downloaded model the person chose, else the bundled one. */
    fun modelFile(): File {
        val chosen = context.getSharedPreferences("captions", Context.MODE_PRIVATE).getString("model", null)
        if (chosen != null) File(context.filesDir, "models/$chosen.bin").takeIf { it.isFile }?.let { return it }
        return bundledModel()
    }

    /** The bundled model, copied out of the app once: Whisper reads a file, not an asset. */
    fun bundledModel(): File {
        val f = File(context.filesDir, "models/$MODEL")
        if (f.isFile && f.length() > 50_000_000) return f
        f.parentFile!!.mkdirs()
        val tmp = File(f.parentFile, "$MODEL.part")
        context.assets.open("models/$MODEL").use { i -> tmp.outputStream().use { i.copyTo(it) } }
        tmp.renameTo(f)
        return f
    }

    fun stop() {
        stopped = true
        whisper?.cancel()
    }

    /**
     * [language] is a Whisper code, or null to detect it from the first window with speech.
     * [translate] writes English captions for speech in another language.
     */
    fun run(
        project: Project,
        language: String?,
        translate: Boolean,
        onProgress: (Float) -> Unit,
    ): Outcome {
        stopped = false
        val t0 = System.currentTimeMillis()
        val store = Storage.store(context)
        val w = Whisper.load(modelFile().path) ?: return Outcome.Failed("The speech model could not be loaded.")
        whisper = w
        try {
            val clips = project.clips.filter { !it.reversed }
            val total = clips.sumOf { it.trimmedDuration }.coerceAtLeast(0.001)
            var done = 0.0
            var lang = language
            var heard = false
            val placed = ArrayList<CaptionWord>()
            for (clip in clips) {
                val file = store.resolve(clip.source, project.id)
                var from = clip.trimStart
                while (from < clip.trimEnd - 0.05) {
                    if (stopped) return Outcome.Stopped
                    val to = minOf(clip.trimEnd, from + WINDOW)
                    val pcm = AudioExtractor.pcm16k(file, from, to)
                    if (pcm != null && pcm.isNotEmpty() && loudEnough(pcm)) {
                        heard = true
                        if (lang == null) {
                            val ranked = w.detectLanguage(pcm)
                            Log.i("ZellaCaptions", "language guesses $ranked")
                            lang = ranked.firstOrNull()?.first ?: "en"
                        }
                        val windowStart = from
                        val windowLen = to - from
                        val t =
                            w.transcribe(pcm, lang!!, translate) { pct -> onProgress(((done + windowLen * pct / 100.0) / total).toFloat()) }
                                ?: return if (stopped) Outcome.Stopped else Outcome.Failed("Captions stopped part way. Please try again.")
                        val spoken =
                            t.words.filterNot {
                                isNoise(it.text)
                            }.map { com.zellahq.core.auto.SpokenWord(it.text, it.startMs / 1000.0, it.endMs / 1000.0) }
                        val words =
                            com.zellahq.core.auto.WordTiming.tighten(spoken, pcm).map {
                                SourceWord(it.text, windowStart + it.start, windowStart + it.end)
                            }
                        placed += CaptionBuilder.place(project, clip, words)
                    }
                    done += to - from
                    onProgress((done / total).toFloat())
                    from = to
                }
            }
            if (!heard) return Outcome.NoSound
            if (placed.isEmpty()) return Outcome.NoWords(lang ?: "auto")
            val track = CaptionBuilder.track(CaptionBuilder.cues(placed), project.captions)
            // The language is saved only when the person picked it; "detect" stays detect next time.
            val p = project.copy(captions = track, captionLanguage = language)
            return Outcome.Done(p, lang ?: "auto", placed.size, System.currentTimeMillis() - t0)
        } finally {
            whisper = null
            w.close()
        }
    }

    /** Whisper writes these for music, silence and noise. They are not words anyone said. */
    private fun isNoise(t: String) = t.startsWith("[") || t.startsWith("(") || t.startsWith("*") || t == "-"

    private fun loudEnough(pcm: FloatArray): Boolean {
        var sum = 0.0
        for (v in pcm) sum += v * v
        return Math.sqrt(sum / pcm.size) > 0.003
    }
}
