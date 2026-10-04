import Foundation

struct WeatherSnapshot: Sendable {
    struct Current: Sendable {
        let at: Date
        let temperature: Double
        let feelsLike: Double
        let humidity: Int
        let rain: Double
        let wind: Double
        let gusts: Double
        let windDirection: Int
        let code: Int
        let isDay: Bool
    }

    struct Hour: Sendable, Identifiable {
        let at: Date
        let temperature: Double?
        let rainChance: Int?
        let wind: Double?
        let code: Int?
        let isDay: Bool?
        var id: Date { at }
    }

    struct Day: Sendable, Identifiable {
        let at: Date
        let high: Double?
        let low: Double?
        let rainChance: Int?
        let windMax: Double?
        let code: Int?
        var id: Date { at }
    }

    let current: Current
    let hours: [Hour]
    let days: [Day]
    let timeZone: TimeZone
    let fetchedAt: Date
}

struct WeatherForecastService {
    func load(at point: GeoPoint) async throws -> WeatherSnapshot {
        var components = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        components.queryItems = [
            URLQueryItem(name: "latitude", value: String(point.latitude)),
            URLQueryItem(name: "longitude", value: String(point.longitude)),
            URLQueryItem(name: "current", value: "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,wind_speed_10m,wind_gusts_10m,wind_direction_10m,weather_code,is_day"),
            URLQueryItem(name: "hourly", value: "temperature_2m,precipitation_probability,wind_speed_10m,weather_code,is_day"),
            URLQueryItem(name: "daily", value: "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max"),
            URLQueryItem(name: "forecast_days", value: "16"),
            URLQueryItem(name: "timezone", value: "auto"),
            URLQueryItem(name: "wind_speed_unit", value: "kmh"),
            URLQueryItem(name: "temperature_unit", value: "celsius"),
            URLQueryItem(name: "precipitation_unit", value: "mm")
        ]
        guard let url = components.url else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.timeoutInterval = 15
        let (data, response) = try await URLSession.shared.data(for: request)
        guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0) else { throw URLError(.badServerResponse) }
        return try Self.decode(data)
    }

    static func decode(_ data: Data, fetchedAt: Date = .now) throws -> WeatherSnapshot {
        let response = try JSONDecoder().decode(ForecastResponse.self, from: data)
        guard let zone = TimeZone(identifier: response.timezone) else { throw URLError(.cannotParseResponse) }
        let localTime = DateFormatter()
        localTime.locale = Locale(identifier: "en_US_POSIX")
        localTime.timeZone = zone
        localTime.dateFormat = "yyyy-MM-dd'T'HH:mm"
        let localDate = DateFormatter()
        localDate.locale = Locale(identifier: "en_US_POSIX")
        localDate.timeZone = zone
        localDate.dateFormat = "yyyy-MM-dd"
        guard let currentTime = localTime.date(from: response.current.time) else { throw URLError(.cannotParseResponse) }
        let now = response.current
        let current = WeatherSnapshot.Current(at: currentTime, temperature: now.temperature,
            feelsLike: now.feelsLike, humidity: now.humidity, rain: now.rain, wind: now.wind,
            gusts: now.gusts, windDirection: now.windDirection, code: now.code, isDay: now.isDay == 1)

        let hourly = response.hourly
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let currentHour = calendar.dateInterval(of: .hour, for: currentTime)?.start ?? currentTime
        let hourCount = [hourly.time.count, hourly.temperature.count, hourly.rainChance.count,
                         hourly.wind.count, hourly.code.count].min() ?? 0
        let hours = (0..<hourCount).compactMap { index -> WeatherSnapshot.Hour? in
            guard let time = localTime.date(from: hourly.time[index]), time >= currentHour else { return nil }
            return .init(at: time, temperature: hourly.temperature[index], rainChance: hourly.rainChance[index],
                         wind: hourly.wind[index], code: hourly.code[index], isDay: hourly.isDay.flatMap { index < $0.count ? $0[index].map { $0 == 1 } : nil })
        }.prefix(24)

        let daily = response.daily
        let dayCount = [daily.time.count, daily.high.count, daily.low.count,
                        daily.rainChance.count, daily.windMax.count, daily.code.count].min() ?? 0
        let days = (0..<dayCount).compactMap { index -> WeatherSnapshot.Day? in
            guard let date = localDate.date(from: daily.time[index]) else { return nil }
            return .init(at: date, high: daily.high[index], low: daily.low[index],
                         rainChance: daily.rainChance[index], windMax: daily.windMax[index], code: daily.code[index])
        }
        guard !days.isEmpty else { throw URLError(.cannotParseResponse) }
        return WeatherSnapshot(current: current, hours: Array(hours), days: days, timeZone: zone, fetchedAt: fetchedAt)
    }
}

private struct ForecastResponse: Decodable {
    let timezone: String
    let current: Current
    let hourly: Hourly
    let daily: Daily

    struct Current: Decodable {
        let time: String
        let temperature: Double
        let feelsLike: Double
        let humidity: Int
        let rain: Double
        let wind: Double
        let gusts: Double
        let windDirection: Int
        let code: Int
        let isDay: Int
        enum CodingKeys: String, CodingKey {
            case time
            case temperature = "temperature_2m"
            case feelsLike = "apparent_temperature"
            case humidity = "relative_humidity_2m"
            case rain = "precipitation"
            case wind = "wind_speed_10m"
            case gusts = "wind_gusts_10m"
            case windDirection = "wind_direction_10m"
            case code = "weather_code"
            case isDay = "is_day"
        }
    }

    struct Hourly: Decodable {
        let time: [String]
        let temperature: [Double?]
        let rainChance: [Int?]
        let wind: [Double?]
        let code: [Int?]
        let isDay: [Int?]?
        enum CodingKeys: String, CodingKey {
            case time
            case temperature = "temperature_2m"
            case rainChance = "precipitation_probability"
            case wind = "wind_speed_10m"
            case code = "weather_code"
            case isDay = "is_day"
        }
    }

    struct Daily: Decodable {
        let time: [String]
        let high: [Double?]
        let low: [Double?]
        let rainChance: [Int?]
        let windMax: [Double?]
        let code: [Int?]
        enum CodingKeys: String, CodingKey {
            case time
            case high = "temperature_2m_max"
            case low = "temperature_2m_min"
            case rainChance = "precipitation_probability_max"
            case windMax = "wind_speed_10m_max"
            case code = "weather_code"
        }
    }
}
