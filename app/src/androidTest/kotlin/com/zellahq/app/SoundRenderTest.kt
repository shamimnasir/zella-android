package com.zellahq.app

import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.app.editor.CompositionBuilder
import com.zellahq.app.editor.ExportOutcome
import com.zellahq.app.editor.ExportSettings
import com.zellahq.app.editor.Exporter
import com.zellahq.app.recording.PatternSource
import com.zellahq.app.recording.Recorder
import com.zellahq.app.recording.RecorderConfig
import com.zellahq.app.sound.Sounds
import com.zellahq.core.EntitlementState
import com.zellahq.core.ExportDecision
import com.zellahq.core.ExportRequest
import com.zellahq.core.Plan
import com.zellahq.core.PremiumGate
import com.zellahq.core.audio.Loudness
import com.zellahq.core.project.Clip
import com.zellahq.core.project.MediaRef
import com.zellahq.core.project.MusicBed
import com.zellahq.core.project.Project
import com.zellahq.core.project.ProjectStore
import com.zellahq.core.project.SoundBite
import com.zellahq.core.project.duration
import com.zellahq.core.project.isoNow
import com.zellahq.core.project.newId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Sound through the real export path, measured from the file that comes out: loudness, ducking,
 * looping, sound effect timing against the picture, and voice clean-up. Every number is printed so
 * it can go into the evidence.
 */
