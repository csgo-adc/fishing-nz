import Foundation

struct ConditionPlace: Identifiable, Sendable {
    let name: String
    let point: GeoPoint
    var region = "Selected place"
    var boat = false
    var station: TideStation? = nil
    var id: String { "\(name):\(point.latitude):\(point.longitude)" }
    var initialTideStation: TideStation? {
        station ?? tideStations.min {
            placeDistanceKm(point, GeoPoint(latitude: $0.latitude, longitude: $0.longitude)) <
                placeDistanceKm(point, GeoPoint(latitude: $1.latitude, longitude: $1.longitude))
        }
    }
    var tideCandidates: [TideStation] { nearbyTideStations(point: point, linked: station) }
}

struct PlaceTide { let station: TideStation; let tide: TideState }

/// Automatic references try up to three nearest stations; a manual choice remains explicit.
func loadPlaceTide(place: ConditionPlace, selected: TideStation?, date: Date,
                   isolation: isolated (any Actor)? = #isolation,
                   load: (TideStation, Date) async throws -> TideState) async throws -> PlaceTide {
    let reference = try await loadTideReference(point: place.point, linked: place.station, selected: selected) { try await load($0, date) }
    return PlaceTide(station: reference.station, tide: reference.value)
}
struct PlaceWeatherHour: Sendable, Identifiable {
    let at: Date
    let temperature: Double?, feelsLike: Double?, wind: Double?, gust: Double?, direction: Double?
    let rain: Double?, chance: Double?, code: Int?, isDay: Bool?, visibility: Double?
    var id: Date { at }
}
struct PlaceWeatherDay: Sendable, Identifiable {
    let at: Date
    let code: Int?, high: Double?, low: Double?, feelsHigh: Double?, feelsLow: Double?
    let wind: Double?, gust: Double?, direction: Double?, rain: Double?, chance: Double?
    let sunrise: Date?, sunset: Date?, uv: Double?
    var id: Date { at }
}
struct PlaceMarineHour: Sendable {
    let height: Double?, period: Double?, direction: Double?, swell: Double?, swellPeriod: Double?, waterTemperature: Double?
}
struct PlaceWeather: Sendable {
    let hours: [PlaceWeatherHour], days: [PlaceWeatherDay], zone: TimeZone, fetchedAt: Date, grid: String
}
struct PlaceMarine: Sendable {
    let hours: [Date: PlaceMarineHour], zone: TimeZone, fetchedAt: Date, grid: String
    var gridPoint: GeoPoint? = nil
}
struct PlaceConditions: Sendable {
    let weather: PlaceWeather?, marine: PlaceMarine?, weatherIssue: String?, marineIssue: String?
    var zone: TimeZone { weather?.zone ?? marine?.zone ?? TimeZone(identifier: "Pacific/Auckland")! }
    var calendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; return c }
    var dates: [Date] { Array(Set((weather?.days.map(\.at) ?? []) + (marine?.hours.keys.map { calendar.startOfDay(for: $0) } ?? []))).sorted() }
    func weatherHours(_ day: Date) -> [PlaceWeatherHour] { weather?.hours.filter { calendar.isDate($0.at, inSameDayAs: day) } ?? [] }
    func marineHours(_ day: Date) -> [PlaceMarineHour] { marine?.hours.filter { calendar.isDate($0.key, inSameDayAs: day) }.map(\.value) ?? [] }
    func hourDates(_ day: Date) -> [Date] { Array(Set(weatherHours(day).map(\.at) + (marine?.hours.keys.filter { calendar.isDate($0, inSameDayAs: day) } ?? []))).sorted() }
    func rows(_ day: Date) -> [ConditionItem] { PlaceConditionRows.make(self, date: day) }
    func details(_ day: Date, title: String) -> [String] { PlaceConditionRows.details(self, date: day, title: title) }
}

