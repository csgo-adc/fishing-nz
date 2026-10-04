import Foundation

struct FishingSpot: Sendable {
    let name: String
    let area: String
    let coordinate: GeoPoint
    let boat: Bool
    var id: String { "\(boat ? "boat" : "land"):\(name)" }
}

struct ScoredFishingWindow: Identifiable, Sendable {
    let id: String
    let spotName: String
    let area: String
    let boat: Bool
    let score: Int
    let start: Date
    let end: Date
    let distanceKm: Double
    let reasons: [String]
    let warnings: [String]
    var summary: String = ""
    var conditions: [String] = []
    var sourceNote: String = ""
    var alternative: String? = nil
    var rankingValue: Double = 0
    var dataComplete: Bool = true
    var daylightFraction: Double = 0
    var tidePreferenceFit: Double = 0
}

struct PreferredFishingHours: Sendable {
    let startMinute: Int
    let endMinute: Int

    func contains(start: Date, end: Date, calendar: Calendar) -> Bool {
        let startDay = calendar.startOfDay(for: start)
        let startClock = calendar.component(.hour, from: start) * 60 + calendar.component(.minute, from: start)
        let endDayOffset = calendar.dateComponents([.day], from: startDay, to: calendar.startOfDay(for: end)).day ?? 0
        let endClock = endDayOffset * 1_440 + calendar.component(.hour, from: end) * 60 + calendar.component(.minute, from: end)
        if startMinute == endMinute { return endClock - startClock <= 1_440 }
        if startMinute < endMinute { return startClock >= startMinute && endClock <= endMinute }
        return (startClock >= startMinute && endClock <= endMinute + 1_440)
            || (startClock < endMinute && endClock <= endMinute)
    }
}

