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

    /// A stand-in for the service: it answers with fixed values, or with nothing, like an offline phone.
    struct Stub: ShareBackend {
        var id: String? = "k3F9xQ2m"
        var stored: String?
        func create(token: String) async -> String? { id }
        func read(id: String) async -> String? { stored }
    }

    static func main() async throws {
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
        let link = SharedWindowLink.longURL(for: original, now: start.addingTimeInterval(-86_400))!
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
        try expect(SharedWindowLink.longURL(for: window(rows: rows))!.absoluteString.count < 1_000, "Link with six conditions is too long")

        // A window with no times cannot be shared.
        var untimed = original; untimed.startsAt = nil
        try expect(SharedWindowLink.longURL(for: untimed) == nil, "Window without a start was shareable")

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
        // Short ids are told apart from windows in the address.
        for id in ["k3F9xQ2m", "23456789", "ZZzzZZzz"] { try expect(SharedWindowLink.isShortID(id), "\(id) should be a short id") }
        for id in ["k3F9xQ2", "k3F9xQ2mm", "k3F9xQ20", "k3F9xQ2l", "k3F9xQ2I", "k3F9xQ2O", "k3F9xQ21", "", goldenToken] {
            try expect(!SharedWindowLink.isShortID(id), "\(id.prefix(12)) should not be a short id")
        }
        try expect(SharedWindowLink.shortURL(id: "k3F9xQ2m") == "https://fishing.fishnz.space/w/k3F9xQ2m", "Short link has the wrong address")
        try expect(SharedWindowLink.token(from: URL(string: "https://fishing.fishnz.space/w/k3F9xQ2m")!) == "k3F9xQ2m", "Short link was not recognised")
        try expect(SharedWindowLink.token(from: URL(string: "nz.fishingnz.catchcheck://w/k3F9xQ2m")!) == "k3F9xQ2m", "Short link in the app scheme was not recognised")
        try expect(SharedWindowLink.window(from: URL(string: "https://fishing.fishnz.space/w/k3F9xQ2m")!) == nil, "A short link cannot be read without the service")
        try expect(SharedWindowLink.canShare(original) && !SharedWindowLink.canShare(untimed), "canShare disagrees with the window's times")

        // Sharing: a short link when the service answers, the long link otherwise.
        let short = await ShareLinkService(backend: Stub()).link(for: original)
        try expect(short?.absoluteString == "https://fishing.fishnz.space/w/k3F9xQ2m", "Short link was not used")
        let offline = await ShareLinkService(backend: Stub(id: nil)).link(for: original)
        try expect((offline?.absoluteString.count ?? 0) > 200 && SharedWindowLink.window(from: offline!)?.name == "Takapuna Beach", "Offline share did not fall back to the long link")
        for bad in ["", "evil", "https://evil.example/x", "k3F9xQ20", "k3F9xQ2mextra"] {
            let link = await ShareLinkService(backend: Stub(id: bad)).link(for: original)
            try expect((link?.absoluteString.count ?? 0) > 200, "An id that is not ours was shared: \(bad)")
        }
        let untimedLink = await ShareLinkService(backend: Stub()).link(for: untimed)
        try expect(untimedLink == nil, "A window without a start was sent to the service")

        // Opening: a short link is looked up, a long one is read from the address.
        let longToken = SharedWindowLink.encode(original)!
        let looked = await ShareLinkService(backend: Stub(stored: longToken)).window(forToken: "k3F9xQ2m")
        try expect(looked?.name == "Takapuna Beach", "Short link did not open the stored window")
        let direct = await ShareLinkService(backend: Stub(stored: nil)).window(forToken: longToken)
        try expect(direct?.name == "Takapuna Beach", "Long link needed the service")
        let unknown = await ShareLinkService(backend: Stub(stored: nil)).window(forToken: "k3F9xQ2m")
        try expect(unknown == nil, "Unknown short link opened something")
        let broken = await ShareLinkService(backend: Stub(stored: "garbage")).window(forToken: "k3F9xQ2m")
        try expect(broken == nil, "Broken stored value opened something")
        let damaged = await ShareLinkService(backend: Stub()).window(forToken: "not-a-window")
        try expect(damaged == nil, "Damaged link opened something")
        print("\(checks) shared-window checks passed.")
    }
}
