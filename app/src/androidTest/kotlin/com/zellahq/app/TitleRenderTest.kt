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
import com.zellahq.app.titles.TitleLooks
import com.zellahq.app.titles.TitleRenderer
import com.zellahq.core.EntitlementState
import com.zellahq.core.ExportDecision
import com.zellahq.core.ExportRequest
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import com.zellahq.core.motion.TextAnimationSpec
import com.zellahq.core.motion.TextMotionCatalog
import com.zellahq.core.motion.TextMotionPhase
import com.zellahq.core.motion.TextMotionPhaseKind
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.newTitle
import com.zellahq.core.project.position
import com.zellahq.core.project.withField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Text through the real renderer: all 24 styles and all 85 text animations draw, in Latin and in
 * Bangla, a title settles to exactly the pixels of the same title with no animation, and titles come
 * out of a real export in the right place at the right time. Writes contact sheets to look at.
 */
@RunWith(AndroidJUnit4::class)
class TitleRenderTest {
    private companion object {
        val ENTRANCES =
            listOf(
                "fade", "pop", "scaleIn", "slideUp", "slideDown", "slideLeft", "slideRight",
                "dropIn", "wipe", "typewriter", "bounce", "glitch", "blurIn",
            )
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private val w = 540
    private val h = 300

    private fun frame(draw: (Canvas) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(46, 54, 70))
        draw(c)
        return b
    }

    private fun different(
        a: Bitmap,
        b: Bitmap,
    ): Int {
        var n = 0
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    private fun sheet(
        name: String,
        cells: List<Pair<String, Bitmap>>,
        columns: Int = 4,
    ) {
        val rows = (cells.size + columns - 1) / columns
        val cw = w / 2
        val ch = h / 2 + 18
        val out = Bitmap.createBitmap(cw * columns, ch * rows, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val label =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 13f
            }
        cells.forEachIndexed { i, (text, bmp) ->
            val x = (i % columns) * cw
            val y = (i / columns) * ch
            c.drawBitmap(Bitmap.createScaledBitmap(bmp, cw - 2, h / 2, true), x.toFloat(), y.toFloat(), null)
            c.drawText(text, x + 4f, y + h / 2 + 14f, label)
        }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyStyleDraws() {
        val renderer = TitleRenderer(context, w, h)
        val blank = frame {}
        val cells = ArrayList<Pair<String, Bitmap>>()
        for (text in listOf("Your best video yet", "তোমার সেরা ভিডিও")) {
            TitleLooks.all(context).forEach { look ->
                val t = look.title(text, 0.0, 6.0)
                val b = frame { renderer.draw(t, it, 3.0) }
                val changed = different(blank, b)
                assertTrue("${look.label} on \"$text\" drew nothing", changed > 50)
                if (text.startsWith("Your")) {
                    cells += look.label to b
                } else if (look.label == "Clean Bold" || look.label == "Gold Title" || look.label == "Keyword Punch") {
                    cells += "${look.label} (bn)" to b
                }
            }
        }
        sheet("title-styles", cells)
        assertEquals(24, TitleLooks.all(context).size)
    }

    @Test fun everyEntranceStartsApartAndSettles() {
        val renderer = TitleRenderer(context, w, h)
        val blank = frame {}
        val rest = newTitle("Entrance", 0.0, 6.0)
        val settled = frame { renderer.draw(rest.withField("entrance", kotlinx.serialization.json.JsonPrimitive("none")), it, 3.0) }
        for (entrance in ENTRANCES) {
            val t = rest.withField("entrance", kotlinx.serialization.json.JsonPrimitive(entrance))
            val during = frame { renderer.draw(t, it, 0.2) }
            val after = frame { renderer.draw(t, it, 3.0) }
            assertTrue("$entrance did not move at 0.2 s", different(during, settled) > 20)
            assertEquals("$entrance must settle on the plain title", 0, different(after, settled))
            assertTrue("$entrance drew nothing", different(blank, after) > 50)
        }
    }

    @Test fun every85AnimationsDrawsAndSettlesExactly() {
        val renderer = TitleRenderer(context, w, h)
        val blank = frame {}
        val base = newTitle("Move me now", 0.0, 6.0)
        val plain = base.withField("entrance", kotlinx.serialization.json.JsonPrimitive("none"))
        val settled = frame { renderer.draw(plain, it, 3.0) }
        val cells = ArrayList<Pair<String, Bitmap>>()
        var phases = 0
        var still = ArrayList<String>()
        for (p in TextMotionCatalog.all) {
            val specs =
                buildList {
                    if (p.supports(TextMotionPhaseKind.In)) add("in" to TextAnimationSpec(inPhase = TextMotionPhase(p.id)))
                    if (p.supports(TextMotionPhaseKind.Out)) add("out" to TextAnimationSpec(outPhase = TextMotionPhase(p.id)))
                    if (p.supports(TextMotionPhaseKind.Loop)) add("loop" to TextAnimationSpec(loop = TextMotionPhase(p.id)))
                }
            for ((kind, spec) in specs) {
                val t = plain.withField("textAnimation", spec.toJson())
                val times =
                    when (kind) {
                        "in" -> listOf(0.03, 0.15, 0.35, 0.6)
                        "out" -> listOf(5.4, 5.65, 5.85, 5.97)
                        // Some loops only fire in short bursts, so look often.
                        else -> List(40) { 0.8 + it * 0.1 }
                    }
                val shots = times.map { at -> frame { renderer.draw(t, it, at) } }
                val moved = shots.maxOf { different(it, settled) }
                if (moved < 5) still += "${p.id} $kind"
                phases++
                if (kind == specs.first().first) cells += p.name to shots[1]
                if (kind != "loop") {
                    val end = frame { renderer.draw(t, it, 3.0) }
                    assertEquals("${p.id} $kind must settle on the plain title", 0, different(end, settled))
                }
                assertTrue("${p.id} $kind drew nothing at all", shots.any { different(blank, it) > 0 } || kind == "in")
            }
        }
        sheet("title-motion", cells, columns = 6)
        Log.i("TitleRenderTest", "drew $phases phases of ${TextMotionCatalog.all.size} presets; no movement seen in: $still")
        assertTrue("these never moved: $still", still.isEmpty())
    }

    /** The contact sheet caught this and the pixel counts did not: blur drew faint dark boxes instead of soft text. */
    @Test fun blurKeepsTheColourAndTheLight() {
        val src = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        Canvas(src).drawColor(Color.WHITE)
        val out = com.zellahq.app.titles.BoxBlur.blurred(src, 6f, 16)
        var alpha = 0L
        var darkest = 255
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val c = out.getPixel(x, y)
                alpha += Color.alpha(c)
                if (Color.alpha(c) > 40) darkest = minOf(darkest, Color.red(c), Color.green(c), Color.blue(c))
            }
        }
        val expected = 60L * 40 * 255
        Log.i("TitleRenderTest", "blur: light kept ${alpha * 100 / expected}%, darkest visible channel $darkest")
        assertTrue("blur lost light: $alpha of $expected", kotlin.math.abs(alpha - expected) < expected / 20)
        assertTrue("white blurred to a dark edge: $darkest", darkest > 230)
        assertTrue("the middle stays solid", Color.alpha(out.getPixel(out.width / 2, out.height / 2)) > 240)
        assertTrue("the edge is soft", Color.alpha(out.getPixel(14, out.height / 2)) in 30..225)

        // And a whole preset: mid focus pull is bright soft text, not nothing.
        val renderer = TitleRenderer(context, w, h)
        val t =
            newTitle("Move me now", 0.0, 6.0).withField("entrance", kotlinx.serialization.json.JsonPrimitive("none"))
                .withField("textAnimation", TextAnimationSpec(inPhase = TextMotionPhase("blur.focuspull")).toJson())
        val shot = frame { renderer.draw(t, it, 0.15) }
        var brightest = 0
        for (y in 0 until shot.height) for (x in 0 until shot.width) brightest = maxOf(brightest, Color.red(shot.getPixel(x, y)))
        Log.i("TitleRenderTest", "focus pull at 0.15 s: brightest red $brightest (background 46)")
        assertTrue("focus pull is too dim: $brightest", brightest > 120)
    }

