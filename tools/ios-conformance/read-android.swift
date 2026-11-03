import Foundation
import ZellaCore
// Decodes every Android-written project.json with the iPhone's own decoder settings and model.
let dir = URL(fileURLWithPath: CommandLine.arguments[1])
let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601
var ok = 0, bad = 0
for f in try FileManager.default.contentsOfDirectory(atPath: dir.path).sorted() where f.hasSuffix(".json") {
    let url = dir.appendingPathComponent(f)
    do {
        let p = try d.decode(ZProject.self, from: Data(contentsOf: url))
        let expected = Double(try String(contentsOf: dir.appendingPathComponent(f.replacingOccurrences(of: ".json", with: ".duration")), encoding: .utf8))!
        let same = abs(p.duration - expected) < 1e-9
        print(f, "decoded, iPhone duration", p.duration, "Android said", expected, same ? "SAME" : "DIFFERENT")
        if same { ok += 1 } else { bad += 1 }
    } catch {
        print(f, "FAILED:", error); bad += 1
    }
}
print("ok", ok, "bad", bad)
exit(bad == 0 ? 0 : 1)
