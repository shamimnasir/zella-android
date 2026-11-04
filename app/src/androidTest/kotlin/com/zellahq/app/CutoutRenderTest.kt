@file:Suppress("ktlint:standard:max-line-length")

package com.zellahq.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.cutout.BackgroundPainter
import com.zellahq.app.cutout.MatteBaker
import com.zellahq.app.cutout.MatteFile
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.editor.appComposition
import com.zellahq.core.EntitlementState
import com.zellahq.core.ExportDecision
import com.zellahq.core.ExportRequest
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.BackgroundChoice
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.setBackgroundEverywhere
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The gate for Phase 7's cutout: a real person, in real footage. Test clips cannot live in the repo
 * (tools/push-test-media.sh). Edge stability is measured as the iPhone's measuring tool does: the share
 * of pixels near the person's edge that change by more than a fifth between neighbouring mask frames.
 */
@RunWith(AndroidJUnit4::class)
class CutoutRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val media = context.getExternalFilesDir(null)!!
    private val dir = File(media, "evidence").apply { mkdirs() }

    private fun export(
        p: Project,
        name: String,
    ): File {
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit = (PremiumGate.authorize(ExportRequest(30.0, 720), gate, System.currentTimeMillis()) as ExportDecision.Allowed).permit
        val out = File(dir, name).apply { delete() }
        val o =
            Exporter(context, appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))))
                .export(p, permit, ExportSettings(shortSide = 720), out)
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

    private fun bake(f: File): File {
        val t0 = System.nanoTime()
        assertTrue(MatteBaker(context).bake(f))
        val out = MatteBaker.fileFor(context, f)
        Log.i("Cutout", "${f.name}: baked in ${(System.nanoTime() - t0) / 1_000_000} ms, ${out.length() / 1024} KB")
        return out
    }

    private fun stability(
        matte: File,
        from: Int = 1,
        until: Int = Int.MAX_VALUE,
    ): String {
        val r = MatteFile(matte)
        val a = ByteArray(r.width * r.height)
        val b = ByteArray(r.width * r.height)
        val shares = ArrayList<Double>()
        var person = 0L
        var seen = 0
        for (i in from until minOf(until, r.count)) {
            r.read(i - 1, a)
            r.read(i, b)
            var edge = 0
            var flips = 0
            for (k in a.indices) {
                val x = a[k].toInt() and 255
                val y = b[k].toInt() and 255
                if (x > 128) person++
                if (x in 26..229 || y in 26..229) {
                    edge++
                    if (kotlin.math.abs(x - y) > 51) flips++
                }
            }
            shares += if (edge == 0) 0.0 else 100.0 * flips / edge
            seen++
        }
        val n = r.count
        val covered = 100.0 * person / (maxOf(1, seen) * a.size)
        r.close()
        shares.sort()
        // The median between neighbouring frames: a cut between shots counts once, not as crawl.
        return "%d frames, person %.1f%%, edge pixels changed by over 20%%: median %.2f%%, worst tenth %.2f%%".format(
            n,
            covered,
            shares.getOrElse(shares.size / 2) { 0.0 },
            shares.getOrElse(shares.size * 9 / 10) { 0.0 },
        )
    }

    private fun project(
        f: File,
        seconds: Double,
        choice: BackgroundChoice?,
        aspect: Aspect = Aspect.Landscape,
    ) = Project(
        id = newId(),
        name = "cutout",
        createdAt = isoNow(),
        modifiedAt = isoNow(),
        aspect = aspect,
        clips = listOf(Clip(newId(), MediaRef.Absolute(f.toURI().toString()), seconds, trimEnd = seconds)),
    ).setBackgroundEverywhere(choice)

    private fun sheet(
        name: String,
        items: List<Pair<String, Bitmap>>,
    ) {
        val cols = 4
        val cw = 300
        val ch = (cw * items[0].second.height / items[0].second.width) + 26
        val s = Bitmap.createBitmap(cols * cw, ((items.size + cols - 1) / cols) * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(s)
        c.drawColor(Color.WHITE)
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 18f
            }
        items.forEachIndexed { i, (label, b) ->
            val x = (i % cols) * cw
            val y = (i / cols) * ch
            c.drawBitmap(Bitmap.createScaledBitmap(b, cw - 4, ch - 26, true), x.toFloat(), y.toFloat(), null)
            c.drawText(label, x + 4f, y + ch - 6f, p)
        }
        File(dir, name).outputStream().use { s.compress(Bitmap.CompressFormat.PNG, 95, it) }
    }

    @Test fun aRealPersonOnEveryKindOfBackground() {
        val f = File(media, "face-wide.mp4")
        assumeTrue("run tools/push-test-media.sh", f.isFile)
        bake(f)
        Log.i("Cutout", "still portrait: " + stability(MatteBaker.fileFor(context, f)))
        val choices =
            listOf(
                "cutout" to BackgroundChoice("cutout", colorHex = "#000000"),
                "green" to BackgroundChoice("color", colorHex = "#00B140"),
                "blur" to BackgroundChoice("blur", blurRadius = 18.0),
                "preset" to BackgroundChoice.REMOVED,
                "moving" to BackgroundChoice("preset", presetId = "vid.neon.violet"),
            )
        val items = ArrayList<Pair<String, Bitmap>>()
        items += "source" to frame(f, 2.0)
        choices.forEach { (name, c) ->
            val b = frame(export(project(f, 4.0, c), "cutout-$name.mp4"), 2.0)
            items += name to b
        }
        // On green: a background corner is green, and the middle of the face is not.
        val green = items[2].second
        val corner = green.getPixel(green.width - 40, 30)
        Log.i("Cutout", "green corner ${Integer.toHexString(corner)}")
        assertTrue("the far corner should be the new background", Color.green(corner) > 130 && Color.red(corner) < 60)
        sheet("cutout-backgrounds.png", items)
    }

    @Test fun realFootageHoldsSteady() {
        val f = File(media, "tos.mp4")
        assumeTrue("run tools/push-test-media.sh", f.isFile)
        val matte = bake(f)
        Log.i("Cutout", "tears of steel, whole clip: " + stability(matte))
        Log.i("Cutout", "tears of steel, three people talking, 3 to 6 s: " + stability(matte, 45, 90))
        val out = export(project(f, 11.0, BackgroundChoice("color", colorHex = "#00B140")), "cutout-tos.mp4")
        sheet(
            "cutout-tos.png",
            listOf(1.0, 3.0, 5.0, 7.0, 9.0, 10.5).flatMap {
                    t ->
                listOf("src $t" to frame(f, t), "out $t" to frame(out, t))
            },
        )
    }

    /** A still person under camera noise: anything that moves in the mask here is crawl, nothing else. */
    @Test fun stillPersonEdgeCrawl() {
        val f = File(media, "face-noise.mp4")
        assumeTrue("run tools/push-test-media.sh", f.isFile)
        listOf(0f, 0.5f, 0.75f, 0.9f).forEach { hold ->
            assertTrue(MatteBaker(context, hold).bake(f))
            Log.i("Cutout", "noise, hold $hold: " + stability(MatteBaker.fileFor(context, f, hold)))
        }
        val tos = File(media, "tos.mp4")
        listOf(0f, 0.75f).forEach { hold ->
            assertTrue(MatteBaker(context, hold).bake(tos))
            Log.i("Cutout", "people moving, hold $hold: " + stability(MatteBaker.fileFor(context, tos, hold), 45, 90))
        }
    }

    @Test fun everyPresetPaints() {
        val items = (BackgroundPainter.images + BackgroundPainter.videos).map { it.title to BackgroundPainter.swatch(it.id, 200, 120) }
        items.forEach { (name, b) ->
            val colours = HashSet<Int>()
            for (y in 0 until b.height step 10) for (x in 0 until b.width step 10) colours += b.getPixel(x, y)
            assertTrue("$name painted one flat colour", colours.size > 1)
        }
        val moving = BackgroundPainter.videos.first { it.id == "vid.neon.violet" }
        val a = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        BackgroundPainter.paint(moving.id, Canvas(a), 64, 64, 0.0)
        BackgroundPainter.paint(moving.id, Canvas(b), 64, 64, 1.0)
        assertTrue("a moving background must change", !a.sameAs(b))
        sheet("cutout-presets.png", items)
    }
}
