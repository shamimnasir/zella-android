@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app.assistant

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import com.zellahq.ai.Args
import com.zellahq.ai.Tool
import com.zellahq.ai.ToolResult
import com.zellahq.ai.emptyParams
import com.zellahq.ai.enumProp
import com.zellahq.ai.obj
import com.zellahq.ai.prop
import com.zellahq.app.broll.BRollMaker
import com.zellahq.app.broll.StockProvider
import com.zellahq.app.callouts.ScreenReader
import com.zellahq.app.callouts.findSensitiveText
import com.zellahq.app.cutout.BackgroundPainter
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.Storage
import com.zellahq.app.export.ExportService
import com.zellahq.app.export.ExportStatus
import com.zellahq.app.looks.Looks
import com.zellahq.app.sound.Sounds
import com.zellahq.core.PremiumGate
import com.zellahq.core.audio.VoiceChain
import com.zellahq.core.auto.BRoll
import com.zellahq.core.captions.CaptionBuilder
import com.zellahq.core.motion.TextAnimationSpec
import com.zellahq.core.motion.TextMotionCatalog
import com.zellahq.core.motion.TextMotionPhase
import com.zellahq.core.motion.TextMotionPhaseKind
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.BackgroundChoice
import com.zellahq.core.project.Callout
import com.zellahq.core.project.CalloutKind
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.MusicBed
import com.zellahq.core.project.Project
import com.zellahq.core.project.SoundBite
import com.zellahq.core.project.TitleLook
import com.zellahq.core.project.TransitionTypes
import com.zellahq.core.project.ZoomBlock
import com.zellahq.core.project.addCallouts
import com.zellahq.core.project.callouts
import com.zellahq.core.project.clipAt
import com.zellahq.core.project.duration
import com.zellahq.core.project.latestOverlayStart
import com.zellahq.core.project.move
import com.zellahq.core.project.newId
import com.zellahq.core.project.newTitle
import com.zellahq.core.project.position
import com.zellahq.core.project.removeTransition
import com.zellahq.core.project.rippleDelete
import com.zellahq.core.project.setBackgroundEverywhere
import com.zellahq.core.project.setSpeed
import com.zellahq.core.project.setTransition
import com.zellahq.core.project.setVolume
import com.zellahq.core.project.split
import com.zellahq.core.project.startTime
import com.zellahq.core.project.transitionAfter
import com.zellahq.core.project.withField
import com.zellahq.core.sensitive.SensitiveBlurPlanner
import com.zellahq.core.sensitive.SensitiveMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.roundToInt

private fun fmt(v: Double) = "%.2f".format(v)

private fun bad(name: String) = ToolResult("Missing or invalid argument: $name", true)

private val noClips = ToolResult("The timeline is empty: add a clip first.", true)

private fun round3(v: Double) = if (v.isFinite()) (v * 1000).roundToInt() / 1000.0 else 0.0

/**
 * The summary the language model gets of the project. Clip lengths, names of looks, counts: text about
 * the edit. No file names or paths, and never a picture or sound.
 */
fun projectSummary(
    state: EditorState,
    selectedId: String?,
): String {
    val p = state.project.value
    return buildJsonObject {
        put("name", p.name)
        put("duration", round3(p.duration))
        put("currentTime", round3(state.position.value))
        put("isPlaying", state.playing.value)
        put("aspect", p.aspect.raw)
        put("clipCount", p.clips.size)
        put("selectedClipIndex", p.clips.indexOfFirst { it.id == selectedId })
        put(
            "clips",
            buildJsonArray {
                p.clips.forEachIndexed { i, c ->
                    add(
                        buildJsonObject {
                            put("index", i)
                            put("id", c.id)
                            put("start", round3(p.startTime(c.id)))
                            put("duration", round3(c.timelineDuration))
                            put("speed", c.speed)
                            put("volume", c.volume)
                            put("look", Looks.presetName(c) ?: "none")
                            put("is_broll", c.isBRoll)
                        },
                    )
                }
            },
        )
        put("hasCaptions", p.captions != null)
        put("captionPreset", p.captions?.extras?.get("preset") ?: JsonPrimitive("none"))
        put("transcriptWordCount", p.captions?.cues?.sumOf { it.words.size } ?: 0)
        put(
            "titles",
            buildJsonArray {
                p.titles.orEmpty().forEach {
                    add(
                        buildJsonObject {
                            put("text", it.text)
                            put("start", round3(it.start))
                        },
                    )
                }
            },
        )
        put("calloutCount", p.callouts.size)
        put(
            "transitions",
            buildJsonArray {
                p.transitions.orEmpty().forEach { t ->
                    add(
                        buildJsonObject {
                            put("afterIndex", p.clips.indexOfFirst { it.id == t.afterClipId })
                            put("type", t.type)
                            put("duration", round3(t.duration))
                        },
                    )
                }
            },
        )
        put("musicBedCount", p.musicBeds?.size ?: 0)
        put("soundEffectCount", p.soundBites?.size ?: 0)
        put("zoomBlockCount", p.zoomBlocks?.size ?: 0)
    }.toString()
}

/**
 * Everything the Assistant can do, each bound to the same edit the editor's own buttons make. A tool
 * exists here only when the feature does: nothing is faked, and nothing returns "done" without doing it.
 */
