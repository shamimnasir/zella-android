package com.zellahq.app

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.captions.CaptionEngine
import com.zellahq.app.editor.CompositionBuilder
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.editor.Storage
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
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captions end to end on the device, with the bundled model and real speech (macOS "Samantha" saying
 * a known sentence, 6.4 s). Logs the time taken, for BUILDLOG.
 */
@RunWith(AndroidJUnit4::class)
class CaptionEngineTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    private fun speech(): File {
        val f = File(context.cacheDir, "speech-en.m4a")
        if (!f.isFile) inst.context.assets.open("speech-en.m4a").use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun project(clip: Clip) = Project(id = newId(), name = "cap", createdAt = isoNow(), modifiedAt = isoNow(), clips = listOf(clip))

    private fun words(p: Project) = p.captions!!.cues.flatMap { it.words }.map { it.text.lowercase().trim('.', ',', '!', '?') }

    @Test fun englishSpeechBecomesTheRightWords() {
        val clip = Clip(newId(), MediaRef.Absolute(speech().toURI().toString()), 6.4)
        val out = CaptionEngine(context).run(project(clip), "en", false) {}
        assertTrue("outcome $out", out is CaptionEngine.Outcome.Done)
        out as CaptionEngine.Outcome.Done
        val w = words(out.project)
        Log.i("CaptionEngineTest", "en: ${out.words} words in ${out.tookMs} ms for 6.4 s of speech: $w")
        for (expected in listOf("captions", "phone", "voice", "device", "test", "engine")) {
            assertTrue("missing '$expected' in $w", w.any { it.contains(expected) })
        }
        val all = out.project.captions!!.cues.flatMap { it.words }
        assertTrue("times run forward", all.zipWithNext().all { (a, b) -> a.start <= b.start + 1e-6 })
        assertTrue("times inside the clip", all.all { it.start >= 0 && it.end <= 6.5 })
        assertEquals("en", out.project.captionLanguage)
    }

    @Test fun detectsEnglishAndMapsThroughSpeed() {
        val clip = Clip(newId(), MediaRef.Absolute(speech().toURI().toString()), 6.4, speed = 2.0)
        val out = CaptionEngine(context).run(project(clip), null, false) {}
        assertTrue("outcome $out", out is CaptionEngine.Outcome.Done)
        out as CaptionEngine.Outcome.Done
        Log.i("CaptionEngineTest", "detected ${out.language}, ${out.words} words, ${out.tookMs} ms")
        assertEquals("en", out.language)
        // Detect is remembered as detect, not as the language it found.
        assertEquals(null, out.project.captionLanguage)
        val last = out.project.captions!!.cues.last().end
        assertTrue("at double speed the words end by 3.3 s, got $last", last <= 3.3)
    }

    /** State set but never drawn is a known failure shape: the caption must change pixels in the export. */
    @Test fun captionsAreDrawnIntoTheExport() {
        val bg = File(context.cacheDir, "cap-bg.mp4")
        if (!bg.isFile) {
            val r = Recorder(bg, RecorderConfig(720, 1280), PatternSource(30, background = Color.BLUE), emptyMap())
            r.start()
            Thread.sleep(2_300)
            r.stop()
        }
        val base = project(Clip(newId(), MediaRef.Absolute(bg.toURI().toString()), 2.0, trimEnd = 2.0))
        val words = listOf(com.zellahq.core.project.CaptionWord("HELLO", 0.0, 2.0))
        val cue = com.zellahq.core.project.CaptionCue(newId(), words)
        val with = base.copy(captions = com.zellahq.core.captions.CaptionBuilder.track(listOf(cue), null))
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())

        fun export(
            p: Project,
            name: String,
        ): File {
            val permit =
                (
                    PremiumGate.authorize(
                        ExportRequest(p.duration, 720),
                        gate,
                        System.currentTimeMillis(),
                    ) as ExportDecision.Allowed
                ).permit
            val out = File(context.cacheDir, name)
            val o = Exporter(context, CompositionBuilder(Storage.store(context))).export(p, permit, ExportSettings(shortSide = 720), out)
            assertTrue("export $o", o is ExportOutcome.Done)
            return out
        }
        val a = export(base, "cap-without.mp4")
        val b = export(with, "cap-with.mp4")

        fun changedPixels(): Int {
            val ma = MediaMetadataRetriever().apply { setDataSource(a.path) }
            val mb = MediaMetadataRetriever().apply { setDataSource(b.path) }
            val fa = ma.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            val fb = mb.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            ma.release()
            mb.release()
            // Count changed pixels in the caption band (around 72% down the frame).
            var n = 0
            val y0 = (fa.height * 0.62).toInt()
            val y1 = (fa.height * 0.76).toInt()
            for (y in y0 until y1 step 4) for (x in 0 until fa.width step 4) {
                val p = fa.getPixel(x, y)
                val q = fb.getPixel(x, y)
                val dr = Math.abs(Color.red(p) - Color.red(q))
                val d = dr + Math.abs(Color.green(p) - Color.green(q)) + Math.abs(Color.blue(p) - Color.blue(q))
                if (d > 90) n++
            }
            return n
        }
        val n = changedPixels()
        Log.i("CaptionEngineTest", "caption band pixels changed: $n")
        assertTrue("captions did not change the frame ($n pixels)", n > 200)
    }
}