enum WeatherPresentation {
    static func rainDrops(_ code: Int?) -> Int {
        switch code {
        case 51, 56, 61, 66, 80: 1
        case 53, 63, 81: 2
        case 55, 57, 65, 67, 82: 3
        default: 0
        }
    }
    static func isDrizzle(_ code: Int?) -> Bool { [51, 53, 55, 56, 57].contains(code ?? -1) }
    static func label(_ code: Int?) -> String {
        guard let code else { return "Weather unavailable" }
        return switch code {
        case 0: "Clear"
        case 1: "Mostly clear"
        case 2: "Partly cloudy"
        case 3: "Cloudy"
        case 45, 48: "Fog"
        case 51: "Light drizzle"
        case 53: "Moderate drizzle"
        case 55: "Heavy drizzle"
        case 56: "Light freezing drizzle"
        case 57: "Heavy freezing drizzle"
        case 61: "Light rain"
        case 63: "Moderate rain"
        case 65: "Heavy rain"
        case 66: "Light freezing rain"
        case 67: "Heavy freezing rain"
        case 71: "Light snow"
        case 73, 77: "Snow"
        case 75: "Heavy snow"
        case 80: "Light showers"
        case 81: "Moderate showers"
        case 82: "Heavy showers"
        case 85, 86: "Snow showers"
        case 95: "Thunderstorms"
        case 97: "Heavy thunderstorms"
        case 96, 99: "Thunderstorms with hail"
        default: "Weather unavailable"
        }
    }
    static func symbol(_ code: Int?, isDay: Bool = true) -> String {
        guard let code else { return "questionmark.circle" }
        return switch code {
        case 0, 1: isDay ? "sun.max.fill" : "moon.stars.fill"
        case 2: isDay ? "cloud.sun.fill" : "cloud.moon.fill"
        case 3: "cloud.fill"
        case 45, 48: "cloud.fog.fill"
        case 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82: "cloud.rain.fill"
        case 71, 73, 75, 77, 85, 86: "cloud.snow.fill"
        case 95, 96, 97, 99: "cloud.bolt.rain.fill"
        default: "questionmark.circle"
        }
    }
}

