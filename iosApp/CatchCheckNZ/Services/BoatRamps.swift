import Foundation

/// One public boat ramp from OpenStreetMap. `name` is nil for the many unnamed ramps.
struct BoatRamp: Decodable, Sendable {
    let lat: Double
    let lon: Double
    let name: String?
    let fee: String?
    var point: GeoPoint { GeoPoint(latitude: lat, longitude: lon) }
    var label: String { name ?? "Boat ramp" }
}

/// Boat ramps bundled from `tools/build_boat_ramps.py` (© OpenStreetMap contributors, ODbL). Android has the same rules
/// in `BoatRamps.kt`. Access, condition and tide limits are not known, so the app says so wherever ramps show.
enum BoatRamps {
    static let creditURL = URL(string: "https://www.openstreetmap.org/copyright")!
    static let caution = "Public access, condition and tide limits aren't checked."

    private struct Catalogue: Decodable { let schema_version: Int; let ramps: [BoatRamp] }

    /// The bundled ramps, read once. Empty if the file is missing or unreadable.
    static let bundled: [BoatRamp] = Bundle.main.url(forResource: "boat_ramps", withExtension: "json")
        .flatMap { try? Data(contentsOf: $0) }.flatMap { try? decode($0) } ?? []

    static func decode(_ data: Data) throws -> [BoatRamp] {
        let catalogue = try JSONDecoder().decode(Catalogue.self, from: data)
        guard catalogue.schema_version == 1 else { throw CocoaError(.coderInvalidValue) }
        return catalogue.ramps
    }

    /// Up to `limit` ramps within `withinKm` of `point`, nearest first.
    static func nearest(_ point: GeoPoint, in ramps: [BoatRamp], limit: Int = 3, withinKm: Double = 25) -> [(ramp: BoatRamp, km: Double)] {
        Array(ramps.lazy
            .filter { abs($0.lat - point.latitude) < withinKm / 100 && abs($0.lon - point.longitude) < withinKm / 70 }
            .map { (ramp: $0, km: placeDistanceKm(point, $0.point)) }
            .filter { $0.km <= withinKm }
            .sorted { $0.km < $1.km }
            .prefix(limit))
    }

    /// "Low tide 1:05 PM, during your trip. …" when a low tide falls within an hour of the trip, else nil.
    static func lowTideNote(lows: [Date], start: Date, end: Date) -> String? {
        guard let low = lows.first(where: { $0 >= start.addingTimeInterval(-3_600) && $0 <= end.addingTimeInterval(3_600) }) else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
        formatter.dateFormat = "h:mm a"
        let position = low < start ? "just before your trip" : low > end ? "just after your trip" : "during your trip"
        return "Low tide \(formatter.string(from: low)), \(position). Some ramps can't be used near low tide: check the ramp's limits."
    }

    /// The 8-point compass direction from `from` to `to`, such as "SW".
    static func direction(from: GeoPoint, to: GeoPoint) -> String {
        let east = (to.longitude - from.longitude) * cos(from.latitude * .pi / 180), north = to.latitude - from.latitude
        let degrees = (atan2(east, north) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
        return ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int((degrees + 22.5) / 45) % 8]
    }

    /// "450 m" or "2.1 km".
    static func distanceLabel(_ km: Double) -> String { km < 1 ? "\(Int(km * 1000 / 10) * 10) m" : String(format: "%.1f km", km) }
}
