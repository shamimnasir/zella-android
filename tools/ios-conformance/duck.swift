import Foundation
import ZellaCore

// Prints the iPhone's own ducking decisions for a set of voice-window layouts, as JSON, so the
// Android music envelope can be held to them.
let cases: [(voice: [(Double, Double)], total: Double)] = [
    ([], 20),
    ([(3, 5)], 20),
    ([(0, 4)], 10),
    ([(1, 2), (2.05, 3)], 20),
    ([(1, 2), (2.5, 3.5), (2.6, 3.2)], 20),
    ([(1, 1.1)], 20),
    ([(1, 9.8)], 10),
    ([(1, 19)], 20),
    ([(-1, 2)], 20),
    ([(2, 3), (3.25, 4), (4.4, 6), (6.2, 7)], 12),
    ([(1, 2)], 2),
    ([(0.1, 0.5)], 0.6),
]
var out: [[String: Any]] = []
for c in cases {
    let total = c.total
    let fadeDur = min(1.5, max(0, total * 0.5))
    let fadeStart = total - fadeDur
    let r = CompositionBuilder.duckRamps(
        voiceWindows: c.voice.map { TimeWindow(start: $0.0, end: $0.1) },
        rampDur: 0.12, fadeStart: fadeStart, rampGap: 0.15)
    out.append([
        "voice": c.voice.map { [$0.0, $0.1] },
        "total": total,
        "ramps": r.map { [$0.down, $0.up] },
    ])
}
let data = try JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys])
FileManager.default.createFile(atPath: CommandLine.arguments[1], contents: data)
print("wrote \(out.count) duck cases")