// Curated named coastal areas. Coordinates are approximate area markers, not access
// points or launch sites; distance is straight-line and local access must be checked.
let fishingSpots: [FishingSpot] = [
    .init(name: "Whangārei Harbour", area: "Northland", coordinate: .init(latitude: -35.72, longitude: 174.32), boat: false),
    .init(name: "Mission Bay", area: "Auckland", coordinate: .init(latitude: -36.8485, longitude: 174.7633), boat: false),
    .init(name: "Rangitoto Channel", area: "Auckland", coordinate: .init(latitude: -36.78, longitude: 174.93), boat: true),
    .init(name: "Takapuna Beach", area: "Auckland", coordinate: .init(latitude: -36.786, longitude: 174.773), boat: false),
    .init(name: "Wellington Harbour", area: "Wellington", coordinate: .init(latitude: -41.28, longitude: 174.78), boat: true),
    .init(name: "Nelson Harbour", area: "Nelson", coordinate: .init(latitude: -41.27, longitude: 173.28), boat: true),
    .init(name: "Paihia Wharf", area: "Bay of Islands", coordinate: .init(latitude: -35.283, longitude: 174.091), boat: false),
    .init(name: "Bay of Islands", area: "Northland", coordinate: .init(latitude: -35.235, longitude: 174.18), boat: true),
    .init(name: "Mangōnui Harbour", area: "Far North", coordinate: .init(latitude: -34.99, longitude: 173.535), boat: false),
    .init(name: "Tutukākā Coast", area: "Northland", coordinate: .init(latitude: -35.602, longitude: 174.537), boat: true),
    .init(name: "Orewa Beach", area: "Auckland", coordinate: .init(latitude: -36.587, longitude: 174.696), boat: false),
    .init(name: "Muriwai Beach", area: "Auckland", coordinate: .init(latitude: -36.821, longitude: 174.427), boat: false),
    .init(name: "Manukau Harbour", area: "Auckland", coordinate: .init(latitude: -37.05, longitude: 174.65), boat: true),
    .init(name: "Coromandel Harbour", area: "Coromandel", coordinate: .init(latitude: -36.745, longitude: 175.5), boat: false),
    .init(name: "Whitianga Harbour", area: "Coromandel", coordinate: .init(latitude: -36.83, longitude: 175.705), boat: true),
    .init(name: "Raglan", area: "Waikato", coordinate: .init(latitude: -37.799, longitude: 174.87), boat: false),
    .init(name: "Raglan", area: "Waikato", coordinate: .init(latitude: -37.78, longitude: 174.82), boat: true),
    .init(name: "Thames", area: "Coromandel", coordinate: .init(latitude: -37.136, longitude: 175.526), boat: false),
    .init(name: "Thames", area: "Firth of Thames", coordinate: .init(latitude: -37.10, longitude: 175.42), boat: true),
    .init(name: "Tauranga", area: "Bay of Plenty", coordinate: .init(latitude: -37.64, longitude: 176.18), boat: false),
    .init(name: "Tauranga", area: "Bay of Plenty", coordinate: .init(latitude: -37.59, longitude: 176.20), boat: true),
    .init(name: "Kāwhia", area: "Waikato", coordinate: .init(latitude: -38.063, longitude: 174.82), boat: false),
    .init(name: "Kāwhia", area: "Waikato", coordinate: .init(latitude: -38.03, longitude: 174.79), boat: true),
    .init(name: "Waihī Beach", area: "Bay of Plenty", coordinate: .init(latitude: -37.4, longitude: 175.943), boat: false),
    .init(name: "Waihī Beach", area: "Bay of Plenty", coordinate: .init(latitude: -37.39, longitude: 175.98), boat: true),
    .init(name: "Whangamatā", area: "Coromandel", coordinate: .init(latitude: -37.21, longitude: 175.875), boat: false),
    .init(name: "Whangamatā", area: "Coromandel", coordinate: .init(latitude: -37.19, longitude: 175.92), boat: true),
    .init(name: "Mount Maunganui", area: "Bay of Plenty", coordinate: .init(latitude: -37.632, longitude: 176.185), boat: false),
    .init(name: "Ōhope Beach", area: "Bay of Plenty", coordinate: .init(latitude: -37.966, longitude: 177.058), boat: false),
    .init(name: "Gisborne Harbour", area: "Gisborne", coordinate: .init(latitude: -38.672, longitude: 178.02), boat: true),
    .init(name: "Napier Breakwater", area: "Hawke's Bay", coordinate: .init(latitude: -39.48, longitude: 176.92), boat: false),
    .init(name: "New Plymouth Coast", area: "Taranaki", coordinate: .init(latitude: -39.055, longitude: 174.075), boat: false),
    .init(name: "Whanganui River Mouth", area: "Whanganui", coordinate: .init(latitude: -39.946, longitude: 174.98), boat: false),
    .init(name: "Kāpiti Coast", area: "Wellington", coordinate: .init(latitude: -40.92, longitude: 174.98), boat: false),
    .init(name: "Eastbourne", area: "Wellington", coordinate: .init(latitude: -41.29, longitude: 174.9), boat: false),
    .init(name: "Marlborough Sounds", area: "Marlborough", coordinate: .init(latitude: -41.1, longitude: 174.2), boat: true),
    .init(name: "Picton Foreshore", area: "Marlborough", coordinate: .init(latitude: -41.288, longitude: 174.008), boat: false),
    .init(name: "Kaikōura Coast", area: "Canterbury", coordinate: .init(latitude: -42.404, longitude: 173.684), boat: false),
    .init(name: "Lyttelton Harbour", area: "Canterbury", coordinate: .init(latitude: -43.62, longitude: 172.75), boat: true),
    .init(name: "New Brighton Pier", area: "Canterbury", coordinate: .init(latitude: -43.506, longitude: 172.729), boat: false),
    .init(name: "Akaroa Harbour", area: "Canterbury", coordinate: .init(latitude: -43.81, longitude: 172.965), boat: true),
    .init(name: "Timaru Coast", area: "Canterbury", coordinate: .init(latitude: -44.395, longitude: 171.256), boat: false),
    .init(name: "Moeraki Coast", area: "Otago", coordinate: .init(latitude: -45.36, longitude: 170.86), boat: false),
    .init(name: "Otago Harbour", area: "Otago", coordinate: .init(latitude: -45.84, longitude: 170.64), boat: true),
    .init(name: "St Clair Beach", area: "Dunedin", coordinate: .init(latitude: -45.91, longitude: 170.49), boat: false),
    .init(name: "Bluff Harbour", area: "Southland", coordinate: .init(latitude: -46.60, longitude: 168.34), boat: true),
    .init(name: "Riverton Coast", area: "Southland", coordinate: .init(latitude: -46.35, longitude: 168.02), boat: false),
    .init(name: "Stewart Island / Rakiura", area: "Southland", coordinate: .init(latitude: -46.895, longitude: 168.13), boat: true),
    .init(name: "Greymouth Coast", area: "West Coast", coordinate: .init(latitude: -42.45, longitude: 171.2), boat: false),
    .init(name: "Hokitika Coast", area: "West Coast", coordinate: .init(latitude: -42.715, longitude: 170.96), boat: false),
    .init(name: "Westport Coast", area: "West Coast", coordinate: .init(latitude: -41.75, longitude: 171.60), boat: false),
    // Additional named coastal reference points use coordinates published in LINZ daily tide CSV headers.
    // https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions/tide-predictions-list-view
    // Markers indicate areas to explore, not verified public fishing access or permitted fishing.
    .init(name: "Ōpōtiki Wharf", area: "Bay of Plenty", coordinate: .init(latitude: -38.033333, longitude: 177.233333), boat: false),
    .init(name: "Port Ōhope Wharf", area: "Bay of Plenty", coordinate: .init(latitude: -37.983333, longitude: 177.1), boat: false),
    .init(name: "Opua Foreshore", area: "Bay of Islands", coordinate: .init(latitude: -35.316667, longitude: 174.116667), boat: false),
    .init(name: "Opononi Foreshore", area: "Hokianga", coordinate: .init(latitude: -35.5, longitude: 173.4), boat: false),
    .init(name: "Whangaroa Harbour", area: "Northland", coordinate: .init(latitude: -35.05, longitude: 173.75), boat: false),
    .init(name: "Anawhata Coast", area: "Auckland West Coast", coordinate: .init(latitude: -36.933333, longitude: 174.45), boat: false),
    .init(name: "Mātiatia Bay", area: "Waiheke Island", coordinate: .init(latitude: -36.783333, longitude: 174.983333), boat: false),
    .init(name: "Kaituna River Mouth", area: "Bay of Plenty", coordinate: .init(latitude: -37.75, longitude: 176.416667), boat: false),
    .init(name: "Whakatāne Coast", area: "Bay of Plenty", coordinate: .init(latitude: -37.95, longitude: 177.0), boat: false),
    .init(name: "Port Taranaki Coast", area: "Taranaki", coordinate: .init(latitude: -39.05, longitude: 174.033333), boat: false),
    .init(name: "Castlepoint Coast", area: "Wairarapa", coordinate: .init(latitude: -40.916667, longitude: 176.216667), boat: false),
    .init(name: "Havelock Foreshore", area: "Marlborough", coordinate: .init(latitude: -41.283333, longitude: 173.766667), boat: false),
    .init(name: "Kaiteriteri Coast", area: "Tasman", coordinate: .init(latitude: -41.05, longitude: 173.016667), boat: false),
    .init(name: "Māpua Coast", area: "Tasman", coordinate: .init(latitude: -41.25, longitude: 173.1), boat: false),
    .init(name: "Tarakohe Coast", area: "Golden Bay", coordinate: .init(latitude: -40.816667, longitude: 172.9), boat: false),
    .init(name: "Jackson Bay Coast", area: "West Coast", coordinate: .init(latitude: -43.983333, longitude: 168.633333), boat: false),
    .init(name: "Oamaru Coast", area: "Otago", coordinate: .init(latitude: -45.1, longitude: 170.983333), boat: false),
    .init(name: "Timaru Harbour", area: "Canterbury", coordinate: .init(latitude: -44.383333, longitude: 171.25), boat: false),
    .init(name: "Manu Bay Coast", area: "Waikato", coordinate: .init(latitude: -37.816667, longitude: 174.816667), boat: false),
    .init(name: "Ōmokoroa Foreshore", area: "Bay of Plenty", coordinate: .init(latitude: -37.666667, longitude: 176.05), boat: false),
    .init(name: "Sumner Head Coast", area: "Canterbury", coordinate: .init(latitude: -43.566667, longitude: 172.766667), boat: false),
    .init(name: "Riverton / Aparima Foreshore", area: "Southland", coordinate: .init(latitude: -46.366667, longitude: 168.016667), boat: false),
    .init(name: "Opua Waters", area: "Bay of Islands", coordinate: .init(latitude: -35.316667, longitude: 174.116667), boat: true),
    .init(name: "Whangaroa Waters", area: "Northland", coordinate: .init(latitude: -35.05, longitude: 173.75), boat: true),
    .init(name: "Havelock Waters", area: "Marlborough Sounds", coordinate: .init(latitude: -41.283333, longitude: 173.766667), boat: true),
    .init(name: "Golden Bay / Tarakohe", area: "Golden Bay", coordinate: .init(latitude: -40.816667, longitude: 172.9), boat: true),
    .init(name: "Port Taranaki Waters", area: "Taranaki", coordinate: .init(latitude: -39.05, longitude: 174.033333), boat: true),
    .init(name: "Castlepoint Waters", area: "Wairarapa", coordinate: .init(latitude: -40.916667, longitude: 176.216667), boat: true),
    .init(name: "Westport Waters", area: "West Coast", coordinate: .init(latitude: -41.75, longitude: 171.6), boat: true),
    .init(name: "Kaikōura Waters", area: "Canterbury", coordinate: .init(latitude: -42.416667, longitude: 173.7), boat: true),
    .init(name: "Riverton / Aparima Waters", area: "Southland", coordinate: .init(latitude: -46.366667, longitude: 168.016667), boat: true)
]

