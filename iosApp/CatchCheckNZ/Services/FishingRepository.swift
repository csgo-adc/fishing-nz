import Foundation
import UIKit

struct FishingRepository {
    private let decoder = JSONDecoder()

    func conditions(at point: GeoPoint) async throws -> (WeatherState, TideState) {
        let weatherURL = URL(string: "https://api.open-meteo.com/v1/forecast?latitude=\(point.latitude)&longitude=\(point.longitude)&current=temperature_2m,wind_speed_10m,precipitation&timezone=auto")!
        let (data, response) = try await URLSession.shared.data(from: weatherURL)
        guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0) else { throw URLError(.badServerResponse) }
        let weather = try decoder.decode(WeatherResponse.self, from: data).current
        let state = WeatherState(temperature: "\(Int(weather.temperature2m))°C", wind: "\(Int(weather.windSpeed10m)) km/h", rain: "\(weather.precipitation) mm")
        let currentStation = TideStation(id: "current", name: "Current location", region: "", latitude: point.latitude, longitude: point.longitude)
        return (state, try await tide(for: currentStation, date: .now))
    }

    func tide(for station: TideStation, date: Date) async throws -> TideState {
        let day = Self.apiDay.string(from: date)
        guard let nextDay = Calendar.current.date(byAdding: .day, value: 1, to: date) else { throw URLError(.badURL) }
        let url = URL(string: "https://marine-api.open-meteo.com/v1/marine?latitude=\(station.latitude)&longitude=\(station.longitude)&hourly=sea_level_height_msl&start_date=\(day)&end_date=\(Self.apiDay.string(from: nextDay))&cell_selection=sea&timezone=auto")!
        let (data, response) = try await URLSession.shared.data(from: url)
        guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0) else { throw URLError(.badServerResponse) }
        let hourly = try decoder.decode(MarineResponse.self, from: data).hourly
        let rows = zip(hourly.time, hourly.seaLevelHeightMsl).compactMap { raw, level -> (Date, Double)? in
            guard let parsed = Self.apiTime.date(from: raw) else { return nil }; return (parsed, level)
        }
        let calendar = Calendar.current
        let todayRows = rows.filter { calendar.isDate($0.0, inSameDayAs: date) }
        let points = todayRows.map { TidePoint(time: Self.time.string(from: $0.0), level: $0.1) }
        let events = (1..<(rows.count - 1)).compactMap { index -> (Date, TideEvent)? in
            let prior = rows[index - 1].1, current = rows[index].1, next = rows[index + 1].1
            guard (current > prior && current >= next) || (current < prior && current <= next) else { return nil }
            let type = current > prior ? "High" : "Low"
            return (rows[index].0, TideEvent(time: Self.time.string(from: rows[index].0), height: String(format: "%.2f m", current), type: type))
        }.filter { calendar.isDate($0.0, inSameDayAs: date) }
        let now = Date()
        let upcoming = calendar.isDate(date, inSameDayAs: now) ? events.first { $0.0 > now } : events.first
        let level = (calendar.isDate(date, inSameDayAs: now) ? rows.last(where: { $0.0 <= now }) : todayRows.first)?.1 ?? 0
        return TideState(currentLevel: String(format: "%.2f m", level), nextEvent: upcoming?.1.type ?? "—", eventTime: upcoming.map { Self.eventTime.string(from: $0.0) } ?? "No event", events: events.map(\.1), points: points, stationName: station.name)
    }

    func identifyFish(image: UIImage, at point: GeoPoint, hasDeviceLocation: Bool) async throws -> FishCheck {
        guard let baseURL = Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String,
              let url = URL(string: baseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/v1/fish/identify"),
              let imageData = image.jpegData(compressionQuality: 0.88) else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"; request.httpBody = imageData
        request.setValue("image/jpeg", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("\(point.latitude),\(point.longitude)", forHTTPHeaderField: "X-Location-Lat-Lon")
        request.setValue(hasDeviceLocation ? "device" : "fallback", forHTTPHeaderField: "X-Location-Source")
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        let payload = try JSONDecoder().decode(FishIdentificationResponse.self, from: data)
        guard (response as? HTTPURLResponse)?.statusCode ?? 500 < 300 else { throw NSError(domain: "FishIdentification", code: 1, userInfo: [NSLocalizedDescriptionKey: payload.error ?? "Fish identification failed."]) }
        let commonName = payload.commonName ?? "Unknown fish"
        let scientificName = payload.scientificName ?? ""
        return FishCheck(commonName: commonName, scientificName: scientificName, confidence: Int((payload.confidence ?? 0) * 100), areaName: payload.areaName ?? "Fishing area", areaIsEstimated: payload.areaIsEstimated ?? true, rulesReviewedAt: payload.rulesReviewedAt, rulesSourceURL: URL(string: payload.rulesSourceURL ?? ""), fishRules: payload.fishRules ?? [])
    }

    private static let apiDay = DateFormatter.make("yyyy-MM-dd")
    private static let apiTime = DateFormatter.make("yyyy-MM-dd'T'HH:mm")
    private static let time = DateFormatter.make("h:mm a")
    private static let eventTime = DateFormatter.make("EEE d MMM · h:mm a")
}

private struct FishIdentificationResponse: Decodable {
    let commonName: String?; let scientificName: String?; let confidence: Double?; let error: String?
    let areaName: String?; let areaIsEstimated: Bool?; let rulesReviewedAt: String?; let rulesSourceURL: String?; let fishRules: [FishRuleMatch]?
    enum CodingKeys: String, CodingKey {
        case commonName, scientificName, confidence, error, areaName, areaIsEstimated, rulesReviewedAt, fishRules
        case rulesSourceURL = "rulesSourceUrl"
    }
}

private extension DateFormatter { static func make(_ format: String) -> DateFormatter { let formatter = DateFormatter(); formatter.dateFormat = format; formatter.locale = Locale(identifier: "en_US_POSIX"); return formatter } }
private struct WeatherResponse: Decodable { let current: Current; struct Current: Decodable { let temperature2m: Double; let windSpeed10m: Double; let precipitation: Double; enum CodingKeys: String, CodingKey { case temperature2m = "temperature_2m", windSpeed10m = "wind_speed_10m", precipitation } } }
private struct MarineResponse: Decodable { let hourly: Hourly; struct Hourly: Decodable { let time: [String]; let seaLevelHeightMsl: [Double]; enum CodingKeys: String, CodingKey { case time; case seaLevelHeightMsl = "sea_level_height_msl" } } }
