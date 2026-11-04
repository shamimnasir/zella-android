@file:Suppress("ktlint:standard:max-line-length")

// Media3's composition preview and export are marked unstable and experimental. Zella depends on
// them on purpose: they are the only way to preview exactly what exports. Pinned to 1.10.1.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.zellahq.app.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import com.zellahq.app.Entitlements
import com.zellahq.app.R
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.editor.ReverseRenderer
import com.zellahq.app.editor.Storage
import com.zellahq.core.ExportDecision
import com.zellahq.core.ExportRequest
import com.zellahq.core.PremiumGate
import com.zellahq.core.ProFeature
import com.zellahq.core.project.duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

sealed interface ExportStatus {
    data object Idle : ExportStatus

    data class Running(val percent: Int, val hot: Boolean) : ExportStatus

    /** [missed] counts shapes in the queue that did not come out. */
    data class Done(val file: File, val seconds: Double, val more: List<File> = emptyList(), val missed: Int = 0) : ExportStatus

    data class Blocked(val needs: List<ProFeature>) : ExportStatus

    data class Failed(val message: String) : ExportStatus

    data object Cancelled : ExportStatus
}

/**
 * Runs an export in the foreground, with a notification, so it survives the person leaving the app.
 * It asks PremiumGate itself, from the saved project and the stored entitlement: the export screen
 * only says what was picked, it cannot hand out a permit.
 */
