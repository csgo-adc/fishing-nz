import Foundation

struct GeoPoint: Equatable, Sendable { let latitude: Double; let longitude: Double }
struct WeatherState { let temperature: String; let wind: String; let rain: String }
struct TideEvent: Identifiable { let time: String; let height: String; let type: String; var id: String { "\(time)-\(type)" } }
struct TidePoint: Identifiable { let time: String; let level: Double; let minuteOfDay: Int; var id: Int { minuteOfDay } }
struct TideState { let currentLevel: String; let nextEvent: String; let eventTime: String; let events: [TideEvent]; let points: [TidePoint]; let stationName: String }
struct TideStation: Identifiable, Equatable, Sendable { let id: String; let name: String; let csvName: String; let region: String; let latitude: Double; let longitude: Double }
enum WindowPriority: String, CaseIterable, Identifiable, Sendable {
    case weather, lateIncoming
    var id: String { rawValue }
    var title: String { self == .weather ? "Weather balance" : "Late incoming tide" }
    func title(boat: Bool) -> String { self == .lateIncoming ? "Late incoming tide" : (boat ? "Wave comfort" : "Comfort") }
}
enum ShoreSetting: String, CaseIterable, Sendable {
    case unknown = "Not set", wharf = "Wharf", bank = "Harbour bank", beach = "Beach", rocks = "Rocks"
}
enum LandPriority: String, CaseIterable, Sendable { case balanced = "Comfort", casting = "Easier casting", dry = "Less rain" }
struct LandPreferences: Equatable, Sendable {
    var setting: ShoreSetting = .unknown
    var priority: LandPriority = .balanced
    var maxBand = 1
    var arrivalMinutes: Int? = nil
    var returnMinutes: Int? = nil
    var daylightOnly = false
}
struct ConditionItem: Equatable, Sendable { let title: String; let value: String; let mood: WindowMood }
struct WindowAssessment: Equatable, Sendable {
    let mood: WindowMood
    let conditions: [ConditionItem]
    let confidence: WindowMood
    var checks = WindowMood(emoji: "🔎", label: "Local checks needed")
    var details: [String] = []
    var band = 0
    var demandingHours: Double = 0
    var uncomfortableHours: Double = 0
    var matchesComfort = true
    var comfortComplete = true
    var maxWind: Double = 0
    var maxGust: Double = 0
    var rainTotal: Double = 0
    var priority: LandPriority = .balanced
}
func comfortMood(_ band: Int) -> WindowMood {
    switch band {
    case 0: WindowMood(emoji: "😌", label: "Comfortable")
    case 1: WindowMood(emoji: "🙂", label: "Okay")
    case 2: WindowMood(emoji: "😕", label: "Demanding")
    default: WindowMood(emoji: "😣", label: "Uncomfortable")
    }
}
let needsDataMood = WindowMood(emoji: "❓", label: "Needs more data")

/// A subjective planning reaction, not a catch probability or safety clearance.
struct WindowMood: Equatable, Sendable {
    let emoji: String
    let label: String

    static let excellent = WindowMood(emoji: "😄", label: "Excellent")
    static let good = WindowMood(emoji: "🙂", label: "Good")
    static let mixed = WindowMood(emoji: "😐", label: "Fair")
    static let concern = WindowMood(emoji: "😟", label: "Concerning")
    static let unknown = WindowMood(emoji: "🤔", label: "Unverified")
}

private func numberAfter(_ marker: String, in text: String) -> Double? {
    guard let range = text.range(of: marker, options: .caseInsensitive) else { return nil }
    let value = text[range.upperBound...].drop(while: \.isWhitespace)
        .prefix { $0.isNumber || $0 == "." }
    return Double(String(value))
}

struct Recommendation: Identifiable, Equatable {
    let name: String; let area: String; let rating: Int; let time: String; let distance: String
    let reasons: [String]; let boat: Bool
    var warnings: [String] = []
    var startsAt: Date? = nil
    var endsAt: Date? = nil
    var summary: String = ""
    var conditions: [String] = []
    var sourceNote: String = ""
    var alternative: String? = nil
    var dataComplete: Bool = true
    var tidePreferenceFit: Double = 0
    var assessment: WindowAssessment? = nil
    /// The named area's marker, so a shared window can link to a map.
    var coordinate: GeoPoint? = nil
    var id: String { "\(boat ? "boat" : "land"):\(name)" }
    var windowID: String { "\(id):\(startsAt?.timeIntervalSince1970 ?? 0)" }
    var windowMood: WindowMood {
        if let assessment { return assessment.mood }
        let seriousWarnings = ["Strong gusts", "Elevated offshore waves", "Short-period waves", "Long-period waves", "Fog may reduce visibility"]
        let serious = warnings.contains { warning in seriousWarnings.contains { warning.hasPrefix($0) } }
        let mixed = warnings.contains { $0.hasPrefix("Part or all of this session is after dark") || $0.hasPrefix("Rain could affect") }
        switch (serious, dataComplete, rating, mixed) {
        case (true, _, _, _): return WindowMood(emoji: "😟", label: "Check conditions")
        case (_, false, _, _): return WindowMood(emoji: "🤔", label: "Forecast incomplete")
        case (_, _, ..<50, _): return WindowMood(emoji: "😟", label: "Challenging")
        case (_, _, _, true): return WindowMood(emoji: "😐", label: "Mixed conditions")
        case (_, _, 85..., _): return .excellent
        case (_, _, 70..., _): return .good
        default: return WindowMood(emoji: "😐", label: "Fair")
        }
    }

