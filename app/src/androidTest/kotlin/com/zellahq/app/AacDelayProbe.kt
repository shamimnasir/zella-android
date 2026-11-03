package com.zellahq.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zellahq.app.recording.AacDelay
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prints this phone's AAC encoder delay for BUILDLOG, and checks the measurement is repeatable. */
@RunWith(AndroidJUnit4::class)
class AacDelayProbe {
    @Test fun encoderDelayIsMeasuredTheSameEveryTime() {
        val runs = List(3) { AacDelay.measure(48_000) }
        Log.i("AacDelayProbe", "RESULT encoder delay us: $runs")
        assertTrue("delay out of range: $runs", runs.all { it in 0..200_000 })
        assertTrue("measurements differ: $runs", runs.max() - runs.min() < 1_000)
    }
}
