import Foundation

/// Conditions recorded with a catch, looked up after it is saved. Every field is optional.
struct CatchConditions: Codable, Equatable, Sendable {
    /// "Incoming", "Outgoing", "Around high" or "Around low".
    var tidePhase: String? = nil
    /// "Incoming · 2 h after low (LINZ Raglan)".
    var tide: String? = nil
    var wind: String? = nil
    var waves: String? = nil
    var waterTemperature: String? = nil
    var moon: String? = nil
    var tideRange: String? = nil
    var isEmpty: Bool { [tidePhase, tide, wind, waves, waterTemperature, moon].allSatisfy { $0 == nil } }

    enum CodingKeys: String, CodingKey {
        case tidePhase = "tide_phase", tide, wind, waves, waterTemperature = "water_temperature", moon, tideRange = "tide_range"
    }
}

/// One fish (or several of the same species) in the catch log, in the same JSON shape as Android's `CatchLogStore`.
/// `updatedAt` and `deleted` let account sync merge devices; a deleted entry stays as a tombstone until synced.
/// `photoFile` is a file name in this device's private storage and never leaves it.
struct CatchEntry: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var caughtAt: Date
    var species: String
    var lengthCm: Double? = nil
    var kept = false
    var count = 1
    var placeName = ""
    var latitude: Double? = nil
    var longitude: Double? = nil
    var rulesAreaID: String? = nil
    var notes = ""
    var photoFile: String? = nil
    var conditions = CatchConditions()
    var updatedAt: Date
    var deleted = false

    var point: GeoPoint? { latitude.flatMap { lat in longitude.map { GeoPoint(latitude: lat, longitude: $0) } } }

    enum CodingKeys: String, CodingKey {
        case id, caughtAt = "caught_at", species, lengthCm = "length_cm", kept, count, placeName = "place", latitude, longitude
        case rulesAreaID = "rules_area", notes, photoFile = "photo", conditions, updatedAt = "updated_at", deleted
    }

    init(id: String = UUID().uuidString.lowercased(), caughtAt: Date, species: String, placeName: String = "",
         latitude: Double? = nil, longitude: Double? = nil, rulesAreaID: String? = nil) {
        self.id = id; self.caughtAt = caughtAt; self.species = species; self.placeName = placeName
        self.latitude = latitude; self.longitude = longitude; self.rulesAreaID = rulesAreaID; self.updatedAt = caughtAt
    }

    /// Lenient reading: times are whole seconds since 1970, and out-of-range values are dropped rather than trusted.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func text(_ key: CodingKeys, _ max: Int) -> String? { (try? c.decodeIfPresent(String.self, forKey: key))?.trimmingCharacters(in: .whitespacesAndNewlines).prefix(max).description }
        id = text(.id, 64) ?? ""
        species = text(.species, 80) ?? ""
        guard !id.isEmpty, !species.isEmpty else { throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "Missing id or species") }
        let caught = try c.decode(Double.self, forKey: .caughtAt)
        caughtAt = Date(timeIntervalSince1970: caught)
        lengthCm = (try? c.decodeIfPresent(Double.self, forKey: .lengthCm)).flatMap { $0 > 0 && $0 <= CatchLog.maxLengthCm ? $0 : nil }
        kept = (try? c.decodeIfPresent(Bool.self, forKey: .kept)) ?? false
        count = min(max((try? c.decodeIfPresent(Int.self, forKey: .count)) ?? 1, 1), 99)
        placeName = text(.placeName, 120) ?? ""
        latitude = (try? c.decodeIfPresent(Double.self, forKey: .latitude)).flatMap { abs($0) <= 90 ? $0 : nil }
        longitude = (try? c.decodeIfPresent(Double.self, forKey: .longitude)).flatMap { abs($0) <= 180 ? $0 : nil }
        rulesAreaID = text(.rulesAreaID, 40)
        notes = text(.notes, 1000) ?? ""
        photoFile = text(.photoFile, 80)
        conditions = (try? c.decodeIfPresent(CatchConditions.self, forKey: .conditions)) ?? CatchConditions()
        updatedAt = Date(timeIntervalSince1970: (try? c.decodeIfPresent(Double.self, forKey: .updatedAt)) ?? caught)
        deleted = (try? c.decodeIfPresent(Bool.self, forKey: .deleted)) ?? false
    }

    func encode(to encoder: Encoder) throws { try encode(to: encoder, includePhoto: true) }

    /// Account sync sends the same JSON without the photo.
    func encode(to encoder: Encoder, includePhoto: Bool) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id); try c.encode(Int64(caughtAt.timeIntervalSince1970.rounded()), forKey: .caughtAt)
        try c.encode(species, forKey: .species); try c.encodeIfPresent(lengthCm, forKey: .lengthCm)
        try c.encode(kept, forKey: .kept); try c.encode(count, forKey: .count); try c.encode(placeName, forKey: .placeName)
        try c.encodeIfPresent(latitude, forKey: .latitude); try c.encodeIfPresent(longitude, forKey: .longitude)
        try c.encodeIfPresent(rulesAreaID, forKey: .rulesAreaID); try c.encode(notes, forKey: .notes)
        if includePhoto { try c.encodeIfPresent(photoFile, forKey: .photoFile) }
        try c.encode(conditions, forKey: .conditions)
        try c.encode(Int64(updatedAt.timeIntervalSince1970.rounded()), forKey: .updatedAt); try c.encode(deleted, forKey: .deleted)
    }
}

