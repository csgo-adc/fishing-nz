import Foundation

/// The pre-trip forecast check. Android has the same rules in `TripCheck.kt`.
enum TripCheck {
    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar
    }

    /// When to check: 7 PM the evening before a trip on a later day, otherwise 2 hours before it starts. Nil when that
    /// time is less than 10 minutes away.
    static func checkTime(start: Date, now: Date = .now) -> Date? {
        let calendar = calendar
        let startDay = calendar.startOfDay(for: start)
        let soonest = now.addingTimeInterval(600)
        let twoHoursBefore = start.addingTimeInterval(-7_200)
        if startDay > calendar.startOfDay(for: now), let dayBefore = calendar.date(byAdding: .day, value: -1, to: startDay),
           let eveningBefore = calendar.date(bySettingHour: 19, minute: 0, second: 0, of: dayBefore), eveningBefore > soonest {
            return eveningBefore
        }
        return twoHoursBefore > soonest ? twoHoursBefore : nil
    }

    /// The notification: "Raglan · Sun 18 Oct, 10:00 AM–12:00 PM" and what changed, if anything.
    static func message(trip: Recommendation, latest: Recommendation?) -> (title: String, body: String) {
        let (day, hours) = trip.windowDayAndHours
        let title = "\(trip.name) · \(day), \(hours)"
        guard let latest, latest.assessment != nil else {
            return (title, "Check the latest forecast before you go: it couldn't be updated just now.")
        }
        let was = trip.windowMood, now = latest.windowMood
        let watch = latest.watchOuts.prefix(2).joined(separator: " · ")
        let lead = now.label == was.label ? "Still \(now.emoji) \(now.label)." : "Now \(now.emoji) \(now.label), was \(was.emoji) \(was.label)."
        return (title, [lead, watch.isEmpty ? nil : "\(watch).", "Tap to see your trip."].compactMap { $0 }.joined(separator: " "))
    }

    /// A re-checked window in the form the message reads.
    static func recommendation(_ window: ScoredFishingWindow) -> Recommendation {
        Recommendation(name: window.spotName, area: window.area, rating: window.score, time: "", distance: "", reasons: window.reasons,
                       boat: window.boat, warnings: window.warnings, startsAt: window.start, endsAt: window.end, summary: window.summary,
                       conditions: window.conditions, dataComplete: window.dataComplete, tidePreferenceFit: window.tidePreferenceFit,
                       assessment: window.assessment, coordinate: window.coordinate)
    }
}
