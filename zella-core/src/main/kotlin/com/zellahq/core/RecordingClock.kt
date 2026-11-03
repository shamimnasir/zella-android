package com.zellahq.core

/**
 * Maps raw monotonic timestamps (nanoseconds, the same clock for video and every audio track)
 * to presentation times in microseconds that start at zero and skip paused spans.
 *
 * Frames and samples that were captured while paused are dropped, not shifted, so picture and
 * sound lose exactly the same stretch of time and cannot drift apart.
 *
 * The start is set once, by the first video frame. Fragmented MP4 as written by Media3 has no
 * per-track start offset: every track's first sample plays at zero. So every track must really
 * begin at the same instant, and anything captured before the first frame is dropped.
 */
class RecordingClock(startNs: Long? = null) {
    @Volatile private var startNs: Long = startNs ?: Long.MIN_VALUE

    val started get() = startNs != Long.MIN_VALUE

    /** Sets the start if it is not set yet. Returns the start in force. */
    @Synchronized
    fun startAt(ns: Long): Long {
        if (!started) startNs = ns
        return startNs
    }

    private val pauses = ArrayList<LongArray>() // [fromNs, toNs]; toNs is Long.MAX_VALUE while open

    @Synchronized
    fun pause(nowNs: Long) {
        if (pauses.isEmpty() || pauses.last()[1] != Long.MAX_VALUE) pauses.add(longArrayOf(nowNs, Long.MAX_VALUE))
    }

    @Synchronized
    fun resume(nowNs: Long) {
        if (pauses.isNotEmpty() && pauses.last()[1] == Long.MAX_VALUE) pauses.last()[1] = nowNs
    }

    /** Presentation time in microseconds, or null before the start or inside a pause. */
    @Synchronized
    fun presentationUs(timestampNs: Long): Long? {
        if (!started || timestampNs < startNs) return null
        var skipped = 0L
        for (p in pauses) {
            if (timestampNs >= p[0] && timestampNs < p[1]) return null
            if (p[1] != Long.MAX_VALUE && p[1] <= timestampNs) skipped += p[1] - p[0]
        }
        return (timestampNs - startNs - skipped) / 1_000
    }
}
