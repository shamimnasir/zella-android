// Media3's composition preview and export are marked unstable and experimental. Zella depends on
// them on purpose: they are the only way to preview exactly what exports. Pinned to 1.10.1.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.zellahq.app.editor

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.zellahq.core.ExportPermit
import com.zellahq.core.project.Project
import com.zellahq.core.project.duration
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** What the person picked on the export screen. */
data class ExportSettings(
    /** Short side in pixels: 1080 free, 2160 with Pro. */
    val shortSide: Int = 1080,
    val fps: Int = 30,
    /** Null lets the encoder choose. A custom value is a Pro feature. */
    val bitrate: Int? = null,
    val hevc: Boolean = false,
)

sealed interface ExportOutcome {
    data class Done(val file: File, val result: ExportResult, val tookMs: Long) : ExportOutcome

    data class Failed(val message: String, val cause: Throwable?) : ExportOutcome

    data object Cancelled : ExportOutcome
}

/**
 * Renders a project to MP4. This is the render boundary the pricing rules are enforced at:
 * [export] takes an [ExportPermit], which only PremiumGate.authorize can make, and refuses settings
 * that go beyond what that permit approved. A UI bug can therefore not hand out Pro exports, and a
 * trial user can never be refused an export the gate allowed.
 */
@OptIn(UnstableApi::class)
class Exporter(
    private val context: Context,
    private val builder: CompositionBuilder,
) {
    @Volatile private var transformer: Transformer? = null

    @Volatile private var cancelled = false

    @Volatile private var matte: com.zellahq.app.cutout.MatteBaker? = null

    // The running export's result slot. Transformer.cancel() calls no listener, so cancel fills it.
    @Volatile private var pending: Pair<CountDownLatch, AtomicReference<ExportOutcome>>? = null
    private val thread = HandlerThread("export").apply { start() }
    private val handler = Handler(thread.looper)

    /** Blocks until done. Call off the main thread. [onProgress] gets 0 to 100. */
    fun export(
        project: Project,
        permit: ExportPermit,
        settings: ExportSettings,
        out: File,
        onProgress: (Int) -> Unit = {},
    ): ExportOutcome {
        val r = permit.request
        // The permit is for one exact request. Anything bigger than it is refused here.
        if (settings.shortSide > r.height || settings.fps > r.fps || (settings.bitrate != null && !r.customBitrate) ||
            project.duration > r.durationSeconds + 0.05
        ) {
            return ExportOutcome.Failed(context.getString(com.zellahq.app.R.string.ex_failed_generic), null)
        }
        val canvas = canvasSize(project, settings.shortSide)
        var built = builder.build(project, canvas, settings.fps)
        if (built.missing.isNotEmpty()) {
            return ExportOutcome.Failed(
                context.getString(com.zellahq.app.R.string.ex_err_missing),
                null,
            )
        }
        // A clip that should play backwards and cannot must not quietly go out forwards.
        if (built.needsReverse.isNotEmpty()) {
            return ExportOutcome.Failed(context.getString(com.zellahq.app.R.string.ex_reverse_failed), null)
        }
        // A person mask the preview has not finished yet comes before anything drawn from it.
        if (built.needsMatte.isNotEmpty()) {
            val baker = com.zellahq.app.cutout.MatteBaker(context).also { matte = it }
            built.needsMatte.forEach { f ->
                if (cancelled || !baker.bake(f)) {
                    return if (cancelled) {
                        ExportOutcome.Cancelled
                    } else {
                        ExportOutcome.Failed(
                            "The new background could not be prepared.",
                            null,
                        )
                    }
                }
            }
            built = builder.build(project, canvas, settings.fps)
        }
        // Transitions the preview has not rendered at this size yet come first.
        if (built.needsBake.isNotEmpty()) {
            built.needsBake.forEachIndexed { n, join ->
                if (cancelled) return ExportOutcome.Cancelled
                TransitionBaker.bake(context, builder, project, join, canvas)
                onProgress((n + 1) * 5 / built.needsBake.size)
            }
            built = builder.build(project, canvas, settings.fps)
        }
        if (cancelled) return ExportOutcome.Cancelled
        return run(built.composition, settings, out, onProgress)
    }

    /** Renders any composition with no pricing check. Only for intermediates kept in the app's own cache. */
    internal fun render(
        composition: Composition,
        settings: ExportSettings,
        out: File,
    ) = run(composition, settings, out) { }

    fun cancel() {
        cancelled = true
        matte?.stop()
        handler.post {
            transformer?.cancel()
            pending?.let { (latch, slot) ->
                slot.compareAndSet(null, ExportOutcome.Cancelled)
                latch.countDown()
            }
        }
    }

    private fun run(
        composition: Composition,
        settings: ExportSettings,
        out: File,
        onProgress: (Int) -> Unit,
    ): ExportOutcome {
        val done = CountDownLatch(1)
        val outcome = AtomicReference<ExportOutcome>()
        pending = done to outcome
        // A name of its own: two renders of one transition must not write into each other.
        val partial = File(out.parentFile, out.name + "." + System.nanoTime() + ".part")
        val t0 = System.nanoTime()
        handler.post {
            val video =
                VideoEncoderSettings
                    .Builder()
                    .apply { settings.bitrate?.let { setBitrate(it) } }
                    .build()
            val t =
                Transformer
                    .Builder(context)
                    .setLooper(thread.looper)
                    .setVideoMimeType(if (settings.hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory
                            .Builder(context)
                            .setRequestedVideoEncoderSettings(video)
                            .setEnableFallback(true) // a phone that cannot do the size or codec gets the nearest it can
                            .build(),
                    ).addListener(
                        object : Transformer.Listener {
                            override fun onCompleted(
                                composition: Composition,
                                exportResult: ExportResult,
                            ) {
                                // Rename last: a half-written export never sits under the real name.
                                val moved =
                                    runCatching {
                                        java.nio.file.Files.move(
                                            partial.toPath(),
                                            out.toPath(),
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                        )
                                    }
                                outcome.compareAndSet(
                                    null,
                                    if (moved.isSuccess) {
                                        ExportOutcome.Done(out, exportResult, (System.nanoTime() - t0) / 1_000_000)
                                    } else {
                                        ExportOutcome.Failed(
                                            context.getString(com.zellahq.app.R.string.ex_failed_generic),
                                            moved.exceptionOrNull(),
                                        )
                                    },
                                )
                                done.countDown()
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                partial.delete()
                                outcome.compareAndSet(
                                    null,
                                    ExportOutcome.Failed(plainMessage(exportException), exportException),
                                )
                                done.countDown()
                            }
                        },
                    ).build()
            transformer = t
            // A refusal here arrives as a throw on this thread, which would take the whole app down.
            try {
                t.start(composition, partial.path)
            } catch (e: Exception) {
                android.util.Log.e("ZellaExport", "start", e)
                outcome.compareAndSet(null, ExportOutcome.Failed(context.getString(com.zellahq.app.R.string.ex_failed_generic), e))
                done.countDown()
                return@post
            }
            val holder = ProgressHolder()

            fun poll() {
                if (done.count == 0L) return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                handler.postDelayed(::poll, 250)
            }
            poll()
        }
        done.await()
        transformer = null
        pending = null
        val o = outcome.get()
        if (o !is ExportOutcome.Done) partial.delete()
        return o
    }

    private fun plainMessage(e: ExportException) =
        when (e.errorCode) {
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                context.getString(com.zellahq.app.R.string.ex_err_encode)
            ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                context.getString(com.zellahq.app.R.string.ex_err_read)
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> context.getString(com.zellahq.app.R.string.ex_err_missing)
            ExportException.ERROR_CODE_IO_NO_PERMISSION -> context.getString(com.zellahq.app.R.string.ex_err_read)
            else -> context.getString(com.zellahq.app.R.string.ex_failed_generic)
        }

    fun release() {
        thread.quitSafely()
    }
}

/** Size of the canvas at a given short side, for the gate's height check. */
fun Project.exportSize(shortSide: Int): Size = canvasSize(this, shortSide)
