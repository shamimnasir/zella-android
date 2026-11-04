package com.zellahq.core.audio

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceTest {
    @Test fun energyFindsTheTalkingParts() {
        val rate = 16_000
        // 1 s quiet, 1.5 s tone, 1 s quiet, 0.5 s tone.
        val x =
            FloatArray(4 * rate) { i ->
                val t = i.toDouble() / rate
                val on = (t in 1.0..2.5) || (t in 3.5..4.0)
                (if (on) 0.2 * sin(2 * PI * 220 * t) else 0.0005 * sin(2 * PI * 50 * t)).toFloat()
            }
        val w = Voice.fromEnergy(x, rate)
        assertEquals(2, w.size)
        assertEquals(1.0, w[0].start, 0.05)
        assertEquals(2.5, w[0].end, 0.05)
    }

    @Test fun shortGapsAreBridged() {
        val merged = Voice.merge(listOf(Window(0.0, 1.0), Window(1.2, 2.0), Window(3.0, 3.5)), 0.35)
        assertEquals(listOf(Window(0.0, 2.0), Window(3.0, 3.5)), merged)
    }

    /** The ducked spans the iPhone's own code picks, from tools/ios-conformance/duck.swift. */
    @Test fun duckedSpansMatchTheIphone() {
        val text = javaClass.getResourceAsStream("/fixtures/ios/duck-ramps.json")!!.readBytes().decodeToString()
        val cases = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray
        assertEquals(12, cases.size)
        for (c in cases.map { it.jsonObject }) {
            val total = c["total"]!!.jsonPrimitive.double
            val voice = c["voice"]!!.jsonArray.map { w -> Window(w.jsonArray[0].jsonPrimitive.double, w.jsonArray[1].jsonPrimitive.double) }
            val want = c["ramps"]!!.jsonArray.map { r -> r.jsonArray[0].jsonPrimitive.double to r.jsonArray[1].jsonPrimitive.double }
            val fadeStart = total - minOf(MusicEnvelope.FADE_OUT, maxOf(0.0, total * 0.5))
            val got = MusicEnvelope.ramps(voice, MusicEnvelope.RAMP, fadeStart, MusicEnvelope.RAMP_GAP)
            assertEquals("voice $voice over $total s", want.size, got.size)
            want.zip(got).forEach { (w, g) ->
                assertEquals(w.first, g.down, 1e-6)
                assertEquals(w.second, g.up, 1e-6)
            }
        }
    }

    @Test fun musicOpensDipsAndFades() {
        val e = MusicEnvelope.of(volume = 0.4, duck = true, voice = listOf(Window(3.0, 5.0)), total = 20.0)
        assertEquals("opens at 1.25 times", 0.5, e.gain(1.0), 1e-9)
        assertEquals("18% under the voice", 0.4 * 0.18, e.gain(4.0), 1e-9)
        assertEquals("back to its level after", 0.4, e.gain(10.0), 1e-9)
        assertEquals("half way through the fade", 0.2, e.gain(19.25), 1e-9)
        assertEquals("silent at the very end", 0.0, e.gain(20.0), 1e-9)
    }

    @Test fun musicWithNothingToStepBackForStaysLevelThenFades() {
        val e = MusicEnvelope.of(volume = 0.4, duck = true, voice = emptyList(), total = 10.0)
        assertEquals(0.4, e.gain(0.0), 1e-9)
        assertEquals(0.4, e.gain(8.0), 1e-9)
        assertEquals(0.0, e.gain(10.0), 1e-9)
    }

    @Test fun aVoiceoverStaysFlat() {
        val e = MusicEnvelope.of(volume = 0.7, duck = false, voice = listOf(Window(1.0, 2.0)), total = 5.0)
        listOf(0.0, 1.5, 4.99).forEach { assertEquals(0.7, e.gain(it), 1e-9) }
    }
}

class VoiceChainTest {
    private val rate = 48_000

    private fun tone(
        f: Double,
        level: Double,
        seconds: Double = 1.0,
    ) = FloatArray((seconds * rate).toInt()) { (level * sin(2 * PI * f * it / rate)).toFloat() }

    private fun rmsDb(
        x: FloatArray,
        from: Double = 0.5,
    ): Double {
        val a = (from * rate).toInt()
        var s = 0.0
        for (i in a until x.size) s += x[i] * x[i]
        return 10 * kotlin.math.log10((s / (x.size - a)).coerceAtLeast(1e-20))
    }

    @Test fun rumbleIsCutAndVoiceIsKept() {
        fun through(f: Double) = rmsDb(VoiceChain(rate, 1).processAll(tone(f, 0.05)))
        val rumble = through(30.0)
        val voice = through(1000.0)
        assertTrue("30 Hz $rumble dB against 1 kHz $voice dB", rumble < voice - 15)
    }

    @Test fun denoisedVoiceHoldsThePresenceBandBack() {
        val full = rmsDb(VoiceChain(rate, 1).processAll(tone(3500.0, 0.01)))
        val held = rmsDb(VoiceChain(rate, 1, denoised = true).processAll(tone(3500.0, 0.01)))
        assertTrue("presence $full dB, held back $held dB", held < full - 2)
    }

    @Test fun loudAndQuietComeCloser() {
        fun out(level: Double) = rmsDb(VoiceChain(rate, 1).processAll(tone(1000.0, level)))
        val inSpread = 20 * kotlin.math.log10(0.5 / 0.05)
        val outSpread = out(0.5) - out(0.05)
        assertTrue("20 dB in became $outSpread dB out", outSpread < inSpread * 0.6)
    }

    @Test fun peaksNeverPassTheCeiling() {
        val out = VoiceChain(rate, 1).processAll(tone(1000.0, 1.0))
        val peak = out.drop(rate / 10).maxOf { kotlin.math.abs(it) }
        assertTrue("peak $peak", peak <= 0.95f)
    }

    @Test fun gapsBetweenWordsGoQuieter() {
        // A quiet room tone at about -50 dBFS, under the expander's line.
        val quiet = tone(1000.0, 0.004, 2.0)
        val out = VoiceChain(rate, 1, voicing = VoiceChain.Voicing.Natural).processAll(quiet)
        assertTrue("room tone ${rmsDb(out)} dB", rmsDb(out) < rmsDb(quiet) + 6)
    }

    @Test fun everyVoicingRunsAndStaysFinite() {
        for (v in VoiceChain.Voicing.entries) {
            val out =
                VoiceChain(rate, 2, v).let { c ->
                    val frame = DoubleArray(2)
                    val l = tone(220.0, 0.3)
                    FloatArray(l.size) { i ->
                        frame[0] = l[i].toDouble()
                        frame[1] = l[i].toDouble()
                        c.frame(frame)
                        frame[0].toFloat()
                    }
                }
            assertTrue("$v", out.all { it.isFinite() })
        }
    }
}
