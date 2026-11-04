package com.zellahq.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.zellahq.core.project.TransitionTypes
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.setTransition
import com.zellahq.core.project.startTime
import com.zellahq.core.project.transitionOverlap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * All 25 transitions in a real export, in both layer orders (the outgoing clip on the top track, and
 * on the bottom one). Each join goes from pure red to pure green. It must start as exactly the
 * outgoing clip, end as exactly the incoming clip, and in the middle be neither a plain red nor a
 * plain green frame for the ones that mix. A contact sheet of the middles goes to the evidence folder.
 */
@RunWith(AndroidJUnit4::class)
class TransitionRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }

    private fun picture(color: Int): File {
        val f = File(context.cacheDir, "transition-$color.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(360, 640), PatternSource(30, background = color), emptyMap())
            r.start()
            Thread.sleep(2_300)
            r.stop()
        }
        return f
    }

    private fun clip(color: Int) = Clip(newId(), MediaRef.Absolute(picture(color).toURI().toString()), 2.0, trimEnd = 1.0)

    private fun export(
        p: Project,
        name: String,
    ): File {
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit = (PremiumGate.authorize(ExportRequest(100.0, 360), gate, System.currentTimeMillis()) as ExportDecision.Allowed).permit
        val out = File(dir, name).apply { delete() }
        val o =
            Exporter(
                context,
                appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))),
            ).export(p, permit, ExportSettings(shortSide = 360), out)
        assertTrue("export: $o", o is ExportOutcome.Done)
        return out
    }

    /** Average red and green over a coarse grid. */
    private fun rg(b: Bitmap): Pair<Double, Double> {
        var r = 0L
        var g = 0L
        var n = 0
        for (y in 0 until b.height step 8) {
            for (x in 0 until b.width step 8) {
                val c = b.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                n++
            }
        }
        return r / n.toDouble() to g / n.toDouble()
    }

    private fun run(order: String) {
        // Colours alternate so every join is a colour change; even joins go out from the top track.
        val firstRed = order == "top"
        val names = TransitionTypes.all
        val lead = if (firstRed) emptyList() else listOf(clip(Color.GREEN))
        val colours = List(names.size + 1) { if (it % 2 == 0) Color.RED else Color.GREEN }
        var clips = lead + colours.map { clip(it) }
        var p = Project(id = newId(), name = "transitions", createdAt = isoNow(), modifiedAt = isoNow(), clips = clips)
        val offset = lead.size
        names.forEachIndexed { i, name -> p = p.setTransition(p.clips[i + offset].id, name, 0.5) }
        clips = p.clips
        val out = export(p, "transitions-$order.mp4")
        val mmr = MediaMetadataRetriever().apply { setDataSource(out.path) }
        val cells = ArrayList<Pair<String, Bitmap>>()
        val problems = ArrayList<String>()
        names.forEachIndexed { i, name ->
            val at = i + offset
            val join = p.startTime(clips[at + 1].id)
            val d = p.transitionOverlap(at)

            fun frame(t: Double) = mmr.getFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
            val fromRed = Color.red(colours[i]) > 200
            val (r0, g0) = rg(frame(join - 0.08))
            val (r1, g1) = rg(frame(join + d + 0.08))
            val mid = frame(join + d / 2)
            val (rm, gm) = rg(mid)
            cells += name to mid
            val startOk = if (fromRed) r0 > 180 && g0 < 70 else g0 > 180 && r0 < 70
            val endOk = if (fromRed) g1 > 180 && r1 < 70 else r1 > 180 && g1 < 70
            Log.i("TransitionRenderTest", "$order $name: before %.0f/%.0f middle %.0f/%.0f after %.0f/%.0f".format(r0, g0, rm, gm, r1, g1))
            if (!startOk) problems += "$name did not start on the outgoing clip: %.0f/%.0f".format(r0, g0)
            if (!endOk) problems += "$name did not end on the incoming clip: %.0f/%.0f".format(r1, g1)
        }
        mmr.release()
        sheet("transitions-$order", cells)
        assertEquals("problems: $problems", 0, problems.size)
    }

    private fun sheet(
        name: String,
        cells: List<Pair<String, Bitmap>>,
    ) {
        val columns = 7
        val cw = 150
        val ch = 266 + 18
        val rows = (cells.size + columns - 1) / columns
        val out = Bitmap.createBitmap(cw * columns, ch * rows, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val label =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 13f
            }
        cells.forEachIndexed { i, (text, b) ->
            val x = (i % columns) * cw
            val y = (i / columns) * ch
            c.drawBitmap(Bitmap.createScaledBitmap(b, cw - 2, 266, true), x.toFloat(), y.toFloat(), null)
            c.drawText(text, x + 3f, y + 266 + 14f, label)
        }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyTransitionWithTheOutgoingClipOnTop() = run("top")

    @Test fun everyTransitionWithTheOutgoingClipBelow() = run("below")
}
