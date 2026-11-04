package com.zellahq.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.content.ContextCompat
import com.zellahq.app.editor.EditorScreen
import com.zellahq.app.editor.Storage
import com.zellahq.app.editor.Tool
import com.zellahq.app.editor.mmss
import com.zellahq.app.export.ExportScreen
import com.zellahq.app.recording.CameraHub
import com.zellahq.app.recording.RecordingNote
import com.zellahq.app.recording.RecordingService
import com.zellahq.app.recording.Remuxer
import com.zellahq.core.EntitlementState
import com.zellahq.core.PremiumGate
import com.zellahq.core.RecorderState
import com.zellahq.core.RecordingJournal
import com.zellahq.core.SampleJournal
import com.zellahq.core.project.NewerFormatException
import com.zellahq.core.project.Project
import com.zellahq.core.project.duration
import com.zellahq.ui.ZellaCard
import com.zellahq.ui.ZellaChip
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing
import com.zellahq.ui.ZellaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The base theme is a dark one, so without this the clock and icons are pale on our light screens.
        enableEdgeToEdge()
        val state = Entitlements.load(this)
        // Debug builds only: start a test-pattern recording from adb, for the forced-kill test.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_pattern", false)) {
            ContextCompat.startForegroundService(
                this,
                RecordingService.command(this, RecordingService.ACTION_START_PATTERN)
                    .putExtra(RecordingService.EXTRA_MIC, true),
            )
        }
        setContent { ZellaTheme { Root(state, System.currentTimeMillis(), recoveredNames()) } }
    }

    /** Names of recordings that were rebuilt after Zella closed unexpectedly. */
    private fun recoveredNames(): Set<String> =
        getSharedPreferences("recordings", Context.MODE_PRIVATE).getStringSet("recovered", emptySet())!!
}

/**
 * Rebuilds the MP4 of every recording that was cut short by a crash or a kill. Runs off the main
 * thread: a long recording takes seconds to copy. Returns the names rebuilt this time.
 */
private suspend fun recoverInterrupted(context: Context): Set<String> =
    withContext(Dispatchers.IO) {
        if (RecordingService.status.value.state != RecorderState.Idle) return@withContext emptySet()
        val prefs = context.getSharedPreferences("recordings", Context.MODE_PRIVATE)
        val kept = prefs.getStringSet("recovered", emptySet())!!.toMutableSet()
        val journal = RecordingJournal(RecordingService.recordingsDir(context))
        val now = HashSet<String>()
        for (j in journal.interrupted()) {
            val video = journal.videoFor(j)
            val result = runCatching { Remuxer.toMp4(j, video) }
            android.util.Log.i("ZellaRecorder", "recovered ${j.name}: $result")
            if (result.isSuccess) {
                j.delete()
                kept += video.name
                now += video.name
            } else if (SampleJournal.read(j).sampleCount == 0) {
                j.delete() // killed before a single frame: there is nothing to keep
            }
            // Otherwise keep the journal so a later version can try again. Never delete footage.
        }
        prefs.edit().putStringSet("recovered", kept).apply()
        now
    }

private data class RecordingItem(val file: File, val seconds: Long, val recovered: Boolean)

