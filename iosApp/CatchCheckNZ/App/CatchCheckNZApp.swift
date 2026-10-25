import SwiftUI
import UserNotifications
import WidgetKit

@main
struct CatchCheckNZApp: App {
    @StateObject private var viewModel = FishingViewModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        TripReminders.register()
        UNUserNotificationCenter.current().delegate = NotificationHandler.shared
    }

    var body: some Scene {
        WindowGroup {
            CatchCheckRootView()
                .environmentObject(viewModel)
                // A shared fishing window: a Universal Link from Messages, WhatsApp and the like, or the app's own scheme.
                .onOpenURL { url in
                    // The Tides widget opens the Tide tab on its station; other links are shared windows.
                    if url.scheme == "nz.fishingnz.catchcheck" && url.host == "tide" { viewModel.openTide(from: url) }
                    else { viewModel.openSharedWindow(url) }
                }
                .task { NotificationHandler.shared.openTrips = { viewModel.openTripsRequest += 1 } }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background { Analytics.flush() }
                    // Keeps a booked trip check's text up to date with the latest forecast.
                    if phase == .active {
                        Task { await TripReminders.refresh(force: false) }
                        // Every area's rules and the species pictures stay on the phone for use with no signal.
                        Task { await RulesSync.sync() }
                        // The Tides widget reloads with the latest LINZ table whenever the app opens.
                        WidgetCenter.shared.reloadAllTimelines()
                    }
                }
        }
    }
}

/// Shows trip checks while the app is open and opens Trips when one is tapped.
final class NotificationHandler: NSObject, UNUserNotificationCenterDelegate, @unchecked Sendable {
    static let shared = NotificationHandler()
    @MainActor var openTrips: (() -> Void)?

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard [TripReminders.requestID, TripReminders.checkInID].contains(response.notification.request.identifier) else { return }
        await MainActor.run { openTrips?() }
    }
}
