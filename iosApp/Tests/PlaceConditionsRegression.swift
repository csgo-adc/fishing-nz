import Foundation

@main struct PlaceConditionsRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }
    static func rejects(_ work: () throws -> Void) throws { var rejected = false; do { try work() } catch { rejected = true }; try expect(rejected, "Invalid provider data accepted") }
    static func main() async throws {
        let root = URL(fileURLWithPath: CommandLine.arguments[1])
        let fixtures = root.appendingPathComponent("app/src/test/resources/conditions")
        let weatherJSON = try Data(contentsOf: fixtures.appendingPathComponent("weather.json"))
        let marineJSON = try Data(contentsOf: fixtures.appendingPathComponent("marine.json"))
        let weather = try PlaceConditionsService.decodeWeather(weatherJSON, fetchedAt: .now)
        let marine = try PlaceConditionsService.decodeMarine(marineJSON, fetchedAt: .now)
        let data = PlaceConditions(weather: weather, marine: marine, weatherIssue: nil, marineIssue: nil)
        let calendar = data.calendar
        let date = calendar.date(from: .init(year: 2026, month: 9, day: 27))!
        let later = calendar.date(byAdding: .day, value: 10, to: date)!
        let point = GeoPoint(latitude: -37.133, longitude: 175.533)
        let pin = ConditionPlace(name: "Dropped pin", point: GeoPoint(latitude: -37.799, longitude: 174.87))
        let searchResult = ConditionPlace(name: "Thames waterfront", point: point)
        try expect(pin.initialTideStation?.id == "raglan", "Dropped pin did not select nearby tides")
        try expect(searchResult.initialTideStation?.id == "thames", "Search result did not select nearby tides")
        try expect(ConditionPlace(name: pin.name, point: point).initialTideStation?.id == "thames", "Changed place kept the previous nearby station")
        let linked = tideStations.first { $0.id == "thames" }!
        try expect(ConditionPlace(name: "Named fishing area", point: pin.point, station: linked).initialTideStation == linked, "Linked tide station was replaced by nearest station")
        let pins = [GeoPoint(latitude: -35.22, longitude: 173.95), GeoPoint(latitude: -36.914, longitude: 175.143),
                    GeoPoint(latitude: -39.12, longitude: 174.0), GeoPoint(latitude: -41.27, longitude: 174.83),
                    GeoPoint(latitude: -43.69, longitude: 173.05), GeoPoint(latitude: -46.42, longitude: 168.37)]
        let places = fishingSpots.map { ConditionPlace(name: $0.name, point: $0.coordinate, station: FishingScoringService.matchingStation(for: $0)) }
            + pins.map { ConditionPlace(name: "Dropped pin", point: $0) }
        try expect(places.allSatisfy { $0.tideCandidates.count == 3 && $0.tideCandidates.first == $0.initialTideStation }, "Every location and pin must have nearby reference candidates")
        try expect(places.allSatisfy { place in
            let sorted = tideStations.sorted { placeDistanceKm(place.point, .init(latitude: $0.latitude, longitude: $0.longitude)) < placeDistanceKm(place.point, .init(latitude: $1.latitude, longitude: $1.longitude)) }
            return place.tideCandidates.first == (place.station ?? sorted.first)
        }, "Automatic references must start with the linked or closest station")
        let waitawa = ConditionPlace(name: "Waitawa Wharf", point: pins[1])
        let fakeTide = TideState(currentLevel: "1.0 m", nextEvent: "High", eventTime: "2:00 PM", events: [], points: [], stationName: "")
        var attempted: [TideStation] = []
        let fallback = try await loadPlaceTide(place: waitawa, selected: nil, date: date) { station, requested in
            try expect(requested == date, "Fallback changed the requested date")
            attempted.append(station)
            if station == waitawa.tideCandidates.first { throw TideDataError.noPredictions }
            return fakeTide
        }
        try expect(attempted == Array(waitawa.tideCandidates.prefix(2)) && fallback.station == waitawa.tideCandidates[1], "Unavailable nearest station did not try the next nearby source")
        attempted = []
        var manualFailed = false
        do {
            _ = try await loadPlaceTide(place: waitawa, selected: linked, date: date) { station, _ in
                attempted.append(station); throw TideDataError.noPredictions
            }
        } catch { manualFailed = true }
        try expect(manualFailed && attempted == [linked], "Manual choices must remain explicit")
        attempted = []
        var autoFailed = false
        do {
            _ = try await loadPlaceTide(place: waitawa, selected: nil, date: date) { station, _ in
                attempted.append(station); throw TideDataError.noPredictions
            }
        } catch { autoFailed = true }
        try expect(autoFailed && attempted == waitawa.tideCandidates, "Automatic failure must be bounded and must not fabricate data")
        attempted = []
        var cancelled = false
        do {
            _ = try await loadPlaceTide(place: waitawa, selected: nil, date: date) { station, _ in
                attempted.append(station); throw CancellationError()
            }
        } catch is CancellationError { cancelled = true }
        try expect(cancelled && attempted.count == 1, "Cancellation must stop nearby retries")
        if CommandLine.arguments.contains("--audit-tides") {
            let tideDay = LINZTideStore.calendar.startOfDay(for: .now)
            let store = LINZTideStore()
            var available = Set<String>()
            var failures: [String] = []
            for first in stride(from: 0, to: tideStations.count, by: 6) {
                let stations = Array(tideStations[first..<min(first + 6, tideStations.count)])
                let results = await withTaskGroup(of: (String, Bool, String).self) { group in
                    for station in stations {
                        group.addTask {
                            do {
                                let predictions = try await store.predictions(stationName: station.csvName, start: tideDay, end: tideDay)
                                let valid = predictions.contains { LINZTideStore.calendar.isDate($0.time, inSameDayAs: tideDay) }
                                return (station.id, valid, valid ? "" : "No events on requested day")
                            } catch { return (station.id, false, String(describing: error)) }
                        }
                    }
                    var values: [(String, Bool, String)] = []
                    for await result in group { values.append(result) }
                    return values
                }
                for (id, valid, error) in results { if valid { available.insert(id) } else { failures.append("\(id): \(error)") } }
            }
            let uncovered = places.filter { !$0.tideCandidates.contains { available.contains($0.id) } }
            let defaultsUnavailable = places.filter { !available.contains($0.initialTideStation!.id) }
            let report = "Live tide audit: \(available.count)/\(tideStations.count) stations verified for \(LINZTideStore.calendar.dateComponents([.year, .month, .day], from: tideDay)).\n" +
                "Map coverage: \(places.count - uncovered.count)/\(places.count) places (\(fishingSpots.count) catalogue entries and \(pins.count) dropped pins); \(defaultsUnavailable.count) require a nearby fallback.\n" +
                (failures.isEmpty ? "" : "Unavailable stations: \(failures.sorted().joined(separator: ", "))\n")
            FileHandle.standardOutput.write(Data(report.utf8))
            try expect(uncovered.isEmpty, "Map locations without any valid nearby tide data: \(uncovered.map(\.name))")
            try expect(failures.isEmpty, "Live LINZ tide stations failed verification")
        }
        func query(_ url: URL, _ name: String) -> String? { URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value }
        try expect(query(PlaceConditionsService.weatherURL(point, pastDays: 92), "forecast_days") == "16", "Weather horizon")
        try expect(query(PlaceConditionsService.marineURL(point, pastDays: 92), "forecast_days") == "8", "Marine horizon")
        try expect(query(PlaceConditionsService.marineURL(point, pastDays: 92), "past_days") == "92", "History horizon")
        try expect(query(PlaceConditionsService.marineURL(point, pastDays: 3), "cell_selection") == "sea", "Offshore grid selection")
        try expect(data.dates.count == 17, "Actual returned dates")
        try expect(data.weatherHours(date).count == 23 && data.marineHours(date).count == 23, "NZ DST day must be 23 hours")
        try expect(data.rows(date).first?.mood.label == "More motion", "Complete DST day was treated as incomplete")
        try expect(data.rows(date).first { $0.title == "Daylight" }?.value == "6:30 AM–7:30 PM", "Sunrise timezone applied twice")
        try expect(weather.hours.last?.chance == nil && weather.days.last?.chance == nil, "Missing rain chance became zero")
        try expect(data.marineHours(later).isEmpty && data.rows(later).first?.mood == needsDataMood, "Waves extended past returned data")
        func changed(_ source: Data, section: String, field: String, value: Any) throws -> Data {
            var root = try JSONSerialization.jsonObject(with: source) as! [String: Any]
            var values = root[section] as! [String: Any]; values[field] = value; root[section] = values
            return try JSONSerialization.data(withJSONObject: root)
        }
        try rejects { _ = try PlaceConditionsService.decodeMarine(changed(marineJSON, section: "hourly_units", field: "wave_height", value: "ft"), fetchedAt: .now) }
        try rejects { _ = try PlaceConditionsService.decodeMarine(changed(marineJSON, section: "hourly", field: "wave_period", value: 8), fetchedAt: .now) }
        try rejects { _ = try PlaceConditionsService.decodeWeather(changed(weatherJSON, section: "hourly", field: "temperature_2m", value: [17]), fetchedAt: .now) }
        let weatherOnly = PlaceConditions(weather: weather, marine: nil, weatherIssue: nil, marineIssue: "Unavailable")
        let seaOnly = PlaceConditions(weather: nil, marine: marine, weatherIssue: "Unavailable", marineIssue: nil)
        try expect(weatherOnly.dates.count == 17 && weatherOnly.rows(date).first?.mood == needsDataMood, "Weather fallback")
        try expect(seaOnly.dates.count == 2 && seaOnly.hourDates(date).count == 23 && seaOnly.rows(date).first?.mood.label == "More motion", "Marine fallback")
        let last = marine.hours.keys.max()!
        let partial = PlaceMarine(hours: [last: .init(height: 2.5, period: nil, direction: nil, swell: nil, swellPeriod: nil, waterTemperature: nil)], zone: marine.zone, fetchedAt: marine.fetchedAt, grid: marine.grid)
        try expect(PlaceConditions(weather: weather, marine: partial, weatherIssue: nil, marineIssue: nil).rows(date).first?.mood.label == "High waves", "Known high waves hidden by missing period")
        func paired(_ height: Double) -> PlaceConditions {
            var hours = marine.hours.mapValues { _ in PlaceMarineHour(height: 0.7, period: 9, direction: nil, swell: nil, swellPeriod: nil, waterTemperature: nil) }
            hours[last] = .init(height: height, period: 4, direction: nil, swell: nil, swellPeriod: nil, waterTemperature: nil)
            return .init(weather: weather, marine: .init(hours: hours, zone: marine.zone, fetchedAt: marine.fetchedAt, grid: marine.grid), weatherIssue: nil, marineIssue: nil)
        }
        try expect(paired(0.1).rows(date).first?.mood.label == "More motion", "Unpaired short period wrongly marked choppy")
        try expect(paired(0.7).rows(date).first?.mood.label == "Choppy", "Paired chop missing")
        try expect(WeatherPresentation.label(61) == "Light rain" && WeatherPresentation.label(65) == "Heavy rain", "Rain intensity labels")
        try expect(WeatherPresentation.label(55) == "Heavy drizzle" && WeatherPresentation.label(99) == "Thunderstorms with hail", "Specific weather labels")
        try expect(WeatherPresentation.label(nil) == "Weather unavailable" && WeatherPresentation.label(64) == "Weather unavailable" && WeatherPresentation.symbol(64) == "questionmark.circle", "Unknown weather labels")
        try expect(WeatherPresentation.symbol(61) == "cloud.rain.fill" && WeatherPresentation.symbol(51) == "cloud.rain.fill", "Recognisable rain symbols")
        try expect(WeatherPresentation.symbol(2, isDay: false) == "cloud.moon.fill" && WeatherPresentation.symbol(45) == "cloud.fog.fill", "Night and fog symbols")
        let pageData = try Data(contentsOf: fixtures.appendingPathComponent("weather-page.json"))
        let page = try WeatherForecastService.decode(pageData)
        try expect(page.days.count == 17 && page.days.last?.rainChance == nil && page.hours.last?.rainChance == nil && page.days.last?.high == nil && page.days.last?.windMax == nil && page.days.last?.code == nil && page.hours.last?.temperature == nil && page.hours.last?.wind == nil && page.hours.last?.code == nil, "Weather page lost extended dates or missing chance")
        try expect(page.hours.first?.isDay == true && page.hours.last?.isDay == false, "Weather page guessed daylight")
        if CommandLine.arguments.contains("--live") || CommandLine.arguments.contains("--live-history") {
            let history = CommandLine.arguments.contains("--live-history") ? 92 : 3
            let page = try await WeatherForecastService().load(at: point)
            try expect(page.days.count == 16 && page.hours.count == 24, "Live Weather page horizon")
            let live = try await PlaceConditionsService().load(at: point, pastDays: history)
            try expect(live.weather != nil && live.marine != nil, "Live Thames weather or marine decoder failed")
            let today = live.calendar.startOfDay(for: .now)
            let futureWeather = live.weather!.days.filter { $0.at >= today }
            let previousWeather = live.weather!.days.filter { $0.at < today }
            let futureMarine = Set(live.marine!.hours.keys.filter { $0 >= today }.map { live.calendar.startOfDay(for: $0) })
            try expect(futureWeather.count == 16 && previousWeather.count == history, "Live weather dates differ from provider horizon")
            try expect(futureMarine.count == 8, "Live marine dates differ from provider horizon")
            print("Live Thames: \(futureWeather.count) upcoming weather days, \(previousWeather.count) recent days, \(futureMarine.count) upcoming marine days.")
            print("Weather grid \(live.weather!.grid); marine grid \(live.marine!.grid).")
            print(live.rows(today).map { "\($0.title): \($0.value) · \($0.mood.emoji) \($0.mood.label)" }.joined(separator: "\n"))
        }
        print("\(checks) place-condition checks passed.")
    }
}
