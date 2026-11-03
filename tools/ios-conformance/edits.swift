import Foundation
import CoreGraphics
import ZellaCore

// Applies a fixed edit script with the iPhone's own ProjectEditing and writes every step, so the
// Android port can be checked step by step against the real thing.
func u(_ n: Int) -> UUID { UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", n))! }
func w(_ t: String, _ s: Double, _ e: Double) -> CaptionWord { CaptionWord(text: t, start: s, end: e) }

let a = ZClip(id: u(1), source: .packaged("a.mp4"), sourceDuration: 6, trimStart: 0.5, trimEnd: 5.0)
let b = ZClip(id: u(2), source: .packaged("b.mp4"), sourceDuration: 6, trimStart: 1.0, trimEnd: 4.0, speed: 1.5)
let c = ZClip(id: u(3), source: .packaged("c.mp4"), sourceDuration: 6, trimStart: 0, trimEnd: 6)
var p = ZProject(id: u(100), name: "Edits", createdAt: Date(timeIntervalSince1970: 1_793_000_000), aspect: .vertical, clips: [a, b, c])
p.modifiedAt = p.createdAt
p.captions = CaptionTrack(cues: [
    CaptionCue(id: u(10), words: [w("one", 0.2, 0.6), w("two", 0.6, 1.4)]),
    CaptionCue(id: u(11), words: [w("in", 4.6, 5.0), w("bee", 5.0, 6.0)]),
    CaptionCue(id: u(12), words: [w("sea", 7.0, 7.5), w("end", 9.0, 9.5)]),
])
p.titles = [TitleOverlay(id: u(20), text: "hook", start: 0.1, duration: 2.0),
            TitleOverlay(id: u(21), text: "late", start: 8.0, duration: 1.5)]
p.zoomBlocks = [ZoomBlock(id: u(30), start: 4.8), ZoomBlock(id: u(31), start: 9.0)]
p.musicBeds = [MusicBed(id: u(40), source: .packaged("bed.m4a"), sourceDuration: 20, start: 0.0)]
p.soundBites = [SoundBite(id: u(50), effectID: "whoosh", atTime: 1.0), SoundBite(id: u(51), effectID: "pop", atTime: 5.2),
                SoundBite(id: u(52), effectID: "hit", atTime: 10.0)]
p.callouts = [Callout(id: u(60), kind: .arrow, rect: CGRect(x: 0.1, y: 0.1, width: 0.2, height: 0.2), start: 0.3, duration: 1.0),
              Callout(id: u(61), kind: .box, rect: CGRect(x: 0.5, y: 0.5, width: 0.2, height: 0.2), start: 7.5, duration: 2.0)]
p.overlayClips = [OverlayClip(id: u(70), source: .packaged("pip.mp4"), sourceDuration: 10, start: 6.5, duration: 3.0)]
p.voiceWindows = [TimeWindow(start: 0.2, end: 1.4), TimeWindow(start: 7.0, end: 9.5)]

var steps: [(String, ZProject)] = [("base", p)]
@MainActor func step(_ name: String, _ f: (inout ZProject) -> Void) { f(&p); steps.append((name, p)) }
step("split 1.0") { $0.split(at: 1.0) }
step("speed clip 2 x2") { $0.setSpeed($0.clips[2].id, 2.0) }
step("trimEnd clip 3 -1") { $0.trimEnd($0.clips[3].id, by: -1.0) }
step("trimStart clip 0 +0.25") { $0.trimStart($0.clips[0].id, by: 0.25) }
step("move clip 3 to 0") { $0.move($0.clips[3].id, to: 0) }
step("rippleDelete clip 1") { $0.rippleDelete($0.clips[1].id) }
step("removeRange 0.5..1.0") { $0.removeTimelineRange(0.5 ..< 1.0) }
step("transition after 0") { $0.setTransition(after: $0.clips[0].id, type: .crossDissolve, duration: 0.4) }
step("duplicate clip 1") { $0.duplicate($0.clips[1].id) }
step("removeTransition after 0") { $0.removeTransition(after: $0.clips[0].id) }
step("rewind clip 0") { $0.rewindClip($0.clips[0].id) }
step("split 2.5") { $0.split(at: 2.5) }

let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; e.outputFormatting = [.sortedKeys]
var out: [[String: Any]] = []
for (name, proj) in steps {
    let obj = try JSONSerialization.jsonObject(with: try e.encode(proj))
    out.append(["step": name, "project": obj, "duration": proj.duration])
}
let data = try JSONSerialization.data(withJSONObject: out, options: [.sortedKeys])
try data.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
print("steps", steps.count, "final duration", p.duration, "clips", p.clips.count)