    var windowOutlook: String { "\(windowMood.emoji) \(windowMood.label)" }

    func conditionMood(_ index: Int) -> WindowMood {
        let detail = conditions.indices.contains(index) ? conditions[index] : ""
        func warned(_ prefixes: String...) -> Bool {
            warnings.contains { warning in prefixes.contains { warning.hasPrefix($0) } }
        }
        switch index {
        case 0: // Wind
            if warned("Strong gusts") { return .concern }
            if (numberAfter("gusts up to", in: detail) ?? .greatestFiniteMagnitude) <= (boat ? 25 : 30) { return .good }
            return .mixed
        case 1: // Rain
            if warned("Rain could affect") { return WindowMood(emoji: "😕", label: "Unfavourable") }
            if detail.hasPrefix("0.0 mm rain"), (numberAfter("highest hourly rain chance", in: detail) ?? 100) <= 30 { return .good }
            return .mixed
        case 2: // Tide
            if detail.hasPrefix("No verified") || detail.hasPrefix("Local tide coverage") || detail.hasPrefix("Local tide events") { return .unknown }
            return tidePreferenceFit >= 0.8 ? .good : .mixed
        case 3: // Daylight
            return detail.contains("whole fishing session is in daylight") ? .good : WindowMood(emoji: "😕", label: "Limited daylight")
        case 4: // Waves
            if warned("Elevated offshore waves", "Short-period waves", "Long-period waves") { return .concern }
            if detail.contains("unavailable") || warned("Wave data is incomplete") { return .unknown }
            if (numberAfter("wave height up to", in: detail) ?? .greatestFiniteMagnitude) <= 0.5 { return .good }
            return .mixed
        default: return .unknown
        }
    }
}

/// What a fishing window card puts first: the day and hours, an outlook colour, three key numbers and anything that
/// needs attention. Built from the window's own condition rows, so shared windows highlight the same way. Android has the
/// same rules in `WindowHighlights.kt`.
enum OutlookTone { case good, caution, poor, unknown }

struct KeyTile: Equatable {
    let label: String
    let value: String
    let detail: String
    let concern: Bool
}

enum WindowHighlights {
    static let goodLabels: Set<String> = ["Excellent", "Good", "Comfortable", "Okay"]
    static let poorLabels: Set<String> = ["Uncomfortable", "Challenging", "Concerning", "Check conditions"]
    static let cautionLabels: Set<String> = ["Demanding", "Fair", "Mixed conditions", "Unfavourable"]
    /// Condition moods that make a key number amber and add a line to the watch-out box.
    static let concernLabels: Set<String> = ["Demanding", "Uncomfortable", "Choppy", "More motion", "High waves", "Wet", "Cold", "Hot",
                                             "After dark", "Limited daylight", "Challenging", "Concerning", "Check conditions", "Unfavourable"]

    static func tone(_ label: String) -> OutlookTone {
        if goodLabels.contains(label) { return .good }
        if poorLabels.contains(label) { return .poor }
        if cautionLabels.contains(label) { return .caution }
        return .unknown
    }

    static func segments(_ value: String) -> [String] {
        (value.split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? value)
            .components(separatedBy: " · ").map { $0.trimmingCharacters(in: .whitespaces) }
    }

    static func isConcern(_ item: ConditionItem) -> Bool { concernLabels.contains(item.mood.label) }

    static func tile(_ label: String, _ item: ConditionItem?) -> KeyTile {
        guard let item, item.value.trimmingCharacters(in: .whitespaces) != "—" else { return KeyTile(label: label, value: "—", detail: "No data", concern: false) }
        // Wind and waves in plain words (see PlainConditions); a row in another form keeps its own two parts.
        let plain = item.title == "Wind" ? PlainConditions.windTile(item.value) : item.title == "Offshore waves" ? PlainConditions.waveTile(item.value) : nil
        if let plain { return KeyTile(label: label, value: plain.value, detail: plain.detail, concern: isConcern(item)) }
        let parts = segments(item.value)
        let first = parts.first ?? item.value
        switch item.title {
        case "Rain":
            if let chance = parts.first(where: { $0.contains("%") })?.split(separator: " ").first.map(String.init) {
                return KeyTile(label: label, value: chance, detail: "chance · \(first) total", concern: isConcern(item))
            }
            return KeyTile(label: label, value: first, detail: parts.count > 1 ? parts[1] : "", concern: isConcern(item))
        case "Tide":
            let high = first.localizedCaseInsensitiveContains("high")
            let time = first.range(of: "\\d{1,2}:\\d{2}\\s?[AP]M", options: [.regularExpression, .caseInsensitive]).map { String(first[$0]) } ?? ""
            return KeyTile(label: label, value: high ? "High" : "Low", detail: first.hasPrefix("Next") ? "next \(time)" : time, concern: isConcern(item))
        default:
            return KeyTile(label: label, value: first, detail: parts.count > 1 ? parts[1] : "", concern: isConcern(item))
        }
    }
}