fun assistantTools(
    context: Context,
    state: EditorState,
    confirm: suspend (String) -> Boolean,
): List<Tool> {
    fun project() = state.project.value

    fun selected(): Clip? {
        val p = project()
        return p.clips.firstOrNull { it.id == state.selected.value } ?: p.clips.firstOrNull()
    }

    fun clipFrom(a: Args): Pair<Clip, Int>? {
        val p = project()
        a.int("clip_index")?.let { i -> p.clips.getOrNull(i)?.let { return it to i } }
        a.str("clip_id")?.let { id ->
            val i = p.clips.indexOfFirst { it.id == id }
            if (i >= 0) return p.clips[i] to i
        }
        return null
    }

    suspend fun autoFinished() {
        state.auto.first { it == null }
    }

    suspend fun polish(
        viral: Boolean,
        name: String,
    ): ToolResult {
        if (project().clips.isEmpty()) return noClips
        state.runAuto(viral)
        autoFinished()
        val r = state.receipt.value ?: return ToolResult("$name did not finish.", true)
        return ToolResult(
            "$name done: cut ${r.silencesCut} silences (${fmt(r.silenceSeconds)} s), ${r.fillersCut} filler words, " +
                "placed ${r.dissolves} transitions, ${r.zooms} zooms, ${r.sounds} sounds. One undo removes it all.",
        )
    }

    suspend fun words(): List<com.zellahq.core.project.CaptionWord>? {
        if (project().captions == null) {
            state.makeCaptions(null, false)
            state.captioning.first { it == null }
        }
        return project().captions?.cues?.flatMap { it.words }
    }

    return buildList {
        add(
            Tool(
                "get_project_state",
                "Summarize the current project: clips (0-based index, id, duration, start, speed, look), playhead, selection, captions, titles, callouts, transitions, audio, zooms. Call this to resolve references and to verify edits.",
                emptyParams(),
            ) { ToolResult(projectSummary(state, state.selected.value)) },
        )
        add(
            Tool("play_pause", "Toggle preview playback.", emptyParams()) {
                state.togglePlay()
                ToolResult(if (state.playing.value) "Paused." else "Playing.")
            },
        )
        add(
            Tool(
                "seek_to_time",
                "Move the playhead to a timeline time (seconds).",
                obj(mapOf("seconds" to prop("number", "Timeline time in seconds.")), listOf("seconds")),
            ) { a ->
                val t = a.num("seconds") ?: return@Tool bad("seconds")
                state.seek(t.coerceIn(0.0, project().duration))
                ToolResult("Playhead at ${fmt(state.position.value)}s.")
            },
        )
        add(
            Tool("undo_last_action", "Undo the most recent edit. Call get_project_state after to check.", emptyParams()) {
                if (state.history.value.canUndo) {
                    state.undo()
                    ToolResult("Undid the last edit: ${project().clips.size} clips, ${fmt(project().duration)}s.")
                } else {
                    ToolResult("Nothing to undo.")
                }
            },
        )
        add(
            Tool("redo_last_action", "Re-apply the last undone edit.", emptyParams()) {
                if (state.history.value.canRedo) {
                    state.redo()
                    ToolResult("Redid the edit: ${project().clips.size} clips, ${fmt(project().duration)}s.")
                } else {
                    ToolResult("Nothing to redo.")
                }
            },
        )
        add(
            Tool(
                "select_clip",
                "Select a clip so effect tools act on it. Provide clip_index (0-based) or clip_id.",
                obj(mapOf("clip_index" to prop("integer", "0-based clip index."), "clip_id" to prop("string", "Clip id."))),
            ) { a ->
                val (c, i) = clipFrom(a) ?: return@Tool bad("clip_index/clip_id")
                state.select(c.id)
                ToolResult("Selected clip $i.")
            },
        )
        add(
            Tool(
                "split_at_playhead",
                "Split the clip under the playhead (or at seconds) into two.",
                obj(mapOf("seconds" to prop("number", "Timeline seconds; omit for the playhead."))),
            ) { a ->
                a.num("seconds")?.let { state.seek(it) }
                val before = project().clips.size
                val t = state.position.value
                state.edit("split") { it.split(t) }
                ToolResult("Split at ${fmt(t)}s: now ${project().clips.size} clips (was $before).")
            },
        )
        add(
            Tool("ripple_delete_selected", "Delete the SELECTED clip and close the gap. Select a clip first.", emptyParams()) {
                val p = project()
                val c = p.clips.firstOrNull { it.id == state.selected.value } ?: return@Tool ToolResult("No clip selected: call select_clip first.", true)
                if (!c.isBRoll && p.duration > 0 && c.timelineDuration > 0.6 * p.duration) {
                    val ok = confirm("Delete this clip? It is most of the video (${fmt(c.timelineDuration)}s of ${fmt(p.duration)}s).")
                    if (!ok) return@Tool ToolResult("Cancelled: the clip was not deleted.")
                }
                state.edit("delete") { it.rippleDelete(c.id) }
                ToolResult("Deleted 1 clip: ${project().clips.size} remain.")
            },
        )
        add(
            Tool(
                "reorder_clip",
                "Move a clip to a new position. Provide clip_index or clip_id, and to_index (0-based destination).",
                obj(
                    mapOf(
                        "clip_index" to prop("integer", "0-based index of the clip to move."),
                        "clip_id" to prop("string", "Clip id."),
                        "to_index" to prop("integer", "0-based destination."),
                    ),
                    listOf("to_index"),
                ),
            ) { a ->
                val (c, from) = clipFrom(a) ?: return@Tool bad("clip_index/clip_id")
                val to = a.int("to_index") ?: return@Tool bad("to_index")
                state.edit("move") { it.move(c.id, to) }
                ToolResult("Moved clip $from to $to.")
            },
        )
        add(
            Tool(
                "magic_edit",
                "One tap clean-up, the same as auto_polish: silences, filler words, voice, framing, hook, music. Best default for 'edit this for me'.",
                emptyParams(),
            ) { polish(false, "Auto-Polish") },
        )
        add(Tool("auto_polish", "The full one-tap Auto-Polish, same as magic_edit.", emptyParams()) { polish(false, "Auto-Polish") })
        add(
            Tool("make_it_viral", "Auto-Polish plus pattern interrupts, a brighter grade and cut sounds.", emptyParams()) {
                polish(true, "Make it Viral")
            },
        )
        add(
            Tool("make_vertical", "Make it a Short: 9:16 canvas with a crop that keeps the speaker in frame. Reversible.", emptyParams()) {
                if (project().clips.isEmpty()) return@Tool noClips
                state.reshape(Aspect.Vertical)
                autoFinished()
                ToolResult("Vertical ready (9:16).")
            },
        )
        add(
            Tool(
                "auto_reframe",
                "Reshape to a new aspect, keeping the speaker in the crop.",
                obj(mapOf("aspect" to enumProp(Aspect.entries.map { it.raw }, "Target shape.")), listOf("aspect")),
            ) { a ->
                val aspect = Aspect.entries.firstOrNull { it.raw == a.str("aspect") } ?: return@Tool bad("aspect")
                if (project().clips.isEmpty()) return@Tool noClips
                state.reshape(aspect)
                autoFinished()
                ToolResult("Reframed to ${aspect.raw}.")
            },
        )
        add(
            Tool(
                "set_aspect",
                "Set the output shape with no face tracking: a plain canvas change.",
                obj(mapOf("aspect" to enumProp(Aspect.entries.map { it.raw }, "Shape.")), listOf("aspect")),
            ) { a ->
                val aspect = Aspect.entries.firstOrNull { it.raw == a.str("aspect") } ?: return@Tool bad("aspect")
                state.edit("shape") { it.copy(aspect = aspect) }
                ToolResult("Shape set to ${aspect.raw}.")
            },
        )
        add(
            Tool("auto_transitions", "Place a Cross Dissolve on every cut between clips that has none.", emptyParams()) {
                var placed = 0
                state.edit("transitions") { p0 ->
                    var p = p0
                    p0.clips.dropLast(1).forEach { c ->
                        if (p.transitionAfter(c.id) == null) {
                            p = p.setTransition(c.id, "Cross Dissolve", 0.5)
                            placed++
                        }
                    }
                    p
                }
                ToolResult("$placed transitions placed.")
            },
        )
        add(
            Tool(
                "add_transition",
                "Place a transition on the cut AFTER a clip (0-based index; not the last clip).",
                obj(
                    mapOf(
                        "after_index" to prop("integer", "0-based index of the clip the transition follows."),
                        "type" to enumProp(TransitionTypes.all, "Transition type."),
                        "duration" to prop("number", "Seconds (default 0.5)."),
                    ),
                    listOf("after_index", "type"),
                ),
            ) { a ->
                val p = project()
                val i = a.int("after_index")?.takeIf { it in p.clips.indices } ?: return@Tool bad("after_index")
                if (i >= p.clips.size - 1) return@Tool ToolResult("The last clip cannot host a transition.", true)
                val type = a.str("type")?.takeIf { it in TransitionTypes.all } ?: return@Tool bad("type")
                state.edit("transition") { it.setTransition(p.clips[i].id, type, a.num("duration") ?: 0.5) }
                ToolResult("$type placed after clip $i.")
            },
        )
        add(
            Tool(
                "remove_transition",
                "Remove the transition on the cut after a clip.",
                obj(mapOf("after_index" to prop("integer", "0-based clip index.")), listOf("after_index")),
            ) { a ->
                val p = project()
                val i = a.int("after_index")?.takeIf { it in p.clips.indices } ?: return@Tool bad("after_index")
                state.edit("transition") { it.removeTransition(p.clips[i].id) }
                ToolResult("Transition after clip $i removed.")
            },
        )
        add(
            Tool(
                "remove_silences",
                "Find and cut the silent gaps across the timeline. On this phone.",
                emptyParams(),
            ) {
                if (project().clips.isEmpty()) return@Tool noClips
                val p = project()
                val (out, cut) = withContext(Dispatchers.Default) { com.zellahq.app.auto.AutoPolish(context).silencesOnly(p) }
                state.edit("remove silences") { out }
                ToolResult("Cut $cut silences. The video is now ${fmt(project().duration)}s.")
            },
        )
        add(
            Tool(
                "remove_filler_words",
                "Cut filler words such as um and uh. Makes captions first if needed. On this phone.",
                emptyParams(),
            ) {
                if (words() == null) return@Tool ToolResult("No speech was found to work from.", true)
                val p = project()
                val (out, n) = com.zellahq.app.auto.AutoPolish(context).fillersOnly(p)
                state.edit("remove fillers") { out }
                ToolResult("Cut $n filler words.")
            },
        )
        add(
            Tool(
                "polish_voice",
                "Clean up and level the voice on every clip. On this phone.",
                emptyParams(),
            ) {
                if (project().clips.isEmpty()) return@Tool noClips
                state.edit("voice") { p ->
                    p.copy(
                        clips =
                            p.clips.map {
                                it.copy(
                                    extras =
                                        JsonObject(
                                            it.extras + ("voiceNoise" to JsonPrimitive(true)) + ("voicePolish" to JsonPrimitive(true)),
                                        ),
                                )
                            },
                    )
                }
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                state.analyseSound { done.complete(Unit) }
                done.await()
                val g = Sounds.boostDb(project())
                ToolResult("Voice cleaned and levelled (%+.1f dB).".format(g))
            },
        )
        add(
            Tool(
                "set_voice_tone",
                "Set the voice tone: Natural, Warm, Bright, Podcast or Cinematic.",
                obj(mapOf("tone" to enumProp(VoiceChain.Voicing.entries.map { it.name }, "Tone.")), listOf("tone")),
            ) { a ->
                val tone = VoiceChain.Voicing.entries.firstOrNull { it.name.equals(a.str("tone"), true) } ?: return@Tool bad("tone")
                state.edit("voice tone") { it.copy(extras = JsonObject(it.extras + ("voiceTone" to JsonPrimitive(tone.name)))) }
                ToolResult("Voice tone set to ${tone.name}.")
            },
        )
        add(
            Tool("auto_duck", "Find where the voice is, so music steps back under speech.", emptyParams()) {
                val done = kotlinx.coroutines.CompletableDeferred<Unit>()
                state.analyseSound { done.complete(Unit) }
                done.await()
                ToolResult("Voice found: music will step back for it.")
            },
        )
        add(
            Tool(
                "add_captions",
                "Transcribe on this phone and show animated captions. Optional style.",
                obj(mapOf("preset" to enumProp(CaptionBuilder.PRESETS, "Caption style."))),
            ) { a ->
                if (project().clips.isEmpty()) return@Tool noClips
                state.makeCaptions(project().captionLanguage, false)
                state.captioning.first { it == null }
                val cap = project().captions ?: return@Tool ToolResult("No speech was found, so no captions were added.")
                a.str("preset")?.takeIf { it in CaptionBuilder.PRESETS }?.let { pr ->
                    state.edit(
                        "caption style",
                    ) { it.copy(captions = cap.copy(extras = JsonObject(cap.extras + ("preset" to JsonPrimitive(pr))))) }
                }
                ToolResult("Captions added: ${cap.cues.sumOf { it.words.size }} words.")
            },
        )
        add(
            Tool(
                "apply_caption_preset",
                "Change the style of existing captions. Add captions first.",
                obj(mapOf("preset" to enumProp(CaptionBuilder.PRESETS, "Caption style.")), listOf("preset")),
            ) { a ->
                val pr = a.str("preset")?.takeIf { it in CaptionBuilder.PRESETS } ?: return@Tool bad("preset")
                val cap = project().captions ?: return@Tool ToolResult("No captions yet: call add_captions first.", true)
                state.edit(
                    "caption style",
                ) { it.copy(captions = cap.copy(extras = JsonObject(cap.extras + ("preset" to JsonPrimitive(pr))))) }
                ToolResult("Caption style set to $pr.")
            },
        )
        add(
            Tool("get_transcript", "Return the spoken words with their times. Transcribes on this phone if needed.", emptyParams()) {
                val w = words().orEmpty()
                if (w.isEmpty()) return@Tool ToolResult("No speech found in this project.")
                ToolResult("${w.size} words:\n" + w.take(600).joinToString(" ") { "[${fmt(it.start)}] ${it.text}" })
            },
        )
        add(
            Tool(
                "find_transcript",
                "Find where a phrase is spoken. Returns times you can seek to and cut around.",
                obj(mapOf("query" to prop("string", "Word to find.")), listOf("query")),
            ) { a ->
                val q = a.str("query")?.lowercase()?.ifBlank { null } ?: return@Tool bad("query")
                val hits = words().orEmpty().filter { it.text.lowercase().contains(q) }
                if (hits.isEmpty()) return@Tool ToolResult("No matches for \"$q\".")
                ToolResult(
                    "${hits.size} matches:\n" + hits.take(20).joinToString("\n") { "[${fmt(it.start)} to ${fmt(it.end)}] ${it.text}" },
                )
            },
        )
        add(
            Tool("clear_captions", "Remove all captions from the video.", emptyParams()) {
                val n = project().captions?.cues?.size ?: return@Tool ToolResult("There are no captions to remove.")
                state.edit("captions") { it.copy(captions = null) }
                ToolResult("Removed all $n captions.")
            },
        )
        add(
            Tool(
                "remove_caption_cue",
                "Remove one caption whose text contains the given words.",
                obj(mapOf("containing" to prop("string", "Words the caption contains.")), listOf("containing")),
            ) { a ->
                val needle = a.str("containing")?.ifBlank { null } ?: return@Tool bad("containing")
                val cap = project().captions ?: return@Tool ToolResult("There are no captions.", true)
                val cue = cap.cues.firstOrNull { it.text.contains(needle, true) } ?: return@Tool ToolResult("No caption contains \"$needle\".", true)
                state.edit("caption") { it.copy(captions = cap.copy(cues = cap.cues - cue)) }
                ToolResult("Removed the caption \"${cue.text}\".")
            },
        )
        add(
            Tool(
                "add_sound_effect",
                "Place a built-in sound effect at the playhead, or at seconds. Use list_options sound_effects for names.",
                obj(
                    mapOf("effect" to prop("string", "Sound effect name."), "seconds" to prop("number", "Timeline time.")),
                    listOf("effect"),
                ),
            ) { a ->
                val fx = Sounds.effects(context).firstOrNull { it.id.equals(a.str("effect"), true) } ?: return@Tool bad("effect")
                val t = a.num("seconds") ?: state.position.value
                state.edit("sound effect") { it.copy(soundBites = it.soundBites.orEmpty() + SoundBite(newId(), fx.id, t)) }
                ToolResult("${fx.id} added at ${fmt(t)}s.")
            },
        )
        add(
            Tool(
                "remove_sound_effect",
                "Remove a placed sound effect: the one nearest at_time, or the last.",
                obj(mapOf("at_time" to prop("number", "Timeline seconds (optional)."))),
            ) { a ->
                val bites = project().soundBites.orEmpty()
                if (bites.isEmpty()) return@Tool ToolResult("There are no sound effects.", true)
                val at = a.num("at_time")
                val target = if (at != null) bites.minBy { abs(it.atTime - at) } else bites.maxBy { it.atTime }
                state.edit("sound effect") { it.copy(soundBites = (it.soundBites.orEmpty() - target).ifEmpty { null }) }
                ToolResult("Removed the sound effect at ${fmt(target.atTime)}s.")
            },
        )
        add(
            Tool(
                "add_music",
                "Add a built-in music bed by name. Use list_options music for names.",
                obj(mapOf("mood" to prop("string", "Music name.")), listOf("mood")),
            ) { a ->
                val raw = a.str("mood")?.lowercase() ?: return@Tool bad("mood")
                val all = Sounds.music(context)
                val m =
                    all.firstOrNull {
                        it.id.lowercase() == raw || it.id.lowercase().contains(raw)
                    } ?: return@Tool ToolResult("Unknown music '$raw'. Available: ${all.joinToString { it.id }}.", true)
                val f = Sounds.file(context, m, "music")
                state.edit("music") {
                    it.copy(
                        musicBeds =
                            listOf(
                                MusicBed(newId(), MediaRef.Absolute(f.toURI().toString()), m.seconds, volume = 0.4, name = m.id),
                            ),
                    )
                }
                if (!Sounds.hasVoiceWindows(project())) state.findVoice()
                ToolResult("Added ${m.id} as the music.")
            },
        )
        add(
            Tool("remove_music", "Remove all music from the timeline.", emptyParams()) {
                val n = project().musicBeds?.size ?: 0
                if (n == 0) return@Tool ToolResult("There is no music to remove.")
                state.edit("music") { it.copy(musicBeds = null) }
                ToolResult("Removed the music.")
            },
        )
        add(
            Tool(
                "set_music_volume",
                "Set the music volume, 0 to 1.",
                obj(mapOf("volume" to prop("number", "0 to 1.")), listOf("volume")),
            ) { a ->
                val v = a.num("volume")?.coerceIn(0.0, 1.0) ?: return@Tool bad("volume")
                if (project().musicBeds.isNullOrEmpty()) return@Tool ToolResult("There is no music yet: add_music first.", true)
                state.edit("music volume") { p -> p.copy(musicBeds = p.musicBeds?.map { it.copy(volume = v) }) }
                ToolResult("Music volume set to ${(v * 100).roundToInt()}%.")
            },
        )
        add(
            Tool(
                "set_clip_volume",
                "Set a clip's volume: 1 is the original, 0 is mute. Targets clip_index, or the selected clip.",
                obj(mapOf("volume" to prop("number", "0 to 1."), "clip_index" to prop("integer", "0-based clip."))),
            ) { a ->
                val v = a.num("volume") ?: return@Tool bad("volume")
                val c = a.int("clip_index")?.let { project().clips.getOrNull(it) ?: return@Tool bad("clip_index") } ?: selected() ?: return@Tool noClips
                state.edit("volume") { it.setVolume(c.id, v) }
                ToolResult("Clip volume set to ${(v.coerceIn(0.0, 1.0) * 100).roundToInt()}%.")
            },
        )
        add(
            Tool(
                "set_speed",
                "Set the selected clip's speed. 1 is normal, 2 is double.",
                obj(mapOf("speed" to prop("number", "0.25 to 8.")), listOf("speed")),
            ) { a ->
                val v = a.num("speed") ?: return@Tool bad("speed")
                val c = selected() ?: return@Tool noClips
                state.edit("speed") { it.setSpeed(c.id, v) }
                ToolResult("Speed set to ${fmt(v)}.")
            },
        )
        add(
            Tool(
                "apply_look_preset",
                "Apply a colour look to the whole video. Use list_options color_looks for names.",
                obj(mapOf("preset" to prop("string", "Look name.")), listOf("preset")),
            ) { a ->
                if (project().clips.isEmpty()) return@Tool noClips
                val look = Looks.all(context).firstOrNull { it.name.equals(a.str("preset"), true) } ?: return@Tool bad("preset")
                state.edit("look all") { p -> p.copy(clips = p.clips.map { Looks.applyLook(it, look, Looks.DEFAULT_INTENSITY) }) }
                ToolResult("Applied the ${look.name} look.")
            },
        )
        add(
            Tool("clear_effects", "Clear the look and colour work on the selected clip. Keeps the footage.", emptyParams()) {
                val c = selected() ?: return@Tool noClips
                state.edit(
                    "clear",
                ) { p -> p.copy(clips = p.clips.map { if (it.id == c.id) it.copy(extras = JsonObject(it.extras - "effects")) else it }) }
                ToolResult("Effects cleared on the selected clip.")
            },
        )
        add(
            Tool(
                "add_zoom_block",
                "Add a punch-in zoom at the playhead. Optional peak scale (default 1.6).",
                obj(mapOf("scale" to prop("number", "Peak zoom, for example 1.6."))),
            ) { a ->
                if (project().clips.isEmpty()) return@Tool noClips
                val t = state.position.value
                state.edit("zoom") { it.copy(zoomBlocks = it.zoomBlocks.orEmpty() + ZoomBlock(newId(), t, scale = a.num("scale") ?: 1.6)) }
                ToolResult("Zoom added at ${fmt(t)}s (${project().zoomBlocks?.size} in all).")
            },
        )
        add(
            Tool(
                "edit_zoom_block",
                "Change a zoom's peak by index (see get_project_state).",
                obj(mapOf("index" to prop("integer", "0-based zoom index."), "scale" to prop("number", "Peak zoom.")), listOf("index")),
            ) { a ->
                val i = a.int("index") ?: return@Tool bad("index")
                val list = project().zoomBlocks.orEmpty()
                if (i !in list.indices) return@Tool bad("index")
                val s = a.num("scale") ?: return@Tool bad("scale")
                state.edit("zoom") { p -> p.copy(zoomBlocks = list.mapIndexed { k, z -> if (k == i) z.copy(scale = s) else z }) }
                ToolResult("Zoom $i updated.")
            },
        )
        add(
            Tool(
                "add_title",
                "Add a text title at the playhead.",
                obj(
                    mapOf(
                        "text" to prop("string", "Title text."),
                        "position" to enumProp(listOf("lowerThird", "center", "top"), "Placement (default center)."),
                        "animation_in" to prop("string", "In animation id (list_options title_animations)."),
                        "animation_loop" to prop("string", "Animation while on screen."),
                        "animation_out" to prop("string", "Animation as it leaves."),
                        "font_size" to prop("number", "Size (default 90)."),
                        "duration" to prop("number", "Seconds on screen (default 3)."),
                    ),
                    listOf("text"),
                ),
            ) { a ->
                val text = a.str("text")?.ifBlank { null } ?: return@Tool bad("text")
                val spec = motion(a, null).getOrElse { return@Tool bad(it.message.orEmpty()) }
                val d = a.num("duration") ?: 3.0
                val at = minOf(state.position.value, latestOverlayStart(project().duration, d))
                val y =
                    when (a.str("position")) {
                        "lowerThird" -> 0.78
                        "top" -> 0.16
                        else -> 0.5
                    }
                val title =
                    newTitle(
                        text,
                        at,
                        d,
                        buildMap {
                            put("position", position(0.5, y))
                            a.num("font_size")?.let { put("fontSize", JsonPrimitive(it)) }
                            spec?.let { put("textAnimation", it.toJson()) }
                        },
                    )
                state.edit("title") { it.copy(titles = it.titles.orEmpty() + title) }
                ToolResult("Title \"$text\" added at ${fmt(at)}s.")
            },
        )

        fun titleTarget(needle: String?) =
            project().titles.orEmpty().let { ts ->
                needle?.let { n ->
                    ts.firstOrNull {
                        it.text.contains(n, true)
                    }
                } ?: ts.lastOrNull()
            }
        add(
            Tool(
                "set_title_animation",
                "Set or clear a title's letter or word animation. Pass none to clear a phase.",
                obj(
                    mapOf(
                        "matching" to prop("string", "Words the title contains (optional)."),
                        "animation_in" to prop("string", "In animation id, or none."),
                        "animation_loop" to prop("string", "Loop animation id, or none."),
                        "animation_out" to prop("string", "Out animation id, or none."),
                    ),
                ),
            ) { a ->
                val t = titleTarget(a.str("matching")) ?: return@Tool ToolResult("There are no titles.", true)
                val spec = motion(a, TitleLook(t).animation).getOrElse { return@Tool bad(it.message.orEmpty()) }
                state.edit(
                    "title",
                ) { p -> p.copy(titles = p.titles?.map { if (it.id == t.id) it.withField("textAnimation", spec?.toJson()) else it }) }
                ToolResult("Title \"${t.text}\" animation updated.")
            },
        )
        add(
            Tool(
                "edit_title",
                "Change a title's text: the one matching, or the most recent.",
                obj(
                    mapOf("new_text" to prop("string", "The new text."), "matching" to prop("string", "Words it now contains (optional).")),
                    listOf("new_text"),
                ),
            ) { a ->
                val text = a.str("new_text")?.ifBlank { null } ?: return@Tool bad("new_text")
                val t = titleTarget(a.str("matching")) ?: return@Tool ToolResult("There are no titles to edit.", true)
                state.edit("title") { p -> p.copy(titles = p.titles?.map { if (it.id == t.id) it.copy(text = text) else it }) }
                ToolResult("Title is now \"$text\".")
            },
        )
        add(
            Tool(
                "remove_title",
                "Remove a title: the one matching, or the most recent.",
                obj(mapOf("matching" to prop("string", "Words the title contains (optional)."))),
            ) { a ->
                val t = titleTarget(a.str("matching")) ?: return@Tool ToolResult("There are no titles.", true)
                state.edit("title") { p -> p.copy(titles = p.titles?.filterNot { it.id == t.id }?.ifEmpty { null }) }
                ToolResult("Removed the title \"${t.text}\".")
            },
        )
        add(
            Tool(
                "add_callout",
                "Add a callout (arrow, box, label, spotlight, blur, pixelate and others). x, y are the top-left, all 0 to 1: the same numbers find_text_on_screen returns. To hide a token, key or password use blur_sensitive_text: it finds the text for you.",
                obj(
                    mapOf(
                        "kind" to enumProp(CalloutKind.entries.map { it.raw }, "Callout kind."),
                        "text" to prop("string", "Words for label, key press, number, emoji or note."),
                        "x" to prop("number", "Left edge 0 to 1 (default 0.3)."),
                        "y" to prop("number", "Top edge 0 to 1 (default 0.35)."),
                        "width" to prop("number", "Width 0 to 1 (default 0.4)."),
                        "height" to prop("number", "Height 0 to 1 (default 0.2)."),
                        "start" to prop("number", "Start, timeline seconds (default the playhead)."),
                        "duration" to prop("number", "Seconds on screen (default 3)."),
                    ),
                    listOf("kind"),
                ),
            ) { a ->
                val kind = CalloutKind.of(a.str("kind")) ?: return@Tool bad("kind")
                val start = (a.num("start") ?: state.position.value).coerceIn(0.0, project().duration)
                val d = a.num("duration") ?: 3.0
                val c =
                    Callout.create(
                        kind,
                        a.num("x") ?: 0.3,
                        a.num("y") ?: 0.35,
                        a.num("width") ?: 0.4,
                        a.num("height") ?: 0.2,
                        start,
                        d,
                        a.str("text"),
                    )
                state.edit("callout") { it.addCallouts(listOf(c)) }
                ToolResult("${kind.label} added from ${fmt(start)}s to ${fmt(start + d)}s.")
            },
        )
        add(
            Tool(
                "find_text_on_screen",
                "Read the text visible in the video (on this phone, nothing is sent while reading) at one time or across a range. Each line comes back with its time and box: x, y top-left, width, height, 0 to 1, the same numbers add_callout takes. Secret-looking values come back masked.",
                obj(
                    mapOf(
                        "query" to prop("string", "Only lines containing this (optional)."),
                        "time" to prop("number", "One frame at this time (default the playhead)."),
                        "start" to prop("number", "Scan a range from here, about once a second, up to 30 frames."),
                        "end" to prop("number", "Range end, seconds."),
                    ),
                ),
            ) { a ->
                if (project().clips.isEmpty()) return@Tool noClips
                val p = project()
                val at = state.position.value
                withContext(Dispatchers.Default) { findText(context, p, a, at) }
            },
        )
        add(
            Tool(
                "blur_sensitive_text",
                "Find and blur sensitive text on screen: access tokens, API keys, bearer tokens, passwords, emails, card numbers, or any phrase the user names. Reads rendered frames on this phone about every half second, makes one blur per secret for its whole time on screen, following it when it scrolls, as one undo step. Reports what was blurred, masked, never the secret.",
                obj(
                    mapOf(
                        "query" to prop("string", "The user's own words for what to hide (optional)."),
                        "secrets" to prop("boolean", "Also look for secret shapes (default true)."),
                        "include_labels" to prop("boolean", "Blur the label too, not just the value (default false)."),
                        "start" to prop("number", "Scan from, seconds (default 0)."),
                        "end" to prop("number", "Scan to, seconds (default the end)."),
                        "style" to enumProp(listOf("blur", "pixelate"), "Blur (default) or a pixel mosaic."),
                    ),
                ),
            ) { a ->
                if (project().clips.isEmpty()) return@Tool noClips
                val m = SensitiveMatcher(a.str("query"), a.bool("secrets") ?: true, a.bool("include_labels") ?: false)
                if (!m.secrets && m.query == null) return@Tool ToolResult("Nothing to look for: pass a query, or secrets=true.", true)
                val p = project()
                val report =
                    withContext(
                        Dispatchers.Default,
                    ) { findSensitiveText(context, p, m, a.num("start"), a.num("end"), a.str("style") == "pixelate") }
                if (report.callouts.isNotEmpty()) state.edit("blur") { it.addCallouts(report.callouts) }
                ToolResult(report.text)
            },
        )
        add(
            Tool(
                "remove_background",
                "Replace what is behind the person with a finished backdrop, on the whole video. Worked out on this phone.",
                emptyParams(),
            ) {
                if (project().clips.isEmpty()) return@Tool noClips
                state.edit("background") { it.setBackgroundEverywhere(BackgroundChoice.REMOVED) }
                ToolResult(
                    "Background replaced on all ${project().clips.size} clips. Finding the person takes a moment; the preview changes when it is done.",
                )
            },
        )
        add(
            Tool(
                "set_background",
                "Replace the background on the whole video. style: blur, cutout (black or a colour), color, or preset.",
                obj(
                    mapOf(
                        "style" to enumProp(listOf("blur", "cutout", "color", "preset"), "Background style."),
                        "blur_radius" to prop("number", "For blur (default 18)."),
                        "color_hex" to prop("string", "#RRGGBB for color or cutout."),
                        "preset_id" to enumProp((BackgroundPainter.images + BackgroundPainter.videos).map { it.id }, "For preset."),
                    ),
                    listOf("style"),
                ),
            ) { a ->
                val style = a.str("style")?.takeIf { it in listOf("blur", "cutout", "color", "preset") } ?: return@Tool bad("style")
                if (style == "preset" &&
                    (BackgroundPainter.images + BackgroundPainter.videos).none {
                        it.id == a.str("preset_id")
                    }
                ) {
                    return@Tool bad("preset_id")
                }
                if (project().clips.isEmpty()) return@Tool noClips
                val choice = BackgroundChoice(style, a.num("blur_radius"), a.str("color_hex"), null, a.str("preset_id"))
                state.edit("background") { it.setBackgroundEverywhere(choice) }
                ToolResult(
                    "Background set to $style on all ${project().clips.size} clips. Finding the person takes a moment; the preview changes when it is done.",
                )
            },
        )
        add(
            Tool("clear_background", "Put the original background back on the whole video.", emptyParams()) {
                state.edit("background") { it.setBackgroundEverywhere(null) }
                ToolResult("Original background restored.")
            },
        )
        add(
            Tool(
                "add_word_cards",
                "Add short full-screen cutaway cards, each showing a key word from the captions. Made on this phone with no network. These are generated cards, not stock footage.",
                emptyParams(),
            ) {
                if (words() == null) return@Tool ToolResult("No speech was found to pick words from.", true)
                val p = project()
                val sel = state.selected.value
                val r = withContext(Dispatchers.IO) { BRollMaker(context).cards(p, sel) }
                if (r.placed > 0) state.edit("cutaways") { r.project }
                ToolResult(r.note, r.placed == 0)
            },
        )
        add(
            Tool(
                "add_stock_broll",
                "Add stock video cutaways matched to words in the captions, from the person's own Pexels or Pixabay key. Only each search word is sent, never the video. If no key is saved, tell them to add one in the Cutaways panel.",
                obj(mapOf("provider" to enumProp(StockProvider.entries.map { it.raw }, "Stock site (default pexels)."))),
            ) { a ->
                val provider = StockProvider.entries.firstOrNull { it.raw == a.str("provider") } ?: StockProvider.Pexels
                val key = AssistantKeys(context).getNamed(provider.raw) ?: return@Tool ToolResult("There is no ${provider.displayName} key saved. They can add a free one in the Cutaways panel.", true)
                if (words() == null) return@Tool ToolResult("No speech was found to pick words from.", true)
                val p = project()
                val sel = state.selected.value
                val r = withContext(Dispatchers.IO) { BRollMaker(context).stock(p, sel, provider, key) }
                if (r.placed > 0) state.edit("cutaways") { r.project }
                ToolResult(r.note, r.placed == 0)
            },
        )
        add(
            Tool("remove_broll", "Remove every cutaway Auto B-Roll added. Regular clips are untouched.", emptyParams()) {
                val (out, n) = BRoll.removeAll(project())
                if (n == 0) return@Tool ToolResult("There are no cutaways on the timeline.")
                state.edit("remove cutaways") { out }
                ToolResult("Removed $n cutaways.")
            },
        )
        add(
            Tool(
                "describe_frame",
                "Look at a frame on this phone: faces and brightness. Use to judge framing or exposure. It does not read text: use find_text_on_screen for that.",
                obj(mapOf("seconds" to prop("number", "Timeline time (default the playhead)."))),
            ) { a ->
                val t = a.num("seconds") ?: state.position.value
                val p = project()
                withContext(Dispatchers.Default) { describeFrame(context, p, t) }
            },
        )
        add(
            Tool(
                "export_video",
                "Render and save the finished video. Asks the user to confirm first. Free exports are up to 2 minutes and 1080p; 4K and longer need Zella Pro.",
                obj(mapOf("quality" to enumProp(listOf("720p", "1080p", "4k"), "Quality (default 1080p)."))),
            ) { a ->
                val p = project()
                if (p.clips.isEmpty()) return@Tool ToolResult("The timeline is empty: nothing to export.", true)
                if (ExportService.status.value is ExportStatus.Running) {
                    return@Tool ToolResult(
                        "Another export is already running. Wait for it to finish.",
                        true,
                    )
                }
                val short =
                    when (a.str("quality")) {
                        "720p" -> 720
                        "4k" -> 2160
                        else -> 1080
                    }
                val ok = confirm("Export this video at ${if (short == 2160) "4K" else "${short}p"}? It can take a while.")
                if (!ok) return@Tool ToolResult("Export cancelled.")
                state.saveNow()
                ExportService.reset()
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ExportService::class.java)
                        .putExtra(ExportService.EXTRA_PROJECT, p.id)
                        .putExtra(ExportService.EXTRA_SHORT_SIDE, short),
                )
                when (val s = ExportService.status.first { it !is ExportStatus.Idle && it !is ExportStatus.Running }) {
                    is ExportStatus.Done -> ToolResult("Exported ${s.file.name}. It is ready on the export screen.")
                    is ExportStatus.Blocked ->
                        ToolResult(
                            "This export needs Zella Pro: ${s.needs.joinToString { it.title }}. Free exports are up to ${(PremiumGate.MAX_FREE_EXPORT_SECONDS / 60).toInt()} minutes at ${PremiumGate.MAX_FREE_HEIGHT}p.",
                            true,
                        )
                    is ExportStatus.Failed -> ToolResult("Export failed: ${s.message}", true)
                    else -> ToolResult("Export cancelled: nothing was saved.")
                }
            },
        )
        add(
            Tool(
                "list_options",
                "List the valid names for a category.",
                obj(
                    mapOf(
                        "category" to enumProp(listOf("color_looks", "caption_presets", "transitions", "sound_effects", "music", "callouts", "aspects", "title_animations"), "What to list."),
                    ),
                    listOf("category"),
                ),
            ) { a ->
                when (a.str("category")) {
                    "color_looks" -> ToolResult(Looks.all(context).joinToString { it.name })
                    "caption_presets" -> ToolResult(CaptionBuilder.PRESETS.joinToString())
                    "transitions" -> ToolResult(TransitionTypes.all.joinToString())
                    "sound_effects" -> ToolResult(Sounds.effects(context).joinToString { it.id })
                    "music" -> ToolResult(Sounds.music(context).joinToString { it.id })
                    "callouts" -> ToolResult(CalloutKind.entries.joinToString { it.raw })
                    "aspects" -> ToolResult(Aspect.entries.joinToString { it.raw })
                    "title_animations" -> ToolResult(TextMotionCatalog.all.joinToString { "${it.id} (${it.name})" })
                    else -> bad("category")
                }
            },
        )
    }
}

