// Media3's composition preview and export are marked unstable and experimental. Zella depends on
// them on purpose: they are the only way to preview exactly what exports. Pinned to 1.10.1.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.zellahq.app.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.UndoStack
import com.zellahq.core.project.append
import com.zellahq.core.project.clipAt
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/** Where Zella keeps projects and imported media. */
object Storage {
    fun projects(context: Context) = File(context.getExternalFilesDir(null), "projects").apply { mkdirs() }

    fun imports(context: Context) = File(context.getExternalFilesDir(null), "imports").apply { mkdirs() }

    fun exports(context: Context) = File(context.getExternalFilesDir(null), "exports").apply { mkdirs() }

    fun store(context: Context) = ProjectStore(projects(context))

    /** Length of a video or audio file in seconds, or null when it cannot be read. */
    fun durationSeconds(file: File): Double? =
        runCatching {
            MediaMetadataRetriever().use {
                it.setDataSource(file.path)
                it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?.div(1000.0)
            }
        }.getOrNull()

    /** A project with one clip made from [file]. */
    fun newProject(
        context: Context,
        file: File,
        name: String,
    ): Project? {
        val len = durationSeconds(file) ?: return null
        val p =
            Project(id = newId(), name = name, createdAt = isoNow(), modifiedAt = isoNow())
                .append(Clip(newId(), MediaRef.Absolute(file.toURI().toString()), len))
        store(context).save(p)
        return p
    }

    /** Copies a picked gallery video into Zella's storage: content links can stop working later. */
    fun importUri(
        context: Context,
        uri: Uri,
    ): File? =
        runCatching {
            val out = File(imports(context), "Import-${System.currentTimeMillis()}.mp4")
            context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
            out
        }.getOrNull()
}

/**
 * Everything the editor screen shows, in one place: the project, undo, the preview player, and
 * where the playhead is. Edits go through [edit], which records undo, rebuilds the preview and
 * saves. The player lives on the main thread, as Media3 requires.
 */
