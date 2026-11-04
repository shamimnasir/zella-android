// Dumps what the iPhone's SensitiveMatcher and SensitiveBlurPlanner say about fixed inputs, so the Kotlin
// port can be compared. Compile with ZellaCore/SensitiveText.swift; prints JSON.
import Foundation
import CoreGraphics

let lines = [
    "Access token: ghp_abcdefghijklmnopqrstuvwxyz123456",
    "password = hunter2hunter",
    "password: hidden",
    "key: Enter",
    "my api key sk-ant-api03-abcdefghijklmnopqrstuv is here",
    "AKIAIOSFODNN7EXAMPLE and AIzaSyA1234567890abcdefghijklmnopqrstuv",
    "xoxb-1234567890-abcdefghij",
    "Authorization: Bearer abc.def-ghi_jkl123456",
    "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.abcd1234efgh",
    "mail me at someone.name+tag@example.co.uk today",
    "card 4111 1111 1111 1111 exp",
    "not a card 1234 5678 9012 3456 7",
    "4111111111111112",
    "deadbeefdeadbeefdeadbeefdeadbeef",
    "abcdefabcdefabcdefabcdefabcdef",
    "Donaudampfschifffahrtsgesellschaftskapitaen",
    "aB3dE6gH9jK2mN5pQ8sT1vW4yZ7bC0eF",
    "ghр_abcdefghijklmnopqrstuvwxyz123456",
    "plain line of words",
    "",
    "token abcdefghijklmnop",
    "secret_key: abcd",
]
let queries: [(String?, Bool, Bool)] = [
    (nil, true, false), (nil, true, true), ("access token", false, false), ("t0ken", false, false), ("words", true, false), ("acess token", false, false),
]
var matchOut: [[String: Any]] = []
for (q, s, l) in queries {
    let m = SensitiveMatcher(query: q, secrets: s, includeLabels: l)
    for line in lines {
        let ms = m.matches(in: line).map { x -> [String: Any] in
            ["kind": x.kind.rawValue, "location": x.location, "length": x.length, "masked": SensitiveMatcher.mask(x.matchedText, kind: x.kind)]
        }
        matchOut.append(["query": q ?? NSNull(), "secrets": s, "labels": l, "line": line, "matches": ms])
    }
}

func hit(_ key: String, _ x: Double, _ y: Double, _ w: Double = 0.2, _ h: Double = 0.03, kind: SensitiveTextKind = .apiKey) -> SensitiveHit {
    SensitiveHit(kind: kind, box: CGRect(x: x, y: y, width: w, height: h), masked: "m-" + key, key: key)
}
typealias S = SensitiveBlurPlanner.Sample
let scenarios: [(String, [S], Double, Double)] = [
    ("still", (0..<6).map { S(time: Double($0) * 0.5, hits: $0 >= 2 && $0 <= 3 ? [hit("abc", 0.3, 0.4)] : []) }, 0, 2.5),
    ("fromStart", (0..<4).map { S(time: Double($0) * 0.5, hits: $0 <= 1 ? [hit("abc", 0.3, 0.4)] : []) }, 0, 1.5),
    ("toEnd", (0..<4).map { S(time: Double($0) * 0.5, hits: $0 >= 2 ? [hit("abc", 0.3, 0.4)] : []) }, 0, 2.0),
    ("scroll", (0..<6).map { S(time: Double($0) * 0.5, hits: [hit("abc", 0.3, 0.8 - Double($0) * 0.1)]) }, 0, 2.5),
    ("missedOne", (0..<6).map { S(time: Double($0) * 0.5, hits: $0 == 2 ? [] : [hit("abc", 0.3, 0.4)]) }, 0, 2.5),
    ("missedThree", (0..<8).map { S(time: Double($0) * 0.5, hits: ($0 >= 2 && $0 <= 4) ? [] : [hit("abc", 0.3, 0.4)]) }, 0, 3.5),
    ("newWhereOldWas", (0..<4).map { S(time: Double($0) * 0.5, hits: [hit($0 < 2 ? "aaaaaaaaaaaaaaaa" : "zzzzzzzzzzzzzzzz", 0.3, 0.4)]) }, 0, 1.5),
    ("outlier", (0..<5).map { S(time: Double($0) * 0.5, hits: [hit("abc", 0.3, 0.4, $0 == 2 ? 0.8 : 0.2, $0 == 2 ? 0.2 : 0.03)]) }, 0, 2.0),
    ("two", (0..<3).map { S(time: Double($0) * 0.5, hits: [hit("aaa", 0.1, 0.1), hit("bbbbbbbbbbbb", 0.5, 0.6, kind: .email)]) }, 0, 1.0),
    ("labelAndValue", (0..<3).map { S(time: Double($0) * 0.5, hits: [hit("aaaaaaaaaaaaaaaa", 0.3, 0.4), hit("zzzzzzzzzzzzzzzzzzzzzz", 0.302, 0.401, kind: .email)]) }, 0, 1.0),
    ("edge", (0..<3).map { S(time: Double($0) * 0.5, hits: [hit("abc", 0.9, 0.97)]) }, 0, 1.0),
]
var planOut: [[String: Any]] = []
for (name, s, a, b) in scenarios {
    let ps = SensitiveBlurPlanner.plans(samples: s, rangeStart: a, rangeEnd: b).map { p -> [String: Any] in
        ["centerX": p.centerX, "centerY": p.centerY, "width": p.width, "height": p.height, "start": p.start, "end": p.end,
         "kind": p.kind.rawValue, "masked": p.masked,
         "trackTimes": p.trackTimes ?? NSNull(), "trackX": p.trackX ?? NSNull(), "trackY": p.trackY ?? NSNull()]
    }
    planOut.append(["name": name, "plans": ps])
}
var times: [[String: Any]] = []
for (a, b) in [(0.0, 10.0), (2.0, 2.0), (0.0, 100.0), (1.3, 4.1)] {
    let (t, st) = SensitiveBlurPlanner.sampleTimes(start: a, end: b)
    times.append(["start": a, "end": b, "times": t, "step": st])
}
let out: [String: Any] = ["matches": matchOut, "plans": planOut, "times": times]
let data = try JSONSerialization.data(withJSONObject: out, options: [.sortedKeys, .prettyPrinted])
print(String(data: data, encoding: .utf8)!)
