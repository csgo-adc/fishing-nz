import Foundation

/// The window list cache matches Android's `WindowCacheTest`. Run with `tools/test_window_cache_swift.sh`.
@main struct WindowCacheRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let raglan = GeoPoint(latitude: -37.8, longitude: 174.87)
        let start = Date(timeIntervalSince1970: 1_792_360_800)
        let now = start.addingTimeInterval(-3_600)
        let assessment = WindowAssessment(mood: WindowMood(emoji: "😌", label: "Comfortable"),
                                          conditions: [ConditionItem(title: "Wind", value: "Light wind: 8 km/h", mood: WindowMood(emoji: "🙂", label: "Good"))],
                                          confidence: WindowMood(emoji: "👍", label: "Good confidence"), details: ["Check the bar"], band: 1,
                                          maxWind: 12, maxGust: 20.5, rainTotal: 0.4, priority: .casting, preference: .tide)
        let window = ScoredFishingWindow(id: "raglan", spotName: "Raglan", area: "Waikato", boat: true, score: 80, start: start,
                                         end: start.addingTimeInterval(7_200), distanceKm: 12.4, reasons: ["Light wind"], warnings: [],
                                         summary: "Calm morning", conditions: ["Wind: 8 km/h"], rankingValue: 81.5, daylightFraction: 0.75,
                                         tidePreferenceFit: 0.5, assessment: assessment, coordinate: GeoPoint(latitude: -37.8, longitude: 174.88))
        let ended = ScoredFishingWindow(id: "kawhia", spotName: "Kawhia", area: "Waikato", boat: true, score: 70, start: start.addingTimeInterval(-10_800),
                                        end: start.addingTimeInterval(-3_600), distanceKm: .nan, reasons: [], warnings: [])
        func entry(savedAt: Date = now, key: String = "land|50", origin: GeoPoint = raglan, build: Int = 72) -> WindowCache.Entry {
            WindowCache.Entry(key: key, origin: origin, savedAt: savedAt, build: build, windows: [window, ended], failedSpots: [], spotsWithoutWindows: ["Aotea"])
        }

        // Survives a restart, including a missing distance.
        let restored = try WindowCache.decode(WindowCache.encode([entry()]))
        try expect(restored.count == 1 && restored[0].windows[0].assessment == assessment && restored[0].windows[0].coordinate == window.coordinate, "Round trip")
        try expect(restored[0].windows[1].distanceKm.isNaN && restored[0].spotsWithoutWindows == ["Aotea"], "NaN and spots")

        // Fresh for an hour, then shown while refreshing until six hours; ended windows are left out.
        let entries = [entry()]
        try expect(WindowCache.lookup(entries, key: "land|50", origin: raglan, now: now.addingTimeInterval(59 * 60), build: 72)?.freshness == .fresh, "Fresh")
        let stale = WindowCache.lookup(entries, key: "land|50", origin: raglan, now: now.addingTimeInterval(2 * 3_600), build: 72)
        try expect(stale?.freshness == .stale && stale?.entry.windows.map(\.spotName) == ["Raglan"], "Stale without ended")
        try expect(WindowCache.lookup([entry(savedAt: now.addingTimeInterval(-6 * 3_600 - 1))], key: "land|50", origin: raglan, now: now, build: 72) == nil, "Too old")

        // Another plan, build or place searches again; about 1 km of drift does not.
        try expect(WindowCache.lookup(entries, key: "boat|50", origin: raglan, now: now, build: 72) == nil, "Other plan")
        try expect(WindowCache.lookup(entries, key: "land|50", origin: raglan, now: now, build: 73) == nil, "Other build")
        try expect(WindowCache.lookup(entries, key: "land|50", origin: GeoPoint(latitude: -37.809, longitude: 174.87), now: now, build: 72) != nil, "Drift")
        try expect(WindowCache.lookup(entries, key: "land|50", origin: GeoPoint(latitude: -37.827, longitude: 174.87), now: now, build: 72) == nil, "Moved")

        // A list whose windows have all ended is searched again.
        let afterAll = start.addingTimeInterval(3 * 3_600)
        try expect(WindowCache.lookup([entry(savedAt: afterAll.addingTimeInterval(-60))], key: "land|50", origin: raglan, now: afterAll, build: 72) == nil, "All ended")

        // The last six plans, newest first; a day-old list still stands in when a search fails.
        var kept: [WindowCache.Entry] = []
        for index in 0..<8 { kept = WindowCache.storing(kept, entry(savedAt: now.addingTimeInterval(Double(index)), key: "plan\(index)")) }
        try expect(kept.map(\.key) == ["plan7", "plan6", "plan5", "plan4", "plan3", "plan2"], "Six newest: \(kept.map(\.key))")
        kept = WindowCache.storing(kept, entry(savedAt: now.addingTimeInterval(10), key: "plan4"))
        try expect(kept.map(\.key) == ["plan4", "plan7", "plan6", "plan5", "plan3", "plan2"], "Replaced")
        let dayOld = [entry(savedAt: now.addingTimeInterval(-20 * 3_600))]
        try expect(WindowCache.lookup(dayOld, key: "land|50", origin: raglan, now: now, build: 72) == nil, "Not shown normally")
        try expect(WindowCache.lookup(dayOld, key: "land|50", origin: raglan, now: now, build: 72, maxAge: WindowCache.offlineFor) != nil, "Offline fallback")

        print("Window cache regression passed (\(checks) checks)")
    }
}
