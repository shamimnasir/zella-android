package com.zellahq.core

enum class RecorderState { Idle, Recording, Paused, Stopping }

/**
 * Legal moves of the recorder. The service asks here before acting, so a double tap on Stop or a
 * Resume that arrives after a crash cannot start a second stop sequence.
 */
object RecorderTransitions {
    fun next(
        from: RecorderState,
        event: Event,
    ): RecorderState? =
        when (from to event) {
            RecorderState.Idle to Event.Start -> RecorderState.Recording
            RecorderState.Recording to Event.Pause -> RecorderState.Paused
            RecorderState.Paused to Event.Resume -> RecorderState.Recording
            RecorderState.Recording to Event.Stop -> RecorderState.Stopping
            RecorderState.Paused to Event.Stop -> RecorderState.Stopping
            RecorderState.Stopping to Event.Finished -> RecorderState.Idle
            RecorderState.Recording to Event.Failed -> RecorderState.Stopping
            RecorderState.Paused to Event.Failed -> RecorderState.Stopping
            else -> null
        }

    enum class Event { Start, Pause, Resume, Stop, Finished, Failed }
}
