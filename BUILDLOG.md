# BUILDLOG

Read this and tasks/lessons.md at the start of every session.

## Phase status

| Phase | Status |
| --- | --- |
| 0 Foundation | Built, exit gate PARTLY met (see below). Stopped for review. |
| 1 Capture | Screen and camera capture built and measured on the emulator. Exit gate NOT passed (see below). |
| 2 Timeline and editor | Mostly built. Model, edits, undo, store and iPhone parity done; editor UI works on the emulator. Open items below. |
| 3 Export and limits | Mostly built. Export service, gate at the boundary, gallery save, share. Open items below. |
| 4 Captions | Built and measured on the emulator. Gate NOT passed (needs a phone and a real Bangla clip). |
| 5 Looks, sound, text | Built and measured on the emulator. Open items below. |
| 6 One-tap AI | Built and measured on the emulator. Open items below. |
| 7 Background, B-roll, Assistant, blur | In progress. Callouts, blur and the Assistant are built and measured on the emulator. |
| 8 and 9 | Not started |

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

- Paused seek: FIXED for projects without fades (single-input player draws the new frame; pixel-checked
  in `PausedSeekTest`). Projects with a fade need the two-track player, which never redraws after a paused
  seek, so the editor shows a still read from the file while paused (checked in the UI: the frames shown
  at 1.78 s and 3.51 s were the right ones). The still is the clip under the playhead, without the blend,
  zoom or later looks. Playback always goes through the real compositor.
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

## Cloud session note (2026-11-03)

The dash scan now skips `third_party` (whisper.cpp uses dashes upstream); that was what broke `main`
at `c52c536`. In this cloud environment the network policy blocks `dl.google.com`, which serves the
Android Gradle plugin, every AndroidX library and the Android SDK (maven.google.com redirects there).
So `./gradlew test lint ktlintCheck assembleDebug` cannot run here. Only `zella-core` was checked:
52 unit tests passed through a scratch build without the Android plugin, and a separate exact scan
found no dashes outside `third_party`. CI on GitHub has to confirm the full build.

## Phase 4 evidence (2026-11-04, emulator only)

| Check | Result |
| --- | --- |
| Bundled model | whisper base multilingual, 5-bit, 59.7 MB, SHA-256 pinned, fetched at build time (`fetchCaptionModel`), not in git |
| English speech (macOS voice, known sentence, 6.4 s) | 20 words, 19 right ("Zella makes" heard as "elemakes"); 8.2 s on the emulator CPU (`CaptionEngineTest`) |
| Language detection | English at 0.9975 |
| Speed | words of a 2x clip end by 3.3 s on the timeline, as they should |
| Captions are drawn | 286 sampled pixels change in the caption band of an export (`CaptionEngineTest`) |
| Real UI | Captions, English, Make captions: "Captions made: 20 words"; styles, hide, edit, remove shown; caption visible in the preview |
| Grouping and placement | max 3 words, pause and sentence breaks incl. the Bangla danda, trim and speed mapping, reversed clips skipped, cue editing (`CaptionBuilderTest`) |
| Fonts | PostScript names read from real fonts, ArialMT and Helvetica collection (`FontNamesTest`); import checks type and the 160 MB cap |
| Model download | against a real HTTP server: 3 parts, checksums, a wrong checksum refused, unreachable list hidden, 59.7 MB in 4.9 s (`ModelDownloaderTest`) |

### Open in Phase 4