struct FishingScoringService: Sendable {
    static let timeZone = TimeZone(identifier: "Pacific/Auckland")!
    private static let maximumConcurrentSpots = 5

    func rank(origin: GeoPoint, radiusKm: Double, selectedStation: TideStation? = nil, days: [Date], boat: Bool,
              preferredHours: PreferredFishingHours?, priority: WindowPriority = .weather) async throws -> [ScoredFishingWindow] {
        if selectedStation == nil {
            guard radiusKm.isFinite, radiusKm > 0 else { throw ScoringError.invalidRadius }
        }
        var configuredCalendar = Calendar(identifier: .gregorian)
        configuredCalendar.timeZone = Self.timeZone
        let calendar = configuredCalendar
        let now = Date()
        let today = calendar.startOfDay(for: now)
        let lastDay = calendar.date(byAdding: .day, value: 15, to: today)!
        let selectedDays = Set(days.map { calendar.startOfDay(for: $0) }.filter { $0 >= today && $0 <= lastDay })
        guard !selectedDays.isEmpty else { throw ScoringError.datesOutsideForecast }
        let mayCrossMidnight = preferredHours.map { $0.startMinute >= $0.endMinute } ?? true
        let forecastDays = min(16, 1 + selectedDays.compactMap { calendar.dateComponents([.day], from: today, to: $0).day }.max()! + (mayCrossMidnight ? 1 : 0))
        let candidates: [Candidate]
        if let station = selectedStation {
            let spot = FishingSpot(name: station.name, area: station.region,
                                   coordinate: .init(latitude: station.latitude, longitude: station.longitude), boat: boat)
            candidates = [.init(spot: spot, distanceKm: 0, isTideStation: true, tideStation: station)]
        } else {
            candidates = fishingSpots.compactMap { spot in
                guard spot.boat == boat else { return nil }
                let distance = Self.distanceKm(from: origin, to: spot.coordinate)
                guard distance.isFinite, distance <= radiusKm else { return nil }
                return .init(spot: spot, distanceKm: distance, isTideStation: false, tideStation: Self.matchingStation(for: spot))
            }
        }
        guard !candidates.isEmpty else { return [] }
        var windows: [ScoredFishingWindow] = []
        var weatherSucceeded = false
        var usableWeatherFound = false
        await withTaskGroup(of: SpotOutcome.self) { group in
            var next = 0
            for _ in 0..<min(Self.maximumConcurrentSpots, candidates.count) {
                let candidate = candidates[next]; next += 1
                group.addTask { await Self.scoreSpot(candidate, days: selectedDays, forecastDays: forecastDays,
                                                     now: now, calendar: calendar, preferredHours: preferredHours, priority: priority) }
            }
            while let outcome = await group.next() {
                if Task.isCancelled { group.cancelAll(); break }
                switch outcome {
                case .forecastAvailable(let found, let hasUsableWeather):
                    weatherSucceeded = true
                    usableWeatherFound = usableWeatherFound || hasUsableWeather
                    windows.append(contentsOf: found)
                case .forecastFailed: break
                }
                if next < candidates.count {
                    let candidate = candidates[next]; next += 1
                    group.addTask { await Self.scoreSpot(candidate, days: selectedDays, forecastDays: forecastDays,
                                                         now: now, calendar: calendar, preferredHours: preferredHours, priority: priority) }
                }
            }
        }
        try Task.checkCancellation()
        guard weatherSucceeded else { throw ScoringError.forecastsUnavailable }
        guard usableWeatherFound else { throw ScoringError.noUsableForecast }
        if selectedStation != nil { return windows.sorted { $0.start < $1.start } }
        return windows.sorted {
            if Self.isBetter($0, than: $1) { return true }
            if Self.isBetter($1, than: $0) { return false }
            return $0.spotName.localizedStandardCompare($1.spotName) == .orderedAscending
        }
    }

