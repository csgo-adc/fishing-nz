import Foundation

/// The iPhone moon phase matches Android's `MoonPhaseTest`. Run with `tools/test_moon_phase_swift.sh`.
@main struct MoonPhaseRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let iso = ISO8601DateFormatter()
        // Eclipse dates, whose phase times are well published (UTC).
        for (at, target) in [("2024-04-08T18:21:00Z", 0.0), ("2025-03-14T06:55:00Z", 0.5), ("2025-09-07T18:09:00Z", 0.5),
                             ("2026-03-03T11:38:00Z", 0.5), ("2026-08-12T17:37:00Z", 0.0), ("2026-08-28T04:18:00Z", 0.5)] {
            let position = MoonPhase.position(at: iso.date(from: at)!)
            let hours = ((position - target + 1.5).truncatingRemainder(dividingBy: 1) - 0.5) * 29.530588853 * 24
            try expect(abs(hours) < 1, "\(at) is \(hours) hours off")
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        func day(_ y: Int, _ m: Int, _ d: Int) -> Date { calendar.date(from: DateComponents(year: y, month: m, day: d, hour: 9))! }
        // Full moon 2026-03-03 11:38 UTC is Wednesday 4 March, 12:38 AM in New Zealand.
        try expect(MoonPhase.forDay(day(2026, 3, 3)).name == "Waxing gibbous", "Day before full moon")
        let full = MoonPhase.forDay(day(2026, 3, 4))
        try expect(full.name == "Full moon" && full.emoji == "🌕" && full.illuminationPercent >= 99, "Full moon day")
        try expect(MoonPhase.forDay(day(2026, 3, 5)).name == "Waning gibbous", "Day after full moon")
        try expect(MoonPhase.forDay(day(2026, 8, 13)).name == "New moon", "New moon day")
        let crescent = MoonPhase.forDay(day(2026, 8, 15))
        try expect(crescent.name == "Waxing crescent" && crescent.emoji == "🌘", "Waxing crescent is lit on the left in New Zealand")
        try expect(full.tideRange == .spring && MoonPhase.forDay(day(2026, 3, 6)).tideRange == .spring, "Spring tides after full moon")
        try expect(MoonPhase.tideRange(0.25) == .neap && MoonPhase.tideRange(0.80) == .neap, "Neap tides after quarters")
        try expect(MoonPhase.tideRange(0.65) == .between && MoonPhase.tideRange(0.99) == .spring, "Between and just before new moon")
        try expect(MoonPhase.nextDay("Full moon", after: day(2026, 2, 20)).map { calendar.isDate($0, inSameDayAs: day(2026, 3, 4)) } == true, "Next full moon")
        try expect(MoonPhase.nextPhasesLine(after: day(2026, 2, 20)).hasPrefix("Next full moon Wed 4 Mar"), "Next phases line")
        print("Moon phase regression passed (\(checks) checks)")
    }
}
