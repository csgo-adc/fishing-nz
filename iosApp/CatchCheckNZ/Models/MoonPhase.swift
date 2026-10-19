import Foundation

/// Spring tides follow new and full moons (a bigger range); neap tides follow the quarter moons (a smaller range).
enum TideRange: Sendable {
    case spring, neap, between
    var label: String {
        switch self {
        case .spring: "Spring tides"
        case .neap: "Neap tides"
        case .between: "Between spring and neap"
        }
    }
    var explanation: String {
        switch self {
        case .spring: "A bigger range than usual: higher highs and lower lows."
        case .neap: "A smaller range than usual: lower highs and higher lows."
        case .between: "A middling tide range."
        }
    }
}

/// The moon on one New Zealand day. `emoji` shows it as seen from New Zealand, lit on the left while it waxes.
struct MoonDay: Equatable, Sendable {
    let name: String
    let emoji: String
    let illuminationPercent: Int
    let tideRange: TideRange
    var summary: String { "\(emoji) \(name) · \(illuminationPercent)% lit" }
}

/// Moon phase from Meeus's low-precision lunar elongation (Astronomical Algorithms, ch. 47–48), within about half an
/// hour of the published phase times. Android has the same rules in `MoonPhase.kt`. Planning information only: it
/// describes the tide range, never how many fish to expect.
enum MoonPhase {
    private static let synodicDays = 29.530588853
    private static let principal: [(Double, String)] = [(0, "New moon"), (0.25, "First quarter"), (0.5, "Full moon"), (0.75, "Last quarter")]
    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar
    }

    /// 0 at new moon, 0.25 first quarter, 0.5 full and 0.75 last quarter.
    static func position(at date: Date) -> Double {
        let t = (date.timeIntervalSince1970 / 86_400 + 2_440_587.5 - 2_451_545.0) / 36_525
        let d = 297.8501921 + 445_267.1114034 * t - 0.0018819 * t * t + t * t * t / 545_868 - t * t * t * t / 113_065_000
        let m = 357.5291092 + 35_999.0502909 * t - 0.0001536 * t * t + t * t * t / 24_490_000
        let mp = 134.9633964 + 477_198.8675055 * t + 0.0087414 * t * t + t * t * t / 69_699 - t * t * t * t / 14_712_000
        func s(_ degrees: Double) -> Double { sin(degrees * .pi / 180) }
        let elongation = d + 6.289 * s(mp) - 2.100 * s(m) + 1.274 * s(2 * d - mp) + 0.658 * s(2 * d) + 0.214 * s(2 * mp) + 0.110 * s(d)
        return (elongation.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) / 360
    }

    static func illumination(at date: Date) -> Double { (1 - cos(2 * .pi * position(at: date))) / 2 }

    /// The moon on the New Zealand calendar day that contains `day`.
    static func forDay(_ day: Date) -> MoonDay {
        let calendar = calendar
        let start = calendar.startOfDay(for: day)
        let end = calendar.date(byAdding: .day, value: 1, to: start)!
        let noon = calendar.date(bySettingHour: 12, minute: 0, second: 0, of: start)!
        let from = position(at: start), until = position(at: end), p = position(at: noon)
        // A principal phase is named on the day it happens; between them, the noon position decides.
        let name = principal.first { target, _ in target == 0 ? until < from : from < target && target <= until }?.1
            ?? (p < 0.25 ? "Waxing crescent" : p < 0.5 ? "Waxing gibbous" : p < 0.75 ? "Waning gibbous" : "Waning crescent")
        return MoonDay(name: name, emoji: emoji(name), illuminationPercent: Int((illumination(at: noon) * 100).rounded()), tideRange: tideRange(p))
    }

    /// The next day after `day` (within about a month) whose moon is `phase`, such as "Full moon".
    static func nextDay(_ phase: String, after day: Date) -> Date? {
        let calendar = calendar
        let start = calendar.startOfDay(for: day)
        return (1...31).lazy.compactMap { calendar.date(byAdding: .day, value: $0, to: start) }.first { forDay($0).name == phase }
    }

    /// "Next full moon Mon 26 Oct · Next new moon Tue 10 Nov", soonest first.
    static func nextPhasesLine(after day: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "EEE d MMM"
        return ["Full moon", "New moon"].compactMap { phase in nextDay(phase, after: day).map { (phase, $0) } }
            .sorted { $0.1 < $1.1 }
            .map { "Next \($0.0.lowercased()) \(formatter.string(from: $0.1))" }
            .joined(separator: " · ")
    }

    static func tideRange(_ position: Double) -> TideRange {
        // Days after the latest new or full moon, and after the latest quarter. Ranges peak a day or two after each.
        let afterSyzygy = position.truncatingRemainder(dividingBy: 0.5) * synodicDays
        let afterQuarter = (position + 0.25).truncatingRemainder(dividingBy: 0.5) * synodicDays
        let halfCycle = synodicDays / 2
        if afterSyzygy <= 3 || afterSyzygy >= halfCycle - 0.5 { return .spring }
        if afterQuarter <= 3 || afterQuarter >= halfCycle - 0.5 { return .neap }
        return .between
    }

    private static func emoji(_ name: String) -> String {
        switch name {
        case "New moon": "🌑"
        case "Waxing crescent": "🌘"
        case "First quarter": "🌗"
        case "Waxing gibbous": "🌖"
        case "Full moon": "🌕"
        case "Waning gibbous": "🌔"
        case "Last quarter": "🌓"
        default: "🌒"
        }
    }
}
