# Known devices

Only tested on an Android 15 arm64 emulator so far (`zella_p0` AVD). No phone has run Zella. This file is the plan,
and the place to record results.

| Class | Why it matters | Example | Status |
| --- | --- | --- | --- |
| 4 GB mid-range, Helio or Dimensity | The main market in Bangladesh, India and Pakistan | Redmi Note, Samsung A series | Not tested |
| Exynos | Different encoder behaviour | Samsung A or S series | Not tested |
| Snapdragon flagship | Reference for speed | Any recent | Not tested |
| Tensor | Google's encoder quirks | Pixel | Not tested |
| Android 10 (the lowest supported) | Oldest device audio capture and export paths | Any on API 29 | Not tested |
| Android 15 and 16 | Foreground service rules, 16 KB pages | Pixel | Emulator only |

Record for each: model, Android version, chipset, A/V sync measured, export time for a fixed 1 minute project, crashes.
