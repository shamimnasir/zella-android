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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.zellahq.app.recording.RecordingNote
import com.zellahq.app.recording.RecordingService
import com.zellahq.app.recording.Remuxer
import com.zellahq.core.EntitlementState
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import com.zellahq.core.RecorderState
import com.zellahq.core.RecordingJournal
import com.zellahq.core.SampleJournal
import com.zellahq.ui.ZellaCard
import com.zellahq.ui.ZellaChip
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing
import com.zellahq.ui.ZellaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = loadEntitlement()
        // Debug builds only: start a test-pattern recording from adb, for the forced-kill test.
        if (BuildConfig.DEBUG && intent.getBooleanExtra("debug_pattern", false)) {
            ContextCompat.startForegroundService(
                this,
                RecordingService.command(this, RecordingService.ACTION_START_PATTERN)
                    .putExtra(RecordingService.EXTRA_MIC, true),
            )
        }
        setContent { ZellaTheme { Home(state, System.currentTimeMillis(), recoveredNames()) } }
    }

    /** Install time is written once, on first launch, and never again. Purchases arrive in Phase 8. */
    private fun loadEntitlement(): EntitlementState {
        val prefs = getSharedPreferences("entitlement", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val install =
            prefs.getLong("install", 0L).takeIf { it > 0L }
                ?: now.also { prefs.edit().putLong("install", it).apply() }
        val seen = prefs.getLong("seen", install)
        val state = PremiumGate.touch(EntitlementState(Plan.Free, install, seen), now)
        prefs.edit().putLong("seen", state.lastSeenEpochMs).apply()
        return state
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

@Composable
private fun Home(
    entitlement: EntitlementState,
    nowMs: Long,
    recovered: Set<String>,
) {
    val context = LocalContext.current
    val status by RecordingService.status.collectAsState()
    var voice by rememberSaveable { mutableStateOf(true) }
    var phoneSound by rememberSaveable { mutableStateOf(false) }
    var localNote by remember { mutableStateOf<Int?>(null) }
    var items by remember { mutableStateOf(emptyList<RecordingItem>()) }
    var showCamera by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(status.state) {
        if (status.state == RecorderState.Idle) {
            val now = recoverInterrupted(context)
            items = listRecordings(context, recovered + now)
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(ZellaSpacing.M),
        verticalArrangement = Arrangement.spacedBy(ZellaSpacing.M),
    ) {
        // A camera recording that is still running brings the camera screen back.
        if (showCamera || status.camera) {
            BackHandler(enabled = status.state == RecorderState.Idle) { showCamera = false }
            CameraScreen(voice) { showCamera = false }
            return@Column
        }
        if (PremiumGate.isInProWeek(entitlement, nowMs)) {
            val days = PremiumGate.proWeekDaysLeft(entitlement, nowMs)
            ZellaChip(pluralStringResource(R.plurals.pro_week_chip, days, days))
        }
        Text(stringResource(R.string.home_tagline), color = MaterialTheme.colorScheme.onBackground)

        when (status.state) {
            RecorderState.Idle -> {
                LabelledSwitch(stringResource(R.string.rec_option_voice), voice) { voice = it }
                LabelledSwitch(stringResource(R.string.rec_option_phone_sound), phoneSound) { phoneSound = it }
                ActionCard(ZellaColors.CardViolet, stringResource(R.string.rec_start)) { onRecord() }
                ActionCard(ZellaColors.CardCoral, stringResource(R.string.home_camera)) { showCamera = true }
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
            status.notes.forEach { Text(stringResource(noteText(it)), color = MaterialTheme.colorScheme.onBackground) }
        }

        Text(stringResource(R.string.rec_list_title), color = MaterialTheme.colorScheme.onBackground)
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.rec_list_empty),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(ZellaSpacing.S)) {
            items(items, key = { it.file.name }) { item ->
                val name = item.file.nameWithoutExtension
                val main = stringResource(R.string.rec_list_item, name, item.seconds / 60, item.seconds % 60)
                val label = if (item.recovered) main + "\n" + stringResource(R.string.rec_list_recovered) else main
                ZellaCard(ZellaColors.CardMint, label, Modifier.fillMaxWidth())
            }
        }
        Text(stringResource(R.string.home_status), color = MaterialTheme.colorScheme.onBackground)
    }
}

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
        Text(label, color = MaterialTheme.colorScheme.onBackground)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun ActionCard(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    onClick: () -> Unit,
) = ZellaCard(color, label, Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick))

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