/// Condition rows in plain words: "22 km/h · gust 28 · S" reads "Breezy: 22 km/h, gusts to 28 km/h, from the south".
/// A window's rows keep their short form because shared links carry it and older apps read it, so a window is put into
/// words when it is shown (`windowValue`). The map's conditions page builds its rows with the same words. Android has the
/// same rules in `PlainConditions.kt`, the shared-window page in `plain-conditions.ts`.
enum PlainConditions {
    private static let compass = ["N": "north", "NE": "north-east", "E": "east", "SE": "south-east",
                                  "S": "south", "SW": "south-west", "W": "west", "NW": "north-west"]

    /// The word for a steady wind, in km/h. The steps follow the land comfort bands (12, 20 and 30 km/h).
    static func windStrength(_ kmh: Int) -> String {
        kmh < 12 ? "Light wind" : kmh < 20 ? "Gentle breeze" : kmh < 30 ? "Breezy" : kmh < 40 ? "Windy" : "Very windy"
    }

    /// "from the south" for one direction, "from SW, then W" when it changes; nil when unknown.
    static func from(_ directions: [String]) -> String? {
        let known = directions.map { $0.trimmingCharacters(in: .whitespaces) }.filter { compass[$0] != nil }
        switch known.count {
        case 0: return nil
        case 1: return "from the \(compass[known[0]]!)"
        default: return "from " + known.joined(separator: ", then ")
        }
    }

    /// Whole seconds from a period range: "6.5–7.0" is "7", "5.6–8.2" is "6–8"; nil without a number.
    static func seconds(_ range: String?) -> String? {
        let values = (range ?? "").components(separatedBy: "–").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
        guard let lowest = values.min(), let highest = values.max() else { return nil }
        let low = Int(lowest.rounded()), high = Int(highest.rounded())
        return low == high ? "\(low)" : "\(low)–\(high)"
    }

    /// "Breezy: 22 km/h, gusts to 28 km/h, from the south"; `upTo` for a whole day's strongest wind.
    static func wind(speed: Double?, gust: Double?, directions: [String], upTo: Bool = false) -> String {
        guard let speed else { return "—" }
        let steady = Int(speed.rounded())
        return ["\(windStrength(steady)): \(upTo ? "up to " : "")\(steady) km/h", gust.map { "gusts to \(Int($0.rounded())) km/h" }, from(directions)]
            .compactMap { $0 }.joined(separator: ", ")
    }

    /// "Up to 1.2 m, 6–7 seconds apart".
    static func waves(height: Double?, periods: String?) -> String {
        guard let height else { return "—" }
        let apart = seconds(periods).map { ", \($0) \($0 == "1" ? "second" : "seconds") apart" } ?? ""
        return "Up to \(String(format: "%.1f", height)) m\(apart)"
    }

    /// "0.4 mm in total, up to 40% chance in any hour", or "… all day" for a whole day on the map. A 0% chance is left out.
    static func rain(total: Double?, chance: Double?, wholeDay: Bool = false) -> String {
        let amount = total.map { String(format: "%.1f", $0) }
        let parts = [amount.map { $0 == "0.0" ? "No rain expected" : "\($0) mm \(wholeDay ? "all day" : "in total")" },
                     chance.map { Int($0.rounded()) }.flatMap { $0 > 0 ? "up to \($0)% chance in any hour" : nil }].compactMap { $0 }
        guard let first = parts.first else { return "—" }
        let text = parts.joined(separator: ", ")
        return first.hasPrefix("up") ? text.prefix(1).uppercased() + text.dropFirst() : text
    }

    private static let windRow = "^(\\d+) km/h · gust (\\d+)(?: · ([A-Z/]+))?$"
    private static let waveRow = "^(\\d+(?:\\.\\d+)?) m · (.+) s$"
    private static let rainRow = "^(\\d+(?:\\.\\d+)?) mm · peak \\S+ mm/h · (?:(\\d+)% hourly|chance —)$"
    private static let tideEvent = "^(Next )?(high|low) (.+) · (\\d+(?:\\.\\d+)?) m CD$"
    private static let daylightShare = "^(\\d+)% (session|visit|known time)$"
    private static let shorePlan = "^(.+) · before (.+) · return (.+)$"

