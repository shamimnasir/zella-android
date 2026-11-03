package com.zellahq.app

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.zellahq.app.recording.AudioSources
import com.zellahq.app.recording.CameraHub
import com.zellahq.app.recording.CameraVideoSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The camera version of the stop sequence loop. Every loop rebuilds the camera session twice (add the
 * encoder, remove it), which is where a camera pipeline hangs if it is going to.
 */
@RunWith(AndroidJUnit4::class)
class CameraStopLoopTest {
    @get:Rule val perms: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loops = (InstrumentationRegistry.getArguments().getString("loops") ?: "100").toInt()

    @After fun close() = CameraHub.close().let { }

    @Test fun cameraStartPauseStopManyTimes() {
        val info = CameraHub.openBlocking(context, front = false)
        assertNotNull("camera did not open", info)
        info!!
        android.util.Log.i("CameraStopLoopTest", "camera $info")
        val dir =
            File(context.cacheDir, "camloop").apply {
                deleteRecursively()
                mkdirs()
            }
        val t0 = System.nanoTime()
        repeat(loops) { n ->
            val f = File(dir, "cam-$n.mp4")
            val config =
                RecorderConfig(
                    info.recordSize.width,
                    info.recordSize.height,
                    rotationDegrees = 90,
                    videoRealtimeTimestamps = info.realtimeTimestamps,
                )
            val r = Recorder(f, config, CameraVideoSource(), mapOf("voice" to AudioSources.mic(context)))
            r.start()
            Thread.sleep(500)
            if (n % 2 == 0) {
                r.pause()
                Thread.sleep(150)
                r.resume()
                Thread.sleep(250)
            }
            val report = r.stop()
            assertEquals("loop $n drain timeouts", 0, report.drainTimeouts)
            // On the recorder's clock the first frame arrives within a second or so of Start. Without
            // the boot-time conversion this would be off by however long the phone has slept.
            val offset = report.videoClockOffsetMs ?: -1
            assertTrue("loop $n first frame at $offset ms", offset in 0..3_000)
            val ex = MediaExtractor().apply { setDataSource(f.path) }
            val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            ex.release()
            assertTrue(
                "loop $n tracks $mimes",
                mimes.any { it.startsWith("video/") } && mimes.any { it.startsWith("audio/") },
            )
            val rotation =
                MediaMetadataRetriever().use {
                    it.setDataSource(f.path)
                    it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                }
            assertEquals("loop $n rotation", "90", rotation)
            f.delete()
        }
        android.util.Log.i("CameraStopLoopTest", "PASSED $loops loops in ${(System.nanoTime() - t0) / 1_000_000} ms")
    }
}