/// A size check against one MPI rule row. `meets` is nil when the rule cannot be compared safely.
struct SizeVerdict: Equatable, Sendable { let rule: String; let meets: Bool?; let text: String }

/// The catch log's rules, matching Android's `CatchLog.kt`.
enum CatchLog {
    static let maxLengthCm = 400.0
    private static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar
    }

    static func visible(_ entries: [CatchEntry]) -> [CatchEntry] { entries.filter { !$0.deleted }.sorted { $0.caughtAt > $1.caughtAt } }

    /// Fish caught (each entry's count) and different species in the log, for the Account header.
    static func totals(_ entries: [CatchEntry]) -> (fish: Int, species: Int) {
        let live = entries.filter { !$0.deleted }
        let species = Set(live.map { $0.species.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }.filter { !$0.isEmpty })
        return (live.reduce(0) { $0 + $1.count }, species.count)
    }

    /// Compares `lengthCm` with each matching MPI rule row. Only a single plain number is compared; a range, several
    /// numbers or a tail width points to MPI instead. Rules in millimetres are converted.
    static func sizeVerdicts(_ lengthCm: Double, rules: [FishRuleMatch]) -> [SizeVerdict] {
        rules.compactMap { rule in
            guard let raw = rule.minimumSize?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else { return nil }
            let label = rule.minimumSizeLabel ?? ""
            let numbers = raw.matches(of: /\d+(?:\.\d+)?/).compactMap { Double($0.output) }
            let name = rule.species.replacing(/\s*[*†‡§]+\s*/, with: " ").trimmingCharacters(in: .whitespaces)
            let shown = formatCm(lengthCm)
            if numbers.count != 1 || label.localizedCaseInsensitiveContains("tail") || raw.localizedCaseInsensitiveContains("tail") {
                return SizeVerdict(rule: name, meets: nil, text: "Check MPI for the minimum size of \(name).")
            }
            let millimetres = label.localizedCaseInsensitiveContains("mm") || raw.localizedCaseInsensitiveContains("mm")
            let minimumCm = millimetres ? numbers[0] / 10 : numbers[0]
            let minimum = formatCm(minimumCm)
            return lengthCm >= minimumCm
                ? SizeVerdict(rule: name, meets: true, text: "\(shown) meets the \(minimum) minimum for \(name).")
                : SizeVerdict(rule: name, meets: false, text: "\(shown) is under the \(minimum) minimum for \(name). Release it.")
        }
    }

    /// Fish of `species` kept on the New Zealand day of `date`, from entries other than `excludingID`.
    static func keptOnDay(_ entries: [CatchEntry], species: String, at date: Date, excludingID: String? = nil) -> Int {
        let key = species.trimmingCharacters(in: .whitespaces).lowercased()
        let calendar = calendar
        return entries.filter { !$0.deleted && $0.kept && $0.id != excludingID && $0.species.trimmingCharacters(in: .whitespaces).lowercased() == key
            && calendar.isDate($0.caughtAt, inSameDayAs: date) }.reduce(0) { $0 + $1.count }
    }

    /// Plain-word patterns once there are at least three catches. Describes the log only; it predicts nothing.
    static func insights(_ entries: [CatchEntry]) -> [String] {
        let list = visible(entries)
        guard list.count >= 3 else { return [] }
        let fish = list.reduce(0) { $0 + $1.count }, kept = list.filter(\.kept).reduce(0) { $0 + $1.count }
        var lines = ["\(fish) fish logged · \(kept) kept, \(fish - kept) released"]
        var bySpecies: [String: Int] = [:]
        for entry in list { bySpecies[entry.species.trimmingCharacters(in: .whitespaces), default: 0] += entry.count }
        if let top = bySpecies.max(by: { $0.value < $1.value || ($0.value == $1.value && $0.key > $1.key) }) {
            lines.append("Most caught: \(top.key) (\(top.value))")
        }
        func share(_ label: String, _ key: (CatchEntry) -> String?) {
            let known = list.compactMap { entry in key(entry).map { ($0, entry.count) } }
            let total = known.reduce(0) { $0 + $1.1 }
            guard total >= 3, let top = Dictionary(known, uniquingKeysWith: +).max(by: { $0.value < $1.value }), top.value * 2 > total else { return }
            lines.append("\(label): mostly \(top.key.lowercased()) (\(top.value) of \(total) fish)")
        }
        share("Tide") { $0.conditions.tidePhase }
        share("Time of day") { timeOfDay($0.caughtAt) }
        share("Tide range") { $0.conditions.tideRange }
        return lines
    }

    static func timeOfDay(_ date: Date) -> String {
        switch calendar.component(.hour, from: date) {
        case 4...7: "Early morning"
        case 8...11: "Morning"
        case 12...16: "Afternoon"
        case 17...20: "Evening"
        default: "Night"
        }
    }

    /// Spreadsheet-ready export of the visible entries, newest first. Photos stay on the phone.
    static func csv(_ entries: [CatchEntry]) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = calendar.timeZone; formatter.dateFormat = "yyyy-MM-dd HH:mm"
        func cell(_ value: Any?) -> String {
            let text: String
            switch value {
            case let number as Double: text = number == number.rounded() ? String(Int(number)) : String(number)
            case let some?: text = "\(some)"
            case nil: text = ""
            }
            return text.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" }) ? "\"" + text.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : text
        }
        let header = "Date and time (NZ),Species,Length (cm),Kept,Count,Place,Latitude,Longitude,Tide,Wind,Waves,Water temperature,Moon,Notes"
        let rows = visible(entries).map { e -> String in
            let cells: [Any?] = [formatter.string(from: e.caughtAt), e.species, e.lengthCm.map(formatNumber), e.kept ? "Kept" : "Released", e.count,
                                 e.placeName, e.latitude, e.longitude, e.conditions.tide, e.conditions.wind, e.conditions.waves,
                                 e.conditions.waterTemperature, e.conditions.moon, e.notes]
            return cells.map(cell).joined(separator: ",")
        }
        return ([header] + rows).joined(separator: "\n") + "\n"
    }

    /// "Snapper · 32 cm · kept" or "2 × Kahawai · released".
    static func headline(_ entry: CatchEntry) -> String {
        [(entry.count > 1 ? "\(entry.count) × " : "") + entry.species, entry.lengthCm.map(formatCm), entry.kept ? "kept" : "released"]
            .compactMap { $0 }.joined(separator: " · ")
    }

    static func formatCm(_ value: Double) -> String { "\(formatNumber(value)) cm" }
    static func formatNumber(_ value: Double) -> String { value == value.rounded() ? String(Int(value)) : String(format: "%.1f", value) }

    /// Common recreational species, offered before the area's rules load.
    static let commonSpecies = ["Snapper", "Kahawai", "Gurnard", "Trevally", "Kingfish", "Blue cod", "Tarakihi", "John dory",
                                "Blue moki", "Red cod", "Flatfish", "Butterfish", "Hāpuku", "Yellow-eyed mullet", "Rock lobster", "Pāua", "Scallops"]
}

