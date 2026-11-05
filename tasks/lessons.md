# Lessons

Mistakes worth not repeating. Newest first.

## 2026-11-06: a paused preview that "redraws" can be showing the wrong frame

Handing `CompositionPlayer` its surface again drew a later frame each time while `currentPosition` and the label stayed
put: 30 panels moved the picture 3.1 s at 0:00. A seek to the same place draws nothing, and redrawing without the
handover, or on a timer, sometimes left it black. What holds: on the surface's own `surfaceChanged`, hand it over and then
`setComposition` at the playhead. To check, compare the screen with frames cut from the source, and read the match error:
my first probe compared against an uncropped frame and its answers meant nothing.

## 2026-11-04: a media test with round numbers hides rounding crashes

Every ramp test used lengths that divide evenly, so a truncated boundary never landed on itself. A 4 second clip split
in 12 did, and took the app down. Use awkward lengths in media tests. Throws from Media3 on a handler thread end the
process, so wrap the call that starts work.

## 2026-11-04: never count frames out of an encoder surface

A drawing surface may skip frames when fed quickly. Assigning times by counting made reversed clips a third short with
no error. Feed buffers with an explicit time. A test comparing only gaps passed for the frames that survived: compare the
frame count and the pictures as well.

## 2026-11-04: read one pixel of a moving test pattern and you will chase a false bug

A corner pixel went from magenta to white and looked like a colour conversion bug. The pattern moves. Compare the mirrored
frame, across the frame, in all three colours.

## 2026-11-04: a permission or field declared is not a thing done

The export wake lock had a permission, a field and a release, and nothing ever took it. A long patch script matches on text:
print what missed, and check afterwards that each change landed.

## 2026-11-04: the two-track preview player never redraws after a paused seek

Media3 1.10.1 `CompositionPlayer` with `MultipleInputVideoGraph` (needed for cross-fades) moved its
position on a paused seek and drew nothing. The single-input player draws the new frame. It also sends
nothing to an ImageReader, so a test that reads frames off it proves nothing about it. Measure with
the single-input player, and check the two-track one in the real PlayerView.

## 2026-11-04: export tests passed while preview and long edits failed

Export does not need `setDurationUs`, the preview player does (it crashed on open). A three-clip test
kept track 0 alive to the end and hid that Media3 takes the video length from track 0 (a real two-clip
export lost its second clip). Test the preview separately, and test the shapes people make: two clips
and one fade is the most common edit there is.

## 2026-11-04: an emulator started with -no-audio cannot play video

Media3 drives playback from the audio clock. With no audio output the preview never starts. Start the
emulator with audio before concluding playback is broken.

## 2026-11-04: a permission silently adds a required hardware feature

Asking for CAMERA made Play treat `android.hardware.camera` as required, which would hide Zella from
phones without a camera even though screen recording works there. Declaring `camera.any` optional
was not enough; `android.hardware.camera` itself must be declared `required="false"`. Check
`aapt dump badging` on the release APK after adding any permission, and read the implied features.

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

## 2026-11-03: vendored code breaks repo-wide scans

Adding `third_party/whisper.cpp` put upstream dashes into the `NoDashTest` scan and `main` went red,
and the commit was pushed anyway. Scans skip `third_party` now. Run the full test task before every
push, and exclude any new vendored folder from the house-rule scans in the same commit.

## 2026-11-04: Media3 calls queueEndOfStream over and over

`AudioProcessingPipeline` calls `queueEndOfStream()` on a processor once per pass until the one before
it has drained, and `queueInput` again with an empty buffer. A processor that writes its tail in
`onQueueEndOfStream` without a guard produces audio forever: a 10 s export grew past 139 MB and never
finished. An empty `queueInput` calling `replaceOutputBuffer(0)` also throws the tail away. Guard both.
Any stateful `BaseAudioProcessor` needs the same two lines.

## 2026-11-04: measure loudness the way the file carries it

BS.1770 adds the channels up, and the export is stereo. Measuring a mono mix read 3 dB low and every
video came out 3 dB too loud. A comment saying "the measurement holds at the resampled rate" sat right
on top of the bug. Only reading the exported file showed it.

## 2026-11-04: read the iPhone's numbers before writing your own

Ducking was written from memory at 30% with 0.2 s ramps. The iPhone uses 18%, 0.12 s, an opening at
1.25 times and a 1.5 s fade, and its rule for when music repeats is tied to the same flag. The code
compiled, the unit test passed, and it was wrong. Read the Swift first, and hold the port to a fixture.

