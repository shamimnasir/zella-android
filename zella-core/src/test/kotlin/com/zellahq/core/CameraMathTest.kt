package com.zellahq.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraMathTest {
    @Test fun snapsToQuarterTurns() {
        assertEquals(0, CameraMath.snap(0))
        assertEquals(0, CameraMath.snap(44))
        assertEquals(90, CameraMath.snap(45))
        assertEquals(270, CameraMath.snap(300))
        assertEquals(0, CameraMath.snap(350))
        assertEquals(0, CameraMath.snap(CameraMath.ORIENTATION_UNKNOWN))
    }

    @Test fun backCameraPortraitAndLandscape() {
        // Typical back sensor is mounted at 90.
        assertEquals(90, CameraMath.recordingRotation(90, front = false, deviceDegrees = 0))
        assertEquals(180, CameraMath.recordingRotation(90, front = false, deviceDegrees = 90))
        assertEquals(0, CameraMath.recordingRotation(90, front = false, deviceDegrees = 270))
    }

    @Test fun frontCameraPortraitAndLandscape() {
        // Typical front sensor is mounted at 270. Front turns the other way.
        assertEquals(270, CameraMath.recordingRotation(270, front = true, deviceDegrees = 0))
        assertEquals(180, CameraMath.recordingRotation(270, front = true, deviceDegrees = 90))
        assertEquals(0, CameraMath.recordingRotation(270, front = true, deviceDegrees = 270))
    }

    @Test fun realtimeFramesMoveOntoTheMonotonicClock() {
        // Phone slept for 3 hours: boot time is 3 h ahead of monotonic time.
        val sleep = 3L * 3600 * 1_000_000_000
        val mono = 50_000_000_000L
        val frame = mono + sleep - 20_000_000 // captured 20 ms ago, stamped in boot time
        assertEquals(mono - 20_000_000, CameraMath.toMonotonicNs(frame, true, mono + sleep, mono))
        assertEquals(frame, CameraMath.toMonotonicNs(frame, false, mono + sleep, mono))
    }
}
