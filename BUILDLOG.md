# BUILDLOG

Read this and tasks/lessons.md at the start of every session.

## Phase status

| Phase | Status |
| --- | --- |
| 0 Foundation | Built, exit gate PARTLY met (see below). Stopped for review. |
| 1 Capture | Screen and camera capture built and measured on the emulator. Exit gate NOT passed (see below). |
| 2 Timeline and editor | Mostly built. Model, edits, undo, store and iPhone parity done; editor UI works on the emulator. Open items below. |
| 3 Export and limits | Mostly built. Export service, gate at the boundary, gallery save, share. Open items below. |
| 4 to 9 | Not started |

## Phase 0 evidence (2026-11-04)

- `./gradlew test lint ktlintCheck assembleDebug`: BUILD SUCCESSFUL. 12 unit tests, 0 failures
  (10 in zella-core: PremiumGate x10 cases plus dash scan; 2 in app: jargon scan).
  After the last build.gradle.kts edit only `:zella-core:test ktlintCheck` was re-run, not lint.
- The dash scan was proven to fail: a planted em dash in docs made `NoDashTest` fail, and the
  build passed again once it was removed. This also exposed that Gradle cached the pass, so
  the scanned files are now declared as test inputs.
- App launched on an Android 35 arm64 EMULATOR (not a phone). Screenshot:
  `docs/evidence/phase0-home.png`. It shows the Pro Week chip, three cards and the status line.
  `am start -W` reported "timeout" on first cold start under software rendering; the screen did
  render. No crash in the AndroidRuntime log. No timing is claimed.
- Manifest requests no permissions of its own.

## NOT met

- The gate says "launches on a physical phone". No phone was available. Not verified on hardware.
- CI workflow is written but has never run (no remote).
- Nothing is committed. Nothing is tagged.

## Deviations and notes

- minSdk 29, JDK 17, AGP 8.11.1, Kotlin 2.1.21, Gradle wrapper 8.14.3.
- `zella-render` and `zella-ai` are empty JVM placeholders.
- Product ids and the 120 s free cap were read from the iPhone `PremiumGate.swift`.
- ktlint is used. detekt is not added yet.
- Home screen Record/Edit/Ship cards are not tappable yet. Review before any beta.
- Not yet read: the iPhone .zella schema (needed Phase 2), the website docs the brief cites.
- I booted a second emulator `zella_p0` (copied from your `posora_test` config). Your own was untouched.

## Phase 1 evidence (2026-11-04, emulator only)

All on `zella_p0`: Android 15 emulator, arm64, software GPU, `-no-audio` (its microphone records
silence). Nothing in this section was run on a phone.

| Check | Result | How |
| --- | --- | --- |
| Unit tests | 27 core + 4 app, 0 failures | `./gradlew test` |
| Lint, ktlint, debug and release build | pass | `./gradlew lint ktlintCheck assembleDebug assembleRelease` |
| Stop sequence, 100 loops with pause/resume | pass, 0 drain timeouts, every file has video and audio | `StopSequenceLoopTest`, 71.5 s |
| Track alignment, first frame 1 s late, mid pause | pass, video and audio ends 47 ms apart | `TrackAlignmentTest`; shown to FAIL with the fix switched off |
| AAC encoder delay | 42.645 ms, identical over 3 runs | `AacDelayProbe` |
| Screen recording via the real UI, with pause | plays; 22.1 s video, 22.6 s audio for 30 s wall minus 8 s pause window; 0 audio holes; saved in 309 ms | ffprobe, `ffmpeg -c copy` |
| Forced kill mid-recording (`am force-stop` at +14 s) | rebuilt on next launch in 686 ms, 920 samples, 0 torn bytes, listed as "Kept after Zella closed unexpectedly", 0:14 | `docs/evidence/phase1-recovered.png` |
| A/V sync, phone sound path, 60 s | 30 pairs, median +0.6 ms, worst -34.8 ms (last pair, while pressing Back), first 29 within -18.3 to +8.3 ms | `docs/evidence/phase1-sync-emulator-phone-sound.txt` |
| Sync tool itself | reads +80.0 and +10.0 ms exactly on synthetic clips; exits 1 above the limit | `tools/measure-av-sync.py` |
| Release APK | no sync test screen; asks for exactly the 5 permissions recording uses | `aapt dump badging` |

## Phase 1 camera evidence (2026-11-04, emulator only)

Emulator cameras: back (sensor 90, test house scene), front (sensor 270, added to my AVD copy).
Both report boot-time (`REALTIME`) frame stamps. The emulator offers up to 1280x720 on the back
camera and 1920x1080 on the front, at a fixed 30 fps.

| Check | Result |
| --- | --- |
| Camera stop sequence, 100 loops with pause, mic on | pass, 0 drain timeouts, every file has video, audio and rotation 90, first frame 0 to 3 s after Start (`CameraStopLoopTest`, 125.8 s) |
| Portrait, back camera | rotation 90 stored, plays upright, 29.2 fps, 0 frame steps under 20 ms, 0 audio holes |
| Held sideways (left edge down), back camera | rotation 0 stored, plays upright |
| Front camera, portrait | rotation 270 stored, 1920x1080, plays upright; preview mirrored, file not mirrored (`docs/evidence/phase1-camera-front-preview-vs-file.png`) |
| Leave the app while recording, come back | recording continues; reopens on the camera screen with Pause and Stop; worst video freeze 122 ms leaving, 68 ms returning; 0 audio holes |
| Forced kill while recording (`am force-stop` at +19 s) | rebuilt on next launch in 605 ms, 18 s, rotation kept, decodes fully |
| Unit tests | 39 runs, 0 failures (includes `CameraMathTest`: rotation rules and boot-time clock conversion) |

