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
import com.zellahq.core.project.insertFreeze
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.setBackgroundEverywhere
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/** Where Zella keeps projects and imported media. */
object Storage {
    private val sizes = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    /** A video's width and height as it plays, with its rotation applied. Null if it cannot be read. */
    fun videoSize(file: File): Pair<Int, Int>? {
        sizes[file.path]?.let { return it }
        if (!file.isFile) return null
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.path)
            val w = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
            val h = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
            val r = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val size = if (r % 180 == 0) w to h else h to w
            sizes[file.path] = size
            size
        } catch (_: Exception) {
            null
        } finally {
            mmr.release()
        }
    }

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

    private const val CAMERA_PREFS = "camera-background"

    /** Remembers the choice for the recording that is about to start. Called from the camera screen. */
    fun chooseCameraBackground(
        context: Context,
        choice: com.zellahq.core.project.BackgroundChoice?,
    ) = context.getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE).edit().putString("choice", choice?.json()?.toString()).apply()

    fun chosenCameraBackground(context: Context): com.zellahq.core.project.BackgroundChoice? =
        context.getSharedPreferences(CAMERA_PREFS, Context.MODE_PRIVATE).getString("choice", null)?.let {
            com.zellahq.core.project.BackgroundChoice.of(kotlinx.serialization.json.Json.parseToJsonElement(it))
        }

    /** Screen recordings keep their picture: this only applies to what the camera shot. */
    private fun cameraBackground(
        context: Context,
        file: File,
    ) = if (file.name.startsWith("camera")) chosenCameraBackground(context) else null

    /** A project with one clip made from [file]. */
    fun newProject(
        context: Context,
        file: File,
        name: String,
    ): Project? {
        val len = durationSeconds(file) ?: return null
        var p =
            Project(id = newId(), name = name, createdAt = isoNow(), modifiedAt = isoNow())
                .append(Clip(newId(), MediaRef.Absolute(file.toURI().toString()), len))
        // A camera recording made with "Background after recording" picked gets it now.
        cameraBackground(context, file)?.let { p = p.setBackgroundEverywhere(it) }
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

    /** One-tap editing: what is running now, and what the last run did. */
    data class AutoProgress(val viral: Boolean, val step: com.zellahq.core.auto.PolishStep?, val index: Int, val total: Int)

    private val mutableAuto = MutableStateFlow<AutoProgress?>(null)
    val auto: StateFlow<AutoProgress?> = mutableAuto
    private val mutableReceipt = MutableStateFlow<com.zellahq.core.auto.PolishReceipt?>(null)
    val receipt: StateFlow<com.zellahq.core.auto.PolishReceipt?> = mutableReceipt
    private var autoRunner: com.zellahq.app.auto.AutoPolish? = null

    /** The project as it was before the last one-tap run this session, for undoing the whole run at once. */
    private val mutableBeforeAuto = MutableStateFlow<Project?>(null)
    val beforeAuto: StateFlow<Project?> = mutableBeforeAuto

    fun runAuto(viral: Boolean) {
        if (mutableAuto.value != null) return
        val start = project.value
        val runner = com.zellahq.app.auto.AutoPolish(context)
        autoRunner = runner
        mutableAuto.value = AutoProgress(viral, null, 0, 0)
        mutableReceipt.value = null
        Thread({
            val r =
                runCatching {
                    runner.run(start, viral) { step, i, total -> main.post { mutableAuto.value = AutoProgress(viral, step, i, total) } }
                }.getOrNull()
            main.post {
                mutableAuto.value = null
                autoRunner = null
                if (r != null && !r.stopped) {
                    mutableBeforeAuto.value = start
                    mutableReceipt.value = r.receipt
                    edit(if (viral) "make it viral" else "auto polish") { r.project }
                }
            }
        }, "auto-polish").start()
    }

    fun stopAuto() = autoRunner?.stop()

    fun undoAuto() {
        val before = mutableBeforeAuto.value ?: return
        mutableBeforeAuto.value = null
        mutableReceipt.value = null
        edit("undo one tap") { before }
    }

    /** Make Vertical and the other shapes. Keeps the speaker in the crop. */
    fun reshape(aspect: com.zellahq.core.project.Aspect) {
        if (mutableAuto.value != null) return
        val start = project.value
        mutableAuto.value = AutoProgress(false, com.zellahq.core.auto.PolishStep.Reframe, 1, 1)
        val runner = com.zellahq.app.auto.AutoPolish(context)
        Thread({
            val out = runCatching { runner.reshape(start, aspect) }.getOrNull()
            main.post {
                mutableAuto.value = null
                if (out != null) edit("shape") { out }
            }
        }, "reshape").start()
    }

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

    private val mutableBaking = MutableStateFlow(false)
    private var bakingFor: Project? = null

    /** True while transitions are being rendered; they play as plain cuts until then. */
    val baking: StateFlow<Boolean> = mutableBaking

    private val mutableReversing = MutableStateFlow(false)

    /** True while a backwards copy is being made; the clip plays forwards until then. */
    val reversing: StateFlow<Boolean> = mutableReversing

    data class History(val canUndo: Boolean, val canRedo: Boolean)

    private val mutablePlayer = MutableStateFlow(previewPlayer(context))

    /** Changes when a project gains or loses its first transition; the preview view re-attaches. */
    val playerFlow: StateFlow<CompositionPlayer> = mutablePlayer
    val player: CompositionPlayer get() = mutablePlayer.value

    private val listener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mutablePlaying.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) mutablePlaying.value = false
            }
        }

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

    /** Back to the main thread, for work that finished in the background. */
    fun onMain(block: () -> Unit) {
        main.post(block)
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

    /** Freeze frame: holds the picture at [at] for two seconds, with no sound. One undo removes it. */
    fun freezeFrame(at: Double) {
        val start = project.value
        Thread({
            val f = runCatching { com.zellahq.app.depth.Stills.freeze(context, start, at) }.getOrNull()
            if (f != null) {
                main.post {
                    edit(
                        "freeze",
                    ) { it.insertFreeze(f.toURI().toString(), at, com.zellahq.app.depth.Stills.FREEZE_SECONDS) }
                }
            }
        }, "freeze").start()
    }

    /** How far along finding the person is, or null. The clip plays as it was until this finishes. */
    private val mutableFinding = MutableStateFlow<Float?>(null)
    val finding: StateFlow<Float?> = mutableFinding
    private var matteBaker: com.zellahq.app.cutout.MatteBaker? = null

    private fun startMatte(files: List<java.io.File>) {
        if (matteBaker != null) return
        val baker = com.zellahq.app.cutout.MatteBaker(context)
        matteBaker = baker
        mutableFinding.value = 0f
        Thread({
            files.forEachIndexed { i, f ->
                baker.bake(f) { x -> main.post { if (matteBaker === baker) mutableFinding.value = (i + x) / files.size } }
            }
            main.post {
                matteBaker = null
                mutableFinding.value = null
                rebuild()
            }
        }, "find-person").start()
    }

    fun stopFinding() = matteBaker?.stop()

    private fun rebuild() {
        val p = project.value
        if (p.clips.isEmpty()) {
            player.clearMediaItems()
            return
        }
        val built = builder.build(p)
        mutableMissing.value = built.missing.map { it.id }
        player.setComposition(built.composition, (position.value * 1000).toLong())
        player.prepare()
        if (built.needsMatte.isNotEmpty()) startMatte(built.needsMatte)
        // Clips marked reversed get their reversed copy made in the background, then the preview rebuilds.
        mutableReversing.value = built.needsReverse.isNotEmpty()
        if (built.needsReverse.isNotEmpty()) {
            io.execute {
                built.needsReverse.forEach { ReverseRenderer.render(context, store, p.id, it) }
                main.post { if (project.value == p) rebuild() }
            }
        } else if (built.needsBake.isNotEmpty() && p !== bakingFor) {
            // Transitions play as plain cuts until their short videos are rendered.
            bakingFor = p
            mutableBaking.value = true
            io.execute {
                val canvas = canvasSize(p)
                built.needsBake.forEach { join ->
                    if (project.value === p || project.value == p) TransitionBaker.bake(context, builder, p, join, canvas)
                }
                main.post {
                    bakingFor = null
                    mutableBaking.value = false
                    if (project.value == p) rebuild()
                }
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
    }

    fun release() {
        matteBaker?.stop()
        main.removeCallbacks(ticker)
        saveNow()
        player.release()
        io.shutdown()
    }
}

/** The preview player. One picture track, whatever the project holds. */
fun previewPlayer(context: Context): CompositionPlayer = CompositionPlayer.Builder(context).build()

/** The caption font a track asks for: an imported font by its PostScript name, else bold system text. */
fun captionTypeface(
    context: Context,
    track: com.zellahq.core.project.CaptionTrack,
): android.graphics.Typeface {
    val name = (track.extras["fontName"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    return com.zellahq.app.captions.FontLibrary.typeface(context, name) ?: android.graphics.Typeface.DEFAULT_BOLD
}
