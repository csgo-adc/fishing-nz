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
        let manOWarCSV = try String(contentsOf: root.appendingPathComponent("app/src/test/resources/tides/man-owar-bay-2026.csv"), encoding: .utf8)
        let waitawaDay = calendar.date(from: DateComponents(year: 2026, month: 10, day: 5))!
        let waitawaReference = tideStations.first { $0.id == "man-o-war-bay" }!
        let waitawaTides = try LINZTideStore.parse(manOWarCSV, stationName: waitawaReference.csvName, year: 2026, start: waitawaDay, end: waitawaDay)
        let dailyWaitawa = waitawaTides.filter { calendar.isDate($0.time, inSameDayAs: waitawaDay) }
        try expect(dailyWaitawa.map(\.height) == [2.9, 0.6, 3.0, 0.8], "Waitawa reference must accept LINZ's spaced apostrophe in Man O' War Bay")
        try expect(dailyWaitawa.map { calendar.component(.hour, from: $0.time) * 60 + calendar.component(.minute, from: $0.time) } == [138, 508, 893, 1273], "Waitawa reference must preserve published NZDT clocks")
        try expect(waitawaTides.count == 6 && waitawaTides.first!.time < waitawaDay && waitawaTides.last!.time > calendar.date(byAdding: .day, value: 1, to: waitawaDay)!, "Waitawa curve needs adjacent events")
        try rejects({ _ = try LINZTideStore.parse(manOWarCSV.replacingOccurrences(of: "Man O' War Bay", with: "Matiatia Bay"), stationName: waitawaReference.csvName, year: 2026, start: waitawaDay, end: waitawaDay) }, "Apostrophe normalization must still reject a different station")
        for (filename, sourceName) in [
            ("Halfmoon Bay - Oban", "Halfmoon Bay / Oban"), ("Kaituna River Entrance", "Kaituna River"),
            ("Lottin Point - Wakatiri", "Lottin Point / Wakatiri"), ("North Cape - Otou", "North Cape / Otou"),
            ("Rangitaiki River Entrance", "Rangitaiki River"), ("Town Basin", "Town Basin - Whangarei")
        ] {
            let aliasedCSV = header.replacingOccurrences(of: "180,Raglan", with: "147,\(sourceName)") + "5,Mo,10,2026,08:00,0.5,14:00,3.0"
            let aliasedTides = try LINZTideStore.parse(aliasedCSV, stationName: filename, year: 2026)
            try expect(aliasedTides.count == 2, "Verified LINZ filename/header alias rejected")
            try rejects({ _ = try LINZTideStore.parse(aliasedCSV.replacingOccurrences(of: sourceName, with: "Another Bay"), stationName: filename, year: 2026) }, "Verified aliases must reject other station names")
        }
        let manaCSV = try String(contentsOf: root.appendingPathComponent("app/src/test/resources/tides/mana-marina-2026.csv"), encoding: .utf8)
        let mana = try LINZTideStore.parse(manaCSV, stationName: "Mana Marina", year: 2026, start: waitawaDay, end: waitawaDay)
        let dailyMana = mana.filter { calendar.isDate($0.time, inSameDayAs: waitawaDay) }
        try expect(dailyMana.map(\.height) == [1.3, 1.0, 1.2], "Unrelated rounded equal-height extrema must not hide valid Mana tides")
        try expect(dailyMana.map { calendar.component(.hour, from: $0.time) * 60 + calendar.component(.minute, from: $0.time) } == [283, 574, 1068], "Mana published clocks changed")
        let roundedDay = calendar.date(from: .init(year: 2026, month: 2, day: 27))!
        try rejects({ _ = try LINZTideStore.parse(manaCSV, stationName: "Mana Marina", year: 2026, start: roundedDay, end: roundedDay) }, "Needed equal-height extrema must remain unclassified")
        try rejects({ _ = try LINZTideStore.parse(manaCSV.replacingOccurrences(of: "03:30,1.2", with: "03:30,NaN"), stationName: "Mana Marina", year: 2026, start: waitawaDay, end: waitawaDay) }, "Scoped Mana requests must still reject invalid source heights")
        let waitawaSpot = fishingSpots.first { $0.name == "Waitawa Wharf" }!
        let loadedWaitawa = try await loadTideReference(point: waitawaSpot.coordinate, linked: FishingScoringService.matchingStation(for: waitawaSpot)) {
            try LINZTideStore.parse(manOWarCSV, stationName: $0.csvName, year: 2026, start: waitawaDay, end: waitawaDay)
        }
        try expect(loadedWaitawa.station == waitawaReference, "Planner did not automatically load Waitawa's nearby tide reference")
        func waitawaAt(_ hour: Int) -> Date { calendar.date(bySettingHour: hour, minute: 0, second: 0, of: waitawaDay)! }
        let waitawaHours = (12...14).map { WeatherHour(time: waitawaAt($0), wind: 5, gust: 10, precipitation: 0, rainProbability: 0, code: 0, feelsLike: 16) }
        let waitawaWaves = Dictionary(uniqueKeysWithValues: waitawaHours.map { (Int($0.time.timeIntervalSince1970), MarineHour(wave: 1, period: 8)) })
        let waitawaWindow = FishingScoringService.evaluate(samples: waitawaHours, spot: waitawaSpot, distanceKm: 0, isTideStation: false,
            solar: [waitawaDay: SolarDay(sunrise: waitawaAt(7), sunset: waitawaAt(19))], marineHours: waitawaWaves,
            tides: loadedWaitawa.value, tideStation: loadedWaitawa.station, now: waitawaAt(0), calendar: calendar, priority: .lateIncoming, sourceNote: "Test")!
        let waitawaRow = waitawaWindow.assessment!.conditions.first { $0.title == "Tide" }!
        try expect(waitawaRow.mood.label == "Late incoming" && waitawaWindow.tidePreferenceFit >= 0.8, "Waitawa reference was not used for late incoming selection")
        try expect(waitawaRow.value.contains("2:53 PM") && waitawaRow.value.contains("3.0 m CD"), "Waitawa window omitted published tide events")
        try expect(waitawaRow.value.contains("LINZ Man o‘War Bay · 14.5 km away"), "Waitawa window must identify the actual nearby reference")
        try expect(!waitawaWindow.assessment!.details.contains("Local tide coverage unverified."), "Loaded Waitawa tides still show unverified")
        if CommandLine.arguments.contains("--live-tides") {
            let store = LINZTideStore()
            let liveThames = try await store.predictions(stationName: "Thames", start: octoberDay, end: octoberDay)
            try expect(liveThames.map(\.height) == thames.map(\.height) && liveThames.map(\.time) == thames.map(\.time), "Live Thames table differs from the original fixture")
            let tomorrow = calendar.date(byAdding: .day, value: 1, to: octoberDay)!
            let cachedTomorrow = try await store.predictions(stationName: "Thames", start: tomorrow, end: tomorrow)
            try expect(cachedTomorrow.contains { calendar.isDate($0.time, inSameDayAs: tomorrow) }, "Cached annual table must serve another date")
            let liveWaitawa = try await store.predictions(stationName: waitawaReference.csvName, start: waitawaDay, end: waitawaDay)
            try expect(liveWaitawa.map(\.height) == waitawaTides.map(\.height) && liveWaitawa.map(\.time) == waitawaTides.map(\.time), "Live Waitawa reference differs from the original fixture")
            let plannerDay = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: .now))!
            let liveWindows = try await FishingScoringService().rank(origin: waitawaSpot.coordinate, radiusKm: 1, days: [plannerDay], boat: false, preferredHours: nil)
            try expect(!liveWindows.isEmpty && liveWindows.allSatisfy { $0.spotName == "Waitawa Wharf" }, "Live planner did not return Waitawa windows")
            try expect(liveWindows.allSatisfy { $0.assessment?.conditions.first(where: { $0.title == "Tide" })?.value.contains("LINZ Man o‘War Bay · 14.5 km away") == true }, "Live planner failed to populate the nearby tide reference")
            print("Live Thames and Waitawa tides, cached date changes and Waitawa fishing windows passed.")
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
        try expect(later?.assessment?.conditions.first(where: { $0.title == "Rain" })?.value.contains("0.5 mm") == true, "Explanation must use session rain")
        try expect(later?.conditions.contains(where: { $0.contains("1:23") }) == true, "Explanation must use LINZ high")
        try expect(early?.alternative != nil, "Real tide alternative should be offered")
        try expect(later?.alternative != nil, "Real weather alternative should be offered")
        let noTides = FishingScoringService.bestWindows(for: candidate, weather: weather, marine: marine, tides: [], days: [at(30, 0)], now: at(27, 0), calendar: calendar, preferredHours: nil, priority: .lateIncoming)
        try expect(noTides.windows.isEmpty, "Cannot fabricate late incoming without LINZ")
        var badUnits = try JSONSerialization.jsonObject(with: Data(contentsOf: fixture.appendingPathComponent("weather.json"))) as! [String: Any]
        var units = badUnits["hourly_units"] as! [String: String]; units["wind_speed_10m"] = "kn"; badUnits["hourly_units"] = units
        let badWeather = try JSONDecoder().decode(WeatherPayload.self, from: JSONSerialization.data(withJSONObject: badUnits))
        try expect(!badWeather.isValid, "Wrong weather units accepted")
        var optionalWeather = try JSONSerialization.jsonObject(with: Data(contentsOf: fixture.appendingPathComponent("weather.json"))) as! [String: Any]
        var optionalHours = optionalWeather["hourly"] as! [String: Any]
        var optionalUnits = optionalWeather["hourly_units"] as! [String: String]
        let hourCount = (optionalHours["time"] as! [Int]).count
        optionalHours["apparent_temperature"] = Array(repeating: 16.0, count: hourCount)
        optionalUnits["apparent_temperature"] = "°F"
        optionalWeather["hourly"] = optionalHours; optionalWeather["hourly_units"] = optionalUnits
        let wrongThermalUnits = try JSONDecoder().decode(WeatherPayload.self, from: JSONSerialization.data(withJSONObject: optionalWeather))
        try expect(!wrongThermalUnits.isValid, "Wrong feels-like temperature units accepted")
        optionalUnits["apparent_temperature"] = "°C"; optionalWeather["hourly_units"] = optionalUnits
        let thermalWeather = try JSONDecoder().decode(WeatherPayload.self, from: JSONSerialization.data(withJSONObject: optionalWeather))
        try expect(thermalWeather.isValid && thermalWeather.hourly.apparentTemperature?.first == 16, "Valid feels-like data must decode")
        optionalHours["apparent_temperature"] = [16.0]; optionalWeather["hourly"] = optionalHours
        let shortThermalArray = try JSONDecoder().decode(WeatherPayload.self, from: JSONSerialization.data(withJSONObject: optionalWeather))
        try expect(!shortThermalArray.isValid, "Misaligned temperature arrays accepted")
        optionalHours.removeValue(forKey: "apparent_temperature"); optionalHours.removeValue(forKey: "precipitation_probability"); optionalWeather["hourly"] = optionalHours
        let missingChance = try JSONDecoder().decode(WeatherPayload.self, from: JSONSerialization.data(withJSONObject: optionalWeather))
        try expect(missingChance.isValid && missingChance.hourly.precipitationProbability == nil, "Missing likelihood must stay optional")
        let hours = (7...9).map { WeatherHour(time: at(30, $0), wind: 5, gust: $0 == 7 ? 90 : 14,
                                            precipitation: $0 == 7 ? 20 : 0.25, rainProbability: 0, code: 0, feelsLike: 16) }
        let solar = [at(30, 0): SolarDay(sunrise: at(30, 7), sunset: at(30, 19))]
        let normalWaves = Dictionary(uniqueKeysWithValues: hours.map { (Int($0.time.timeIntervalSince1970), MarineHour(wave: 1, period: 8)) })
        func evaluate(_ samples: [WeatherHour], _ waves: [Int: MarineHour]) -> ScoredFishingWindow? {
            FishingScoringService.evaluate(samples: samples, spot: spot, distanceKm: 0, isTideStation: true,
                solar: solar, marineHours: waves, tides: tides, tideStation: station, now: at(27, 0), calendar: calendar,
                priority: .weather, sourceNote: "Frozen test")
        }
        let interval = evaluate(hours, normalWaves)
        try expect(interval?.assessment?.conditions.first(where: { $0.title == "Rain" })?.value.contains("0.5 mm") == true, "07 interval must be excluded")
        var adverse = normalWaves
        adverse[Int(at(30, 7).timeIntervalSince1970)] = .init(wave: 3.5, period: 12)
        adverse[Int(at(30, 8).timeIntervalSince1970)] = .init(wave: nil, period: nil)
        try expect(evaluate(hours, adverse) == nil, "Missing wave must not erase known adverse wave")
        var ending = normalWaves; ending[Int(at(30, 9).timeIntervalSince1970)] = .init(wave: 3.5, period: 12)
        try expect(evaluate(hours, ending) == nil, "Final endpoint wave omitted")
        let partial = evaluate(hours, [:])!
        try expect(partial.dataComplete && !FishingScoringService.isBetter(interval!, than: partial) && !FishingScoringService.isBetter(partial, than: interval!), "Missing marine must stay independent of land weather comfort")
        try expect(partial.assessment!.checks.label == "Local checks needed" && partial.assessment!.details.contains { $0.contains("wave coverage incomplete") }, "Missing local data must retain its outstanding checks")
        let boatSpot = FishingSpot(name: spot.name, area: spot.area, coordinate: spot.coordinate, boat: true)
        let boatHours = (7...9).map { WeatherHour(time: at(30, $0), wind: 5, gust: 10, precipitation: 0, rainProbability: 0, code: 0, feelsLike: 16) }
        func boatWaves(_ height: Double, _ period: Double) -> [Int: MarineHour] {
            Dictionary(uniqueKeysWithValues: boatHours.map { (Int($0.time.timeIntervalSince1970), MarineHour(wave: height, period: period)) })
        }
        func evaluateBoat(_ samples: [WeatherHour], _ waves: [Int: MarineHour], priority: WindowPriority = .weather) -> ScoredFishingWindow? {
            FishingScoringService.evaluate(samples: samples, spot: boatSpot, distanceKm: 0, isTideStation: true,
                solar: solar, marineHours: waves, tides: tides, tideStation: station, now: at(27, 0), calendar: calendar,
                priority: priority, sourceNote: "Frozen boat test")
        }
        let breezyWetHours = (7...9).map { WeatherHour(time: at(30, $0), wind: 20, gust: 25, precipitation: 0.3, rainProbability: 50, code: 0, feelsLike: 16) }
        let calmSea = evaluateBoat(breezyWetHours, boatWaves(0.5, 8))!
        let roughSea = evaluateBoat(boatHours, boatWaves(1.5, 4))!
        try expect(FishingScoringService.isBetter(calmSea, than: roughSea), "Boat comfort must prefer calmer waves even with more wind and rain")
        try expect(calmSea.reasons == ["Waves first"] && roughSea.assessment!.conditions.first!.value.contains("1.5 m"), "Boat explanations must state the wave preference and forecast")
        let shortWaves = evaluateBoat(boatHours, boatWaves(1, 4))!
        let spacedWaves = evaluateBoat(boatHours, boatWaves(1, 8))!
        try expect(FishingScoringService.isBetter(spacedWaves, than: shortWaves), "Short mean periods should reduce boat comfort")
        let shortRecommendation = Recommendation(name: spot.name, area: spot.area, rating: shortWaves.score, time: "", distance: "", reasons: [], boat: true,
                                                 warnings: shortWaves.warnings, conditions: shortWaves.conditions, assessment: shortWaves.assessment)
        try expect(shortRecommendation.windowMood.label == "Demanding" && shortWaves.assessment!.conditions.first!.mood.label == "Choppy", "Short waves must affect boat comfort and wave feeling")
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
        let lateBoatHours = (11...13).map { WeatherHour(time: at(30, $0), wind: 5, gust: 10, precipitation: 0, rainProbability: 0, code: 0, feelsLike: 16) }
        let lateBoatWaves = Dictionary(uniqueKeysWithValues: lateBoatHours.map { (Int($0.time.timeIntervalSince1970), MarineHour(wave: 1, period: 8)) })
        let lateBoat = evaluateBoat(lateBoatHours, lateBoatWaves, priority: .lateIncoming)!
        try expect(lateBoat.tidePreferenceFit >= 0.8 && lateBoat.reasons == ["Fits late incoming · waves first"] && lateBoat.rankingValue == evaluateBoat(lateBoatHours, lateBoatWaves)!.rankingValue, "Late incoming must retain boat wave ranking and explain it")
        let coldBoatHours = boatHours.map { WeatherHour(time: $0.time, wind: $0.wind, gust: $0.gust, precipitation: $0.precipitation, rainProbability: $0.rainProbability, code: $0.code, feelsLike: 7) }
        let hotBoatHours = boatHours.map { WeatherHour(time: $0.time, wind: $0.wind, gust: $0.gust, precipitation: $0.precipitation, rainProbability: $0.rainProbability, code: $0.code, feelsLike: 34) }
        let unknownBoatHours = boatHours.map { WeatherHour(time: $0.time, wind: $0.wind, gust: $0.gust, precipitation: $0.precipitation, rainProbability: $0.rainProbability, code: $0.code) }
        let mildBoat = evaluateBoat(boatHours, boatWaves(0.3, 8))!, coldBoat = evaluateBoat(coldBoatHours, boatWaves(0.3, 8))!
        try expect(coldBoat.assessment!.mood.label == "Demanding" && evaluateBoat(hotBoatHours, boatWaves(0.3, 8))!.assessment!.mood.label == "Uncomfortable", "Boat cold and heat must limit the comfort outlook")
        let unknownBoat = evaluateBoat(unknownBoatHours, boatWaves(0.3, 8))!
        try expect(unknownBoat.assessment!.mood.label == "Needs more data" && !unknownBoat.dataComplete, "Missing boat temperature must remain incomplete")
        try expect(mildBoat.rankingValue == coldBoat.rankingValue && FishingScoringService.isBetter(mildBoat, than: coldBoat), "Boat temperature must break equal wave/wind/rain comfort ties")
        try expect(FishingScoringService.isBetter(coldBoat, than: spacedWaves), "Boat wave comfort must still lead the ordering")
        let landLowWaves = evaluate(boatHours, boatWaves(0.5, 8))!
        let landHighWaves = evaluate(boatHours, boatWaves(1, 4))!
        try expect(landLowWaves.rankingValue == landHighWaves.rankingValue && !landHighWaves.warnings.contains { $0.hasPrefix("Short-period waves") }, "New wave comfort policy must not change land ranking")
        try expect(FishingScoringService.daylightFraction(start: at(30, 6), end: at(30, 8), solar: solar, calendar: calendar) == 0.5, "Daylight must cover full session")
        try expect(FishingScoringService.daylightFraction(start: at(30, 23), end: at(30, 23).addingTimeInterval(7200), solar: solar, calendar: calendar) == nil, "Midnight needs next-day solar")
        try landChecks(calendar: calendar)
        print("Passed \(checks) Swift fishing-window regression checks.")
        print("Frozen Raglan: weather 07:00–09:00; late incoming 11:00–13:00; official high 13:23.")
        if CommandLine.arguments.contains("--live") {
            let thamesStation = tideStations.first { $0.id == "thames" }!
            let tomorrow = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: Date()))!
            for boat in [false, true] {
                let windows = try await FishingScoringService().rank(
                    origin: GeoPoint(latitude: thamesStation.latitude, longitude: thamesStation.longitude), radiusKm: 0,
                    selectedStation: thamesStation, days: [tomorrow], boat: boat, preferredHours: nil, priority: .weather)
                print("Live provider validation passed; \(windows.count) Thames \(boat ? "boat" : "land") planning window(s).")
                for window in windows {
                    print("\(window.assessment!.mood.emoji) \(window.assessment!.mood.label) · \(window.assessment!.confidence.label)")
                    for item in window.assessment!.conditions { print("\(item.title): \(item.value) · \(item.mood.emoji) \(item.mood.label)") }
                }
            }
        }
    }

    static func landChecks(calendar: Calendar) throws {
        func at(_ hour: Int) -> Date { calendar.date(from: DateComponents(year: 2026, month: 10, day: 4, hour: hour))! }
        func hour(_ h: Int, wind: Double = 10, gust: Double = 18, rain: Double = 0,
                  temp: Double? = 16, chance: Double? = 80) -> WeatherHour {
            WeatherHour(time: at(h), wind: wind, gust: gust, precipitation: rain, rainProbability: chance, code: 0, feelsLike: temp)
        }
        func hours(wind: Double = 10, gust: Double = 18, rain: Double = 0,
                   temp: Double? = 16, chance: Double? = 80) -> [WeatherHour] {
            (8...10).map { hour($0, wind: wind, gust: gust, rain: rain, temp: temp, chance: chance) }
        }
        let spot = FishingSpot(name: "Shore reference", area: "Test", coordinate: .init(latitude: -37.1, longitude: 175.5), boat: false)
        let solar = [at(0): SolarDay(sunrise: at(7), sunset: at(19))]
        let core = hours()
        func evaluate(_ samples: [WeatherHour] = [], all: [WeatherHour]? = nil,
                      prefs: LandPreferences = LandPreferences(arrivalMinutes: 0, returnMinutes: 0),
                      waves: [Int: MarineHour]? = nil, sourceTime: Date? = nil) -> ScoredFishingWindow? {
            let samples = samples.isEmpty ? core : samples
            let all = all ?? samples
            let waves = waves ?? Dictionary(uniqueKeysWithValues: all.map { (Int($0.time.timeIntervalSince1970), MarineHour(wave: 0.5, period: 8)) })
            return FishingScoringService.evaluate(samples: samples, spot: spot, distanceKm: 0, isTideStation: false,
                solar: solar, marineHours: waves, tides: [], tideStation: nil, now: at(7), calendar: calendar,
                priority: .weather, sourceNote: "Test", land: prefs, allHours: all, retrievedAt: sourceTime ?? at(7))
        }
        let gusty = evaluate(hours(wind: 20, gust: 32))!, lightRain = evaluate(hours(rain: 0.3))!
        try expect(gusty.assessment!.mood.label == "Demanding" && !gusty.assessment!.matchesComfort, "Gusty casting must exceed the default tolerance")
        try expect(lightRain.assessment!.mood.label == "Okay" && FishingScoringService.isBetter(lightRain, than: gusty), "Calm light rain should beat dry gusty weather")
        let cold = evaluate(hours(temp: 4))!, mild = evaluate(hours(wind: 14, gust: 25))!, unknown = evaluate(hours(temp: nil))!
        try expect(cold.assessment!.mood.label == "Uncomfortable" && FishingScoringService.isBetter(mild, than: cold), "Cold must not receive perfect comfort")
        try expect(unknown.assessment!.mood.label == "Needs more data" && !unknown.assessment!.matchesComfort && FishingScoringService.isBetter(cold, than: unknown), "Missing temperature must not improve comfort")
        let burst = [hour(8), hour(9), hour(10, rain: 2)]
        try expect(evaluate(burst)!.assessment!.mood.label == "Uncomfortable", "Wettest hour must not be averaged away")
        try expect(evaluate([hour(8), hour(9), hour(10, temp: 4)])!.assessment!.mood.label == "Uncomfortable", "Final temperature endpoint matters")
        let likely = evaluate(hours(rain: 0.15))!, unlikely = evaluate(hours(rain: 0.15, chance: 20))!, absent = evaluate(hours(rain: 0.15, chance: nil))!
        try expect(likely.rankingValue == unlikely.rankingValue && likely.rankingValue == absent.rankingValue, "Rain likelihood must not count as actual wetness")
        try expect(absent.assessment!.details.contains { $0.contains("Rain likelihood incomplete") }, "Missing likelihood must remain visible")
        let badReturn = core + [hour(11, wind: 60)]
        try expect(evaluate(core, all: badReturn, prefs: LandPreferences(arrivalMinutes: 0, returnMinutes: 60)) == nil, "Return wind can rule out a visit")
        let ordinary = core + [hour(11)]
        let adverse = Dictionary(uniqueKeysWithValues: ordinary.map { (Int($0.time.timeIntervalSince1970), MarineHour(wave: $0.time == at(10) ? 2 : $0.time == at(11) ? 4 : 0.5, period: 8)) })
        try expect(evaluate(core, all: ordinary, prefs: LandPreferences(arrivalMinutes: 0, returnMinutes: 30), waves: adverse) == nil, "Interpolated return waves can rule out a visit")
        let sparse = core + [hour(12)]
        try expect(evaluate(core, all: sparse, prefs: LandPreferences(arrivalMinutes: 0, returnMinutes: 120), waves: adverse) == nil, "Missing weather must not conceal a known adverse marine hour on return")
        let late = (17...20).map { hour($0) }
        try expect(evaluate(Array(late.prefix(3)), all: late, prefs: LandPreferences(arrivalMinutes: 0, returnMinutes: 30, daylightOnly: true)) == nil, "Daylight only must include the return")
        try expect(evaluate(prefs: LandPreferences(daylightOnly: true)) == nil, "Daylight only requires known access and return times")
        try expect(evaluate(prefs: LandPreferences(arrivalMinutes: 0, returnMinutes: 0, daylightOnly: true)) != nil, "Known daylight visit should remain available")
        try expect(evaluate(prefs: LandPreferences(arrivalMinutes: 120)) == nil, "Known access time cannot begin in the past even if return is unset")
        let rocks = evaluate(prefs: LandPreferences(setting: .rocks), sourceTime: at(7).addingTimeInterval(-14400))!
        try expect(rocks.assessment!.checks.label == "Local checks needed" && rocks.assessment!.confidence.label == "Limited confidence", "Unknown local exposure cannot claim supported confidence")
        try expect(rocks.assessment!.details.contains { $0.contains("Rock footing") } && rocks.assessment!.details.contains { $0.contains("older than 3 hours") }, "Site and freshness reasons must be available")
        let unpaired = [Int(at(8).timeIntervalSince1970): MarineHour(wave: 1, period: 8), Int(at(9).timeIntervalSince1970): MarineHour(wave: 0.4, period: 14), Int(at(10).timeIntervalSince1970): MarineHour(wave: 0.4, period: 8)]
        try expect(!evaluate(waves: unpaired)!.warnings.contains { $0.hasPrefix("Long-period waves") }, "Height and period warnings must stay paired")
        var partialBad = unpaired; partialBad[Int(at(10).timeIntervalSince1970)] = MarineHour(wave: 3.4, period: nil)
        try expect(evaluate(waves: partialBad) == nil, "Known adverse height must survive a missing period")
        let normal = evaluate()!, missing = evaluate(waves: [:])!
        try expect(normal.assessment!.mood == missing.assessment!.mood && !FishingScoringService.isBetter(normal, than: missing) && !FishingScoringService.isBetter(missing, than: normal), "Wave completeness must stay separate from land weather comfort")
        try expect(missing.assessment!.details.contains { $0.contains("wave coverage incomplete") }, "Missing wave coverage still needs explanation")
        let dry = evaluate(hours(wind: 14, gust: 25, rain: 0.2))!
        let calmer = evaluate((9...11).map { hour($0, rain: 0.3) })!
        try expect(FishingScoringService.landTradeoff(selected: dry, pool: [dry, calmer])?.0 == "Calmer alternative", "Balanced mode should offer a calmer peer")
        try expect(FishingScoringService.landTradeoff(selected: calmer, pool: [dry, calmer])?.0 == "Drier alternative", "Balanced mode should offer a drier peer")
        try expect(FishingScoringService.landTradeoff(selected: gusty, pool: [gusty, calmer]) == nil, "Trade-offs must respect the comfort limit")
        let casting = evaluate(prefs: LandPreferences(priority: .casting))!
        try expect(FishingScoringService.landTradeoff(selected: casting, pool: [casting, dry, calmer]) == nil, "Explicit casting preference must not get a balanced trade-off")
        let oneBuffer = evaluate(core, all: core + [hour(11, rain: 1)], prefs: LandPreferences(returnMinutes: 30))!
        try expect(oneBuffer.assessment!.rainTotal == 0.5 && oneBuffer.assessment!.conditions.first { $0.title == "Daylight" }!.value == "100% known time", "One known buffer must be included without claiming complete visit timing")
        try expect(oneBuffer.assessment!.conditions.first { $0.title == "Daylight" }!.mood.label == "Visit times needed", "Missing visit timing still needs checking")
    }
}