private fun motion(
    a: Args,
    base: TextAnimationSpec?,
): Result<TextAnimationSpec?> {
    var s = base ?: TextAnimationSpec()

    fun phase(
        key: String,
        kind: TextMotionPhaseKind,
    ): Result<TextMotionPhase?>? {
        val raw = a.str(key)?.trim() ?: return null
        if (raw.equals("none", true)) return Result.success(null)
        val preset =
            TextMotionCatalog.preset(raw) ?: TextMotionCatalog.all.firstOrNull { it.name.equals(raw, true) }
                ?: return Result.failure(IllegalArgumentException(key))
        if (!preset.supports(kind)) return Result.failure(IllegalArgumentException(key))
        return Result.success(TextMotionPhase(preset.id))
    }
    phase("animation_in", TextMotionPhaseKind.In)?.let { r -> s = s.copy(inPhase = r.getOrElse { return Result.failure(it) }) }
    phase("animation_loop", TextMotionPhaseKind.Loop)?.let { r -> s = s.copy(loop = r.getOrElse { return Result.failure(it) }) }
    phase("animation_out", TextMotionPhaseKind.Out)?.let { r -> s = s.copy(outPhase = r.getOrElse { return Result.failure(it) }) }
    return Result.success(if (s.isEmpty) null else s)
}

