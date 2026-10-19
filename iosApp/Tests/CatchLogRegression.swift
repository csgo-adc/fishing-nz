import Foundation

/// The iPhone catch log rules match Android's `CatchLogTest`. Run with `tools/test_catch_log_swift.sh`.
@main struct CatchLogRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        let east = FishRuleMatch(species: "Snapper — Auckland East", dailyLimit: "7", minimumSize: "30", minimumSizeLabel: "Minimum size (cm)", details: [])
        let west = FishRuleMatch(species: "Snapper — Auckland West", dailyLimit: "7", minimumSize: "27", minimumSizeLabel: "Minimum size (cm)", details: [])
        let paua = FishRuleMatch(species: "Pāua", dailyLimit: "10", minimumSize: "125", minimumSizeLabel: "Minimum size (mm)", details: [])
        let cray = FishRuleMatch(species: "Rock lobster", dailyLimit: "6", minimumSize: "54 tail width (mm)", minimumSizeLabel: "Minimum size", details: [])
        let verdicts = CatchLog.sizeVerdicts(28, rules: [east, west])
        try expect(verdicts.map(\.meets) == [false, true], "Snapper verdicts")
        try expect(verdicts[0].text == "28 cm is under the 30 cm minimum for Snapper — Auckland East. Release it.", "Under wording")
        try expect(verdicts[1].text == "28 cm meets the 27 cm minimum for Snapper — Auckland West.", "Meets wording")
        try expect(CatchLog.sizeVerdicts(12.6, rules: [paua]).first?.meets == true && CatchLog.sizeVerdicts(12.4, rules: [paua]).first?.meets == false, "Millimetres")
        try expect(CatchLog.sizeVerdicts(30, rules: [cray]).first?.meets == nil, "Tail width goes to MPI")

        let noon = ISO8601DateFormatter().date(from: "2026-10-17T23:00:00Z")! // Sunday 18 October, 12 PM in New Zealand
        func entry(_ id: String, _ species: String, at: Date = noon, kept: Bool = true, count: Int = 1, tide: String? = nil) -> CatchEntry {
            var e = CatchEntry(id: id, caughtAt: at, species: species); e.kept = kept; e.count = count; e.conditions.tidePhase = tide; return e
        }
        var deleted = entry("f", "Snapper"); deleted.deleted = true
        let day = [entry("a", "Snapper", count: 2), entry("b", "snapper "), entry("c", "Snapper", kept: false),
                   entry("d", "Snapper", at: noon.addingTimeInterval(-13 * 3600)), entry("e", "Kahawai"), deleted]
        try expect(CatchLog.keptOnDay(day, species: "Snapper", at: noon) == 3, "Kept that day")
        try expect(CatchLog.keptOnDay(day, species: "Snapper", at: noon, excludingID: "b") == 2, "Kept excluding the edited entry")

        try expect(CatchLog.insights([entry("a", "Snapper"), entry("b", "Snapper")]).isEmpty, "No patterns under three")
        let lines = CatchLog.insights([entry("a", "Snapper", tide: "Incoming"), entry("b", "Snapper", tide: "Incoming"), entry("c", "Kahawai", kept: false, tide: "Outgoing")])
        try expect(lines.first == "3 fish logged · 2 kept, 1 released", "Totals line")
        try expect(lines.contains("Most caught: Snapper (2)") && lines.contains("Tide: mostly incoming (2 of 3 fish)")
                   && lines.contains("Time of day: mostly afternoon (3 of 3 fish)"), "Pattern lines: \(lines)")

        var exported = entry("a", "Snapper"); exported.lengthCm = 32.5; exported.notes = "Pilchard, \"ledger\" rig"; exported.placeName = "Raglan"
        let row = CatchLog.csv([exported]).components(separatedBy: "\n")[1]
        try expect(row.hasPrefix("2026-10-18 12:00,Snapper,32.5,Kept,1,Raglan,") && row.hasSuffix(",\"Pilchard, \"\"ledger\"\" rig\""), "CSV row: \(row)")

        // The shared fixture Android's CatchLogTest reads too.
        let fixture = try Data(contentsOf: root.appendingPathComponent("app/src/test/resources/catches/catch-log.json"))
        let entries = try CatchLogStore.decode(fixture)
        try expect(entries.map(\.id) == ["7d0f3c1e-2a4b-4c55-9b1e-0f6b8e2d9a10", "b2", "b3"], "Fixture ids: \(entries.map(\.id))")
        try expect(entries[0].lengthCm == 32.5 && entries[0].count == 2 && entries[0].conditions.tidePhase == "Incoming"
                   && entries[0].conditions.tideRange == "Neap tides", "Fixture first entry")
        try expect(entries[1].deleted && entries[2].lengthCm == nil && entries[2].latitude == nil && entries[2].count == 99, "Fixture limits")
        let again = try CatchLogStore.decode(CatchLogStore.encode(entries))
        try expect(again == entries, "Round trip")

        let low = LINZTidePrediction(time: ISO8601DateFormatter().date(from: "2026-10-18T00:00:00Z")!, height: 0.4)
        let high = LINZTidePrediction(time: ISO8601DateFormatter().date(from: "2026-10-18T06:10:00Z")!, height: 3.1)
        let nextLow = LINZTidePrediction(time: ISO8601DateFormatter().date(from: "2026-10-18T12:20:00Z")!, height: 0.5)
        func at(_ time: String) -> (phase: String, text: String)? {
            CatchConditionsLookup.tideAt([low, high, nextLow], date: ISO8601DateFormatter().date(from: time)!, stationName: "Raglan")
        }
        try expect(at("2026-10-18T02:00:00Z")?.phase == "Incoming" && at("2026-10-18T02:00:00Z")?.text == "Incoming · 2 h after low (LINZ Raglan)", "Incoming")
        try expect(at("2026-10-18T04:30:00Z")?.phase == "Around high" && at("2026-10-18T07:00:00Z")?.phase == "Around high", "Around high")
        try expect(at("2026-10-18T09:00:00Z")?.phase == "Outgoing" && at("2026-10-18T11:30:00Z")?.phase == "Around low", "Falling")
        try expect(at("2026-10-18T01:00:00Z")?.phase == "Around low" && at("2026-10-18T13:00:00Z") == nil, "Edges")
        print("Catch log regression passed (\(checks) checks)")
    }
}
