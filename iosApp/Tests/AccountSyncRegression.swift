import Foundation

/// Account sync matches Android's `AccountSyncTest`. Run with `tools/test_account_sync_swift.sh`.
@main struct AccountSyncRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let t0 = 1_792_300_000
        let start = Date(timeIntervalSince1970: 1_792_360_800)
        let window = Recommendation(name: "Raglan", area: "Waikato", rating: 80, time: "", distance: "", reasons: [], boat: false,
                                    startsAt: start, endsAt: start.addingTimeInterval(7_200), coordinate: GeoPoint(latitude: -37.8, longitude: 174.88))
        func entry(_ id: String, _ species: String, updated: Int, photo: String? = nil) -> CatchEntry {
            var e = CatchEntry(id: id, caughtAt: Date(timeIntervalSince1970: TimeInterval(t0)), species: species)
            e.photoFile = photo; e.updatedAt = Date(timeIntervalSince1970: TimeInterval(updated)); return e
        }

        let items = AccountSync.items(trips: .init(saved: [window], active: window, backBy: Date(timeIntervalSince1970: TimeInterval(t0))),
                                      tripsUpdatedAt: t0, catches: [entry("a", "Snapper", updated: t0, photo: "a.jpg")])
        try expect(items.count == 2 && items[0]["kind"] as? String == "trips", "Trips first")
        try expect((items[0]["payload"] as? [String: Any])?["back_by"] as? Int == t0, "Back-by")
        try expect((items[1]["payload"] as? [String: Any])?["photo"] == nil, "No photo")
        try expect((items[1]["payload"] as? [String: Any])?["caught_at"] as? Int == t0, "Whole-second times")
        try expect(AccountSync.items(trips: .init(saved: [], active: nil, backBy: nil), tripsUpdatedAt: 0, catches: [entry("a", "Snapper", updated: t0)]).count == 1,
                   "Unchanged trips are not sent")

        func item(_ e: CatchEntry, _ updated: Int, deleted: Bool = false) -> [String: Any] {
            ["kind": "catch", "id": e.id, "payload": AccountSync.catchPayload(e), "updated_at": updated, "deleted": deleted]
        }
        let response = try JSONSerialization.data(withJSONObject: ["items": [
            item(entry("a", "Old snapper", updated: 0), t0),
            item(entry("b", "Trevally", updated: 0), t0 + 20),
            item(entry("c", "Gurnard", updated: 0), t0 + 5),
            ["kind": "trips", "id": "trips", "updated_at": t0 + 30, "deleted": false, "payload": AccountSync.tripsDocument(.init(saved: [window], active: nil, backBy: nil))],
        ]])
        let local = [entry("a", "Snapper", updated: t0 + 10, photo: "a.jpg"), entry("b", "Kahawai", updated: t0, photo: "b.jpg")]
        let merged = AccountSync.merge(response, tripsUpdatedAt: t0, catches: local)
        let byID = Dictionary(uniqueKeysWithValues: merged.catches.map { ($0.id, $0) })
        try expect(byID["a"]?.species == "Snapper", "Older remote ignored")
        try expect(byID["b"]?.species == "Trevally" && byID["b"]?.photoFile == "b.jpg" && byID["b"]?.updatedAt.timeIntervalSince1970 == TimeInterval(t0 + 20), "Newer remote taken, photo kept")
        try expect(byID["c"]?.species == "Gurnard", "Remote-only entry added")
        try expect(merged.trips?.saved.map(\.name) == ["Raglan"] && merged.trips?.active == nil && merged.tripsUpdatedAt == t0 + 30, "Newer trips taken")
        try expect(AccountSync.merge(response, tripsUpdatedAt: t0 + 60, catches: local).trips == nil, "Older trips ignored")

        let deletion = try JSONSerialization.data(withJSONObject: ["items": [item(entry("a", "Snapper", updated: 0), t0 + 99, deleted: true)]])
        let removed = AccountSync.merge(deletion, tripsUpdatedAt: 0, catches: [entry("a", "Snapper", updated: t0, photo: "a.jpg")]).catches[0]
        try expect(removed.deleted && removed.photoFile == nil, "Remote deletion")
        print("Account sync regression passed (\(checks) checks)")
    }
}