@RunWith(AndroidJUnit4::class)
class SoundRenderTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val dir = File(context.cacheDir, "sound").apply { mkdirs() }
    private val store = ProjectStore(File(context.filesDir, "projects-test"))

    private fun builder() =
        CompositionBuilder(store).apply {
            soundEffectFile = { id -> Sounds.effects(context).firstOrNull { it.id == id }?.let { Sounds.file(context, it, "sfx") } }
            voiceWindows = { Sounds.voiceWindows(it) }
        }

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
        val o = Exporter(context, builder()).export(p, permit, ExportSettings(shortSide = 720), out)
        assertTrue("export $name: $o", o is ExportOutcome.Done)
        return out
    }

    /** A picture with no sound at all. */
    private fun picture(
        seconds: Int,
        color: Int = Color.DKGRAY,
    ): File {
        val f = File(dir, "picture-$seconds-$color.mp4")
        if (!f.isFile) {
            val r = Recorder(f, RecorderConfig(720, 1280), PatternSource(30, background = color), emptyMap())
            r.start()
            Thread.sleep(seconds * 1000L + 400)
            r.stop()
        }
        return f
    }

    private fun source(f: File) = MediaRef.Absolute(f.toURI().toString())

    private fun project(
        clips: List<Clip>,
        beds: List<MusicBed>? = null,
        bites: List<SoundBite>? = null,
        extras: JsonObject = JsonObject(emptyMap()),
    ) = Project(
        id = newId(),
        name = "sound",
        createdAt = isoNow(),
        modifiedAt = isoNow(),
        clips = clips,
        musicBeds = beds,
        soundBites = bites,
        extras = extras,
    )

    private fun silentClip(
        seconds: Int,
        color: Int = Color.DKGRAY,
    ) = Clip(newId(), source(picture(seconds, color)), seconds.toDouble(), trimEnd = seconds.toDouble())

    private fun voice() = AudioTestKit.readWav(inst.context.assets.open("voice-fixture.wav").use { it.readBytes() })

    /** A clip with this sound in it: the picture and the sound go through one export first. */
    private fun clipWithSound(
        name: String,
        samples: FloatArray,
    ): Clip {
        val seconds = samples.size / 48_000
        val wav = AudioTestKit.wav(File(dir, "$name.wav"), samples)
        val bed = MusicBed(newId(), source(wav), samples.size / 48_000.0, volume = 1.0, duck = false, name = name)
        val made = export(project(listOf(silentClip(seconds)), beds = listOf(bed)), "$name.mp4")
        return Clip(newId(), source(made), seconds.toDouble(), trimEnd = seconds.toDouble())
    }

    private fun tone(
        seconds: Double,
        level: Float = 0.25f,
    ) = FloatArray((seconds * 48_000).toInt()) { (level * sin(2 * PI * 440 * it / 48_000)).toFloat() }

    private fun lufs(f: File): Double = AudioTestKit.decode(f).let { Loudness.integrated(it.channels, it.rate) }

    private fun windows(vararg w: Pair<Double, Double>) =
        JsonObject(
            mapOf(
                "voiceWindows" to
                    JsonArray(w.map { JsonObject(mapOf("start" to JsonPrimitive(it.first), "end" to JsonPrimitive(it.second))) }),
            ),
        )

    private fun log(text: String) = Log.i("SoundRenderTest", text)

    /** Quiet and normal speech both come out near -14 LUFS, through the app's own analysis. */
    @Test fun evenOutLoudnessLandsNearTheTarget() {
        for ((name, scale) in listOf("normal" to 1f, "quiet" to 0.25f)) {
            val v = voice()
            val clip = clipWithSound("voice-$name", FloatArray(v.size) { v[it] * scale })
            val plain = project(listOf(clip))
            val plainFile = export(plain, "plain-$name.mp4")
            val before = lufs(plainFile)
            log("export carries ${AudioTestKit.decode(plainFile).channels.size} channels at ${AudioTestKit.decode(plainFile).rate} Hz")
            val analysed = Sounds.analyse(context, plain)
            val gain = (analysed.extras[Sounds.LOUDNESS_KEY] as JsonPrimitive).content.toDouble()
            val outFile = export(analysed, "even-$name.mp4")
            val after = lufs(outFile)
            log("loudness $name: before %.2f LUFS, gain %+.2f dB, after %.2f LUFS".format(before, gain, after))
            // The boost is capped at +12 dB, so a very quiet take lands short of the target rather than pumped up.
            val expected = minOf(-14.0, before + 12.0)
            assertTrue("$name came out at $after LUFS, expected $expected", abs(after - expected) <= 1.0)
            val windows = analysed.extras["voiceWindows"] as JsonArray
            assertTrue("voice found in $name speech", windows.isNotEmpty())
        }
    }

    /** Music dips to 18% under voice with the iPhone's envelope, and repeats to fill the video. */
    @Test fun musicStepsBackAndRepeats() {
        val wav = AudioTestKit.wav(File(dir, "tone-2s.wav"), tone(2.0))
        val bed = MusicBed(newId(), source(wav), 2.0, volume = 0.5, duck = true, name = "tone")
        val out = decodeExport(project(listOf(silentClip(10)), beds = listOf(bed), extras = windows(3.0 to 5.0)), "duck.mp4")
        val intro = out.rmsDb(1.0, 2.5)
        val under = out.rmsDb(3.4, 4.6)
        val after = out.rmsDb(6.0, 7.5)
        val fadeEnd = out.rmsDb(9.85, 9.98)
        log("music: intro %.1f dB, under voice %.1f dB, after %.1f dB, end of fade %.1f dB".format(intro, under, after, fadeEnd))
        val expectedDip = 20 * kotlin.math.log10(0.18)
        assertEquals("dip against the settled level", expectedDip, under - after, 1.0)
        assertEquals("the opening is 1.25 times", 20 * kotlin.math.log10(1.25), intro - after, 0.8)
        assertTrue("still playing at 7 s, past its 2 s length", after > -30)
        assertTrue("fades out by the end", fadeEnd < after - 15)
    }

    /** A bed that does not step back plays once, and a stored play length cuts music short. */
    @Test fun aFlatBedPlaysOnceAndATrimCuts() {
        val wav = AudioTestKit.wav(File(dir, "tone-2s.wav"), tone(2.0))
        val flat = MusicBed(newId(), source(wav), 2.0, volume = 1.0, duck = false, name = "once")
        val once = decodeExport(project(listOf(silentClip(10)), beds = listOf(flat)), "once.mp4")
        log("flat bed: during %.1f dB, after %.1f dB".format(once.rmsDb(0.5, 1.5), once.rmsDb(3.0, 6.0)))
        assertTrue(once.rmsDb(0.5, 1.5) > -20)
        assertTrue("silent once finished", once.rmsDb(3.0, 6.0) < -60)

        val cut =
            flat.copy(
                duck = true,
                extras = JsonObject(mapOf("duration" to JsonPrimitive(3.0))),
            )
        val trimmed = decodeExport(project(listOf(silentClip(10)), beds = listOf(cut)), "trim.mp4")
        log("trimmed bed: before the cut %.1f dB, after %.1f dB".format(trimmed.rmsDb(2.2, 2.9), trimmed.rmsDb(3.5, 6.0)))
        assertTrue(trimmed.rmsDb(2.2, 2.9) > -20)
        assertTrue("silent after the trim", trimmed.rmsDb(3.5, 6.0) < -60)
    }

    /**
     * A sound effect lands on its time against the picture. The cut between two colours is at 2.0 s
     * and the effect is placed at 2.0 s. A bite at the very end must not make the video longer.
     */
    @Test fun aSoundEffectLandsOnTheCutAndNeverStretchesTheVideo() {
        val p =
            project(
                listOf(silentClip(2, Color.RED), silentClip(2, Color.GREEN)),
                bites = listOf(SoundBite(newId(), "Click", 2.0), SoundBite(newId(), "Explosion", 3.9)),
            )
        val f = export(p, "sfx.mp4")
        val mmr = MediaMetadataRetriever().apply { setDataSource(f.path) }
        var firstGreenMs = -1L
        for (ms in 1_800L..2_300L step 5) {
            val px = mmr.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!.getPixel(8, 8)
            if (Color.green(px) > 200 && Color.red(px) < 60) {
                firstGreenMs = ms
                break
            }
        }
        mmr.release()
        val sound = AudioTestKit.decode(f)
        val onset = sound.firstAbove(-40.0)!!
        val asset = AudioTestKit.decode(Sounds.file(context, Sounds.effects(context).first { it.id == "Click" }, "sfx"))
        val own = asset.firstAbove(-40.0)!!
        val ex = MediaExtractor().apply { setDataSource(f.path) }
        val formats = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
        ex.release()
        val videoMs = formats.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }.getLong(MediaFormat.KEY_DURATION) / 1000
        val audioMs = formats.first { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }.getLong(MediaFormat.KEY_DURATION) / 1000
        log(
            "effect: first green frame $firstGreenMs ms, sound onset %.1f ms (its own onset %.1f ms), video $videoMs ms, audio $audioMs ms"
                .format(onset * 1000, own * 1000),
        )
        val offset = onset - own - firstGreenMs / 1000.0
        log("effect against the picture: %+.1f ms".format(offset * 1000))
        assertTrue("sound is %.1f ms from the cut".format(offset * 1000), abs(offset) <= 0.05)
        assertTrue("video $videoMs ms, timeline 4000", abs(videoMs - 4000) <= 70)
        assertTrue("audio $audioMs ms, timeline 4000", abs(audioMs - 4000) <= 100)
    }

    /** Clean-up through the export: less hiss against the voice, and the voice stays where it was. */
    @Test fun cleanUpThroughExportRemovesHissAndKeepsTheVoiceInPlace() {
        val v = voice()
        val rnd = java.util.Random(7)
        val noisy = FloatArray(v.size) { v[it] + rnd.nextGaussian().toFloat() * 0.02f }
        val clip = clipWithSound("noisy", noisy)
        val plain = AudioTestKit.decode(export(project(listOf(clip)), "noisy-plain.mp4"))
        val plainAt48 = AudioTestKit.resample(plain.mono, plain.rate, 48_000)
        val on = clip.copy(extras = JsonObject(mapOf("voiceNoise" to JsonPrimitive(true))))
        val cleanedFile = export(project(listOf(on)), "noisy-clean.mp4")
        val cleaned = AudioTestKit.decode(cleanedFile)
        assertEquals(48_000, cleaned.rate)

        fun hiss(x: FloatArray) =
            AudioTestKit.db(AudioTestKit.bandPower(x, 3000.0, 6000.0)) - AudioTestKit.db(AudioTestKit.bandPower(x, 300.0, 3000.0))
        val before = hiss(plainAt48)
        val after = hiss(cleaned.mono)
        val lag = AudioTestKit.lagSamples(plainAt48, cleaned.mono)
        log(
            "clean-up through export: hiss vs voice %.1f -> %.1f dB, voice moved %.2f ms, lengths %.3f and %.3f s"
                .format(before, after, lag * 1000.0 / 48_000, plain.seconds, cleaned.seconds),
        )
        assertTrue("hiss %.1f -> %.1f".format(before, after), after <= before - 3)
        assertTrue("voice moved by $lag samples", abs(lag) <= 48)
        assertEquals(plain.seconds, cleaned.seconds, 0.1)
    }

    private fun decodeExport(
        p: Project,
        name: String,
    ) = AudioTestKit.decode(export(p, name))
}