    /// The capture groups of a whole-text match ("" for a group that took no part), or nil.
    private static func groups(_ pattern: String, _ text: String) -> [String]? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
        return (1..<match.numberOfRanges).map { Range(match.range(at: $0), in: text).map { String(text[$0]) } ?? "" }
    }

    /// A window's condition row in plain words. Text it does not recognise, such as a cut-off shared row, stays as it is.
    static func windowValue(_ item: ConditionItem) -> String {
        let value = item.value.trimmingCharacters(in: .whitespaces)
        let plain: String?
        switch item.title {
        case "Wind":
            plain = groups(windRow, value).map { wind(speed: Double($0[0]), gust: Double($0[1]), directions: $0[2].components(separatedBy: "/")) }
        case "Offshore waves":
            plain = groups(waveRow, value).map { waves(height: Double($0[0]), periods: $0[1]) }
        case "Rain":
            plain = groups(rainRow, value).map { rain(total: Double($0[0]), chance: Double($0[1])) }
        case "Tide":
            plain = tide(value)
        case "Daylight":
            plain = groups(daylightShare, value).map { daylight($0[0], $0[1]) }
        case "Shore plan":
            plain = groups(shorePlan, value).map { parts in
                [parts[0] == "Not set" ? "Shore type not set" : parts[0],
                 parts[1] == "—" ? "walk-in time not set" : "\(parts[1]) walk in",
                 parts[2] == "—" ? "return time not set" : "\(parts[2]) back"].joined(separator: " · ")
            }
        default:
            plain = nil
        }
        return plain ?? value
    }

    /// "Next high tide 7:12 PM, 2.4 m" and "Tide station: Auckland, 3.0 km away".
    private static func tide(_ value: String) -> String? {
        let lines = value.components(separatedBy: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        guard let first = lines.first, let event = groups(tideEvent, first) else { return nil }
        let kind = event[1]
        let start = event[0].isEmpty ? kind.prefix(1).uppercased() + kind.dropFirst() : "Next \(kind)"
        let stations = lines.dropFirst().map { line in
            "Tide station: " + (line.hasPrefix("LINZ ") ? String(line.dropFirst(5)) : line).replacingOccurrences(of: " · ", with: ", ")
        }
        return (["\(start) tide \(event[2]), \(event[3]) m"] + stations).joined(separator: "\n")
    }

    private static func daylight(_ percent: String, _ of: String) -> String {
        if percent == "100" { return of == "visit" ? "Whole visit in daylight" : "All in daylight" }
        if percent == "0" { return "All after dark" }
        if of == "visit" { return "\(percent)% of your visit in daylight" }
        if of == "known time" { return "\(percent)% of the known times in daylight" }
        return "\(percent)% in daylight"
    }

    /// "22 km/h" over "Breezy · gusts 28 · from S" ("from SW to E" when it changes). Nil when the row is not recognised.
    static func windTile(_ value: String) -> (value: String, detail: String)? {
        guard let parts = groups(windRow, value.trimmingCharacters(in: .whitespaces)), let speed = Int(parts[0]) else { return nil }
        let directions = parts[2].components(separatedBy: "/").filter { compass[$0] != nil }
        let from = directions.isEmpty ? nil : directions.count == 1 ? "from \(directions[0])" : "from \(directions.first!) to \(directions.last!)"
        return ("\(parts[0]) km/h", [windStrength(speed), "gusts \(parts[1])", from].compactMap { $0 }.joined(separator: " · "))
    }

    /// "1.2 m" over "6–7 s apart". Nil when the row is not recognised.
    static func waveTile(_ value: String) -> (value: String, detail: String)? {
        guard let parts = groups(waveRow, value.trimmingCharacters(in: .whitespaces)) else { return nil }
        return ("\(parts[0]) m", seconds(parts[1]).map { "\($0) s apart" } ?? "")
    }
}

extension Recommendation {
    /// True when the card can use the highlighted layout: a timed window with condition rows.
    var hasHighlights: Bool {
        guard assessment != nil, let startsAt, let endsAt else { return false }
        return endsAt > startsAt
    }

    private func condition(_ title: String) -> ConditionItem? { assessment?.conditions.first { $0.title == title } }

    /// "Wed 14 Oct" and "10:00 AM–12:00 PM" (or "11:00 PM–Thu 1:00 AM" across midnight), in New Zealand time.
    var windowDayAndHours: (day: String, hours: String) {
        guard let startsAt, let endsAt else { return (time, "") }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        func text(_ date: Date, _ pattern: String) -> String {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = calendar.timeZone
            formatter.dateFormat = pattern
            return formatter.string(from: date)
        }
        let end = calendar.isDate(startsAt, inSameDayAs: endsAt) ? text(endsAt, "h:mm a") : text(endsAt, "EEE h:mm a")
        return (text(startsAt, "EEE d MMM"), "\(text(startsAt, "h:mm a"))–\(end)")
    }

    /// Land: wind, tide and rain. Boat: waves, wind and tide.
    var keyTiles: [KeyTile] {
        boat ? [WindowHighlights.tile("Waves", condition("Offshore waves")), WindowHighlights.tile("Wind", condition("Wind")), WindowHighlights.tile("Tide", condition("Tide"))]
             : [WindowHighlights.tile("Wind", condition("Wind")), WindowHighlights.tile("Tide", condition("Tide")), WindowHighlights.tile("Rain", condition("Rain"))]
    }

    /// Short lines for the amber box: only what needs attention, most important first.
    var watchOuts: [String] {
        guard let assessment else { return [] }
        var lines: [String] = []
        if let wind = condition("Wind"), WindowHighlights.isConcern(wind) {
            if let range = wind.value.range(of: "gust \\d+", options: .regularExpression) {
                lines.append("Gusts to \(wind.value[range].dropFirst(5)) km/h")
            } else { lines.append("Wind \(WindowHighlights.segments(wind.value).first ?? wind.value)") }
        }
        if let waves = condition("Offshore waves"), WindowHighlights.isConcern(waves) {
            let height = WindowHighlights.segments(waves.value).first ?? waves.value
            lines.append(waves.mood.label == "Choppy" ? "Short, choppy waves (\(height))" : "Waves to \(height)")
        }
        if let rain = condition("Rain"), WindowHighlights.isConcern(rain) { lines.append("Rain: \(WindowHighlights.segments(rain.value).first ?? rain.value) expected") }
        if let feels = condition("Feels like"), WindowHighlights.isConcern(feels) { lines.append("\(feels.mood.label): feels like \(WindowHighlights.segments(feels.value).first ?? feels.value)") }
        if let daylight = condition("Daylight"), WindowHighlights.isConcern(daylight) { lines.append("Part of the session is after dark") }
        if assessment.comfortComplete && !assessment.matchesComfort { lines.append("Outside your comfort preference") }
        return lines
    }

