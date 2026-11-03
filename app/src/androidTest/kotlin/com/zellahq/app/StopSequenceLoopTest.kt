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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The stop sequence is the part that deadlocked on Mac. Run it many times, with pauses, and
 * require every file to open with both tracks. Change docs/STOP_SEQUENCE.md only after this passes.
 */
@RunWith(AndroidJUnit4::class)
class StopSequenceLoopTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loops = (InstrumentationRegistry.getArguments().getString("loops") ?: "100").toInt()

    @Test fun startPauseResumeStopManyTimes() {
        val dir =
            File(context.cacheDir, "loop").apply {
                deleteRecursively()
                mkdirs()
            }
        val started = System.nanoTime()
        repeat(loops) { n ->
            val f = File(dir, "loop-$n.mp4")
            val r =
                Recorder(f, RecorderConfig(720, 1280), PatternSource(30), mapOf("voice" to AudioSources.mic(context)))
            r.start()
            Thread.sleep(400)
            if (n % 2 == 0) {
                r.pause()
                Thread.sleep(150)
                r.resume()
                Thread.sleep(250)
            }
            val report = r.stop()
            assertEquals("loop $n drain timeouts", 0, report.drainTimeouts)
            val (video, audio) = tracks(f)
            assertTrue("loop $n has no video", video)
            assertTrue("loop $n has no audio", audio)
            f.delete()
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        android.util.Log.i("StopSequenceLoopTest", "PASSED $loops loops in $ms ms")
    }

    private fun tracks(f: File): Pair<Boolean, Boolean> {
        val ex = MediaExtractor()
        ex.setDataSource(f.path)
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        ex.release()
        return mimes.any { it.startsWith("video/") } to mimes.any { it.startsWith("audio/") }
    }
}
