package com.zellahq.app

import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.recording.PatternSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.setTransition
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * After a seek while paused, the preview must show the frame at the new time. On the emulator the
 * picture stayed on the old frame while the playhead moved. This renders into an ImageReader, seeks
 * from a red clip into a green clip while paused, and reads the colour that actually arrived.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PausedSeekTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun clip(
        name: String,
        color: Int,
    ): Clip {
        val f = File(context.cacheDir, name)
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(30, background = color), emptyMap())
            r.start()
            Thread.sleep(2_300)
            r.stop()
        }
        return Clip(newId(), MediaRef.Absolute(f.toURI().toString()), 2.0, trimEnd = 2.0)
    }

    private fun project(fade: Boolean): Project {
        val red = clip("seek-red.mp4", Color.RED)
        val green = clip("seek-green.mp4", Color.GREEN)
        val p = Project(id = newId(), name = "seek", createdAt = isoNow(), modifiedAt = isoNow(), clips = listOf(red, green))
        return if (fade) p.setTransition(red.id, "Cross Dissolve", 0.5) else p
    }

    /** Seeks to [toMs] while paused and returns the colour of the newest frame that reached the screen. */
    private fun colourAfterPausedSeek(
        player: () -> CompositionPlayer,
        project: Project,
        toMs: Long,
        after: (CompositionPlayer) -> Unit = {},
    ): Pair<Int?, Int> {
        val composition =
            com.zellahq.app.editor.appComposition(
                context,
                ProjectStore(File(context.filesDir, "projects-test")),
            ).build(project).composition
        val thread = HandlerThread("reader").apply { start() }
        val reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 3)

        val lastColour = java.util.concurrent.atomic.AtomicReference<Int?>(null)
        val count = AtomicInteger()
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { img ->
                val plane = img.planes[0]
                val b = plane.buffer
                val px = 20 * plane.rowStride + 20 * plane.pixelStride
                lastColour.set(Color.rgb(b.get(px).toInt() and 255, b.get(px + 1).toInt() and 255, b.get(px + 2).toInt() and 255))
                count.incrementAndGet()
            }
        }, Handler(thread.looper))
        val ready = CountDownLatch(1)
        lateinit var p: CompositionPlayer
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.runOnMainSync {
            p = player()
            p.setVideoSurface(reader.surface, Size(720, 1280))
            p.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.countDown()
                    }
                },
            )
            p.setComposition(composition)
            p.prepare()
        }
        assertTrue("ready", ready.await(20, TimeUnit.SECONDS))
        Thread.sleep(800)
        val before = count.get()
        inst.runOnMainSync {
            p.seekTo(toMs)
            after(p)
        }
        Thread.sleep(2_500)
        inst.runOnMainSync { p.release() }
        reader.close()
        thread.quitSafely()
        return lastColour.get() to (count.get() - before)
    }

    private fun green(c: Int?) = c != null && Color.green(c) > 180 && Color.red(c) < 80

    private fun hex(c: Int?) = c?.let { String.format("#%06X", it and 0xFFFFFF) } ?: "none"

    @Test fun pausedSeekShowsTheNewFrame() {
        val single = { CompositionPlayer.Builder(context).build() }
        val multi = { CompositionPlayer.Builder(context).setVideoGraphFactory(MultipleInputVideoGraph.Factory()).build() }
        val plain = project(fade = false)
        val faded = project(fade = true)
        val runs =
            listOf(
                Triple("single-input, no fade", single, plain),
                Triple("multi-input, no fade", multi, plain),
                Triple("multi-input, fade", multi, faded),
            ).associate { (name, make, proj) -> name to colourAfterPausedSeek(make, proj, 3_000) }
        runs.forEach { (k, v) -> Log.i("PausedSeekTest", "$k: newest ${hex(v.first)}, ${v.second} frames after the seek") }

        val nudged =
            colourAfterPausedSeek(multi, faded, 3_000) { pl ->
                pl.volume = 0f
                pl.play()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    pl.pause()
                    pl.seekTo(3_000)
                    pl.volume = 1f
                }, 250)
            }
        Log.i("PausedSeekTest", "multi-input, fade, play 250 ms then back: newest ${hex(nudged.first)}, ${nudged.second} frames")

        val app = colourAfterPausedSeek({ com.zellahq.app.editor.previewPlayer(context, multi = false) }, plain, 3_000)
        Log.i("PausedSeekTest", "app preview, no fade: newest ${hex(app.first)}, ${app.second} frames")
        assertTrue("no-fade preview stayed on the old frame: ${hex(app.first)}", green(app.first))
    }
}