    /// "Planning forecast · local checks needed".
    var forecastNote: String? {
        guard let assessment else { return nil }
        return "\(assessment.confidence.label) · \(assessment.checks.label.prefix(1).lowercased())\(assessment.checks.label.dropFirst())"
    }
}

func warningMood(_ warning: String) -> WindowMood {
    if warning.hasPrefix("Wave data is incomplete") || warning.hasPrefix("Verified local tide coverage is unavailable") ||
        warning.hasPrefix("Long-range forecast") { return .unknown }
    if warning.hasPrefix("The tide station is a reference") || warning.hasPrefix("Local access, shelter") { return .mixed }
    if warning.hasPrefix("Part or all of this session is after dark") || warning.hasPrefix("Rain could affect") {
        return WindowMood(emoji: "😕", label: "Unfavourable")
    }
    return .concern
}
struct FishRuleDetail: Decodable, Identifiable { let label: String; let value: String; var id: String { "\(label)-\(value)" } }
struct FishRuleMatch: Decodable, Identifiable { let species: String; let dailyLimit: String?; let minimumSize: String?; let minimumSizeLabel: String?; let details: [FishRuleDetail]; var id: String { species + (dailyLimit ?? "") + (minimumSize ?? "") } }
struct FishRulesResult: Decodable {
    let areaID: String; let areaName: String; let rulesNeedsReview: Bool; let rulesReviewedAt: String?
    let rulesSourceURL: URL?; let fishRules: [FishRuleMatch]
    enum CodingKeys: String, CodingKey {
        case areaName, rulesNeedsReview, rulesReviewedAt, fishRules
        case areaID = "areaId"
        case rulesSourceURL = "rulesSourceUrl"
    }
}
struct FishCheck {
    let commonName: String; let scientificName: String; let confidence: Int
    let areaID: String?; let areaName: String; let areaIsEstimated: Bool
    let rulesNeedsReview: Bool; let rulesReviewedAt: String?; let rulesSourceURL: URL?
    let fishRules: [FishRuleMatch]; let isFish: Bool; let otherPossibilities: [String]
    let visibleClues: String; let identificationNote: String
    var fishIdentityQuota: FishIdentityQuota? = nil

    func withRules(_ rules: FishRulesResult) -> FishCheck {
        FishCheck(commonName: commonName, scientificName: scientificName, confidence: confidence,
                  areaID: rules.areaID, areaName: rules.areaName, areaIsEstimated: false,
                  rulesNeedsReview: rules.rulesNeedsReview, rulesReviewedAt: rules.rulesReviewedAt,
                  rulesSourceURL: rules.rulesSourceURL, fishRules: rules.fishRules,
                  isFish: isFish, otherPossibilities: otherPossibilities,
                  visibleClues: visibleClues, identificationNote: identificationNote)
    }

    func awaitingRules(for areaID: String?, name: String) -> FishCheck {
        FishCheck(commonName: commonName, scientificName: scientificName, confidence: confidence,
                  areaID: areaID, areaName: name, areaIsEstimated: false,
                  rulesNeedsReview: false, rulesReviewedAt: nil, rulesSourceURL: nil, fishRules: [],
                  isFish: isFish, otherPossibilities: otherPossibilities,
                  visibleClues: visibleClues, identificationNote: identificationNote)
    }
}

struct AccountProfile: Decodable, Equatable {
    let id: String
    let email: String
    let displayName: String
    let countryCode: String
    let plan: String
    // Missing from older service responses; nil is treated as "has a password".
    let hasPasswordField: Bool?
    var hasPassword: Bool { hasPasswordField ?? true }
    enum CodingKeys: String, CodingKey { case id, email, plan; case displayName = "display_name"; case countryCode = "country_code"; case hasPasswordField = "has_password" }
}
struct AccountFeatures: Decodable { let fishIdentity: Bool; enum CodingKeys: String, CodingKey { case fishIdentity = "fish_identity" } }
struct FishIdentityQuota: Decodable {
    let limit: Int
    let used: Int
    let remaining: Int
    let day: String
    var remainingToday: Int {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        formatter.dateFormat = "yyyy-MM-dd"
        return day == formatter.string(from: .now) ? remaining : limit
    }
}
struct AccountPermissions: Decodable {
    let plan: String
    let features: AccountFeatures
    let fishIdentityQuota: FishIdentityQuota?
    enum CodingKeys: String, CodingKey { case plan, features; case fishIdentityQuota = "fish_identity_quota" }
}
struct AccountSnapshot: Decodable { let user: AccountProfile; let permissions: AccountPermissions? }
struct AccountProfileEnvelope: Decodable { let user: AccountProfile }
typealias AccountPermissionsEnvelope = AccountPermissions
struct AccountAuthResponse: Decodable { let token: String; let user: AccountProfile; let permissions: AccountPermissions }

/// A town, or a spot picked on the map, to search for fishing windows around. The same towns as Android's `searchOrigins`.
struct SearchOrigin: Identifiable, Equatable, Sendable {
    let name: String
    let point: GeoPoint
    var id: String { name }
}

