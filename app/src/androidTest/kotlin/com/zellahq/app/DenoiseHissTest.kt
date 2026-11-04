package com.zellahq.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zellahq.whisper.Denoiser
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * The Phase 5 hiss gate. Voice clean-up must not ADD hiss: on the iPhone an RNNoise path made noisy
 * speech worse. Measured as the iPhone lesson says: the 3 to 6 kHz band RELATIVE to the voice band,
 * before and after, on real speech (the iPhone's voice fixture) with white noise added.
 */
@RunWith(AndroidJUnit4::class)
class DenoiseHissTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun voice() = AudioTestKit.readWav(inst.context.assets.open("voice-fixture.wav").use { it.readBytes() })

    private fun bandPower(
        x: FloatArray,
        lo: Double,
        hi: Double,
    ) = AudioTestKit.bandPower(x, lo, hi)

    private fun db(p: Double) = AudioTestKit.db(p)

    @Test fun cleanUpDoesNotAddHissAndRemovesNoise() {
        val clean = voice()
        val rnd = java.util.Random(7)
        val noiseAmp = 0.02f // about -37 dBFS white noise: a fan, a fridge, a busy room
        val noisy = FloatArray(clean.size) { clean[it] + (rnd.nextGaussian().toFloat() * noiseAmp) }
        val cleaned = Denoiser(1f).use { it.processAll(noisy) }
        val mixed = Denoiser(0.8f).use { it.processAll(noisy) }

        fun hissRelative(x: FloatArray) = db(bandPower(x, 3000.0, 6000.0)) - db(bandPower(x, 300.0, 3000.0))
        val original = hissRelative(clean)
        val before = hissRelative(noisy)
        val after = hissRelative(cleaned)
        val afterMixed = hissRelative(mixed)

        // Noise left in the pauses: total power where the clean voice is near silent.
        fun quietPower(x: FloatArray): Double {
            var s = 0.0
            var n = 0
            for (i in clean.indices step 480) {
                var e = 0.0
                for (k in i until minOf(i + 480, clean.size)) e += clean[k] * clean[k]
                if (sqrt(e / 480) < 0.003) {
                    for (k in i until minOf(i + 480, x.size)) s += x[k] * x[k]
                    n += 480
                }
            }
            return if (n == 0) 0.0 else s / n
        }
        val noiseBefore = db(quietPower(noisy))
        val noiseAfter = db(quietPower(cleaned))
        Log.i(
            "DenoiseHissTest",
            "hiss vs voice (dB): original %.1f, noisy %.1f, cleaned %.1f, cleaned at 80%% %.1f; noise in pauses %.1f -> %.1f dB"
                .format(original, before, after, afterMixed, noiseBefore, noiseAfter),
        )
        val lag = AudioTestKit.lagSamples(noisy, mixed)
        Log.i("DenoiseHissTest", "voice moved $lag samples")
        assertTrue("clean-up moved the voice by $lag samples", kotlin.math.abs(lag) <= 2)
        assertTrue("clean-up added hiss: %.1f dB -> %.1f dB".format(before, after), after <= before)
        assertTrue("clean-up removed less than 6 dB of noise in the pauses", noiseBefore - noiseAfter >= 6)
    }
}
