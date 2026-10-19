import Foundation

/// The pre-trip check matches Android's `TripCheckTest`. Run with `tools/test_trip_check_swift.sh`; add `--live` to
/// re-check a Raglan window tomorrow against the live forecast.
@main struct TripCheckRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() async throws {
        let iso = ISO8601DateFormatter()
        let start = iso.date(from: "2026-10-17T21:00:00Z")! // Sunday 18 October 2026, 10 AM in New Zealand
        try expect(TripCheck.checkTime(start: start, now: iso.date(from: "2026-10-15T22:00:00Z")!) == iso.date(from: "2026-10-17T06:00:00Z"), "Evening before")
        try expect(TripCheck.checkTime(start: start, now: iso.date(from: "2026-10-17T07:00:00Z")!) == iso.date(from: "2026-10-17T19:00:00Z"), "Two hours before")
        try expect(TripCheck.checkTime(start: start, now: iso.date(from: "2026-10-17T18:55:00Z")!) == nil, "Too close")

        func window(_ label: String, _ emoji: String, gusts: Bool = false) -> Recommendation {
            Recommendation(name: "Raglan", area: "Waikato", rating: 80, time: "", distance: "", reasons: [], boat: false,
                           startsAt: start, endsAt: start.addingTimeInterval(7_200),
                           assessment: WindowAssessment(mood: WindowMood(emoji: emoji, label: label),
                                                        conditions: gusts ? [ConditionItem(title: "Wind", value: "22 km/h · gust 45 · SW", mood: WindowMood(emoji: "😣", label: "Uncomfortable"))] : [],
                                                        confidence: WindowMood(emoji: "👍", label: "Good confidence")))
        }
        let trip = window("Comfortable", "😌")
        try expect(TripCheck.message(trip: trip, latest: nil).title == "Raglan · Sun 18 Oct, 10:00 AM–12:00 PM", "Title")
        try expect(TripCheck.message(trip: trip, latest: window("Comfortable", "😌")).body == "Still 😌 Comfortable. Tap to see your trip.", "Unchanged")
        let changed = TripCheck.message(trip: trip, latest: window("Uncomfortable", "😣", gusts: true)).body
        try expect(changed == "Now 😣 Uncomfortable, was 😌 Comfortable. Gusts to 45 km/h. Tap to see your trip.", "Changed: \(changed)")
        try expect(TripCheck.message(trip: trip, latest: nil).body == "Check the latest forecast before you go: it couldn't be updated just now.", "Unavailable")

        // Tell someone: the same plan text as Android's TripPlanTest.
        let planned = Recommendation(name: "Raglan", area: "Waikato", rating: 80, time: "", distance: "", reasons: [], boat: false,
                                     startsAt: start, endsAt: start.addingTimeInterval(7_200), coordinate: GeoPoint(latitude: -37.8, longitude: 174.88))
        try expect(TripPlan.defaultBackBy(planned) == iso.date(from: "2026-10-18T00:00:00Z"), "Back by an hour after")
        let shifted = Recommendation(name: "Raglan", area: "Waikato", rating: 80, time: "", distance: "", reasons: [], boat: false,
                                     startsAt: start.addingTimeInterval(600), endsAt: start.addingTimeInterval(7_800))
        try expect(TripPlan.defaultBackBy(shifted) == iso.date(from: "2026-10-18T00:15:00Z"), "Back by on a quarter hour")
        let plan = TripPlan.text(trip: planned, backBy: iso.date(from: "2026-10-18T00:00:00Z")!, who: "Me and Sam", details: " White Hilux at the ramp ",
                                 forecastLink: "https://fishing.fishnz.space/w/k3F9xQ2m")
        let expected = """
            My fishing trip plan (Fishdays - NZ)
            Where: Raglan, Waikato · land fishing
            Map: https://www.google.com/maps/search/?api=1&query=-37.80000,174.88000
            When: Sun 18 Oct, 10:00 AM–12:00 PM
            Back by: Sun 18 Oct, 1:00 PM
            Who: Me and Sam
            Details: White Hilux at the ramp
            Forecast: https://fishing.fishnz.space/w/k3F9xQ2m

            If you haven't heard from me by 1:00 PM, try calling me. If you can't reach me and you're worried, call 111 and ask for the Police.
            """
        try expect(plan == expected, "Plan text:\n\(plan)")

        if CommandLine.arguments.contains("--live") {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
            let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: .now))!
            let liveStart = calendar.date(bySettingHour: 10, minute: 0, second: 0, of: tomorrow)!
            let raglan = fishingSpots.first { $0.name == "Raglan" && !$0.boat } ?? fishingSpots.first { !$0.boat }!
            let live = Recommendation(name: raglan.name, area: raglan.area, rating: 0, time: "", distance: "", reasons: [], boat: false,
                                      startsAt: liveStart, endsAt: liveStart.addingTimeInterval(7_200), coordinate: raglan.coordinate)
            let result = await FishingScoringService.recheck(live, priority: .weather, land: LandPreferences(arrivalMinutes: 0, returnMinutes: 0), tidePhase: .aroundHigh)
            try expect(result?.start == liveStart && result?.assessment != nil, "Live re-check of \(raglan.name) tomorrow 10 AM")
            print("Live: \(TripCheck.message(trip: live, latest: result.map(TripCheck.recommendation)).body)")
        }
        print("Trip check regression passed (\(checks) checks)")
    }
}
