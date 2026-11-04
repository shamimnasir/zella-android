# Release checklist

Play submission and publishing are the owner's to do: they need the owner's Google account, signing key and
identity. Nothing here has been submitted.

## Only the owner can do these

- [ ] Create the app in Play Console, package `com.zellahq.app`.
- [ ] Create the signing key. Put `keystore.properties` beside `settings.gradle.kts` (`storeFile`, `storePassword`,
      `keyAlias`, `keyPassword`). It is ignored by git. Never commit it. Use Play App Signing.
- [ ] Create two one-time products: `zella_pro_lifetime`, `zella_founder_lifetime`. Set $89 and $299, and local prices.
- [ ] Copy the licence public key from Play Console into `local.properties` as `zella.playKey=...`. **A release built
      without it can never unlock a purchase, by design.**
- [ ] Publish the privacy policy at a public address.
- [ ] Decide the Data safety question in `DATA_SAFETY.md`.
- [ ] Record the short video Play wants for the screen recording permission, on a real phone.
- [ ] Build `./gradlew :app:bundleRelease`, upload to the internal track, then closed testing.
- [ ] Run closed testing with at least the testers and days Play currently requires for new personal accounts.
- [ ] Buy, refund and restore with a Play test account on a real phone. **Billing has never touched real Play.**
- [ ] Staged rollout at 5 percent, then watch the crash rate in Play Console for at least 3 days before going on.

## Checks on real phones, which no emulator can answer

Each has a test or a procedure ready. `KNOWN_DEVICES.md` lists what to test on.

- [ ] A/V sync on a real microphone: `tools/measure-av-sync.py`.
- [ ] Kill during recording, on a phone: `docs/STOP_SEQUENCE.md`.
- [ ] Captions in Bangla and Hindi on the owner's own clips.
- [ ] Host `https://models.zellahq.com/android/manifest.json` (404 on 2026-11-04). Until then "See better caption downloads" says none could be reached, and the listing must not promise bigger caption downloads.
- [ ] Voice clean-up and all voice effects, by ear. None has been heard.
- [ ] Music ducking and loudness, by ear on phone speakers and earphones.
- [ ] Cutout on the owner's own clips: edge quality in bright, dim and busy rooms.
- [ ] Export on a Snapdragon, a Dimensity, an Exynos and a Tensor: H.264 and HEVC.
- [ ] Heat and battery: a 10 minute export on a 4 GB phone. The app reports heat; the slowdown has not been measured.
- [ ] Reading text speed on a phone: the 75 second budget may cover less of a long video than on the emulator.
- [ ] The Assistant with a real key on each provider, and stock clips with a real key.

## Build facts on 2026-11-04

- Release APK 114 MB, bundle 103 MB (58 MB is the bundled caption model, 16 MB the person model). Under Play's limit.
- All native libraries are 16 KB aligned; `app:assembleDebug` output was checked with `llvm-readelf`.
- 64-bit only.