struct PlaceConditionsService: Sendable {
    static let weatherDays = 16, marineDays = 8, maxPastDays = 92
    func load(at point: GeoPoint, pastDays: Int = 3) async throws -> PlaceConditions {
        guard point.latitude.isFinite, point.longitude.isFinite, (-90...90).contains(point.latitude), (-180...180).contains(point.longitude), (0...Self.maxPastDays).contains(pastDays) else { throw ConditionsError.invalidData }
        async let weather = attempt { try Self.decodeWeather(await fetch(Self.weatherURL(point, pastDays: pastDays)), fetchedAt: .now) }
        async let marine = attempt { try Self.decodeMarine(await fetch(Self.marineURL(point, pastDays: pastDays)), fetchedAt: .now) }
        let (w, m) = try await (weather, marine)
        try Task.checkCancellation()
        return PlaceConditions(weather: w, marine: m, weatherIssue: w == nil ? "Weather unavailable · try refreshing" : nil,
            marineIssue: m == nil ? "Offshore waves unavailable · try refreshing" : nil)
    }
    private func attempt<T: Sendable>(_ work: @Sendable () async throws -> T) async throws -> T? {
        do { return try await work() } catch is CancellationError { throw CancellationError() } catch { try Task.checkCancellation(); return nil }
    }
    private func fetch(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url); request.timeoutInterval = 20
        let (data, response) = try await URLSession.shared.data(for: request)
        guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0) else { throw URLError(.badServerResponse) }
        return data
    }
    static func weatherURL(_ point: GeoPoint, pastDays: Int) -> URL {
        url("api.open-meteo.com", "/v1/forecast", point, pastDays, weatherDays, [
            .init(name: "hourly", value: "temperature_2m,apparent_temperature,wind_speed_10m,wind_gusts_10m,wind_direction_10m,precipitation,precipitation_probability,weather_code,is_day,visibility"),
            .init(name: "daily", value: "weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,precipitation_sum,precipitation_probability_max,sunrise,sunset,uv_index_max"),
            .init(name: "wind_speed_unit", value: "kmh"), .init(name: "temperature_unit", value: "celsius"), .init(name: "precipitation_unit", value: "mm")])
    }
    static func marineURL(_ point: GeoPoint, pastDays: Int) -> URL {
        url("marine-api.open-meteo.com", "/v1/marine", point, pastDays, marineDays, [
            .init(name: "hourly", value: "wave_height,wave_period,wave_direction,swell_wave_height,swell_wave_period,sea_surface_temperature"),
            .init(name: "length_unit", value: "metric"), .init(name: "cell_selection", value: "sea")])
    }
    private static func url(_ host: String, _ path: String, _ point: GeoPoint, _ past: Int, _ days: Int, _ items: [URLQueryItem]) -> URL {
        var c = URLComponents(); c.scheme = "https"; c.host = host; c.path = path
        c.queryItems = [.init(name: "latitude", value: String(point.latitude)), .init(name: "longitude", value: String(point.longitude)),
            .init(name: "forecast_days", value: String(days)), .init(name: "past_days", value: String(past)),
            .init(name: "timezone", value: "auto"), .init(name: "timeformat", value: "unixtime")] + items
        return c.url!
    }
    static func decodeWeather(_ data: Data, fetchedAt: Date) throws -> PlaceWeather {
        let root = try object(data)
        guard let zone = TimeZone(identifier: root["timezone"] as? String ?? "") else { throw ConditionsError.invalidData }
        let h = try Series(root, "hourly", ["temperature_2m": "°C", "apparent_temperature": "°C", "wind_speed_10m": "km/h", "wind_gusts_10m": "km/h",
            "wind_direction_10m": "°", "precipitation": "mm", "precipitation_probability": "%", "weather_code": "wmo code", "is_day": "", "visibility": "m"])
        let hours = h.times.indices.map { i in PlaceWeatherHour(at: h.times[i], temperature: h.number("temperature_2m", i, -100...80), feelsLike: h.number("apparent_temperature", i, -100...80),
            wind: h.number("wind_speed_10m", i), gust: h.number("wind_gusts_10m", i), direction: h.number("wind_direction_10m", i, 0...360),
            rain: h.number("precipitation", i), chance: h.number("precipitation_probability", i, 0...100), code: h.integer("weather_code", i, 0...99),
            isDay: h.integer("is_day", i, 0...1).map { $0 == 1 }, visibility: h.number("visibility", i)) }
        let d = try Series(root, "daily", ["weather_code": "wmo code", "temperature_2m_max": "°C", "temperature_2m_min": "°C", "apparent_temperature_max": "°C", "apparent_temperature_min": "°C",
            "wind_speed_10m_max": "km/h", "wind_gusts_10m_max": "km/h", "wind_direction_10m_dominant": "°", "precipitation_sum": "mm", "precipitation_probability_max": "%",
            "sunrise": "unixtime", "sunset": "unixtime", "uv_index_max": ""])
        var calendar = Calendar(identifier: .gregorian); calendar.timeZone = zone
        let days = d.times.indices.map { i in PlaceWeatherDay(at: calendar.startOfDay(for: d.times[i]), code: d.integer("weather_code", i, 0...99),
            high: d.number("temperature_2m_max", i, -100...80), low: d.number("temperature_2m_min", i, -100...80),
            feelsHigh: d.number("apparent_temperature_max", i, -100...80), feelsLow: d.number("apparent_temperature_min", i, -100...80),
            wind: d.number("wind_speed_10m_max", i), gust: d.number("wind_gusts_10m_max", i), direction: d.number("wind_direction_10m_dominant", i, 0...360),
            rain: d.number("precipitation_sum", i), chance: d.number("precipitation_probability_max", i, 0...100), sunrise: d.instant("sunrise", i), sunset: d.instant("sunset", i), uv: d.number("uv_index_max", i)) }
        guard Set(days.map(\.at)).count == days.count else { throw ConditionsError.invalidData }
        return PlaceWeather(hours: hours, days: days, zone: zone, fetchedAt: fetchedAt, grid: try grid(root))
    }
    static func decodeMarine(_ data: Data, fetchedAt: Date) throws -> PlaceMarine {
        let root = try object(data)
        guard let zone = TimeZone(identifier: root["timezone"] as? String ?? "") else { throw ConditionsError.invalidData }
        let h = try Series(root, "hourly", ["wave_height": "m", "wave_period": "s", "wave_direction": "°", "swell_wave_height": "m", "swell_wave_period": "s", "sea_surface_temperature": "°C"])
        let hours = h.times.indices.map { i in (h.times[i], PlaceMarineHour(height: h.number("wave_height", i), period: h.number("wave_period", i).flatMap { $0 > 0 ? $0 : nil },
            direction: h.number("wave_direction", i, 0...360), swell: h.number("swell_wave_height", i), swellPeriod: h.number("swell_wave_period", i).flatMap { $0 > 0 ? $0 : nil }, waterTemperature: h.number("sea_surface_temperature", i, -5...50))) }
        return PlaceMarine(hours: Dictionary(uniqueKeysWithValues: hours), zone: zone, fetchedAt: fetchedAt, grid: try grid(root), gridPoint: GeoPoint(latitude: root["latitude"] as! Double, longitude: root["longitude"] as! Double))
    }
    private static func object(_ data: Data) throws -> [String: Any] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ConditionsError.invalidData }; return root
    }
    private static func grid(_ root: [String: Any]) throws -> String {
        guard let lat = root["latitude"] as? Double, let lon = root["longitude"] as? Double, lat.isFinite, lon.isFinite, (-90...90).contains(lat), (-180...180).contains(lon) else { throw ConditionsError.invalidData }
        return "\(lat), \(lon)"
    }
    private struct Series {
        let data: [String: Any], times: [Date]
        init(_ root: [String: Any], _ name: String, _ fields: [String: String]) throws {
            guard let data = root[name] as? [String: Any], let units = root[name + "_units"] as? [String: String], units["time"] == "unixtime", let stamps = data["time"] as? [Double], !stamps.isEmpty,
                stamps.allSatisfy({ $0.isFinite && $0.rounded() == $0 }), zip(stamps, stamps.dropFirst()).allSatisfy({ $1 > $0 }) else { throw ConditionsError.invalidData }
            self.data = data; times = stamps.map { Date(timeIntervalSince1970: $0) }
            for (field, unit) in fields {
                if let raw = data[field], !(raw is NSNull) {
                    guard let array = raw as? [Any], array.count == times.count, units[field] == unit else { throw ConditionsError.invalidData }
                }
            }
        }
        func number(_ name: String, _ i: Int, _ limits: ClosedRange<Double> = 0...Double.greatestFiniteMagnitude) -> Double? {
            guard let array = data[name] as? [Any], let value = array[i] as? Double, value.isFinite, limits.contains(value) else { return nil }; return value
        }
        func integer(_ name: String, _ i: Int, _ limits: ClosedRange<Int>) -> Int? {
            guard let value = number(name, i), value.rounded() == value, value >= Double(limits.lowerBound), value <= Double(limits.upperBound) else { return nil }; return Int(value)
        }
        func instant(_ name: String, _ i: Int) -> Date? { number(name, i).flatMap { $0.rounded() == $0 ? Date(timeIntervalSince1970: $0) : nil } }
    }
}
private enum ConditionsError: Error { case invalidData }

