import Foundation

struct LINZTidePrediction: Sendable {
    let time: Date
    let height: Double
}

enum TideDataError: LocalizedError {
    case noPredictions, invalidSource
    var errorDescription: String? { "LINZ tide predictions could not be verified for this station and date." }
}

/// Shared by the Tide page and fishing-window planner; no offshore sea-level fallback.
actor LINZTideStore {
    static let shared = LINZTideStore()
    // The UTC calendar stores wall-clock fields here, not actual event instants.
    private struct LocalPrediction: Sendable { let clock: Date; let height: Double }
    private struct Cached: Sendable { let fetchedAt: Date; let events: [LocalPrediction] }
    private var annualCache: [String: Cached] = [:]

    func load(stationName: String, year: Int) async throws -> [LINZTidePrediction] {
        let events = try await annual(stationName: stationName, year: year)
        try Self.validateExtrema(events)
        return try events.map(Self.resolve)
    }

    /// Only requested dates and their bracketing events need unambiguous NZ clock times.
    func predictions(stationName: String, start: Date, end: Date) async throws -> [LINZTidePrediction] {
        let calendar = Self.calendar
        let firstDay = calendar.startOfDay(for: start), lastDay = calendar.startOfDay(for: end)
        guard lastDay >= firstDay else { throw TideDataError.invalidSource }
        let firstYear = calendar.component(.year, from: firstDay), lastYear = calendar.component(.year, from: lastDay)
        var events: [LocalPrediction] = []
        for year in firstYear...lastYear { events += try await annual(stationName: stationName, year: year) }
        let from = Self.wallClock(firstDay)
        let until = Self.wallCalendar.date(byAdding: .day, value: 1, to: Self.wallClock(lastDay))!
        if calendar.ordinality(of: .day, in: .year, for: firstDay) == 1, !events.contains(where: { $0.clock < from }),
           let prior = try? await annual(stationName: stationName, year: firstYear - 1) { events += prior }
        if calendar.date(byAdding: .day, value: 1, to: lastDay).map({ calendar.component(.year, from: $0) }) != lastYear,
           !events.contains(where: { $0.clock >= until }),
           let following = try? await annual(stationName: stationName, year: lastYear + 1) { events += following }
        return try Self.resolveRange(events, start: start, end: end)
    }

    private func annual(stationName: String, year: Int) async throws -> [LocalPrediction] {
        let key = "\(stationName)-\(year)"
        if let cached = annualCache[key], (0..<86_400).contains(Date().timeIntervalSince(cached.fetchedAt)) { return cached.events }
        let filename = "\(stationName) \(year).csv"
        guard let path = filename.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))),
              let url = URL(string: "https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/\(path)") else { throw TideDataError.invalidSource }
        let request = URLRequest(url: url, cachePolicy: .reloadRevalidatingCacheData, timeoutInterval: 20)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0),
              let csv = String(data: data, encoding: .utf8) else { throw TideDataError.noPredictions }
        let events = try Self.parseLocal(csv, stationName: stationName, year: year)
        let calendar = Self.wallCalendar
        let days = Set(events.map { calendar.startOfDay(for: $0.clock) })
        let expected = calendar.range(of: .day, in: .year, for: events[0].clock)?.count
        guard days.count == expected else { throw TideDataError.invalidSource }
        annualCache[key] = Cached(fetchedAt: Date(), events: events)
        return events
    }

    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar
    }

    private static var wallCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private static func wallClock(_ date: Date) -> Date {
        wallCalendar.date(from: calendar.dateComponents([.year, .month, .day], from: date))!
    }

    private static func resolveRange(_ events: [LocalPrediction], start: Date, end: Date) throws -> [LINZTidePrediction] {
        let from = wallClock(start), lastDay = wallClock(end)
        guard lastDay >= from, let until = wallCalendar.date(byAdding: .day, value: 1, to: lastDay) else { throw TideDataError.invalidSource }
        let ordered = events.sorted { $0.clock < $1.clock }
        try validateChronology(ordered)
        let inRange = ordered.filter { $0.clock >= from && $0.clock < until }
        guard !inRange.isEmpty else { throw TideDataError.noPredictions }
        var selected: [LocalPrediction] = []
        if let previous = ordered.last(where: { $0.clock < from }) { selected.append(previous) }
        selected += inRange
        if let following = ordered.first(where: { $0.clock >= until }) { selected.append(following) }
        try validateExtrema(selected)
        return try selected.map(resolve)
    }

    private static func resolve(_ event: LocalPrediction) throws -> LINZTidePrediction {
        let parts = wallCalendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: event.clock)
        let dayParts = wallCalendar.dateComponents([.year, .month, .day], from: event.clock)
        let calendar = Self.calendar
        guard let day = calendar.date(from: dayParts) else { throw TideDataError.invalidSource }
        let anchor = day.addingTimeInterval(-1)
        guard let first = calendar.nextDate(after: anchor, matching: parts, matchingPolicy: .strict, repeatedTimePolicy: .first),
              let last = calendar.nextDate(after: anchor, matching: parts, matchingPolicy: .strict, repeatedTimePolicy: .last),
              first == last,
              calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: first) == parts else { throw TideDataError.invalidSource }
        return .init(time: first, height: event.height)
    }

    static func identity(_ name: String) -> String {
        name.replacingOccurrences(of: "‘", with: "'").replacingOccurrences(of: "’", with: "'")
            .replacingOccurrences(of: "\\s*'\\s*", with: "'", options: .regularExpression)
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_NZ"))
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// Published CSV headers can differ from LINZ's filenames; checked against the 2026 tables.
    private static func matchesHeader(_ header: String, stationName: String) -> Bool {
        let expected = identity(stationName), actual = identity(header)
        let aliases = [
            "halfmoon bay - oban": "halfmoon bay / oban",
            "kaituna river entrance": "kaituna river",
            "lottin point - wakatiri": "lottin point / wakatiri",
            "north cape - otou": "north cape / otou",
            "rangitaiki river entrance": "rangitaiki river",
            "town basin": "town basin - whangarei"
        ]
        return actual == expected || actual == aliases[expected]
    }

    /// Published NZ clock times already include daylight saving. Ambiguity is not guessed.
    static func parse(_ csv: String, stationName: String, year: Int) throws -> [LINZTidePrediction] {
        let events = try parseLocal(csv, stationName: stationName, year: year)
        try validateExtrema(events)
        return try events.map(resolve)
    }

    static func parse(_ csv: String, stationName: String, year: Int, start: Date, end: Date) throws -> [LINZTidePrediction] {
        try resolveRange(parseLocal(csv, stationName: stationName, year: year), start: start, end: end)
    }

    private static func parseLocal(_ csv: String, stationName: String, year: Int) throws -> [LocalPrediction] {
        let lines = csv.replacingOccurrences(of: "\u{FEFF}", with: "").split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        func fields(_ line: String) -> [String] {
            line.split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
        }
        guard lines.count >= 4 else { throw TideDataError.invalidSource }
        let header = fields(lines[0])
        guard header.count >= 4, Int(header[0]) != nil, matchesHeader(header[1], stationName: stationName),
              lines[1].hasPrefix("Based on constituent set with reference date:"),
              fields(lines[2]) == ["Local Std or Daylight Time", "Tidal heights in metres."] else { throw TideDataError.invalidSource }
        let calendar = Self.wallCalendar
        var previousDay: Date?
        var events: [LocalPrediction] = []
        for line in lines.dropFirst(3) {
            let row = fields(line)
            guard row.count >= 6, row.count.isMultiple(of: 2), let day = Int(row[0]), let month = Int(row[2]),
                  let rowYear = Int(row[3]), rowYear == year else { throw TideDataError.invalidSource }
            let dateParts = DateComponents(year: rowYear, month: month, day: day)
            guard let localDay = calendar.date(from: dateParts),
                  calendar.dateComponents([.year, .month, .day], from: localDay) == dateParts else { throw TideDataError.invalidSource }
            if let previousDay, calendar.date(byAdding: .day, value: 1, to: previousDay) != localDay { throw TideDataError.invalidSource }
            previousDay = localDay
            var hasBlank = false
            let previousCount = events.count
            for index in stride(from: 4, to: row.count, by: 2) {
                if row[index].isEmpty && row[index + 1].isEmpty { hasBlank = true; continue }
                let time = row[index].split(separator: ":")
                guard !hasBlank, time.count == 2, let hour = Int(time[0]), let minute = Int(time[1]),
                      (0...23).contains(hour), (0...59).contains(minute),
                      let height = Double(row[index + 1]), height.isFinite else { throw TideDataError.invalidSource }
                let parts = DateComponents(year: rowYear, month: month, day: day, hour: hour, minute: minute, second: 0)
                guard let clock = calendar.date(from: parts),
                      calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: clock) == parts else { throw TideDataError.invalidSource }
                events.append(.init(clock: clock, height: height))
            }
            guard events.count > previousCount else { throw TideDataError.invalidSource }
        }
        // Rounded, equal-height extrema on another day must not hide valid requested tides.
        try validateChronology(events)
        return events
    }

    private static func validateChronology(_ events: [LocalPrediction]) throws {
        guard events.count >= 2 else { throw TideDataError.noPredictions }
        for index in events.indices where index > 0 {
            if events[index - 1].clock >= events[index].clock { throw TideDataError.invalidSource }
        }
    }

    private static func validateExtrema(_ events: [LocalPrediction]) throws {
        try validateChronology(events)
        for index in events.indices {
            let neighbours = [index - 1, index + 1].filter { events.indices.contains($0) }.map { events[$0].height }
            let height = events[index].height
            guard neighbours.allSatisfy({ height > $0 }) || neighbours.allSatisfy({ height < $0 }) else { throw TideDataError.invalidSource }
        }
    }
}
