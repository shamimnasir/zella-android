package com.zellahq.core

/** Orientation and clock rules for camera recording. Pure functions, so the rules are tested here. */
object CameraMath {
    /** OrientationEventListener reports -1 when the phone lies flat. */
    const val ORIENTATION_UNKNOWN = -1

    /** Rounds a raw device angle (0 to 359, clockwise from natural portrait) to 0, 90, 180 or 270. */
    fun snap(rawDegrees: Int): Int = if (rawDegrees == ORIENTATION_UNKNOWN) 0 else ((rawDegrees + 45) / 90 * 90) % 360

    /**
     * Rotation to store in the MP4 so the video plays upright. Pixels are never rotated: the sensor's
     * own frames go to the encoder and players apply this. Same rule as Camera2's JPEG_ORIENTATION.
     */
    fun recordingRotation(
        sensorOrientation: Int,
        front: Boolean,
        deviceDegrees: Int,
    ): Int {
        val d = snap(deviceDegrees)
        return if (front) (sensorOrientation - d + 360) % 360 else (sensorOrientation + d) % 360
    }

    /**
     * Converts a camera frame time to the monotonic clock that audio uses. Many cameras stamp frames
     * with time since boot including deep sleep (REALTIME); audio uses time that stops in deep sleep.
     * After a phone has slept the two differ by hours.
     */
    fun toMonotonicNs(
        frameNs: Long,
        realtimeSource: Boolean,
        elapsedRealtimeNs: Long,
        monotonicNs: Long,
    ): Long = if (realtimeSource) frameNs - (elapsedRealtimeNs - monotonicNs) else frameNs
}
