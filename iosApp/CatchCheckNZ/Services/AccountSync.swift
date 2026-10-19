import Foundation

/// Whether this device syncs trips and the catch log with the account, and when they last changed or synced.
enum SyncStore {
    private static var defaults: UserDefaults { .standard }
    static var enabled: Bool {
        get { defaults.bool(forKey: "sync_enabled") }
        set { defaults.set(newValue, forKey: "sync_enabled") }
    }
    /// When the saved windows, active trip or back-by time last changed on this device, in seconds since 1970.
    static var tripsUpdatedAt: Int {
        get { defaults.integer(forKey: "sync_trips_updated_at") }
        set { defaults.set(newValue, forKey: "sync_trips_updated_at") }
    }
    static var lastSyncedAt: Date? {
        get { defaults.object(forKey: "sync_last_synced_at") as? Date }
        set { defaults.set(newValue, forKey: "sync_last_synced_at") }
    }
}

/// The items a device sends to `/v1/me/sync` and how the reply is merged back. Trips travel as one document per account;
/// catches one by one, without photos. The newest `updated_at` wins on the server and here. Android has the same rules in
/// `AccountSync.kt`.
enum AccountSync {
    struct Trips { var saved: [Recommendation]; var active: Recommendation?; var backBy: Date? }
    struct Merged { var trips: Trips?; var tripsUpdatedAt: Int; var catches: [CatchEntry] }

    private struct WithoutPhoto: Encodable {
        let entry: CatchEntry
        func encode(to encoder: Encoder) throws { try entry.encode(to: encoder, includePhoto: false) }
    }

    static func tripsDocument(_ trips: Trips) -> [String: Any] {
        var document: [String: Any] = ["saved": trips.saved.compactMap { SharedWindowLink.encode($0) }]
        if let active = trips.active.flatMap({ SharedWindowLink.encode($0) }) { document["active"] = active }
        if let backBy = trips.backBy { document["back_by"] = Int(backBy.timeIntervalSince1970.rounded()) }
        return document
    }

    static func trips(_ document: [String: Any]) -> Trips {
        Trips(saved: (document["saved"] as? [String] ?? []).compactMap(TripStore.restore),
              active: (document["active"] as? String).flatMap(TripStore.restore),
              backBy: (document["back_by"] as? Int).flatMap { $0 > 0 ? Date(timeIntervalSince1970: TimeInterval($0)) : nil })
    }

    static func catchPayload(_ entry: CatchEntry) -> [String: Any] {
        (try? JSONEncoder().encode(WithoutPhoto(entry: entry))).flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
    }

    static func items(trips: Trips, tripsUpdatedAt: Int, catches: [CatchEntry]) -> [[String: Any]] {
        var items: [[String: Any]] = []
        if tripsUpdatedAt > 0 {
            items.append(["kind": "trips", "id": "trips", "payload": tripsDocument(trips), "updated_at": tripsUpdatedAt, "deleted": false])
        }
        for entry in catches {
            items.append(["kind": "catch", "id": entry.id, "payload": catchPayload(entry),
                          "updated_at": Int(entry.updatedAt.timeIntervalSince1970.rounded()), "deleted": entry.deleted])
        }
        return items
    }

    /// Takes each server item that is newer than this device's copy. Photos stay with the local entry.
    static func merge(_ response: Data, tripsUpdatedAt: Int, catches: [CatchEntry]) -> Merged {
        let root = (try? JSONSerialization.jsonObject(with: response)) as? [String: Any]
        var merged = Merged(trips: nil, tripsUpdatedAt: tripsUpdatedAt, catches: [])
        var byID = Dictionary(catches.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        for item in root?["items"] as? [[String: Any]] ?? [] {
            guard let payload = item["payload"] as? [String: Any], let updatedAt = item["updated_at"] as? Int else { continue }
            switch item["kind"] as? String {
            case "trips":
                if updatedAt > merged.tripsUpdatedAt { merged.trips = trips(payload); merged.tripsUpdatedAt = updatedAt }
            case "catch":
                guard let id = item["id"] as? String else { continue }
                if let local = byID[id], Int(local.updatedAt.timeIntervalSince1970.rounded()) >= updatedAt { continue }
                guard let data = try? JSONSerialization.data(withJSONObject: payload),
                      var remote = try? JSONDecoder().decode(CatchEntry.self, from: data), remote.id == id else { continue }
                let deleted = item["deleted"] as? Bool ?? false
                if deleted { CatchLogStore.deletePhoto(byID[id]?.photoFile) }
                remote.updatedAt = Date(timeIntervalSince1970: TimeInterval(updatedAt))
                remote.deleted = deleted
                remote.photoFile = deleted ? nil : byID[id]?.photoFile
                byID[id] = remote
            default: continue
            }
        }
        merged.catches = Array(byID.values)
        return merged
    }
}
