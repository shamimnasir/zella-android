import Foundation
import CoreGraphics
import ZellaMotion

// Dumps the iPhone's whole text motion catalogue as data, and what its evaluator computes for every
// preset, so Android runs the same numbers instead of a hand copy of them.
func name(_ e: Ease) -> String {
    switch e {
    case .linear: return "linear"
    case .sineIn: return "sineIn"
    case .sineOut: return "sineOut"
    case .sineInOut: return "sineInOut"
    case .quadIn: return "quadIn"
    case .quadOut: return "quadOut"
    case .quadInOut: return "quadInOut"
    case .cubicIn: return "cubicIn"
    case .cubicOut: return "cubicOut"
    case .cubicInOut: return "cubicInOut"
    case .quartIn: return "quartIn"
    case .quartOut: return "quartOut"
    case .quartInOut: return "quartInOut"
    case .quintIn: return "quintIn"
    case .quintOut: return "quintOut"
    case .quintInOut: return "quintInOut"
    case .expoIn: return "expoIn"
    case .expoOut: return "expoOut"
    case .expoInOut: return "expoInOut"
    case .backOut(let s): return "backOut(\(s))"
    case .elasticOut(let a, let p): return "elasticOut(\(a),\(p))"
    case .bounceOut: return "bounceOut"
    case .spring(let z, let o): return "spring(\(z),\(o))"
    case .steps(let n): return "steps(\(n))"
    case .pulse2: return "pulse2"
    }
}

func track(_ t: MotionTrack) -> [String: Any] {
    var o: [String: Any] = ["property": t.property.rawValue, "keys": t.keys.map { [$0.at, $0.value] }, "alternate": t.alternate]
    if let e = t.ease { o["ease"] = name(e) }
    if case .flicker(let hz, let amount) = t.modulation { o["flicker"] = [hz, amount] }
    return o
}

func wave(_ w: MotionWave) -> [String: Any] {
    switch w {
    case .sine: return ["kind": "sine"]
    case .cosine01: return ["kind": "cosine01"]
    case .absSine: return ["kind": "absSine"]
    case .peak: return ["kind": "peak"]
    case .noise: return ["kind": "noise"]
    case .stepped(let hz): return ["kind": "stepped", "hz": hz]
    case .steppedUnit(let hz): return ["kind": "steppedUnit", "hz": hz]
    case .bursts(let hz, let d): return ["kind": "bursts", "hz": hz, "density": d]
    case .pulse2: return ["kind": "pulse2"]
    case .ramp: return ["kind": "ramp"]
    case .creep: return ["kind": "creep"]
    case .constant: return ["kind": "constant"]
    }
}

var catalog: [[String: Any]] = []
for p in TextMotionCatalog.all {
    var o: [String: Any] = [
        "id": p.id, "name": p.name, "family": p.family.rawValue, "familyName": p.family.displayName, "unit": p.unit.rawValue,
        "ease": name(p.ease), "stagger": p.stagger, "order": p.order.rawValue,
        "variants": (p.supports(.inPhase) ? "I" : "") + (p.supports(.outPhase) ? "O" : "") + (p.supports(.loop) ? "L" : ""),
        "baseDuration": p.baseDuration, "anchor": p.anchor.rawValue, "tracks": p.tracks.map(track),
        "loopTracks": p.loopTracks.map { l in
            ["property": l.property.rawValue, "offset": l.offset, "amplitude": l.amplitude, "wave": wave(l.wave),
             "period": l.period, "phase": l.phase] as [String: Any]
        },
        "caret": p.caret, "legacyMac": p.legacyMac, "legacyIOS": p.legacyIOS,
    ]
    if let out = p.outTracks { o["outTracks"] = out.map(track) }
    if let t = p.tintColor { o["tint"] = [t.r, t.g, t.b] }
    catalog.append(o)
}
func write(_ v: Any, _ path: String) throws {
    let d = try JSONSerialization.data(withJSONObject: v, options: [.sortedKeys])
    FileManager.default.createFile(atPath: path, contents: d)
}
try write(catalog, CommandLine.arguments[1])

