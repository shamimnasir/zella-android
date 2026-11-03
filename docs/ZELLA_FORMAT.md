# The .zella project format (cross-platform contract)

The iPhone app defines the format (`ZellaKit/Sources/ZellaCore/Project.swift`, `ProjectStore.swift`).
Android follows it. Mac shares it. Changing it means changing all three and bumping `formatVersion`.

## Package

```
NAME.zella/
  project.json   the document, compact JSON
  media/         copied clips, music, LUTs, custom backgrounds (self-contained hand-off only)
  fonts/         imported font files used by titles and captions
```

## Encoding rules (what Swift JSONEncoder with `.iso8601` writes)

| Thing | Encoding |
| --- | --- |
| ids | uppercase UUID strings |
| dates | ISO 8601, UTC, whole seconds, no fraction (`2026-10-26T07:33:20Z`). Older iOS rejects fractions. |
| integers (`formatVersion`) | JSON integers, never `1.0` |
| optional fields | absent when nil, never `null` |
| media references | `{"packaged":{"_0":"clip.mp4"}}` (file in media/) or `{"absolute":{"_0":"file:///..."}}` |
| enums | the Swift raw value: `"vertical"`, `"Cross Dissolve"`, `"Word Pop"`, `"Ramp Up"` |

`packaged` paths are untrusted: only the last path component is used (`MediaRef.safeLeaf`).

A file with a higher `formatVersion` than the reader supports is refused with a message. It is
never "healed" from an older snapshot, because that would destroy the newer document.

## Unknown fields

Android types the fields it supports and keeps every other key, at every level, in `extras`.
They are written back unchanged. Timeline edits remap the times inside the collections Android
does not model yet: `callouts` (start, duration), `overlayClips` (start, duration),
`voiceWindows` (start, end) and `reframe.keys[].time`.

## Timeline rules

Ported from `ProjectEditing.swift`: transitions overlap at most 0.45 of either neighbour; every edit
remaps every pinned collection. One known difference: the iPhone's reorder does not move
`overlayClips`; Android does (see `EditParityTest`).

## How conformance is proven

`tools/ios-conformance/build.sh` compiles the iPhone's ZellaCore for macOS from the local checkout and:

1. writes fixtures with the iPhone's own `ProjectStore.exportSelfContained` (`fixtures/ios/*.json`),
2. runs a 12-step edit script with the iPhone's own edit functions (`edit-steps.json`),
3. decodes every project Android wrote for the same script with the iPhone's decoder.

`ConformanceTest` and `EditParityTest` check 1 and 2 on every build. Step 3 needs a Mac with the
iPhone checkout and is run by hand: last result 12 of 12 decoded with identical durations (2026-11-04).
