import Foundation
import CoreImage

// Bakes every iPhone look into a 33x33x33 LUT by running the iPhone's own EffectChain on a colour
// grid. Output: looks/NNN.lut (33^3 RGB bytes, index = r + 33*g + 33*33*b), looks.json with each
// look's exact ClipEffects, and board/ fixtures of the colour board for Android's own maths to match.
let n = 33
let out = URL(fileURLWithPath: CommandLine.arguments[1])
for d in ["looks", "board"] { try? FileManager.default.createDirectory(at: out.appendingPathComponent(d), withIntermediateDirectories: true) }
let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CIContext(options: [.outputColorSpace: srgb])
var grid = [Float](repeating: 1, count: n * n * n * 4)
for b in 0..<n { for g in 0..<n { for r in 0..<n {
    let i = ((b * n * n) + (g * n) + r) * 4
    grid[i] = Float(r) / Float(n - 1); grid[i + 1] = Float(g) / Float(n - 1); grid[i + 2] = Float(b) / Float(n - 1); grid[i + 3] = 1
}}}
let data = grid.withUnsafeBufferPointer { Data(buffer: $0) }
let input = CIImage(bitmapData: data, bytesPerRow: n * n * 16, size: CGSize(width: n * n, height: n), format: .RGBAf, colorSpace: srgb)

func bake(_ fx: ClipEffects, _ name: String) throws {
    let graded = EffectChain.apply(fx, to: input, frame: input.extent)
    var px = [Float](repeating: 0, count: n * n * n * 4)
    px.withUnsafeMutableBytes { buf in
        ctx.render(graded, toBitmap: buf.baseAddress!, rowBytes: n * n * 16, bounds: input.extent, format: .RGBAf, colorSpace: srgb)
    }
    // render(toBitmap:) keeps the row order the input bitmap was built in: no flip. A flip here
    // inverted the blue axis of every look (caught by Android's colour board test, 2026-11-04).
    var lut = Data(count: n * n * n * 3)
    for b in 0..<n { for g in 0..<n { for r in 0..<n {
        let src = ((b * n * n) + g * n + r) * 4
        let dst = ((b * n * n) + g * n + r) * 3
        for c in 0..<3 { lut[dst + c] = UInt8(max(0, min(255, (px[src + c] * 255).rounded()))) }
    }}}
    try lut.write(to: out.appendingPathComponent(name))
}

// The nine probe patches through the real chain, unquantised: what the iPhone's own look gate measures.
func probes(_ fx: ClipEffects) -> [[Double]] {
    ColorMath.probePatches.map { p in
        let c = CIImage(color: CIColor(red: CGFloat(p.rgb.0), green: CGFloat(p.rgb.1), blue: CGFloat(p.rgb.2), colorSpace: srgb)!)
            .cropped(to: CGRect(x: 0, y: 0, width: 4, height: 4))
        let g = EffectChain.apply(fx, to: c, frame: c.extent)
        var px = [Float](repeating: 0, count: 4)
        px.withUnsafeMutableBytes { ctx.render(g, toBitmap: $0.baseAddress!, rowBytes: 16, bounds: CGRect(x: 1, y: 1, width: 1, height: 1), format: .RGBAf, colorSpace: srgb) }
        return [Double(px[0]), Double(px[1]), Double(px[2])]
    }
}

let enc = JSONEncoder(); enc.outputFormatting = [.sortedKeys]
var catalog: [[String: Any]] = []
for (idx, preset) in ColorPreset.all.enumerated() {
    var fx = ClipEffects()
    fx.applyPreset(preset)
    fx.gradeIntensity = 1
    // Colour only in the LUT. Spatial effects would smear neighbouring grid cells into each other
    // (VHS Retro came out ΔE 2 off); Android applies them on the frame from the values listed below.
    var colourOnly = fx
    colourOnly.special.blurRadius = 0
    colourOnly.special.glow = 0
    colourOnly.special.grain = 0
    colourOnly.special.vignette = 0
    colourOnly.special.pixelate = 0
    colourOnly.special.chromaticAberration = 0
    colourOnly.special.motionBlur = 0
    colourOnly.special.letterbox = false
    colourOnly.colorAdjustments.sharpness = 0
    colourOnly.colorAdjustments.noiseReduction = 0
    try bake(colourOnly, String(format: "looks/%03d.lut", idx))
    let s = preset.special
    catalog.append([
        "index": idx, "name": preset.name, "category": preset.category, "founder": preset.isFounderExclusive,
        "effects": try JSONSerialization.jsonObject(with: enc.encode(fx)),
        // Grain off, as the iPhone's own gate measures: random noise is not part of a look's colour.
        "probes": probes({ var f = fx; f.special.grain = 0; return f }()),
        "vignette": s.vignette, "grain": s.grain, "glow": s.glow, "blur": s.blurRadius,
        "chromaticAberration": s.chromaticAberration, "pixelate": s.pixelate, "letterbox": s.letterbox,
        "sharpness": fx.colorAdjustments.sharpness,
    ])
}
let boards: [ColorBoard] = [
    ColorBoard(lift: .init(red: 0.10, luma: 0.05)),
    ColorBoard(lift: .init(blue: -0.08)),
    ColorBoard(gamma: .init(red: -0.2, blue: 0.25)),
    ColorBoard(gain: .init(green: 0.15, luma: -0.1)),
]
var boardJSON: [Any] = []
for (i, cb) in boards.enumerated() {
    var fx = ClipEffects()
    fx.colorBoard = cb
    try bake(fx, "board/\(i).lut")
    boardJSON.append(try JSONSerialization.jsonObject(with: enc.encode(cb)))
}
try JSONSerialization.data(withJSONObject: boardJSON, options: [.sortedKeys]).write(to: out.appendingPathComponent("board/boards.json"))
try JSONSerialization.data(withJSONObject: ["size": n, "looks": catalog], options: [.prettyPrinted, .sortedKeys])
    .write(to: out.appendingPathComponent("looks.json"))
print("baked", catalog.count, "looks and", boards.count, "colour board fixtures")
