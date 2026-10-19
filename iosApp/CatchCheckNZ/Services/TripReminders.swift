import Foundation
import UserNotifications
import BackgroundTasks

/// The pre-trip forecast check on iPhone. A local notification is booked for the check time (see `TripCheck`). When
/// iOS gives the app background time before then, and whenever the app opens, the trip's hours are checked again with
/// the latest forecast and the booked notification's text is replaced with what changed. Android runs the check in a
/// WorkManager job (`TripReminders.kt`).
enum TripReminders {
    static let taskID = "nz.fishingnz.catchcheck.tripcheck"
    static let requestID = "trip-check"
    static let checkInID = "trip-check-in"
    private static let provisional = "Time to check the latest forecast before you go. Tap to see your trip."
    nonisolated(unsafe) private static var lastRefresh: Date?

    /// Call once at launch, before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskID, using: nil) { task in
            let box = TaskBox(task: task)
            let work = Task { await refresh(); box.task.setTaskCompleted(success: true) }
            task.expirationHandler = { work.cancel() }
        }
    }
    /// The system hands the task to one handler and expects completion from it; the box carries it into the async check.
    private struct TaskBox: @unchecked Sendable { let task: BGTask }

    /// Books the check for `trip`, replacing any earlier one. Returns the check time, or nil when none fits.
    @discardableResult
    static func schedule(_ trip: Recommendation) async -> Date? {
        cancel()
        guard let start = trip.startsAt, let at = TripCheck.checkTime(start: start) else { return nil }
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
        guard granted else { return at }
        try? await book(title: TripCheck.message(trip: trip, latest: nil).title, body: provisional, at: at)
        let request = BGAppRefreshTaskRequest(identifier: taskID)
        request.earliestBeginDate = at.addingTimeInterval(-7_200)
        try? BGTaskScheduler.shared.submit(request)
        return at
    }

    static func cancel() {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [requestID, checkInID])
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskID)
    }

    /// "Back safe?" at `at`, after the trip plan was shared. Replaces an earlier one.
    static func scheduleCheckIn(_ trip: Recommendation, at: Date) async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
        let message = TripPlan.checkInMessage(trip)
        try? await book(title: message.title, body: message.body, at: at, id: checkInID)
    }

    /// Whether notifications are allowed, for the Trips card.
    static func allowed() async -> Bool {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        return settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
    }

    /// Checks the active trip again and rewrites the booked notification. Does nothing when no check is booked, and runs
    /// at most every 30 minutes when the app opens.
    static func refresh(force: Bool = true) async {
        if !force, let lastRefresh, Date.now.timeIntervalSince(lastRefresh) < 1_800 { return }
        let center = UNUserNotificationCenter.current()
        guard let pending = await center.pendingNotificationRequests().first(where: { $0.identifier == requestID }),
              let at = (pending.trigger as? UNCalendarNotificationTrigger)?.nextTriggerDate(), at.timeIntervalSinceNow < 86_400,
              let trip = TripStore.load().active else { return }
        lastRefresh = .now
        let latest = await FishingScoringService.recheck(trip, priority: FishingViewModel.storedPriority(),
                                                         land: FishingViewModel.storedLandPreferences(), tidePhase: FishingViewModel.storedTidePhase())
        guard let latest else { return }
        let message = TripCheck.message(trip: trip, latest: TripCheck.recommendation(latest))
        try? await book(title: message.title, body: message.body, at: at)
    }

    private static func book(title: String, body: String, at: Date, id: String = requestID) async throws {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        let parts = calendar.dateComponents(in: calendar.timeZone, from: at)
        let trigger = UNCalendarNotificationTrigger(dateMatching: DateComponents(calendar: calendar, timeZone: calendar.timeZone,
            year: parts.year, month: parts.month, day: parts.day, hour: parts.hour, minute: parts.minute), repeats: false)
        try await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
    }
}
