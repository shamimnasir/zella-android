import Foundation
import ZellaCore
let c = ZClip(source: .absolute(URL(fileURLWithPath: "/storage/emulated/0/Android/data/com.zellahq.app/files/recordings/Zella-1.mp4")), sourceDuration: 3)
var p = ZProject(id: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!, name: "abs", createdAt: Date(timeIntervalSince1970: 1_793_000_000.25), clips: [c])
var c2 = p.clips[0]; c2.speedRamp = .rampUp; p.clips[0] = c2
let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; e.outputFormatting = [.sortedKeys]
print(String(data: try e.encode(p), encoding: .utf8)!)
// Does the iPhone decoder accept fractional seconds and lowercase ids?
let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601
let probe = #"{"id":"11111111-2222-3333-4444-555555555555","formatVersion":1,"name":"x","createdAt":"2026-10-26T07:33:20.250Z","modifiedAt":"2026-10-26T07:33:20Z","aspect":"vertical","clips":[]}"#
print("fractional seconds decode:", (try? d.decode(ZProject.self, from: Data(probe.utf8))) != nil)
let lower = probe.replacingOccurrences(of: "20.250Z", with: "20Z").replacingOccurrences(of: "11111111-2222-3333-4444-555555555555", with: "aaaaaaaa-2222-3333-4444-555555555555")
print("lowercase id decode:", (try? d.decode(ZProject.self, from: Data(lower.utf8))) != nil)
let floaty = lower.replacingOccurrences(of: "\"formatVersion\":1", with: "\"formatVersion\":1.0")
print("formatVersion 1.0 decode:", (try? d.decode(ZProject.self, from: Data(floaty.utf8))) != nil)
