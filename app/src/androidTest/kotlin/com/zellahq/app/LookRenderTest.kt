package com.zellahq.app

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.editor.Storage
import com.zellahq.app.looks.Looks
import com.zellahq.app.recording.PatternSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import com.zellahq.core.EntitlementState
import com.zellahq.core.ExportDecision
import com.zellahq.core.ExportRequest
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import com.zellahq.core.looks.ColorMath
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Project
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Colour really reaches the exported pixels, in the right channel order. */
@RunWith(AndroidJUnit4::class)
class LookRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun source(color: Int): File {
        val f = File(context.cacheDir, "look-${Integer.toHexString(color)}.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(30, background = color), emptyMap())
            r.start()
            Thread.sleep(1_500)
            r.stop()
        }
        return f
    }

    private fun exportCorner(clip: Clip): Int {
        val p = Project(id = newId(), name = "look", createdAt = isoNow(), modifiedAt = isoNow(), clips = listOf(clip))
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit =
            (
                PremiumGate.authorize(
                    ExportRequest(p.duration, 720),
                    gate,
                    System.currentTimeMillis(),
                ) as ExportDecision.Allowed
            ).permit
        val builder =
            com.zellahq.app.editor.appComposition(context, Storage.store(context)).apply {
                clipLook = {
                        c,
                        s,
                    ->
                    Looks.effects(context, c, s.width, s.height)
                }
            }
        val out = File(context.cacheDir, "look-out.mp4")
        val o = Exporter(context, builder).export(p, permit, ExportSettings(shortSide = 720), out)
        assertTrue("export $o", o is ExportOutcome.Done)
        val m = MediaMetadataRetriever().apply { setDataSource(out.path) }
        val px = m.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!.getPixel(20, 20)
        m.release()
        return px
    }

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    @Test fun anInvertCubeTurnsBlueIntoYellow() {
        val cube =
            buildString {
                appendLine("LUT_3D_SIZE 2")
                for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("${1 - r} ${1 - g} ${1 - b}")
            }
        val clip =
            Looks.importCube(
                context,
                Clip(newId(), MediaRef.Absolute(source(Color.BLUE).toURI().toString()), 1.5, trimEnd = 1.5),
                "invert",
                cube,
            )!!
        val px = exportCorner(clip)
        Log.i("LookRenderTest", "invert of blue: ${hex(px)}")
        assertTrue("expected yellow, got ${hex(px)}", Color.red(px) > 200 && Color.green(px) > 200 && Color.blue(px) < 60)
    }

    @Test fun aCatalogueLookLandsWhereItsLutSays() {
        val src = Color.rgb(200, 120, 60)
        val look = Looks.byName(context, "Teal & Orange") ?: Looks.all(context)[1]
        val clip = Looks.applyLook(Clip(newId(), MediaRef.Absolute(source(src).toURI().toString()), 1.5, trimEnd = 1.5), look, 1f)
        val px = exportCorner(clip)
        // What the plain clip exports as, then what the look's LUT says that colour becomes.
        val plain = exportCorner(Clip(newId(), MediaRef.Absolute(source(src).toURI().toString()), 1.5, trimEnd = 1.5))
        val predicted = Looks.lut(context, look).apply(Color.red(plain) / 255f, Color.green(plain) / 255f, Color.blue(plain) / 255f)
        val got = floatArrayOf(Color.red(px) / 255f, Color.green(px) / 255f, Color.blue(px) / 255f)
        val d = ColorMath.deltaE(ColorMath.lab(predicted), ColorMath.lab(got))
        Log.i("LookRenderTest", "${look.name}: plain ${hex(plain)} graded ${hex(px)} predicted ΔE $d")
        assertTrue("${look.name} exported ΔE %.1f from its LUT".format(d), d < 4.0)
    }
}
