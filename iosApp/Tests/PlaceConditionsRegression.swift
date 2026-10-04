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
