package com.zellahq.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingClockTest {
    private val s = 1_000_000_000L

    @Test fun startsAtZeroAndCountsMicroseconds() {
        val c = RecordingClock(5 * s)
        assertEquals(0L, c.presentationUs(5 * s))
        assertEquals(2_000_000L, c.presentationUs(7 * s))
    }

    @Test fun dropsAnythingBeforeStart() = assertNull(RecordingClock(5 * s).presentationUs(4 * s))

    @Test fun unstartedClockDropsEverythingThenFirstFrameSetsZero() {
        val c = RecordingClock()
        assertNull(c.presentationUs(3 * s)) // audio that arrives before the first video frame
        assertEquals(4 * s, c.startAt(4 * s))
        assertEquals(4 * s, c.startAt(9 * s)) // a later call cannot move the start
        assertEquals(0L, c.presentationUs(4 * s))
        assertEquals(500_000L, c.presentationUs(4 * s + 500_000_000))
    }

    @Test fun pausedSpanIsDroppedAndLaterTimeIsShifted() {
        val c = RecordingClock(0)
        c.pause(10 * s)
        c.resume(15 * s)
        assertEquals(9_000_000L, c.presentationUs(9 * s))
        assertNull(c.presentationUs(12 * s))
        assertEquals(10_000_000L, c.presentationUs(15 * s))
        assertEquals(12_000_000L, c.presentationUs(17 * s))
    }

    @Test fun openPauseDropsEverythingAfterIt() {
        val c = RecordingClock(0)
        c.pause(3 * s)
        assertNull(c.presentationUs(100 * s))
    }

    @Test fun videoAndAudioLoseTheSameTime() {
        val c = RecordingClock(0)
        c.pause(4 * s)
        c.resume(6 * s)
        c.pause(8 * s)
        c.resume(9 * s)
        // Same instant on both tracks must map to the same presentation time.
        val t = 10 * s + 123_000
        assertEquals(c.presentationUs(t), c.presentationUs(t))
        assertEquals(7_000_123L, c.presentationUs(t))
    }
}

class RecorderTransitionsTest {
    private fun step(
        from: RecorderState,
        e: RecorderTransitions.Event,
    ) = RecorderTransitions.next(from, e)

    @Test fun happyPath() {
        var s = RecorderState.Idle
        for (e in listOf(
            RecorderTransitions.Event.Start,
            RecorderTransitions.Event.Pause,
            RecorderTransitions.Event.Resume,
            RecorderTransitions.Event.Stop,
            RecorderTransitions.Event.Finished,
        )) {
            s = step(s, e) ?: error("blocked at $e")
        }
        assertEquals(RecorderState.Idle, s)
    }

    @Test fun secondStopIsRefused() {
        assertNull(step(RecorderState.Stopping, RecorderTransitions.Event.Stop))
    }

    @Test fun cannotPauseOrResumeWhenIdle() {
        assertNull(step(RecorderState.Idle, RecorderTransitions.Event.Pause))
        assertNull(step(RecorderState.Idle, RecorderTransitions.Event.Resume))
    }

    @Test fun failureAlwaysLeadsToStopping() {
        assertEquals(RecorderState.Stopping, step(RecorderState.Recording, RecorderTransitions.Event.Failed))
        assertEquals(RecorderState.Stopping, step(RecorderState.Paused, RecorderTransitions.Event.Failed))
    }
}

class RecordingJournalTest {
    private fun tmp() = java.nio.file.Files.createTempDirectory("zj").toFile()

    @Test fun journalAndVideoNamesPair() {
        val d = tmp()
        val j = RecordingJournal(d)
        val v = File(d, "Zella-1.mp4")
        assertEquals("Zella-1.zjournal", j.journalFor(v).name)
        assertEquals(v, j.videoFor(j.journalFor(v)))
    }

    @Test fun leftoverJournalIsInterruptedAndFinishedVideoIsNot() {
        val d = tmp()
        val j = RecordingJournal(d)
        File(d, "done.mp4").writeText("x")
        val left = File(d, "cut.zjournal").apply { writeText("x") }
        assertEquals(listOf(left), j.interrupted())
    }
}

class SampleJournalTest {
    private fun tmp() = java.nio.file.Files.createTempDirectory("sj").toFile()

    private fun write(
        f: File,
        samples: Int,
    ) {
        SampleJournal.Writer(f).use { w ->
            val v = w.addTrack(mapOf("mime" to "video/avc", "width" to 720, "csd-0" to byteArrayOf(0, 0, 1, 103)))
            val a = w.addTrack(mapOf("mime" to "audio/mp4a-latm", "sample-rate" to 48_000))
            repeat(samples) { i ->
                val t = if (i % 2 == 0) v else a
                val data = java.nio.ByteBuffer.wrap(ByteArray(100 + i) { i.toByte() })
                w.writeSample(t, i * 21_333L, if (i % 10 == 0) 1 else 0, data, 100 + i)
            }
        }
    }

    @Test fun roundTripKeepsExactTimestampsFlagsAndBytes() {
        val f = File(tmp(), "a.zjournal")
        write(f, 50)
        val got = ArrayList<SampleJournal.Sample>()
        val c = SampleJournal.read(f, onSample = { got += it })
        assertEquals(2, c.tracks.size)
        assertEquals(720, c.tracks[0].format["width"])
        assertTrue((c.tracks[0].format["csd-0"] as ByteArray).contentEquals(byteArrayOf(0, 0, 1, 103)))
        assertEquals(50, c.sampleCount)
        assertEquals(0L, c.tornBytes)
        assertEquals(49 * 21_333L, got.last().ptsUs)
        assertEquals(1, got[10].flags)
        assertEquals(100 + 7, got[7].data.size)
        assertEquals(7.toByte(), got[7].data[0])
    }

    @Test fun everyPossibleKillPointLeavesAReadablePrefix() {
        val d = tmp()
        val full = File(d, "full.zjournal").also { write(it, 20) }
        val bytes = full.readBytes()
        val counts = HashSet<Int>()
        // Cut the file at every byte, like a process killed mid-write.
        for (cut in 0..bytes.size) {
            val f = File(d, "cut.zjournal").apply { writeBytes(bytes.copyOf(cut)) }
            val c = SampleJournal.read(f)
            assertTrue("cut $cut", c.sampleCount <= 20)
            counts += c.sampleCount
        }
        assertEquals((0..20).toSet(), counts)
    }

    @Test fun corruptedRecordStopsReadingThere() {
        val f = File(tmp(), "c.zjournal")
        write(f, 20)
        val bytes = f.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        f.writeBytes(bytes)
        val c = SampleJournal.read(f)
        assertTrue(c.sampleCount in 1 until 20)
        assertTrue(c.tornBytes > 0)
    }
}
