# Zella for Android: working notes for agents

Read `BUILDLOG.md` (status, evidence, open items) and `tasks/lessons.md` (mistakes not to repeat)
before changing anything. `docs/ADR-001-architecture.md`, `docs/ZELLA_FORMAT.md` and
`docs/STOP_SEQUENCE.md` hold the decisions that are easy to break.

## What this is

The Android sibling of Zella for iPhone and Mac: an on-device screen recorder and AI video editor
for beginners. Kotlin, Jetpack Compose, Media3, minSdk 29. Modules: `zella-core` (pure JVM: project
model, edits, undo, store, pricing gate), `zella-ui` (design tokens), `app` (everything Android).

## Rules that are not negotiable

- **On device.** Recording, editing, AI and export run on the phone. The only network paths allowed,
  each opt-in and labelled: the Assistant (user's own key, prompt plus a short text summary, never
  video), stock B-roll keywords to Pexels or Pixabay (user's key), one-time caption model downloads.
  Never write "nothing is uploaded" on a screen that touches one of them (`BeginnerCopyTest` checks).
- **Free is permanent.** Complete editor and all AI free, watermark-free, exports up to 2 minutes at
  up to 1080p. Pro ($89 once) lifts length, 4K, 60 fps, transparent export, custom bitrate,
  multi-aspect queue, saved looks, and shows the depth tools. Founder ($299 once) adds supporter perks. Pro Week:
  every install gets 7 days of Pro, quietly. No watermark, no fake urgency, no countdowns, ever.
- **The gate is enforced at the render boundary.** Only `PremiumGate.authorize` makes an
  `ExportPermit`; `ExportService` asks the gate itself. Never trust the UI for this.
- **For beginners.** Every control is a labelled word. No jargon in copy (`BeginnerCopyTest` bans a
  list). Say what a thing does, then what it does not do. Every number in copy is checked in code.
- **No em or en dashes anywhere** (code, copy, docs, commits). `NoDashTest` scans the repo.
- **Never claim an untested fix is done.** A fix is done when a measured artifact from the real flow
  proves it. Say plainly what was not verified. A green unit test is not proof of a media fix.
- **The `.zella` format is a contract** with the iPhone and Mac apps. Keep unknown fields, write what
  Swift writes (`docs/ZELLA_FORMAT.md`). `ConformanceTest` and `EditParityTest` use real iPhone output.
- **Every edit moves everything pinned to the content** (captions, titles, zooms, music, sounds,
  callouts, picture-in-picture, voice windows, reframe keys). This is the most repeated bug in the
  iPhone and Mac history.

## Commands

```bash
./gradlew test lint ktlintCheck assembleDebug         # what CI runs; must stay green
./gradlew :zella-core:test                           # fast: model, edits, iPhone parity, store
ANDROID_SERIAL=<device> ./gradlew connectedDebugAndroidTest   # needs a phone or emulator
```

`connectedDebugAndroidTest` uninstalls the app afterwards, with its data. Pull evidence files first.

## In a cloud session

`.claude/settings.json` runs `scripts/cloud-setup.sh` at start (Java 17, Android SDK 36). A cloud
machine has no emulator and no phone, so it can build and run unit tests only. Anything touching
recording, playback, export or the camera must be left marked "not verified on a device" in
`BUILDLOG.md`, with the instrumented test that will check it. The iPhone conformance tools
(`tools/ios-conformance/`) need a Mac with the iPhone checkout and cannot run in the cloud.

## Where the work stands

Phases 0 to 9 are built and measured on an emulator. No phone has been used, no real Assistant provider or stock
site has been called, and billing has never reached real Google Play. `BUILDLOG.md` holds the evidence and the
known differences; `docs/store/RELEASE_CHECKLIST.md` holds what only the owner can do.

Depth tools (keyframes, masks, speed curves) are shown to Pro and always export on every tier: the iPhone's law.
