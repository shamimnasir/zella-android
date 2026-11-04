# Release checklist

Publishing decisions are the owner's. They need the owner's Google account, signing key and identity.

State on 2026-11-04: version 1 (0.0.1) is on the internal testing track of the Nasir Uddin Shamim developer account,
list "Zella internal". It has not been reviewed and is not public. The Play Store listing is a text-only draft.

## Only the owner can do these

- [x] Create the app in Play Console, package `com.zellahq.app`.
- [x] Create the signing key (kept in `~/.zella-android-keys/`, back it up). Put `keystore.properties` beside `settings.gradle.kts` (`storeFile`, `storePassword`,
      `keyAlias`, `keyPassword`). It is ignored by git. Never commit it. Use Play App Signing.
- [x] Set up the Google Payments merchant account.
- [x] Create two one-time products: `zella_pro_lifetime` ($89.00), `zella_founder_lifetime` ($299.00), both active, 174
      regions, purchase option `buy-once`, backwards compatible (`PlayStore` reads `oneTimePurchaseOfferDetails`). Play's
      bulk price rounds to .99 and adds VAT where prices include tax, so only the US price is exact. Check other regions.
- [x] Copy the licence public key from Play Console into `local.properties` as `zella.playKey=...`. **A release built
      without it can never unlock a purchase, by design.**
- [x] Publish the privacy policy at a public address: https://zellahq.com/legal/privacy-android/
- [x] Decide the Data safety question in `DATA_SAFETY.md`: option 2, declare it. The form itself is not filled in yet.
- [x] App content forms saved on 2026-11-05 except Foreground services. Nothing has been sent for review. Sign-in details
      ticks "full access", with a note saying the 3 Founder colour grades are the one exception and Pro lasts 7 days.
- [ ] Foreground services form: blank, every task needs a video link. Tick: Camera "Other", Data sync "Importing, exporting",
      Media processing "Media transcoding", Media projection "Media and content projection, streaming", Microphone
      "Background audio input". Check each against the form's wording first.
- [ ] Record the short video Play wants for the screen recording permission, on a real phone.
- [x] Build `./gradlew :app:bundleRelease`, upload to the internal track. Closed testing is still to do.
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
