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
    private static let timeZone = TimeZone(identifier: "Pacific/Auckland")!
    private static let maximumConcurrentSpots = 5

    func rank(origin: GeoPoint, radiusKm: Double, selectedStation: TideStation? = nil, days: [Date], boat: Bool, preferredHours: PreferredFishingHours?) async throws -> [ScoredFishingWindow] {
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
        // Fetch one extra day only when a selected day's window may end after midnight.
        let mayCrossMidnight = preferredHours.map { $0.startMinute >= $0.endMinute } ?? true
        let forecastDays = min(16, 1 + selectedDays.compactMap { calendar.dateComponents([.day], from: today, to: $0).day }.max()! + (mayCrossMidnight ? 1 : 0))

        let candidates: [Candidate]
        if let selectedStation {
            let stationSpot = FishingSpot(name: selectedStation.name, area: selectedStation.region,
                                          coordinate: GeoPoint(latitude: selectedStation.latitude, longitude: selectedStation.longitude), boat: boat)
            candidates = [Candidate(spot: stationSpot, distanceKm: 0, isTideStation: true)]
        } else {
            candidates = fishingSpots.compactMap { spot -> Candidate? in
                guard spot.boat == boat else { return nil }
                let distance = Self.distanceKm(from: origin, to: spot.coordinate)
                guard distance.isFinite, distance <= radiusKm else { return nil }
                return Candidate(spot: spot, distanceKm: distance, isTideStation: false)
            }
        }
        guard !candidates.isEmpty else { return [] }

        var windows: [ScoredFishingWindow] = []
        var weatherSucceeded = false
        var usableWeatherFound = false
        await withTaskGroup(of: SpotOutcome.self) { group in
            var next = 0
            for _ in 0..<min(Self.maximumConcurrentSpots, candidates.count) {
                let candidate = candidates[next]
                next += 1
                group.addTask { await Self.scoreSpot(candidate, days: selectedDays, forecastDays: forecastDays, now: now, calendar: calendar, preferredHours: preferredHours) }
            }
            while let outcome = await group.next() {
                if Task.isCancelled {
                    group.cancelAll()
                    break
                }
                switch outcome {
                case .forecastAvailable(let window, let hasUsableWeather):
                    weatherSucceeded = true
                    usableWeatherFound = usableWeatherFound || hasUsableWeather
                    if let window { windows.append(window) }
                case .forecastFailed:
                    break
                }
                if next < candidates.count {
                    let candidate = candidates[next]
                    next += 1
                    group.addTask { await Self.scoreSpot(candidate, days: selectedDays, forecastDays: forecastDays, now: now, calendar: calendar, preferredHours: preferredHours) }
                }
            }
        }
        try Task.checkCancellation()
        guard weatherSucceeded else { throw ScoringError.forecastsUnavailable }
        guard usableWeatherFound else { throw ScoringError.noUsableForecast }
        return windows.sorted {
            if $0.score != $1.score { return $0.score > $1.score }
            if $0.distanceKm != $1.distanceKm { return $0.distanceKm < $1.distanceKm }
            return $0.spotName.localizedStandardCompare($1.spotName) == .orderedAscending
        }
    }

    private static func scoreSpot(_ candidate: Candidate, days: Set<Date>, forecastDays: Int, now: Date, calendar: Calendar, preferredHours: PreferredFishingHours?) async -> SpotOutcome {
        let marineTask = Task { try? await fetchMarine(at: candidate.spot.coordinate, forecastDays: forecastDays) }
        defer { marineTask.cancel() }
        do {
            let weather = try await fetchWeather(at: candidate.spot.coordinate, boat: candidate.spot.boat, forecastDays: forecastDays)
            let marine = await marineTask.value
            let result = bestWindow(for: candidate, weather: weather, marine: marine, days: days, now: now, calendar: calendar, preferredHours: preferredHours)
            return .forecastAvailable(result.window, result.hasUsableWeather)
        } catch {
            return .forecastFailed
        }
    }

    private static func bestWindow(for candidate: Candidate, weather: WeatherPayload, marine: MarinePayload?, days: Set<Date>, now: Date, calendar: Calendar, preferredHours: PreferredFishingHours?) -> (window: ScoredFishingWindow?, hasUsableWeather: Bool) {
        let solar = solarByDay(weather.daily, calendar: calendar)
        let marineHours = marineHoursByTime(marine?.hourly)
        var hours: [WeatherHour] = []
        let data = weather.hourly
        for index in data.time.indices {
            guard let speed = data.windSpeed10m[safe: index] ?? nil,
                  let gust = data.windGusts10m[safe: index] ?? nil,
                  let rain = data.precipitation[safe: index] ?? nil,
                  let rainProbability = data.precipitationProbability[safe: index] ?? nil,
                  let code = data.weatherCode[safe: index] ?? nil,
                  speed.isFinite, gust.isFinite, rain.isFinite, rainProbability.isFinite,
                  speed >= 0, gust >= 0, rain >= 0 else { continue }
            let time = Date(timeIntervalSince1970: TimeInterval(data.time[index]))
            guard time >= now else { continue }
            hours.append(.init(time: time, wind: speed, gust: gust, precipitation: rain, rainProbability: rainProbability, code: code))
        }
        let hasUsableWeather = hours.contains { days.contains(calendar.startOfDay(for: $0.time)) }
        guard hours.count >= 2 else { return (nil, hasUsableWeather) }

        var best: ScoredFishingWindow?
        for startIndex in hours.indices {
            for length in [3, 2] where startIndex + length <= hours.count {
                let samples = Array(hours[startIndex..<(startIndex + length)])
                guard days.contains(calendar.startOfDay(for: samples[0].time)),
                      zip(samples, samples.dropFirst()).allSatisfy({ $1.time.timeIntervalSince($0.time) == 3_600 }),
                      let daySolar = solar[calendar.startOfDay(for: samples[0].time)] else { continue }
                let end = samples[0].time.addingTimeInterval(TimeInterval(length * 3_600))
                if let preferredHours, !preferredHours.contains(start: samples[0].time, end: end, calendar: calendar) { continue }
                guard let candidateWindow = evaluate(samples: samples, end: end, spot: candidate.spot, distanceKm: candidate.distanceKm,
                                                     isTideStation: candidate.isTideStation, solar: daySolar, marineHours: marineHours, now: now) else { continue }
                if let current = best {
                    if candidateWindow.score > current.score ||
                        (candidateWindow.score == current.score && candidateWindow.end.timeIntervalSince(candidateWindow.start) > current.end.timeIntervalSince(current.start)) ||
                        (candidateWindow.score == current.score && candidateWindow.end.timeIntervalSince(candidateWindow.start) == current.end.timeIntervalSince(current.start) && candidateWindow.start < current.start) {
                        best = candidateWindow
                    }
                } else {
                    best = candidateWindow
                }
            }
        }
        return (best, hasUsableWeather)
    }

    private static func evaluate(samples: [WeatherHour], end: Date, spot: FishingSpot, distanceKm: Double,
                                 isTideStation: Bool, solar: SolarDay, marineHours: [Int: MarineHour], now: Date) -> ScoredFishingWindow? {
        let maximumWind = samples.map(\.wind).max()!
        let maximumGust = samples.map(\.gust).max()!
        guard !samples.contains(where: { (95...99).contains($0.code) }) else { return nil }
        if spot.boat {
            guard maximumWind < 40, maximumGust < 55 else { return nil }
        } else {
            guard maximumWind < 55, maximumGust < 70 else { return nil }
        }

        let marineSamples = samples.map { marineHours[Int($0.time.timeIntervalSince1970)] }
        let waves = marineSamples.compactMap { $0?.wave }
        let worstWave = waves.count == samples.count ? waves.max() : nil
        if spot.boat, let worstWave, worstWave >= 2 { return nil }
        if !spot.boat, let worstWave, worstWave >= 3 { return nil }
        let periods = marineSamples.compactMap { $0?.period }
        let longestPeriod = periods.count == samples.count ? periods.max() : nil
        let levels = marineSamples.compactMap { $0?.tide }
        let tide = !spot.boat && levels.count == samples.count
            ? tideScore(levels: levels, at: samples[0].time, marineHours: marineHours)
            : nil
        let wave = worstWave.map { waveScore($0, period: longestPeriod, boat: spot.boat) }

        let meanWind = samples.map(\.wind).reduce(0, +) / Double(samples.count)
        let meanRain = samples.map(\.precipitation).reduce(0, +) / Double(samples.count)
        let highestRainProbability = samples.map(\.rainProbability).max()!
        let wind = 0.65 * lowerIsBetter(maximumWind, best: spot.boat ? 10 : 12, worst: spot.boat ? 40 : 55)
            + 0.35 * lowerIsBetter(maximumGust, best: spot.boat ? 18 : 20, worst: spot.boat ? 55 : 70)
        let weather = 0.45 * (1 - clamp(highestRainProbability / 100))
            + 0.35 * lowerIsBetter(meanRain, best: 0, worst: 2.5)
            + 0.20 * (samples.map { weatherCodeScore($0.code) }.reduce(0, +) / Double(samples.count))
        let midpoint = samples[0].time.addingTimeInterval(end.timeIntervalSince(samples[0].time) / 2)
        let sunDistanceHours = min(abs(midpoint.timeIntervalSince(solar.sunrise)), abs(midpoint.timeIntervalSince(solar.sunset))) / 3_600
        let daylight = midpoint >= solar.sunrise && midpoint <= solar.sunset
        let timeScore: Double
        if daylight {
            if sunDistanceHours <= 1.5 { timeScore = 1 }
            else if sunDistanceHours <= 3 { timeScore = 0.8 }
            else { timeScore = spot.boat ? 0.62 : 0.68 }
        } else if sunDistanceHours <= 1 {
            timeScore = spot.boat ? 0.65 : 0.75
        } else {
            timeScore = spot.boat ? 0.2 : 0.15
        }
        let distanceScore = clamp(1 - distanceKm / 500)

        let tideWeight = spot.boat ? 0.0 : 25.0
        let windWeight = spot.boat ? 30.0 : 20.0
        let weatherWeight = spot.boat ? 15.0 : 20.0
        let waveWeight = spot.boat ? 35.0 : 10.0
        let timeWeight = spot.boat ? 10.0 : 15.0
        let distanceWeight = 10.0
        var weighted = windWeight * wind + weatherWeight * weather + timeWeight * timeScore + distanceWeight * distanceScore
        var availableWeight = windWeight + weatherWeight + timeWeight + distanceWeight
        if let tide { weighted += tideWeight * tide.value; availableWeight += tideWeight }
        if let wave { weighted += waveWeight * wave; availableWeight += waveWeight }
        var score = Int((100 * weighted / availableWeight).rounded())
        let missingMarine = wave == nil || (!spot.boat && tide == nil)
        if missingMarine { score = min(score, 79) }
        if samples[0].time.timeIntervalSince(now) > 7 * 86_400 { score = min(score, 89) }

        var reasons: [String] = []
        if let tide, tide.value >= 0.65 { reasons.append(tide.incoming ? "Moving incoming tide" : "Tide movement") }
        if meanWind <= (spot.boat ? 16 : 20), maximumGust <= (spot.boat ? 30 : 40) { reasons.append("Light wind around \(Int(meanWind.rounded())) km/h") }
        if meanRain < 0.2, highestRainProbability <= 30 { reasons.append("Low rain chance") }
        if let worstWave, worstWave < (spot.boat ? 0.75 : 1) { reasons.append(String(format: "Waves around %.1f m or less", worstWave)) }
        if daylight && sunDistanceHours <= 2 { reasons.append("Near sunrise or sunset") }
        if reasons.isEmpty { reasons.append("Best available \(samples.count)-hour forecast window") }

        var warnings: [String] = []
        if !spot.boat && tide == nil { warnings.append("Tide forecast unavailable for this window") }
        if wave == nil { warnings.append("Wave forecast unavailable for this window") }
        if samples[0].time < solar.sunrise || end > solar.sunset {
            warnings.append(daylight
                ? "Part of this window is after dark; check access, lighting and navigation"
                : "After dark; check access, lighting and navigation")
        }
        if maximumGust >= (spot.boat ? 40 : 55) { warnings.append("Strong gusts forecast") }
        if let worstWave, worstWave >= (spot.boat ? 1.2 : 1.5) { warnings.append("Elevated waves; check local exposure") }
        if let worstWave, let longestPeriod, worstWave >= (spot.boat ? 1.0 : 0.8), longestPeriod >= (spot.boat ? 10 : 12) {
            warnings.append("Long-period swell may increase surf and surge")
        }
        if highestRainProbability >= 60 || meanRain >= 1.5 { warnings.append("Rain likely during this window") }
        if samples.contains(where: { $0.code == 45 || $0.code == 48 }) { warnings.append("Fog may reduce visibility") }
        if samples[0].time.timeIntervalSince(now) > 7 * 86_400 { warnings.append("Long-range forecast; check again closer to the day") }
        if tide != nil { warnings.append("Coastal tide model is approximate; check local tide tables") }
        if isTideStation { warnings.append("Tide station is a forecast reference point; check local access and conditions before fishing") }

        return .init(
            id: "\(spot.name)|\(spot.boat)|\(Int(samples[0].time.timeIntervalSince1970))",
            spotName: spot.name, area: spot.area, boat: spot.boat, score: score,
            start: samples[0].time, end: end, distanceKm: distanceKm,
            reasons: reasons, warnings: warnings
        )
    }

    private static func tideScore(levels: [Double], at start: Date, marineHours: [Int: MarineHour]) -> (value: Double, incoming: Bool)? {
        guard levels.count >= 2, levels.allSatisfy(\.isFinite) else { return nil }
        let differences = zip(levels.dropFirst(), levels).map(-)
        let meanChange = differences.reduce(0, +) / Double(differences.count)
        let meanMovement = differences.map { abs($0) }.reduce(0, +) / Double(differences.count)
        let nearby = marineHours.compactMap { hour, value -> Double? in
            guard abs(Double(hour) - start.timeIntervalSince1970) <= 6 * 3_600 else { return nil }
            return value.tide
        }
        let localRange = (nearby.max() ?? 0) - (nearby.min() ?? 0)
        let movement = clamp(meanMovement / max(localRange / 5, 0.05))
        let incoming = meanChange > 0.01
        let value = 0.2 + 0.65 * movement + (incoming ? 0.15 : 0)
        return (clamp(value), incoming)
    }

    private static func waveScore(_ height: Double, period: Double?, boat: Bool) -> Double {
        let heightScore = lowerIsBetter(height, best: boat ? 0.5 : 0.7, worst: boat ? 2 : 2.5)
        guard let period, height >= (boat ? 1 : 0.8), period >= (boat ? 10 : 12) else { return heightScore }
        return heightScore * 0.75
    }

    private static func weatherCodeScore(_ code: Int) -> Double {
        switch code {
        case 0...3: return 1
        case 45, 48: return 0.35
        case 51...57: return 0.65
        case 61, 63, 80, 81: return 0.4
        case 65, 66, 67, 82: return 0.1
        case 71...77, 85, 86: return 0.15
        default: return 0.5
        }
    }

    private static func lowerIsBetter(_ value: Double, best: Double, worst: Double) -> Double {
        clamp((worst - value) / (worst - best))
    }

    private static func clamp(_ value: Double) -> Double { min(1, max(0, value)) }

    private static func distanceKm(from a: GeoPoint, to b: GeoPoint) -> Double {
        let radians = Double.pi / 180
        let deltaLatitude = (b.latitude - a.latitude) * radians
        let deltaLongitude = (b.longitude - a.longitude) * radians
        let haversine = pow(sin(deltaLatitude / 2), 2)
            + cos(a.latitude * radians) * cos(b.latitude * radians) * pow(sin(deltaLongitude / 2), 2)
        return 6_371 * 2 * asin(min(1, sqrt(max(0, haversine))))
    }

    private static func solarByDay(_ daily: WeatherPayload.Daily, calendar: Calendar) -> [Date: SolarDay] {
        var result: [Date: SolarDay] = [:]
        for index in daily.time.indices {
            guard let rise = daily.sunrise[safe: index] ?? nil,
                  let set = daily.sunset[safe: index] ?? nil else { continue }
            let day = calendar.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(daily.time[index])))
            result[day] = .init(sunrise: Date(timeIntervalSince1970: TimeInterval(rise)), sunset: Date(timeIntervalSince1970: TimeInterval(set)))
        }
        return result
    }

    private static func marineHoursByTime(_ hourly: MarinePayload.Hourly?) -> [Int: MarineHour] {
        guard let hourly else { return [:] }
        var result: [Int: MarineHour] = [:]
        for index in hourly.time.indices {
            let rawTide = hourly.seaLevelHeightMsl?[safe: index] ?? nil
            let rawWave = hourly.waveHeight?[safe: index] ?? nil
            let rawPeriod = hourly.wavePeriod?[safe: index] ?? nil
            result[hourly.time[index]] = .init(
                tide: rawTide?.isFinite == true ? rawTide : nil,
                wave: rawWave?.isFinite == true ? rawWave : nil,
                period: rawPeriod?.isFinite == true ? rawPeriod : nil
            )
        }
        return result
    }

    private static func fetchWeather(at coordinate: GeoPoint, boat: Bool, forecastDays: Int) async throws -> WeatherPayload {
        let url = apiURL(host: "api.open-meteo.com", path: "/v1/forecast", coordinate: coordinate, forecastDays: forecastDays, items: [
            .init(name: "hourly", value: "wind_speed_10m,wind_gusts_10m,precipitation,precipitation_probability,weather_code"),
            .init(name: "daily", value: "sunrise,sunset"),
            .init(name: "cell_selection", value: boat ? "sea" : "land")
        ])
        return try await fetch(WeatherPayload.self, from: url)
    }

    private static func fetchMarine(at coordinate: GeoPoint, forecastDays: Int) async throws -> MarinePayload {
        let url = apiURL(host: "marine-api.open-meteo.com", path: "/v1/marine", coordinate: coordinate, forecastDays: forecastDays, items: [
            .init(name: "hourly", value: "sea_level_height_msl,wave_height,wave_period"),
            .init(name: "cell_selection", value: "sea")
        ])
        return try await fetch(MarinePayload.self, from: url)
    }

    private static func apiURL(host: String, path: String, coordinate: GeoPoint, forecastDays: Int, items: [URLQueryItem]) -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = path
        components.queryItems = [
            .init(name: "latitude", value: String(coordinate.latitude)),
            .init(name: "longitude", value: String(coordinate.longitude)),
            .init(name: "timezone", value: "Pacific/Auckland"),
            .init(name: "forecast_days", value: String(forecastDays)),
            .init(name: "timeformat", value: "unixtime")
        ] + items
        return components.url!
    }

    private static func fetch<T: Decodable>(_ type: T.Type, from url: URL) async throws -> T {
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let response = response as? HTTPURLResponse, 200...299 ~= response.statusCode else { throw URLError(.badServerResponse) }
        return try JSONDecoder().decode(type, from: data)
    }
}

