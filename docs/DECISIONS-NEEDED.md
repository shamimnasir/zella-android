# Decisions needed from a human

| # | Decision | Needed by | Default if no answer |
| --- | --- | --- | --- |
| 1 | Android pricing: (A) sold separately at the same prices, (B) restore by receipt across stores, (C) different Android price | Before Phase 8 | A. Copy will say plainly that Android is a separate purchase. |
| 2 | minSdk 29 (Android 10). Check the share of target-market devices (Bangladesh, India, Pakistan) | Before Phase 1 | 29 |
| 3 | Package name `com.zellahq.app`: confirm it is free on Play and fits the iOS bundle id | Before Phase 9 | com.zellahq.app |
| 4 | Tablet support in v1 | Before Phase 2 | Out of scope: normal resizable layouts only |
| 5 | Test devices: one flagship, one 2021-class mid-range, one 3 to 4 GB low-end, ideally across Snapdragon, Dimensity, Exynos, Tensor | Before Phase 1 gate | Emulator only, which cannot prove sync or encoder behaviour |
| 6 | Banned-jargon list in `BeginnerCopyTest`: it currently bans "bitrate" and "keyframe", yet both are Pro feature names on iPhone. Pick plain Android labels or lift the ban | Before Phase 5 | Plain labels |
| 7 | Google Play account type and the current closed-testing rule for new accounts | Before Phase 9 | Verify at the time |
