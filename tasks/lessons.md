# Lessons

Mistakes worth not repeating. Newest first.

## 2026-11-04: "repeat the last frame after 33 ms" doubles a third of camera frames

`KEY_REPEAT_PREVIOUS_FRAME_AFTER` was set to one frame time for every source. The camera delivers a
frame every 33.3 ms with a little jitter, so the encoder fired a repeat just before most real frames:
131 of 389 frame steps were under 5 ms and the file claimed 45.6 fps. The same setting caused the
bunched frames in screen recordings. Camera now has no repeat; screen repeats after 250 ms.
Check the frame interval histogram, not just the frame count.

## 2026-11-04: rebuilding the camera session freezes the recording

Every time the preview surface came or went (leaving the app, coming back), the session was rebuilt:
388 ms and 221 ms freezes. Fix: the preview stream runs into a hidden SurfaceTexture and the visible
preview shares it (`enableSurfaceSharing`, `updateOutputConfiguration`). Trap: a surface cannot leave
a shared stream while frames for it are in flight ("Shared surface still part of a pending request").
Send the new request, wait for its first completed frame, then remove. Freezes now 122 ms and 68 ms
on the emulator.

## 2026-11-04: camera frames use a different clock from audio

The emulator camera reports `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`: time since boot including deep
sleep. Audio uses the monotonic clock, which stops in deep sleep. On a phone that has slept, the two
differ by hours. The emulator never sleeps, so the difference is near zero there and no emulator test
can catch a missing conversion: the rule is unit-tested in `CameraMathTest` instead.

## 2026-11-04: AAC makes sound late by a fixed amount, and the encoder will not say how much

The first real sync measurement read sound +37.8 ms late, steady, no drift. The cause was AAC
encoder padding: the emulator's `c2.android.aac.encoder` puts a click 42.6 ms (2048 samples) late,
and its output format has no key that says so. `AacDelay` now measures it once per encoder with an
encode-then-decode click and Zella moves audio earlier by that much. After the fix: median +0.6 ms.
Never hard-code 1024 or 2048; phones differ.

## 2026-11-04: Media3 fragmented MP4 (1.10.1) cannot hold variable frame rate screen video

It writes no fragment start times (no `tfdt`) and copies the previous sample's duration onto the
last sample of every fragment. Every boundary adds or loses time, per track. Screen capture has
uneven gaps, so video drifted 4 s in 27 s while audio did not. It also drops each track's start
offset, so video that started 1.3 s after audio played 1.3 s early. Found by parsing the boxes
(`trun` durations), not by watching the video. Replaced by `SampleJournal` plus `MediaMuxer`.

## 2026-11-04: a dropped end-of-stream hangs the stop sequence

The audio reader queued end-of-stream with a 20 ms timeout and silently gave up when the encoder had
no free input buffer. The encoder never finished, the drain thread timed out. The 100-loop test
caught it on loop 0. End-of-stream is now sent by the stopping thread with retries.

## 2026-11-04: dropping audio to avoid blocking leaves holes

`dequeueInputBuffer(20 ms)` then drop meant 3 s of holes in 24 s on the emulator. AudioRecord buffers
200 ms, so waiting up to 100 ms costs nothing. Drops are now counted in `RecorderReport`.

## 2026-11-04: fragmented MP4 has no duration in its header

MediaMetadataRetriever said 0:00 and MediaExtractor could not seek to the end. Moot after the switch
to MediaMuxer, but check the length shown in the UI against ffprobe after any container change.

## 2026-11-04: `connectedAndroidTest` uninstalls the app, with its recordings

Pull any evidence file before running instrumented tests.

## 2026-11-04: a dash scan that contains dashes fails itself

The first version of `NoDashTest` wrote the banned characters as literals, so the scan flagged
its own source. Build the characters from code points (`0x2014.toChar()`). Declare the scanned files
as Gradle test inputs, or a cached pass hides a new dash.

## 2026-11-04: a KDoc comment cannot contain a star followed by a slash

A path like `res/values*/` inside `/** */` closes the comment early and breaks the build.
