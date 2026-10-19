import Foundation

/// Saved windows and the active trip, kept across launches. Each window is stored as its share-link token, the same
/// small snapshot both apps and the web page read, so account sync can carry it between iPhone and Android too.
/// Android has the same rules in `TripStore.kt`.
enum TripStore {
    static let savedNote = "Saved: these conditions were captured when you saved this window and may have changed. Search from Home for a live forecast."
    private static let key = "trips_v1"
    private struct Stored: Codable { var saved: [String]; var active: String? }

    static func load(defaults: UserDefaults = .standard) -> (saved: [Recommendation], active: Recommendation?) {
        guard let data = defaults.data(forKey: key) else { return ([], nil) }
        return decode(data)
    }

    static func save(saved: [Recommendation], active: Recommendation?, defaults: UserDefaults = .standard) {
        defaults.set(encode(saved: saved, active: active), forKey: key)
    }

    static func encode(saved: [Recommendation], active: Recommendation?) -> Data {
        (try? JSONEncoder().encode(Stored(saved: saved.compactMap { SharedWindowLink.encode($0) }, active: active.flatMap { SharedWindowLink.encode($0) }))) ?? Data()
    }

    static func decode(_ data: Data) -> (saved: [Recommendation], active: Recommendation?) {
        guard let stored = try? JSONDecoder().decode(Stored.self, from: data) else { return ([], nil) }
        return (stored.saved.compactMap(restore), stored.active.flatMap(restore))
    }

    /// A window from its token, labelled as saved rather than shared.
    static func restore(_ token: String) -> Recommendation? {
        guard let window = SharedWindowLink.decode(token) else { return nil }
        var assessment = window.assessment
        assessment?.details = [savedNote]
        return Recommendation(name: window.name, area: window.area, rating: window.rating, time: window.time,
                              distance: "Saved · forecast may have changed", reasons: window.reasons, boat: window.boat,
                              warnings: window.warnings, startsAt: window.startsAt, endsAt: window.endsAt, summary: window.summary,
                              conditions: window.conditions, sourceNote: window.sourceNote, alternative: window.alternative,
                              dataComplete: window.dataComplete, tidePreferenceFit: window.tidePreferenceFit,
                              assessment: assessment,
                              coordinate: window.coordinate)
    }
}