private fun findText(
    context: Context,
    p: Project,
    a: Args,
    playhead: Double,
): ToolResult {
    val dur = p.duration
    val ranged = a.num("start") != null || a.num("end") != null
    val (times, step) =
        if (ranged) {
            val s = (a.num("start") ?: 0.0).coerceIn(0.0, dur)
            SensitiveBlurPlanner.sampleTimes(s, (a.num("end") ?: dur).coerceIn(s, dur), 1.0, 30)
        } else {
            listOf((a.num("time") ?: playhead).coerceIn(0.0, dur)) to 0.0
        }
    val video = ScreenReader.renderForReading(context, p) ?: return ToolResult("The video could not be prepared for reading.", true)
    try {
        val scan = ScreenReader(context).use { it.scan(video, times, null, 40.0) }
        val masker = SensitiveMatcher()
        val q = a.str("query")?.trim()?.ifEmpty { null }
        val rows =
            scan.lines.filter { (_, l) -> q == null || SensitiveMatcher(q, false).matches(l.text).isNotEmpty() }.take(60).map { (t, l) ->
                buildJsonObject {
                    put("time", round3(t))
                    put("text", SensitiveMatcher.maskedLine(l.text, masker))
                    put("x", round3(l.box.x))
                    put("y", round3(l.box.y))
                    put("width", round3(l.box.w))
                    put("height", round3(l.box.h))
                }
            }
        val out =
            buildJsonObject {
                put("framesScanned", scan.framesScanned)
                put("step", round3(step))
                put("coordinates", "0 to 1, x and y are the top-left corner, same as add_callout")
                put("matches", rows.size)
                put("lines", kotlinx.serialization.json.JsonArray(rows))
            }.toString()
        return ToolResult(if (rows.isEmpty()) "No matching text found. $out" else out)
    } finally {
        video.delete()
    }
}

