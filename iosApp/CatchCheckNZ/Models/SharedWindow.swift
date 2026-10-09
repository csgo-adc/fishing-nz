import Foundation

/// The link behind "Share this window": `https://fishing.fishnz.space/w/<token>`, where the token is the unpadded
/// base64url of a small JSON snapshot. The Android app writes and reads the same format and the API Worker draws it as a
/// web page; see docs/share-fishing-window.md. Links can come from anywhere, so reading one validates and limits every field.
enum SharedWindowLink {
    static let origin = "https://fishing.fishnz.space"
    /// The custom scheme the web page's "Open in the app" button uses, as a fallback where Universal Links are not followed.
    static let appScheme = "nz.fishingnz.catchcheck"

    private static let version = 1
    // Eight characters from an alphabet without the look-alikes 0 O 1 I l. No real token is this short.
    private static let shortIDAlphabet = Set("23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
    private static let maxRows = 8
    private static let maxWindowSeconds = 24 * 3600
    private static let earliest = 1_704_067_200 // 2024-01-01
    private static let latest = 4_102_444_800 // 2100-01-01

    private struct Payload: Codable {
        var v: Int
        var n: String
        var a: String
        var b: Int
        var s: Int
        var e: Int
        var la: Double?
        var lo: Double?
        var o: [String]?
        var f: [String]?
        var c: [[String]]?
        var r: String?
        var t: Int?
    }

    /// True when `window` has the start and end time a link needs; a spot saved from the map has neither.
    static func canShare(_ window: Recommendation) -> Bool {
        guard let start = window.startsAt, let end = window.endsAt else { return false }
        return end > start
    }

    /// The long link, which holds the whole window in the address, or nil when it has no start and end time to share.
    static func longURL(for window: Recommendation, now: Date = .now) -> URL? {
        encode(window, now: now).flatMap { URL(string: "\(origin)/w/\($0)") }
    }

    /// The short link the service made for a window, `https://fishing.fishnz.space/w/k3F9xQ2m`.
    static func shortURL(id: String) -> String { "\(origin)/w/\(id)" }

    /// True for the service's short ids, which have to be looked up, and false for a token that holds the window itself.
    static func isShortID(_ token: String) -> Bool {
        token.count == 8 && token.allSatisfy { shortIDAlphabet.contains($0) }
    }

    /// The text the share sheet sends: where and when, how it looks, and the link.
    static func text(for window: Recommendation, url: URL) -> String {
        "Fishing window: \(window.name), \(window.area)\n\(window.time) · \(window.windowOutlook)\n\(url.absoluteString)"
    }

    static func encode(_ window: Recommendation, now: Date = .now) -> String? {
        guard let start = window.startsAt, let end = window.endsAt, end > start else { return nil }
        let mood = window.windowMood
        var payload = Payload(
            v: version, n: clip(window.name, 80), a: clip(window.area, 80), b: window.boat ? 1 : 0,
            s: Int(start.timeIntervalSince1970.rounded()), e: Int(end.timeIntervalSince1970.rounded()))
        if let place = window.coordinate, place.latitude.isFinite, place.longitude.isFinite {
            payload.la = round4(place.latitude)
            payload.lo = round4(place.longitude)
        }
        payload.o = [clip(mood.emoji, 8), clip(mood.label, 40)]
        if let assessment = window.assessment {
            payload.f = [clip(assessment.confidence.emoji, 8), clip(assessment.confidence.label, 40)]
            payload.c = assessment.conditions.prefix(maxRows).map {
                [clip($0.title, 24), clip($0.value, 120), clip($0.mood.emoji, 8), clip($0.mood.label, 40)]
            }
        }
        let reason = (window.reasons.first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } ?? window.summary)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !reason.isEmpty { payload.r = clip(reason, 200) }
        payload.t = Int(now.timeIntervalSince1970)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        guard let data = try? encoder.encode(payload) else { return nil }
        return data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    /// The token in a shared-window link, from the https address or the app's own scheme; nil for any other address.
    static func token(from url: URL) -> String? {
        let parts = url.pathComponents.dropFirst().filter { $0 != "/" }
        let scheme = url.scheme?.lowercased(), host = url.host?.lowercased()
        let candidate: String?
        if scheme == "https", host == URL(string: origin)?.host?.lowercased() {
            candidate = parts.count == 2 && parts.first == "w" ? parts.last : nil
        } else if scheme == appScheme, host == "w" {
            candidate = parts.count == 1 ? parts.first : nil
        } else {
            candidate = nil
        }
        guard let candidate, (1...4096).contains(candidate.count),
              candidate.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_") }) else { return nil }
        return candidate
    }

    /// A window read from a long link, or nil for anything else. A short link needs `ShareLinkService.window(forToken:)`.
    static func window(from url: URL) -> Recommendation? {
        token(from: url).flatMap { isShortID($0) ? nil : decode($0) }
    }

    static func decode(_ token: String) -> Recommendation? {
        var base64 = token.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        base64 += String(repeating: "=", count: (4 - base64.count % 4) % 4)
        guard let data = Data(base64Encoded: base64), let payload = try? JSONDecoder().decode(Payload.self, from: data),
              payload.v == version, (earliest...latest).contains(payload.s), (earliest...latest).contains(payload.e),
              payload.e > payload.s, payload.e - payload.s <= maxWindowSeconds,
              let name = line(payload.n, 80), let area = line(payload.a, 80) else { return nil }
        let rows = (payload.c ?? []).prefix(maxRows).compactMap { row -> ConditionItem? in
            guard row.count >= 4, let title = line(row[0], 24), let value = lines(row[1], 120),
                  let rowMood = readMood(row[2], row[3]) else { return nil }
            return ConditionItem(title: title, value: value, mood: rowMood)
        }
        let reason = line(payload.r ?? "", 200) ?? ""
        let outlook = readMood(payload.o) ?? WindowMood(emoji: "🤔", label: "Shared window")
        let confidence = readMood(payload.f) ?? WindowMood(emoji: "🤔", label: "Limited confidence")
        let place: GeoPoint? = {
            guard let latitude = payload.la, let longitude = payload.lo, abs(latitude) <= 90, abs(longitude) <= 180 else { return nil }
            return GeoPoint(latitude: latitude, longitude: longitude)
        }()
        let start = Date(timeIntervalSince1970: TimeInterval(payload.s)), end = Date(timeIntervalSince1970: TimeInterval(payload.e))
        let snapshot = "Shared with you: these conditions were captured when the window was shared and may have changed. Search from Home for a live forecast."
        return Recommendation(
            name: name, area: area, rating: 0, time: timeText(start, end),
            distance: "Shared with you · forecast may have changed",
            reasons: reason.isEmpty ? [] : [reason], boat: payload.b == 1, startsAt: start, endsAt: end, summary: reason,
            conditions: rows.map { "\($0.title): \($0.value.replacingOccurrences(of: "\n", with: " ")) · \($0.mood.emoji) \($0.mood.label)" },
            assessment: WindowAssessment(mood: outlook, conditions: rows, confidence: confidence, details: [snapshot]),
            coordinate: place)
    }

    /// "Sun 11 Oct · 6:00 PM–8:00 PM" in New Zealand time, the same wording the app shows for its own windows.
    private static func timeText(_ start: Date, _ end: Date) -> String {
        let zone = TimeZone(identifier: "Pacific/Auckland")!
        func format(_ date: Date, _ pattern: String, _ locale: String) -> String {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: locale)
            formatter.timeZone = zone
            formatter.dateFormat = pattern
            return formatter.string(from: date)
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let endTime = format(end, "h:mm a", "en_US_POSIX")
        let endLabel = calendar.isDate(start, inSameDayAs: end) ? endTime : "\(format(end, "EEE d MMM", "en_NZ")) \(endTime)"
        return "\(format(start, "EEE d MMM", "en_NZ")) · \(format(start, "h:mm a", "en_US_POSIX"))–\(endLabel)"
    }

    private static let unsafe: CharacterSet = {
        var set = CharacterSet.controlCharacters
        set.insert(charactersIn: "\u{2028}\u{2029}\u{200E}\u{200F}\u{202A}\u{202B}\u{202C}\u{202D}\u{202E}\u{2066}\u{2067}\u{2068}\u{2069}\u{FEFF}")
        return set
    }()

    /// One line of plain text, or nil when it is empty.
    private static func line(_ value: String, _ max: Int) -> String? {
        let cleaned = value.unicodeScalars.map { unsafe.contains($0) ? " " : String($0) }.joined()
        let text = cleaned.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return text.isEmpty ? nil : clip(text, max)
    }

    /// Up to three short lines; a tide row uses a second line for the reference station.
    private static func lines(_ value: String, _ max: Int) -> String? {
        let parts = value.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n", omittingEmptySubsequences: true)
            .compactMap { line(String($0), max) }
        return parts.isEmpty ? nil : parts.prefix(3).joined(separator: "\n")
    }

    private static func readMood(_ pair: [String]?) -> WindowMood? {
        guard let pair, pair.count >= 2 else { return nil }
        return readMood(pair[0], pair[1])
    }

    private static func readMood(_ emoji: String, _ label: String) -> WindowMood? {
        guard let emoji = line(emoji, 8), let label = line(label, 40) else { return nil }
        return WindowMood(emoji: emoji, label: label)
    }

    private static func round4(_ value: Double) -> Double { (value * 10_000).rounded() / 10_000 }

    /// At most `max` characters (Unicode scalars), ending in an ellipsis when something was cut.
    private static func clip(_ value: String, _ max: Int) -> String {
        let text = value.trimmingCharacters(in: .whitespacesAndNewlines)
        let scalars = Array(text.unicodeScalars)
        guard scalars.count > max else { return text }
        var cut = String.UnicodeScalarView()
        cut.append(contentsOf: scalars.prefix(max - 1))
        return String(cut).trimmingCharacters(in: .whitespaces) + "…"
    }
}
