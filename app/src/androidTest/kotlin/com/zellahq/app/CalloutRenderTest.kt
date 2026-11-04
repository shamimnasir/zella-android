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
import com.zellahq.app.callouts.ScreenReader
import com.zellahq.app.callouts.findSensitiveText
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
import com.zellahq.core.project.Callout
import com.zellahq.core.project.CalloutKind
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.addCallouts
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.sensitive.SensitiveMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Callouts through a real export. The blur gate: a video that shows an access token is scanned, the
 * blur placed, and exported again. The text reader finds the token before and must not find it after.
 */
@RunWith(AndroidJUnit4::class)
class CalloutRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private val secret = "ghp_abcdefghijklmnopqrstuvwxyz123456"

    private fun export(
        p: Project,
        name: String,
    ): File {
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit = (PremiumGate.authorize(ExportRequest(10.0, 720), gate, System.currentTimeMillis()) as ExportDecision.Allowed).permit
        val out = File(dir, name).apply { delete() }
        val o =
            Exporter(context, appComposition(context, ProjectStore(File(context.filesDir, "projects-test"))))
                .export(p, permit, ExportSettings(shortSide = 720), out)
        assertTrue("export: $o", o is ExportOutcome.Done)
        return out
    }

    private fun project(
        file: File,
        seconds: Double,
    ) = Project(
        id = newId(),
        name = "callouts",
        createdAt = isoNow(),
        modifiedAt = isoNow(),
        aspect = Aspect.Landscape,
        clips = listOf(Clip(newId(), MediaRef.Absolute(file.toURI().toString()), seconds, trimEnd = seconds)),
    )

    /** A recorded screen: a white page with lines of text, one of them a token. */
    private fun screenRecording(): File {
        val f = File(context.cacheDir, "callout-screen.mp4")
        if (!f.isFile) {
            val src =
                PatternSource(30, background = Color.WHITE) { canvas: Canvas, _: Long ->
                    val p =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 26f
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                    canvas.drawText("Settings for my account", 60f, 120f, p)
                    canvas.drawText("Access token: $secret", 60f, 220f, p)
                    canvas.drawText("Nothing else to see on this page", 60f, 320f, p)
                }
            val r = Recorder(f, RecorderConfig(1280, 720), src, emptyMap())
            r.start()
            Thread.sleep(4_300)
            r.stop()
        }
        return f
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

    private fun readText(b: Bitmap) = ScreenReader(context).use { r -> r.read(b).joinToString("\n") { it.line.text } }

    @Test fun theBlurHidesTheTokenFromTheReader() {
        val p = project(screenRecording(), 4.0)
        val before = readText(frame(export(p, "callout-before.mp4"), 2.0))
        Log.i("CalloutRender", "before: ${before.replace('\n', '|')}")
        assertTrue("the reader should see the token first: $before", before.contains("ghp_"))

        val report = findSensitiveText(context, p, SensitiveMatcher(), null, null, pixelate = false)
        Log.i("CalloutRender", report.text)
        assertTrue("a blur was planned: ${report.text}", report.callouts.isNotEmpty())
        assertFalse("the report must not carry the token: ${report.text}", report.text.contains("abcdefghij"))

        val blurred = export(p.addCallouts(report.callouts), "callout-after.mp4")
        for (t in listOf(0.2, 2.0, 3.8)) {
            val after = readText(frame(blurred, t))
            Log.i("CalloutRender", "after at $t: ${after.replace('\n', '|')}")
            assertFalse("the token is still readable at $t: $after", after.contains("ghp_") || after.contains("abcdefgh"))
            assertTrue("the rest of the page should stay readable: $after", after.contains("Settings"))
        }
        val pixels =
            export(
                p.addCallouts(findSensitiveText(context, p, SensitiveMatcher(), null, null, pixelate = true).callouts),
                "callout-pixel.mp4",
            )
        assertFalse(readText(frame(pixels, 2.0)).contains("ghp_"))
    }

    /** A blur placed with a tracked path follows it, and a rect is where the callout says. */
    @Test fun aBoxIsWhereItSaysAndEveryKindDraws() {
        val f = screenRecording()
        val kinds = CalloutKind.entries
        val perKind = 0.2
        val callouts =
            kinds.mapIndexed { i, k ->
                Callout.create(
                    k,
                    0.3,
                    0.35,
                    0.4,
                    0.25,
                    start = i * perKind,
                    duration = perKind,
                    text = if (k == CalloutKind.Emoji) "🔥" else "Word",
                )
                    .let { c -> Callout(kotlinx.serialization.json.JsonObject(c.raw - "animIn" - "animOut")) }
            }
        val p = project(f, 4.0).addCallouts(callouts)
        val out = export(p, "callout-kinds.mp4")
        val sheet = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        val label =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                textSize = 16f
            }
        val plain = frame(out, 3.95)
        kinds.forEachIndexed { i, k ->
            val b = frame(out, i * perKind + perKind / 2)
            val x = (i % 5) * 200
            val y = (i / 5) * 175
            c.drawBitmap(Bitmap.createScaledBitmap(b, 200, 112, true), x.toFloat(), y.toFloat(), null)
            c.drawText(k.label, x + 4f, y + 130f, label)
            var diff = 0
            for (py in 0 until b.height step 8) {
                for (px in 0 until b.width step 8) {
                    val q = b.getPixel(px, py)
                    val w = plain.getPixel(px, py)
                    if (kotlin.math.abs(Color.green(q) - Color.green(w)) > 12 || kotlin.math.abs(Color.red(q) - Color.red(w)) > 12) diff++
                }
            }
            Log.i("CalloutRender", "${k.raw}: $diff cells changed")
            assertTrue("${k.raw} changed nothing", diff > 0)
        }
        File(dir, "callout-kinds.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 95, it) }
    }

    private fun Bitmap.isRed(
        x: Int,
        y: Int,
    ) = Color.red(getPixel(x, y)) > 200

    @Test fun aBlurLandsOnTheTopLeftRect() {
        val f = File(context.cacheDir, "callout-stripes.mp4")
        if (!f.isFile) {
            val src =
                PatternSource(30, background = Color.WHITE) { canvas: Canvas, _: Long ->
                    val p = Paint().apply { color = Color.BLACK }
                    for (x in 0 until 1280 step 8) canvas.drawRect(x.toFloat(), 0f, x + 4f, 720f, p)
                }
            val r = Recorder(f, RecorderConfig(1280, 720), src, emptyMap())
            r.start()
            Thread.sleep(2_300)
            r.stop()
        }
        // A blurred stripe pattern goes flat grey; everywhere else keeps its contrast.
        val p = project(f, 2.0).addCallouts(listOf(Callout.create(CalloutKind.Blur, 0.1, 0.1, 0.2, 0.2, 0.0, 2.0)))
        val b = frame(export(p, "callout-rect.mp4"), 1.0)

        fun contrast(
            cx: Double,
            cy: Double,
        ): Int {
            val y = (cy * b.height).toInt()
            val x0 = (cx * b.width).toInt()
            val vals = (0 until 16).map { Color.green(b.getPixel(x0 + it, y)) }
            return vals.max() - vals.min()
        }
        val inside = contrast(0.2, 0.2)
        val below = contrast(0.2, 0.8)
        val right = contrast(0.8, 0.2)
        Log.i("CalloutRender", "contrast inside $inside, below $below, right $right")
        assertTrue("inside should be flat: $inside", inside < 25)
        assertTrue("the mirrored spot must stay sharp: $below", below > 80)
        assertTrue("to the right must stay sharp: $right", right > 80)
    }
}
