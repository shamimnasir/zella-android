package com.zellahq.app.recording

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.view.Surface

/** Mirrors the whole screen into the encoder. */
class ScreenSource(
    private val projection: MediaProjection,
    private val dpi: Int,
) : VideoSource {
    private var display: VirtualDisplay? = null

    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        display =
            projection.createVirtualDisplay(
                "Zella",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null,
            )
    }

    override fun detach() {
        display?.release()
        display = null
    }
}

/**
 * Draws a frame counter and a full white flash once every [flashEveryMs]. Used by the automated
 * stop-sequence loop, and by the sync measurement together with a click played at the flash.
 */
class PatternSource(
    private val fps: Int,
    private val flashEveryMs: Long = 2_000,
    /** Wait before the first frame, like a real screen share does. Used to test track alignment. */
    private val startDelayMs: Long = 0,
    private val onFlash: ((Long) -> Unit)? = null,
) : VideoSource {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
    ) {
        running = true
        thread =
            Thread({
                if (startDelayMs > 0) Thread.sleep(startDelayMs)
                val paint = Paint().apply { textSize = height / 10f }
                val t0 = System.nanoTime()
                var frame = 0L
                var lastFlash = -1L
                while (running) {
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                    val slot = elapsedMs / flashEveryMs
                    val flash = elapsedMs % flashEveryMs < 1000 / fps && slot != lastFlash
                    val canvas = runCatching { surface.lockHardwareCanvas() }.getOrNull() ?: break
                    if (flash) {
                        canvas.drawColor(Color.WHITE)
                        lastFlash = slot
                    } else {
                        canvas.drawColor(Color.BLACK)
                        paint.color = Color.rgb(124, 108, 255)
                        canvas.drawText("frame $frame", width / 10f, height / 2f, paint)
                    }
                    surface.unlockCanvasAndPost(canvas)
                    if (flash) onFlash?.invoke(System.nanoTime())
                    frame++
                    Thread.sleep(1000L / fps)
                }
            }, "pattern").also { it.start() }
    }

    override fun detach() {
        running = false
        thread?.join(2_000)
    }
}

object AudioSources {
    /** The microphone, unprocessed where the phone allows it, so the voice is captured dry. */
    @SuppressLint("MissingPermission")
    fun mic(context: Context) =
        AudioSourceFactory { rate, mask, bytes ->
            val am = context.getSystemService(AudioManager::class.java)
            val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val src = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
            runCatching { AudioRecord(src, rate, mask, AudioFormat.ENCODING_PCM_16BIT, bytes) }.getOrNull()
        }

    /** Sound other apps play. Apps can opt out, in which case this track is silent. */
    @SuppressLint("MissingPermission")
    fun device(projection: MediaProjection) =
        AudioSourceFactory { rate, mask, bytes ->
            val cfg =
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
            runCatching {
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask)
                            .build(),
                    ).setBufferSizeInBytes(bytes)
                    .build()
            }.getOrNull()
        }
}