    // No nearest-station fallback: coastal proximity does not establish tidal connectivity.
    static func matchingStation(for spot: FishingSpot) -> TideStation? {
        let name = spot.name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_NZ"))
        return tideStations.first {
            $0.name.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_NZ")) == name
            || (abs($0.latitude - spot.coordinate.latitude) < 0.00001 && abs($0.longitude - spot.coordinate.longitude) < 0.00001)
        }
    }

    private static func scoreSpot(_ candidate: Candidate, days: Set<Date>, forecastDays: Int, now: Date,
                                  calendar: Calendar, preferredHours: PreferredFishingHours?, priority: WindowPriority) async -> SpotOutcome {
        let marineTask = Task { try? await fetchMarine(at: candidate.spot.coordinate, forecastDays: forecastDays) }
        let tideTask = Task { await loadTides(station: candidate.tideStation, days: days, calendar: calendar) }
        defer { marineTask.cancel(); tideTask.cancel() }
        do {
            let weather = try await fetchWeather(at: candidate.spot.coordinate, boat: candidate.spot.boat, forecastDays: forecastDays)
            let marine = await marineTask.value
            let tides = await tideTask.value
            let result = bestWindows(for: candidate, weather: weather, marine: marine, tides: tides, days: days,
                                     now: now, calendar: calendar, preferredHours: preferredHours, priority: priority)
            return .forecastAvailable(result.windows, result.hasUsableWeather)
        } catch { return .forecastFailed }
    }

    private static func loadTides(station: TideStation?, days: Set<Date>, calendar: Calendar) async -> [LINZTidePrediction] {
        guard let station, let start = days.min(), let lastDay = days.max(),
              let end = calendar.date(byAdding: .day, value: 1, to: lastDay) else { return [] }
        return (try? await LINZTideStore.shared.predictions(stationName: station.csvName, start: start, end: end)) ?? []
    }

    static func bestWindows(for candidate: Candidate, weather: WeatherPayload, marine: MarinePayload?, tides: [LINZTidePrediction],
                            days: Set<Date>, now: Date, calendar: Calendar, preferredHours: PreferredFishingHours?,
                            priority: WindowPriority) -> (windows: [ScoredFishingWindow], hasUsableWeather: Bool) {
        guard weather.isValid else { return ([], false) }
        let solar = solarByDay(weather.daily, calendar: calendar)
        let marineHours = marineHoursByTime(marine?.isValid == true ? marine?.hourly : nil)
        let data = weather.hourly
        let hours = data.time.indices.map { index in
            WeatherHour(time: Date(timeIntervalSince1970: TimeInterval(data.time[index])),
                        wind: nonnegative(data.windSpeed10m[index]), gust: nonnegative(data.windGusts10m[index]),
                        precipitation: nonnegative(data.precipitation[index]),
                        rainProbability: probability(data.precipitationProbability[index]),
                        code: data.weatherCode[index].flatMap { (0...99).contains($0) ? $0 : nil },
                        windDirection: data.windDirection10m[index].flatMap { $0.isFinite && (0...360).contains($0) ? $0 : nil })
        }
        let hasUsableWeather = hours.contains { days.contains(calendar.startOfDay(for: $0.time)) && $0.wind != nil }
        var byDay: [Date: [ScoredFishingWindow]] = [:]
        for index in hours.indices where index + 2 < hours.count {
            let samples = Array(hours[index...index + 2])
            let start = samples[0].time
            let end = samples[2].time
            let day = calendar.startOfDay(for: start)
            guard start >= now, days.contains(day),
                  zip(samples, samples.dropFirst()).allSatisfy({ $1.time.timeIntervalSince($0.time) == 3_600 }) else { continue }
            if let preferredHours, !preferredHours.contains(start: start, end: end, calendar: calendar) { continue }
            guard let result = evaluate(samples: samples, spot: candidate.spot, distanceKm: candidate.distanceKm,
                                        isTideStation: candidate.isTideStation, solar: solar, marineHours: marineHours,
                                        tides: tides, tideStation: candidate.tideStation, now: now, calendar: calendar,
                                        priority: priority, sourceNote: sourceNote(weather: weather, marine: marine,
                                                                                station: tides.isEmpty ? nil : candidate.tideStation)) else { continue }
            byDay[day, default: []].append(result)
        }
        let best = byDay.values.compactMap { pool -> ScoredFishingWindow? in
            let eligible = priority == .lateIncoming ? pool.filter { $0.tidePreferenceFit >= 0.8 } : pool
            guard var selected = eligible.sorted(by: { isBetter($0, than: $1) }).first else { return nil }
            let alternatives = priority == .weather ? pool.filter { $0.tidePreferenceFit >= 0.8 } : pool
            if let other = alternatives.sorted(by: { isBetter($0, than: $1) }).first, other.start != selected.start {
                let label = priority == .weather ? "Late-incoming alternative" : (candidate.spot.boat ? "Alternative for wave comfort" : "Weather-balance alternative")
                let details = candidate.spot.boat ? [other.conditions[4], other.conditions[0]] : Array(other.conditions.prefix(2))
                selected.alternative = "\(label): \(clock(other.start))–\(clock(other.end)). \(details.joined(separator: " "))"
            }
            return selected
        }
        if candidate.isTideStation { return (best, hasUsableWeather) }
        return (best.sorted(by: { isBetter($0, than: $1) }).first.map { [$0] } ?? [], hasUsableWeather)
    }