class ExportService : Service() {
    companion object {
        const val EXTRA_PROJECT = "project"
        const val EXTRA_SHORT_SIDE = "short"
        const val EXTRA_FPS = "fps"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_BITRATE = "bitrate"

        /** The other shapes to export as well, by their stored names. Pro: the multi-aspect queue. */
        const val EXTRA_ALSO = "also"
        const val ACTION_CANCEL = "cancel"
        private const val CHANNEL = "export"
        private const val ID = 2
        private const val READY_ID = 3
        private const val WAKE_LIMIT_MS = 6 * 60 * 60 * 1000L

        private val mutableStatus = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
        val status: StateFlow<ExportStatus> = mutableStatus

        /** The project the current status belongs to. One project's finished file must never show on another's screen. */
        @Volatile var statusProject: String? = null
            private set

        fun reset() {
            if (mutableStatus.value !is ExportStatus.Running) mutableStatus.value = ExportStatus.Idle
        }

        /** Leaving the screen forgets a failure, but a finished video stays until the next export. */
        fun leftScreen() {
            if (mutableStatus.value is ExportStatus.Failed || mutableStatus.value is ExportStatus.Cancelled || mutableStatus.value is ExportStatus.Blocked) reset()
        }

        /** Letters of any language stay in the name. */
        fun safeName(name: String) = name.replace(Regex("[^\\p{L}\\p{M}\\p{N} _-]"), "").trim().ifEmpty { "Zella" }

        /** Finished files of a project with this name, newest first. They stay on the phone until deleted here. */
        fun earlier(
            context: Context,
            name: String,
        ): List<File> {
            val prefix = safeName(name)
            return Storage.exports(context).listFiles { f -> f.isFile && !f.name.endsWith(".part") && f.name.startsWith("$prefix-") }
                .orEmpty()
                .sortedByDescending { it.lastModified() }
        }

        /** Adds the file to the gallery, in Movies/Zella. No storage permission needed on Android 10+. */
        fun saveToGallery(
            context: Context,
            file: File,
        ): Boolean =
            runCatching {
                val (collection, folder) =
                    when (file.extension) {
                        "gif" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Zella"
                        "m4a" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Zella"
                        else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Zella"
                    }
                val values =
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, Formats.mime(file))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                val uri = context.contentResolver.insert(collection, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                true
            }.getOrDefault(false)

        fun shareIntent(
            context: Context,
            file: File,
            more: List<File> = emptyList(),
        ): Intent {
            val uris = (listOf(file) + more).map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
            val send =
                if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                }
            send.setType(Formats.mime(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return Intent.createChooser(send, context.getString(R.string.ex_share))
        }
    }

    private var exporter: Exporter? = null

    @Volatile private var exporterCancelled = false

    private var wake: PowerManager.WakeLock? = null

    /** Android 15 ends a mediaProcessing service after its time limit. Stop cleanly rather than be killed for it. */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        exporterCancelled = true
        exporter?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CANCEL) {
            exporterCancelled = true
            exporter?.cancel()
            return START_NOT_STICKY
        }
        if (status.value is ExportStatus.Running) return START_NOT_STICKY
        val projectId = intent?.getStringExtra(EXTRA_PROJECT) ?: return START_NOT_STICKY
        val settings =
            ExportSettings(
                shortSide = intent.getIntExtra(EXTRA_SHORT_SIDE, 1080),
                fps = intent.getIntExtra(EXTRA_FPS, 30),
                bitrate = intent.getIntExtra(EXTRA_BITRATE, 0).takeIf { it > 0 },
            )
        val type =
            if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        // Not ServiceCompat: androidx.core 1.16 masks off types it does not know, and mediaProcessing
        // (Android 15) became "type none", which Android refuses (crash on the emulator, 2026-11-04).
        startForeground(ID, notification(0), type)
        statusProject = projectId
        mutableStatus.value = ExportStatus.Running(0, false)
        getSystemService(NotificationManager::class.java).cancel(READY_ID)
        // The screen may turn off during a long export. Bounded, so a stuck export cannot hold it for ever.
        wake =
            getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zella:export").apply {
                setReferenceCounted(false)
                acquire(WAKE_LIMIT_MS)
            }
        exporterCancelled = false
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: Formats.VIDEO
        val also = intent.getStringArrayListExtra(EXTRA_ALSO).orEmpty()
        Thread({ run(projectId, settings, format, also) }, "export").start()
        return START_NOT_STICKY
    }

    private fun run(
        projectId: String,
        settings: ExportSettings,
        format: String,
        also: List<String>,
    ) {
        val store = Storage.store(this)
        val result =
            runCatching {
                val project = store.load(projectId)
                // The decision is made here, at the render boundary, from stored facts only.
                val request =
                    ExportRequest(
                        durationSeconds = project.duration,
                        height = settings.shortSide,
                        fps = settings.fps,
                        customBitrate = settings.bitrate != null,
                        multiAspectQueue = also.isNotEmpty(),
                        usesFounderGrade = com.zellahq.app.looks.Looks.usesFounderGrade(this, project),
                    )
                when (val d = PremiumGate.authorize(request, Entitlements.load(this), System.currentTimeMillis())) {
                    is ExportDecision.Blocked -> ExportStatus.Blocked(d.needs)
                    is ExportDecision.Allowed -> {
                        // Reversed clips need their backwards copy before they can be exported.
                        for (c in project.clips.filter { it.reversed }) {
                            if (exporterCancelled) return@runCatching ExportStatus.Cancelled
                            // Waits if the preview is making it. A clip that cannot go backwards fails the export below.
                            ReverseRenderer.render(this, store, project.id, c)
                        }
                        val builder =
                            com.zellahq.app.editor.appComposition(
                                this,
                                store,
                                reversedFile = { ReverseRenderer.cached(this, store, project.id, it) },
                            )
                        val e = Exporter(this, builder).also { exporter = it }
                        val safe = safeName(project.name)
                        val out = File(Storage.exports(this), "$safe-${System.currentTimeMillis() / 1000}.mp4")
                        val power = getSystemService(PowerManager::class.java)
                        when (
                            val o =
                                e.export(project, d.permit, settings, out) { pct ->
                                    // Honest about heat: a hot phone slows itself down, and the person should know why.
                                    val hot = power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                                    mutableStatus.value = ExportStatus.Running(pct, hot)
                                    getSystemService(NotificationManager::class.java).notify(ID, notification(pct))
                                }
                        ) {
                            is ExportOutcome.Done -> {
                                val first = convert(o, format)
                                // The same video again in each other shape, each with the permit the gate gave.
                                val more =
                                    if (first !is ExportStatus.Done) {
                                        emptyList()
                                    } else {
                                        also.mapIndexedNotNull { i, raw ->
                                            val aspect = com.zellahq.core.project.Aspect.of(raw)
                                            if (aspect == project.aspect || exporterCancelled) return@mapIndexedNotNull null
                                            mutableStatus.value = ExportStatus.Running((100 * (i + 1)) / (also.size + 1), false)
                                            val f =
                                                File(Storage.exports(this), "$safe-${aspect.raw}-${System.currentTimeMillis() / 1000}.mp4")
                                            val x = e.export(project.copy(aspect = aspect, platformPresetId = null), d.permit, settings, f)
                                            (x as? ExportOutcome.Done)?.file
                                        }
                                    }
                                if (first is ExportStatus.Done) {
                                    first.copy(
                                        more = more,
                                        missed =
                                            also.count {
                                                com.zellahq.core.project.Aspect.of(it) != project.aspect
                                            } - more.size,
                                    )
                                } else {
                                    first
                                }
                            }
                            is ExportOutcome.Failed -> ExportStatus.Failed(o.message)
                            ExportOutcome.Cancelled -> ExportStatus.Cancelled
                        }.also { e.release() }
                    }
                }
            }.getOrElse { ExportStatus.Failed(getString(R.string.ex_failed_generic)) }
        exporter = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        mutableStatus.value = result
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (result is ExportStatus.Done) ready()
        stopSelf()
    }

    /** A GIF or sound-only file is made from the finished video, so it holds exactly what the video holds. */
    private fun convert(
        o: ExportOutcome.Done,
        format: String,
    ): ExportStatus {
        val seconds = o.result.approximateDurationMs / 1000.0
        if (format == Formats.VIDEO) return ExportStatus.Done(o.file, seconds)
        val out = File(o.file.parentFile, o.file.nameWithoutExtension + if (format == Formats.GIF) ".gif" else ".m4a")
        val ok = runCatching { if (format == Formats.GIF) Formats.gif(o.file, out) else Formats.soundOnly(o.file, out) }.getOrDefault(false)
        o.file.delete()
        if (ok) return ExportStatus.Done(out, seconds)
        return ExportStatus.Failed(getString(if (format == Formats.SOUND) R.string.ex_no_sound else R.string.ex_failed_generic))
    }

    private fun openApp() =
        PendingIntent.getActivity(
            this,
            2,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )

    /** The person may have left the app. This says it finished and where to find it. */
    private fun ready() {
        getSystemService(NotificationManager::class.java).notify(
            READY_ID,
            Notification
                .Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle(getString(R.string.ex_done))
                .setContentText(getString(R.string.ex_done_notice))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notification(percent: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.ex_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(getString(R.string.ex_running, percent))
            .setContentIntent(openApp())
            .setProgress(100, percent, false)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.ex_cancel), cancel).build())
            .build()
    }
}
