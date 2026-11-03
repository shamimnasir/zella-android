# ADR-001: Zella for Android architecture

Status: proposed, Phase 0. Date: 2026-11-04.

## Decision summary

| Area | Choice | Why |
| --- | --- | --- |
| Language and UI | Kotlin 2.1, Jetpack Compose, Zella's own tokens in `zella-ui` | Material 3 is plumbing only |
| Timeline | Custom Canvas, measured | Compose may not hold the display refresh rate while dragging |
| SDK | minSdk 29, targetSdk 35, arm64-v8a only | Device audio capture needs API 29 |
| Modules | `zella-core`, `zella-render`, `zella-ai`, `zella-ui`, `app` | Mirrors ZellaKit so concepts map 1:1 |
| Camera | Camera2 directly (`CameraHub`), not CameraX. DEVIATION, see below | Orientation must be handled explicitly |
| Screen capture | MediaProjection plus foreground service | Required from Android 14 |
| Device audio | AudioPlaybackCaptureConfiguration | Some apps opt out; detect and explain |
| Encode and mux | MediaCodec and MediaMuxer, one monotonic clock | A/V sync is the classic failure |
| Preview and export | Custom GL compositor fed by MediaCodec, Media3 as fallback | Multi-layer preview is the hard problem |
| Speech to text | whisper.cpp over JNI, quantised models | Works offline from first launch |
| Segmentation | MediaPipe segmenter first, RVM-style model later | Edge halo needs temporal smoothing |
| Billing | Play Billing, two one-time products, on-device verification | No server, by promise |
| Secrets | Android Keystore | Assistant keys never leave the phone except to the chosen provider |

Product ids are fixed to match Apple: `zella_pro_lifetime`, `zella_founder_lifetime`.

## Entitlement

One object, `PremiumGate` in `zella-core`. The only way to obtain an `ExportPermit` is
`PremiumGate.authorize`, and the permit constructor is internal to the module. The exporter
will take a permit as a parameter, so the gate is enforced at the render boundary and cannot
be bypassed by UI code. The clock high-water mark stops a user winding the date back to extend
Pro Week.

## Top five risks and the prototype that retires each

Build in this order. Each prototype has a pass condition measured on hardware.

1. **A/V-synced screen plus mic capture.** Prototype: MediaProjection plus AudioRecord plus
   playback capture into MediaMuxer on one clock. Pass: clap or flash test within 40 ms on
   three device classes; forced kill leaves a playable file.
2. **Multi-layer GL preview.** Prototype: two 1080p MediaCodec decoders, a LUT, a caption
   layer, composited on one GL thread. Pass: 30 fps on a 4 GB device, scrub at refresh rate.
3. **whisper.cpp throughput on a low-end phone.** Prototype: JNI build, base model, 30 s
   windows, performance cores only. Pass: a 60 s English and a 60 s Bangla clip transcribed
   with real progress, time logged. Unknown today: whether Bangla quality holds at base size.
4. **Person segmentation quality.** Prototype: MediaPipe segmenter with temporal smoothing on
   the user's own clips. Pass: edge-stability measured before any tuning. Expect halo.
5. **Hardware encoder variability.** Prototype: export one 60 s edit on Snapdragon, Dimensity,
   Exynos and Tensor, H.264 and HEVC, with a software fallback. Pass: every device produces a
   file that plays on Android, iPhone and Mac.

## Recording stop sequence

Written in `docs/STOP_SEQUENCE.md`, frozen behind two 100-loop tests (screen pattern and camera).
Mac had deadlocks from finishing the writer before draining queues.

## Deviation: Camera2 instead of CameraX (2026-11-04)

CameraX's `VideoCapture` writes the MP4 itself. That would bypass the crash-safe `SampleJournal`,
the shared recording clock and the measured AAC delay, which together are the whole sync and
recovery story. Camera2 lets the camera feed our own encoder surface. Costs we accepted: we own the
session lifecycle (`CameraHub`), size selection and rotation rules (`CameraMath`, tested).

## Consequences

- No network code yet. Permissions are only those recording uses: microphone, camera, notifications,
  and the foreground service types for screen, microphone and camera.
- `zella-render` and `zella-ai` are empty JVM placeholders. They become Android libraries
  when they first need NDK or GL code.
- Release signing is a placeholder. Keystore files are gitignored.