@OptIn(UnstableApi::class)
class EditorState(
    private val context: Context,
    initial: Project,
) {
    private val store = Storage.store(context)
    private val undo = UndoStack(initial)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    val builder = appComposition(context, store, reversedFile = { ReverseRenderer.cached(context, store, project.value.id, it) })

    private val mutableProject = MutableStateFlow(initial)
    val project: StateFlow<Project> = mutableProject

    private val mutablePosition = MutableStateFlow(0.0)

    /** Playhead, timeline seconds. */
    val position: StateFlow<Double> = mutablePosition

    private val mutablePlaying = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = mutablePlaying

    private val mutableSelected = MutableStateFlow(initial.clips.firstOrNull()?.id)
    val selected: StateFlow<String?> = mutableSelected

    private val mutableHistory = MutableStateFlow(History(false, false))
    val history: StateFlow<History> = mutableHistory

    /** Clips whose file is gone, shown as a warning instead of a silent black gap. */
    private val mutableMissing = MutableStateFlow(emptyList<String>())
    val missing: StateFlow<List<String>> = mutableMissing

    private val mutableStill = MutableStateFlow<android.graphics.Bitmap?>(null)

    /**
     * A still of the frame under the playhead, shown over the preview while paused in a project with
     * a fade. The two-track player does not draw a new frame after a paused seek (PausedSeekTest),
     * so scrubbing would show a stale picture. Null when the player draws the right frame itself.
     */
    val still: StateFlow<android.graphics.Bitmap?> = mutableStill
    private val stills = Executors.newSingleThreadExecutor()

    @Volatile private var stillRequest = 0

    /** Caption progress 0 to 1 while making captions, null otherwise. */
    private val mutableCaptioning = MutableStateFlow<Float?>(null)
    val captioning: StateFlow<Float?> = mutableCaptioning
    private val mutableCaptionNote = MutableStateFlow<CaptionNote?>(null)
    val captionNote: StateFlow<CaptionNote?> = mutableCaptionNote
    private var captionEngine: com.zellahq.app.captions.CaptionEngine? = null

    sealed interface CaptionNote {
        data class Made(val words: Int, val language: String) : CaptionNote

        data class NoWords(val language: String) : CaptionNote

        data object NoSound : CaptionNote

        data object Stopped : CaptionNote

        data class Failed(val message: String) : CaptionNote
    }

    /** Makes captions in the background. One step of undo when done. */
    fun makeCaptions(
        language: String?,
        translate: Boolean,
    ) {
        if (mutableCaptioning.value != null) return
        player.pause()
        mutableCaptionNote.value = null
        mutableCaptioning.value = 0f
        val engine = com.zellahq.app.captions.CaptionEngine(context).also { captionEngine = it }
        val start = project.value
        Thread({
            val out = engine.run(start, language, translate) { f -> main.post { mutableCaptioning.value = f } }
            main.post {
                mutableCaptioning.value = null
                captionEngine = null
                mutableCaptionNote.value =
                    when (out) {
                        is com.zellahq.app.captions.CaptionEngine.Outcome.Done -> {
                            android.util.Log.i("ZellaCaptions", "made ${out.words} words, ${out.language}, ${out.tookMs} ms")
                            // Apply onto the current project, in case the person edited meanwhile.
                            edit("captions") { it.copy(captions = out.project.captions, captionLanguage = out.project.captionLanguage) }
                            CaptionNote.Made(out.words, out.language)
                        }
                        is com.zellahq.app.captions.CaptionEngine.Outcome.NoWords -> CaptionNote.NoWords(out.language)
                        com.zellahq.app.captions.CaptionEngine.Outcome.NoSound -> CaptionNote.NoSound
                        com.zellahq.app.captions.CaptionEngine.Outcome.Stopped -> CaptionNote.Stopped
                        is com.zellahq.app.captions.CaptionEngine.Outcome.Failed -> CaptionNote.Failed(out.message)
                    }
            }
        }, "captions").start()
    }

    fun stopCaptions() = captionEngine?.stop()

    /** Finds the voice in the background so music can step back for it. Changes nothing else. */
    fun findVoice() = analyse(setOf("voiceWindows")) {}

    /** Finds the voice and measures loudness in the background, then applies both as one undo step. */
    fun analyseSound(done: () -> Unit) = analyse(setOf("voiceWindows", com.zellahq.app.sound.Sounds.LOUDNESS_KEY), done)

    private fun analyse(
        keys: Set<String>,
        done: () -> Unit,
    ) {
        val start = project.value
        Thread({
            val analysed = runCatching { com.zellahq.app.sound.Sounds.analyse(context, start) }.getOrNull()
            main.post {
                analysed?.let { a ->
                    edit("sound") {
                        it.copy(
                            extras =
                                kotlinx.serialization.json.JsonObject(
                                    it.extras +
                                        a.extras.filterKeys {
                                                k ->
                                            k in keys
                                        },
                                ),
                        )
                    }
                }
                done()
            }
        }, "analyse-sound").start()
    }

    private val mutableReversing = MutableStateFlow(false)

    /** True while a backwards copy is being made; the clip plays forwards until then. */
    val reversing: StateFlow<Boolean> = mutableReversing

    data class History(val canUndo: Boolean, val canRedo: Boolean)

    private var multi = needsMulti(initial)
    private val mutablePlayer = MutableStateFlow(previewPlayer(context, multi))

    /** Changes when a project gains or loses its first transition; the preview view re-attaches. */
    val playerFlow: StateFlow<CompositionPlayer> = mutablePlayer
    val player: CompositionPlayer get() = mutablePlayer.value

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mutablePlaying.value = isPlaying
                if (isPlaying) {
                    mutableStill.value = null
                } else {
                    requestStill()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) mutablePlaying.value = false
            }
        }

    private fun needsMulti(p: Project) = !p.transitions.isNullOrEmpty() && p.clips.size > 1

    private val ticker =
        object : Runnable {
            override fun run() {
                if (player.isPlaying) mutablePosition.value = player.currentPosition / 1000.0
                main.postDelayed(this, 33)
            }
        }

    init {
        player.addListener(listener)
        rebuild()
        main.post(ticker)
    }

    fun select(clipId: String?) {
        mutableSelected.value = clipId
    }

    /** Applies one edit as one undo step. An edit that changes nothing does nothing. */
    fun edit(
        label: String,
        change: (Project) -> Project,
    ) {
        val next = change(project.value)
        if (!undo.apply(next.copy(modifiedAt = project.value.modifiedAt), label)) return
        commit(undo.current.copy(modifiedAt = isoNow()))
    }

    fun undo() = undo.undo()?.let { commit(it.copy(modifiedAt = isoNow())) }

    fun redo() = undo.redo()?.let { commit(it.copy(modifiedAt = isoNow())) }

    private fun commit(p: Project) {
        mutableProject.value = p
        if (p.clips.none { it.id == selected.value }) mutableSelected.value = p.clipAt(position.value)?.first?.id
        mutablePosition.value = position.value.coerceIn(0.0, p.duration)
        mutableHistory.value = History(undo.canUndo, undo.canRedo)
        rebuild()
        save(p)
    }

    private fun save(p: Project) {
        io.execute {
            runCatching { store.save(p, allowCollapse = true) } // the user deleted the clips on purpose
        }
    }

    fun saveNow() = save(project.value)

    private fun rebuild() {
        val p = project.value
        if (p.clips.isEmpty()) {
            player.clearMediaItems()
            return
        }
        val wantMulti = needsMulti(p)
        if (wantMulti != multi) {
            multi = wantMulti
            val old = player
            mutablePlayer.value = previewPlayer(context, multi).also { it.addListener(listener) }
            old.release()
        }
        val built = builder.build(p)
        mutableMissing.value = built.missing.map { it.id }
        player.setComposition(built.composition, (position.value * 1000).toLong())
        player.prepare()
        requestStill()
        // Clips marked reversed get their reversed copy made in the background, then the preview rebuilds.
        mutableReversing.value = built.needsReverse.isNotEmpty()
        if (built.needsReverse.isNotEmpty()) {
            io.execute {
                built.needsReverse.forEach { ReverseRenderer.render(context, store, p.id, it) }
                main.post { if (project.value == p) rebuild() }
            }
        }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (position.value >= project.value.duration - 0.05) seek(0.0)
            player.play()
        }
    }

    /** Scrubbing: move the playhead and show that frame. */
    fun seek(seconds: Double) {
        val t = seconds.coerceIn(0.0, project.value.duration)
        mutablePosition.value = t
        player.seekTo((t * 1000).toLong())
        requestStill()
    }

    /** Reads the frame under the playhead from the clip's file. Only the newest request is shown. */
    private fun requestStill() {
        if (!multi || player.isPlaying) {
            mutableStill.value = null
            return
        }
        val id = ++stillRequest
        val p = project.value
        val t = position.value
        stills.execute {
            if (id != stillRequest) return@execute // the person has already scrubbed on
            val (clip, start) = p.clipAt(t) ?: return@execute
            val local = (t - start).coerceAtLeast(0.0)
            val sourceSec = if (clip.reversed) clip.trimEnd - local * clip.speed else clip.trimStart + local * clip.speed
            val bmp =
                runCatching {
                    MediaMetadataRetriever().use { m ->
                        m.setDataSource(store.resolve(clip.source, p.id).path)
                        m.getScaledFrameAtTime((sourceSec * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, 720, 1280)
                    }
                }.getOrNull()
            main.post { if (id == stillRequest) mutableStill.value = bmp }
        }
    }

    fun release() {
        main.removeCallbacks(ticker)
        saveNow()
        player.release()
        io.shutdown()
        stills.shutdown()
    }
}

/**
 * The preview player. A cross-fade puts clips on two tracks, which needs the multiple-input video
 * graph (the default single-input graph refuses it). But with the multiple-input graph a seek while
 * paused never draws the new frame (PausedSeekTest, emulator, 2026-11-04). So: single-input unless
 * the project has a transition.
 */
fun previewPlayer(
    context: Context,
    multi: Boolean,
): CompositionPlayer =
    CompositionPlayer
        .Builder(context)
        .apply { if (multi) setVideoGraphFactory(androidx.media3.effect.MultipleInputVideoGraph.Factory()) }
        .build()

/** The caption font a track asks for: an imported font by its PostScript name, else bold system text. */
fun captionTypeface(
    context: Context,
    track: com.zellahq.core.project.CaptionTrack,
): android.graphics.Typeface {
    val name = (track.extras["fontName"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return com.zellahq.app.captions.FontLibrary.typeface(context, name) ?: android.graphics.Typeface.DEFAULT_BOLD
}
