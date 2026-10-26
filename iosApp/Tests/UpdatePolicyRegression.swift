import Foundation

/// The iPhone update pop-up schedule matches Android's `UpdatePolicyTest`. Run with `tools/test_update_policy_swift.sh`.
@main struct UpdatePolicyRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let installed = 60, available = 62, firstUse = 100
        func prompt(_ record: UpdatePromptRecord, _ today: Int, notice: Int? = nil, build: Int = available) -> Bool {
            UpdatePolicy.shouldPrompt(record, available: build, installed: installed, today: today, firstUseDay: firstUse, lastNoticeDay: notice)
        }

        var record = UpdatePromptRecord()
        try expect(prompt(record, 200), "First pop-up on day 0")
        record = UpdatePolicy.shown(record, available: available, today: 200)
        try expect(!prompt(record, 201) && !prompt(record, 202), "Nothing on days 1 and 2")
        try expect(prompt(record, 203), "Second pop-up on day 3")
        record = UpdatePolicy.shown(record, available: available, today: 203)
        try expect(!prompt(record, 209), "Nothing before day 10")
        try expect(prompt(record, 210), "Third pop-up on day 10")
        record = UpdatePolicy.shown(record, available: available, today: 210)
        try expect(record.count == 3 && !prompt(record, 400), "No fourth pop-up")
        try expect(UpdatePolicy.remindersDone(record, available: available, installed: installed), "Badge after the third")

        try expect(prompt(record, 300, build: available + 1), "A newer version starts again")
        let next = UpdatePolicy.shown(record, available: available + 1, today: 300)
        try expect(next == UpdatePromptRecord(build: available + 1, count: 1, lastShownDay: 300), "Newer version record")

        try expect(!prompt(UpdatePromptRecord(), firstUse), "Never on the first day")
        try expect(!prompt(UpdatePromptRecord(), 200, notice: 200), "Never on a day with another notice")
        try expect(prompt(UpdatePromptRecord(), 200, notice: 199), "Another notice yesterday is fine")
        try expect(!UpdatePolicy.shouldPrompt(UpdatePromptRecord(), available: installed, installed: installed, today: 200, firstUseDay: firstUse, lastNoticeDay: nil), "Up to date")

        try expect(UpdatePolicy.required(installed: 58, minimum: 59) && !UpdatePolicy.required(installed: 59, minimum: 59), "Minimum build")
        try expect(UpdatePolicy.versionNumber("1.0.61") > UpdatePolicy.versionNumber("1.0.9"), "1.0.61 is newer than 1.0.9")
        try expect(UpdatePolicy.versionNumber("1.1") == UpdatePolicy.versionNumber("1.1.0"), "Missing patch is zero")
        print("Update policy: \(checks) checks passed")
    }
}
