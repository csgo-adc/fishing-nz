import Foundation
import UIKit
import Security

struct FishingRepository {
    private let decoder = JSONDecoder()
    private var accountBaseURL: String { ((Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String) ?? "https://fishing.fishnz.space").trimmingCharacters(in: CharacterSet(charactersIn: "/")) }

    func registerOrLogin(email: String, password: String) async throws -> AccountSnapshot {
        let response: AccountAuthResponse = try await accountRequest("/v1/auth/login", method: "POST", body: ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "password": password])
        KeychainSession.save(response.token)
        return AccountSnapshot(user: response.user, permissions: response.permissions)
    }

    func createAccount(email: String, password: String, displayName: String) async throws -> String {
        let response: AccountMessageResponse = try await accountRequest("/v1/auth/register", method: "POST", body: ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "password": password, "display_name": displayName.trimmingCharacters(in: .whitespacesAndNewlines)])
        return response.message
    }

    func resendVerification(email: String) async throws -> String {
        let response: AccountMessageResponse = try await accountRequest("/v1/auth/resend-verification", method: "POST", body: ["email": email.trimmingCharacters(in: .whitespacesAndNewlines)])
        return response.message
    }

    func trackEvent(_ eventName: String, feature: String?, platform: String) async {
        guard KeychainSession.load() != nil else { return }
        var body: [String: Any] = ["event_name": eventName, "platform": platform]
        if let feature { body["feature"] = feature }
        let _: EmptyResponse? = try? await accountRequest("/v1/analytics/events", method: "POST", body: body, token: KeychainSession.load())
    }

    func currentAccount() async throws -> AccountSnapshot? {
        guard let token = KeychainSession.load() else { return nil }
        do {
            let profile: AccountProfileEnvelope = try await accountRequest("/v1/me", method: "GET", token: token)
            let permissions: AccountPermissionsEnvelope = try await accountRequest("/v1/me/permissions", method: "GET", token: token)
            return AccountSnapshot(user: profile.user, permissions: AccountPermissions(plan: permissions.plan, features: permissions.features))
        } catch let error as AccountAPIError where error.status == 401 {
            KeychainSession.clear()
            return nil
        }
    }

    func saveProfile(displayName: String, countryCode: String) async throws -> AccountSnapshot {
        guard let token = KeychainSession.load() else { throw AccountAPIError(message: "Sign in to update your profile.", status: 401) }
        let _: AccountProfileEnvelope = try await accountRequest("/v1/me", method: "PATCH", body: ["display_name": displayName, "country_code": countryCode.uppercased()], token: token)
        if let snapshot = try await currentAccount() { return snapshot }
        throw AccountAPIError(message: "Please sign in again.", status: 401)
    }

    func sendFeedback(category: String, message: String, rating: Int) async throws {
        guard let token = KeychainSession.load() else { throw AccountAPIError(message: "Sign in to send feedback.", status: 401) }
        let _: EmptyResponse = try await accountRequest("/v1/feedback", method: "POST", body: ["category": category, "message": message, "rating": rating], token: token, platform: "ios")
    }

    func signOut() async {
        if let token = KeychainSession.load() { let _: EmptyResponse? = try? await accountRequest("/v1/auth/logout", method: "POST", body: [String: String](), token: token) }
        KeychainSession.clear()
    }

    private func accountRequest<T: Decodable>(_ path: String, method: String, body: Any? = nil, token: String? = nil, platform: String? = nil) async throws -> T {
        var request = URLRequest(url: URL(string: accountBaseURL + path)!)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let platform { request.setValue(platform, forHTTPHeaderField: "X-Client-Platform") }
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        request.timeoutInterval = 15
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 500
        guard 200...299 ~= status else {
            let payload = (try? JSONDecoder().decode(AccountErrorResponse.self, from: data))
            throw AccountAPIError(message: payload?.error ?? "Account request failed.", status: status)
        }
        return try decoder.decode(T.self, from: data)
    }

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
        request.setValue("ios", forHTTPHeaderField: "X-Client-Platform")
        if let token = KeychainSession.load() { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
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

private struct AccountErrorResponse: Decodable { let error: String? }
private struct AccountMessageResponse: Decodable { let message: String }
private struct EmptyResponse: Decodable {}
private struct AccountAPIError: LocalizedError { let message: String; let status: Int; var errorDescription: String? { message } }

private enum KeychainSession {
    private static let service = "nz.fishingnz.catchcheck.session"
    private static let account = "bearer-token"

    static func save(_ token: String) {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = Data(token.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }

    static func load() -> String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func clear() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account] as CFDictionary)
    }
}

private extension DateFormatter { static func make(_ format: String) -> DateFormatter { let formatter = DateFormatter(); formatter.dateFormat = format; formatter.locale = Locale(identifier: "en_US_POSIX"); return formatter } }
private struct WeatherResponse: Decodable { let current: Current; struct Current: Decodable { let temperature2m: Double; let windSpeed10m: Double; let precipitation: Double; enum CodingKeys: String, CodingKey { case temperature2m = "temperature_2m", windSpeed10m = "wind_speed_10m", precipitation } } }
private struct MarineResponse: Decodable { let hourly: Hourly; struct Hourly: Decodable { let time: [String]; let seaLevelHeightMsl: [Double]; enum CodingKeys: String, CodingKey { case time; case seaLevelHeightMsl = "sea_level_height_msl" } } }
