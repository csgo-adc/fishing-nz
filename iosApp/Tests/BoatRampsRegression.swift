import Foundation

/// The iPhone boat ramp rules match Android's `BoatRampsTest`. Run with `tools/test_boat_ramps_swift.sh`.
@main struct BoatRampsRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        let source = try Data(contentsOf: root.appendingPathComponent("data/boat_ramps.json"))
        let bundled = try Data(contentsOf: root.appendingPathComponent("iosApp/CatchCheckNZ/Resources/boat_ramps.json"))
        try expect(source == bundled, "Bundled copy is out of date")
        let ramps = try BoatRamps.decode(source)
        try expect(ramps.count > 900, "Ramp count")
        try expect(ramps.first { $0.name == nil }?.label == "Boat ramp", "Unnamed label")
        let boatSpots = fishingSpots.filter(\.boat)
        let covered = boatSpots.filter { !BoatRamps.nearest($0.coordinate, in: ramps, limit: 1, withinKm: 15).isEmpty }.count
        try expect(covered * 10 >= boatSpots.count * 8, "\(covered) of \(boatSpots.count) boat areas have a ramp within 15 km")
        let kawakawa = fishingSpots.first { $0.name == "Kawakawa Bay" && $0.boat }!
        let nearest = BoatRamps.nearest(kawakawa.coordinate, in: ramps)
        try expect(!nearest.isEmpty && nearest.count <= 3 && nearest.map(\.km) == nearest.map(\.km).sorted() && nearest.allSatisfy { $0.km <= 25 }, "Nearest ramps")
        let iso = ISO8601DateFormatter()
        let start = iso.date(from: "2026-10-17T21:00:00Z")!, end = start.addingTimeInterval(7_200)
        try expect(BoatRamps.lowTideNote(lows: [iso.date(from: "2026-10-17T22:05:00Z")!], start: start, end: end)
                   == "Low tide 11:05 AM, during your trip. Some ramps can't be used near low tide: check the ramp's limits.", "Low tide during")
        try expect(BoatRamps.lowTideNote(lows: [iso.date(from: "2026-10-17T20:30:00Z")!], start: start, end: end)?.contains("just before your trip") == true, "Low tide before")
        try expect(BoatRamps.lowTideNote(lows: [iso.date(from: "2026-10-18T03:00:00Z")!], start: start, end: end) == nil, "No low tide")
        try expect(BoatRamps.distanceLabel(0.456) == "450 m" && BoatRamps.distanceLabel(2.06) == "2.1 km", "Distance labels")
        try expect(BoatRamps.direction(from: GeoPoint(latitude: -36.8, longitude: 174.9), to: GeoPoint(latitude: -36.85, longitude: 174.84)) == "SW"
                   && BoatRamps.direction(from: GeoPoint(latitude: -36.8, longitude: 174.9), to: GeoPoint(latitude: -36.7, longitude: 174.9)) == "N", "Directions")
        print("Boat ramp regression passed (\(checks) checks)")
    }
}
