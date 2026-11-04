package com.zellahq.app.auto

import android.content.Context
import com.zellahq.app.captions.AudioExtractor
import com.zellahq.app.captions.CaptionEngine
import com.zellahq.app.editor.Storage
import com.zellahq.app.looks.Looks
import com.zellahq.app.sound.Sounds
import com.zellahq.core.auto.AutoEdit
import com.zellahq.core.auto.CaptionEmphasis
import com.zellahq.core.auto.FaceSample
import com.zellahq.core.auto.Polish
import com.zellahq.core.auto.PolishReceipt
import com.zellahq.core.auto.PolishStep
import com.zellahq.core.auto.Reframe
import com.zellahq.core.auto.SilenceDetector
import com.zellahq.core.auto.SpokenWord
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.CaptionTrack
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.MusicBed
import com.zellahq.core.project.Project
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.startTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Runs Auto-Polish, Make it Viral and Make Vertical, in the order of [PolishStep.plan]. Every step only
 * edits the project, so a run is one undo step and a second run replaces its own work. Words go into
 * the project's captions for the length of the run, whether or not the person wants captions: every
 * cut then keeps them in step with the picture, and Auto-Polish takes them out again at the end.
 */
class AutoPolish(
    private val context: Context,
) {
    class Result(
        val project: Project,
        val executed: List<PolishStep>,
        val receipt: PolishReceipt,
        val stopped: Boolean,
    )

    private val engine = CaptionEngine(context)

    @Volatile private var stopped = false

    fun stop() {
        stopped = true
        engine.stop()
    }

    private fun words(p: Project) = p.captions?.cues.orEmpty().flatMap { it.words }.map { SpokenWord(it.text, it.start, it.end) }

    /** Just the pauses, for the Assistant's remove_silences. */
    fun silencesOnly(p: Project): Pair<Project, Int> {
        val (out, ranges) = Polish.cutSilences(p, SilenceDetector().detect(TimelineAudio.mono(context, p)), words(p))
        return out to ranges.size
    }

    /** Just the filler words. Needs captions already made. */
    fun fillersOnly(p: Project): Pair<Project, Int> {
        val (out, fillers) = Polish.cutFillers(p, words(p), p.captionLanguage)
        return out to fillers.size
    }

    fun run(
        start: Project,
        viral: Boolean,
        reframeTo: Aspect? = null,
        onStep: (PolishStep, Int, Int) -> Unit = { _, _, _ -> },
    ): Result {
        stopped = false
        val plan = PolishStep.plan(viral, reframing = reframeTo != null)
        val executed = ArrayList<PolishStep>()
        var cut = 0
        var cutSeconds = 0.0
        var fillerCount = 0
        var p = start
        val ownCaptions: CaptionTrack? = start.captions
        var language = start.captionLanguage

        fun begin(step: PolishStep) {
            executed += step
            onStep(step, executed.size, plan.size)
        }

        // 1. Pauses. The words are the second witness: a "silence" under a word is a mistake of the detector.
        begin(PolishStep.Silences)
        if (ownCaptions == null) {
            when (val out = engine.run(p, language, translate = false) { _ -> }) {
                is CaptionEngine.Outcome.Done -> {
                    p = p.copy(captions = out.project.captions)
                    language = out.language
                }
                else -> Unit
            }
        }
        if (stopped) return Result(start, executed, PolishReceipt(), true)
        val silences = SilenceDetector().detect(TimelineAudio.mono(context, p))
        val (uncut, ranges) = Polish.cutSilences(p, silences, words(p))
        p = uncut
        cut = ranges.size
        cutSeconds = ranges.sumOf { it.length }

        // 2. Filler words.
        begin(PolishStep.Fillers)
        val (noFillers, fillers) = Polish.cutFillers(p, words(p), language)
        p = noFillers
        fillerCount = fillers.size
        if (stopped) return Result(start, executed, PolishReceipt(), true)

        // 3. All the remaining cutting, before any voice work: an opener, cuts at real pauses, a few dissolves.
        begin(PolishStep.HookAndTransitions)
        p = Polish.addHookTitle(p, words(p).map { it.text })
        if (p.clips.size <= 1) p = Polish.cutAtNaturalPauses(p, SilenceDetector().detect(TimelineAudio.mono(context, p)))
        p = Polish.placeDissolves(p, reserve = if (viral) 2 else 0).first

        // 4. Voice: clean-up and the studio chain on every clip. Loudness is measured once the timeline is final.
        begin(PolishStep.Voice)
        p =
            p.copy(
                clips =
                    p.clips.map { c ->
                        c.copy(
                            extras = JsonObject(c.extras + ("voiceNoise" to JsonPrimitive(true)) + ("voicePolish" to JsonPrimitive(true))),
                        )
                    },
                extras = JsonObject(p.extras + ("reduceNoise" to JsonPrimitive(true))),
            )
        if (stopped) return Result(start, executed, PolishReceipt(), true)

        // 5. Captions, only when asked for by the bold button.
        if (viral) {
            begin(PolishStep.Captions)
            p = withEmphasis(p)
        }

        // 6. Framing: the faces decide whether moves are welcome at all. A person walking around is the variety.
        begin(PolishStep.Framing)
        val faces = FaceSampler.sample(context, p, to = minOf(120.0, p.duration)) { stopped }
        val calm = AutoEdit.tracksAreCalm(listOf(faces.map { it.x }, faces.map { it.y }))
        val first = faces.firstOrNull()
        val anchor = first?.let { anchorOf(p, it) }
        if (calm) {
            val tooClose = first != null && AutoEdit.smartZoomScale(first.height) == null
            if (!tooClose) {
                val scale = first?.let { AutoEdit.smartZoomScale(it.height) }
                p = Polish.addOpeningPunch(p, anchor?.first ?: 0.5, anchor?.second ?: 0.5, scale)
            }
            // An emphasis zoom means "look at the speaker", so with no speaker there is none.
            if (first != null) {
                p = Polish.addEmphasisZooms(p, words(p), anchor?.first ?: 0.5, anchor?.second ?: 0.5).first
            }
        }
        if (stopped) return Result(start, executed, PolishReceipt(), true)

        // 7. Music, unless there is already some.
        begin(PolishStep.Music)
        p = addMusic(p)

        // 8. The last visual pass, so it sees everything else that moves.
        begin(PolishStep.PatternInterrupts)
        if (calm) {
            p = Polish.applyPatternInterrupts(p).first
        }

        if (viral) {
            begin(PolishStep.ColorsPop)
            p = colorsPop(p)
            begin(PolishStep.CutSounds)
            p = Polish.placeCutSounds(p).first
        }

        if (reframeTo != null) {
            begin(PolishStep.Reframe)
            p = reframed(p, reframeTo, faces)
        }

        p = Polish.capDensity(p)
        // Words were only borrowed for the run unless captions were asked for or already there.
        if (!viral) p = p.copy(captions = keepOwn(p.captions, ownCaptions))
        if (stopped) return Result(start, executed, PolishReceipt(), true)
        // Loud or quiet is measured last, on the final cut of the final voice.
        val measured = Sounds.analyse(context, p)
        val stamp = if (viral) "makeViralAppliedAt" else "autoPolishAppliedAt"
        p =
            p.copy(
                extras =
                    JsonObject(
                        p.extras +
                            measured.extras.filterKeys { it == "voiceWindows" || it == Sounds.LOUDNESS_KEY } +
                            (stamp to JsonPrimitive(isoNow())),
                    ),
            )
        return Result(p, executed, Polish.receiptOf(p, cut, cutSeconds, fillerCount), false)
    }

    /** The person's own captions, moved with every cut, or nothing. */
    private fun keepOwn(
        now: CaptionTrack?,
        own: CaptionTrack?,
    ) = if (own == null) null else now

    /** Beast captions with the strongest words marked and one emoji at most per line. */
    private fun withEmphasis(p: Project): Project {
        val track = p.captions ?: return p
        val cues =
            track.cues.map { cue ->
                val chosen = CaptionEmphasis.chosen(cue.words.map { it.text }).toSet()
                var emojiUsed = false
                cue.copy(
                    words =
                        cue.words.mapIndexed { i, w ->
                            if (i !in chosen) return@mapIndexed w
                            val e = if (emojiUsed) null else CaptionEmphasis.emoji(w.text)
                            if (e != null) emojiUsed = true
                            w.copy(emphasized = true, emoji = e)
                        },
                )
            }
        return p.copy(captions = CaptionTrack(cues, JsonObject(track.extras + ("preset" to JsonPrimitive("Beast")))))
    }

    private fun anchorOf(
        p: Project,
        face: FaceSample,
    ): Pair<Double, Double>? {
        val clip = p.clips.firstOrNull() ?: return null
        val file = Storage.store(context).resolve(clip.source, p.id)
        val size = Storage.videoSize(file) ?: return null
        return Reframe.canvasPoint(face.x, face.y, size.first.toDouble() / size.second, p.aspect.ratio)
    }

    private fun addMusic(p: Project): Project {
        if (!p.musicBeds.isNullOrEmpty()) return p
        val w = words(p).size
        val wps = if (p.duration > 0) w / p.duration else 0.0
        val avg = if (p.clips.isEmpty()) 0.0 else p.duration / p.clips.size
        val wanted = AutoEdit.musicLoopFor(AutoEdit.musicMood(wps, avg))
        val all = Sounds.music(context)
        val pick = all.firstOrNull { it.id == wanted } ?: all.firstOrNull() ?: return p
        val file = Sounds.file(context, pick, "music")
        val bed =
            MusicBed(
                newId(),
                MediaRef.Absolute(file.toURI().toString()),
                pick.seconds,
                start = firstOnset(p),
                volume = 0.32,
                duck = true,
                name = pick.id,
                extras = JsonObject(mapOf("autoAdded" to JsonPrimitive(true))),
            )
        return p.copy(musicBeds = listOf(bed))
    }

    /** The music comes in on the take's first sound, if that falls within half a second. */
    private fun firstOnset(p: Project): Double {
        val clip = p.clips.firstOrNull() ?: return 0.0
        val file = Storage.store(context).resolve(clip.source, p.id)
        val pcm = AudioExtractor.pcm16k(file, clip.trimStart, minOf(clip.trimEnd, clip.trimStart + 1.0)) ?: return 0.0
        val det = SilenceDetector()
        val e = det.energies(pcm)
        if (e.size < 4) return 0.0
        val floor = e.minOf { it.db }
        for (i in 1 until e.size) {
            val t = e[i].t / clip.speed
            if (t > 0.5) break
            if (t > 0.01 && e[i].db > floor + 12) return t
        }
        return 0.0
    }

    private fun colorsPop(p: Project): Project {
        if (p.clips.any { Polish.hasOwnGrade(it) }) return p
        val look = Looks.all(context).firstOrNull { it.name == Polish.BUNDLE_GRADE } ?: return p
        return p.copy(clips = p.clips.map { Looks.applyLook(it, look, 1f) })
    }

    /** Make Vertical, and the other shapes: changes the shape and keeps the speaker in the crop. */
    fun reshape(
        start: Project,
        aspect: Aspect,
    ): Project {
        val faces = FaceSampler.sample(context, start, to = start.duration, fps = 3.0, most = 240) { stopped }
        return reframed(start, aspect, faces).copy(modifiedAt = isoNow())
    }

    private fun reframed(
        p: Project,
        aspect: Aspect,
        faces: List<FaceSample>,
    ): Project {
        val keys = Reframe.plan(faces)
        val plan =
            JsonObject(
                mapOf(
                    "keys" to
                        JsonArray(
                            keys.map {
                                JsonObject(
                                    mapOf(
                                        "time" to JsonPrimitive(it.time),
                                        "centerX" to JsonPrimitive(it.x),
                                        "centerY" to JsonPrimitive(it.y),
                                    ),
                                )
                            },
                        ),
                    "zoom" to JsonPrimitive(1.0),
                ),
            )
        return p.copy(aspect = aspect, extras = JsonObject(p.extras + ("reframe" to plan)))
    }
}

/** The whole timeline's sound as one 16 kHz mono stream, for finding where it goes quiet. */
object TimelineAudio {
    fun mono(
        context: Context,
        p: Project,
    ): FloatArray {
        val store = Storage.store(context)
        val rate = AudioExtractor.RATE
        val out = FloatArray((p.duration * rate).toInt() + 1)
        for (clip in p.clips) {
            if (clip.reversed || clip.volume <= 0.0) continue
            val file = store.resolve(clip.source, p.id)
            val pcm = AudioExtractor.pcm16k(file, clip.trimStart, clip.trimEnd) ?: continue
            val at = (p.startTime(clip.id) * rate).toInt()
            val n = (pcm.size / clip.speed).toInt()
            for (i in 0 until n) {
                val src = (i * clip.speed).toInt()
                if (src < pcm.size && at + i < out.size) out[at + i] += (pcm[src] * clip.volume).toFloat()
            }
        }
        return out
    }
}
