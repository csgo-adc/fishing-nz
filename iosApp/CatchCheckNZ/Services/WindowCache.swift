import Foundation

/// The fishing windows found for recent plans, kept on the phone so Home, "See all" and the next launch show them at
/// once instead of searching again. Android has the same rules in `WindowCache.kt`.
///
/// - Up to `freshFor` old: shown as they are, with no new search.
/// - Up to `maxAge` old: shown at once while a new search runs quietly and replaces them.
/// - Older, or for another plan (place moved more than `moveKm`, days, hours, land or boat, distance, preferences), or
///   from another app build: searched again. Up to `offlineFor` old they still stand in when that search fails.
/// - Windows that have already ended are left out; a list with none left is searched again.
/// - The last `maxEntries` plans are kept, so switching between them is instant.
enum WindowCache {
    static let freshFor: TimeInterval = 60 * 60
    static let maxAge: TimeInterval = 6 * 60 * 60
    static let offlineFor: TimeInterval = 24 * 60 * 60
    static let moveKm = 2.0
    static let maxEntries = 6
    private static let file = "window_cache_v1.json"

    struct Entry: Codable, Sendable {
        let key: String
        let origin: GeoPoint
        let savedAt: Date
        let build: Int
        var windows: [ScoredFishingWindow]
        let failedSpots: [String]
        let spotsWithoutWindows: [String]
    }
    enum Freshness: Equatable { case fresh, stale }
    struct Hit { let entry: Entry; let freshness: Freshness }

    nonisolated(unsafe) private static var memory: [Entry]?

    /// The saved windows for this plan and place, without the ones that have ended, or nil when they need a new search.
    static func lookup(_ entries: [Entry], key: String, origin: GeoPoint, now: Date, build: Int, maxAge: TimeInterval = maxAge) -> Hit? {
        guard var entry = entries.first(where: { $0.key == key && $0.build == build && distanceKm($0.origin, origin) <= moveKm }) else { return nil }
        let age = now.timeIntervalSince(entry.savedAt)
        guard age >= 0, age <= maxAge else { return nil }
        let current = entry.windows.filter { $0.end > now }
        if current.isEmpty && !entry.windows.isEmpty { return nil }
        entry.windows = current
        return Hit(entry: entry, freshness: age <= freshFor ? .fresh : .stale)
    }

    /// `entries` with `entry` first, replacing the same plan at about the same place, at most `maxEntries`.
    static func storing(_ entries: [Entry], _ entry: Entry) -> [Entry] {
        let others = entries.filter { !($0.key == entry.key && distanceKm($0.origin, entry.origin) <= moveKm) }
            .filter { entry.savedAt.timeIntervalSince($0.savedAt) <= offlineFor }
        return Array(([entry] + others).prefix(maxEntries))
    }

    static func distanceKm(_ a: GeoPoint, _ b: GeoPoint) -> Double {
        let radians = Double.pi / 180
        let h = pow(sin((b.latitude - a.latitude) * radians / 2), 2) +
            cos(a.latitude * radians) * cos(b.latitude * radians) * pow(sin((b.longitude - a.longitude) * radians / 2), 2)
        return 2 * 6371 * asin(min(1, max(0, h)).squareRoot())
    }

    // MARK: On the phone

    static func load() -> [Entry] {
        if let memory { return memory }
        let entries = OfflineCache.read(file).flatMap { try? decode($0.data) } ?? []
        memory = entries
        return entries
    }

    static func save(_ entry: Entry) {
        let entries = storing(load(), entry)
        memory = entries
        if let data = try? encode(entries) { OfflineCache.write(file, data) }
    }

    // MARK: JSON (non-finite numbers such as a missing distance are written as strings)

    private struct Stored: Codable { let v: Int; let entries: [Entry] }

    static func encode(_ entries: [Entry]) throws -> Data {
        let encoder = JSONEncoder()
        encoder.nonConformingFloatEncodingStrategy = .convertToString(positiveInfinity: "inf", negativeInfinity: "-inf", nan: "nan")
        return try encoder.encode(Stored(v: 1, entries: entries))
    }

    static func decode(_ data: Data) throws -> [Entry] {
        let decoder = JSONDecoder()
        decoder.nonConformingFloatDecodingStrategy = .convertFromString(positiveInfinity: "inf", negativeInfinity: "-inf", nan: "nan")
        let stored = try decoder.decode(Stored.self, from: data)
        return stored.v == 1 ? stored.entries : []
    }
}
