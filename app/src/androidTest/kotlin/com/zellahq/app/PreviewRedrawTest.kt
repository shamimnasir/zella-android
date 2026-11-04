@file:Suppress("ktlint:standard:max-line-length")

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
import androidx.media3.transformer.CompositionPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.editor.appComposition
import com.zellahq.app.editor.previewPlayer
import com.zellahq.app.recording.PatternSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A paused preview must show its picture again after an edit and after the surface changes size. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PreviewRedrawTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val inst = InstrumentationRegistry.getInstrumentation()

    private inner class Screen(w: Int, h: Int) {
        val thread = HandlerThread("screen").apply { start() }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        val frames = AtomicInteger()
        val colour = AtomicReference<Int?>(null)

        init {
            reader.setOnImageAvailableListener({ r ->
                r.acquireLatestImage()?.use { img ->
                    val p = img.planes[0]
                    val at = (img.height / 2) * p.rowStride + (img.width / 2) * p.pixelStride
                    colour.set(
                        Color.rgb(
                            p.buffer.get(at).toInt() and 255,
                            p.buffer.get(at + 1).toInt() and 255,
                            p.buffer.get(at + 2).toInt() and 255,
                        ),
                    )
                    frames.incrementAndGet()
                }
            }, Handler(thread.looper))
        }

        fun close() {
            reader.close()
            thread.quitSafely()
        }
    }

    private fun green(c: Int?) = c != null && Color.green(c) > 180 && Color.red(c) < 80

    private fun project(): Project {
        val f = File(context.cacheDir, "redraw-green.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(30, background = Color.GREEN) { _, _ -> }, emptyMap())
            r.start()
            Thread.sleep(2_300)
            r.stop()
        }
        return Project(
            id = newId(),
            name = "r",
            createdAt = isoNow(),
            modifiedAt = isoNow(),
            clips = listOf(Clip(newId(), MediaRef.Absolute(f.toURI().toString()), 2.0, trimEnd = 2.0)),
        )
    }

    private fun composition() = appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))).build(project()).composition

    private fun onMain(block: () -> Unit) = inst.runOnMainSync(block)

    @Test fun whatRedrawsAPausedPreview() {
        val a = Screen(360, 640)
        val ready = CountDownLatch(1)
        lateinit var p: CompositionPlayer
        onMain {
            p = previewPlayer(context)
            p.setVideoSurface(a.reader.surface, Size(360, 640))
            p.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) ready.countDown()
                    }
                },
            )
            p.setComposition(composition())
            p.prepare()
        }
        assertTrue(ready.await(20, TimeUnit.SECONDS))
        Thread.sleep(1_000)
        Log.i("Redraw", "first: ${a.frames.get()} frames, green ${green(a.colour.get())}")
        assertTrue("first picture", green(a.colour.get()))

        // Case 1: the composition is set again, as after an edit.
        var before = a.frames.get()
        a.colour.set(null)
        onMain {
            p.setComposition(composition(), 500)
            p.prepare()
        }
        Thread.sleep(1_500)
        Log.i("Redraw", "after setComposition: ${a.frames.get() - before} frames, ${a.colour.get()?.let { Integer.toHexString(it) }}")
        val afterEdit = green(a.colour.get())

        // Case 2: a seek to the same place on the same surface.
        before = a.frames.get()
        onMain { p.seekTo(500) }
        Thread.sleep(1_000)
        Log.i("Redraw", "same seek: ${a.frames.get() - before} frames")

        // Case 3: a surface of another size, as when a panel opens.
        val b = Screen(180, 320)
        onMain { p.setVideoSurface(b.reader.surface, Size(180, 320)) }
        Thread.sleep(1_000)
        Log.i("Redraw", "new surface alone: ${b.frames.get()} frames")
        onMain { p.seekTo(p.currentPosition) }
        Thread.sleep(1_000)
        Log.i("Redraw", "new surface then same seek: ${b.frames.get()} frames")
        onMain { p.seekTo(p.currentPosition + 34) }
        Thread.sleep(1_000)
        Log.i("Redraw", "new surface then nearby seek: ${b.frames.get()} frames, green ${green(b.colour.get())}")
        val c = Screen(360, 640)
        onMain {
            p.setVideoSurface(c.reader.surface, Size(360, 640))
            p.setComposition(composition(), 500)
            p.prepare()
        }
        Thread.sleep(1_500)
        Log.i("Redraw", "new surface then setComposition: ${c.frames.get()} frames, green ${green(c.colour.get())}")
        onMain { p.release() }
        a.close()
        b.close()
        c.close()
        assertTrue("an edit must redraw", afterEdit)
    }
}
