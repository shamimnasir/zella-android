import Foundation
import ZellaCore

// Runs the iPhone's own planning code on a battery of inputs and writes the answers as JSON.
func out(_ v: Any, _ path: String) throws {
    let d = try JSONSerialization.data(withJSONObject: v, options: [.prettyPrinted, .sortedKeys])
    FileManager.default.createFile(atPath: path, contents: d)
}
struct Rng { var s: UInt64; mutating func next() -> Double { s = s &* 6364136223846793005 &+ 1442695040888963407; return Double(s >> 11) / Double(1 << 53) } }
var r = Rng(s: 42)

// Silence detector.
var silence: [[String: Any]] = []
for k in 0..<8 {
    var energies: [(t: Double, db: Float)] = []
    var t = 0.0
    let floor = Float(-70 + 5 * k)
    while t < 12 {
        let talking = sin(t * 1.3 + Double(k)) > -0.2 || (k % 3 == 0 && Int(t * 10) % 11 == 0)
        let db = talking ? Float(-20 - 6 * r.next()) : floor + Float(4 * r.next())
        energies.append((t, db))
        t += 0.03
    }
    let d = SilenceDetector()
    let th = d.exposedThreshold(energies)
    let ranges = d.collapse(energies, threshold: th)
    silence.append(["energies": energies.map { [$0.t, Double($0.db)] }, "threshold": Double(th), "ranges": ranges.map { [$0.lowerBound, $0.upperBound] }])
}

// Fillers.
let vocab = ["um", "uh", "so", "you", "know", "i", "mean", "the", "the", "kind", "of", "really", "Um,", "hmm", "great", "great!", "mm", "er", "right", "sort"]
var fillers: [[String: Any]] = []
for _ in 0..<20 {
    var t = 0.0
    var words: [TranscribedWord] = []
    for _ in 0..<40 {
        let w = vocab[Int(r.next() * Double(vocab.count))]
        let len = r.next() < 0.1 ? 0.9 : 0.1 + r.next() * 0.4
        words.append(TranscribedWord(text: w, start: t, end: t + len))
        t += len + r.next() * 0.2
    }
    let ranges = TranscriptAnalysis.allFillerRanges(in: words)
    fillers.append(["words": words.map { [$0.text, $0.start, $0.end] as [Any] }, "ranges": ranges.map { [$0.lowerBound, $0.upperBound] }])
}

// Witness subtraction.
var witness: [[String: Any]] = []
for _ in 0..<20 {
    var ranges: [Range<TimeInterval>] = []
    var t = 0.0
    for _ in 0..<6 { let a = t + r.next() * 2; let b = a + 0.1 + r.next() * 2; ranges.append(a..<b); t = b }
    var words: [TranscribedWord] = []
    for _ in 0..<12 { let a = r.next() * t; words.append(TranscribedWord(text: "w", start: a, end: a + r.next() * 0.8)) }
    let kept = Plans.subtractingWitnessWords(ranges, words: words)
    witness.append(["ranges": ranges.map { [$0.lowerBound, $0.upperBound] }, "words": words.map { [$0.start, $0.end] }, "kept": kept.map { [$0.lowerBound, $0.upperBound] }])
}

// Interrupts.
var interrupts: [[String: Any]] = []
for _ in 0..<20 {
    let duration = 3 + r.next() * 120
    var covered: [(start: TimeInterval, end: TimeInterval)] = []
    for _ in 0..<Int(r.next() * 10) { let a = r.next() * duration; covered.append((a, a + r.next() * 4)) }
    let plan = Plans.interruptPlan(duration: duration, covered: covered)
    interrupts.append(["duration": duration, "covered": covered.map { [$0.start, $0.end] }, "plan": plan.map { [$0.start, $0.anchorX, $0.anchorY] }])
}

// Emphasis zooms.
var emphasis: [[String: Any]] = []
for _ in 0..<20 {
    let duration = 4 + r.next() * 90
    var candidates: [(time: TimeInterval, rank: Int)] = []
    for _ in 0..<Int(r.next() * 30) { candidates.append((r.next() * duration, Int(r.next() * 120))) }
    var cuts: [TimeInterval] = []
    for _ in 0..<Int(r.next() * 8) { cuts.append(r.next() * duration) }
    var occupied: [(start: TimeInterval, end: TimeInterval)] = []
    for _ in 0..<Int(r.next() * 4) { let a = r.next() * duration; occupied.append((a, a + r.next() * 3)) }
    let starts = Plans.emphasisZoomStarts(candidates: candidates, duration: duration, transitionTimes: cuts, occupied: occupied)
    emphasis.append(["duration": duration, "candidates": candidates.map { [$0.time, Double($0.rank)] }, "cuts": cuts, "occupied": occupied.map { [$0.start, $0.end] }, "starts": starts])
}

// Sounds under cuts.
let types: [TransitionType] = TransitionType.allCases
var sounds: [[String: Any]] = []
for _ in 0..<30 {
    let duration = 5 + r.next() * 200
    var cuts: [(type: TransitionType, time: TimeInterval)] = []
    for _ in 0..<Int(r.next() * 20) { cuts.append((types[Int(r.next() * Double(types.count))], r.next() * duration)) }
    var pauses: [ClosedRange<TimeInterval>] = []
    for _ in 0..<Int(r.next() * 5) { let a = r.next() * duration; pauses.append(a...(a + r.next() * 3)) }
    let plan = Plans.autoCutSoundPlan(cuts: cuts, pauses: pauses, timelineDuration: duration)
    sounds.append(["duration": duration, "cuts": cuts.map { [$0.type.rawValue, $0.time] as [Any] }, "pauses": pauses.map { [$0.lowerBound, $0.upperBound] },
                   "plan": plan.map { [$0.effect.rawValue, $0.time, $0.volume] as [Any] }])
}

// Small ones.
var zoomScale: [[Double]] = []
for h in stride(from: 0.0, through: 0.6, by: 0.013) {
    for db in [0.0, 3.0, 9.0] { zoomScale.append([h, db, Plans.smartZoomScale(faceHeightFrac: h, emphasisDb: db) ?? -1]) }
}
var mood: [[Any]] = []
for wps in [0.0, 1.0, 1.6, 2.0, 2.6, 3.0] { for avg in [0.0, 1.5, 2.5, 4.0, 7.0, 9.0] { mood.append([wps, avg, Plans.bundleMusicMood(wordsPerSecond: wps, averageClipLength: avg)]) } }
try out(["silence": silence, "fillers": fillers, "witness": witness, "interrupts": interrupts, "emphasis": emphasis, "sounds": sounds, "zoomScale": zoomScale, "mood": mood], CommandLine.arguments[1])
print("wrote one-tap plans")