Camera not verified anywhere:
- Any real phone. Real cameras differ in sizes, fps ranges, stream sharing support and timestamp source.
- Boot-time conversion on a phone that has actually slept (the emulator never sleeps; the rule is unit-tested only).
- Camera A/V sync: the emulator mic records silence. On a phone, point the camera at a second screen running the sync test screen (or film a clap) and run `tools/measure-av-sync.py`.
- Holding the phone upside down (180).
- Another app taking the camera mid-recording (the "camera stopped" path exists, untested).
- 4K camera capture (Pro): not built. Capture is capped at 1080p.
- A phone with no camera: the release APK now marks both camera features optional and the home screen hides "Record with camera" when no camera is found (confirmed shown on the emulator, which has cameras). The no-camera case itself was not run.
- The remaining 2 to 4 dropped frames when switching apps may be emulator load. Measure on a phone.

## Phase 2 and 3 evidence (2026-11-04, emulator and Mac only)

| Check | Result |
| --- | --- |
| Reads iPhone projects | 3 fixtures written by the iPhone's own ZellaCore decode; duration 12.0 s and clip starts 0, 4, 6 match what the iPhone printed (`ConformanceTest`) |
| Round trip keeps unknown fields | the iPhone fixture re-encodes to the same JSON, key for key (`ConformanceTest`) |
| Edits match the iPhone | a 12-step edit script run by the iPhone's ProjectEditing and by Android lands on the same project after every step, all collections, to 1e-9 s (`EditParityTest`) |
| The iPhone reads Android | the iPhone decoder opened all 12 Android-written projects with identical durations (`tools/ios-conformance`, run by hand on a Mac) |
| Store | atomic save, snapshots, heal from snapshot, collapse guard, newer-format refusal, zip-slip refusal, recoverable delete (`ProjectStoreTest`) |
| Export matches the timeline | 5,013 ms against 5,000 ms; two-clip fade 3,000 ms against 3,000 ms (`ExportRenderTest`) |
| The fade really renders | pixel at mid-fade `#758804`, a red and green mix (`ExportRenderTest`) |
| Gate at the boundary | a 4K render on a 1080p permit is refused and writes nothing (`ExportRenderTest`) |
| Preview loads | the real preview player becomes ready with the timeline's length (`PreviewPlayerTest`) |
| Real UI flow | split, fade, double speed, undo, redo, export, save to gallery, all by taps; autosave wrote the edits; the saved video plays and has both tracks |

### Open in Phase 2 and 3

- **A paused seek does not update the preview picture.** Seeks reach the player (logged), the playhead
  moves, the frame stays. Playback works. Not fixed. Check `CompositionPlayer` seeking from the ENDED
  state and while paused; write a test that reads the rendered frame after a paused seek.
- Playback on the emulator needs audio output: started with `-no-audio`, the preview never plays.
  Phones always have audio, but an output-less device would freeze the same way.
- Reverse and Rewind render a backwards copy (`ReverseRenderer`), never run on a device yet.
- Timeline scrub and playback frame-rate budgets: not measured (needs a phone).
- Export on real encoders (Snapdragon, Dimensity, Exynos, Tensor): not run. Fallback is on.
- In Pro Week the export screen still labels 4K "needs Pro" although it is allowed. Fix in Phase 8.
- Tapping Edit on a recording makes a new project every time.
- The iPhone's reorder does not move picture-in-picture clips; Android does (see `EditParityTest`).
  Worth fixing on the iPhone side.
- How a `.zella` gets from Android into the iPhone app: the iPhone code has no zip import that I
  found. UNVERIFIED.

## Phase 1 exit gate: NOT passed

| Gate item | Status |
| --- | --- |
| Sync within 40 ms on three device classes | Emulator only, phone sound path only. Mic path not measured. No phone. |
| Forced kill recovers a playable file | Done on the emulator. Not on a phone, not across OEM battery killers. |
| 30 minute recording stays stable | Not run. |

## Phase 1 not built yet

- Rotation during a screen recording: capture size is fixed at start; content will be letterboxed or squeezed. Not handled.
- Phone call, screen off, low storage during recording: code paths exist (storage watch every 10 s, projection stop callback), none tested.
- Device audio from apps that opt out: the "silent the whole time" message exists, untested on a real app.
- Screen video is variable frame rate (frames only when the screen changes). Fine for playback; export (Phase 3) must produce constant frame rate.
- `REPEAT_PREVIOUS_FRAME_AFTER` does not appear to work on the emulator's software encoder (4.7 s gaps on a still screen). Check on phones.
- Android 10 path (`getRealMetrics`) compiled but never run.

## How to run the phone checks (for the human with devices)

1. `./gradlew installDebug`, open "Zella sync test" (debug only), start Zella screen recording with "Record my voice" on, speaker volume up, for 60 s.
2. `adb pull` the MP4 from `Android/data/com.zellahq.app/files/recordings/`, run `tools/measure-av-sync.py FILE`. Repeat with "Record phone sound".
3. `ANDROID_SERIAL=<phone> ./gradlew connectedDebugAndroidTest` (100 loops, alignment, AAC delay). Note the AAC delay per phone here.
4. Record, then `adb shell am force-stop com.zellahq.app`, relaunch, check the list and play the file.
5. Record 30 minutes with the screen off for part of it.

## Human TODOs

See docs/DECISIONS-NEEDED.md.
