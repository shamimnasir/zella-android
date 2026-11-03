package com.zellahq.app

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.zellahq.app.recording.AudioSources
import com.zellahq.app.recording.PatternSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * A screen share delivers its first frame late (1.3 s on the emulator). Media3's fragmented MP4 plays
 * every track's first sample at zero, so if audio started earlier the picture ran ahead of the sound.
 * This test delays the first frame on purpose and requires both tracks to cover the same time.
 */
@RunWith(AndroidJUnit4::class)
class TrackAlignmentTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test fun lateFirstFrameDoesNotShiftAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(context.cacheDir, "align.mp4").apply { delete() }
        val r =
            Recorder(
                f,
                RecorderConfig(720, 1280),
                PatternSource(30, startDelayMs = 1_000),
                mapOf("voice" to AudioSources.mic(context)),
            )
        r.start()
        Thread.sleep(3_000)
        // Pause in the middle: both tracks must lose the same stretch, and nothing of it may remain.
        r.pause()
        Thread.sleep(1_500)
        r.resume()
        Thread.sleep(2_000)
        val report = r.stop()
        val durations = HashMap<String, Long>()
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        for (i in 0 until ex.trackCount) {
            ex.selectTrack(i)
        }
        // Walk every sample: the last timestamp per track is the length that really plays.
        while (true) {
            val t = ex.sampleTrackIndex
            if (t < 0) break
            val kind = ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME)!!.substringBefore('/')
            durations[kind] = maxOf(durations[kind] ?: 0L, ex.sampleTime)
            ex.advance()
        }
        ex.release()
        val v = durations["video"] ?: 0L
        val a = durations["audio"] ?: 0L
        val gapMs = abs(v - a) / 1_000
        android.util.Log.i(
            "TrackAlignmentTest",
            "video=${v / 1000} ms audio=${a / 1000} ms gap=$gapMs ms report=$report",
        )
        assertTrue(
            "first frame came ${report.videoClockOffsetMs} ms after start, expected about 1000",
            (report.videoClockOffsetMs ?: 0) in 800..3_000,
        )
        assertTrue("audio and video lengths differ by $gapMs ms", gapMs < 100)
        // About 5 s of wall time after the first frame (6.5 s minus the 1 s delay), less the 1.5 s pause.
        val expectedMs = 5_000 - 1_500L - (report.videoClockOffsetMs ?: 1_000) + 1_000
        assertTrue("video is ${v / 1000} ms, expected about $expectedMs ms", abs(v / 1000 - expectedMs) < 600)
    }
}
