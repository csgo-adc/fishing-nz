import Foundation

/// Saved trips and the offline cache match Android's `OfflineDataTest`. Run with `tools/test_offline_swift.sh`.
@main struct OfflineDataRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let start = Date(timeIntervalSince1970: 1_792_360_800)
        let window = Recommendation(name: "Raglan", area: "Waikato", rating: 80, time: "Sat 18 Oct · 10:00 AM–12:00 PM", distance: "12 km",
                                    reasons: ["Light wind"], boat: false, startsAt: start, endsAt: start.addingTimeInterval(7200),
                                    assessment: WindowAssessment(mood: WindowMood(emoji: "😌", label: "Comfortable"),
                                                                 conditions: [ConditionItem(title: "Wind", value: "Light wind: 8 km/h", mood: WindowMood(emoji: "🙂", label: "Good"))],
                                                                 confidence: WindowMood(emoji: "👍", label: "Good confidence")),
                                    coordinate: GeoPoint(latitude: -37.8, longitude: 174.88))
        let kawhia = Recommendation(name: "Kawhia", area: window.area, rating: 70, time: window.time, distance: "", reasons: [], boat: true,
                                    startsAt: start, endsAt: start.addingTimeInterval(3600))
        let restored = TripStore.decode(TripStore.encode(saved: [window, kawhia], active: window))
        try expect(restored.saved.map(\.name) == ["Raglan", "Kawhia"], "Saved names")
        try expect(restored.saved[0].distance == "Saved · forecast may have changed", "Saved label")
        try expect(restored.saved[0].assessment?.details == [TripStore.savedNote], "Saved note")
        try expect(restored.saved[0].assessment?.mood.label == "Comfortable" && restored.saved[0].startsAt == start, "Saved window keeps its outlook and time")
        try expect(restored.saved[0].coordinate == GeoPoint(latitude: -37.8, longitude: 174.88) && restored.saved[1].boat, "Position and boat")
        try expect(restored.active?.name == "Raglan", "Active trip")
        let untimed = Recommendation(name: "No time", area: "", rating: 0, time: "", distance: "", reasons: [], boat: false)
        try expect(TripStore.decode(TripStore.encode(saved: [untimed], active: nil)).saved.isEmpty, "Untimed windows are left out")

        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        OfflineCache.folderOverride = folder
        let key = OfflineCache.placeKey("weather", GeoPoint(latitude: -37.80012, longitude: 174.87991))
        try expect(key == "weather_-37.800_174.880.json", "Place key \(key)")
        try expect(OfflineCache.read(key) == nil, "Nothing saved yet")
        OfflineCache.write(key, Data("{\"a\":1}".utf8))
        try expect(OfflineCache.read(key).map { String(decoding: $0.data, as: UTF8.self) } == "{\"a\":1}", "Saved text")
        try expect(OfflineCache.savedLabel(ISO8601DateFormatter().date(from: "2026-10-17T23:00:00Z")!) == "Sun 18 Oct, 12:00 PM", "Saved label")
        print("Offline data regression passed (\(checks) checks)")
    }
}
