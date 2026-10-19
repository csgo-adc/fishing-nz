import SwiftUI
import UserNotifications

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
                .onOpenURL { viewModel.openSharedWindow($0) }
                .task { NotificationHandler.shared.openTrips = { viewModel.openTripsRequest += 1 } }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .background { Analytics.flush() }
                    // Keeps a booked trip check's text up to date with the latest forecast.
                    if phase == .active { Task { await TripReminders.refresh(force: false) } }
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
