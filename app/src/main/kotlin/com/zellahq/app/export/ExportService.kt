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

    data class Done(val file: File, val seconds: Double) : ExportStatus

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
        const val ACTION_CANCEL = "cancel"
        private const val CHANNEL = "export"
        private const val ID = 2

        private val mutableStatus = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
        val status: StateFlow<ExportStatus> = mutableStatus

        fun reset() {
            if (mutableStatus.value !is ExportStatus.Running) mutableStatus.value = ExportStatus.Idle
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
        ): Intent {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send =
                Intent(Intent.ACTION_SEND)
                    .setType(Formats.mime(file))
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return Intent.createChooser(send, context.getString(R.string.ex_share))
        }
    }

    private var exporter: Exporter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CANCEL) {
            exporter?.cancel()
            return START_NOT_STICKY
        }
        if (status.value is ExportStatus.Running) return START_NOT_STICKY
        val projectId = intent?.getStringExtra(EXTRA_PROJECT) ?: return START_NOT_STICKY
        val settings =
            ExportSettings(
                shortSide = intent.getIntExtra(EXTRA_SHORT_SIDE, 1080),
                fps = intent.getIntExtra(EXTRA_FPS, 30),
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
        mutableStatus.value = ExportStatus.Running(0, false)
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: Formats.VIDEO
        Thread({ run(projectId, settings, format) }, "export").start()
        return START_NOT_STICKY
    }

    private fun run(
        projectId: String,
        settings: ExportSettings,
        format: String,
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
                        usesFounderGrade = com.zellahq.app.looks.Looks.usesFounderGrade(this, project),
                    )
                when (val d = PremiumGate.authorize(request, Entitlements.load(this), System.currentTimeMillis())) {
                    is ExportDecision.Blocked -> ExportStatus.Blocked(d.needs)
                    is ExportDecision.Allowed -> {
                        // Reversed clips need their backwards copy before they can be exported.
                        project.clips.filter { it.reversed }.forEach {
                            ReverseRenderer.render(
                                this,
                                store,
                                project.id,
                                it,
                            )
                        }
                        val builder =
                            com.zellahq.app.editor.appComposition(
                                this,
                                store,
                                reversedFile = { ReverseRenderer.cached(this, store, project.id, it) },
                            )
                        val e = Exporter(this, builder).also { exporter = it }
                        val safe = project.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Zella" }
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
                            is ExportOutcome.Done -> convert(o, format)
                            is ExportOutcome.Failed -> ExportStatus.Failed(o.message)
                            ExportOutcome.Cancelled -> ExportStatus.Cancelled
                        }.also { e.release() }
                    }
                }
            }.getOrElse { ExportStatus.Failed(getString(R.string.ex_failed_generic)) }
        exporter = null
        mutableStatus.value = result
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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
        val ok = if (format == Formats.GIF) Formats.gif(o.file, out) else Formats.soundOnly(o.file, out)
        o.file.delete()
        return if (ok) ExportStatus.Done(out, seconds) else ExportStatus.Failed(getString(R.string.ex_failed_generic))
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.ex_running, percent))
            .setProgress(100, percent, false)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.ex_cancel), cancel).build())
            .build()
    }
}
