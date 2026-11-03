import Foundation
import ZellaCore

// Builds sample projects with the iPhone app's own types and writes them through its own
// ProjectStore.exportSelfContained, so the Android conformance fixtures are real iPhone output.
let here = URL(fileURLWithPath: CommandLine.arguments[1]) // the work folder made by build.sh
let media = here.appendingPathComponent("media")
let out = here.appendingPathComponent("out")
func ref(_ n: String) -> MediaRef { .absolute(media.appendingPathComponent(n)) }

let a = ZClip(source: ref("clip_a.mp4"), sourceDuration: 6, trimStart: 0.5, trimEnd: 5.0)
var b = ZClip(source: ref("clip_b.mp4"), sourceDuration: 6, trimStart: 1.0, trimEnd: 4.0, speed: 1.5, volume: 0.5)
b.reversed = true
let c = ZClip(source: ref("clip_c.mp4"), sourceDuration: 6, trimStart: 0, trimEnd: 6, isBRoll: true)

var p = ZProject(id: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!, name: "Conformance basic",
                 createdAt: Date(timeIntervalSince1970: 1_793_000_000), aspect: .vertical, clips: [a, b, c])
p.modifiedAt = Date(timeIntervalSince1970: 1_793_000_100)
p.platformPresetID = "tiktok"
p.transitions = [TransitionPoint(afterClipID: a.id, type: .crossDissolve, duration: 0.5)]
p.captions = CaptionTrack(cues: [
    CaptionCue(words: [CaptionWord(text: "Hello", start: 0.1, end: 0.5), CaptionWord(text: "world", start: 0.5, end: 0.9, emphasized: true)]),
    CaptionCue(words: [CaptionWord(text: "আমার", start: 1.2, end: 1.6), CaptionWord(text: "সোনার", start: 1.6, end: 2.0, emoji: "✨")]),
])
p.captionLanguage = "bn"
p.titles = [TitleOverlay(text: "Big hook", subtitle: "subtitle here")]
p.zoomBlocks = [ZoomBlock(start: 2.0, scale: 1.4, anchorX: 0.3, anchorY: 0.6)]
p.musicBeds = [MusicBed(source: ref("bed.m4a"), sourceDuration: 20, start: 0, volume: 0.4, duck: true, name: "Lo-fi")]
p.soundBites = [SoundBite(effectID: "whoosh", atTime: 4.2)]
p.audioLeadOffset = -0.1
p.fitMode = .fitBlur

let pkg = try ProjectStore().exportSelfContained(p, to: out)
print("wrote", pkg.path, "duration", p.duration, "clip starts", p.clips.map { p.startTime(of: $0) })

let empty = ZProject(id: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!, name: "Conformance empty",
                     createdAt: Date(timeIntervalSince1970: 1_793_000_000), aspect: .landscape, clips: [])
print("wrote", try ProjectStore().exportSelfContained(empty, to: out).path)
