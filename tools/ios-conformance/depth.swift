// Dumps the iPhone's keyframe maths and JSON shapes on fixed inputs for the Kotlin port to match.
// Compile with ZellaCore/Keyframe.swift and ZellaCore/BeatSync.swift; prints JSON.
import Foundation

let interps: [(String, KFInterp)] = [("hold", .hold), ("linear", .linear), ("easeIn", .easeIn), ("easeOut", .easeOut), ("easeInOut", .easeInOut), ("bezier", .bezier(0.2, 0.9, 0.3, 1.0))]
var eased: [String: [Double]] = [:]
for (n, i) in interps { eased[n] = stride(from: 0.0, through: 1.0, by: 0.05).map { i.eased($0) } }
var encoded: [String: Any] = [:]
let enc = JSONEncoder()
for (n, i) in interps {
    let d = try enc.encode(i)
    encoded[n] = try JSONSerialization.jsonObject(with: d)
}
var tr = KeyframeTrack()
for (t, v, i) in [(0.0, 1.0, KFInterp.easeInOut), (1.0, 2.0, KFInterp.linear), (2.0, 0.5, KFInterp.hold), (3.0, 3.0, KFInterp.easeOut)] {
    tr.upsert(Keyframe(time: t, value: v, interp: i))
}
tr.upsert(Keyframe(time: 1.01, value: 2.5, interp: .linear))
let values = stride(from: -0.5, through: 3.5, by: 0.125).map { tr.value(at: $0, default: 9) }
let beats = [0.5, 1.0, 1.1, 3.0]
let punch = BeatSyncKeyframes.scaleTrack(beats: beats, peak: BeatSyncSettings(strength: 0.5).peakScale, clipDuration: 3.1)
let out: [String: Any] = [
    "eased": eased, "encoded": encoded, "values": values, "keysAfterUpsert": tr.keys.map(\.time),
    "punchTimes": punch.keys.map(\.time), "punchValues": punch.keys.map(\.value), "peak": BeatSyncSettings(strength: 0.5).peakScale,
]
print(String(data: try JSONSerialization.data(withJSONObject: out, options: [.sortedKeys, .prettyPrinted]), encoding: .utf8)!)
