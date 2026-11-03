package com.zellahq.app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zellahq.app.R
import com.zellahq.core.RecorderState
import com.zellahq.core.RecorderTransitions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Things the recorder tells the person. The UI maps each one to a plain sentence. */
enum class RecordingNote {
    Saved,
    NoMic,
    NoPhoneSound,
    PhoneSoundSilent,
    StorageLow,
    StoppedStorageFull,
    ScreenShareEnded,
    CameraLost,
    Failed,
}

data class RecordingStatus(
    val state: RecorderState = RecorderState.Idle,
    val notes: List<RecordingNote> = emptyList(),
    val file: File? = null,
    /** True while the recording comes from the camera, so the app reopens the camera screen. */
    val camera: Boolean = false,
)

/**
 * Foreground service that owns one [Recorder]. Recording lives here, not in the activity, so it
 * keeps going when the person switches apps or the screen turns off.
 */
class RecordingService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_START_PATTERN = "start_pattern"
        const val ACTION_START_CAMERA = "start_camera"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_MIC = "mic"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_ROTATION = "rotation"
        const val MIN_FREE_TO_START = 500L * 1024 * 1024
        const val MIN_FREE_TO_CONTINUE = 200L * 1024 * 1024
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1

        private val mutableStatus = MutableStateFlow(RecordingStatus())
        val status: StateFlow<RecordingStatus> = mutableStatus

        fun recordingsDir(context: Context) = File(context.getExternalFilesDir(null), "recordings")

        fun freeBytes(context: Context): Long = recordingsDir(context).apply { mkdirs() }.let { StatFs(it.path).availableBytes }

        fun command(
            context: Context,
            action: String,
        ) = Intent(context, RecordingService::class.java).setAction(action)
    }

    private var recorder: Recorder? = null
    private var projection: MediaProjection? = null
    private var file: File? = null
    private val notes = ArrayList<RecordingNote>()
    private val main = Handler(Looper.getMainLooper())

    private val storageWatch =
        object : Runnable {
            override fun run() {
                if (freeBytes(this@RecordingService) < MIN_FREE_TO_CONTINUE) {
                    notes += RecordingNote.StoppedStorageFull
                    stopRecording()
                } else {
                    main.postDelayed(this, 10_000)
                }
            }
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> start(intent, Mode.Screen)
            ACTION_START_PATTERN -> start(intent, Mode.Pattern)
            ACTION_START_CAMERA -> start(intent, Mode.Camera)
            ACTION_PAUSE -> move(RecorderTransitions.Event.Pause) { recorder?.pause() }
            ACTION_RESUME -> move(RecorderTransitions.Event.Resume) { recorder?.resume() }
            ACTION_STOP -> stopRecording()
        }
        // If the system kills us, do not restart with a stale projection token. Recovery runs on next launch.
        return START_NOT_STICKY
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private enum class Mode { Screen, Pattern, Camera }

    @Volatile private var starting = false

    private fun start(
        intent: Intent,
        mode: Mode,
    ) {
        if (starting || RecorderTransitions.next(status.value.state, RecorderTransitions.Event.Start) == null) return
        notes.clear()
        val wantMic = intent.getBooleanExtra(EXTRA_MIC, true) && hasMic()
        if (intent.getBooleanExtra(EXTRA_MIC, true) && !wantMic) notes += RecordingNote.NoMic
        val wantDevice = mode == Mode.Screen && intent.getBooleanExtra(EXTRA_DEVICE, false) && hasMic()
        val cameraInfo = if (mode == Mode.Camera) CameraHub.info else null
        if (mode == Mode.Camera && cameraInfo == null) {
            fail("camera is not open")
            return
        }

        var type =
            when (mode) {
                Mode.Screen -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                Mode.Camera -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                Mode.Pattern -> 0
            }
        if (wantMic || wantDevice) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (type == 0) {
            fail("pattern mode needs the microphone permission")
            return
        }
        // Must happen within seconds of startForegroundService, so it stays on the main thread.
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(RecorderState.Recording), type)
        starting = true

        // Everything else can block (camera session, codecs, the AAC delay probe): never on main.
        Thread({
            try {
                val config: RecorderConfig
                val source: VideoSource
                when (mode) {
                    Mode.Pattern -> {
                        val (w, h) = captureSize()
                        config = RecorderConfig(w, h)
                        source = PatternSource(30)
                    }
                    Mode.Camera -> {
                        val info = cameraInfo!!
                        config =
                            RecorderConfig(
                                info.recordSize.width,
                                info.recordSize.height,
                                videoBitrate = 10_000_000,
                                rotationDegrees = intent.getIntExtra(EXTRA_ROTATION, 0),
                                videoRealtimeTimestamps = info.realtimeTimestamps,
                            )
                        source = CameraVideoSource()
                    }
                    Mode.Screen -> {
                        val (w, h, dpi) = captureSize()
                        config = RecorderConfig(w, h, repeatFrameAfterUs = 250_000)
                        val p = openProjection(intent)
                        projection = p
                        source = ScreenSource(p, dpi)
                    }
                }
                val sources = LinkedHashMap<String, AudioSourceFactory>()
                if (wantMic) sources["voice"] = AudioSources.mic(this)
                if (wantDevice) sources["phone sound"] = AudioSources.device(projection!!)

                val name = "Zella-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"
                val f = File(recordingsDir(this).apply { mkdirs() }, name)
                val delay = if (sources.isEmpty()) 0L else AacDelay.forDevice(this, 48_000)
                val r = Recorder(f, config.copy(audioEncoderDelayUs = delay), source, sources)
                r.start()
                main.post {
                    starting = false
                    recorder = r
                    file = f
                    if (wantDevice && "phone sound" !in r.openedTracks()) notes += RecordingNote.NoPhoneSound
                    if (wantMic && "voice" !in r.openedTracks()) notes += RecordingNote.NoMic
                    mutableStatus.value =
                        RecordingStatus(RecorderState.Recording, notes.toList(), f, camera = mode == Mode.Camera)
                    main.postDelayed(storageWatch, 10_000)
                    if (mode == Mode.Camera) watchCamera()
                }
            } catch (t: Throwable) {
                main.post {
                    starting = false
                    fail("start failed", t)
                }
            }
        }, "start-sequence").start()
    }

    private var cameraWatch: Job? = null

    /** Another app taking the camera, or a camera error, ends the recording and keeps what was saved. */
    private fun watchCamera() {
        cameraWatch?.cancel()
        cameraWatch =
            scope.launch {
                CameraHub.state.collect {
                    if (it is CameraHub.State.Lost && status.value.state != RecorderState.Idle) {
                        notes += RecordingNote.CameraLost
                        stopRecording()
                    }
                }
            }
    }

    private fun openProjection(intent: Intent): MediaProjection {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val data =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }
        val p =
            mpm.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data!!)
                ?: error("screen share token was refused")
        // Android 14 requires the callback before the virtual display is created.
        p.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    main.post {
                        if (status.value.state != RecorderState.Idle) {
                            notes += RecordingNote.ScreenShareEnded
                            stopRecording()
                        }
                    }
                }
            },
            main,
        )
        return p
    }

    private fun move(
        event: RecorderTransitions.Event,
        action: () -> Unit,
    ) {
        val next = RecorderTransitions.next(status.value.state, event) ?: return
        action()
        mutableStatus.value = status.value.copy(state = next)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(next))
    }

    private fun stopRecording() {
        if (RecorderTransitions.next(status.value.state, RecorderTransitions.Event.Stop) == null) return
        mutableStatus.value = status.value.copy(state = RecorderState.Stopping)
        main.removeCallbacks(storageWatch)
        cameraWatch?.cancel()
        val r = recorder
        recorder = null
        // The stop sequence blocks on codec threads, so it never runs on the main thread.
        Thread({
            val report = runCatching { r?.stop() }.onFailure { Log.e("ZellaRecorder", "stop", it) }.getOrNull()
            projection?.stop()
            projection = null
            CameraHub.closeIfUnused()
            report?.let {
                if ("phone sound" in it.silentTracks) notes += RecordingNote.PhoneSoundSilent
                Log.i("ZellaRecorder", "report $it")
                notes += RecordingNote.Saved
            }
            if (report == null) notes += RecordingNote.Failed
            main.post {
                mutableStatus.value = RecordingStatus(RecorderState.Idle, notes.toList(), file, camera = false)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "stop-sequence").start()
    }

    private fun fail(
        why: String,
        t: Throwable? = null,
    ) {
        Log.e("ZellaRecorder", why, t)
        notes += RecordingNote.Failed
        file?.let { if (it.length() == 0L) it.delete() }
        mutableStatus.value = RecordingStatus(RecorderState.Idle, notes.toList(), null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Screen size scaled so the short side is at most 1080, rounded to a multiple of 16 for encoders. */
    private fun captureSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val (w, h) =
            if (Build.VERSION.SDK_INT >= 30) {
                wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
            } else {
                // Android 10 has no window metrics API; the real display size is the same thing there.
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(m)
                m.widthPixels to m.heightPixels
            }
        val dpi = resources.displayMetrics.densityDpi
        val scale = minOf(1f, 1080f / minOf(w, h))

        fun r16(v: Float) = (v / 16).toInt() * 16
        return Triple(r16(w * scale), r16(h * scale), dpi)
    }

    private fun notification(state: RecorderState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.rec_channel), NotificationManager.IMPORTANCE_LOW),
        )

        fun action(
            label: Int,
            act: String,
        ) = Notification.Action.Builder(
            null,
            getString(label),
            PendingIntent.getService(this, act.hashCode(), command(this, act), PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val b =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(
                    getString(if (state == RecorderState.Paused) R.string.rec_paused else R.string.rec_recording),
                )
                .setOngoing(true)
        if (state == RecorderState.Paused) b.addAction(action(R.string.rec_resume, ACTION_RESUME))
        if (state == RecorderState.Recording) b.addAction(action(R.string.rec_pause, ACTION_PAUSE))
        b.addAction(action(R.string.rec_stop, ACTION_STOP))
        return b.build()
    }
}