// Two lines of text as rectangles, 40 by 60 pixels a letter, words split by a gap.
func layout() -> [UnitInfo] {
    var out: [UnitInfo] = []
    func add(_ text: String, word: Int, line: Int, startX: Double) {
        for (i, c) in text.enumerated() {
            out.append(UnitInfo(rect: CGRect(x: startX + Double(i) * 40, y: Double(line) * 70, width: 40, height: 60), word: word, line: line, text: String(c)))
        }
    }
    add("Hi", word: 0, line: 0, startX: 0)
    add("all", word: 1, line: 0, startX: 110)
    add("now", word: 0, line: 1, startX: 0)
    return out
}
let props: [MotionProperty] = MotionProperty.allCases.filter { $0 != .scale }
func state(_ s: UnitState) -> [String: Any] {
    var o: [String: Any] = ["anchor": s.anchor.rawValue, "scrambleIndex": s.scrambleIndex, "spreadRank": s.spreadRank, "noiseSeed": String(s.noiseSeed)]
    for p in props where abs(s[p] - p.rest) > 1e-12 { o[p.rawValue] = s[p] }
    if let t = s.tintColor { o["tintColor"] = [t.r, t.g, t.b] }
    return o
}
func frame(_ f: MotionFrame) -> [String: Any] {
    var o: [String: Any] = ["unit": f.unit.rawValue, "groups": f.groups, "atRest": f.atRest, "states": f.states.map(state)]
    if let c = f.caret { o["caret"] = [Double(c.afterGroup), c.opacity] }
    if let ph = f.phase { o["phase"] = ph.rawValue }
    return o
}

let l = layout()
let duration = 3.0
var frames: [[String: Any]] = []
let times = [0.0, 0.02, 0.11, 0.27, 0.43, 0.61, 0.83, 1.37, 2.19, 2.55, 2.74, 2.93, 3.0]
for p in TextMotionCatalog.all {
    var specs: [(String, TextAnimationSpec)] = []
    if p.supports(.inPhase) { specs.append(("in", TextAnimationSpec(inPhase: TextMotionPhase(preset: p.id)))) }
    if p.supports(.outPhase) { specs.append(("out", TextAnimationSpec(outPhase: TextMotionPhase(preset: p.id)))) }
    if p.supports(.loop) { specs.append(("loop", TextAnimationSpec(loop: TextMotionPhase(preset: p.id)))) }
    for (kind, spec) in specs {
        let w = TextMotion.windows(spec, layout: l, titleDuration: duration)
        frames.append([
            "preset": p.id, "kind": kind, "spec": try JSONSerialization.jsonObject(with: JSONEncoder().encode(spec)),
            "inDuration": w.inDuration, "outDuration": w.outDuration,
            "frames": times.map { t in
                ["t": t, "frame": frame(TextMotion.evaluate(spec, layout: l, local: t, titleDuration: duration)),
                 "isAtRest": TextMotion.isAtRest(spec, layout: l, local: t, titleDuration: duration)] as [String: Any]
            },
        ])
    }
}
// Overrides: speed, intensity, stagger, order, unit, seed, ease, duration, in and out and loop together.
let combos: [TextAnimationSpec] = [
    TextAnimationSpec(inPhase: TextMotionPhase(preset: "rise.letters", speed: 2, intensity: 1.5, stagger: 0.2, order: "edges", unit: "word", seed: 42, ease: "backOut(1.2)")),
    TextAnimationSpec(inPhase: TextMotionPhase(preset: "scale.popup", duration: 0.5), outPhase: TextMotionPhase(preset: "fade.drift", duration: 0.5), loop: TextMotionPhase(preset: "wave.swell")),
    TextAnimationSpec(inPhase: TextMotionPhase(preset: "fade.drift", duration: 3), outPhase: TextMotionPhase(preset: "rise.letters", duration: 3)),
    TextAnimationSpec(loop: TextMotionPhase(preset: "flip.billboard")),
    TextAnimationSpec(outPhase: TextMotionPhase(preset: "fade.whisper"), loop: TextMotionPhase(preset: "flip.billboard")),
    TextAnimationSpec(inPhase: TextMotionPhase(preset: "does.not.exist")),
]
for spec in combos {
    let w = TextMotion.windows(spec, layout: l, titleDuration: duration)
    frames.append([
        "preset": "combo", "kind": "combo", "spec": try JSONSerialization.jsonObject(with: JSONEncoder().encode(spec)),
        "inDuration": w.inDuration, "outDuration": w.outDuration,
        "frames": times.map { t in
            ["t": t, "frame": frame(TextMotion.evaluate(spec, layout: l, local: t, titleDuration: duration)),
             "isAtRest": TextMotion.isAtRest(spec, layout: l, local: t, titleDuration: duration)] as [String: Any]
        },
    ])
}
try write(frames, CommandLine.arguments[2])
print("wrote \(catalog.count) presets and \(frames.count) evaluated phases")
