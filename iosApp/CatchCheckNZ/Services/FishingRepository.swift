import Foundation
import UIKit
import Security
import CoreLocation

struct FishingRepository {
    private let decoder = JSONDecoder()
    private var accountBaseURL: String { ((Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String) ?? "https://fishing.fishnz.space").trimmingCharacters(in: CharacterSet(charactersIn: "/")) }

    func hasStoredSession() -> Bool { KeychainSession.load() != nil }

    func registerOrLogin(email: String, password: String) async throws -> AccountSnapshot {
        let response: AccountAuthResponse = try await accountRequest("/v1/auth/login", method: "POST", body: ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "password": password])
        try KeychainSession.save(response.token)
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
        guard let token = KeychainSession.load() else { throw AccountAPIError(message: "Sign in to update your profile.", status: 401, code: nil) }
        let _: AccountProfileEnvelope = try await accountRequest("/v1/me", method: "PATCH", body: ["display_name": displayName, "country_code": countryCode.uppercased()], token: token)
        if let snapshot = try await currentAccount() { return snapshot }
        throw AccountAPIError(message: "Please sign in again.", status: 401, code: nil)
    }

    func sendFeedback(category: String, message: String, rating: Int) async throws {
        guard let token = KeychainSession.load() else { throw AccountAPIError(message: "Sign in to send feedback.", status: 401, code: nil) }
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
            throw AccountAPIError(message: payload?.error ?? "Account request failed.", status: status, code: payload?.code)
        }
        return try decoder.decode(T.self, from: data)
    }

    func conditions(at point: GeoPoint) async throws -> (WeatherState, TideState?) {
        let weatherURL = URL(string: "https://api.open-meteo.com/v1/forecast?latitude=\(point.latitude)&longitude=\(point.longitude)&current=temperature_2m,wind_speed_10m,precipitation&timezone=auto")!
        let (data, response) = try await URLSession.shared.data(from: weatherURL)
        guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0) else { throw URLError(.badServerResponse) }
        let weather = try decoder.decode(WeatherResponse.self, from: data).current
        let state = WeatherState(temperature: "\(Int(weather.temperature2m))°C", wind: "\(Int(weather.windSpeed10m)) km/h", rain: "\(weather.precipitation) mm")
        let origin = CLLocation(latitude: point.latitude, longitude: point.longitude)
        let nearest = tideStations.min {
            origin.distance(from: CLLocation(latitude: $0.latitude, longitude: $0.longitude)) <
            origin.distance(from: CLLocation(latitude: $1.latitude, longitude: $1.longitude))
        }
        guard let nearest else { return (state, nil) }
        return (state, try? await tide(for: nearest, date: .now))
    }

    func tide(for station: TideStation, date: Date) async throws -> TideState {
        let calendar = Self.tideCalendar
        let dayStart = calendar.startOfDay(for: date)
        guard let dayEnd = calendar.date(byAdding: .day, value: 1, to: dayStart) else { throw URLError(.badURL) }
        let year = calendar.component(.year, from: date)
        var predictions = try await Self.tideStore.load(stationName: station.csvName, year: year)
        let month = calendar.component(.month, from: date)
        let day = calendar.component(.day, from: date)
        if month == 1 && day == 1,
           let prior = try? await Self.tideStore.load(stationName: station.csvName, year: year - 1) {
            predictions.insert(contentsOf: prior.suffix(2), at: 0)
        }
        if month == 12 && day == 31,
           let following = try? await Self.tideStore.load(stationName: station.csvName, year: year + 1) {
            predictions.append(contentsOf: following.prefix(2))
        }
        predictions.sort { $0.time < $1.time }

        let daily = predictions.filter { $0.time >= dayStart && $0.time < dayEnd }
        guard !daily.isEmpty else { throw TideDataError.noPredictions }
        let events: [(Date, TideEvent)] = daily.compactMap { prediction in
            guard let index = predictions.firstIndex(where: { $0.time == prediction.time }) else { return nil }
            let type: String
            if predictions.indices.contains(index + 1) {
                type = prediction.height > predictions[index + 1].height ? "High" : "Low"
            } else if predictions.indices.contains(index - 1) {
                type = prediction.height > predictions[index - 1].height ? "High" : "Low"
            } else { return nil }
            return (prediction.time, TideEvent(time: Self.time.string(from: prediction.time), height: String(format: "%.1f m", prediction.height), type: type))
        }

        var points: [TidePoint] = []
        var sample = dayStart
        while sample < dayEnd {
            if let height = Self.interpolatedHeight(at: sample, predictions: predictions) {
                points.append(TidePoint(time: Self.time.string(from: sample), level: height,
                                        minuteOfDay: calendar.dateComponents([.minute], from: dayStart, to: sample).minute ?? 0))
            }
            sample.addTimeInterval(10 * 60)
        }
        guard !points.isEmpty else { throw TideDataError.noPredictions }
        let now = Date()
        let isToday = calendar.isDate(date, inSameDayAs: now)
        let nextPrediction = isToday ? predictions.first(where: { $0.time > now }) : daily.first
        let nextEvent = nextPrediction.flatMap { prediction -> String? in
            if let event = events.first(where: { $0.0 == prediction.time }) { return event.1.type }
            guard let index = predictions.firstIndex(where: { $0.time == prediction.time }),
                  predictions.indices.contains(index + 1) else { return nil }
            return prediction.height > predictions[index + 1].height ? "High" : "Low"
        }
        let referenceTime = isToday ? now : (calendar.date(byAdding: .hour, value: 12, to: dayStart) ?? dayStart)
        let referenceHeight = Self.interpolatedHeight(at: referenceTime, predictions: predictions)
        return TideState(
            currentLevel: referenceHeight.map { String(format: "%.2f m", $0) } ?? "—",
            nextEvent: nextEvent ?? "—",
            eventTime: nextPrediction.map { Self.eventTime.string(from: $0.time) } ?? "No event",
            events: events.map(\.1),
            points: points,
            stationName: station.name
        )
    }

    private static func interpolatedHeight(at time: Date, predictions: [LINZTidePrediction]) -> Double? {
        guard let afterIndex = predictions.firstIndex(where: { $0.time >= time }) else { return nil }
        if predictions[afterIndex].time == time { return predictions[afterIndex].height }
        guard afterIndex > 0 else { return nil }
        let before = predictions[afterIndex - 1]
        let after = predictions[afterIndex]
        let duration = after.time.timeIntervalSince(before.time)
        guard duration > 0 else { return nil }
        let progress = time.timeIntervalSince(before.time) / duration
        let smoothProgress = (1 - cos(.pi * progress)) / 2
        return before.height + (after.height - before.height) * smoothProgress
    }

    func identifyFish(image: UIImage, at point: GeoPoint, hasDeviceLocation: Bool, selectedRulesAreaID: String?) async throws -> FishCheck {
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
        if let selectedRulesAreaID { request.setValue(selectedRulesAreaID, forHTTPHeaderField: "X-Fishing-Rules-Area") }
        if let token = KeychainSession.load() { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        let payload = try JSONDecoder().decode(FishIdentificationResponse.self, from: data)
        guard (response as? HTTPURLResponse)?.statusCode ?? 500 < 300 else { throw NSError(domain: "FishIdentification", code: 1, userInfo: [NSLocalizedDescriptionKey: payload.error ?? "Fish identification failed."]) }
        let commonName = payload.commonName ?? "Unknown fish"
        let scientificName = payload.scientificName ?? ""
        return FishCheck(commonName: commonName, scientificName: scientificName, confidence: Int((payload.confidence ?? 0) * 100), areaName: payload.areaName ?? "Choose an MPI fishing area", areaIsEstimated: payload.areaIsEstimated ?? false, rulesNeedsReview: payload.rulesNeedsReview ?? false, rulesReviewedAt: payload.rulesReviewedAt, rulesSourceURL: URL(string: payload.rulesSourceURL ?? ""), fishRules: payload.fishRules ?? [])
    }

    private static let tideStore = LINZTideStore()
    private static let tideCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar
    }()
    private static let time = DateFormatter.make("h:mm a", timeZone: tideCalendar.timeZone)
    private static let eventTime = DateFormatter.make("EEE d MMM · h:mm a", timeZone: tideCalendar.timeZone)
}

