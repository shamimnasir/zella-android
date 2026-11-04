package com.zellahq.app

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.auto.AutoPolish
import com.zellahq.app.auto.FaceFinder
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.editor.Storage
import com.zellahq.app.editor.appComposition
import com.zellahq.app.recording.PatternSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import com.zellahq.core.EntitlementState
import com.zellahq.core.ExportDecision
import com.zellahq.core.ExportRequest
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import com.zellahq.core.audio.Loudness
import com.zellahq.core.auto.Polish
import com.zellahq.core.auto.PolishStep
import com.zellahq.core.auto.SilenceDetector
import com.zellahq.core.project.Aspect
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.MusicBed
import com.zellahq.core.project.Project
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import com.zellahq.core.project.startTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Auto-Polish, Make it Viral and Make Vertical end to end: real speech with long pauses, the real
 * caption engine, a real export. The Phase 6 gates are measured here: the plan shown is the plan run,
 * a second run stacks nothing, density stays under the cap, and the pauses are gone from the file.
 */
@RunWith(AndroidJUnit4::class)
class AutoPolishRunTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val dir = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
    private val store = Storage.store(context)

    private fun log(s: String) = Log.i("AutoPolishRun", s)

    private fun export(
        p: Project,
        name: String,
    ): File {
        val gate = EntitlementState(Plan.Free, installEpochMs = System.currentTimeMillis())
        val permit =
            (
                PremiumGate.authorize(
                    ExportRequest(p.duration, 720),
                    gate,
                    System.currentTimeMillis(),
                ) as ExportDecision.Allowed
            ).permit
        val out = File(dir, name).apply { delete() }
        val o = Exporter(context, appComposition(context, store)).export(p, permit, ExportSettings(shortSide = 720), out)
        assertTrue("export $name: $o", o is ExportOutcome.Done)
        return out
    }

    /** Speech, a long pause, the same speech, a long pause, the same speech. */
    private fun speakingClip(): Clip {
        val v = AudioTestKit.readWav(inst.context.assets.open("voice-fixture.wav").use { it.readBytes() })
        val rate = 48_000
        val gap = FloatArray(rate * 2)
        val samples = v + gap + v + gap + v
        val seconds = samples.size / rate
        val wav = AudioTestKit.wav(File(context.cacheDir, "polish-take.wav"), samples)
        val picture = File(context.cacheDir, "polish-picture.mp4")
        if (!picture.isFile) {
            val r = Recorder(picture, RecorderConfig(720, 1280), PatternSource(30, background = Color.DKGRAY), emptyMap())
            r.start()
            Thread.sleep(seconds * 1000L + 400)
            r.stop()
        }
        val bed =
            MusicBed(
                newId(),
                MediaRef.Absolute(wav.toURI().toString()),
                samples.size.toDouble() / rate,
                volume = 1.0,
                duck = false,
                name = "take",
            )
        val made =
            export(
                Project(
                    id = newId(),
                    name = "take",
                    createdAt = isoNow(),
                    modifiedAt = isoNow(),
                    clips =
                        listOf(
                            Clip(newId(), MediaRef.Absolute(picture.toURI().toString()), seconds.toDouble(), trimEnd = seconds.toDouble()),
                        ),
                    musicBeds = listOf(bed),
                ),
                "polish-take.mp4",
            )
        return Clip(newId(), MediaRef.Absolute(made.toURI().toString()), seconds.toDouble(), trimEnd = seconds.toDouble())
    }

    private fun project(clip: Clip) =
        Project(
            id = newId(),
            name = "polish",
            createdAt = isoNow(),
            modifiedAt = isoNow(),
            clips = listOf(clip),
        )

    private fun layout(p: Project) =
        p.clips.joinToString(" ") { c ->
            val t = if (p.transitions.orEmpty().any { it.afterClipId == c.id }) "+" else ""
            "%.3f(%.3f)%s".format(p.startTime(c.id), c.timelineDuration, t)
        }

    private fun describe(p: Project) =
        "length %.1f s, %d clips, %d titles, %d transitions, %d zooms, %d sounds, %d beds, captions %d, density %.3f".format(
            p.duration, p.clips.size, p.titles.orEmpty().size, p.transitions.orEmpty().size, p.zoomBlocks.orEmpty().size,
            p.soundBites.orEmpty().size, p.musicBeds.orEmpty().size, p.captions?.cues?.size ?: 0, Polish.effectDensity(p),
        )

    /** The longest stretch of quiet left in a file's sound. */
    private fun longestPause(f: File): Double {
        val d = AudioTestKit.decode(f)
        val e = SilenceDetector().energies(AudioTestKit.resample(d.mono, d.rate, 16_000))
        val threshold = SilenceDetector().adaptiveThreshold(e)
        var best = 0.0
        var run = 0.0
        for (x in e) {
            if (x.db < threshold) {
                run += 0.03
                best = maxOf(best, run)
            } else {
                run = 0.0
            }
        }
        return best
    }

    /** The test picture is grey with a white flash, so nothing in a finished video may be black. */
    private fun blackFrames(f: File): List<Long> {
        val ex = MediaExtractor().apply { setDataSource(f.path) }
        val video = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.selectTrack(video)
        val times = ArrayList<Long>()
        while (ex.sampleTime >= 0) {
            times += ex.sampleTime
            ex.advance()
        }
        ex.release()
        val mmr = MediaMetadataRetriever().apply { setDataSource(f.path) }
        val black = ArrayList<Long>()
        for (t in times.sorted()) {
            val b = mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
            val c = b.getPixel(4, b.height / 3)
            if (Color.red(c) < 25 && Color.green(c) < 25 && Color.blue(c) < 25) black += t / 1000
        }
        mmr.release()
        return black
    }

    @Test fun autoPolishDoesWhatItSaidAndARunTwiceAddsNothing() {
        val clip = speakingClip()
        val start = project(clip)
        val plain = export(start, "polish-before.mp4")
        val pauseBefore = longestPause(plain)
        val runner = AutoPolish(context)
        val seen = ArrayList<PolishStep>()
        val t0 = System.currentTimeMillis()
        val first =
            runner.run(start, viral = false) { s, i, total ->
                seen += s
                log("step $i of $total: $s")
            }
        log("first run took ${System.currentTimeMillis() - t0} ms: ${describe(first.project)}")
        log("receipt ${first.receipt}")

        assertEquals("the plan shown is the plan run", PolishStep.plan(viral = false, reframing = false), first.executed)
        assertEquals(first.executed, seen)
        val p = first.project
        assertTrue("pauses cut: ${describe(p)}", p.duration < start.duration - 2.0)
        assertTrue("captions stay off in Auto-Polish", p.captions == null)
        assertEquals("no sounds in Auto-Polish", 0, p.soundBites.orEmpty().size)
        assertTrue("no colour in Auto-Polish", p.clips.none { Polish.hasOwnGrade(it) })
        assertEquals("one opener", 1, p.titles.orEmpty().size)
        assertEquals("one music bed", 1, p.musicBeds.orEmpty().size)
        assertTrue("density ${Polish.effectDensity(p)}", Polish.effectDensity(p) <= Polish.MAX_EFFECT_DENSITY)

        val second = runner.run(p, viral = false)
        log("second run: ${describe(second.project)}")
        val q = second.project
        assertEquals("titles stack", p.titles.orEmpty().size, q.titles.orEmpty().size)
        assertEquals("transitions stack", p.transitions.orEmpty().size, q.transitions.orEmpty().size)
        assertEquals("zooms stack", p.zoomBlocks.orEmpty().size, q.zoomBlocks.orEmpty().size)
        assertEquals("music stacks", p.musicBeds.orEmpty().size, q.musicBeds.orEmpty().size)

        log("layout ${layout(p)}")
        val out = export(p, "polish-after.mp4")
        val pauseAfter = longestPause(out)
        val lufs = AudioTestKit.decode(out).let { Loudness.integrated(it.channels, it.rate) }
        val ex = MediaExtractor().apply { setDataSource(out.path) }
        val video =
            (0 until ex.trackCount).map {
                ex.getTrackFormat(
                    it,
                )
            }.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
        ex.release()
        val lenMs = video.getLong(MediaFormat.KEY_DURATION) / 1000
        log(
            "longest pause %.2f s before, %.2f s after; loudness %.2f LUFS; video $lenMs ms against %.0f ms".format(
                pauseBefore,
                pauseAfter,
                lufs,
                p.duration * 1000,
            ),
        )
        assertTrue("a long pause survived: $pauseAfter s", pauseAfter < 1.0)
        assertEquals("black frames in the export", emptyList<Long>(), blackFrames(out))
        assertTrue("video length", abs(lenMs - p.duration * 1000) < 120)
        assertTrue("loudness $lufs", abs(lufs + 14) <= 3)
    }

    @Test fun makeItViralAddsItsThreeLayers() {
        val clip = speakingClip()
        val start = project(clip)
        val r = AutoPolish(context).run(start, viral = true)
        log("viral: ${describe(r.project)} receipt ${r.receipt}")
        assertEquals(PolishStep.plan(viral = true, reframing = false), r.executed)
        val p = r.project
        assertTrue("captions on", (p.captions?.cues?.size ?: 0) > 0)
        assertTrue("a sound under the cuts", p.soundBites.orEmpty().isNotEmpty())
        assertEquals("the receipt says what is there", p.soundBites.orEmpty().size, r.receipt.sounds)
        val preset = (p.captions!!.extras["preset"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals("Beast", preset)
        assertTrue("strong words marked", p.captions!!.cues.flatMap { it.words }.any { it.emphasized == true })
        val fx = p.clips.first().extras["effects"] as kotlinx.serialization.json.JsonObject
        assertEquals(Polish.BUNDLE_GRADE, (fx["presetName"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertTrue("density ${Polish.effectDensity(p)}", Polish.effectDensity(p) <= Polish.MAX_EFFECT_DENSITY)
        log("viral layout ${layout(p)}")
        val out = export(p, "viral-after.mp4")
        assertEquals("black frames in the export", emptyList<Long>(), blackFrames(out))
        val mmr = MediaMetadataRetriever().apply { setDataSource(out.path) }
        val frame = mmr.getFrameAtTime((p.duration * 0.5 * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
        File(dir, "viral-frame.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        mmr.release()
    }

    /** A wide video with a person at the left. After Make Vertical the person must sit in the middle of the frame. */
    @Test fun makeVerticalKeepsTheFaceInTheFrame() {
        val source = File(context.getExternalFilesDir(null), "face-wide.mp4")
        assumeTrue("needs the face clip pushed by tools/push-test-media.sh", source.isFile)
        val seconds = 4.0
        val clip = Clip(newId(), MediaRef.Absolute(source.toURI().toString()), seconds, trimEnd = seconds)
        val start = project(clip).copy(aspect = Aspect.Landscape)
        val p = AutoPolish(context).reshape(start, Aspect.Vertical)
        assertEquals(Aspect.Vertical, p.aspect)
        val out = export(p, "vertical-after.mp4")
        val plainOut = export(start.copy(aspect = Aspect.Vertical), "vertical-centred.mp4")

        fun faceIn(f: File): FaceFinder.Face? {
            val mmr = MediaMetadataRetriever().apply { setDataSource(f.path) }
            val b = mmr.getFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
            mmr.release()
            return FaceFinder().use { it.find(b) }
        }
        val reframed = faceIn(out)
        val centred = faceIn(plainOut)
        log("face after Make Vertical $reframed; plain centre crop $centred")
        assertTrue("no face in the vertical video", reframed != null)
        assertTrue("face at x=${reframed!!.centerX}", abs(reframed.centerX - 0.5) < 0.12)
        assertTrue("the plain centre crop should miss or edge the face: $centred", centred == null || abs(centred.centerX - 0.5) > 0.2)
    }
}