/** Which screen shows. Kept as a string so it survives rotation and process death. */
@Composable
private fun Root(
    entitlement: EntitlementState,
    nowMs: Long,
    recovered: Set<String>,
) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(if (Onboarding.isDone(context)) "home" else "welcome") }
    // Refunds and restored purchases arrive here: what Google Play says, at every start.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val play = com.zellahq.app.billing.PlayStore(context)
        play.connect()
        onDispose { play.close() }
    }
    // The "Record my voice" switch applies to screen and camera recordings alike.
    var voice by rememberSaveable { mutableStateOf(true) }
    val status by RecordingService.status.collectAsState()
    val store = remember { Storage.store(context) }
    // One editor session across the editor and export screens, so undo and running work survive the trip.
    val openId = screen.takeIf { it.startsWith("editor:") || it.startsWith("export:") || it.startsWith("plans:") }?.substringAfter(':')
    val session =
        remember(openId) {
            openId?.let { id -> runCatching { store.load(id) }.getOrNull()?.let { com.zellahq.app.editor.EditorState(context, it) } }
        }
    androidx.compose.runtime.DisposableEffect(session) { onDispose { session?.release() } }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        when {
            screen == "welcome" ->
                OnboardingScreen {
                    Onboarding.finish(context)
                    screen = "home"
                }
            screen == "plans" || screen.startsWith("plans:") -> {
                // From Export, back goes to Export.
                val back = screen.substringAfter("plans:", "home").let { if (it == "home") it else "export:$it" }
                BackHandler { screen = back }
                com.zellahq.app.billing.PlansScreen { screen = back }
            }
            // A camera recording that is still running brings the camera screen back.
            status.camera || screen == "camera" ->
                Box(Modifier.padding(ZellaSpacing.M)) {
                    BackHandler(enabled = status.state == RecorderState.Idle) { screen = "home" }
                    CameraScreen(voice) { screen = "home" }
                }
            screen.startsWith("editor:") -> {
                if (session == null) {
                    screen = "home"
                } else {
                    EditorScreen(session, onBack = { screen = "home" }, onExport = { screen = "export:${it.id}" })
                }
            }
            screen.startsWith("export:") -> {
                if (session == null) {
                    screen = "home"
                } else {
                    val p by session.project.collectAsState()
                    BackHandler { screen = "editor:${p.id}" }
                    ExportScreen(
                        p,
                        { next -> session.edit("where it goes") { next } },
                        { screen = "plans:${p.id}" },
                    ) { screen = "editor:${p.id}" }
                }
            }
            else ->
                Home(
                    entitlement,
                    nowMs,
                    recovered,
                    voice,
                    { voice = it },
                    onCamera = { screen = "camera" },
                    onOpen = { screen = "editor:$it" },
                    onPlans = { screen = "plans" },
                )
        }
    }
}

