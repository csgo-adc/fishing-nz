import Foundation

/// One wharf, breakwater or groyne from OpenStreetMap. `kind` is "wharf", "breakwater" or "groyne"; `name` is nil for
/// unnamed ones.
struct ShoreStructure: Decodable, Sendable {
    let lat: Double
    let lon: Double
    let name: String?
    let kind: String
    var point: GeoPoint { GeoPoint(latitude: lat, longitude: lon) }
    var kindLabel: String { kind == "breakwater" || kind == "groyne" ? kind : "wharf" }
    var label: String { name ?? (kind == "breakwater" ? "Breakwater" : kind == "groyne" ? "Groyne" : "Wharf or jetty") }
    /// The map card's subtitle, such as "Breakwater · © OpenStreetMap · access and fishing rules not checked".
    var mapSubtitle: String { kindLabel.prefix(1).uppercased() + kindLabel.dropFirst() + " · © OpenStreetMap · access and fishing rules not checked" }
}

/// Wharves, breakwaters and groynes bundled from `tools/build_shore_structures.py` (© OpenStreetMap contributors, ODbL).
/// Android has the same rules in `ShoreStructures.kt`. A mapped wharf isn't permission to fish, so the app says so.
enum ShoreStructures {
    static let credit = "Wharves © OpenStreetMap contributors"
    static let creditURL = URL(string: "https://www.openstreetmap.org/copyright")!
    static let caution = "A wharf on the map doesn't mean you can fish there: ports, ferry wharves and some council wharves ban it. Check the signs."

    private struct Catalogue: Decodable { let schema_version: Int; let structures: [ShoreStructure] }

    /// The bundled structures, read once. Empty if the file is missing or unreadable.
    static let bundled: [ShoreStructure] = Bundle.main.url(forResource: "shore_structures", withExtension: "json")
        .flatMap { try? Data(contentsOf: $0) }.flatMap { try? decode($0) } ?? []

    static func decode(_ data: Data) throws -> [ShoreStructure] {
        let catalogue = try JSONDecoder().decode(Catalogue.self, from: data)
        guard catalogue.schema_version == 1 else { throw CocoaError(.coderInvalidValue) }
        return catalogue.structures
    }

    /// Up to `limit` structures within `withinKm` of `point`, nearest first.
    static func nearest(_ point: GeoPoint, in structures: [ShoreStructure], limit: Int = 3, withinKm: Double = 10) -> [(structure: ShoreStructure, km: Double)] {
        Array(structures.lazy
            .filter { abs($0.lat - point.latitude) < withinKm / 100 && abs($0.lon - point.longitude) < withinKm / 70 }
            .map { (structure: $0, km: placeDistanceKm(point, $0.point)) }
            .filter { $0.km <= withinKm }
            .sorted { $0.km < $1.km }
            .prefix(limit))
    }
}