// Manual origins keep radius searches usable when device location is unavailable.
let searchOrigins: [SearchOrigin] = [
    .init(name: "Whangārei", point: .init(latitude: -35.725, longitude: 174.324)),
    .init(name: "Kerikeri", point: .init(latitude: -35.227, longitude: 173.95)),
    .init(name: "Kaitaia", point: .init(latitude: -35.112, longitude: 173.263)),
    .init(name: "Whitianga", point: .init(latitude: -36.835, longitude: 175.705)),
    .init(name: "Hamilton", point: .init(latitude: -37.787, longitude: 175.279)),
    .init(name: "Auckland", point: .init(latitude: -36.8485, longitude: 174.7633)),
    .init(name: "Tauranga", point: .init(latitude: -37.686, longitude: 176.166)),
    .init(name: "Thames", point: .init(latitude: -37.138, longitude: 175.54)),
    .init(name: "Whakatāne", point: .init(latitude: -37.953, longitude: 176.991)),
    .init(name: "Gisborne", point: .init(latitude: -38.662, longitude: 178.018)),
    .init(name: "Napier", point: .init(latitude: -39.493, longitude: 176.912)),
    .init(name: "New Plymouth", point: .init(latitude: -39.057, longitude: 174.074)),
    .init(name: "Whanganui", point: .init(latitude: -39.932, longitude: 175.052)),
    .init(name: "Wellington", point: .init(latitude: -41.286, longitude: 174.776)),
    .init(name: "Nelson", point: .init(latitude: -41.271, longitude: 173.284)),
    .init(name: "Blenheim", point: .init(latitude: -41.513, longitude: 173.961)),
    .init(name: "Westport", point: .init(latitude: -41.755, longitude: 171.603)),
    .init(name: "Greymouth", point: .init(latitude: -42.449, longitude: 171.207)),
    .init(name: "Hokitika", point: .init(latitude: -42.717, longitude: 170.965)),
    .init(name: "Kaikōura", point: .init(latitude: -42.404, longitude: 173.681)),
    .init(name: "Christchurch", point: .init(latitude: -43.532, longitude: 172.636)),
    .init(name: "Timaru", point: .init(latitude: -44.397, longitude: 171.254)),
    .init(name: "Oamaru", point: .init(latitude: -45.097, longitude: 170.969)),
    .init(name: "Dunedin", point: .init(latitude: -45.878, longitude: 170.503)),
    .init(name: "Invercargill", point: .init(latitude: -46.413, longitude: 168.354)),
    .init(name: "Bluff", point: .init(latitude: -46.598, longitude: 168.33))
]

