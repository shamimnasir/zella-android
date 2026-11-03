# Recording stop sequence

Frozen. Change it only after `StopSequenceLoopTest` passes 100 loops on an emulator and a phone.

The service runs this on its own thread, never the main thread.

1. Recorder state moves to Stopping. A second Stop is refused by `RecorderTransitions`.
2. Stop the storage watch.
3. Each audio reader: `AudioRecord.stop()` (unblocks a waiting read), join the reader (2 s), then the
   stopping thread queues end-of-stream into that encoder, retrying for up to 2 s. Never drop it.
4. The video source is detached: the virtual display is released, the pattern thread is joined, or
   for the camera the session is rebuilt without the encoder and `CameraHub` returns only once that
   new session runs (so no frame reaches the encoder after this step).
5. `signalEndOfInputStream()` on the video encoder.
6. Every drain thread is joined (5 s each). A drain that misses its deadline is counted, told to
   abandon, and joined again for 1 s, so it cannot touch a released codec.
7. Only now: close the sample journal (fsync).
8. Stop and release the codecs and AudioRecords.
9. Copy the journal into the MP4 (`Remuxer`, writes `NAME.mp4.part`, renames last), then delete
   the journal. A journal that survives is rebuilt into an MP4 on next launch.
10. Stop the screen share (MediaProjection).

## Lock order

There is one lock, the journal writer's monitor. It is held only inside `addTrack`, `writeSample`
and `close`. No code waits on a thread or a latch while holding it. The format latch is awaited
outside it.
