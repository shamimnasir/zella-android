import Foundation
import ZellaCore

// Renders every iPhone music loop and sound effect with the iPhone's own synthesiser (SoundFX.swift)
// to WAV, plus a catalogue. Android ships them compressed, so both apps play identical sounds.
let out = URL(fileURLWithPath: CommandLine.arguments[1])
for d in ["music", "sfx"] { try? FileManager.default.createDirectory(at: out.appendingPathComponent(d), withIntermediateDirectories: true) }
var music: [[String: Any]] = []
for loop in MusicLoop.allCases {
    let (l, r) = loop.render(sampleRate: 44_100)
    let name = loop.fileName
    _ = WAVWriter.writeStereo(left: l, right: r, to: out.appendingPathComponent("music/\(name).wav"), sampleRate: 44_100)
    music.append(["id": loop.rawValue, "file": name, "seconds": Double(l.count) / 44_100])
}
var sfx: [[String: Any]] = []
for fx in SoundEffect.allCases {
    let s = fx.render(sampleRate: 44_100)
    let name = "sfx_" + fx.rawValue.lowercased().replacingOccurrences(of: " ", with: "_")
    _ = WAVWriter.write(s, to: out.appendingPathComponent("sfx/\(name).wav"), sampleRate: 44_100)
    sfx.append(["id": fx.rawValue, "file": name, "seconds": Double(s.count) / 44_100])
}
let json = try JSONSerialization.data(withJSONObject: ["music": music, "sfx": sfx], options: [.prettyPrinted, .sortedKeys])
try json.write(to: out.appendingPathComponent("sounds.json"))
print("baked", music.count, "music loops and", sfx.count, "sound effects")