private fun describeFrame(
    context: Context,
    p: Project,
    t: Double,
): ToolResult {
    val (clip, local) = p.clipAt(t) ?: return ToolResult("No clip at that time.", true)
    val file = Storage.store(context).resolve(clip.source, p.id)
    if (!file.isFile) return ToolResult("That clip's file is missing.", true)
    val m = MediaMetadataRetriever()
    val raw =
        try {
            m.setDataSource(file.absolutePath)
            m.getFrameAtTime(((clip.trimStart + local) * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            m.release()
        } ?: return ToolResult("A frame could not be read from this clip.", true)
    val w = minOf(512, raw.width) and 1.inv()
    val h = (raw.height * w / raw.width) and 1.inv()
    val small = Bitmap.createScaledBitmap(raw, w, h, true).copy(Bitmap.Config.RGB_565, false)
    val faces = arrayOfNulls<FaceDetector.Face>(4)
    val n = FaceDetector(w, h, 4).findFaces(small, faces)
    val face =
        if (n == 0) {
            "no faces detected"
        } else {
            val main = faces.filterNotNull().maxBy { it.eyesDistance() }
            val c = android.graphics.PointF()
            main.getMidPoint(c)
            val x = c.x / w
            val y = c.y / h
            val across =
                if (x < 0.4) {
                    "left"
                } else if (x > 0.6) {
                    "right"
                } else {
                    "center"
                }
            val down =
                if (y < 0.4) {
                    "upper"
                } else if (y > 0.6) {
                    "lower"
                } else {
                    "middle"
                }
            "$n face${if (n == 1) "" else "s"}, main face $down-$across (x ${fmt(x.toDouble())}, y ${fmt(y.toDouble())})"
        }
    var luma = 0.0
    var count = 0
    for (y in 0 until h step 8) {
        for (x in 0 until w step 8) {
            val c = small.getPixel(x, y)
            luma += (0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255)) / 255
            count++
        }
    }
    val l = luma / maxOf(1, count)
    val bright =
        if (l < 0.28) {
            "dark"
        } else if (l > 0.7) {
            "bright"
        } else {
            "medium"
        }
    return ToolResult("Frame at ${fmt(t)}s: $face. Brightness: $bright (${fmt(l)}).")
}

/** The Assistant's standing instructions. */
fun assistantPrompt(): String =
    """
    You are the editing assistant built into Zella, a screen and camera recorder and video editor for beginners on Android. You help the person edit the project that is open by calling the provided tools. You cannot edit the video any other way, and you cannot see or hear it: you only get text.

    Rules:
    - Call get_project_state before acting whenever you need to resolve references like "clip 2", or to check the result of an edit. Clip indices start at 0.
    - Effect tools act on the selected clip. Use select_clip first when a clip is named. If nothing is selected, they use the first clip.
    - Prefer the most specific tool. Dead air: remove_silences. Um and uh: remove_filler_words. Subtitles: add_captions. Shape: make_vertical or set_aspect. A built-in sound: add_sound_effect.
    - For what was said ("find where I say welcome", "cut the pricing part"), use find_transcript or get_transcript for exact times, then seek_to_time, split_at_playhead and ripple_delete_selected.
    - To judge the picture ("is my face centred?", "is it too dark?"), use describe_frame.
    - To hide sensitive text on screen ("blur the access token", "hide my API key", "blur my email"), call blur_sensitive_text. It reads the frames on this phone and places the blurs itself. Pass the person's own words as query and leave secrets on. If it finds nothing, say exactly what was scanned and offer a blur box with add_callout (kind blur). To read or find other text use find_text_on_screen. Never repeat a secret's value.
    - "Clean this up for me" means magic_edit. "Make it a Short" means make_vertical.
    - export_video is the only tool that asks the person to confirm; just call it. Everything else applies at once, and the person can undo it.
    - Never invent file names. Tools needing a file the person has not picked do not exist here.
    - If a tool you want is not in your list, say that you cannot do that from here. Never say something was done unless a tool said so.
    - After tools run, say briefly in plain words what changed. If a tool returns an error, read it and try a corrected call or explain the problem. Reply in the language the person writes in.
    """.trimIndent()