## 2026-11-04: look at the picture

Blur drew faint dark boxes (a wrong row layout and straight colour instead of premultiplied) and every
pixel-count assertion passed, because a dark box is also "different pixels". A contact sheet showed it
in one glance. For anything visual, write the sheet and open it.

## 2026-11-04: compare like with like

The clean-up test said the voice moved 44 ms. It had compared a 44.1 kHz export with a 48 kHz one sample
by sample. The export rate follows the first sound in the mix, so check it before comparing.

## 2026-11-04: do not run a whole timeline through Media3's two-track compositor

Three faults in a row, each timing dependent. Its 1 by 1 gap frames rebuild the texture pool under
queued frames, so pictures are drawn 2 to 4 frames ahead of their timestamps (burning the time into the
frame before and after the compositor showed it in one picture). A track whose first item lacks a kind
of media it declares makes Media3 invent a silent track and crash if the other sequence has not started
(SequenceAssetLoader:343). And black frames still appeared at hard cuts, more under load, so a real phone
would have behaved differently again. Each transition is now rendered on its own, two inputs and nothing
else, and the main video is one track. Timing went from about 30 ms to 4 ms, and the preview no longer
needs the multi-input graph or its still-frame workaround. After the second bug of one kind in a
foreign component, stop patching and change the shape of the problem.

## 2026-11-04: read the POM before adding a dependency

MediaPipe tasks-vision crashed the process with SIGILL on the emulator, and `tasks-core` depends on
Google's datatransport libraries. Both would have shipped. Android's own FaceDetector found the test
face correctly with no dependency at all. Try what the platform already has first.

## 2026-11-04: word times from whisper.cpp need the aligner and a reality check

Without DTW, words stretch across pauses ("Yes" from 0.00 to 5.24 s). That silently disabled silence
cutting, because the words protected the pauses, and misplaced captions. With DTW and trimming words to
where there is sound, the same take lost 15 pauses and 10.2 s. A green test on sample speech with no
pauses hid this for a whole phase.

## 2026-11-04: loudness belongs to the voice, not the mix

A gain on the whole composition lifted the music with the voice. Measured 3 dB over target, and the
balance was wrong. Apply it per clip, ahead of the limiter, and correct once against the measured result.

## 2026-11-04: look at one exported frame after every render change

Every test passed while a Make it Viral export was black for seconds at a time, because nothing checked
the picture was there. One PNG showed it. There is now a black frame scan in the one-tap tests. A receipt
also claimed a sound that the density cap had removed: read receipts from the finished project.

## 2026-11-04: Media3's GlProgram overwrites array uniforms

`bindAttributesAndUniforms` writes its own zeros to the first element of any array uniform, so a blur rect
set before it came out empty and the blur silently did nothing. Set arrays after binding. The OCR gate caught it.

## 2026-11-04: copy bundled data through a temporary name

Reinstalling during a copy left a 0 byte language file, and the reader refused to start from then on. A person
killing the app on first use would hit the same thing, forever. Write to `.part`, then rename.

## 2026-11-04: depth tools are hidden, not refused

I wired keyframes, masks and ramps into the export gate because the brief said Pro. The iPhone's law says the
opposite and for a good reason: a project that uses them still exports on every tier, so Pro Week ending can
never hold anyone's finished work back. Read the iPhone's gate before adding a line to this one.

## 2026-11-04: a shader value set but not used is a crash

Media3's GlProgram throws on a uniform the compiler optimised out. The message only says frame processing failed.

## 2026-11-04: a look stores effects that are already baked

`applyLook` copies the look's `special` onto the clip, so reading it back and applying it again would make Film
Noir black and white twice. Apply only what differs from the look.

## 2026-11-04: read models on real footage with a variant sheet before tuning

Holes in faces and phantom people looked like a weak model. They were my input and output handling. A sheet of
four variants on two real clips settled it in one run.

## 2026-11-04: drive the real screens before calling a feature done

Every render test passed while the paused preview was black after any panel opened. Only a tour of the real app
showed it. A seek, a reset composition and a clear shutter all failed; re-attaching the surface worked. A result
that lands below the fold also read as a dead button.

## 2026-11-04: never chain a commit after a check with a semicolon

A style failure went into a commit that way. It was caught and amended before pushing. Test the exit code first.
