import Foundation

/// How often one version has been offered: its number, how many pop-ups so far and the day of the last one.
struct UpdatePromptRecord: Equatable, Codable {
    var build = 0
    var count = 0
    var lastShownDay = Int.min
}

/// When the "Update available" pop-up may appear (docs/in-app-updates.md), the same rules as Android's `UpdatePolicy`.
/// At most three pop-ups per version: the first time the App Store has it, 3 days after the first and 7 days after
/// the second (day 10). After that only the More row and a badge on More remain. A newer version starts the count
/// again. Never on the first day the app is used, never twice in one day and never on a day another notice was shown.
enum UpdatePolicy {
    static let maxPrompts = 3
    private static let waitDays = [0, 3, 7]

    static func record(_ record: UpdatePromptRecord, for available: Int) -> UpdatePromptRecord {
        available > record.build ? UpdatePromptRecord(build: available) : record
    }

    static func shouldPrompt(_ record: UpdatePromptRecord, available: Int, installed: Int, today: Int, firstUseDay: Int, lastNoticeDay: Int?) -> Bool {
        guard available > installed, today > firstUseDay, lastNoticeDay != today else { return false }
        let current = Self.record(record, for: available)
        guard current.count < maxPrompts else { return false }
        if current.count == 0 { return true }
        return today - current.lastShownDay >= waitDays[current.count]
    }

    static func shown(_ record: UpdatePromptRecord, available: Int, today: Int) -> UpdatePromptRecord {
        var current = Self.record(record, for: available)
        current.count += 1
        current.lastShownDay = today
        return current
    }

    static func remindersDone(_ record: UpdatePromptRecord, available: Int, installed: Int) -> Bool {
        available > installed && Self.record(record, for: available).count >= maxPrompts
    }

    static func required(installed: Int, minimum: Int) -> Bool { installed < minimum }

    /// A version such as 1.0.61 as one comparable number, for the App Store, which reports versions but not builds.
    static func versionNumber(_ version: String) -> Int {
        let parts = version.split(separator: ".").prefix(3).map { Int($0.filter(\.isNumber)) ?? 0 }
        let padded = parts + Array(repeating: 0, count: max(0, 3 - parts.count))
        return padded[0] * 1_000_000 + padded[1] * 1_000 + padded[2]
    }
}
