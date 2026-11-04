@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.callouts.ScreenReader
import com.zellahq.app.editor.EditorState
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.editor.ReverseRenderer
import com.zellahq.app.editor.Storage
import com.zellahq.app.export.ExportService
import com.zellahq.app.recording.PatternSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import com.zellahq.core.EntitlementState
import com.zellahq.core.ExportDecision
import com.zellahq.core.ExportRequest
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.MusicBed
import com.zellahq.core.project.Project
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.setTransition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The audit's fixes, measured from the file or state that comes out of the real path. */
@RunWith(AndroidJUnit4::class)
class AuditFlowTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val dir = File(context.cacheDir, "audit").apply { mkdirs() }

    private fun log(text: String) = Log.i("AuditFlowTest", text)

    private fun picture(
        name: String,
        seconds: Int,
        color: Int,
        fps: Int = 30,
    ): File {
        val f = File(dir, "$name.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(fps, background = color), emptyMap())
            r.start()
            Thread.sleep(seconds * 1000L + 400)
            r.stop()
        }
        return f
    }

    private fun clip(
        f: File,
        seconds: Double,
    ) = Clip(newId(), MediaRef.Absolute(f.toURI().toString()), seconds, trimEnd = seconds)

    private fun project(clips: List<Clip>) =
        Project(
            id = newId(),
            name = "audit",
            createdAt = isoNow(),
            modifiedAt = isoNow(),
            clips = clips,
        )

    private fun export(
        p: Project,
        name: String,
        fps: Int = 30,
    ): File {
        val permit =
            (
                PremiumGate.authorize(
                    ExportRequest(p.duration, 720, fps),
                    EntitlementState(Plan.Pro, installEpochMs = System.currentTimeMillis()),
                    System.currentTimeMillis(),
                ) as ExportDecision.Allowed
            ).permit
        val out = File(dir, name).apply { delete() }
        val store = Storage.store(context)
        val o = Exporter(context, com.zellahq.app.editor.appComposition(context, store)).export(p, permit, ExportSettings(720, fps), out)
        assertTrue("export $name: $o", o is ExportOutcome.Done)
        return out
    }

    private fun videoTimes(f: File): List<Long> {
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        val t = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(t)
        val out = ArrayList<Long>()
        while (ex.sampleTime >= 0) {
            out += ex.sampleTime
            if (!ex.advance()) break
        }
        ex.release()
        return out.sorted()
    }

    private fun colourAt(
        f: File,
        seconds: Double,
    ): Int {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.path)
        val b = r.getFrameAtTime((seconds * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
        r.release()
        return b.getPixel(20, 20)
    }

    /** A fast source exported at Normal smoothness really comes out at about 30 frames a second. */
    @Test fun normalSmoothnessCapsTheFrameRate() {
        val src = picture("fps60", 3, Color.BLUE, fps = 60)
        val srcRate = videoTimes(src).size / 3.0
        assumeTrue("this machine records fast enough: $srcRate", srcRate > 40)
        val out = videoTimes(export(project(listOf(clip(src, 3.0))), "fps-out.mp4", fps = 30))
        val rate = (out.size - 1) * 1e6 / (out.last() - out.first())
        log("source $srcRate fps, export $rate fps")
        assertTrue("export ran at $rate", rate < 34)
    }

    /** A speed curve and a fade together: the length matches the timeline and the next clip is really there. */
    @Test fun speedCurveWithAFadeKeepsItsTiming() {
        val red = clip(picture("red", 4, Color.RED), 4.0)
        val ramped = red.copy(extras = JsonObject(red.extras + ("speedRamp" to JsonPrimitive("Slow-Fast-Slow"))))
        val green = clip(picture("green", 3, Color.GREEN), 3.0)
        val p = project(listOf(ramped, green)).setTransition(ramped.id, "Cross Dissolve", 0.5)
        val f = export(p, "ramp-fade.mp4")
        val times = videoTimes(f)
        val seconds = (times.last() - times.first()) / 1e6
        log("project ${p.duration}s, file ${seconds}s")
        assertEquals(p.duration, seconds, 0.3)
        assertTrue("clip two at the end", Color.green(colourAt(f, seconds - 0.3)) > 180)
    }

    /** A reversed copy keeps the source's real gaps between frames, mirrored. A steady rate would drift. */
    @Test fun aReversedClipMirrorsTheRealFrameTimes() {
        val src = picture("reverse-src", 3, Color.MAGENTA)
        val c = clip(src, 3.0).copy(reversed = true)
        val p = project(listOf(c))
        val store = Storage.store(context)
        store.save(p, allowCollapse = true)
        val started = System.nanoTime()
        val out = ReverseRenderer.render(context, store, p.id, c)!!
        log("reversing took ${(System.nanoTime() - started) / 1_000_000} ms")
        val a = videoTimes(src).filter { it < 2_999_500 }
        val b = videoTimes(out)
        val total = a.last() - a.first()
        val expected = a.map { a.last() - it }.toSet()
        val stray = b.count { t -> expected.none { abs(it - t) < 2_000 } }
        log(
            "source frames ${a.size}, reversed ${b.size}, times not from the source $stray, length ${b.last()} of $total us, first ${b.take(
                3,
            )}, strays ${b.filter {
                    t ->
                expected.none { abs(it - t) < 2_000 }
            }.take(6)}, expected ${expected.sorted().take(3)}",
        )
        assertTrue("almost every frame kept: ${a.size} and ${b.size}", b.size >= a.size * 0.9)
        assertEquals("every frame keeps a real time", 0, stray)
        assertTrue("the length holds", abs(b.last() - total) < 80_000)
        // The picture at each time is the source's picture at the mirrored time, not just the right count of frames.
        val rs = MediaMetadataRetriever().apply { setDataSource(src.path) }
        val rr = MediaMetadataRetriever().apply { setDataSource(out.path) }
        listOf(0.25, 0.75).forEach { f ->
            val t = (b.last() * f).toLong()
            val rev = rr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val mirrored = rs.getFrameAtTime(a.last() - t, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val same = rs.getFrameAtTime(a.first() + t, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val good = difference(rev, mirrored)
            val wrong = difference(rev, same)
            log("at $f: against the mirrored frame $good, against the unreversed frame $wrong")
            assertTrue("at $f the picture is the mirrored one: $good against $wrong", good < wrong)
            // All three colours are compared, so a wrong colour conversion fails here too.
            assertTrue("at $f the colours survive: $good", good < 6)
        }
        rs.release()
        rr.release()
    }

    private fun difference(
        a: Bitmap,
        b: Bitmap,
    ): Double {
        var sum = 0L
        var n = 0
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        for (y in 0 until h step 8) {
            for (x in 0 until w step 8) {
                val p = a.getPixel(x, y)
                val q = b.getPixel(x, y)
                sum += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
                n++
            }
        }
        return sum.toDouble() / n
    }

    /** A password appearing on a still page changes a tiny part of the frame. That frame must be read. */
    @Test fun aSmallChangeOnAStillPageIsNotTheSameFrame() {
        val a = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val b = a.copy(Bitmap.Config.ARGB_8888, true)
        assertTrue(ScreenReader.sameFrame(a, b))
        for (x in 300 until 360) for (y in 170 until 176) b.setPixel(x, y, Color.BLACK)
        assertFalse(ScreenReader.sameFrame(a, b))
    }

    /** The fade out of a trimmed clip falls at the end of the part that is kept, not at the end of the source file. */
    @Test fun aTrimmedClipFadesAtItsOwnEnd() {
        val seconds = 6
        val tone = FloatArray(seconds * 48_000) { (0.25f * sin(2 * PI * 440 * it / 48_000)).toFloat() }
        val wav = AudioTestKit.wav(File(dir, "tone.wav"), tone)
        val pic = clip(picture("tone-pic", seconds, Color.DKGRAY), seconds.toDouble())
        val bed =
            MusicBed(newId(), MediaRef.Absolute(wav.toURI().toString()), seconds.toDouble(), volume = 1.0, duck = false, name = "tone")
        val made = export(project(listOf(pic)).copy(musicBeds = listOf(bed)), "tone.mp4")
        val first = Clip(newId(), MediaRef.Absolute(made.toURI().toString()), 6.0, trimStart = 1.0, trimEnd = 4.0)
        val second = clip(picture("tone-next", 3, Color.BLACK), 3.0)
        val p = project(listOf(first, second)).setTransition(first.id, "Cross Dissolve", 1.0)
        val d = AudioTestKit.decode(export(p, "tone-fade.mp4"))
        val cut = first.timelineDuration
        val steady = d.rmsDb(0.5, 1.0)
        val mid = d.rmsDb(cut - 0.5, cut - 0.4)
        val end = d.rmsDb(cut - 0.12, cut - 0.02)
        log("steady $steady dB, half way through the fade $mid dB, at its end $end dB")
        assertTrue("level held before the fade: $steady", steady > -22)
        assertTrue("half way is lower: $mid", mid < steady - 1 && mid > steady - 8)
        assertTrue("the end is much lower: $end", end < steady - 10)
    }

    /** Leaving the editor while Auto-Polish runs must not crash, and must not write a late result over the project. */
    @Test fun leavingTheEditorDuringAutoPolishIsSafe() {
        val p = project(listOf(clip(picture("polish", 4, Color.GRAY), 4.0)))
        val store = Storage.store(context)
        store.save(p, allowCollapse = true)
        val done = CountDownLatch(1)
        lateinit var s: EditorState
        inst.runOnMainSync {
            s = EditorState(context, p)
            s.runAuto(false) { done.countDown() }
        }
        Thread.sleep(400)
        inst.runOnMainSync { s.release() }
        done.await(60, TimeUnit.SECONDS)
        Thread.sleep(500)
        val back = store.load(p.id)
        assertEquals("nothing arrived late", p.clips.size, back.clips.size)
        assertEquals(p.duration, back.duration, 0.01)
    }

    /** A finished export's file is listed under its own project's name, in any language. */
    @Test fun earlierExportsFollowTheProjectName() {
        val name = "ভিডিও ${newId().take(6)}"
        val safe = ExportService.safeName(name)
        assertTrue("letters stay: $safe", safe.contains("ভিডিও"))
        val exports = Storage.exports(context)
        val mine = File(exports, "$safe-1.mp4").apply { writeText("x") }
        val other = File(exports, "Other-${newId().take(6)}-2.mp4").apply { writeText("x") }
        val part = File(exports, "$safe-3.mp4.part").apply { writeText("x") }
        try {
            assertEquals(listOf(mine), ExportService.earlier(context, name))
        } finally {
            listOf(mine, other, part).forEach { it.delete() }
        }
    }
}