@Composable
private fun Home(
    entitlement: EntitlementState,
    nowMs: Long,
    recovered: Set<String>,
    voice: Boolean,
    onVoice: (Boolean) -> Unit,
    onCamera: () -> Unit,
    onOpen: (String) -> Unit,
    onPlans: () -> Unit,
) {
    val context = LocalContext.current
    val status by RecordingService.status.collectAsState()
    var phoneSound by rememberSaveable { mutableStateOf(false) }
    var localNote by remember { mutableStateOf<Int?>(null) }
    var busyImporting by remember { mutableStateOf(false) }
    var unreadable by remember { mutableIntStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var items by remember { mutableStateOf(emptyList<RecordingItem>()) }
    val store = remember { Storage.store(context) }
    var projects by remember { mutableStateOf(emptyList<Project>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var deleted by remember { mutableStateOf<String?>(null) }
    val newName = stringResource(R.string.home_new_name)
    // Zella installs on phones without a camera (screen recording still works), so only offer it when present.
    val hasCamera =
        remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
                CameraHub.available(context).isNotEmpty()
        }

    LaunchedEffect(status.state, refresh) {
        if (status.state == RecorderState.Idle) {
            val now = recoverInterrupted(context)
            withContext(Dispatchers.IO) { Housekeeping.once(context) }
            items = listRecordings(context, recovered + now)
            val listing = withContext(Dispatchers.IO) { store.list() }
            projects = listing.projects.map { it.second }
            unreadable = listing.unreadable.size
        }
    }

    fun openNew(file: File) {
        val stamp = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date())
        Storage.newProject(context, file, newName.format(stamp))?.let { onOpen(it.id) }
    }

    val videoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) {
                busyImporting = true
                scope.launch {
                    val f = withContext(Dispatchers.IO) { Storage.importUri(context, uri) }
                    busyImporting = false
                    if (f != null) openNew(f) else localNote = R.string.home_import_failed
                }
            }
        }
    val packagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            busyImporting = true
            scope.launch {
                val tmp = File(context.cacheDir, "import-${System.nanoTime()}.zella.zip")
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                            store.import(tmp)
                        }.also { tmp.delete() }
                    }
                busyImporting = false
                result
                    .onSuccess { onOpen(it.id) }
                    .onFailure { localNote = if (it is NewerFormatException) R.string.home_import_newer else R.string.home_import_failed }
            }
        }

    val projectionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                ContextCompat.startForegroundService(
                    context,
                    RecordingService.command(context, RecordingService.ACTION_START)
                        .putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(RecordingService.EXTRA_DATA, result.data)
                        .putExtra(RecordingService.EXTRA_MIC, voice)
                        .putExtra(RecordingService.EXTRA_DEVICE, phoneSound),
                )
            } else {
                localNote = R.string.rec_note_share_declined
            }
        }

    fun askScreen() {
        if (RecordingService.freeBytes(context) < RecordingService.MIN_FREE_TO_START) {
            localNote = R.string.rec_note_storage_low
            return
        }
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { askScreen() }

    fun onRecord() {
        localNote = null
        val needed = ArrayList<String>()
        if ((voice || phoneSound) && !granted(context, Manifest.permission.RECORD_AUDIO)) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) askScreen() else permissionLauncher.launch(needed.toTypedArray())
    }

    fun send(action: String) = context.startService(RecordingService.command(context, action))

    // One scrolling list for the whole screen. A list nested inside a column hides whatever sits below it.
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = ZellaSpacing.M),
        verticalArrangement = Arrangement.spacedBy(ZellaSpacing.M),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.M)) {
                if (PremiumGate.isInProWeek(entitlement, nowMs)) {
                    val days = PremiumGate.proWeekDaysLeft(entitlement, nowMs)
                    ZellaChip(pluralStringResource(R.plurals.pro_week_chip, days, days))
                }
                com.zellahq.app.editor.Tool(stringResource(R.string.pl_open), onClick = onPlans)
                if (busyImporting) Text(stringResource(R.string.home_importing), color = MaterialTheme.colorScheme.onBackground)
                if (unreadable > 0) {
                    Text(
                        stringResource(R.string.home_unreadable, unreadable),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(stringResource(R.string.home_tagline), color = MaterialTheme.colorScheme.onBackground)
                when (status.state) {
                    RecorderState.Idle -> {
                        LabelledSwitch(stringResource(R.string.rec_option_voice), voice) { onVoice(it) }
                        LabelledSwitch(stringResource(R.string.rec_option_phone_sound), phoneSound) { phoneSound = it }
                        ActionCard(ZellaColors.CardViolet, stringResource(R.string.rec_start)) { onRecord() }
                        if (hasCamera) {
                            ActionCard(
                                ZellaColors.CardCoral,
                                stringResource(R.string.home_camera),
                            ) { onCamera() }
                        }
                        ActionCard(ZellaColors.CardSun, stringResource(R.string.home_new_project)) {
                            videoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                            )
                        }
                    }
                    RecorderState.Recording -> {
                        Text(stringResource(R.string.rec_recording), color = MaterialTheme.colorScheme.onBackground)
                        ActionCard(
                            ZellaColors.CardSun,
                            stringResource(R.string.rec_pause),
                        ) { send(RecordingService.ACTION_PAUSE) }
                        ActionCard(
                            ZellaColors.CardCoral,
                            stringResource(R.string.rec_stop),
                        ) { send(RecordingService.ACTION_STOP) }
                    }
                    RecorderState.Paused -> {
                        Text(stringResource(R.string.rec_paused), color = MaterialTheme.colorScheme.onBackground)
                        ActionCard(
                            ZellaColors.CardMint,
                            stringResource(R.string.rec_resume),
                        ) { send(RecordingService.ACTION_RESUME) }
                        ActionCard(
                            ZellaColors.CardCoral,
                            stringResource(R.string.rec_stop),
                        ) { send(RecordingService.ACTION_STOP) }
                    }
                    RecorderState.Stopping ->
                        Text(
                            stringResource(R.string.rec_saving),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                }
                localNote?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.onBackground) }
                if (status.state == RecorderState.Idle) {
                    status.notes.forEach {
                        Text(
                            stringResource(noteText(it)),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
            }
        }

        item { Text(stringResource(R.string.home_projects_title), color = MaterialTheme.colorScheme.onBackground) }
        deleted?.let { id ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
                ) {
                    Text(stringResource(R.string.home_deleted), color = MaterialTheme.colorScheme.onBackground)
                    ActionChip(stringResource(R.string.home_undo_delete)) {
                        store.restore(id)
                        deleted = null
                        refresh++
                    }
                }
            }
        }
        items(projects, key = { "p" + it.id }) { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
            ) {
                ActionCard(
                    ZellaColors.CardViolet,
                    stringResource(R.string.home_project_item, p.name, mmss(p.duration)),
                    Modifier.weight(1f),
                ) {
                    onOpen(p.id)
                }
                ActionChip(stringResource(R.string.home_delete_project)) {
                    store.delete(p.id)
                    deleted = p.id
                    refresh++
                }
            }
        }
        item {
            ActionChip(stringResource(R.string.home_import_project)) {
                packagePicker.launch(arrayOf("application/zip", "application/octet-stream"))
            }
        }

        item { Text(stringResource(R.string.rec_list_title), color = MaterialTheme.colorScheme.onBackground) }
        if (items.isEmpty()) {
            item {
                Text(stringResource(R.string.rec_list_empty), color = MaterialTheme.colorScheme.onBackground)
            }
        }
        items(items, key = { "r" + it.file.name }) { item ->
            val name = item.file.nameWithoutExtension
            val main = stringResource(R.string.rec_list_item, name, item.seconds / 60, item.seconds % 60)
            val label = if (item.recovered) main + "\n" + stringResource(R.string.rec_list_recovered) else main
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZellaSpacing.S),
            ) {
                ZellaCard(ZellaColors.CardMint, label, Modifier.weight(1f))
                ActionChip(stringResource(R.string.home_edit_recording)) { openNew(item.file) }
            }
        }
        item { Text(stringResource(R.string.home_status), color = MaterialTheme.colorScheme.onBackground) }
    }
}

