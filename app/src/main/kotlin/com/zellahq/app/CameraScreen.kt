package com.zellahq.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zellahq.app.recording.CameraHub
import com.zellahq.app.recording.RecordingService
import com.zellahq.core.CameraMath
import com.zellahq.core.RecorderState
import com.zellahq.ui.ZellaColors
import com.zellahq.ui.ZellaSpacing

/**
 * Camera recording. The screen's orientation is locked while it is open, so the preview never jumps;
 * the recording's orientation comes from how the phone is held when Record is tapped.
 * The preview is mirrored for the front camera, like a mirror. The recording is not, so text reads.
 */
@Composable
fun CameraScreen(
    voice: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as Activity
    val status by RecordingService.status.collectAsState()
    val camera by CameraHub.state.collectAsState()
    val cameras = remember { CameraHub.available(context) }
    var front by rememberSaveable { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(granted(context, Manifest.permission.CAMERA)) }
    var asked by remember { mutableStateOf(false) }
    var deviceDegrees by remember { mutableIntStateOf(0) }

    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            allowed = granted(context, Manifest.permission.CAMERA)
            asked = true
        }
    LaunchedEffect(Unit) {
        val needed = ArrayList<String>()
        if (!allowed) needed += Manifest.permission.CAMERA
        if (voice && !granted(context, Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray()) else asked = true
    }

    // Lock the screen while the camera screen shows; follow the phone's real angle for the recording.
    DisposableEffect(Unit) {
        val before = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        val listener =
            object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation != ORIENTATION_UNKNOWN) deviceDegrees = orientation
                }
            }
        listener.enable()
        onDispose {
            listener.disable()
            activity.requestedOrientation = before
        }
    }

    // Open the camera while the screen is visible. Close it when the app goes away, unless recording.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(allowed, front, lifecycle) {
        val observer =
            LifecycleEventObserver { _, e ->
                if (!allowed) return@LifecycleEventObserver
                val recording = RecordingService.status.value.state != RecorderState.Idle
                when (e) {
                    Lifecycle.Event.ON_START -> if (!recording || CameraHub.info == null) CameraHub.open(context, front)
                    Lifecycle.Event.ON_STOP -> if (!recording) CameraHub.close()
                    else -> {}
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (RecordingService.status.value.state == RecorderState.Idle) CameraHub.close()
        }
    }

    fun start() {
        val info = CameraHub.info ?: return
        if (RecordingService.freeBytes(context) < RecordingService.MIN_FREE_TO_START) return
        val rotation = CameraMath.recordingRotation(info.sensorOrientation, info.front, deviceDegrees)
        ContextCompat.startForegroundService(
            context,
            RecordingService.command(context, RecordingService.ACTION_START_CAMERA)
                .putExtra(RecordingService.EXTRA_MIC, voice)
                .putExtra(RecordingService.EXTRA_ROTATION, rotation),
        )
    }

    fun send(action: String) = context.startService(RecordingService.command(context, action))

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ZellaSpacing.M),
    ) {
        val info = (camera as? CameraHub.State.Open)?.info
        when {
            !allowed && asked ->
                Text(
                    stringResource(R.string.cam_need_permission),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            camera is CameraHub.State.Lost ->
                Text(
                    stringResource(R.string.cam_lost),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            info != null -> {
                val rotation = ContextCompat.getDisplayOrDefault(context).rotation
                val portrait = rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180
                val w = info.previewSize.width.toFloat()
                val h = info.previewSize.height.toFloat()
                // weight keeps the buttons below on screen; the preview shrinks instead.
                Box(Modifier.fillMaxWidth().weight(1f, fill = false), contentAlignment = Alignment.Center) {
                    // A new camera means a new buffer size, so a new SurfaceView.
                    key(info.id) { CameraPreview(info, Modifier.aspectRatio(if (portrait) h / w else w / h)) }
                }
            }
            else -> Text(stringResource(R.string.cam_opening), color = MaterialTheme.colorScheme.onBackground)
        }

        when (status.state) {
            RecorderState.Idle -> {
                if (info != null) ActionCard(ZellaColors.CardCoral, stringResource(R.string.cam_start)) { start() }
                if (cameras.size > 1) {
                    val label = if (front) R.string.cam_use_back else R.string.cam_use_front
                    ActionCard(ZellaColors.CardMint, stringResource(label)) { front = !front }
                }
                ActionCard(ZellaColors.CardSun, stringResource(R.string.cam_back)) { onBack() }
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
        if (status.state == RecorderState.Idle) {
            CameraBackgroundChoice()
            status.notes.forEach { Text(stringResource(noteText(it)), color = MaterialTheme.colorScheme.onBackground) }
        }
    }
}

/**
 * A SurfaceView, because the camera itself turns and mirrors frames for a SurfaceView, matching the
 * screen's rotation when the session starts. The buffer is fixed to the preview size in sensor
 * orientation, and the camera only gets the surface once it has that size.
 */
@Composable
private fun CameraPreview(
    info: CameraHub.Info,
    modifier: Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.setFixedSize(info.previewSize.width, info.previewSize.height)
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {}

                        override fun surfaceChanged(
                            h: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            if (width == info.previewSize.width && height == info.previewSize.height) {
                                CameraHub.setPreview(h.surface)
                            }
                        }

                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            CameraHub.setPreviewBlocking(null)
                        }
                    },
                )
            }
        },
    )
}

private fun granted(
    context: Context,
    permission: String,
) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** What goes behind the person, applied when the recording is opened to edit. There is no live preview of it yet. */
@Composable
private fun CameraBackgroundChoice() {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(com.zellahq.app.editor.Storage.chosenCameraBackground(context)) }
    val color = MaterialTheme.colorScheme.onBackground
    Text(stringResource(R.string.cam_background), color = color)
    androidx.compose.foundation.layout.Row(
        androidx.compose.ui.Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ZellaSpacing.S),
    ) {
        listOf(
            null to R.string.cam_bg_none,
            com.zellahq.core.project.BackgroundChoice("blur", blurRadius = 18.0) to R.string.cam_bg_blur,
            com.zellahq.core.project.BackgroundChoice("cutout", colorHex = "#000000") to R.string.cam_bg_black,
            com.zellahq.core.project.BackgroundChoice.REMOVED to R.string.cam_bg_ready,
        ).forEach { (c, label) ->
            com.zellahq.app.editor.Tool(stringResource(label), if (chosen == c) ZellaColors.CardSun else null) {
                chosen = c
                com.zellahq.app.editor.Storage.chooseCameraBackground(context, c)
            }
        }
    }
    if (chosen != null) Text(stringResource(R.string.cam_background_note), color = color)
}
