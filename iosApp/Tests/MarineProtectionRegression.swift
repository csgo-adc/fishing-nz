import Foundation

/// The iPhone protected-area checks match Android's `MarineProtectionTest`. Run with `tools/test_marine_protection_swift.sh`.
@main struct MarineProtectionRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        let source = try Data(contentsOf: root.appendingPathComponent("data/marine_protection.json"))
        let catalogue = try MarineProtection.decode(source)
        let bundled = try Data(contentsOf: root.appendingPathComponent("iosApp/CatchCheckNZ/Resources/marine_protection.json"))
        try expect(bundled == source, "Bundled copy is out of date")
        func area(_ name: String) -> ProtectedArea { catalogue.areas.first { $0.name == name }! }
        try expect(catalogue.areas.count >= 60, "Too few areas")
        for kind in [ProtectionKind.reserve, .highProtection, .seafloorProtection] {
            try expect(catalogue.areas.contains { $0.kind == kind }, "Missing \(kind)")
        }
        // Goat Island Bay is inside Cape Rodney-Okakari Point Marine Reserve; central Auckland is nowhere near one.
        let goat = GeoPoint(latitude: -36.2667, longitude: 174.7958)
        try expect(area("Cape Rodney-Okakari Point Marine Reserve").contains(goat), "Goat Island Bay inside")
        try expect(MarineProtection.notices(at: goat, in: catalogue.areas).first?.inside == true, "Inside notice first")
        try expect(MarineProtection.notices(at: GeoPoint(latitude: -36.85, longitude: 174.76), in: catalogue.areas).isEmpty, "No notice in town")
        // Tawhiti Rahi, the northern Poor Knights island, is a hole in the reserve around it.
        let poorKnights = area("Poor Knights Islands Marine Reserve")
        try expect(!poorKnights.contains(GeoPoint(latitude: -35.4576, longitude: 174.7373)), "Island is outside")
        try expect(poorKnights.contains(GeoPoint(latitude: -35.4576, longitude: 174.7480)), "Water is inside")
        // Listed spots are outside every area; St Clair Beach is beside Ōrau Marine Reserve.
        for spot in fishingSpots {
            try expect(!MarineProtection.notices(at: spot.coordinate, in: catalogue.areas).contains { $0.inside }, "\(spot.name) is inside an area")
        }
        let stClair = fishingSpots.first { $0.name == "St Clair Beach" }!
        let notice = MarineProtection.notices(at: stClair.coordinate, in: catalogue.areas).first
        try expect(notice?.area.name == "Ōrau Marine Reserve", "St Clair notice")
        try expect(notice?.text.hasSuffix("from Ōrau Marine Reserve. No fishing or taking of any marine life.") == true, "St Clair wording")
        try expect(MarineProtection.distanceLabel(0.02) == "Under 50 m" && MarineProtection.distanceLabel(0.203) == "200 m"
                   && MarineProtection.distanceLabel(1.48) == "1.5 km", "Distance labels")
        print("Marine protection regression passed (\(checks) checks)")
    }
}
