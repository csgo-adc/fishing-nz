import Foundation

/// The iPhone wharf and breakwater rules match Android's `ShoreStructuresTest`. Run with `tools/test_shore_structures_swift.sh`.
@main struct ShoreStructuresRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        let source = try Data(contentsOf: root.appendingPathComponent("data/shore_structures.json"))
        let bundled = try Data(contentsOf: root.appendingPathComponent("iosApp/CatchCheckNZ/Resources/shore_structures.json"))
        try expect(source == bundled, "Bundled copy is out of date")
        let structures = try ShoreStructures.decode(source)
        try expect(structures.count > 400, "Structure count")
        try expect(Set(structures.map(\.kind)) == ["wharf", "breakwater", "groyne"], "Kinds")
        try expect(structures.first { $0.name == nil && $0.kind == "wharf" }?.label == "Wharf or jetty", "Unnamed wharf label")
        try expect(structures.first { $0.name == nil && $0.kind == "breakwater" }?.label == "Breakwater", "Unnamed breakwater label")
        try expect(structures.first { $0.kind == "breakwater" }?.mapSubtitle == "Breakwater · access and fishing rules not checked", "Map subtitle")
        let landSpots = fishingSpots.filter { !$0.boat }
        let covered = landSpots.filter { !ShoreStructures.nearest($0.coordinate, in: structures, limit: 1).isEmpty }.count
        try expect(covered * 10 >= landSpots.count * 6, "\(covered) of \(landSpots.count) land areas have a structure within 10 km")
        let oamaru = fishingSpots.first { $0.name == "Oamaru Harbour" && !$0.boat }!
        let nearest = ShoreStructures.nearest(oamaru.coordinate, in: structures)
        try expect(nearest.count == 3 && nearest.map(\.km) == nearest.map(\.km).sorted() && nearest.allSatisfy { $0.km <= 10 }, "Nearest structures")
        try expect(nearest.contains { $0.structure.name == "Oamaru Breakwater" }, "Oamaru Breakwater nearby")
        print("Shore structure regression passed (\(checks) checks)")
    }
}
