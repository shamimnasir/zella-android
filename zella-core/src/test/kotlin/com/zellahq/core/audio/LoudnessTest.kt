package com.zellahq.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Reference signals from EBU Tech 3341 (loudness meter conformance). */
class LoudnessTest {
    private fun sine(
        dbfs: Double,
        seconds: Double,
        freq: Double = 1000.0,
        fs: Int = 48_000,
    ) = FloatArray((seconds * fs).toInt()) { (10.0.pow(dbfs / 20) * sin(2 * PI * freq * it / fs)).toFloat() }

    @Test fun stereoSineAtMinus23IsMinus23Lufs() {
        val s = sine(-23.0, 20.0)
        assertEquals(-23.0, Loudness.integrated(listOf(s, s), 48_000), 0.1)
    }

    @Test fun stereoSineAtMinus33IsMinus33Lufs() {
        val s = sine(-33.0, 20.0)
        assertEquals(-33.0, Loudness.integrated(listOf(s, s), 48_000), 0.1)
    }

    @Test fun monoIsThreeDecibelsQuieterThanTheSameStereo() {
        val s = sine(-23.0, 20.0)
        assertEquals(-26.0, Loudness.integrated(listOf(s), 48_000), 0.1)
    }

    @Test fun worksAt44100() {
        val s = sine(-23.0, 20.0, fs = 44_100)
        assertEquals(-23.0, Loudness.integrated(listOf(s, s), 44_100), 0.1)
    }

    /** Tech 3341 case 3: -36, -23, -36 dBFS segments; the relative gate drops the quiet parts. */
    @Test fun gatingIgnoresQuietParts() {
        val s = sine(-36.0, 10.0) + sine(-23.0, 60.0) + sine(-36.0, 10.0)
        assertEquals(-23.0, Loudness.integrated(listOf(s, s), 48_000), 0.1)
    }

    @Test fun silenceHasNoLoudnessAndGetsNoGain() {
        val l = Loudness.integrated(listOf(FloatArray(48_000 * 2)), 48_000)
        assertTrue(l.isInfinite())
        assertEquals(0.0, Loudness.gainToTarget(l), 0.0)
        assertEquals(12.0, Loudness.gainToTarget(-40.0), 0.0) // capped: more would only lift noise
    }
}