private struct FishIdentificationResponse: Decodable {
    let commonName: String?; let scientificName: String?; let confidence: Double?; let error: String?
    let areaName: String?; let areaIsEstimated: Bool?; let rulesNeedsReview: Bool?; let rulesReviewedAt: String?; let rulesSourceURL: String?; let fishRules: [FishRuleMatch]?
    enum CodingKeys: String, CodingKey {
        case commonName, scientificName, confidence, error, areaName, areaIsEstimated, rulesNeedsReview, rulesReviewedAt, fishRules
        case rulesSourceURL = "rulesSourceUrl"
    }
}

private struct AccountErrorResponse: Decodable { let error: String?; let code: String? }
private struct AccountMessageResponse: Decodable { let message: String }
private struct EmptyResponse: Decodable {}
struct AccountAPIError: LocalizedError { let message: String; let status: Int; let code: String?; var errorDescription: String? { message } }

private enum KeychainSession {
    private static let service = "nz.fishingnz.catchcheck.session"
    private static let account = "bearer-token"

    static func save(_ token: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = Data(token.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
            throw AccountAPIError(message: "Could not save your account session. Please try again.", status: 0, code: "session_storage_failed")
        }
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

private extension DateFormatter {
    static func make(_ format: String, timeZone: TimeZone) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.dateFormat = format
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone
        return formatter
    }
}
private struct WeatherResponse: Decodable { let current: Current; struct Current: Decodable { let temperature2m: Double; let windSpeed10m: Double; let precipitation: Double; enum CodingKeys: String, CodingKey { case temperature2m = "temperature_2m", windSpeed10m = "wind_speed_10m", precipitation } } }

private enum TideDataError: LocalizedError {
    case noPredictions
    var errorDescription: String? { "LINZ tide predictions are unavailable for this station and date." }
}

private struct LINZTidePrediction: Sendable {
    let time: Date
    let height: Double
}

private actor LINZTideStore {
    private var annualCache: [String: [LINZTidePrediction]] = [:]

    func load(stationName: String, year: Int) async throws -> [LINZTidePrediction] {
        let key = "\(stationName)-\(year)"
        if let cached = annualCache[key] { return cached }
        guard let stationPath = stationName.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let url = URL(string: "https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/\(stationPath)%20\(year).csv")
        else { throw URLError(.badURL) }
        let request = URLRequest(url: url, cachePolicy: .returnCacheDataElseLoad, timeoutInterval: 20)
        let (data, response) = try await URLSession.shared.data(for: request)
        guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0) else { throw URLError(.badServerResponse) }
        guard let csv = String(data: data, encoding: .utf8) else { throw TideDataError.noPredictions }

        let timeZone = TimeZone(identifier: "Pacific/Auckland")!
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        var predictions: [LINZTidePrediction] = []
        for line in csv.split(whereSeparator: \.isNewline) {
            let fields = line.split(separator: ",", omittingEmptySubsequences: false)
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            guard fields.count >= 6,
                  let day = Int(fields[0]), let month = Int(fields[2]), let rowYear = Int(fields[3]),
                  rowYear == year, (1...12).contains(month), (1...31).contains(day)
            else { continue }
            for index in stride(from: 4, to: fields.count - 1, by: 2) {
                let clock = fields[index].split(separator: ":")
                guard clock.count == 2, let hour = Int(clock[0]), let minute = Int(clock[1]),
                      let height = Double(fields[index + 1]), height.isFinite
                else { continue }
                var components = DateComponents()
                components.timeZone = timeZone
                components.year = rowYear
                components.month = month
                components.day = day
                components.hour = hour
                components.minute = minute
                if let time = calendar.date(from: components) {
                    predictions.append(LINZTidePrediction(time: time, height: height))
                }
            }
        }
        predictions.sort { $0.time < $1.time }
        guard !predictions.isEmpty else { throw TideDataError.noPredictions }
        annualCache[key] = predictions
        return predictions
    }
}