@Composable
private fun ActionChip(
    label: String,
    onClick: () -> Unit,
) = Tool(label, null, onClick)

@Composable
private fun LabelledSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = ZellaSpacing.MinTap)
            .clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight: a long label wraps instead of running under the switch.
        Text(label, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f).padding(end = ZellaSpacing.S))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun ActionCard(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) = ZellaCard(color, label, modifier.clickable(role = Role.Button, onClick = onClick))

private fun granted(
    context: Context,
    permission: String,
) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

internal fun noteText(note: RecordingNote) =
    when (note) {
        RecordingNote.Saved -> R.string.rec_note_saved
        RecordingNote.NoMic -> R.string.rec_note_no_mic
        RecordingNote.NoPhoneSound -> R.string.rec_note_no_phone_sound
        RecordingNote.PhoneSoundSilent -> R.string.rec_note_phone_sound_silent
        RecordingNote.StorageLow -> R.string.rec_note_storage_low
        RecordingNote.StoppedStorageFull -> R.string.rec_note_storage_full
        RecordingNote.ScreenShareEnded -> R.string.rec_note_share_ended
        RecordingNote.CameraLost -> R.string.rec_note_camera_lost
        RecordingNote.Failed -> R.string.rec_note_failed
    }

private suspend fun listRecordings(
    context: Context,
    recovered: Set<String>,
): List<RecordingItem> =
    withContext(Dispatchers.IO) {
        RecordingService.recordingsDir(context).listFiles { f -> f.extension == "mp4" }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { f ->
                RecordingItem(f, lengthSeconds(f), f.name in recovered)
            }
    }

private fun lengthSeconds(f: File): Long =
    runCatching {
        android.media.MediaMetadataRetriever().use {
            it.setDataSource(f.path)
            it.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
        }
    }.getOrNull()?.div(1000) ?: 0L