// Verified daily-prediction locations and coordinates from the LINZ tide prediction list and 2026 CSV headers.
// https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions/tide-predictions-list-view
// Offset/secondary ports are excluded because they require reference-port corrections.
let tideStations: [TideStation] = [
    .init(id: "akaroa", name: "Akaroa", csvName: "Akaroa", region: "Canterbury & West Coast", latitude: -43.8, longitude: 172.966667),
    .init(id: "anakakata-bay", name: "Anakakata Bay", csvName: "Anakakata Bay", region: "Upper South Island & Wellington", latitude: -41.05, longitude: 174.283333),
    .init(id: "anawhata", name: "Anawhata", csvName: "Anawhata", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.933333, longitude: 174.45),
    .init(id: "auckland", name: "Auckland", csvName: "Auckland", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.85, longitude: 174.766667),
    .init(id: "ben-gunn-wharf", name: "Ben Gunn Wharf", csvName: "Ben Gunn Wharf", region: "Northland & Hauraki Gulf", latitude: -35.0, longitude: 173.266667),
    .init(id: "bluff", name: "Bluff", csvName: "Bluff", region: "Southland & Rakiura", latitude: -46.6, longitude: 168.35),
    .init(id: "castlepoint", name: "Castlepoint", csvName: "Castlepoint", region: "Upper South Island & Wellington", latitude: -40.916667, longitude: 176.216667),
    .init(id: "charleston", name: "Charleston", csvName: "Charleston", region: "Upper South Island & Wellington", latitude: -41.908333, longitude: 171.433333),
    .init(id: "dargaville", name: "Dargaville", csvName: "Dargaville", region: "Northland & Hauraki Gulf", latitude: -35.933333, longitude: 173.866667),
    .init(id: "deep-cove", name: "Deep Cove", csvName: "Deep Cove", region: "Fiordland & Westland", latitude: -45.466667, longitude: 167.15),
    .init(id: "dog-island", name: "Dog Island", csvName: "Dog Island", region: "Southland & Rakiura", latitude: -46.65, longitude: 168.416667),
    .init(id: "dunedin", name: "Dunedin", csvName: "Dunedin", region: "Otago & Southland", latitude: -45.883333, longitude: 170.5),
    .init(id: "elaine-bay", name: "Elaine Bay", csvName: "Elaine Bay", region: "Upper South Island & Wellington", latitude: -41.05, longitude: 173.766667),
    .init(id: "elie-bay", name: "Elie Bay", csvName: "Elie Bay", region: "Upper South Island & Wellington", latitude: -41.131667, longitude: 173.991667),
    .init(id: "flour-cask-bay", name: "Flour Cask Bay", csvName: "Flour Cask Bay", region: "Southland & Rakiura", latitude: -47.283333, longitude: 167.483333),
    .init(id: "fresh-water-basin", name: "Fresh Water Basin", csvName: "Fresh Water Basin", region: "Fiordland & Westland", latitude: -44.666667, longitude: 167.933333),
    .init(id: "gisborne", name: "Gisborne", csvName: "Gisborne", region: "Lower North Island", latitude: -38.666667, longitude: 178.033333),
    .init(id: "green-island", name: "Green Island", csvName: "Green Island", region: "Otago & Southland", latitude: -45.95, longitude: 170.383333),
    .init(id: "halfmoon-bay-oban", name: "Halfmoon Bay / Oban", csvName: "Halfmoon Bay - Oban", region: "Southland & Rakiura", latitude: -46.9, longitude: 168.133333),
    .init(id: "havelock", name: "Havelock", csvName: "Havelock", region: "Upper South Island & Wellington", latitude: -41.283333, longitude: 173.766667),
    .init(id: "helensville", name: "Helensville", csvName: "Helensville", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.666667, longitude: 174.45),
    .init(id: "huruhi-harbour", name: "Huruhi Harbour", csvName: "Huruhi Harbour", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.6, longitude: 175.766667),
    .init(id: "jackson-bay", name: "Jackson Bay", csvName: "Jackson Bay", region: "Fiordland & Westland", latitude: -43.983333, longitude: 168.633333),
    .init(id: "kaikoura", name: "Kaikōura", csvName: "Kaikōura", region: "Canterbury & West Coast", latitude: -42.416667, longitude: 173.7),
    .init(id: "kaiteriteri", name: "Kaiteriteri", csvName: "Kaiteriteri", region: "Upper South Island & Wellington", latitude: -41.05, longitude: 173.016667),
    .init(id: "kaituna-river-entrance", name: "Kaituna River Entrance", csvName: "Kaituna River Entrance", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.75, longitude: 176.416667),
    .init(id: "kawhia", name: "Kawhia", csvName: "Kawhia", region: "Lower North Island", latitude: -38.066667, longitude: 174.816667),
    .init(id: "korotiti-bay", name: "Korotiti Bay", csvName: "Korotiti Bay", region: "Northland & Hauraki Gulf", latitude: -36.183333, longitude: 175.483333),
    .init(id: "leigh", name: "Leigh", csvName: "Leigh", region: "Northland & Hauraki Gulf", latitude: -36.283333, longitude: 174.8),
    .init(id: "long-island", name: "Long Island", csvName: "Long Island", region: "Upper South Island & Wellington", latitude: -41.116667, longitude: 174.283333),
    .init(id: "lottin-point-wakatiri", name: "Lottin Point / Wakatiri", csvName: "Lottin Point - Wakatiri", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.55, longitude: 178.166667),
    .init(id: "lyttelton", name: "Lyttelton", csvName: "Lyttelton", region: "Canterbury & West Coast", latitude: -43.6, longitude: 172.716667),
    .init(id: "man-o-war-bay", name: "Man o‘War Bay", csvName: "Man o‘War Bay", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.783333, longitude: 175.15),
    .init(id: "mana-marina", name: "Mana Marina", csvName: "Mana Marina", region: "Upper South Island & Wellington", latitude: -41.1, longitude: 174.866667),
    .init(id: "manu-bay", name: "Manu Bay", csvName: "Manu Bay", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.816667, longitude: 174.816667),
    .init(id: "mapua", name: "Māpua", csvName: "Māpua", region: "Upper South Island & Wellington", latitude: -41.25, longitude: 173.1),
    .init(id: "marsden-point", name: "Marsden Point", csvName: "Marsden Point", region: "Northland & Hauraki Gulf", latitude: -35.833333, longitude: 174.5),
    .init(id: "matiatia-bay", name: "Mātiatia Bay", csvName: "Mātiatia Bay", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.783333, longitude: 174.983333),
    .init(id: "motuara-island", name: "Motuara Island", csvName: "Motuara Island", region: "Upper South Island & Wellington", latitude: -41.093333, longitude: 174.271667),
    .init(id: "moturiki-island", name: "Moturiki Island", csvName: "Moturiki Island", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.633333, longitude: 176.183333),
    .init(id: "napier", name: "Napier", csvName: "Napier", region: "Lower North Island", latitude: -39.483333, longitude: 176.916667),
    .init(id: "nelson", name: "Nelson", csvName: "Nelson", region: "Upper South Island & Wellington", latitude: -41.266667, longitude: 173.266667),
    .init(id: "new-brighton-pier", name: "New Brighton Pier", csvName: "New Brighton Pier", region: "Canterbury & West Coast", latitude: -43.506667, longitude: 172.735),
    .init(id: "north-cape-otou", name: "North Cape / Otou", csvName: "North Cape - Otou", region: "Northland & Hauraki Gulf", latitude: -34.416667, longitude: 173.033333),
    .init(id: "oamaru", name: "Oamaru", csvName: "Oamaru", region: "Otago & Southland", latitude: -45.1, longitude: 170.983333),
    .init(id: "okukari-bay", name: "Ōkukari Bay", csvName: "Ōkukari Bay", region: "Upper South Island & Wellington", latitude: -41.2, longitude: 174.316667),
    .init(id: "omaha-bridge", name: "Omaha Bridge", csvName: "Omaha Bridge", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.341667, longitude: 174.765),
    .init(id: "omokoroa", name: "Ōmokoroa", csvName: "Ōmokoroa", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.666667, longitude: 176.05),
    .init(id: "onehunga", name: "Onehunga", csvName: "Onehunga", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.933333, longitude: 174.783333),
    .init(id: "opononi", name: "Opononi", csvName: "Opononi", region: "Northland & Hauraki Gulf", latitude: -35.5, longitude: 173.4),
    .init(id: "opotiki-wharf", name: "Ōpōtiki Wharf", csvName: "Ōpōtiki Wharf", region: "Lower North Island", latitude: -38.033333, longitude: 177.233333),
    .init(id: "opua", name: "Opua", csvName: "Opua", region: "Northland & Hauraki Gulf", latitude: -35.316667, longitude: 174.116667),
    .init(id: "paratutae-island", name: "Paratutae Island", csvName: "Paratutae Island", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.05, longitude: 174.516667),
    .init(id: "picton", name: "Picton", csvName: "Picton", region: "Upper South Island & Wellington", latitude: -41.283333, longitude: 174.0),
    .init(id: "port-chalmers", name: "Port Chalmers", csvName: "Port Chalmers", region: "Otago & Southland", latitude: -45.816667, longitude: 170.65),
    .init(id: "port-ohope-wharf", name: "Port Ōhope Wharf", csvName: "Port Ōhope Wharf", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.983333, longitude: 177.1),
    .init(id: "port-taranaki", name: "Port Taranaki", csvName: "Port Taranaki", region: "Lower North Island", latitude: -39.05, longitude: 174.033333),
    .init(id: "pouto-point", name: "Pouto Point", csvName: "Pouto Point", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.366667, longitude: 174.183333),
    .init(id: "raglan", name: "Raglan", csvName: "Raglan", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.8, longitude: 174.883333),
    .init(id: "rangatira-point", name: "Rangatira Point", csvName: "Rangatira Point", region: "Upper South Island & Wellington", latitude: -40.85, longitude: 174.933333),
    .init(id: "rangitaiki-river-entrance", name: "Rangitaiki River Entrance", csvName: "Rangitaiki River Entrance", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.916667, longitude: 176.866667),
    .init(id: "richmond-bay", name: "Richmond Bay", csvName: "Richmond Bay", region: "Upper South Island & Wellington", latitude: -41.015, longitude: 173.988333),
    .init(id: "riverton-aparima", name: "Riverton / Aparima", csvName: "Riverton - Aparima", region: "Southland & Rakiura", latitude: -46.366667, longitude: 168.016667),
    .init(id: "spit-wharf", name: "Spit Wharf", csvName: "Spit Wharf", region: "Otago & Southland", latitude: -45.783333, longitude: 170.716667),
    .init(id: "sumner-head", name: "Sumner Head", csvName: "Sumner Head", region: "Canterbury & West Coast", latitude: -43.566667, longitude: 172.766667),
    .init(id: "tamaki-river", name: "Tāmaki River", csvName: "Tāmaki River", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.911667, longitude: 174.861667),
    .init(id: "tarakohe", name: "Tarakohe", csvName: "Tarakohe", region: "Upper South Island & Wellington", latitude: -40.816667, longitude: 172.9),
    .init(id: "tauranga", name: "Tauranga", csvName: "Tauranga", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.65, longitude: 176.183333),
    .init(id: "thames", name: "Thames", csvName: "Thames", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.133333, longitude: 175.516667),
    .init(id: "timaru", name: "Timaru", csvName: "Timaru", region: "Canterbury & West Coast", latitude: -44.383333, longitude: 171.25),
    .init(id: "town-basin", name: "Town Basin", csvName: "Town Basin", region: "Northland & Hauraki Gulf", latitude: -35.716667, longitude: 174.333333),
    .init(id: "waihopai-river-entrance", name: "Waihopai River Entrance", csvName: "Waihopai River Entrance", region: "Southland & Rakiura", latitude: -46.416667, longitude: 168.333333),
    .init(id: "weiti-river-entrance", name: "Weiti River Entrance", csvName: "Weiti River Entrance", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.65, longitude: 174.733333),
    .init(id: "welcombe-bay", name: "Welcombe Bay", csvName: "Welcombe Bay", region: "Fiordland & Westland", latitude: -46.083333, longitude: 166.583333),
    .init(id: "wellington", name: "Wellington", csvName: "Wellington", region: "Upper South Island & Wellington", latitude: -41.283333, longitude: 174.783333),
    .init(id: "westport", name: "Westport", csvName: "Westport", region: "Upper South Island & Wellington", latitude: -41.75, longitude: 171.6),
    .init(id: "whakatane", name: "Whakatāne", csvName: "Whakatāne", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.95, longitude: 177.0),
    .init(id: "whanganui-river-entrance", name: "Whanganui River Entrance", csvName: "Whanganui River Entrance", region: "Lower North Island", latitude: -39.95, longitude: 174.983333),
    .init(id: "whangarei", name: "Whangārei", csvName: "Whangārei", region: "Northland & Hauraki Gulf", latitude: -35.766667, longitude: 174.35),
    .init(id: "whangaroa", name: "Whangaroa", csvName: "Whangaroa", region: "Northland & Hauraki Gulf", latitude: -35.05, longitude: 173.75),
    .init(id: "whitianga", name: "Whitianga", csvName: "Whitianga", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.833333, longitude: 175.7),
    .init(id: "wilson-bay", name: "Wilson Bay", csvName: "Wilson Bay", region: "Upper South Island & Wellington", latitude: -41.083333, longitude: 173.9)
]