    static func isBetter(_ candidate: ScoredFishingWindow, than current: ScoredFishingWindow) -> Bool {
        if candidate.dataComplete != current.dataComplete { return candidate.dataComplete }
        if candidate.daylightFraction != current.daylightFraction { return candidate.daylightFraction > current.daylightFraction }
        if candidate.rankingValue != current.rankingValue { return candidate.rankingValue > current.rankingValue }
        return candidate.start < current.start
    }

    static func evaluate(samples: [WeatherHour], spot: FishingSpot, distanceKm: Double, isTideStation: Bool,
                         solar: [Date: SolarDay], marineHours: [Int: MarineHour], tides: [LINZTidePrediction],
                         tideStation: TideStation?, now: Date, calendar: Calendar, priority: WindowPriority,
                         sourceNote: String) -> ScoredFishingWindow? {
        guard samples.count == 3,
              zip(samples, samples.dropFirst()).allSatisfy({ $1.time.timeIntervalSince($0.time) == 3_600 }) else { return nil }
        let start = samples[0].time, end = samples[2].time
        let winds = samples.compactMap(\.wind)
        let codes = samples.compactMap(\.code)
        // API labels are the END of each preceding-hour gust/rain interval.
        let intervals = Array(samples.dropFirst())
        let gusts = intervals.compactMap(\.gust)
        let rainfall = intervals.compactMap(\.precipitation)
        let chances = intervals.compactMap(\.rainProbability)
        guard winds.count == 3, codes.count == 3, gusts.count == 2, rainfall.count == 2, chances.count == 2,
              let maximumWind = winds.max(), let maximumGust = gusts.max(), let rainChance = chances.max(),
              (winds + gusts + rainfall).allSatisfy({ $0.isFinite && $0 >= 0 }),
              chances.allSatisfy({ $0.isFinite && (0...100).contains($0) }),
              !codes.contains(where: { (95...99).contains($0) || !(0...99).contains($0) }) else { return nil }
        guard maximumWind < (spot.boat ? 40 : 55), maximumGust < (spot.boat ? 55 : 70) else { return nil }
        let marineSamples = samples.map { marineHours[Int($0.time.timeIntervalSince1970)] }
        let waves = marineSamples.compactMap { nonnegative($0?.wave) }
        let periods = marineSamples.compactMap { nonnegative($0?.period).flatMap { $0 > 0 ? $0 : nil } }
        // A missing hour never cancels an adverse value that IS known.
        let worstWave = waves.max()
        if let worstWave, worstWave >= (spot.boat ? 2 : 3) { return nil }
        let waveComplete = waves.count == 3 && periods.count == 3
        let tideFacts = tideContext(start: start, end: end, tides: tides, station: tideStation)
        guard let daylight = daylightFraction(start: start, end: end, solar: solar, calendar: calendar) else { return nil }
        let meanRain = rainfall.reduce(0, +) / 2
        let rainTotal = rainfall.reduce(0, +)
        let wind = 0.65 * lowerIsBetter(maximumWind, best: spot.boat ? 10 : 12, worst: spot.boat ? 40 : 55)
            + 0.35 * lowerIsBetter(maximumGust, best: spot.boat ? 18 : 20, worst: spot.boat ? 55 : 70)
        let rain = 0.5 * (1 - rainChance / 100) + 0.5 * lowerIsBetter(meanRain, best: 0, worst: 2.5)
        // Subjective boat comfort: waves dominate, and missing marine data earns no wave credit.
        let waveComfort = waveComplete ? (marineSamples.map(boatWaveComfort).min() ?? 0) : 0
        let comfort = spot.boat ? 0.6 * waveComfort + 0.3 * wind + 0.1 * rain : 0.6 * wind + 0.4 * rain
        let meanWind = (winds[0] + 2 * winds[1] + winds[2]) / 4
        let directions = samples.compactMap(\.windDirection)
        let directionText = directions.count == 3
            ? " Wind from " + Array(Set(directions.map { ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int(($0 / 45).rounded()) % 8] })).sorted().joined(separator: " / ") + "." : ""
        let windText = String(format: "Wind averages %.0f km/h, up to %.0f km/h; gusts up to %.0f km/h.", meanWind, maximumWind, maximumGust) + directionText
        let rainText = String(format: "%.1f mm rain forecast across these two hours; highest hourly rain chance %.0f%%.", rainTotal, rainChance)
        let lightText = daylight == 1 ? "The whole fishing session is in daylight." : String(format: "%.0f%% of the fishing session is in daylight.", daylight * 100)
        let comfortReason = spot.boat ? "wave comfort as the main factor, alongside wind and rain" : "the balance of wind and rain"
        let why = priority == .lateIncoming
            ? "Selected to fit your late-incoming preference, then favour daylight and \(comfortReason)."
            : "Selected for daylight and \(comfortReason) among the available two-hour windows."
        var conditions = [windText, rainText, tideFacts.description, lightText]
        let waveText: String
        if let worstWave {
            let qualifier = waveComplete ? "" : "Available samples: "
            let periodText = spot.boat && !periods.isEmpty
                ? String(format: "; mean wave periods %.1f–%.1f s", periods.min()!, periods.max()!)
                : periods.max().map { String(format: "; mean wave period up to %.0f s", $0) } ?? ""
            let exposure = spot.boat ? "Actual conditions depend on the boat, route and local sea state." : "This is not a wave-height forecast at a wharf or on rocks."
            waveText = String(format: "\(qualifier)offshore significant wave height up to %.1f m\(periodText). \(exposure)", worstWave)
        } else { waveText = "Offshore wave data is unavailable for this session." }
        conditions.append(waveText)
        var warnings: [String] = []
        if !waveComplete { warnings.append("Wave data is incomplete; local wave conditions need checking.") }
        if !tideFacts.complete { warnings.append("Verified local tide coverage is unavailable for this session.") }
        if daylight < 1 { warnings.append("Part or all of this session is after dark; check access, lighting and navigation.") }
        if maximumGust >= (spot.boat ? 40 : 55) { warnings.append("Strong gusts are forecast.") }
        if let worstWave, worstWave >= (spot.boat ? 1.2 : 1.5) { warnings.append("Elevated offshore waves: assess the exposure of your actual fishing spot.") }
        if spot.boat, marineSamples.contains(where: { sample in
            guard let height = nonnegative(sample?.wave), let period = nonnegative(sample?.period) else { return false }
            return height >= 0.5 && period > 0 && period <= 5
        }) { warnings.append("Short-period waves may make the boat ride and fishing uncomfortable.") }
        if let worstWave, let period = periods.max(), worstWave >= (spot.boat ? 1 : 0.8), period >= (spot.boat ? 10 : 12) {
            warnings.append("Long-period waves can increase surf and surge at exposed locations.")
        }
        if rainChance >= 60 || meanRain >= 1.5 { warnings.append("Rain could affect this session.") }
        if codes.contains(45) || codes.contains(48) { warnings.append("Fog may reduce visibility.") }
        if start.timeIntervalSince(now) > 7 * 86_400 { warnings.append("Long-range forecast: recheck closer to the day.") }
        if isTideStation { warnings.append("The tide station is a reference location, not a verified fishing access point.") }
        warnings.append("Local access, shelter and official marine warnings have not been assessed; check them before deciding to go.")
        let complete = waveComplete && tideFacts.complete
        let qualification = complete ? "" : "Some essential local data is missing; treat this as a time to investigate. "
        return .init(id: "\(spot.name)|\(spot.boat)|\(Int(start.timeIntervalSince1970))", spotName: spot.name,
                     area: spot.area, boat: spot.boat, score: Int((comfort * 100).rounded()), start: start, end: end,
                     distanceKm: distanceKm, reasons: ["\(qualification)\(why)"], warnings: warnings,
                     summary: "\(qualification)\(why) \(spot.boat ? waveText + " " : "")\(windText) \(rainText) \(tideFacts.description)", conditions: conditions,
                     sourceNote: sourceNote, rankingValue: comfort, dataComplete: complete,
                     daylightFraction: daylight, tidePreferenceFit: tideFacts.fit)
    }

    static func tideContext(start: Date, end: Date, tides: [LINZTidePrediction], station: TideStation?) -> (description: String, fit: Double, complete: Bool) {
        guard let station, let before = tides.lastIndex(where: { $0.time <= start }),
              let after = tides.firstIndex(where: { $0.time >= end }), before < after else {
            return ("No verified local tide prediction is available for these hours.", 0, false)
        }
        let bracket = Array(tides[before...after])
        guard zip(bracket, bracket.dropFirst()).allSatisfy({
            return $1.time > $0.time && $0.height != $1.height
        }) else { return ("Local tide coverage does not bracket this complete session.", 0, false) }
        let events = tides.indices.compactMap { index -> (LINZTidePrediction, Bool)? in
            let value = tides[index]
            let neighbours = [index - 1, index + 1].filter { tides.indices.contains($0) }.map { tides[$0] }
            guard !neighbours.isEmpty else { return nil }
            if neighbours.allSatisfy({ value.height > $0.height }) { return (value, true) }
            if neighbours.allSatisfy({ value.height < $0.height }) { return (value, false) }
            return nil
        }
        let localEvents = events.filter { $0.0.time >= bracket[0].time && $0.0.time <= bracket.last!.time }
        guard localEvents.count == bracket.count else { return ("Local tide events could not be validated.", 0, false) }
        func eventTime(_ time: Date) -> String {
            let pattern = LINZTideStore.calendar.isDate(time, inSameDayAs: start) ? "h:mm a" : "EEE d MMM, h:mm a"
            return format(time, pattern: pattern)
        }
        let turns = localEvents.filter { $0.0.time >= start && $0.0.time <= end }
        var eventText: String
        if !turns.isEmpty {
            eventText = turns.map { event, high in
                let after = event.time < end ? (high ? ", then falling water" : ", then rising water") : " at the end of the session"
                return "\(high ? "High" : "Low") tide at \(eventTime(event.time))\(after)"
            }.joined(separator: "; ")
            if !turns.contains(where: { $0.1 }), let nextHigh = events.first(where: { $0.1 && $0.0.time > start }) {
                eventText += "; next high at \(eventTime(nextHigh.0.time))"
            }
        } else {
            let first = localEvents[0], next = localEvents[1]
            eventText = "\(first.1 ? "Falling" : "Rising") water between \(first.1 ? "high" : "low") at \(eventTime(first.0.time)) and \(next.1 ? "high" : "low") at \(eventTime(next.0.time))"
        }
        let overlap = events.filter { $0.1 }.map { high in
            max(0, min(end, high.0.time.addingTimeInterval(30 * 60)).timeIntervalSince(max(start, high.0.time.addingTimeInterval(-150 * 60))))
        }.reduce(0, +)
        return ("\(eventText). LINZ \(station.name).",
                clamp(overlap / end.timeIntervalSince(start)), true)
    }

    static func daylightFraction(start: Date, end: Date, solar: [Date: SolarDay], calendar: Calendar) -> Double? {
        var day = calendar.startOfDay(for: start)
        var daylight: TimeInterval = 0
        while day < end {
            guard let light = solar[day], light.sunset > light.sunrise,
                  let nextDay = calendar.date(byAdding: .day, value: 1, to: day) else { return nil }
            daylight += max(0, min(end, light.sunset).timeIntervalSince(max(start, light.sunrise)))
            day = nextDay
        }
        return clamp(daylight / end.timeIntervalSince(start))
    }

    private static func sourceNote(weather: WeatherPayload, marine: MarinePayload?, station: TideStation?) -> String {
        let weatherGrid = String(format: "%.4f, %.4f", weather.latitude, weather.longitude)
        var text = "Open-Meteo best-match weather grid \(weatherGrid); retrieved \(stamp(weather.retrievedAt)) NZ time."
        if let marine {
            text += String(format: " Marine grid %.4f, %.4f; retrieved %@.", marine.latitude, marine.longitude, stamp(marine.retrievedAt))
        }
        text += station.map { " Tide reference: LINZ \($0.name), published local times (NZST/NZDT)." } ?? " No verified LINZ station match."
        return text + " Retrieval time is not the forecast model's issue time."
    }

    private static func stamp(_ time: Date) -> String { format(time, pattern: "d MMM, h:mm a") }
    private static func clock(_ time: Date) -> String { format(time, pattern: "h:mm a") }
    private static func format(_ time: Date, pattern: String) -> String {
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_NZ")
        formatter.timeZone = timeZone; formatter.dateFormat = pattern
        return formatter.string(from: time)
    }
    private static func lowerIsBetter(_ value: Double, best: Double, worst: Double) -> Double { clamp((worst - value) / (worst - best)) }
    /// Default comfort preference, not a vessel motion model or a safe wave-height threshold.
    private static func boatWaveComfort(_ sample: MarineHour?) -> Double {
        guard let height = nonnegative(sample?.wave), let period = nonnegative(sample?.period), period > 0 else { return 0 }
        let heightComfort = lowerIsBetter(height, best: 0.3, worst: 2)
        let shortPeriodPenalty = 0.25 * lowerIsBetter(period, best: 3, worst: 8) * clamp(height / 0.75)
        return clamp(heightComfort - shortPeriodPenalty)
    }
    private static func clamp(_ value: Double) -> Double { min(1, max(0, value)) }
    private static func nonnegative(_ value: Double?) -> Double? { value.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil } }
    private static func probability(_ value: Double?) -> Double? { nonnegative(value).flatMap { $0 <= 100 ? $0 : nil } }
    private static func distanceKm(from a: GeoPoint, to b: GeoPoint) -> Double {
        let radians = Double.pi / 180
        let haversine = pow(sin((b.latitude - a.latitude) * radians / 2), 2)
            + cos(a.latitude * radians) * cos(b.latitude * radians) * pow(sin((b.longitude - a.longitude) * radians / 2), 2)
        return 6_371 * 2 * asin(min(1, sqrt(max(0, haversine))))
    }
    private static func solarByDay(_ daily: WeatherPayload.Daily, calendar: Calendar) -> [Date: SolarDay] {
        var result: [Date: SolarDay] = [:]
        for index in daily.time.indices {
            guard let rise = daily.sunrise[index], let set = daily.sunset[index], rise < set else { continue }
            let day = calendar.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(daily.time[index])))
            let sunrise = Date(timeIntervalSince1970: TimeInterval(rise)), sunset = Date(timeIntervalSince1970: TimeInterval(set))
            guard calendar.startOfDay(for: sunrise) == day, calendar.startOfDay(for: sunset) == day else { continue }
            result[day] = .init(sunrise: sunrise, sunset: sunset)
        }
        return result
    }
    private static func marineHoursByTime(_ hourly: MarinePayload.Hourly?) -> [Int: MarineHour] {
        guard let hourly else { return [:] }
        var result: [Int: MarineHour] = [:]
        for index in hourly.time.indices {
            result[hourly.time[index]] = .init(wave: nonnegative(hourly.waveHeight[index]), period: nonnegative(hourly.wavePeriod[index]).flatMap { $0 > 0 ? $0 : nil })
        }
        return result
    }
    private static func fetchWeather(at coordinate: GeoPoint, boat: Bool, forecastDays: Int) async throws -> WeatherPayload {
        let url = apiURL(host: "api.open-meteo.com", path: "/v1/forecast", coordinate: coordinate, forecastDays: forecastDays, items: [
            .init(name: "hourly", value: "wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,precipitation_probability,weather_code"),
            .init(name: "daily", value: "sunrise,sunset"), .init(name: "wind_speed_unit", value: "kmh"),
            .init(name: "precipitation_unit", value: "mm"), .init(name: "cell_selection", value: boat ? "sea" : "land")
        ])
        var payload = try await fetch(WeatherPayload.self, from: url)
        guard payload.isValid else { throw ScoringError.invalidForecast }
        payload.retrievedAt = Date()
        return payload
    }
    private static func fetchMarine(at coordinate: GeoPoint, forecastDays: Int) async throws -> MarinePayload {
        let url = apiURL(host: "marine-api.open-meteo.com", path: "/v1/marine", coordinate: coordinate, forecastDays: forecastDays, items: [
            .init(name: "hourly", value: "wave_height,wave_period"), .init(name: "cell_selection", value: "sea"),
            .init(name: "length_unit", value: "metric")
        ])
        var payload = try await fetch(MarinePayload.self, from: url)
        guard payload.isValid else { throw ScoringError.invalidForecast }
        payload.retrievedAt = Date()
        return payload
    }
    private static func apiURL(host: String, path: String, coordinate: GeoPoint, forecastDays: Int, items: [URLQueryItem]) -> URL {
        var components = URLComponents(); components.scheme = "https"; components.host = host; components.path = path
        components.queryItems = [
            .init(name: "latitude", value: String(coordinate.latitude)), .init(name: "longitude", value: String(coordinate.longitude)),
            .init(name: "timezone", value: "Pacific/Auckland"), .init(name: "forecast_days", value: String(forecastDays)),
            .init(name: "timeformat", value: "unixtime")
        ] + items
        return components.url!
    }
    private static func fetch<T: Decodable>(_ type: T.Type, from url: URL) async throws -> T {
        var request = URLRequest(url: url); request.timeoutInterval = 15
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse, 200...299 ~= response.statusCode else { throw URLError(.badServerResponse) }
        return try JSONDecoder().decode(type, from: data)
    }
}

