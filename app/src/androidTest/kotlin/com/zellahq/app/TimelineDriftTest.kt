package com.zellahq.app

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.editor.appComposition
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
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.setTransition
import com.zellahq.core.project.startTime
import com.zellahq.core.project.transitionOverlap
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Where the real frames land against the timeline. Captions, titles, music and transitions all use
 * timeline times, so if frames slide away from them as clips add up, everything after a few cuts is
 * late. 24 clips of a pure colour, alternating, and the time of every colour change read from the file.
 */
@RunWith(AndroidJUnit4::class)
class TimelineDriftTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun picture(color: Int): File {
        val f = File(context.cacheDir, "drift-$color.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(360, 640), PatternSource(30, background = color), emptyMap())
            r.start()
            Thread.sleep(2_300)
            r.stop()
        }
        return f
    }

    /** The same with a transition on every join, which puts clips on two tracks. Glitch Cut changes clip exactly at its middle. */
    @Test fun framesStayOnTheTimelineOnTwoTracks() {
        val sources = listOf(picture(Color.RED), picture(Color.GREEN))
        var p =
            Project(
                id = newId(),
                name = "drift2",
                createdAt = isoNow(),
                modifiedAt = isoNow(),
                clips =
                    List(
                        12,
                    ) { i -> Clip(newId(), MediaRef.Absolute(sources[i % 2].toURI().toString()), 2.0, trimStart = 0.0, trimEnd = 2.0) },
            )
        p.clips.dropLast(1).forEach { c -> p = p.setTransition(c.id, "Glitch Cut", 0.5) }
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit = (PremiumGate.authorize(ExportRequest(100.0, 360), gate, System.currentTimeMillis()) as ExportDecision.Allowed).permit
        val out = File(context.cacheDir, "drift2-out.mp4").apply { delete() }
        val o =
            Exporter(context, appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))))
                .export(p, permit, ExportSettings(shortSide = 360), out)
        assertTrue("export: $o", o is ExportOutcome.Done)
        val ex = MediaExtractor().apply { setDataSource(out.path) }
        val video = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(video)
        val times = ArrayList<Long>()
        while (ex.sampleTime >= 0) {
            times += ex.sampleTime
            ex.advance()
        }
        ex.release()
        times.sort()
        val mmr = MediaMetadataRetriever().apply { setDataSource(out.path) }
        val seq = StringBuilder()
        var last = ""
        for (t in times) {
            val b = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
            val c = b.getPixel(4, 4)
            val now =
                when {
                    Color.red(c) > 150 && Color.green(c) < 100 -> "R"
                    Color.green(c) > 150 && Color.red(c) < 100 -> "G"
                    Color.red(c) < 40 && Color.green(c) < 40 -> "K"
                    else -> "?"
                }
            if (now != last) seq.append(" $now@${t / 1000}")
            last = now
        }
        mmr.release()
        val expect = (1 until 12).map { i -> ((p.startTime(p.clips[i].id) + p.transitionOverlap(i - 1) / 2) * 1000).toLong() }
        Log.i("TimelineDrift", "two tracks: frames ${times.size}, last ${times.last() / 1000} ms, model ${p.duration * 1000} ms")
        Log.i("TimelineDrift", "two tracks: expected changes at $expect")
        Log.i("TimelineDrift", "two tracks: found$seq")
        // Black frames were the visible sign of the texture pool bug, and every change must land on its time.
        val found = seq.toString().trim().split(" ").map { it.split("@") }
        assertTrue("black frames in $seq", found.none { it[0] == "K" })
        val changes = found.drop(2).map { it[1].toLong() }
        assertTrue("$changes against $expect", changes.size == expect.size)
        changes.zip(expect).forEach { (a, b) -> assertTrue("change at $a ms, expected $b ms", abs(a - b) <= 60) }
    }

    @Test fun framesStayOnTheTimeline() {
        val sources = listOf(picture(Color.RED), picture(Color.GREEN))
        sources.forEach { f ->
            val ex = MediaExtractor().apply { setDataSource(f.path) }
            ex.selectTrack(0)
            val first = ex.sampleTime
            ex.advance()
            val second = ex.sampleTime
            val fmt = ex.getTrackFormat(0)
            Log.i(
                "TimelineDrift",
                "${f.name}: first frame at $first us, second $second us, length ${fmt.getLong(MediaFormat.KEY_DURATION)} us",
            )
            ex.release()
        }
        val clips =
            List(24) { i ->
                Clip(newId(), MediaRef.Absolute(sources[i % 2].toURI().toString()), 2.0, trimStart = 0.0, trimEnd = 1.0)
            }
        val p = Project(id = newId(), name = "drift", createdAt = isoNow(), modifiedAt = isoNow(), clips = clips)
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit = (PremiumGate.authorize(ExportRequest(100.0, 360), gate, System.currentTimeMillis()) as ExportDecision.Allowed).permit
        val out = File(context.cacheDir, "drift-out.mp4").apply { delete() }
        val o =
            Exporter(context, appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))))
                .export(p, permit, ExportSettings(shortSide = 360), out)
        assertTrue("export: $o", o is ExportOutcome.Done)

        // Every video frame's own time from the file, and its colour from the decoder.
        val ex = MediaExtractor().apply { setDataSource(out.path) }
        val video = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(video)
        val times = ArrayList<Long>()
        while (ex.sampleTime >= 0) {
            times += ex.sampleTime
            ex.advance()
        }
        ex.release()
        times.sort()
        val mmr = MediaMetadataRetriever().apply { setDataSource(out.path) }
        var last = -1
        val changes = ArrayList<Long>()
        for (t in times) {
            val b = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
            val c = b.getPixel(4, 4)
            val now = if (Color.red(c) > Color.green(c)) 0 else 1
            // The red test picture's very first frame is white, so colours are read from the third frame on.
            if (t >= 60_000) {
                if (last >= 0 && now != last) changes += t
                last = now
            }
        }
        mmr.release()
        val nominal = (1 until 24).map { (p.startTime(clips[it].id) * 1e6).toLong() }
        val diffs = changes.zip(nominal).map { (a, b) -> (a - b) / 1000 }
        Log.i("TimelineDrift", "frames ${times.size}, first ${times.first()} us, last ${times.last()} us")
        Log.i("TimelineDrift", "changes found ${changes.size} of ${nominal.size}; ms late at each cut: $diffs")
        // A cut into the red picture can read one frame late for the same reason: its first frame is white.
        assertTrue("worst ${diffs.maxOf { abs(it) }} ms", diffs.all { abs(it) <= 60 })
    }
}
