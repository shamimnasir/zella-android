@file:Suppress("ktlint:standard:max-line-length")

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
import com.zellahq.app.R
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
    private val durations = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Double>>()

    /** Remembered per file: opening a video to read this takes long enough to be felt on every edit. */
    fun durationSeconds(file: File): Double? {
        val stamp = file.lastModified()
        durations[file.path]?.takeIf { it.first == stamp }?.let { return it.second }
        return readDuration(file)?.also { durations[file.path] = stamp to it }
    }

    private fun readDuration(file: File): Double? =
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
            // Through a temporary name: a copy cut short by a full disk must not pass for a video.
            val part = File(out.path + ".part")
            try {
                context.contentResolver.openInputStream(uri)!!.use { i -> part.outputStream().use { i.copyTo(it) } }
                check(part.renameTo(out))
            } finally {
                part.delete()
            }
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

    /** Saves wait for nothing but each other. On [io] they sat behind long reverses and transition renders. */
    private val saver = Executors.newSingleThreadExecutor()

    @Volatile private var released = false

    /** A line for the person about something that happened in the background, as a string resource. */
    private val mutableNote = MutableStateFlow<Int?>(null)
    val note: StateFlow<Int?> = mutableNote

    /** Files and joins that failed, or that the person stopped. They are not retried until something changes. */
    private val matteGaveUp = HashSet<String>()
    private val reverseGaveUp = HashSet<String>()
    private val bakeGaveUp = HashSet<String>()

    /** Background work that must never take the app down: a failure becomes a line on screen. */
    private fun background(
        name: String,
        block: () -> Unit,
    ) = Thread({
        try {
            block()
        } catch (t: Throwable) {
            android.util.Log.e("ZellaEditor", name, t)
            main.post { if (!released) mutableNote.value = R.string.ed_background_failed }
        }
    }, name).start()

    /** A long job may run this long only while nothing else is. */
    val busy get() = mutableAuto.value != null || mutableCaptioning.value != null

    /**
     * Applies work computed from the project as it was at [start]. Replacing the project with that result
     * would throw away anything edited since, so when the project has moved on it says so and keeps the edits.
     */
    fun applyResult(
        start: Project,
        label: String,
        result: Project,
    ): Boolean {
        if (released) return false
        if (project.value != start) {
            mutableNote.value = R.string.ed_changed_meanwhile
            return false
        }
        edit(label) { result }
        return true
    }

    val builder = appComposition(context, store, reversedFile = { ReverseRenderer.cached(context, store, project.value.id, it) })

    /** How far along finding the person is, or null. The clip plays as it was until this finishes. */
    private val mutableFinding = MutableStateFlow<Float?>(null)
    val finding: StateFlow<Float?> = mutableFinding
    private var matteBaker: com.zellahq.app.cutout.MatteBaker? = null

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
        done: (Boolean) -> Unit = {},
    ) {
        if (busy) {
            done(false)
            return
        }
        player.pause()
        mutableCaptionNote.value = null
        mutableCaptioning.value = 0f
        val engine = com.zellahq.app.captions.CaptionEngine(context).also { captionEngine = it }
        val start = project.value
        background("captions") {
            val out =
                runCatching { engine.run(start, language, translate) { f -> main.post { mutableCaptioning.value = f } } }
                    .getOrElse { com.zellahq.app.captions.CaptionEngine.Outcome.Failed(context.getString(R.string.cap_failed)) }
            main.post {
                mutableCaptioning.value = null
                captionEngine = null
                if (released) return@post
                mutableCaptionNote.value =
                    when (out) {
                        is com.zellahq.app.captions.CaptionEngine.Outcome.Done -> {
                            android.util.Log.i("ZellaCaptions", "made ${out.words} words, ${out.language}, ${out.tookMs} ms")
                            // Apply onto the current project, in case the person edited meanwhile.
                            edit("captions") { it.copy(captions = out.project.captions, captionLanguage = out.project.captionLanguage) }
                            CaptionNote.Made(out.words, out.language)
                        }
                        is com.zellahq.app.captions.CaptionEngine.Outcome.NoWords -> {
                            done(false)
                            CaptionNote.NoWords(out.language)
                        }
                        com.zellahq.app.captions.CaptionEngine.Outcome.NoSound -> {
                            done(false)
                            CaptionNote.NoSound
                        }
                        com.zellahq.app.captions.CaptionEngine.Outcome.Stopped -> {
                            done(false)
                            CaptionNote.Stopped
                        }
                        is com.zellahq.app.captions.CaptionEngine.Outcome.Failed -> {
                            done(false)
                            CaptionNote.Failed(out.message)
                        }
                    }
            }
        }
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

    fun runAuto(
        viral: Boolean,
        done: (Boolean) -> Unit = {},
    ) {
        if (busy) {
            done(false)
            return
        }
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
                if (r == null && !released) mutableNote.value = R.string.ed_background_failed
                val applied = r != null && !r.stopped && applyResult(start, if (viral) "make it viral" else "auto polish", r.project)
                if (applied) {
                    mutableBeforeAuto.value = start
                    mutableReceipt.value = r!!.receipt
                }
                done(applied)
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
    fun reshape(
        aspect: com.zellahq.core.project.Aspect,
        done: (Boolean) -> Unit = {},
    ) {
        if (busy) {
            done(false)
            return
        }
        val start = project.value
        mutableAuto.value = AutoProgress(false, com.zellahq.core.auto.PolishStep.Reframe, 1, 1)
        val runner = com.zellahq.app.auto.AutoPolish(context)
        Thread({
            val out = runCatching { runner.reshape(start, aspect) }.getOrNull()
            main.post {
                mutableAuto.value = null
                if (out == null && !released) mutableNote.value = R.string.ed_background_failed
                done(out != null && applyResult(start, "shape", out))
            }
        }, "reshape").start()
    }

    /** Finds the voice in the background so music can step back for it. Changes nothing else. */
    fun findVoice() = analyse(setOf("voiceWindows")) { }

    /** Finds the voice and measures loudness in the background, then applies both as one undo step. */
    fun analyseSound(done: (Boolean) -> Unit) = analyse(setOf("voiceWindows", com.zellahq.app.sound.Sounds.LOUDNESS_KEY), done)

    private fun analyse(
        keys: Set<String>,
        done: (Boolean) -> Unit,
    ) {
        val start = project.value
        Thread({
            val analysed = runCatching { com.zellahq.app.sound.Sounds.analyse(context, start) }.getOrNull()
            main.post {
                if (released) return@post
                // Voice times and loudness describe the timeline they were measured on.
                val same = project.value.clips == start.clips
                if (!same) mutableNote.value = R.string.ed_changed_meanwhile
                analysed?.takeIf { same }?.let { a ->
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
                done(analysed != null)
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
        if (released) return
        mutableNote.value = null
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
        runCatching {
            saver.execute {
                // allowCollapse: the user deleted the clips on purpose.
                runCatching { store.save(p, allowCollapse = true) }.onFailure {
                    android.util.Log.e("ZellaEditor", "save", it)
                    main.post { mutableNote.value = R.string.ed_save_failed }
                }
            }
        }
    }

    fun saveNow() = save(project.value)

    /** Waits for the save, for the moment something else is about to read the file. */
    fun saveBlocking() {
        runCatching { saver.submit { store.save(project.value, allowCollapse = true) }.get(10, java.util.concurrent.TimeUnit.SECONDS) }
    }

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

    private fun startMatte(files: List<java.io.File>) {
        if (matteBaker != null || released) return
        val baker = com.zellahq.app.cutout.MatteBaker(context)
        matteBaker = baker
        mutableFinding.value = 0f
        background("find-person") {
            var failed = false
            try {
                files.forEachIndexed { i, f ->
                    val ok = baker.bake(f) { x -> main.post { if (matteBaker === baker) mutableFinding.value = (i + x) / files.size } }
                    if (!ok) {
                        failed = !baker.wasStopped
                        synchronized(matteGaveUp) { matteGaveUp += f.path }
                    }
                }
            } catch (t: Exception) {
                android.util.Log.e("ZellaEditor", "find person", t)
                failed = true
                synchronized(matteGaveUp) { matteGaveUp += files.map { it.path } }
            }
            main.post {
                matteBaker = null
                mutableFinding.value = null
                if (!released) {
                    if (failed) mutableNote.value = R.string.bg_failed
                    rebuild()
                }
            }
        }
    }

    /** Stops finding the person. The clip stays as it was until a background is picked again. */
    fun stopFinding() = matteBaker?.stop()

    /** A background was picked: anything given up on gets another go. */
    fun retryBackground() = synchronized(matteGaveUp) { matteGaveUp.clear() }

    private fun rebuild() {
        if (released) return
        val p = project.value
        if (p.clips.isEmpty()) {
            // The player keeps its last picture when emptied. The editor covers it.
            player.pause()
            mutableMissing.value = emptyList()
            return
        }
        val built = builder.build(p)
        mutableMissing.value = built.missing.map { it.id }
        player.setComposition(built.composition, (position.value * 1000).toLong())
        player.prepare()
        val mattes = synchronized(matteGaveUp) { built.needsMatte.filter { it.path !in matteGaveUp } }
        if (mattes.isNotEmpty()) startMatte(mattes)
        // Clips marked reversed get their reversed copy made in the background, then the preview rebuilds.
        val reverses = built.needsReverse.filter { it.id !in reverseGaveUp }
        mutableReversing.value = reverses.isNotEmpty()
        if (reverses.isNotEmpty()) {
            io.execute {
                reverses.forEach { if (ReverseRenderer.render(context, store, p.id, it) == null) reverseGaveUp += it.id }
                main.post { if (project.value == p) rebuild() }
            }
        } else if (mattes.isEmpty() && matteBaker == null && built.needsBake.any { "${p.modifiedAt}/$it" !in bakeGaveUp } && p !== bakingFor) {
            // Transitions play as plain cuts until their short videos are rendered. A clip's new background
            // comes first, or the transition would be rendered without it.
            bakingFor = p
            mutableBaking.value = true
            io.execute {
                val canvas = canvasSize(p)
                built.needsBake.forEach { join ->
                    if (project.value == p && !released) {
                        val ok = runCatching { TransitionBaker.bake(context, builder, p, join, canvas) }.getOrDefault(false)
                        if (!ok) bakeGaveUp += "${p.modifiedAt}/$join"
                    }
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
        if (released) return
        saveNow()
        released = true
        matteBaker?.stop()
        autoRunner?.stop()
        captionEngine?.stop()
        main.removeCallbacks(ticker)
        player.release()
        // Queued work finishes on its own; nothing new can start.
        io.shutdown()
        saver.shutdown()
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
