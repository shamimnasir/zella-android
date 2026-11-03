package com.zellahq.app

import android.app.Activity
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Bundle
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug only. Plays a 50 ms beep every 2 s and turns the screen white on the frame where that beep
 * actually leaves the audio output (from AudioTrack's timestamp), so speaker latency is not counted.
 * Record the screen with Zella while this runs, then: tools/measure-av-sync.py recording.mp4
 */
class SyncTestActivity : Activity() {
    private val rate = 48_000
    private val periodFrames = rate * 2L
    private lateinit var view: View
    private lateinit var track: AudioTrack

    @Volatile private var running = true
    private var flashAtNs = Long.MAX_VALUE
    private var flashOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = View(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(view)
        track =
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        track.play()
        Thread(::feed, "sync-audio").start()
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** Writes silence with a beep at the start of every period, forever, and tracks when it plays. */
    private fun feed() {
        val chunk = ShortArray(rate / 100)
        var written = 0L
        val ts = AudioTimestamp()
        var nextBeep = periodFrames
        while (running) {
            for (i in chunk.indices) {
                val f = written + i
                val inBeep = f % periodFrames < rate / 20 && f >= periodFrames
                chunk[i] = if (inBeep) (sin(2 * PI * 1000 * f / rate) * 26_000).toInt().toShort() else 0
            }
            track.write(chunk, 0, chunk.size)
            written += chunk.size
            if (track.getTimestamp(ts) && nextBeep <= written) {
                // When frame `nextBeep` reaches the speaker, in the same clock as frame callbacks.
                val at = ts.nanoTime + (nextBeep - ts.framePosition) * 1_000_000_000L / rate
                synchronized(this) { flashAtNs = at }
                nextBeep += periodFrames
            }
        }
    }

    private val frame =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                val at = synchronized(this@SyncTestActivity) { flashAtNs }
                // Light the frame whose time is nearest the beep; one frame is 16 ms at 60 Hz.
                val on = frameTimeNanos >= at - 8_000_000 && frameTimeNanos < at + 42_000_000
                if (on != flashOn) {
                    view.setBackgroundColor(if (on) Color.WHITE else Color.BLACK)
                    flashOn = on
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

    override fun onDestroy() {
        running = false
        track.stop()
        track.release()
        super.onDestroy()
    }
}