enum PlaceConditionRows {
    static func details(_ data: PlaceConditions, date: Date, title: String) -> [String] {
        let wettest = data.weatherHours(date).filter { ($0.rain ?? 0) > 0 }.max { ($0.rain ?? 0) < ($1.rain ?? 0) }
        let clock = DateFormatter(); clock.locale = Locale(identifier: "en_NZ"); clock.timeZone = data.zone; clock.dateFormat = "h:mm a"
        let rainPeak = wettest.map { "Wettest hour ends \(clock.string(from: $0.at)) · \(number($0.rain, 1)) mm." }
        return switch title {
        case "Offshore waves": ["Height is the largest significant wave height; individual waves can be higher. Period is the time between waves.", "🚤 Boat: short periods can make the ride choppy; longer swells can cause rolling.", "🎣 Shore: check breaking waves, shelter and swell direction. Offshore height does not describe waves at your feet."]
        case "Wind": ["Max is the strongest sustained wind for the day. Gust is a brief stronger burst. Direction shows where wind comes from.", "🎣 Shore: headwinds make casting harder. 🚤 Boat: wind can roughen the water and push the boat."]
        case "Rain": [rainPeak, "mm is the day's precipitation total. % is the highest hourly chance, not a whole-day chance.", "Rain drops on weather icons: 1 light · 2 moderate · 3 heavy. Smaller drops mean drizzle.", "Wet clothes, slippery ground and a wet deck can make fishing uncomfortable."].compactMap { $0 }
        case "Feels like": ["The range includes overnight hours and accounts for wind, humidity and sunshine.", "Check the hourly values for your visit. Wet clothes can make you feel colder."]
        case "Daylight": ["Sunrise–sunset in local time.", "Allow time to walk back or return to the ramp before dark."]
        case "UV": ["The day's peak UV index. Protection is useful from UV 3, even when it feels cool.", "Shade, sunscreen, a hat and sunglasses help on shore and on the water."]
        case "Visibility": ["The lowest model visibility for the day.", "Fog or rain can hide landmarks and other boats. Check your visit's hours."]
        case "Water temperature": ["Model temperature at the sea surface, not a measurement at this pin.", "It can help compare days; it does not measure water clarity or fish activity."]
        case "Offshore swell": ["Swell travels from weather farther away. The period is the time between swells.", "Check swell direction and local exposure; calm wind does not guarantee calm water."]
        default: []
        }
    }
    static func number(_ value: Double?, _ decimals: Int = 0) -> String { value.map { String(format: "%.*f", decimals, $0) } ?? "—" }
    static func range(_ values: [Double?], _ decimals: Int = 0) -> String {
        let known = values.compactMap { $0 }; guard let lo = known.min(), let hi = known.max() else { return "—" }
        let a = number(lo, decimals), b = number(hi, decimals); return a == b ? a : a + "–" + b
    }
    static func direction(_ value: Double?) -> String { value.map { ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int(($0/45).rounded()) % 8] } ?? "—" }
    static func make(_ data: PlaceConditions, date: Date) -> [ConditionItem] {
        let day = data.weather?.days.first { data.calendar.isDate($0.at, inSameDayAs: date) }
        let hours = data.weatherHours(date), sea = data.marineHours(date)
        let heights = sea.map(\.height), periods = sea.map(\.period), maxWave = heights.compactMap { $0 }.max()
        let start = data.calendar.startOfDay(for: date), end = data.calendar.date(byAdding: .day, value: 1, to: start)!
        let completeSea = sea.count == Int(end.timeIntervalSince(start)/3600) && heights.allSatisfy { $0 != nil } && periods.allSatisfy { $0 != nil }
        let seaFeeling: WindowMood
        if let maxWave, maxWave >= 2 { seaFeeling = .init(emoji: "🌊", label: "High waves") }
        else if !completeSea { seaFeeling = needsDataMood }
        else if sea.contains(where: { ($0.height ?? 0) >= 0.5 && ($0.period ?? 99) <= 5 }) { seaFeeling = .init(emoji: "🌊", label: "Choppy") }
        else { seaFeeling = .init(emoji: "🌊", label: (maxWave ?? 0) <= 0.5 ? "Lower waves" : "More motion") }
        let wind = ConditionItem(title: "Wind", value: "max \(number(day?.wind)) km/h · gust \(number(day?.gust)) · \(direction(day?.direction))", mood: day?.wind == nil || day?.gust == nil ? needsDataMood : comfortMood(LandAssessment.windBand(day!.wind!, day!.gust!)))
        let waves = ConditionItem(title: "Offshore waves", value: maxWave.map { "max \(number($0, 1)) m · \(range(periods, 1)) s" } ?? "—", mood: seaFeeling)
        let rain = ConditionItem(title: "Rain", value: "\(number(day?.rain, 1)) mm · \(number(day?.chance))% hourly max", mood: day?.rain == nil ? needsDataMood : .init(emoji: day!.rain! > 0 ? "☔" : "🌤️", label: day!.rain! > 3 ? "Wet day" : day!.rain! > 0 ? "Some rain" : (day?.chance ?? 0) >= 60 ? "Rain possible" : "Mostly dry"))
        let feels = ConditionItem(title: "Feels like", value: "\(range([day?.feelsLow, day?.feelsHigh]))°C", mood: day?.feelsLow == nil || day?.feelsHigh == nil ? needsDataMood : .init(emoji: day!.feelsLow! < 12 ? "🥶" : day!.feelsHigh! > 26 ? "🥵" : "😌", label: day!.feelsLow! < 12 ? "Cold at times" : day!.feelsHigh! > 26 ? "Hot at times" : "Mild"))
        let f = DateFormatter(); f.locale = Locale(identifier: "en_NZ"); f.timeZone = data.zone; f.dateFormat = "h:mm a"
        let light = ConditionItem(title: "Daylight", value: day?.sunrise == nil || day?.sunset == nil ? "—" : "\(f.string(from: day!.sunrise!))–\(f.string(from: day!.sunset!))", mood: day?.sunrise == nil || day?.sunset == nil ? needsDataMood : .init(emoji: "🌞", label: "Plan your return"))
        let visibility = hours.compactMap(\.visibility).min()
        let uv = ConditionItem(title: "UV", value: number(day?.uv, 1), mood: day?.uv == nil ? needsDataMood : .init(emoji: "☀️", label: day!.uv! >= 3 ? "Sun protection" : "Lower UV"))
        let view = ConditionItem(title: "Visibility", value: visibility.map { "min \(number($0/1000, 1)) km" } ?? "—", mood: visibility == nil ? needsDataMood : .init(emoji: visibility! < 1000 ? "🌫️" : "👀", label: visibility! < 1000 ? "Poor at times" : "Check visibility"))
        let water = sea.compactMap(\.waterTemperature)
        let waterRow = ConditionItem(title: "Water temperature", value: water.isEmpty ? "—" : "\(range(water, 1))°C", mood: water.isEmpty ? needsDataMood : .init(emoji: "🌡️", label: "Offshore model"))
        let swells = sea.map(\.swell)
        let swell = ConditionItem(title: "Offshore swell", value: swells.compactMap { $0 }.max().map { "max \(number($0, 1)) m · \(range(sea.map(\.swellPeriod), 1)) s" } ?? "—",
            mood: swells.isEmpty || swells.contains(where: { $0 == nil }) || sea.contains(where: { $0.swellPeriod == nil }) ? needsDataMood : .init(emoji: "🌊", label: "Offshore model"))
        return [waves, wind] + [rain, feels, light, uv, view, waterRow, swell]
    }
}