- **Gate not passed.** No real Bangla clip and no phone. The base model will be weak at Bangla; the
  iPhone found on-device Bangla transcription loops and offers English translation instead ("Write the
  captions in English" is here too). Measure on a phone with a real Bangla recording before claiming it.
- **DECISION: host Android models.** Upload ggml files and a manifest to
  `https://models.zellahq.com/android/manifest.json` (format in `ModelDownloader.parse`). Until then
  the "Better captions" download stays hidden. Suggested: ggml small or large-v3-turbo q5.
- True mid-file resume (HTTP 206) is coded but the test server re-sent whole parts.
- Only a baseline ARMv8 whisper build; an ARMv8.2 build would be faster on newer phones. Measure first.
- Fonts travel in `.zella` packages Android writes; fonts in packages from the iPhone are not yet
  imported into the font library on open.
- Caption translation between other languages (not English) is not offered.

## Phase 5 evidence (2026-11-04, emulator and Mac only)

Everything measured from the file a real export wrote, on `zella_p0`. Numbers in
`docs/evidence/phase5-sound-render.txt`. Nothing here ran on a phone.

| Check | Result | How |
| --- | --- | --- |
| 95 looks against the iPhone's own render | worst colour error 0.47 (Delta E) | `LookTest`, `LookRenderTest` |
| Text animation engine against the iPhone | 142,260 values over 85 presets and 161 phases, all within 1e-7 | `MotionTest`, `tools/ios-conformance/motion-dump.swift` |
| All 24 title styles, Latin and Bangla | every one draws; looked at by eye | `TitleRenderTest`, `docs/evidence/phase5-title-styles.png` |
| All 85 animations | 155 phases draw and move; every In and Out settles on exactly the plain title's pixels | `TitleRenderTest`, `docs/evidence/phase5-title-motion.png` |
| Title in a real export, with an animation | yellow pixels: none before, 345 arriving, 1927 settled, none after | `titlesComeOutOfARealExport`, `phase5-title-export-frame.png` |
| Even out loudness | normal speech -19.30 to -14.25 LUFS. Very quiet speech -31.34 to -19.33 LUFS: the boost is capped at +12 dB | `SoundRenderTest` |
| Music steps back for the voice | dip measured -14.9 dB against the iPhone's 18% (-14.89 dB); opening +1.9 dB (1.25 times); fades out | `SoundRenderTest`; ducked spans match the iPhone in 12 layouts, `VoiceTest` |
| Music repeats, plays once, or stops at a trim | the iPhone's rule: repeats while it steps back, once when not, cut by a stored length; silence afterwards measured | `SoundRenderTest` |
| Sound effect against the picture | +0.3 ms from the cut; an effect at the very end does not stretch the video (video 4018 ms, audio 3993 ms, timeline 4000 ms) | `SoundRenderTest` |
| Voice clean-up, hiss against the voice | -17.0 to -22.3 dB through the export; the voice stays within 0.83 ms of where it was | `SoundRenderTest`, `DenoiseHissTest` |

| All 25 transitions | each starts exactly on the outgoing clip and ends exactly on the incoming one, with the outgoing clip on either track; middles looked at by eye | `TransitionRenderTest`, `docs/evidence/phase5-transitions.png` |
| Frames against the timeline | every one of 23 cuts within one frame on a timeline with no transitions; with transitions (rendered ahead, see below) every colour change 3 ms or 4 ms from where it should be, no black frames, final length 29.11 s against 29.10 s |

Sound found by this test and fixed: an export with clean-up on never finished (a 10 s clip grew past
139 MB), loudness read 3 dB low so every video came out 3 dB hot, and the wet and dry voice were 10 ms
apart. See `tasks/lessons.md`.

### Open in Phase 5

- Voice clean-up is RNNoise. The iPhone prefers DeepFilterNet 3 or Apple's isolation engine, and its
  own measurements rank RNNoise below both. The hiss gate passes here on the iPhone's voice fixture
  with white noise added. Not tested on a real noisy room.
- "Polish voice" (the five voicings, compression and limiter on the iPhone) is not built yet.
- A title drawn behind the person needs the cutout from Phase 7. Until then such a title is left out
  of the render rather than drawn over the person.
- Text card photos and the picture-in-picture, callout and sticker collections are kept in the file
  and move with edits, but Android does not draw them yet.
- Loudness on a phone speaker, and hearing any of this, has not been done. It was measured, not heard.

## Phase 6 evidence (2026-11-04, emulator and Mac only)

Run with the real speech engine and exported, on a 34 s clip of the iPhone's voice fixture with two
inserted 2 s pauses. Nothing here ran on a phone, and the sample is one voice, not the battery of 20
clips the brief asks for.

| Gate | Result | How |
| --- | --- | --- |
| The plan shown is the plan run | Both lists come from `PolishStep.plan`; the run reports each step it starts and the test compares | `AutoPolishRunTest`, `AutoEditTest` |
| A second run stacks nothing | titles, transitions, zooms and music identical after a second run, on device and on 5 lengths in unit tests. A second run does find more quiet to cut (22.8 to 20.4 s), because it listens again | `AutoPolishRunTest`, `AutoEditTest` |
| Effect density under the cap | 0.308 and 0.344 per second measured; capped at 0.35 by `Polish.capDensity`, 7 lengths from 8 s to 20 min | `AutoPolishRunTest`, `AutoEditTest` |
| Decisions match the iPhone | silence detector, filler rules, witness words, interrupts, emphasis zooms, sounds under cuts, zoom scale and music mood all compared with the iPhone's own code on generated inputs | `AutoEditTest`, `tools/ios-conformance/plans-main.swift` |
| Pauses gone from the file | longest pause 4.56 s before, 0.39 s after; 15 pauses, 10.2 s | `AutoPolishRunTest` |
| Loudness | -14.25 LUFS from the exported file, with voice clean-up and the studio chain on | `AutoPolishRunTest` |
| Make Vertical keeps the face in frame | face at x = 0.506 of the vertical export; a plain centre crop has no face in it at all | `makeVerticalKeepsTheFaceInTheFrame`, needs `tools/push-test-media.sh` |

### Where this differs from the iPhone, on purpose

- Dissolves: Auto-Polish on the iPhone puts one on every join (`autoPolishBody`), while `makeReel` on the
  same phone caps them at about eight after a 100 minute take froze the editor. The brief says about
  eight of the widest spaced, so Android does that, and the density budget can lower it further.
- Emphasis zooms: Android replaces its own on a re-run. The iPhone keeps them as "occupied", which lets
  last run's runners-up land in the gaps, so its count grows with each tap. Reported below.
- Words: the iPhone transcribes twice per run. Android transcribes once and moves the words with the edits.
- Emphasis: the iPhone uses Apple's part-of-speech tagger. Android has no tagger on the device and uses
  the iPhone's stop words plus a list of connecting words, with the same ranking and cap. Not the same list of chosen words.
- Faces: Android's built-in detector, not Apple Vision. It found the test face. Not tested on real footage.

### Open in Phase 6

- Voice clean-up is RNNoise, with the presence and air bands held back the way the iPhone does for that
  engine. No isolation engine exists on Android.
- The battery of 20 sample clips does not exist yet. Density and re-run gates were measured on one voice and on generated data.
- The one-tap screens have been built but only exercised through tests, not by a person.

## Phase 7 evidence (2026-11-04, emulator and Mac only)

| Check | Result |
| --- | --- |
| Blur gate | `CalloutRenderTest`: a page with an access token is exported. The text reader reads the whole token. The scan finds it and places one blur, reported as `ghp_••• (36 chars)`. Export again: at 0.2, 2.0 and 3.8 s the reader finds `Access token:` and nothing after it, and the rest of the page stays readable. Same for pixelate. |
| A box lands where it says | a blur at top-left 0.1, 0.1 over stripes: contrast 5 inside, 251 at the mirrored spot and at the right |
| All 19 callout kinds | every one changes the export; contact sheet checked by eye |
| Matching and planning against the iPhone | `SensitiveTextTest`: 132 match rows (22 lines, 6 settings) and 11 plan scenarios equal `tools/ios-conformance/sensitive.swift` run on the iPhone's own file |
| The Assistant's wire format | `ClientsTest`: all six providers, key in a header and never the body or address, HTTPS only, plain words per failure |
| The Assistant sends no video | `AssistantTest`: 11 tool rounds on a real editor against a pretend provider. Largest request 21.5 KB, all tool schemas and text. No path on the phone, no `file:`, no 400-character blob, no image block, no key in a body. The edits really landed in the project. Export asked first, and a "no" stopped it |
| Keys | `AssistantTest`: read back through Android Keystore, absent from every shared_prefs file on disk, gone after remove. App backup is off |

| Cutout on real people | `CutoutRenderTest`: Tears of Steel (three people, moving) and a still portrait, contact sheets checked by eye. Faces whole, no phantom shapes. Getting there took a variant sheet: the model wants a letterboxed frame at -1 to 1, and gives raw scores that need a softmax |
| Edge stability | a still person under camera noise, share of edge pixels that change by over 20 percent between frames, median: 6.14 with no hold, 3.17 at 0.5, 2.46 at 0.75 (shipped), 2.25 at 0.9. Three people moving: 21.35 with no hold, 16.86 at 0.75, most of which is real movement |
| Backgrounds | blur, colour, black, your photo, all 40 of the iPhone's procedural backgrounds with its exact values. Moving ones do change over time. On the emulator 180 frames took 22 s to work out |
| Word cards and stock | `BRollRenderTest`: cards land on their words, everything after moves up, the speaker comes back. A stand-in stock site saw only `mountain`, `ocean` and the key, over HTTPS, no caption line, no file name |

| Depth tools | `DepthRenderTest` through real exports: a moved clip lands at 0.75, 0.75, uncovered space black. A keyframed square reads 0.499, 0.651, 0.799 (wanted 0.5, 0.65, 0.8). Half opacity reads 126 of 255. A mask greys the inside and leaves white outside, and flips. All 8 effects change the picture. Freeze holds a moving bar still for 2 s, then it moves on. All tested on the free plan long after Pro Week: they export, the iPhone's rule |
| Curves and punches against the iPhone | `DepthTest` equals `tools/ios-conformance/depth.swift` on Apple's own curves, tracks and beat punches |
| Picture in picture | shows where it says and only while it should; a circle leaves its corners; the main video stays under it. Its sound joins the mix (not measured yet) |
| GIF and sound only | GIF89a at 480 wide, 3.84 s, the colours came through. The sound file has one audio track. MOV is not offered: Android has no writer for it |

Not verified: no real provider has been called, since no key was available. Nothing on a phone. OCR speed
on a phone is unknown; the scan has a 75 s budget and says so when it stops early. Reading is Latin script
only (Tesseract `eng`), and the reader may miss small or fast text, which the screen says. Blend modes on
callouts are kept in the file but drawn as normal. The stock path has never reached a real site. Transparent
export is not built, since Android has no ready alpha video path; its Pro line stays in the gate unused. The
person cutout has no live camera preview yet. Colour sliders use plain formulas, which are not the same maths
as the iPhone's Core Image filters, so a project moved between them may differ slightly. A look's dark corners
and blur do not sit inside a mask's area.

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
