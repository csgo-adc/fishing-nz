import Foundation

@main struct SharedWindowRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    // The same link is decoded by the Android unit tests and the Worker tests, so all three agree on the format.
    static let goldenToken = "eyJ2IjoxLCJuIjoiVGFrYXB1bmEgQmVhY2giLCJhIjoiQXVja2xhbmQiLCJiIjowLCJzIjoxNzkxNjk0ODAwLCJlIjoxNzkxNzAyMDAwLCJsYSI6LTM2Ljc4NzEsImxvIjoxNzQuNzcwNSwibyI6WyLwn5mCIiwiT2theSJdLCJmIjpbIvCfl5PvuI8iLCJQbGFubmluZyBmb3JlY2FzdCJdLCJyIjoiTG93ZXIgZGlzY29tZm9ydCIsInQiOjE3OTE2MDg0MDAsImMiOltbIldpbmQiLCIxMiBrbS9oIMK3IGd1c3QgMjAgwrcgU1ciLCLwn5iMIiwiQ29tZm9ydGFibGUiXSxbIlRpZGUiLCJOZXh0IGhpZ2ggNzoxMiBQTSDCtyAyLjQgbSBDRFxuQXVja2xhbmQgKFdhaXRlbWF0xIEpIiwi8J-VkiIsIlRpZGUgdGltaW5nIl1dfQ"
    static let start = Date(timeIntervalSince1970: 1_791_694_800) // Sunday 11 October 2026, 6:00 PM NZDT

    static func window(name: String = "Takapuna Beach", rows: [ConditionItem]? = nil) -> Recommendation {
        let conditions = rows ?? [
            ConditionItem(title: "Wind", value: "12 km/h · gust 20 · SW", mood: WindowMood(emoji: "😌", label: "Comfortable")),
            ConditionItem(title: "Tide", value: "Next high 7:12 PM · 2.4 m CD\nAuckland (Waitematā)", mood: WindowMood(emoji: "🕒", label: "Tide timing")),
        ]
        return Recommendation(
            name: name, area: "Auckland", rating: 70, time: "Sun 11 Oct · 6:00 PM–8:00 PM", distance: "12 km straight-line",
            reasons: ["Lower discomfort"], boat: false, startsAt: start, endsAt: start.addingTimeInterval(7200), summary: "Lower discomfort",
            assessment: WindowAssessment(mood: WindowMood(emoji: "🙂", label: "Okay"), conditions: conditions,
                                         confidence: WindowMood(emoji: "🗓️", label: "Planning forecast")),
            coordinate: GeoPoint(latitude: -36.7871, longitude: 174.7705))
    }

    static func encodedToken(_ json: [String: Any]) -> String {
        let data = try! JSONSerialization.data(withJSONObject: json)
        return data.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func valid() -> [String: Any] { ["v": 1, "n": "Spot", "a": "Area", "b": 0, "s": 1_791_694_800, "e": 1_791_702_000] }

    static func main() throws {
        // The format written by Android and read by the Worker.
        let read = SharedWindowLink.decode(goldenToken)!
        try expect(read.name == "Takapuna Beach" && read.area == "Auckland" && !read.boat, "Golden link lost its place")
        try expect(read.startsAt == start && read.endsAt == start.addingTimeInterval(7200), "Golden link lost its times")
        try expect(read.time == "Sun 11 Oct · 6:00 PM–8:00 PM", "Golden link time text was \(read.time)")
        try expect(read.coordinate == GeoPoint(latitude: -36.7871, longitude: 174.7705), "Golden link lost its coordinates")
        try expect(read.windowOutlook == "🙂 Okay" && read.reasons == ["Lower discomfort"], "Golden link lost its outlook or reason")
        try expect(read.assessment?.confidence == WindowMood(emoji: "🗓️", label: "Planning forecast"), "Golden link lost its confidence")
        try expect(read.assessment?.conditions.map(\.title) == ["Wind", "Tide"], "Golden link lost its conditions")
        try expect(read.assessment?.conditions[1].value == "Next high 7:12 PM · 2.4 m CD\nAuckland (Waitematā)", "Tide row lost its second line")

        // A window survives being shared and opened.
        let original = window()
        let link = SharedWindowLink.url(for: original, now: start.addingTimeInterval(-86_400))!
        try expect(link.absoluteString.hasPrefix("https://fishing.fishnz.space/w/"), "Link starts with the wrong address")
        let encoded = String(link.absoluteString.dropFirst("https://fishing.fishnz.space/w/".count))
        try expect(encoded.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }, "Token is not URL-safe or is padded")
        let opened = SharedWindowLink.window(from: link)!
        try expect(opened.name == original.name && opened.area == original.area, "Shared place changed")
        try expect(opened.startsAt == original.startsAt && opened.endsAt == original.endsAt, "Shared times changed")
        try expect(opened.windowOutlook == original.windowOutlook && opened.reasons == original.reasons, "Shared outlook changed")
        try expect(opened.assessment?.conditions == original.assessment?.conditions, "Shared conditions changed")
        try expect(opened.coordinate == original.coordinate, "Shared coordinates changed")
        try expect(opened.distance.hasPrefix("Shared with you"), "Opened window is not marked as shared")

        // What the share sheet sends.
        try expect(SharedWindowLink.text(for: original, url: URL(string: "https://fishing.fishnz.space/w/abc")!)
                   == "Fishing window: Takapuna Beach, Auckland\nSun 11 Oct · 6:00 PM–8:00 PM · 🙂 Okay\nhttps://fishing.fishnz.space/w/abc", "Share text changed")

        // Real conditions still make a short link.
        let rows = ["Wind", "Rain", "Feels like", "Tide", "Offshore waves", "Daylight"].map {
            ConditionItem(title: $0, value: "14 km/h · gust 22 · SW and a few more words", mood: WindowMood(emoji: "🌤️", label: "Mostly dry"))
        }
        try expect(SharedWindowLink.url(for: window(rows: rows))!.absoluteString.count < 1_000, "Link with six conditions is too long")

        // A window with no times cannot be shared.
        var untimed = original; untimed.startsAt = nil
        try expect(SharedWindowLink.url(for: untimed) == nil, "Window without a start was shareable")

        // Only our addresses are recognised, in both forms.
        let token = "abc_DEF-123"
        let accepted = ["https://fishing.fishnz.space/w/\(token)", "https://fishing.fishnz.space/w/\(token)/", "https://fishing.fishnz.space/w/\(token)?utm_source=chat#top",
                        "HTTPS://FISHING.FISHNZ.SPACE/w/\(token)", "nz.fishingnz.catchcheck://w/\(token)"]
        for text in accepted { try expect(SharedWindowLink.token(from: URL(string: text)!) == token, "Rejected \(text)") }
        let rejected = ["https://fishing.fishnz.space/privacy", "https://fishing.fishnz.space/w/", "https://evil.example/w/\(token)",
                        "https://fishing.fishnz.space.evil.example/w/\(token)", "http://fishing.fishnz.space/w/\(token)",
                        "nz.fishingnz.catchcheck://auth?code=1", "nz.fishingnz.catchcheck://w/", "nz.fishingnz.app://w/\(token)",
                        "https://fishing.fishnz.space/w/\(String(repeating: "a", count: 5000))"]
        for text in rejected { try expect(SharedWindowLink.token(from: URL(string: text)!) == nil, "Accepted \(text)") }

        // Damaged or forged links are refused.
        try expect(SharedWindowLink.decode("not-base64!") == nil, "Accepted non-base64")
        try expect(SharedWindowLink.decode(Data("not json".utf8).base64EncodedString()) == nil, "Accepted non-JSON")
        for (label, change) in [("version", ["v": 2]), ("name", ["n": "   "]), ("end", ["e": 1_791_694_800]), ("long", ["e": 1_791_694_800 + 90_000]), ("early", ["s": 12])] as [(String, [String: Any])] {
            var json = valid(); for (key, value) in change { json[key] = value }
            try expect(SharedWindowLink.decode(encodedToken(json)) == nil, "Accepted a link with a bad \(label)")
        }
        try expect(SharedWindowLink.decode(encodedToken(valid())) != nil, "Rejected a minimal valid link")

        // Text is cleaned and limited.
        var rowsJSON: [[String]] = []
        for index in 0..<12 { rowsJSON.append(["Row \(index)", "value", "🙂", "Okay"]) }
        var hostile = valid()
        hostile["n"] = "A\u{202E}B\u{0000}C   D" + String(repeating: "x", count: 200)
        hostile["c"] = rowsJSON; hostile["la"] = 500.0; hostile["lo"] = 10.0
        let cleaned = SharedWindowLink.decode(encodedToken(hostile))!
        try expect(cleaned.name.hasPrefix("A B C D"), "Control characters were kept: \(cleaned.name)")
        try expect(cleaned.name.unicodeScalars.count == 80 && cleaned.name.hasSuffix("…"), "Name was not limited")
        try expect(cleaned.assessment?.conditions.count == 8, "Rows were not limited")
        try expect(cleaned.coordinate == nil, "Out-of-range coordinates were kept")
        print("\(checks) shared-window checks passed.")
    }
}
