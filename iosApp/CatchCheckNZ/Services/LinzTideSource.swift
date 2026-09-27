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
    private struct Cached: Sendable { let fetchedAt: Date; let events: [LINZTidePrediction] }
    private var annualCache: [String: Cached] = [:]

    func load(stationName: String, year: Int) async throws -> [LINZTidePrediction] {
        let key = "\(stationName)-\(year)"
        if let cached = annualCache[key], (0..<86_400).contains(Date().timeIntervalSince(cached.fetchedAt)) { return cached.events }
        let filename = "\(stationName) \(year).csv"
        guard let path = filename.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))),
              let url = URL(string: "https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/\(path)") else { throw TideDataError.invalidSource }
        let request = URLRequest(url: url, cachePolicy: .reloadRevalidatingCacheData, timeoutInterval: 20)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0),
              let csv = String(data: data, encoding: .utf8) else { throw TideDataError.noPredictions }
        let events = try Self.parse(csv, stationName: stationName, year: year)
        let calendar = Self.calendar
        let days = Set(events.map { calendar.startOfDay(for: $0.time) })
        let expected = calendar.range(of: .day, in: .year, for: events[0].time)?.count
        guard days.count == expected else { throw TideDataError.invalidSource }
        annualCache[key] = Cached(fetchedAt: Date(), events: events)
        return events
    }

    static var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar
    }

    static func identity(_ name: String) -> String {
        name.replacingOccurrences(of: "‘", with: "'").replacingOccurrences(of: "’", with: "'")
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_NZ"))
            .split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// Published NZ clock times already include daylight saving. Ambiguity is not guessed.
    static func parse(_ csv: String, stationName: String, year: Int) throws -> [LINZTidePrediction] {
        let lines = csv.replacingOccurrences(of: "\u{FEFF}", with: "").split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        func fields(_ line: String) -> [String] {
            line.split(separator: ",", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
        }
        guard lines.count >= 4 else { throw TideDataError.invalidSource }
        let header = fields(lines[0])
        guard header.count >= 4, Int(header[0]) != nil, identity(header[1]) == identity(stationName),
              lines[1].hasPrefix("Based on constituent set with reference date:"),
              fields(lines[2]) == ["Local Std or Daylight Time", "Tidal heights in metres."] else { throw TideDataError.invalidSource }
        let calendar = Self.calendar
        var previousDay: Date?
        var events: [LINZTidePrediction] = []
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
                let anchor = localDay.addingTimeInterval(-1)
                guard let first = calendar.nextDate(after: anchor, matching: parts, matchingPolicy: .strict, repeatedTimePolicy: .first),
                      let last = calendar.nextDate(after: anchor, matching: parts, matchingPolicy: .strict, repeatedTimePolicy: .last),
                      first == last,
                      calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: first) == parts else { throw TideDataError.invalidSource }
                if let previous = events.last, previous.time >= first { throw TideDataError.invalidSource }
                events.append(.init(time: first, height: height))
            }
            guard events.count > previousCount else { throw TideDataError.invalidSource }
        }
        guard events.count >= 2 else { throw TideDataError.noPredictions }
        for index in events.indices {
            let neighbours = [index - 1, index + 1].filter { events.indices.contains($0) }.map { events[$0].height }
            let height = events[index].height
            guard neighbours.allSatisfy({ height > $0 }) || neighbours.allSatisfy({ height < $0 }) else { throw TideDataError.invalidSource }
        }
        return events
    }
}