private struct Candidate: Sendable { let spot: FishingSpot; let distanceKm: Double; let isTideStation: Bool }
private enum SpotOutcome: Sendable {
    case forecastAvailable(ScoredFishingWindow?, Bool)
    case forecastFailed
}
private struct SolarDay: Sendable { let sunrise: Date; let sunset: Date }
private struct WeatherHour: Sendable {
    let time: Date
    let wind: Double
    let gust: Double
    let precipitation: Double
    let rainProbability: Double
    let code: Int
}
private struct MarineHour: Sendable { let tide: Double?; let wave: Double?; let period: Double? }

private struct WeatherPayload: Decodable, Sendable {
    let hourly: Hourly
    let daily: Daily

    struct Hourly: Decodable, Sendable {
        let time: [Int]
        let windSpeed10m: [Double?]
        let windGusts10m: [Double?]
        let precipitation: [Double?]
        let precipitationProbability: [Double?]
        let weatherCode: [Int?]
        enum CodingKeys: String, CodingKey {
            case time, precipitation
            case windSpeed10m = "wind_speed_10m"
            case windGusts10m = "wind_gusts_10m"
            case precipitationProbability = "precipitation_probability"
            case weatherCode = "weather_code"
        }
    }

    struct Daily: Decodable, Sendable {
        let time: [Int]
        let sunrise: [Int?]
        let sunset: [Int?]
    }
}

private struct MarinePayload: Decodable, Sendable {
    let hourly: Hourly
    struct Hourly: Decodable, Sendable {
        let time: [Int]
        let seaLevelHeightMsl: [Double?]?
        let waveHeight: [Double?]?
        let wavePeriod: [Double?]?
        enum CodingKeys: String, CodingKey {
            case time
            case seaLevelHeightMsl = "sea_level_height_msl"
            case waveHeight = "wave_height"
            case wavePeriod = "wave_period"
        }
    }
}

private enum ScoringError: LocalizedError {
    case invalidRadius
    case datesOutsideForecast
    case forecastsUnavailable
    case noUsableForecast

    var errorDescription: String? {
        switch self {
        case .invalidRadius: return "Choose a search distance greater than zero."
        case .datesOutsideForecast: return "Choose a day within the next 16 days."
        case .forecastsUnavailable: return "Weather forecasts are unavailable for nearby spots. Please try again later."
        case .noUsableForecast: return "No usable hourly weather forecast was available for the selected days."
        }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