    @Test fun banglaMovesByWholeClusters() {
        val renderer = TitleRenderer(context, 1080, 1920)
        val units = renderer.unitsOf(newTitle("কি সুন্দর", 0.0, 3.0))
        Log.i("TitleRenderTest", "Bangla units: ${units.map { it.text }}")
        assertTrue(units.isNotEmpty())
        // A cluster holds its vowel sign: no unit is a vowel sign on its own.
        val signs = 'া'..'ৌ'
        assertTrue("a vowel sign was split off: ${units.map { it.text }}", units.none { it.text.length == 1 && it.text[0] in signs })
    }

    @Test fun titlesComeOutOfARealExport() {
        val f = File(context.cacheDir, "title-picture.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(30, background = Color.DKGRAY), emptyMap())
            r.start()
            Thread.sleep(6_400)
            r.stop()
        }
        val look = TitleLooks.all(context).first { it.label == "Hook Yellow" }
        val title =
            look.title("Watch this", 2.0, 2.0)
                .withField("position", position(0.5, 0.5))
                .withField("textAnimation", TextAnimationSpec(inPhase = TextMotionPhase("rise.letters")).toJson())
        val p =
            Project(
                id = newId(),
                name = "titles",
                createdAt = isoNow(),
                modifiedAt = isoNow(),
                clips = listOf(Clip(newId(), MediaRef.Absolute(f.toURI().toString()), 6.0, trimEnd = 6.0)),
                titles = listOf(title),
            )
        val store = ProjectStore(File(context.filesDir, "projects-test"))
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit = (PremiumGate.authorize(ExportRequest(6.0, 720), gate, System.currentTimeMillis()) as ExportDecision.Allowed).permit
        val out = File(dir, "title-export.mp4").apply { delete() }
        val o = Exporter(context, appComposition(context, store)).export(p, permit, ExportSettings(shortSide = 720), out)
        assertTrue("export: $o", o is ExportOutcome.Done)
        val mmr = MediaMetadataRetriever().apply { setDataSource(out.path) }

        fun yellowAt(t: Double): Int {
            val b = mmr.getFrameAtTime((t * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
            var n = 0
            for (y in b.height * 2 / 5 until b.height * 3 / 5 step 3) {
                for (x in 0 until b.width step 3) {
                    val c = b.getPixel(x, y)
                    if (Color.red(c) > 200 && Color.green(c) > 170 && Color.blue(c) < 90) n++
                }
            }
            return n
        }
        val before = yellowAt(1.5)
        val rising = yellowAt(2.15)
        val settled = yellowAt(3.5)
        val after = yellowAt(4.5)
        mmr.getFrameAtTime(3_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!.let { b ->
            File(dir, "title-export-frame.png").outputStream().use { s -> b.compress(Bitmap.CompressFormat.PNG, 100, s) }
        }
        mmr.release()
        Log.i("TitleRenderTest", "yellow pixels: before $before, rising $rising, settled $settled, after $after")
        assertEquals("no title before its start", 0, before)
        assertTrue("title is on screen once settled", settled > 200)
        assertTrue("still arriving at 2.15 s", rising < settled)
        assertEquals("no title after its end", 0, after)
    }
}
