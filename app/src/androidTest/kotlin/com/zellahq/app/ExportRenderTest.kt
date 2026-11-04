package com.zellahq.app

import android.Manifest
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.recording.AudioSources
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
import com.zellahq.core.project.ZoomBlock
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.setTransition
import com.zellahq.core.project.startTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The render path, end to end: real clips recorded on the device, a project with a trim, a speed
 * change, a cross-fade and a zoom, exported through the same builder the preview uses. Checks the
 * exported length against the project timeline, then reads pixels to prove the cross-fade rendered.
 */
@RunWith(AndroidJUnit4::class)
class ExportRenderTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "render").apply { mkdirs() }

    private fun clip(
        name: String,
        color: Int,
    ): File {
        val f = File(dir, name)
        if (f.isFile) return f
        val r =
            Recorder(
                f,
                RecorderConfig(720, 1280),
                PatternSource(30, background = color),
                mapOf("voice" to AudioSources.mic(context)),
            )
        r.start()
        Thread.sleep(3_300)
        r.stop()
        return f
    }

    private fun source(f: File) = MediaRef.Absolute(f.toURI().toString())

    @Test fun exportMatchesTheTimelineAndRendersTheCrossfade() {
        val red = clip("red.mp4", Color.RED)
        val green = clip("green.mp4", Color.GREEN)
        val blue = clip("blue.mp4", Color.BLUE)
        val a = Clip(newId(), source(red), 3.0, trimStart = 0.5, trimEnd = 2.5) // 2.0 s
        val b = Clip(newId(), source(green), 3.0, trimStart = 0.0, trimEnd = 3.0, speed = 2.0) // 1.5 s
        val c = Clip(newId(), source(blue), 3.0, trimStart = 0.0, trimEnd = 2.0) // 2.0 s
        var p =
            Project(
                id = newId(),
                name = "render",
                createdAt = isoNow(),
                modifiedAt = isoNow(),
                clips = listOf(a, b, c),
                zoomBlocks = listOf(ZoomBlock(newId(), start = 4.0, scale = 1.3)),
            )
        p = p.setTransition(a.id, "Cross Dissolve", 0.5)
        // 2.0 + 1.5 + 2.0 minus the 0.5 overlap.
        assertEquals(5.0, p.duration, 1e-9)

        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis()) // in Pro Week
        val permit =
            (
                PremiumGate.authorize(
                    ExportRequest(p.duration, 720),
                    gate,
                    System.currentTimeMillis(),
                ) as ExportDecision.Allowed
            ).permit
        val store = ProjectStore(File(context.filesDir, "projects-test"))
        val exporter = Exporter(context, com.zellahq.app.editor.appComposition(context, store))
        val out = File(dir, "out.mp4")
        val outcome = exporter.export(p, permit, ExportSettings(shortSide = 720), out)
        assertTrue("export: $outcome", outcome is ExportOutcome.Done)
        val done = outcome as ExportOutcome.Done
        Log.i(
            "ExportRenderTest",
            "export took ${done.tookMs} ms, ${done.result.width}x${done.result.height}, " +
                "${done.result.videoFrameCount} frames, ${done.result.approximateDurationMs} ms",
        )

        // Length: within two frames of the timeline.
        val ex = MediaExtractor().apply { setDataSource(out.path) }
        val tracks = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
        ex.release()
        val video = tracks.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        assertTrue("has audio", tracks.any { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") })
        val lenMs = video.getLong(MediaFormat.KEY_DURATION) / 1000
        Log.i("ExportRenderTest", "video track $lenMs ms, timeline ${p.duration * 1000} ms")
        assertTrue("length $lenMs ms vs 5000 ms", abs(lenMs - 5000) <= 70)
        assertEquals(720, minOf(video.getInteger(MediaFormat.KEY_WIDTH), video.getInteger(MediaFormat.KEY_HEIGHT)))

        // Pixels: red before the fade, a red-green mix in the middle of it, green after, blue at the end.
        val fadeStart = p.startTime(b.id) // 1.5 s
        val mmr = MediaMetadataRetriever().apply { setDataSource(out.path) }

        fun corner(t: Double): Int {
            val bmp = mmr.getFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
            return bmp.getPixel(8, 8)
        }
        val before = corner(fadeStart - 0.3)
        val middle = corner(fadeStart + 0.25)
        val after = corner(fadeStart + 0.8)
        val end = corner(4.7)
        mmr.release()
        Log.i(
            "ExportRenderTest",
            "pixels before=${hex(before)} middle=${hex(middle)} after=${hex(after)} end=${hex(end)}",
        )
        assertTrue("before fade is red: ${hex(before)}", Color.red(before) > 200 && Color.green(before) < 60)
        assertTrue(
            "mid fade mixes red and green: ${hex(middle)}",
            Color.red(middle) in 60..200 && Color.green(middle) in 60..200,
        )
        assertTrue("after fade is green: ${hex(after)}", Color.green(after) > 200 && Color.red(after) < 60)
        assertTrue("last clip is blue: ${hex(end)}", Color.blue(end) > 200 && Color.red(end) < 60)
    }

    /** Two clips and a fade: the second clip lives only on the lower track. It must not be cut off. */
    @Test fun aFadeIntoTheLastClipKeepsAllOfIt() {
        val red = clip("red.mp4", Color.RED)
        val green = clip("green.mp4", Color.GREEN)
        val a = Clip(newId(), source(red), 3.0, trimStart = 0.0, trimEnd = 2.0)
        val b = Clip(newId(), source(green), 3.0, trimStart = 0.0, trimEnd = 3.0, speed = 2.0) // 1.5 s
        val p =
            Project(id = newId(), name = "two", createdAt = isoNow(), modifiedAt = isoNow(), clips = listOf(a, b))
                .setTransition(a.id, "Cross Dissolve", 0.5)
        assertEquals(3.0, p.duration, 1e-9)
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit =
            (
                PremiumGate.authorize(
                    ExportRequest(p.duration, 720),
                    gate,
                    System.currentTimeMillis(),
                ) as ExportDecision.Allowed
            ).permit
        val out = File(dir, "two.mp4")
        val o =
            Exporter(
                context,
                com.zellahq.app.editor.appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))),
            ).export(p, permit, ExportSettings(shortSide = 720), out)
        assertTrue("export: $o", o is ExportOutcome.Done)
        val ex = MediaExtractor().apply { setDataSource(out.path) }
        val video =
            (0 until ex.trackCount).map {
                ex.getTrackFormat(
                    it,
                )
            }.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.release()
        val lenMs = video.getLong(MediaFormat.KEY_DURATION) / 1000
        Log.i("ExportRenderTest", "two-clip video track $lenMs ms, timeline 3000 ms")
        assertTrue("video length $lenMs ms vs 3000 ms", abs(lenMs - 3000) <= 70)
        val mmr = MediaMetadataRetriever().apply { setDataSource(out.path) }
        val last = mmr.getFrameAtTime(2_800_000, MediaMetadataRetriever.OPTION_CLOSEST)!!.getPixel(8, 8)
        mmr.release()
        assertTrue("the end is the green clip: ${hex(last)}", Color.green(last) > 200 && Color.red(last) < 60)
    }

    @Test fun theExporterRefusesSettingsBeyondItsPermit() {
        val p =
            Project(
                id = newId(),
                name = "x",
                createdAt = isoNow(),
                modifiedAt = isoNow(),
                clips = listOf(Clip(newId(), source(File(dir, "red.mp4")), 3.0)),
            )
        val freeAfterTrial = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis() - 8L * 86_400_000)
        val permit =
            (
                PremiumGate.authorize(
                    ExportRequest(p.duration, 1080),
                    freeAfterTrial,
                    System.currentTimeMillis(),
                ) as ExportDecision.Allowed
            ).permit
        val exporter =
            Exporter(context, com.zellahq.app.editor.appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))))
        val outcome = exporter.export(p, permit, ExportSettings(shortSide = 2160), File(dir, "never.mp4"))
        assertTrue("4K on a 1080p permit must be refused: $outcome", outcome is ExportOutcome.Failed)
        assertTrue(!File(dir, "never.mp4").exists())
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
}
