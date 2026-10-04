import Foundation

// Run using tools/test_fishing_windows_swift.sh. Uses the production Foundation-only services.
@main struct FishingWindowRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws {
        checks += 1
        if !value() { throw Failure.assertion(message) }
    }
    static func rejects(_ work: () throws -> Void, _ message: String) throws {
        var rejected = false
        do { try work() } catch { rejected = true }
        try expect(rejected, message)
    }
    static func main() async throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        let fixture = root.appendingPathComponent("data/analysis/raglan-2026-09-27")
        let csv = try String(contentsOf: fixture.appendingPathComponent("raglan-2026.csv"), encoding: .utf8)
        let tides = try LINZTideStore.parse(csv, stationName: "Raglan", year: 2026)
        let calendar = LINZTideStore.calendar
        func at(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
            calendar.date(from: DateComponents(year: 2026, month: 9, day: day, hour: hour, minute: minute))!
        }
        let high = tides.first { $0.time == at(30, 13, 23) }
        try expect(high?.height == 3.2, "Official Raglan high must be 13:23 NZDT, 3.2 m")
        try expect(high?.time.timeIntervalSince1970 == 1_790_727_780, "Check official UTC epoch")
        try rejects({ _ = try LINZTideStore.parse(csv, stationName: "Thames", year: 2026) }, "Wrong station accepted")
        try rejects({ _ = try LINZTideStore.parse(csv, stationName: "Raglan", year: 2027) }, "Wrong year accepted")
        try rejects({ _ = try LINZTideStore.parse(csv.replacingOccurrences(of: "Tidal heights in metres.", with: "feet"), stationName: "Raglan", year: 2026) }, "Wrong units accepted")
        let header = "180,Raglan,37S,174E\nBased on constituent set with reference date:,01-Jul-2016\nLocal Std or Daylight Time,Tidal heights in metres.\n"
        for row in ["27,Su,9,2026,02:30,0.2,08:30,3.2", "5,Su,4,2026,02:30,0.2,08:30,3.2", "30,We,9,2026,07:02,0.2,13:23,1.0,19:24,3.2"] {
            try rejects({ _ = try LINZTideStore.parse(header + row, stationName: "Raglan", year: 2026) }, "Invalid clocks/extrema accepted")
        }
        let thamesCSV = try String(contentsOf: root.appendingPathComponent("app/src/test/resources/tides/thames-2026.csv"), encoding: .utf8)
        let octoberDay = calendar.date(from: DateComponents(year: 2026, month: 10, day: 4))!
        let thames = try LINZTideStore.parse(thamesCSV, stationName: "Thames", year: 2026, start: octoberDay, end: octoberDay)
        let dailyThames = thames.filter { calendar.isDate($0.time, inSameDayAs: octoberDay) }
        try expect(dailyThames.map(\.height) == [3.5, 0.9, 3.5, 1.1], "Thames October heights should load despite April's repeated clock")
        try expect(dailyThames.map { calendar.component(.hour, from: $0.time) * 60 + calendar.component(.minute, from: $0.time) } == [80, 444, 830, 1207], "Thames published clocks changed")
        let octoberHigh = calendar.date(from: DateComponents(year: 2026, month: 10, day: 4, hour: 13, minute: 50))!
        try expect(dailyThames[2].time == octoberHigh && calendar.timeZone.secondsFromGMT(for: octoberHigh) == 13 * 3600, "Thames daylight saving must be applied once")
        try expect(thames.count == 6 && thames.first!.time < octoberDay && thames.last!.time > calendar.date(byAdding: .day, value: 1, to: octoberDay)!, "Thames curve needs adjacent events")
        for day in [4, 5] {
            let aprilDay = calendar.date(from: DateComponents(year: 2026, month: 4, day: day))!
            try rejects({ _ = try LINZTideStore.parse(thamesCSV, stationName: "Thames", year: 2026, start: aprilDay, end: aprilDay) }, "Required repeated clock must remain unverified")
        }
        try rejects({ _ = try LINZTideStore.parse(thamesCSV.replacingOccurrences(of: "02:59,0.9", with: "02:59,NaN"), stationName: "Thames", year: 2026, start: octoberDay, end: octoberDay) }, "Scoped requests must still validate all heights")
        try rejects({ _ = try LINZTideStore.parse(thamesCSV, stationName: "Raglan", year: 2026, start: octoberDay, end: octoberDay) }, "Scoped requests must still check station identity")
        if CommandLine.arguments.contains("--live-tides") {
            let store = LINZTideStore()
            let liveThames = try await store.predictions(stationName: "Thames", start: octoberDay, end: octoberDay)
            try expect(liveThames.map(\.height) == thames.map(\.height) && liveThames.map(\.time) == thames.map(\.time), "Live Thames table differs from the original fixture")
            let tomorrow = calendar.date(byAdding: .day, value: 1, to: octoberDay)!
            let cachedTomorrow = try await store.predictions(stationName: "Thames", start: tomorrow, end: tomorrow)
            try expect(cachedTomorrow.contains { calendar.isDate($0.time, inSameDayAs: tomorrow) }, "Cached annual table must serve another date")
            print("Live Thames tide levels and cached date changes passed.")
        }
        var weather = try JSONDecoder().decode(WeatherPayload.self, from: Data(contentsOf: fixture.appendingPathComponent("weather.json")))
        var marine = try JSONDecoder().decode(MarinePayload.self, from: Data(contentsOf: fixture.appendingPathComponent("marine.json")))
        weather.retrievedAt = at(27, 13, 17); marine.retrievedAt = at(27, 13, 17)
        try expect(weather.isValid && marine.isValid, "Frozen provider units/arrays rejected")
        let station = tideStations.first { $0.id == "raglan" }!
        let spot = FishingSpot(name: "Raglan", area: "Waikato", coordinate: .init(latitude: -37.8, longitude: 174.883333), boat: false)
        let candidate = Candidate(spot: spot, distanceKm: 0, isTideStation: true, tideStation: station)
        func selected(_ priority: WindowPriority) -> ScoredFishingWindow? {
            FishingScoringService.bestWindows(for: candidate, weather: weather, marine: marine, tides: tides,
                days: [at(30, 0)], now: at(27, 0), calendar: calendar, preferredHours: nil, priority: priority).windows.first
        }
        let early = selected(.weather)
        let later = selected(.lateIncoming)
        try expect(early?.start == at(30, 7), "Frozen weather selection should be 07:00")
        try expect(later?.start == at(30, 11), "Frozen late-incoming selection should be 11:00")
        try expect(later?.summary.contains("0.5 mm") == true, "Explanation must use session rain")
        try expect(later?.conditions.contains(where: { $0.contains("1:23") }) == true, "Explanation must use LINZ high")
        try expect(early?.alternative != nil, "Real tide alternative should be offered")
        try expect(later?.alternative != nil, "Real weather alternative should be offered")
        let noTides = FishingScoringService.bestWindows(for: candidate, weather: weather, marine: marine, tides: [], days: [at(30, 0)], now: at(27, 0), calendar: calendar, preferredHours: nil, priority: .lateIncoming)
        try expect(noTides.windows.isEmpty, "Cannot fabricate late incoming without LINZ")
        var badUnits = try JSONSerialization.jsonObject(with: Data(contentsOf: fixture.appendingPathComponent("weather.json"))) as! [String: Any]
        var units = badUnits["hourly_units"] as! [String: String]; units["wind_speed_10m"] = "kn"; badUnits["hourly_units"] = units
        let badWeather = try JSONDecoder().decode(WeatherPayload.self, from: JSONSerialization.data(withJSONObject: badUnits))
        try expect(!badWeather.isValid, "Wrong weather units accepted")
        let hours = (7...9).map { WeatherHour(time: at(30, $0), wind: 5, gust: $0 == 7 ? 90 : 14,
                                            precipitation: $0 == 7 ? 20 : 0.25, rainProbability: 0, code: 0) }
        let solar = [at(30, 0): SolarDay(sunrise: at(30, 7), sunset: at(30, 19))]
        let normalWaves = Dictionary(uniqueKeysWithValues: hours.map { (Int($0.time.timeIntervalSince1970), MarineHour(wave: 1, period: 8)) })
        func evaluate(_ samples: [WeatherHour], _ waves: [Int: MarineHour]) -> ScoredFishingWindow? {
            FishingScoringService.evaluate(samples: samples, spot: spot, distanceKm: 0, isTideStation: true,
                solar: solar, marineHours: waves, tides: tides, tideStation: station, now: at(27, 0), calendar: calendar,
                priority: .weather, sourceNote: "Frozen test")
        }
        let interval = evaluate(hours, normalWaves)
        try expect(interval != nil && interval!.summary.contains("0.5 mm"), "07 interval must be excluded")
        var adverse = normalWaves
        adverse[Int(at(30, 7).timeIntervalSince1970)] = .init(wave: 3.5, period: 12)
        adverse[Int(at(30, 8).timeIntervalSince1970)] = .init(wave: nil, period: nil)
        try expect(evaluate(hours, adverse) == nil, "Missing wave must not erase known adverse wave")
        var ending = normalWaves; ending[Int(at(30, 9).timeIntervalSince1970)] = .init(wave: 3.5, period: 12)
        try expect(evaluate(hours, ending) == nil, "Final endpoint wave omitted")
        let partial = evaluate(hours, [:])!
        try expect(!partial.dataComplete && FishingScoringService.isBetter(interval!, than: partial), "Missing marine cannot improve selection")
        let boatSpot = FishingSpot(name: spot.name, area: spot.area, coordinate: spot.coordinate, boat: true)
        let boatHours = (7...9).map { WeatherHour(time: at(30, $0), wind: 5, gust: 10, precipitation: 0, rainProbability: 0, code: 0) }
        func boatWaves(_ height: Double, _ period: Double) -> [Int: MarineHour] {
            Dictionary(uniqueKeysWithValues: boatHours.map { (Int($0.time.timeIntervalSince1970), MarineHour(wave: height, period: period)) })
        }
        func evaluateBoat(_ samples: [WeatherHour], _ waves: [Int: MarineHour], priority: WindowPriority = .weather) -> ScoredFishingWindow? {
            FishingScoringService.evaluate(samples: samples, spot: boatSpot, distanceKm: 0, isTideStation: true,
                solar: solar, marineHours: waves, tides: tides, tideStation: station, now: at(27, 0), calendar: calendar,
                priority: priority, sourceNote: "Frozen boat test")
        }
        let breezyWetHours = (7...9).map { WeatherHour(time: at(30, $0), wind: 20, gust: 25, precipitation: 0.3, rainProbability: 50, code: 0) }
        let calmSea = evaluateBoat(breezyWetHours, boatWaves(0.5, 8))!
        let roughSea = evaluateBoat(boatHours, boatWaves(1.5, 4))!
        try expect(FishingScoringService.isBetter(calmSea, than: roughSea), "Boat comfort must prefer calmer waves even with more wind and rain")
        try expect(calmSea.summary.contains("wave comfort as the main factor") && roughSea.summary.contains("wave height up to 1.5 m"), "Boat explanations must state the wave preference and forecast")
        let shortWaves = evaluateBoat(boatHours, boatWaves(1, 4))!
        let spacedWaves = evaluateBoat(boatHours, boatWaves(1, 8))!
        try expect(FishingScoringService.isBetter(spacedWaves, than: shortWaves), "Short mean periods should reduce boat comfort")
        let shortRecommendation = Recommendation(name: spot.name, area: spot.area, rating: shortWaves.score, time: "", distance: "", reasons: [], boat: true,
                                                 warnings: shortWaves.warnings, conditions: shortWaves.conditions)
        try expect(shortRecommendation.windowMood.label == "Check conditions" && shortRecommendation.conditionMood(4).label == "Concerning", "Short-wave warnings must affect boat outlook and wave mood")
        var endingPeriod = boatWaves(0.6, 8)
        endingPeriod[Int(at(30, 9).timeIntervalSince1970)] = MarineHour(wave: 0.6, period: 4)
        let endingChop = evaluateBoat(boatHours, endingPeriod)!
        try expect(endingChop.rankingValue < evaluateBoat(boatHours, boatWaves(0.6, 8))!.rankingValue && endingChop.conditions[4].contains("4.0–8.0 s"), "Final wave sample and shortest period must be retained")
        var missingPeriod = boatWaves(0.6, 4)
        missingPeriod[Int(at(30, 8).timeIntervalSince1970)] = MarineHour(wave: 0.6, period: nil)
        let incompleteBoat = evaluateBoat(boatHours, missingPeriod)!
        try expect(!incompleteBoat.dataComplete && incompleteBoat.score <= 40 && incompleteBoat.rankingValue < evaluateBoat(boatHours, boatWaves(0.6, 4))!.rankingValue, "Missing boat period must not earn marine comfort credit")
        missingPeriod[Int(at(30, 9).timeIntervalSince1970)] = MarineHour(wave: 2, period: nil)
        try expect(evaluateBoat(boatHours, missingPeriod) == nil, "Known 2 m boat wave still excludes a window when period is missing")
        let lateBoatHours = (11...13).map { WeatherHour(time: at(30, $0), wind: 5, gust: 10, precipitation: 0, rainProbability: 0, code: 0) }
        let lateBoatWaves = Dictionary(uniqueKeysWithValues: lateBoatHours.map { (Int($0.time.timeIntervalSince1970), MarineHour(wave: 1, period: 8)) })
        let lateBoat = evaluateBoat(lateBoatHours, lateBoatWaves, priority: .lateIncoming)!
        try expect(lateBoat.tidePreferenceFit >= 0.8 && lateBoat.summary.contains("wave comfort as the main factor") && lateBoat.rankingValue == evaluateBoat(lateBoatHours, lateBoatWaves)!.rankingValue, "Late incoming must retain boat wave ranking and explain it")
        let landLowWaves = evaluate(boatHours, boatWaves(0.5, 8))!
        let landHighWaves = evaluate(boatHours, boatWaves(1, 4))!
        try expect(landLowWaves.rankingValue == landHighWaves.rankingValue && !landHighWaves.warnings.contains { $0.hasPrefix("Short-period waves") }, "New wave comfort policy must not change land ranking")
        try expect(FishingScoringService.daylightFraction(start: at(30, 6), end: at(30, 8), solar: solar, calendar: calendar) == 0.5, "Daylight must cover full session")
        try expect(FishingScoringService.daylightFraction(start: at(30, 23), end: at(30, 23).addingTimeInterval(7200), solar: solar, calendar: calendar) == nil, "Midnight needs next-day solar")
        print("Passed \(checks) Swift fishing-window regression checks.")
        print("Frozen Raglan: weather 07:00–09:00; late incoming 11:00–13:00; official high 13:23.")
        if CommandLine.arguments.contains("--live") {
            let official = try await LINZTideStore.shared.load(stationName: "Raglan", year: 2026)
            try expect(official.contains { $0.time == at(30, 13, 23) && $0.height == 3.2 }, "Live official tide differs from verified event")
            let windows = try await FishingScoringService().rank(origin: spot.coordinate, radiusKm: 0,
                selectedStation: station, days: [at(30, 0)], boat: false, preferredHours: nil, priority: .weather)
            print("Live provider validation passed; \(windows.count) Raglan planning window(s).")
            for window in windows { print(window.summary); print(window.sourceNote) }
        }
    }
}
