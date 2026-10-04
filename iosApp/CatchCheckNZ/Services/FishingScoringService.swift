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
    var assessment: WindowAssessment? = nil
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
              preferredHours: PreferredFishingHours?, priority: WindowPriority = .weather,
              land: LandPreferences = LandPreferences()) async throws -> [ScoredFishingWindow] {
        if !boat && land.daylightOnly && (land.arrivalMinutes == nil || land.returnMinutes == nil) { throw ScoringError.missingAccessTime }
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
        let mayCrossMidnight = (preferredHours.map { $0.startMinute >= $0.endMinute } ?? true) || (!boat && (land.returnMinutes ?? 0) > 0)
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
                                                     now: now, calendar: calendar, preferredHours: preferredHours, priority: priority, land: land) }
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
                                                         now: now, calendar: calendar, preferredHours: preferredHours, priority: priority, land: land) }
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
                                  calendar: Calendar, preferredHours: PreferredFishingHours?, priority: WindowPriority, land: LandPreferences) async -> SpotOutcome {
        let marineTask = Task { try? await fetchMarine(at: candidate.spot.coordinate, forecastDays: forecastDays) }
        let tideTask = Task { await loadTides(station: candidate.tideStation, days: days, calendar: calendar) }
        defer { marineTask.cancel(); tideTask.cancel() }
        do {
            let weather = try await fetchWeather(at: candidate.spot.coordinate, boat: candidate.spot.boat, forecastDays: forecastDays)
            let marine = await marineTask.value
            let tides = await tideTask.value
            let result = bestWindows(for: candidate, weather: weather, marine: marine, tides: tides, days: days,
                                     now: now, calendar: calendar, preferredHours: preferredHours, priority: priority, land: land)
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
                            priority: WindowPriority, land: LandPreferences = LandPreferences()) -> (windows: [ScoredFishingWindow], hasUsableWeather: Bool) {
        guard weather.isValid else { return ([], false) }
        let solar = solarByDay(weather.daily, calendar: calendar)
        let marineHours = marineHoursByTime(marine?.isValid == true ? marine?.hourly : nil)
        let data = weather.hourly
        let hours = data.time.indices.map { index in
            WeatherHour(time: Date(timeIntervalSince1970: TimeInterval(data.time[index])),
                        wind: nonnegative(data.windSpeed10m[index]), gust: nonnegative(data.windGusts10m[index]),
                        precipitation: nonnegative(data.precipitation[index]),
                        rainProbability: probability(data.precipitationProbability?[index]),
                        code: data.weatherCode[index].flatMap { (0...99).contains($0) ? $0 : nil },
                        windDirection: data.windDirection10m[index].flatMap { $0.isFinite && (0...360).contains($0) ? $0 : nil },
                        feelsLike: data.apparentTemperature?[index].flatMap { $0.isFinite && (-100...80).contains($0) ? $0 : nil })
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
                                                                                station: tides.isEmpty ? nil : candidate.tideStation),
                                        land: land, allHours: hours, retrievedAt: weather.retrievedAt) else { continue }
            byDay[day, default: []].append(result)
        }
        let best = byDay.values.compactMap { pool -> ScoredFishingWindow? in
            let eligible = priority == .lateIncoming ? pool.filter { $0.tidePreferenceFit >= 0.8 } : pool
            guard var selected = eligible.sorted(by: { isBetter($0, than: $1) }).first else { return nil }
            let alternatives = priority == .weather ? pool.filter { $0.tidePreferenceFit >= 0.8 } : pool
            if let other = alternatives.sorted(by: { isBetter($0, than: $1) }).first, other.start != selected.start {
                let label = priority == .weather ? "Late incoming" : "Comfort alternative"
                selected.alternative = "\(label): \(clock(other.start))–\(clock(other.end)) · \(other.assessment?.mood.label ?? "Compare conditions")\(other.assessment?.matchesComfort == false ? " · outside comfort preference" : "")"
            } else if priority == .weather, let (label, other) = landTradeoff(selected: selected, pool: pool) {
                selected.alternative = "\(label): \(clock(other.start))–\(clock(other.end)) · \(other.assessment!.mood.emoji) \(other.assessment!.mood.label)"
            }
            return selected
        }
        if candidate.isTideStation { return (best, hasUsableWeather) }
        return (best.sorted(by: { isBetter($0, than: $1) }).first.map { [$0] } ?? [], hasUsableWeather)
    }

    static func isBetter(_ candidate: ScoredFishingWindow, than current: ScoredFishingWindow) -> Bool {
        if !candidate.boat, !current.boat, let a = candidate.assessment, let b = current.assessment {
            if a.comfortComplete != b.comfortComplete { return a.comfortComplete }
            if a.matchesComfort != b.matchesComfort { return a.matchesComfort }
            if candidate.daylightFraction != current.daylightFraction { return candidate.daylightFraction > current.daylightFraction }
            if a.band != b.band { return a.band < b.band }
            if a.demandingHours != b.demandingHours { return a.demandingHours < b.demandingHours }
            if a.uncomfortableHours != b.uncomfortableHours { return a.uncomfortableHours < b.uncomfortableHours }
            if a.priority == .casting {
                if a.maxGust != b.maxGust { return a.maxGust < b.maxGust }
                if a.maxWind != b.maxWind { return a.maxWind < b.maxWind }
            } else if a.priority == .dry {
                if a.rainTotal != b.rainTotal { return a.rainTotal < b.rainTotal }
                if a.maxGust != b.maxGust { return a.maxGust < b.maxGust }
            }
            return candidate.start < current.start
        }
        if candidate.dataComplete != current.dataComplete { return candidate.dataComplete }
        if candidate.daylightFraction != current.daylightFraction { return candidate.daylightFraction > current.daylightFraction }
        if candidate.rankingValue != current.rankingValue { return candidate.rankingValue > current.rankingValue }
        if let a = candidate.assessment, let b = current.assessment, a.band != b.band { return a.band < b.band }
        return candidate.start < current.start
    }

    static func landTradeoff(selected: ScoredFishingWindow, pool: [ScoredFishingWindow]) -> (String, ScoredFishingWindow)? {
        guard !selected.boat, let chosen = selected.assessment, chosen.priority == .balanced, chosen.matchesComfort else { return nil }
        let peers = pool.filter {
            guard !$0.boat, $0.start != selected.start, let value = $0.assessment else { return false }
            return value.matchesComfort && value.band <= chosen.band && $0.daylightFraction >= selected.daylightFraction
        }
        let calmer = peers.filter { $0.assessment!.maxGust < chosen.maxGust && $0.assessment!.rainTotal > chosen.rainTotal }.sorted {
            let a = $0.assessment!, b = $1.assessment!
            if a.maxGust != b.maxGust { return a.maxGust < b.maxGust }
            if a.rainTotal != b.rainTotal { return a.rainTotal < b.rainTotal }
            return $0.start < $1.start
        }.first
        if let calmer { return ("Calmer alternative", calmer) }
        let drier = peers.filter { $0.assessment!.rainTotal < chosen.rainTotal && $0.assessment!.maxGust > chosen.maxGust }.sorted {
            let a = $0.assessment!, b = $1.assessment!
            if a.rainTotal != b.rainTotal { return a.rainTotal < b.rainTotal }
            if a.maxGust != b.maxGust { return a.maxGust < b.maxGust }
            return $0.start < $1.start
        }.first
        return drier.map { ("Drier alternative", $0) }
    }

    static func evaluate(samples: [WeatherHour], spot: FishingSpot, distanceKm: Double, isTideStation: Bool,
                         solar: [Date: SolarDay], marineHours: [Int: MarineHour], tides: [LINZTidePrediction],
                         tideStation: TideStation?, now: Date, calendar: Calendar, priority: WindowPriority,
                         sourceNote: String, land: LandPreferences = LandPreferences(),
                         allHours: [WeatherHour]? = nil, retrievedAt: Date? = nil) -> ScoredFishingWindow? {
        guard samples.count == 3,
              zip(samples, samples.dropFirst()).allSatisfy({ $1.time.timeIntervalSince($0.time) == 3_600 }) else { return nil }
        if !spot.boat {
            return LandAssessment.evaluate(core: samples, hours: allHours ?? samples, spot: spot, distance: distanceKm,
                solar: solar, marine: marineHours, tides: tides, station: tideStation, now: now, calendar: calendar,
                sourceNote: sourceNote, priority: priority, preferences: land, retrievedAt: retrievedAt)
        }
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
        if marineSamples.contains(where: { ($0?.wave ?? 0) >= 1 && ($0?.period ?? 0) >= 10 }) {
            warnings.append("Long-period waves can increase surf and surge at exposed locations.")
        }
        if rainChance >= 60 || meanRain >= 1.5 { warnings.append("Rain could affect this session.") }
        if codes.contains(45) || codes.contains(48) { warnings.append("Fog may reduce visibility.") }
        if start.timeIntervalSince(now) > 7 * 86_400 { warnings.append("Long-range forecast: recheck closer to the day.") }
        if isTideStation { warnings.append("The tide station is a reference location, not a verified fishing access point.") }
        warnings.append("Local access, shelter and official marine warnings have not been assessed; check them before deciding to go.")
        let temperature = samples.compactMap { $0.feelsLike.flatMap { $0.isFinite && (-100...80).contains($0) ? $0 : nil } }
        let complete = waveComplete && tideFacts.complete && temperature.count == 3
        let thermalBand = temperature.map(LandAssessment.temperatureBand).max() ?? 0
        let comfortBand = max(thermalBand, comfort >= 0.85 ? 0 : comfort >= 0.7 ? 1 : comfort >= 0.5 ? 2 : 3)
        func range(_ values: [Double], decimals: Int) -> String {
            guard let lo = values.min(), let hi = values.max() else { return "—" }
            let a = String(format: "%.*f", decimals, lo), b = String(format: "%.*f", decimals, hi)
            return a == b ? a : a + "–" + b
        }
        var windDirections: [String] = []
        for d in directions where d.isFinite && (0...360).contains(d) {
            let label = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int((d/45).rounded()) % 8]
            if !windDirections.contains(label) { windDirections.append(label) }
        }
        let peakRain = rainfall.max()!
        func high(_ index: Int) -> Bool { [index-1, index+1].filter { tides.indices.contains($0) }.allSatisfy { tides[index].height > tides[$0].height } }
        let eventIndex = tides.indices.first { tides[$0].time >= start && tides[$0].time <= end }
            ?? tides.indices.first { tides[$0].time > start && high($0) }
        let tideValue = tideFacts.complete ? eventIndex.map { index in
            "\(tides[index].time > end ? "Next " : "")\(high(index) ? "high" : "low") \(clock(tides[index].time)) · \(String(format: "%.1f", tides[index].height)) m CD"
        } ?? "—" : "—"
        let choppy = marineSamples.contains { ($0?.wave ?? 0) >= 0.5 && ($0?.period ?? 99) <= 5 }
        let rows: [ConditionItem] = [
            .init(title: "Offshore waves", value: worstWave.map { "\(String(format: "%.1f", $0)) m · \(range(periods, decimals: 1)) s" } ?? "—",
                  mood: !waveComplete ? needsDataMood : .init(emoji: "🌊", label: choppy ? "Choppy" : (worstWave ?? 0) >= 1.2 ? "More motion" : "Lower waves")),
            .init(title: "Wind", value: "\(Int(maximumWind.rounded())) km/h · gust \(Int(maximumGust.rounded()))\(windDirections.isEmpty ? "" : " · " + windDirections.joined(separator: "/"))", mood: comfortMood(LandAssessment.windBand(maximumWind, maximumGust))),
            .init(title: "Rain", value: "\(String(format: "%.1f", rainTotal)) mm · peak \(String(format: "%.1f", peakRain)) mm/h · \(Int(rainChance.rounded()))% hourly", mood: .init(emoji: peakRain > 0.2 ? "🌧️" : rainChance >= 60 ? "🌦️" : "🌤️", label: peakRain > 0.8 ? "Wet" : rainTotal > 0 ? "Light rain" : rainChance >= 60 ? "Rain possible" : "Mostly dry")),
            .init(title: "Feels like", value: temperature.isEmpty ? "—" : "\(range(temperature, decimals: 0))°C", mood: temperature.count != 3 ? needsDataMood : temperature.min()! < 12 ? .init(emoji: "🥶", label: "Cold") : temperature.max()! > 26 ? .init(emoji: "🥵", label: "Hot") : .init(emoji: "😌", label: "Mild")),
            .init(title: "Tide", value: tideValue, mood: tideFacts.complete ? .init(emoji: "🕒", label: "Tide timing") : needsDataMood),
            .init(title: "Daylight", value: "\(Int((daylight*100).rounded()))% session", mood: .init(emoji: daylight == 1 ? "🌞" : "🌙", label: daylight == 1 ? "Daylight" : "After dark"))
        ]
        let age = start.timeIntervalSince(now)
        let confidence = age > 5*86400 ? WindowMood(emoji: "🔭", label: "Early outlook") : age > 2*86400 ? .init(emoji: "🗓️", label: "Planning forecast") : .init(emoji: "🤔", label: "Limited confidence")
        var details = warnings + ["Waves lead boat ranking. Feels-like temperature limits the outlook and breaks comfort ties. Route, vessel response, model timing, agreement and return conditions unchecked. CD = Chart Datum.",
            "Rain chance is the highest hourly likelihood, not the chance for the whole session. Offshore waves show significant height and mean period."]
        if temperature.count != 3 { details.append("Feels-like temperature coverage incomplete.") }
        if retrievedAt == nil || now.timeIntervalSince(retrievedAt!) > 3*3600 { details.append("Forecast freshness unverified or older than 3 hours.") }
        let assessment = WindowAssessment(mood: complete ? comfortMood(comfortBand) : needsDataMood,
            conditions: rows, confidence: confidence,
            details: details, band: comfortBand, comfortComplete: complete)
        let briefReason = priority == .lateIncoming ? "Fits late incoming · waves first" : "Waves first"
        return .init(id: "\(spot.name)|\(spot.boat)|\(Int(start.timeIntervalSince1970))", spotName: spot.name,
                     area: spot.area, boat: spot.boat, score: Int((comfort * 100).rounded()), start: start, end: end,
                     distanceKm: distanceKm, reasons: [briefReason], warnings: warnings,
                     summary: briefReason, conditions: conditions,
                     sourceNote: sourceNote, rankingValue: comfort, dataComplete: complete,
                     daylightFraction: daylight, tidePreferenceFit: tideFacts.fit, assessment: assessment)
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
            .init(name: "hourly", value: "wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,precipitation_probability,weather_code,apparent_temperature"),
            .init(name: "daily", value: "sunrise,sunset"), .init(name: "wind_speed_unit", value: "kmh"),
            .init(name: "precipitation_unit", value: "mm"), .init(name: "temperature_unit", value: "celsius"), .init(name: "cell_selection", value: boat ? "sea" : "land")
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
    var feelsLike: Double? = nil
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
            && (hourly.precipitationProbability == nil || hourlyUnits["precipitation_probability"] == "%") && hourlyUnits["weather_code"] == "wmo code"
            && (hourly.apparentTemperature == nil || hourlyUnits["apparent_temperature"] == "°C")
            && hourlyUnits["wind_direction_10m"] == "°"
            && dailyUnits["time"] == "unixtime" && dailyUnits["sunrise"] == "unixtime" && dailyUnits["sunset"] == "unixtime"
            && validTimes(hourly.time) && validTimes(daily.time)
            && [hourly.windSpeed10m.count, hourly.windGusts10m.count, hourly.precipitation.count,
                hourly.weatherCode.count, hourly.windDirection10m.count].allSatisfy { $0 == hourly.time.count }
            && [hourly.precipitationProbability, hourly.apparentTemperature].compactMap { $0 }.allSatisfy { $0.count == hourly.time.count }
            && daily.sunrise.count == daily.time.count && daily.sunset.count == daily.time.count
    }
    struct Hourly: Decodable, Sendable {
        let time: [Int]; let windSpeed10m: [Double?]; let windGusts10m: [Double?]
        let precipitation: [Double?]; let precipitationProbability: [Double?]?; let weatherCode: [Int?]
        let windDirection10m: [Double?]
        let apparentTemperature: [Double?]?
        enum CodingKeys: String, CodingKey {
            case time, precipitation
            case windSpeed10m = "wind_speed_10m", windGusts10m = "wind_gusts_10m"
            case precipitationProbability = "precipitation_probability", weatherCode = "weather_code"
            case windDirection10m = "wind_direction_10m"
            case apparentTemperature = "apparent_temperature"
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
/// Trial human-comfort bands; independent from local exposure and warning checks.
enum LandAssessment {
    static func band(_ value: Double, _ limits: [Double]) -> Int { limits.firstIndex { value <= $0 } ?? 3 }
    static func windBand(_ wind: Double, _ gust: Double) -> Int { max(band(wind, [12, 20, 30]), band(gust, [20, 30, 45])) }
    static func temperatureBand(_ value: Double) -> Int {
        if (12...26).contains(value) { return 0 }
        if (8...30).contains(value) { return 1 }
        if (5...33).contains(value) { return 2 }
        return 3
    }
    private static func valid(_ value: Double?, temperature: Bool = false) -> Double? {
        guard let value, value.isFinite, temperature ? (-100...80).contains(value) : value >= 0 else { return nil }
        return value
    }
    private static func at(_ hours: [WeatherHour], _ time: Date, temperature: Bool = false) -> Double? {
        func value(_ hour: WeatherHour) -> Double? { valid(temperature ? hour.feelsLike : hour.wind, temperature: temperature) }
        if let exact = hours.first(where: { $0.time == time }) { return value(exact) }
        guard let left = hours.last(where: { $0.time < time }), let right = hours.first(where: { $0.time > time }),
              right.time.timeIntervalSince(left.time) == 3600, let a = value(left), let b = value(right) else { return nil }
        return a + (b - a) * time.timeIntervalSince(left.time) / 3600
    }
    private static func waveAt(_ marine: [Int: MarineHour], _ time: Date) -> MarineHour? {
        let stamp = Int(time.timeIntervalSince1970)
        if let exact = marine[stamp] { return exact }
        let times = marine.keys.sorted()
        guard let left = times.last(where: { $0 < stamp }), let right = times.first(where: { $0 > stamp }), right-left == 3600 else { return nil }
        func interpolate(_ a: Double?, _ b: Double?, period: Bool = false) -> Double? {
            guard let a = valid(a), let b = valid(b), !period || (a > 0 && b > 0) else { return nil }
            return a + (b-a) * Double(stamp-left)/3600
        }
        return MarineHour(wave: interpolate(marine[left]?.wave, marine[right]?.wave), period: interpolate(marine[left]?.period, marine[right]?.period, period: true))
    }
    static func evaluate(core: [WeatherHour], hours: [WeatherHour], spot: FishingSpot, distance: Double,
                         solar: [Date: SolarDay], marine: [Int: MarineHour], tides: [LINZTidePrediction],
                         station: TideStation?, now: Date, calendar: Calendar, sourceNote: String,
                         priority: WindowPriority, preferences: LandPreferences, retrievedAt: Date?) -> ScoredFishingWindow? {
        let start = core[0].time, end = core[2].time
        let arrival = preferences.arrivalMinutes, returning = preferences.returnMinutes
        guard (0...3).contains(preferences.maxBand), [arrival, returning].compactMap({ $0 }).allSatisfy({ (0...180).contains($0) }) else { return nil }
        let visitKnown = arrival != nil && returning != nil
        if preferences.daylightOnly && !visitKnown { return nil }
        let visitStart = start.addingTimeInterval(-Double(arrival ?? 0)*60), visitEnd = end.addingTimeInterval(Double(returning ?? 0)*60)
        if arrival != nil && visitStart < now { return nil }
        let points = Set([visitStart, visitEnd] + hours.filter { $0.time > visitStart && $0.time < visitEnd }.map(\.time)).sorted()
        let winds = points.map { at(hours, $0) }, temperatures = points.map { at(hours, $0, temperature: true) }
        let intervals = hours.filter { $0.time > visitStart && $0.time.addingTimeInterval(-3600) < visitEnd }
        let gusts = intervals.map { valid($0.gust) }, rain = intervals.map { valid($0.precipitation) }
        let probabilities = intervals.compactMap { value -> Double? in
            guard let p = value.rainProbability, p.isFinite, (0...100).contains(p) else { return nil }; return p
        }
        let codes = hours.filter { $0.time >= visitStart && $0.time <= visitEnd }.map(\.code)
        guard let maxWind = winds.compactMap({ $0 }).max(), let maxGust = gusts.compactMap({ $0 }).max() else { return nil }
        let marineSamples = points.map { waveAt(marine, $0) }
        let completeMarine = marineSamples.allSatisfy { valid($0?.wave) != nil && (valid($0?.period) ?? 0) > 0 }
        let marineHours = marineSamples.compactMap { $0 } + marine.filter {
            let time = Date(timeIntervalSince1970: Double($0.key))
            return time >= visitStart && time <= visitEnd && !points.contains(time)
        }.map(\.value)
        let waves = marineHours.compactMap { valid($0.wave) }, periods = marineHours.compactMap { valid($0.period).flatMap { $0 > 0 ? $0 : nil } }
        // Preserve known adverse conditions before considering missing coverage.
        if maxWind >= 55 || maxGust >= 70 || waves.contains(where: { $0 >= 3 }) || codes.contains(where: { $0.map { (95...99).contains($0) } ?? false }) { return nil }
        guard core.allSatisfy({ valid($0.wind) != nil && $0.code.map { (0...99).contains($0) } == true }),
              core.dropFirst().allSatisfy({ valid($0.gust) != nil && valid($0.precipitation) != nil }) else { return nil }
        func overlap(_ hour: WeatherHour) -> Double { min(visitEnd, hour.time).timeIntervalSince(max(visitStart, hour.time.addingTimeInterval(-3600))) }
        let covered = intervals.reduce(0) { $0 + overlap($1) }
        let completeWeather = covered == visitEnd.timeIntervalSince(visitStart)
            && winds.allSatisfy { $0 != nil } && gusts.allSatisfy { $0 != nil } && rain.allSatisfy { $0 != nil }
            && codes.allSatisfy { $0.map { (0...99).contains($0) } ?? false }
        let completeTemperature = temperatures.allSatisfy { $0 != nil }, complete = completeWeather && completeTemperature
        let totalRain = intervals.reduce(0) { $0 + (valid($1.precipitation) ?? 0) * overlap($1)/3600 }
        guard let peakRain = rain.compactMap({ $0 }).max() else { return nil }
        let windGrade = windBand(maxWind, maxGust), rainGrade = band(peakRain, [0.2, 0.8, 1.5])
        let tempGrade = temperatures.compactMap { $0 }.map(temperatureBand).max() ?? 0
        let grade = max(windGrade, rainGrade, tempGrade)
        var demanding: Double = 0, uncomfortable: Double = 0
        for hour in intervals {
            let left = max(visitStart, hour.time.addingTimeInterval(-3600)), right = min(visitEnd, hour.time)
            guard let w = [at(hours, left), at(hours, right)].compactMap({ $0 }).max(), let g = valid(hour.gust), let r = valid(hour.precipitation) else { continue }
            let t = [at(hours, left, temperature: true), at(hours, right, temperature: true)].compactMap { $0 }.map(temperatureBand).max() ?? 0
            let b = max(windBand(w, g), band(r, [0.2, 0.8, 1.5]), t)
            if b >= 2 { demanding += overlap(hour)/3600 }; if b >= 1 { uncomfortable += overlap(hour)/3600 }
        }
        let daylight = FishingScoringService.daylightFraction(start: visitStart, end: visitEnd, solar: solar, calendar: calendar)
        if preferences.daylightOnly && daylight != 1 { return nil }
        let tideFacts = FishingScoringService.tideContext(start: start, end: end, tides: tides, station: station)
        let turns = tides.indices.filter { tides[$0].time >= start && tides[$0].time <= end }
        func high(_ index: Int) -> Bool {
            [index-1, index+1].filter { tides.indices.contains($0) }.allSatisfy { tides[index].height > tides[$0].height }
        }
        let eventIndex = turns.first ?? tides.indices.first { tides[$0].time > start && high($0) }
        let beforeIndex = tides.indices.last { tides[$0].time <= start }
        let rising = beforeIndex.map { !high($0) } ?? false
        let tideMood: WindowMood
        if !tideFacts.complete { tideMood = WindowMood(emoji: "❓", label: "Unverified") }
        else if priority == .lateIncoming && tideFacts.fit >= 0.8 { tideMood = WindowMood(emoji: "🎯", label: "Late incoming") }
        else if !turns.isEmpty { tideMood = WindowMood(emoji: "🔄", label: "Tide turns") }
        else { tideMood = WindowMood(emoji: rising ? "↗️" : "↘️", label: rising ? "Rising" : "Falling") }
        let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.timeZone = calendar.timeZone
        func time(_ at: Date) -> String { formatter.dateFormat = calendar.isDate(at, inSameDayAs: start) ? "h:mm a" : "EEE h:mm a"; return formatter.string(from: at) }
        func range(_ values: [Double], decimals: Int = 0) -> String {
            guard let lo = values.min(), let hi = values.max() else { return "—" }
            let a = String(format: "%.*f", decimals, lo), b = String(format: "%.*f", decimals, hi)
            return a == b ? a : a + "–" + b
        }
        let probability = probabilities.max(), temps = temperatures.compactMap { $0 }
        var directions: [String] = []
        for hour in hours where hour.time >= visitStart && hour.time <= visitEnd {
            if let d = hour.windDirection, d.isFinite, (0...360).contains(d) {
                let label = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int((d/45).rounded()) % 8]
                if !directions.contains(label) { directions.append(label) }
            }
        }
        let tideValue = tideFacts.complete ? eventIndex.map { index in
            "\(turns.isEmpty ? "Next " : "")\(high(index) ? "high" : "low") \(time(tides[index].time)) · \(String(format: "%.1f", tides[index].height)) m CD"
        } ?? "—" : "—"
        let waveFeeling: String = preferences.setting == .rocks ? "Check surge" : preferences.setting == .beach ? "Check surf" : "Check exposure"
        let rows: [ConditionItem] = [
            .init(title: "Wind", value: "\(Int(maxWind.rounded())) km/h · gust \(Int(maxGust.rounded()))\(directions.isEmpty ? "" : " · " + directions.joined(separator: "/"))", mood: winds.contains(where: { $0 == nil }) || gusts.contains(where: { $0 == nil }) ? needsDataMood : comfortMood(windGrade)),
            .init(title: "Rain", value: "\(String(format: "%.1f", totalRain)) mm · peak \(String(format: "%.1f", peakRain)) mm/h · \(probability.map { "\(Int($0.rounded()))% hourly" } ?? "chance —")",
                  mood: covered != visitEnd.timeIntervalSince(visitStart) || rain.contains(where: { $0 == nil }) ? needsDataMood : .init(emoji: peakRain > 0.2 ? "🌧️" : (probability ?? 0) >= 60 ? "🌦️" : "🌤️", label: peakRain > 0.8 ? "Wet" : peakRain > 0 ? "Light rain" : (probability ?? 0) >= 60 ? "Rain possible" : "Mostly dry")),
            .init(title: "Feels like", value: temps.isEmpty ? "—" : "\(range(temps))°C", mood: !completeTemperature ? needsDataMood : temps.min()! < 12 ? .init(emoji: "🥶", label: "Cold") : temps.max()! > 26 ? .init(emoji: "🥵", label: "Hot") : .init(emoji: "😌", label: "Mild")),
            .init(title: "Tide", value: tideValue, mood: tideMood),
            .init(title: "Offshore waves", value: waves.max().map { "\(String(format: "%.1f", $0)) m · \(range(periods, decimals: 1)) s" } ?? "—", mood: completeMarine ? .init(emoji: "🌊", label: waveFeeling) : needsDataMood),
            .init(title: "Daylight", value: daylight.map { "\(Int(($0*100).rounded()))% \(visitKnown ? "visit" : arrival != nil || returning != nil ? "known time" : "session")" } ?? "—", mood: daylight == nil ? needsDataMood : daylight! < 1 ? .init(emoji: "🌙", label: "After dark") : !visitKnown ? .init(emoji: "🔎", label: "Visit times needed") : .init(emoji: "🌞", label: "Daylight")),
            .init(title: "Shore plan", value: "\(preferences.setting.rawValue) · before \(arrival.map { "\($0) min" } ?? "—") · return \(returning.map { "\($0) min" } ?? "—")", mood: .init(emoji: "🔎", label: "Access unchecked"))
        ]
        let localReason: String
        switch preferences.setting {
        case .rocks: localReason = "Rock footing, surge and escape route unchecked."
        case .beach: localReason = "Surf, wading and beach access unchecked."
        case .wharf: localReason = "Wharf access and exposure unchecked."
        case .bank: localReason = "Bank footing and tide access unchecked."
        case .unknown: localReason = "Shore type and access unchecked."
        }
        var details = [localReason, "Official warnings unchecked. Single forecast; model timing and agreement unverified."]
        if !visitKnown { details.append("Access/setup or return time not set; only the session and known extra time are assessed.") }
        if !completeWeather { details.append("Visit weather coverage incomplete; displayed amounts use available samples.") }
        if !completeTemperature { details.append("Feels-like temperature coverage incomplete.") }
        if !tideFacts.complete { details.append("Local tide coverage unverified.") }
        if !completeMarine { details.append("Offshore wave coverage incomplete.") }
        if probabilities.count != intervals.count { details.append("Rain likelihood incomplete.") }
        if retrievedAt == nil || now.timeIntervalSince(retrievedAt!) > 3*3600 { details.append("Forecast freshness unverified or older than 3 hours.") }
        if waves.contains(where: { $0 >= 1.5 }) { details.append("Elevated offshore waves; local exposure unchecked.") }
        if marineHours.contains(where: { (valid($0.wave) ?? 0) >= 0.8 && (valid($0.period) ?? 0) >= 12 }) { details.append("Long-period waves; local surge unchecked.") }
        if codes.contains(where: { $0 == 45 || $0 == 48 }) { details.append("Fog forecast; visibility needs checking.") }
        if temps.contains(where: { $0 < 12 }) && peakRain > 0.2 { details.append("Cold and wet conditions.") }
        details.append("Rain chance is the highest hourly likelihood, not the chance for the whole visit. Offshore waves show significant height and mean period.")
        details.append("Trial comfort bands; not a catch forecast. CD = Chart Datum. Hourly weather timing is approximate.")
        let age = start.timeIntervalSince(now)
        let confidence = age > 5*86400 ? WindowMood(emoji: "🔭", label: "Early outlook") : age > 2*86400 ? .init(emoji: "🗓️", label: "Planning forecast") : .init(emoji: "🤔", label: "Limited confidence")
        let assessment = WindowAssessment(mood: complete ? comfortMood(grade) : needsDataMood, conditions: rows, confidence: confidence,
            details: details, band: grade, demandingHours: demanding, uncomfortableHours: uncomfortable,
            matchesComfort: complete && grade <= preferences.maxBand, comfortComplete: complete, maxWind: maxWind, maxGust: maxGust, rainTotal: totalRain, priority: preferences.priority)
        let reason = !complete ? "Comfort assessment incomplete" : grade > preferences.maxBand ? "Outside your comfort preference" : priority == .lateIncoming ? "Fits late incoming" : "Lower discomfort"
        return .init(id: "\(spot.name)|false|\(Int(start.timeIntervalSince1970))", spotName: spot.name, area: spot.area, boat: false,
                     score: 100-grade*25, start: start, end: end, distanceKm: distance, reasons: [reason], warnings: details,
                     summary: reason, conditions: rows.map { "\($0.title): \($0.value) · \($0.mood.emoji) \($0.mood.label)" },
                     sourceNote: sourceNote, rankingValue: Double(3-grade), dataComplete: complete,
                     daylightFraction: daylight ?? 0, tidePreferenceFit: tideFacts.fit, assessment: assessment)
    }
}
private func validTimes(_ values: [Int]) -> Bool { !values.isEmpty && zip(values, values.dropFirst()).allSatisfy { $1 > $0 } }
private enum ScoringError: LocalizedError {
    case invalidRadius, datesOutsideForecast, forecastsUnavailable, noUsableForecast, invalidForecast, missingAccessTime
    var errorDescription: String? {
        switch self {
        case .invalidRadius: return "Choose a search distance greater than zero."
        case .datesOutsideForecast: return "Choose a day within the next 16 days."
        case .forecastsUnavailable: return "Weather forecasts are unavailable for nearby spots. Please try again later."
        case .noUsableForecast: return "No usable hourly weather forecast was available for the selected days."
        case .invalidForecast: return "Forecast units or timestamps could not be verified."
        case .missingAccessTime: return "Set access/setup and return time for daylight only."
        }
    }
}