struct Candidate: Sendable { let spot: FishingSpot; let distanceKm: Double; let isTideStation: Bool; let tideStation: TideStation? }
private enum SpotOutcome: Sendable { case forecastAvailable([ScoredFishingWindow], Bool), forecastFailed }
struct SolarDay: Sendable { let sunrise: Date; let sunset: Date }
struct WeatherHour: Sendable {
    let time: Date; let wind: Double?; let gust: Double?; let precipitation: Double?; let rainProbability: Double?; let code: Int?
    var windDirection: Double? = nil
}
struct MarineHour: Sendable { let wave: Double?; let period: Double? }

struct WeatherPayload: Decodable, Sendable {
    let hourly: Hourly
    let daily: Daily
    let latitude: Double
    let longitude: Double
    let hourlyUnits: [String: String]
    let dailyUnits: [String: String]
    var retrievedAt: Date = .distantPast
    enum CodingKeys: String, CodingKey { case hourly, daily, latitude, longitude; case hourlyUnits = "hourly_units", dailyUnits = "daily_units" }
    var isValid: Bool {
        latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude)
            && hourlyUnits["time"] == "unixtime" && hourlyUnits["wind_speed_10m"] == "km/h"
            && hourlyUnits["wind_gusts_10m"] == "km/h" && hourlyUnits["precipitation"] == "mm"
            && hourlyUnits["precipitation_probability"] == "%" && hourlyUnits["weather_code"] == "wmo code"
            && hourlyUnits["wind_direction_10m"] == "°"
            && dailyUnits["time"] == "unixtime" && dailyUnits["sunrise"] == "unixtime" && dailyUnits["sunset"] == "unixtime"
            && validTimes(hourly.time) && validTimes(daily.time)
            && [hourly.windSpeed10m.count, hourly.windGusts10m.count, hourly.precipitation.count,
                hourly.precipitationProbability.count, hourly.weatherCode.count, hourly.windDirection10m.count].allSatisfy { $0 == hourly.time.count }
            && daily.sunrise.count == daily.time.count && daily.sunset.count == daily.time.count
    }
    struct Hourly: Decodable, Sendable {
        let time: [Int]; let windSpeed10m: [Double?]; let windGusts10m: [Double?]
        let precipitation: [Double?]; let precipitationProbability: [Double?]; let weatherCode: [Int?]
        let windDirection10m: [Double?]
        enum CodingKeys: String, CodingKey {
            case time, precipitation
            case windSpeed10m = "wind_speed_10m", windGusts10m = "wind_gusts_10m"
            case precipitationProbability = "precipitation_probability", weatherCode = "weather_code"
            case windDirection10m = "wind_direction_10m"
        }
    }
    struct Daily: Decodable, Sendable { let time: [Int]; let sunrise: [Int?]; let sunset: [Int?] }
}
struct MarinePayload: Decodable, Sendable {
    let hourly: Hourly
    let latitude: Double
    let longitude: Double
    let hourlyUnits: [String: String]
    var retrievedAt: Date = .distantPast
    enum CodingKeys: String, CodingKey { case hourly, latitude, longitude; case hourlyUnits = "hourly_units" }
    var isValid: Bool {
        latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude)
            && hourlyUnits["time"] == "unixtime" && hourlyUnits["wave_height"] == "m" && hourlyUnits["wave_period"] == "s"
            && validTimes(hourly.time) && hourly.waveHeight.count == hourly.time.count && hourly.wavePeriod.count == hourly.time.count
    }
    struct Hourly: Decodable, Sendable {
        let time: [Int]; let waveHeight: [Double?]; let wavePeriod: [Double?]
        enum CodingKeys: String, CodingKey { case time; case waveHeight = "wave_height", wavePeriod = "wave_period" }
    }
}
private func validTimes(_ values: [Int]) -> Bool { !values.isEmpty && zip(values, values.dropFirst()).allSatisfy { $1 > $0 } }
private enum ScoringError: LocalizedError {
    case invalidRadius, datesOutsideForecast, forecastsUnavailable, noUsableForecast, invalidForecast
    var errorDescription: String? {
        switch self {
        case .invalidRadius: return "Choose a search distance greater than zero."
        case .datesOutsideForecast: return "Choose a day within the next 16 days."
        case .forecastsUnavailable: return "Weather forecasts are unavailable for nearby spots. Please try again later."
        case .noUsableForecast: return "No usable hourly weather forecast was available for the selected days."
        case .invalidForecast: return "Forecast units or timestamps could not be verified."
        }
    }
}
