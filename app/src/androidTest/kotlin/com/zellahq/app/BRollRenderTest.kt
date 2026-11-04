@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.broll.BRollMaker
import com.zellahq.app.broll.StockNetwork
import com.zellahq.app.broll.StockProvider
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
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.CaptionCue
import com.zellahq.core.project.CaptionTrack
import com.zellahq.core.project.CaptionWord
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.startTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Word cards and stock clips, in a real export. The stock site is a stand-in that records what reached it. */
@RunWith(AndroidJUnit4::class)
class BRollRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }

    private fun video(color: Int): File {
        val f = File(context.cacheDir, "broll-$color.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(30, background = color) { _, _ -> }, emptyMap())
            r.start()
            Thread.sleep(8_300)
            r.stop()
        }
        return f
    }

    private fun project(): Project {
        val f = video(Color.RED)

        fun cue(
            t: Double,
            s: String,
        ) = CaptionCue(newId(), s.split(" ").mapIndexed { i, w -> CaptionWord(w, t + i * 0.2, t + i * 0.2 + 0.15) })
        return Project(
            id = newId(),
            name = "broll",
            createdAt = isoNow(),
            modifiedAt = isoNow(),
            aspect = Aspect.Vertical,
            clips = listOf(Clip(newId(), MediaRef.Absolute(f.toURI().toString()), 8.0, trimEnd = 8.0)),
            captions = CaptionTrack(listOf(cue(1.0, "the mountain was big"), cue(6.0, "then the ocean came"))),
        )
    }

    private fun export(
        p: Project,
        name: String,
    ): File {
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit = (PremiumGate.authorize(ExportRequest(60.0, 720), gate, System.currentTimeMillis()) as ExportDecision.Allowed).permit
        val out = File(dir, name).apply { delete() }
        val o =
            Exporter(
                context,
                appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))),
            ).export(p, permit, ExportSettings(shortSide = 720), out)
        assertTrue("export: $o", o is ExportOutcome.Done)
        return out
    }

    private fun frame(
        f: File,
        t: Double,
    ): Bitmap {
        val m = MediaMetadataRetriever()
        m.setDataSource(f.absolutePath)
        val b = m.getFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
        m.release()
        return b.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun isRed(b: Bitmap) = b.getPixel(b.width / 2, b.height / 10).let { Color.red(it) > 200 && Color.green(it) < 60 }

    @Test fun wordCardsLandOnTheirWords() {
        val p = project()
        val r = BRollMaker(context).cards(p, null)
        assertEquals(r.note, 2, r.placed)
        assertEquals(13.0, r.project.duration, 0.1)
        // Both cards come before this line's words: the second card sits exactly where the line begins.
        assertEquals(6.0 + 2.5 + 2.5, r.project.captions!!.cues[1].start, 1e-6)
        val out = export(r.project, "broll-cards.mp4")
        val cards = r.project.clips.filter { it.isBRoll }
        assertEquals(2, cards.size)
        assertTrue("before the first card it is the speaker", isRed(frame(out, 0.5)))
        cards.forEach { c ->
            val s = r.project.startTime(c.id)
            val b = frame(out, s + 1.2)
            assertFalse("a card shows at ${s + 1.2}", isRed(b))
            File(dir, "broll-card-${"%.0f".format(s)}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 90, it) }
        }
        assertTrue("the speaker comes back after", isRed(frame(out, r.project.startTime(cards[0].id) + 2.8)))
    }

    private class FakeSite(val clip: File) : StockNetwork {
        val asked = ArrayList<Pair<String, Map<String, String>>>()

        override fun text(
            url: String,
            headers: Map<String, String>,
        ): String {
            asked += url to headers
            return if (url.contains("pexels")) {
                """{"videos":[{"id":1,"duration":6,"width":720,"height":1280,"video_files":[{"file_type":"video/mp4","width":720,"height":1280,"link":"https://cdn.example/1.mp4"}]}]}"""
            } else {
                """{"hits":[{"id":1,"duration":6,"videos":{"medium":{"url":"https://cdn.example/1.mp4","width":720,"height":1280}}}]}"""
            }
        }

        override fun download(
            url: String,
            to: File,
        ) {
            clip.copyTo(to, overwrite = true)
        }
    }

    @Test fun stockSendsOnlyTheWordAndTheKey() {
        listOf(StockProvider.Pexels, StockProvider.Pixabay).forEach { provider ->
            val site = FakeSite(video(Color.BLUE))
            val p = project()
            val r = BRollMaker(context).stock(p, null, provider, "my-key", site)
            assertEquals(r.note, 2, r.placed)
            Log.i("BRoll", "${provider.raw}: ${site.asked.map { it.first }}")
            val all = site.asked.joinToString { it.first + it.second }
            assertEquals(listOf("mountain", "ocean"), site.asked.map { (u, _) -> Regex("[?&](query|q)=([^&]+)").find(u)!!.groupValues[2] })
            assertFalse("a file on the phone was named", all.contains("file:") || all.contains(".mp4") || all.contains("broll-"))
            assertFalse("a caption line was sent", all.contains("the+mountain") || all.contains("was"))
            site.asked.forEach { (u, _) -> assertTrue(u.startsWith("https://")) }
            if (provider == StockProvider.Pexels) {
                assertEquals(
                    "my-key",
                    site.asked[0].second["Authorization"],
                )
            } else {
                assertTrue(site.asked[0].first.contains("key=my-key"))
            }
            val out = export(r.project, "broll-${provider.raw}.mp4")
            val c = r.project.clips.first { it.isBRoll }
            val b = frame(out, r.project.startTime(c.id) + 1.0)
            assertTrue("the stock clip shows", Color.blue(b.getPixel(b.width / 2, b.height / 10)) > 200)
        }
    }
}
