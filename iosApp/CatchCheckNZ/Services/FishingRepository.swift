import Foundation
import UIKit
import Security
import CoreLocation
import AuthenticationServices

struct SignInProviders: Decodable {
    var google = false
    var apple = false
    var connected: [String] = []
}

private struct SocialSignInStart: Decodable {
    let authorization_url: String
    let exchange_secret: String
}

@MainActor
final class SocialSignInBrowser: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?

    func authenticate(url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "nz.fishingnz.catchcheck") { [weak self] callback, error in
                Task { @MainActor in
                    self?.session = nil
                    if let error { continuation.resume(throwing: error) }
                    else if let callback { continuation.resume(returning: callback) }
                    else { continuation.resume(throwing: AccountAPIError(message: "Could not finish sign-in.", status: 400, code: nil)) }
                }
            }
            session.presentationContextProvider = self
            self.session = session
            if !session.start() {
                self.session = nil
                continuation.resume(throwing: AccountAPIError(message: "Could not open sign-in. Please try again.", status: 400, code: nil))
            }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}

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

    func signInProviders() async throws -> SignInProviders {
        try await accountRequest("/v1/auth/providers", method: "GET", token: KeychainSession.load())
    }

    func startSocialSignIn(provider: String, link: Bool) async throws -> (url: URL, secret: String) {
        let response: SocialSignInStart = try await accountRequest("/v1/auth/oauth/start", method: "POST",
            body: ["provider": provider, "link": link, "return_uri": "nz.fishingnz.catchcheck://auth/callback"], token: KeychainSession.load())
        guard let url = URL(string: response.authorization_url) else {
            throw AccountAPIError(message: "Could not start sign-in. Please try again.", status: 502, code: nil)
        }
        return (url, response.exchange_secret)
    }

    func completeSocialSignIn(code: String, secret: String) async throws -> AccountSnapshot {
        let response: AccountAuthResponse = try await accountRequest("/v1/auth/oauth/exchange", method: "POST",
            body: ["code": code, "exchange_secret": secret])
        try KeychainSession.save(response.token)
        return AccountSnapshot(user: response.user, permissions: response.permissions)
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
            return AccountSnapshot(user: profile.user, permissions: permissions)
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

    /// The service keeps this session signed in and ends the account's others. Errors carry a message fit to show.
    func changePassword(current: String, new: String) async throws {
        guard let token = KeychainSession.load() else { throw AccountAPIError(message: "Sign in again to change your password.", status: 401, code: nil) }
        let _: EmptyResponse = try await accountRequest("/v1/me/password", method: "POST", body: ["current_password": current, "new_password": new], token: token)
    }

    /// Ends every session for the account, including this one, so the saved session is cleared afterwards.
    func logoutEverywhere() async throws {
        if let token = KeychainSession.load() {
            let _: EmptyResponse = try await accountRequest("/v1/auth/logout-all", method: "POST", body: [String: String](), token: token)
        }
        KeychainSession.clear()
    }

    func deleteAccount() async throws {
        guard let token = KeychainSession.load() else { throw AccountAPIError(message: "Sign in again to delete your account.", status: 401, code: nil) }
        let response: AccountDeleteResponse = try await accountRequest("/v1/me", method: "DELETE", body: ["confirm": true], token: token)
        guard response.deleted else { throw AccountAPIError(message: "Account deletion could not be confirmed. Please try again.", status: 500, code: nil) }
        KeychainSession.clear()
    }

    private func accountRequest<T: Decodable>(_ path: String, method: String, body: Any? = nil, token: String? = nil, platform: String? = nil) async throws -> T {
        var request = URLRequest(url: URL(string: accountBaseURL + path)!)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let platform { request.setValue(platform, forHTTPHeaderField: "X-Client-Platform") }
        // Lets the service count this device's API use; present only while optional analytics is on.
        if let deviceID = AnalyticsPreferences.deviceID() { request.setValue(deviceID, forHTTPHeaderField: "X-Device-Id") }
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
        let predictions = try await Self.tideStore.predictions(stationName: station.csvName, start: dayStart, end: dayStart)

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
            guard let index = predictions.firstIndex(where: { $0.time == prediction.time }) else { return nil }
            if predictions.indices.contains(index + 1) {
                return prediction.height > predictions[index + 1].height ? "High" : "Low"
            }
            guard index > 0 else { return nil }
            return prediction.height > predictions[index - 1].height ? "High" : "Low"
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
        request.setValue("ios", forHTTPHeaderField: "X-Client-Platform")
        if let deviceID = AnalyticsPreferences.deviceID() { request.setValue(deviceID, forHTTPHeaderField: "X-Device-Id") }
        if let selectedRulesAreaID { request.setValue(selectedRulesAreaID, forHTTPHeaderField: "X-Fishing-Rules-Area") }
        if let token = KeychainSession.load() { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        let payload = try JSONDecoder().decode(FishIdentificationResponse.self, from: data)
        guard (response as? HTTPURLResponse)?.statusCode ?? 500 < 300 else {
            throw FishIdentificationError(message: payload.error ?? "Fish identification failed.", quota: payload.fishIdentityQuota)
        }
        let commonName = payload.commonName ?? "Unknown fish"
        let scientificName = payload.scientificName ?? ""
        return FishCheck(commonName: commonName, scientificName: scientificName, confidence: Int((payload.confidence ?? 0) * 100), areaID: payload.areaID, areaName: payload.areaName ?? "Choose an MPI fishing area", areaIsEstimated: payload.areaIsEstimated ?? false, rulesNeedsReview: payload.rulesNeedsReview ?? false, rulesReviewedAt: payload.rulesReviewedAt, rulesSourceURL: payload.rulesSourceURL.flatMap(URL.init(string:)), fishRules: payload.fishRules ?? [], isFish: payload.isFish ?? true, otherPossibilities: payload.otherPossibilities ?? [], visibleClues: payload.visibleClues ?? "", identificationNote: payload.identificationNote ?? "", fishIdentityQuota: payload.fishIdentityQuota)
    }

    func fishRules(species: String, areaID: String) async throws -> FishRulesResult {
        guard var components = URLComponents(string: accountBaseURL + "/v1/fish/rules") else { throw URLError(.badURL) }
        components.queryItems = [URLQueryItem(name: "area", value: areaID), URLQueryItem(name: "species", value: species)]
        guard let url = components.url else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let deviceID = AnalyticsPreferences.deviceID() { request.setValue(deviceID, forHTTPHeaderField: "X-Device-Id") }
        request.timeoutInterval = 15
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode ?? 500 < 300 else {
            let message = (try? JSONDecoder().decode(FishIdentificationResponse.self, from: data).error) ?? "Could not load MPI rules."
            throw NSError(domain: "FishRules", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
        }
        return try JSONDecoder().decode(FishRulesResult.self, from: data)
    }

    private static let tideStore = LINZTideStore.shared
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
    let isFish: Bool?; let otherPossibilities: [String]?; let visibleClues: String?; let identificationNote: String?
    let areaID: String?; let areaName: String?; let areaIsEstimated: Bool?; let rulesNeedsReview: Bool?; let rulesReviewedAt: String?; let rulesSourceURL: String?; let fishRules: [FishRuleMatch]?; let fishIdentityQuota: FishIdentityQuota?
    enum CodingKeys: String, CodingKey {
        case commonName, scientificName, confidence, error, isFish, otherPossibilities, visibleClues, identificationNote, areaName, areaIsEstimated, rulesNeedsReview, rulesReviewedAt, fishRules
        case areaID = "areaId"
        case rulesSourceURL = "rulesSourceUrl"
        case fishIdentityQuota = "fish_identity_quota"
    }
}

struct FishIdentificationError: LocalizedError {
    let message: String
    let quota: FishIdentityQuota?
    var errorDescription: String? { message }
}

private struct AccountErrorResponse: Decodable { let error: String?; let code: String? }
private struct AccountMessageResponse: Decodable { let message: String }
private struct EmptyResponse: Decodable {}
private struct AccountDeleteResponse: Decodable { let deleted: Bool }
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

// MARK: - Optional analytics
//
// Everything below does nothing unless the person turns on "Optional usage analytics" in Terms & privacy. It works
// whether or not they are signed in. Events are queued and sent in batches; a signed-in session links them to the
// account. Only the event names and properties the service allows are kept (server/fishial-proxy/src/analytics.ts).
// Never pass coordinates, email addresses, photos or typed text.

/// A value an analytics event may carry. Only simple values exist, so free text and coordinates have no slot.
enum AnalyticsValue: Sendable, Equatable {
    case text(String)
    case number(Double)
    case flag(Bool)
}

struct AnalyticsEvent: Sendable {
    let name: String
    let props: [String: AnalyticsValue]
    let at: Date
}

/// The random per-install id plus coarse device details. Nothing here identifies a person or a hardware unit.
struct AnalyticsDevice: Sendable {
    let id: String
    let platform: String
    let osVersion: String
    let deviceModel: String
    let appVersion: String
    let locale: String
    let timeZone: String
}

enum AnalyticsPayload {
    static let maxEventsPerUpload = 50

    /// Body for POST /v1/analytics/batch. Each event carries how long ago it happened, not the phone's clock time.
    static func build(device: AnalyticsDevice, events: [AnalyticsEvent], now: Date) -> [String: Any] {
        let eventList: [[String: Any]] = events.map { event in
            var props: [String: Any] = [:]
            for (key, value) in event.props {
                switch value {
                case .text(let text): props[key] = text
                case .number(let number): props[key] = number
                case .flag(let flag): props[key] = flag
                }
            }
            return ["name": event.name, "offset_ms": max(0, Int(now.timeIntervalSince(event.at) * 1000)), "props": props]
        }
        let deviceInfo: [String: Any] = [
            "id": device.id, "platform": device.platform, "os_version": device.osVersion, "device_model": device.deviceModel,
            "app_version": device.appVersion, "locale": device.locale, "time_zone": device.timeZone,
        ]
        return ["device": deviceInfo, "events": eventList]
    }

    static func confidenceLevel(percent: Int) -> String {
        percent >= 80 ? "high" : (percent >= 50 ? "medium" : "low")
    }
}

/// Whether the person ticked "Don't ask me again" when agreeing to send a photo to OpenAI. It is only ever switched on from
/// the consent sheet, which shows the full disclosure, and it is cleared on sign-out and account deletion.
enum PhotoUploadConsent {
    private static let rememberedKey = "photo_upload_consent_remembered"

    static var isRemembered: Bool {
        get { UserDefaults.standard.bool(forKey: rememberedKey) }
        set { UserDefaults.standard.set(newValue, forKey: rememberedKey) }
    }
}

enum AnalyticsPreferences {
    private static let enabledKey = "analytics_enabled"
    private static let deviceIDKey = "analytics_device_id"
    private static let queue = DispatchQueue(label: "nz.fishingnz.analytics.preferences")

    /// The person's answer to the one-time notice or the Settings switch. Nil until they have answered.
    static var choice: Bool? { UserDefaults.standard.object(forKey: enabledKey) as? Bool }

    /// True while the one-time notice has not been answered.
    static var needsNotice: Bool { choice == nil }

    /// Our own anonymous usage statistics are on by default, but nothing is collected before the notice is answered.
    static var isEnabled: Bool { choice ?? false }

    /// The random id that tells our analytics one install from another. It is made when analytics is first used, is never
    /// an advertising or hardware id, and is returned only while analytics is on.
    static func deviceID() -> String? {
        queue.sync {
            guard isEnabled else { return nil }
            if let existing = UserDefaults.standard.string(forKey: deviceIDKey) { return existing }
            let created = UUID().uuidString.lowercased()
            UserDefaults.standard.set(created, forKey: deviceIDKey)
            return created
        }
    }

    /// Turning analytics off forgets the id, discards anything queued, and asks the service to erase what it stored.
    static func setEnabled(_ enabled: Bool) {
        let forgotten: String? = queue.sync {
            let old = enabled ? nil : UserDefaults.standard.string(forKey: deviceIDKey)
            UserDefaults.standard.set(enabled, forKey: enabledKey)
            if !enabled { UserDefaults.standard.removeObject(forKey: deviceIDKey) }
            return old
        }
        if !enabled { Analytics.erase(deviceID: forgotten) }
    }
}

enum Analytics {
    /// Queue an event. Does nothing unless the person turned optional analytics on.
    static func track(_ name: String, _ props: [String: AnalyticsValue] = [:]) {
        guard AnalyticsPreferences.isEnabled else { return }
        let event = AnalyticsEvent(name: name, props: props, at: Date())
        Task { await AnalyticsEngine.shared.add(event) }
    }

    /// Send what is queued now, for example when the app goes to the background.
    static func flush() {
        Task { await AnalyticsEngine.shared.flush() }
    }

    /// Drop anything queued and, if an id had been made, ask the service to erase everything stored for it.
    static func erase(deviceID: String?) {
        Task {
            await AnalyticsEngine.shared.discard()
            if let deviceID { await AnalyticsUploader.erase(deviceID: deviceID) }
        }
    }

    /// A short, non-identifying reason for a failed request.
    static func errorCode(_ error: Error) -> String {
        if let api = error as? AccountAPIError { return api.code ?? "http_\(api.status)" }
        if error is FishIdentificationError { return "rejected" }
        return "network"
    }

    static func deviceInfo() -> AnalyticsDevice? {
        guard let id = AnalyticsPreferences.deviceID() else { return nil }
        let os = ProcessInfo.processInfo.operatingSystemVersion
        var system = utsname()
        uname(&system)
        let model = withUnsafeBytes(of: &system.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        return AnalyticsDevice(
            id: id, platform: "ios", osVersion: "\(os.majorVersion).\(os.minorVersion).\(os.patchVersion)", deviceModel: model,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
            locale: Locale.current.identifier.replacingOccurrences(of: "_", with: "-"), timeZone: TimeZone.current.identifier)
    }
}

actor AnalyticsEngine {
    static let shared = AnalyticsEngine()

    private let capacity = 200
    private var buffer: [AnalyticsEvent] = []
    private var scheduled: Task<Void, Never>?
    private var lastUpload = Date.distantPast
    private var uploading = false

    func add(_ event: AnalyticsEvent) {
        guard AnalyticsPreferences.isEnabled else { return }
        buffer.append(event)
        if buffer.count > capacity { buffer.removeFirst(buffer.count - capacity) }
        schedule()
    }

    func flush() async {
        guard AnalyticsPreferences.isEnabled else {
            // Events queued under an earlier consent must never be sent after it was withdrawn.
            buffer.removeAll()
            return
        }
        await upload()
    }

    func discard() {
        buffer.removeAll()
        scheduled?.cancel()
        scheduled = nil
    }

    private func schedule() {
        guard scheduled == nil else { return }
        // The first upload goes quickly so a new install shows up; later ones wait a minute after the previous upload.
        let wait = min(60, max(2, lastUpload.addingTimeInterval(60).timeIntervalSinceNow))
        scheduled = Task {
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await self.upload()
        }
    }

    private func upload() async {
        scheduled = nil
        guard !uploading else { return }
        uploading = true
        defer { uploading = false }
        while AnalyticsPreferences.isEnabled, !buffer.isEmpty {
            guard let device = Analytics.deviceInfo() else { break }
            let batch = Array(buffer.prefix(AnalyticsPayload.maxEventsPerUpload))
            buffer.removeFirst(batch.count)
            do {
                try await AnalyticsUploader.send(device: device, events: batch)
                lastUpload = Date()
            } catch let error as AccountAPIError where error.status != 429 && error.status < 500 {
                // A rejected upload would be rejected again, so these events are dropped.
                break
            } catch {
                buffer.insert(contentsOf: batch, at: 0)
                if buffer.count > capacity { buffer.removeFirst(buffer.count - capacity) }
                break
            }
        }
        if !AnalyticsPreferences.isEnabled { buffer.removeAll() }
    }
}

enum AnalyticsUploader {
    private static var baseURL: String {
        ((Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String) ?? "https://fishing.fishnz.space")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    static func send(device: AnalyticsDevice, events: [AnalyticsEvent]) async throws {
        guard let url = URL(string: baseURL + "/v1/analytics/batch") else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("ios", forHTTPHeaderField: "X-Client-Platform")
        request.setValue(device.id, forHTTPHeaderField: "X-Device-Id")
        if let token = KeychainSession.load() { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.httpBody = try JSONSerialization.data(withJSONObject: AnalyticsPayload.build(device: device, events: events, now: Date()))
        request.timeoutInterval = 15
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 500
        guard 200...299 ~= status else {
            let payload = try? JSONDecoder().decode(AccountErrorResponse.self, from: data)
            throw AccountAPIError(message: payload?.error ?? "Analytics upload failed.", status: status, code: payload?.code)
        }
    }

    static func erase(deviceID: String) async {
        guard let url = URL(string: baseURL + "/v1/analytics/device") else { return }
        var request = URLRequest(url: url)
        request.httpMethod = "DELETE"
        request.setValue(deviceID, forHTTPHeaderField: "X-Device-Id")
        request.timeoutInterval = 15
        _ = try? await URLSession.shared.data(for: request)
    }
}

extension ShareLinkService {
    /// The service the app uses: the same API base as everything else, and the analytics device id only while analytics is on.
    static let live = ShareLinkService(backend: APIShareBackend(
        baseURL: ((Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String) ?? "https://fishing.fishnz.space")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/")),
        deviceID: { AnalyticsPreferences.deviceID() }))
}
