import Foundation

/// Stands in for the app's analytics setting, which the rules sync reads for a request header.
enum AnalyticsPreferences { static func deviceID() -> String? { nil } }

/// Species pictures match MPI rule names as Android's `RuleRowsTest` does. Run with `tools/test_rules_offline_swift.sh`.
@main struct RulesOfflineRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        struct Bundle: Decodable { let species: SpeciesCatalog }
        let catalog = try JSONDecoder().decode(Bundle.self, from: Data(contentsOf: root.appendingPathComponent("data/offline_rules.json"))).species
        try expect(catalog.forRule("Snapper: Paringa, Popotai Taumaka/Open Bay Islands Mātaitai Reserves")?.id == "snapper", "Snapper with a qualifier")
        try expect(catalog.forRule("Paua – Ordinary – Yellow foot")?.id == "paua", "Pāua without a macron")
        try expect(catalog.forRule("Packhorse rock lobster")?.id == "packhorse-lobster", "Packhorse before rock lobster")
        try expect(catalog.forRule("Red or spiny rock lobster")?.id == "rock-lobster", "Red rock lobster")
        try expect(catalog.forRule("Green-lipped mussels")?.id == "green-lipped-mussel", "A plural name")
        try expect(catalog.forRule("Mussels")?.id == "mussel", "Mussels")
        try expect(catalog.forRule("Blue cod –Te Whaka ā Te Wera/Paterson Inlet Mātaitai")?.id == "blue-cod", "Blue cod with a mātaitai")
        try expect(catalog.forRule("All other finfish") == nil, "No picture for a catch-all row")
        try expect(catalog.forRule("Ringed something") == nil, "Whole words only")
        try expect(catalog.pictures.count == 94, "Each picture once")
        let common = catalog.commonCatches(["Snapper", "Kahawai"])
        try expect(common.map(\.id) == ["john-dory", "leatherjacket", "porae", "sweep", "blue-maomao", "red-snapper", "blue-mackerel"], "Common catches in order")
        try expect(catalog.commonCatches(["John dory"]).count == 6, "A common catch with its own rule shows with that rule")
        try expect(catalog.forRule("Groper (Hapuku) / Bass")?.pictures.map(\.caption) == ["Hāpuku", "Bass"], "Hāpuku and bass")
        try expect(catalog.forRule("Flatfish (except Sand flounder)")?.pictures.count == 7, "Seven flatfish")
        try expect(RulesSync.sha256(Data("abc".utf8)) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "SHA-256")
        print("Rules offline regression passed (\(checks) checks)")
    }
}
