import Foundation
import StoreKit
import UIKit

/// Finds a newer version and offers it (docs/in-app-updates.md). The App Store's public lookup says which version people
/// can download; the service's /v1/app/version adds the "what's new" notes and the oldest build that still works.
/// Apple doesn't let an app update itself, so Update shows the App Store page inside the app, with Apple's own Update
/// button. Android does the same in `AppUpdates.kt`, where Google Play installs the update inside the app.
@MainActor
final class AppUpdates: ObservableObject {
    static let shared = AppUpdates()

    struct State: Equatable {
        /// The version this phone can update to, as `UpdatePolicy.versionNumber`, or nil when up to date.
        var available: Int?
        var availableVersion: String?
        var notes: [String] = []
        /// This build is older than the service supports: the app shows a full-screen Update instead.
        var required = false
        /// All three pop-ups for this version were shown, so More carries a badge.
        var remindersDone = false
    }

    @Published private(set) var state = State()

    static let appStoreID = 6820858116
    private static let checkGap: TimeInterval = 12 * 60 * 60
    private let defaults = UserDefaults.standard
    private var checking = false

    private init() {
        if defaults.object(forKey: "update_first_use_day") == nil { defaults.set(Self.today(), forKey: "update_first_use_day") }
    }

    static var installedVersion: String { (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0" }
    static var installedBuild: Int { Int((Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "") ?? 0 }

    static func today(_ date: Date = Date()) -> Int {
        let calendar = Calendar.current
        return calendar.dateComponents([.day], from: Date(timeIntervalSince1970: 0), to: calendar.startOfDay(for: date)).day ?? 0
    }

    private struct ServerRelease: Decodable { let version: String; let build: Int; let notes: [String] }
    private struct Server: Decodable { let minimum_build: Int; let releases: [ServerRelease] }
    private struct Lookup: Decodable { struct Item: Decodable { let version: String }; let results: [Item] }

    /// Ask the App Store and the service again, at most every 12 hours unless `force`.
    func check(language: String, force: Bool = false) async {
        guard !checking else { return }
        let last = defaults.double(forKey: "update_checked_at")
        guard force || Date().timeIntervalSince1970 - last >= Self.checkGap else { return }
        checking = true
        defer { checking = false }
        async let server = fetchServer(language: language)
        async let storeVersion = fetchStoreVersion()
        let (answer, store) = await (server, storeVersion)
        let installed = UpdatePolicy.versionNumber(Self.installedVersion)
        var next = state
        if let store, UpdatePolicy.versionNumber(store) > installed {
            next.available = UpdatePolicy.versionNumber(store)
            next.availableVersion = store
            next.notes = answer?.releases.first { $0.version == store }?.notes ?? []
        } else {
            next.available = nil
            next.availableVersion = nil
            next.notes = []
        }
        // Only a fresh answer from the service can require an update, so a phone offline is never locked out.
        next.required = answer.map { UpdatePolicy.required(installed: Self.installedBuild, minimum: $0.minimum_build) } ?? false
        next.remindersDone = next.available.map { UpdatePolicy.remindersDone(readRecord(), available: $0, installed: installed) } ?? false
        if answer != nil || store != nil { defaults.set(Date().timeIntervalSince1970, forKey: "update_checked_at") }
        state = next
    }

    private func fetchServer(language: String) async -> Server? {
        let lang = language.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "en"
        guard let url = URL(string: "\(RulesSync.baseURL)/v1/app/version?platform=ios&lang=\(lang)") else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try? JSONDecoder().decode(Server.self, from: data)
    }

    /// The version the New Zealand App Store offers now, from Apple's public lookup.
    private func fetchStoreVersion() async -> String? {
        let bundle = Bundle.main.bundleIdentifier ?? "nz.fishingnz.catchcheck"
        guard let url = URL(string: "https://itunes.apple.com/lookup?bundleId=\(bundle)&country=nz") else { return nil }
        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        request.cachePolicy = .reloadIgnoringLocalCacheData
        guard let (data, _) = try? await URLSession.shared.data(for: request) else { return nil }
        return (try? JSONDecoder().decode(Lookup.self, from: data))?.results.first?.version
    }

    private func readRecord() -> UpdatePromptRecord {
        guard let data = defaults.data(forKey: "update_prompt_record") else { return UpdatePromptRecord() }
        return (try? JSONDecoder().decode(UpdatePromptRecord.self, from: data)) ?? UpdatePromptRecord()
    }

    /// Called when Home could show the pop-up now. Returns which pop-up this is (1 to 3) and records it, or nil when the
    /// rules say not today.
    func claimPrompt() -> Int? {
        guard let available = state.available, !state.required else { return nil }
        let today = Self.today()
        let record = readRecord()
        let installed = UpdatePolicy.versionNumber(Self.installedVersion)
        let lastNotice = defaults.object(forKey: "update_notice_day") as? Int
        guard UpdatePolicy.shouldPrompt(record, available: available, installed: installed, today: today,
                                        firstUseDay: defaults.integer(forKey: "update_first_use_day"), lastNoticeDay: lastNotice) else { return nil }
        let next = UpdatePolicy.shown(record, available: available, today: today)
        if let data = try? JSONEncoder().encode(next) { defaults.set(data, forKey: "update_prompt_record") }
        defaults.set(today, forKey: "update_notice_day")
        state.remindersDone = UpdatePolicy.remindersDone(next, available: available, installed: installed)
        Analytics.track("update_prompt_shown", ["build": .number(Double(available)), "prompt_number": .number(Double(next.count))])
        return next.count
    }

    /// Another pop-up was shown today (a shared window, say), so the update pop-up waits for another day.
    func noteOtherNotice() { defaults.set(Self.today(), forKey: "update_notice_day") }

    func later() {
        Analytics.track("update_prompt_answered", ["build": .number(Double(state.available ?? 0)), "outcome": .text("later")])
    }

    /// Shows the App Store page over the app, where Apple's Update button installs the new version.
    func openStore() {
        Analytics.track("update_prompt_answered", ["build": .number(Double(state.available ?? 0)), "outcome": .text("update"),
                                                   "required": .flag(state.required)])
        guard let presenter = Self.topViewController() else { return openStoreURL() }
        let store = SKStoreProductViewController()
        store.delegate = StoreDismisser.shared
        presenter.present(store, animated: true)
        Task {
            do { try await store.loadProduct(withParameters: [SKStoreProductParameterITunesItemIdentifier: Self.appStoreID]) }
            catch {
                store.dismiss(animated: true)
                openStoreURL()
            }
        }
    }

    private func openStoreURL() {
        if let url = URL(string: "itms-apps://apps.apple.com/app/id\(Self.appStoreID)") { UIApplication.shared.open(url) }
    }

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
        var top = scene?.windows.first { $0.isKeyWindow }?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
}

/// Closes the App Store page when its Done button is tapped.
@MainActor private final class StoreDismisser: NSObject, @preconcurrency SKStoreProductViewControllerDelegate {
    static let shared = StoreDismisser()
    func productViewControllerDidFinish(_ viewController: SKStoreProductViewController) {
        viewController.dismiss(animated: true)
    }
}
