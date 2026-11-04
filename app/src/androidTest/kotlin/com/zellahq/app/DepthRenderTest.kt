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
import com.zellahq.app.depth.Stills
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
import com.zellahq.core.project.Clip
import com.zellahq.core.project.ClipTransform
import com.zellahq.core.project.Interp
import com.zellahq.core.project.KeyProperty
import com.zellahq.core.project.MaskRegion
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.Overlay
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.SpecialEffects
import com.zellahq.core.project.addKeyframe
import com.zellahq.core.project.insertFreeze
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.withMask
import com.zellahq.core.project.withOpacity
import com.zellahq.core.project.withOverlays
import com.zellahq.core.project.withSpecial
import com.zellahq.core.project.withTransform
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Place, keyframes, masks, effects, picture-in-picture and freeze frame, each read back from a real export. */
@RunWith(AndroidJUnit4::class)
class DepthRenderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }

    /** A white screen with a red square at its centre, a third as wide as the screen. */
    private fun target(): File {
        val f = File(context.cacheDir, "depth-target.mp4")
        if (!f.isFile) {
            val src =
                PatternSource(30, background = Color.WHITE) { c: Canvas, _: Long ->
                    val p = Paint().apply { color = Color.RED }
                    val w = c.width / 6f
                    c.drawRect(c.width / 2f - w, c.height / 2f - w, c.width / 2f + w, c.height / 2f + w, p)
                }
            val r = Recorder(f, RecorderConfig(720, 720), src, emptyMap())
            r.start()
            Thread.sleep(4_300)
            r.stop()
        }
        return f
    }

    private fun solid(color: Int): File {
        val f = File(context.cacheDir, "depth-solid-$color.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(720, 720), PatternSource(30, background = color) { _, _ -> }, emptyMap())
            r.start()
            Thread.sleep(4_300)
            r.stop()
        }
        return f
    }

    private fun clip() = Clip(newId(), MediaRef.Absolute(target().toURI().toString()), 4.0, trimEnd = 4.0)

    private fun project(c: Clip) =
        Project(
            id = newId(),
            name = "depth",
            createdAt = isoNow(),
            modifiedAt = isoNow(),
            aspect = Aspect.Square,
            clips = listOf(c),
        )

    private fun export(
        p: Project,
        name: String,
    ): File {
        // Long past Pro Week, on the free plan: depth tools must still export.
        val state = EntitlementState(Plan.Free, installEpochMs = 0)
        val decision = PremiumGate.authorize(ExportRequest(10.0, 720), state, System.currentTimeMillis())
        val permit = (decision as ExportDecision.Allowed).permit
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

    private fun Bitmap.at(
        x: Double,
        y: Double,
    ) = getPixel((x * width).toInt().coerceIn(0, width - 1), (y * height).toInt().coerceIn(0, height - 1))

    private fun isRed(c: Int) = Color.red(c) > 200 && Color.green(c) < 70

    private fun isWhite(c: Int) = Color.red(c) > 220 && Color.green(c) > 220 && Color.blue(c) > 220

    private fun isBlack(c: Int) = Color.red(c) < 30 && Color.green(c) < 30 && Color.blue(c) < 30

    @Test fun aMovedClipIsWhereItSays() {
        // Down and to the right by a quarter: the square's centre lands at 0.75, 0.75.
        val b = frame(export(project(clip().withTransform(ClipTransform(0.25, 0.25))), "depth-move.mp4"), 2.0)
        assertTrue("moved square", isRed(b.at(0.75, 0.75)))
        assertTrue("it left the middle", !isRed(b.at(0.45, 0.45)))
        assertTrue("uncovered space is black", isBlack(b.at(0.05, 0.05)))
    }

    @Test fun sizeAndFadeWork() {
        val big = frame(export(project(clip().withTransform(ClipTransform(scale = 2.0))), "depth-big.mp4"), 2.0)
        assertTrue("twice the size reaches 0.7", isRed(big.at(0.7, 0.5)))
        val half = frame(export(project(clip().withOpacity(0.5)), "depth-half.mp4"), 2.0)
        val g = Color.green(half.at(0.05, 0.05))
        Log.i("Depth", "half opacity white reads $g")
        assertTrue("half see-through over black: $g", g in 100..150)
    }

    @Test fun keyframesGlideAndFollowTheirCurve() {
        val c = clip().addKeyframe(KeyProperty.PosX, 1.0, 0.0, Interp.Linear).addKeyframe(KeyProperty.PosX, 3.0, 0.3, Interp.Linear)
        val out = export(project(c), "depth-keys.mp4")

        fun centreAt(t: Double): Double {
            val b = frame(out, t)
            val y = b.height / 2
            val xs = (0 until b.width).filter { isRed(b.getPixel(it, y)) }
            return xs.average() / b.width
        }
        val start = centreAt(0.5)
        val mid = centreAt(2.0)
        val end = centreAt(3.5)
        Log.i("Depth", "square centre: before $start, middle $mid, after $end")
        assertEquals("holds before the first point", 0.5, start, 0.02)
        assertEquals("halfway at the middle", 0.65, mid, 0.03)
        assertEquals("holds after the last", 0.8, end, 0.02)
        val fade =
            export(
                project(
                    clip().addKeyframe(
                        KeyProperty.Opacity,
                        0.5,
                        1.0,
                        Interp.Linear,
                    ).addKeyframe(KeyProperty.Opacity, 3.5, 0.0, Interp.Linear),
                ),
                "depth-fade.mp4",
            )
        assertTrue("faded to black", isBlack(frame(fade, 3.8).at(0.05, 0.05)))
        assertTrue("still bright early", isWhite(frame(fade, 0.2).at(0.05, 0.05)))
    }

    @Test fun aMaskGradesOnlyInsideIt() {
        val graded =
            clip().let {
                com.zellahq.app.looks.Looks.applyLook(
                    it,
                    com.zellahq.app.looks.Looks.all(context).first {
                            l ->
                        l.name == "Film Noir"
                    },
                    1f,
                )
            }
        val p = project(graded.withMask(MaskRegion("ellipse", 0.5, 0.5, 0.5, 0.5, 0.05)))
        val b = frame(export(p, "depth-mask.mp4"), 2.0)
        val inside = b.at(0.5, 0.5)
        val outsideInSquare = b.at(0.5, 0.5).let { b.at(0.05, 0.05) }
        Log.i("Depth", "mask: inside ${Integer.toHexString(inside)}, outside ${Integer.toHexString(outsideInSquare)}")
        assertTrue(
            "inside the mask the red square has gone grey: ${Integer.toHexString(inside)}",
            abs(Color.red(inside) - Color.green(inside)) < 40,
        )
        assertTrue("outside stays white", isWhite(outsideInSquare))
        // The same area flipped: the middle keeps its colour.
        val flipped = frame(export(project(graded.withMask(MaskRegion("ellipse", 0.5, 0.5, 0.5, 0.5, 0.05, true))), "depth-mask2.mp4"), 2.0)
        assertTrue("flipped: the square stays red", isRed(flipped.at(0.5, 0.5)))
    }

    @Test fun effectsChangeThePicture() {
        val cases =
            listOf(
                "mono" to SpecialEffects(blackAndWhite = true),
                "vignette" to SpecialEffects(vignette = 1.0),
                "grain" to SpecialEffects(grain = 1.0),
                "glow" to SpecialEffects(glow = 1.0),
                "pixels" to SpecialEffects(pixelate = 1.0),
                "fringe" to SpecialEffects(chromatic = 1.0),
                "blur" to SpecialEffects(blur = 30.0),
                "bars" to SpecialEffects(letterbox = true),
            )
        val plain = frame(export(project(clip()), "depth-plain.mp4"), 2.0)
        val sheet = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        cases.forEachIndexed { i, (name, s) ->
            val b = frame(export(project(clip().withSpecial(s)), "depth-$name.mp4"), 2.0)
            var cells = 0
            for (y in 0 until b.height step 8) for (x in 0 until b.width step 8) {
                val p = b.getPixel(x, y)
                val q = plain.getPixel(x, y)
                if (abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q)) > 12) cells++
            }
            Log.i("Depth", "$name: $cells cells changed")
            assertTrue("$name changed nothing", cells > 0)
            c.drawBitmap(Bitmap.createScaledBitmap(b, 150, 150, true), i * 150f, 0f, null)
        }
        File(dir, "depth-effects.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    @Test fun pictureInPictureShowsWhereItSaysAndOnlyWhile() {
        val blue = solid(Color.BLUE)
        val o =
            Overlay
                .create(MediaRef.Absolute(blue.toURI().toString()), 4.0, 1.0, 1.0)
                .with(
                    "duration" to JsonPrimitive(2.0),
                    "position" to com.zellahq.core.project.position(0.75, 0.25),
                    "scale" to JsonPrimitive(0.3),
                )
        val out = export(project(clip()).withOverlays(listOf(o)), "depth-pip.mp4")
        val during = frame(out, 2.0)
        assertTrue("the overlay shows", Color.blue(during.at(0.75, 0.25)) > 200 && Color.red(during.at(0.75, 0.25)) < 60)
        assertTrue("outside its box the main video shows", isWhite(during.at(0.5, 0.75)))
        assertTrue("the main video is under it", isRed(during.at(0.5, 0.5)))
        assertTrue("not before it starts", isWhite(frame(out, 0.5).at(0.75, 0.25)))
        assertTrue("not after it ends", isWhite(frame(out, 3.5).at(0.75, 0.25)))
        val round = frame(export(project(clip()).withOverlays(listOf(o.with("style" to JsonPrimitive("circle")))), "depth-pip2.mp4"), 2.0)
        assertTrue("a circle leaves its corners", isWhite(round.at(0.75 - 0.145, 0.25 - 0.145)))
        assertTrue("a circle fills its middle", Color.blue(round.at(0.75, 0.25)) > 200)
    }

    @Test fun aFreezeFrameHolds() {
        val moving = File(context.cacheDir, "depth-moving.mp4")
        if (!moving.isFile) {
            val src =
                PatternSource(30, background = Color.WHITE) { c: Canvas, n: Long ->
                    val p = Paint().apply { color = Color.RED }
                    val x = (n * 4 % c.width).toFloat()
                    c.drawRect(x, 0f, x + 40f, c.height.toFloat(), p)
                }
            val r = Recorder(moving, RecorderConfig(720, 720), src, emptyMap())
            r.start()
            Thread.sleep(4_300)
            r.stop()
        }
        val c = Clip(newId(), MediaRef.Absolute(moving.toURI().toString()), 4.0, trimEnd = 4.0)
        val p = project(c)
        val f = Stills.freeze(context, p, 2.0)!!
        val out = export(p.insertFreeze(f.toURI().toString(), 2.0, Stills.FREEZE_SECONDS), "depth-freeze.mp4")

        fun bar(t: Double): Double {
            val b = frame(out, t)
            return (0 until b.width).filter { isRed(b.getPixel(it, b.height / 2)) }.average()
        }
        val a = bar(2.2)
        val z = bar(3.8)
        Log.i("Depth", "freeze: bar at $a then $z, before ${bar(1.0)}, after ${bar(5.0)}")
        assertEquals("it holds still", a, z, 3.0)
        assertTrue("before the freeze it was elsewhere", abs(bar(1.0) - a) > 30)
        assertTrue("after the freeze it moves again", abs(bar(5.0) - a) > 30)
    }

    @Suppress("DEPRECATION")
    @Test
    fun aGifAndASoundOnlyFileComeFromTheVideo() {
        val v = export(project(clip()), "depth-format.mp4")
        val gif = File(dir, "depth-format.gif").apply { delete() }
        assertTrue(com.zellahq.app.export.Formats.gif(v, gif))
        val bytes = gif.readBytes()
        assertEquals("GIF89a", String(bytes, 0, 6))
        val first = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertEquals(480, first.width)
        assertTrue("the square came through", isRed(first.at(0.5, 0.5)))
        assertTrue("the white came through", isWhite(first.at(0.05, 0.05)))
        val movie = android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size)!!
        Log.i("Depth", "gif: ${bytes.size / 1024} KB, ${movie.duration()} ms")
        assertTrue("about four seconds: ${movie.duration()}", movie.duration() in 3_500..4_500)

        val sounds = com.zellahq.app.sound.Sounds
        val cam = sounds.file(context, sounds.music(context).first(), "music")
        val m4a = File(dir, "depth-format.m4a").apply { delete() }
        assertTrue(com.zellahq.app.export.Formats.soundOnly(cam, m4a))
        val ex = android.media.MediaExtractor()
        ex.setDataSource(m4a.absolutePath)
        assertEquals(1, ex.trackCount)
        assertTrue(ex.getTrackFormat(0).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("audio/"))
        ex.release()
    }

    @Test fun cardsLandAndSavedLooksApply() {
        val p = project(clip())
        val intro = com.zellahq.app.depth.Cards.add(context, p, false, com.zellahq.app.depth.Cards.Style.Light, "Hello")!!
        assertEquals(7.0, intro.clips.sumOf { it.timelineDuration }, 0.05)
        assertEquals(0.0, intro.titles!!.single().start, 0.0)
        val outro = com.zellahq.app.depth.Cards.add(context, intro, true, com.zellahq.app.depth.Cards.Style.Dark, "Bye")!!
        assertEquals(10.0, outro.clips.sumOf { it.timelineDuration }, 0.05)
        assertEquals(7.0, outro.titles!![1].start, 0.05)
        val out = export(outro, "depth-cards.mp4")
        assertTrue("the light card shows", isWhite(frame(out, 0.1).at(0.05, 0.05)))
        assertTrue("then the clip", isRed(frame(out, 5.0).at(0.5, 0.5)))
        assertTrue("then the dark card", isBlack(frame(out, 9.8).at(0.05, 0.05)))

        val two = project(clip()).let { it.copy(clips = it.clips + clip()) }
        val store = com.zellahq.app.depth.LookStore(context)
        val t = com.zellahq.core.project.LookTemplate(newId(), "Mine", isoNow(), null, null, "Film Noir", 0.8, "Spin", 0.6, null)
        store.add(t)
        assertTrue(store.all().any { it.id == t.id })
        val applied = com.zellahq.app.depth.applyLook(context, two, t)
        assertEquals("Spin", applied.transitions!!.single().type)
        assertTrue(applied.clips.all { com.zellahq.app.looks.Looks.presetName(it) == "Film Noir" })
        store.remove(t.id)
        assertTrue(store.all().none { it.id == t.id })
    }

    @Test fun depthToolsAreShownToProOnlyButAlwaysExport() {
        val now = System.currentTimeMillis()
        val free = EntitlementState(Plan.Free, installEpochMs = now - 30L * 24 * 3600 * 1000)
        val week = EntitlementState(Plan.Free, installEpochMs = now - 24L * 3600 * 1000)
        assertTrue(!PremiumGate.showsDepthTools(free, now))
        assertTrue(PremiumGate.showsDepthTools(week, now))
        assertTrue(PremiumGate.showsDepthTools(EntitlementState(Plan.Pro, installEpochMs = 0), now))
    }
}
