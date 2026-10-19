import Foundation

/// "Tell someone": the trip plan a person leaves with a friend or family member, as AdventureSmart and Coastguard
/// advise. Android writes the same text in `TripPlan.kt`.
enum TripPlan {
    private static let zone = TimeZone(identifier: "Pacific/Auckland")!

    private static func format(_ date: Date, _ pattern: String) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = zone
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }

    /// An hour after the window ends, rounded up to the next quarter hour.
    static func defaultBackBy(_ trip: Recommendation) -> Date? {
        guard let end = trip.endsAt ?? trip.startsAt?.addingTimeInterval(3_600) else { return nil }
        let seconds = end.timeIntervalSince1970 + 3_600
        return Date(timeIntervalSince1970: (seconds / 900).rounded(.up) * 900)
    }

    static func mapLink(_ trip: Recommendation) -> String? {
        trip.coordinate.map { String(format: "https://www.google.com/maps/search/?api=1&query=%.5f,%.5f", $0.latitude, $0.longitude) }
    }

    static func text(trip: Recommendation, backBy: Date, who: String, details: String, forecastLink: String?) -> String {
        let (day, hours) = trip.windowDayAndHours
        let who = who.trimmingCharacters(in: .whitespacesAndNewlines), details = details.trimmingCharacters(in: .whitespacesAndNewlines)
        let lines: [String?] = [
            "My fishing trip plan (Fishdays - NZ)",
            "Where: \(trip.name), \(trip.area) · \(trip.boat ? "boat" : "land") fishing",
            mapLink(trip).map { "Map: \($0)" },
            trip.startsAt == nil ? nil : "When: \(day), \(hours)",
            "Back by: \(format(backBy, "EEE d MMM, h:mm a"))",
            who.isEmpty ? nil : "Who: \(who)",
            details.isEmpty ? nil : "Details: \(details)",
            forecastLink.map { "Forecast: \($0)" },
            "",
            "If you haven't heard from me by \(format(backBy, "h:mm a")), try calling me. If you can't reach me and you're worried, call 111 and ask for the Police."
        ]
        return lines.compactMap { $0 }.joined(separator: "\n")
    }

    /// The reminder at the back-by time.
    static func checkInMessage(_ trip: Recommendation) -> (title: String, body: String) {
        ("Back from \(trip.name)?", "Let the person you told know you're back safe.")
    }
}
