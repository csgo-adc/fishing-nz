import Foundation

/// The Account header and country list match Android's `AccountModelsTest` and `CatchLogTest`. Run with `tools/test_account_profile_swift.sh`.
@main struct AccountProfileRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        func profile(_ name: String = "", createdAt: String?) -> AccountProfile {
            AccountProfile(id: "u1", email: "a@b.nz", displayName: name, countryCode: "NZ", plan: "free", hasPasswordField: nil, createdAt: createdAt)
        }
        try expect(profile(createdAt: "2026-10-01T10:00:00.000Z").memberSince == "Member since October 2026", "Fractional seconds")
        try expect(profile(createdAt: "2026-09-30T11:30:00Z").memberSince == "Member since October 2026", "New Zealand month")
        try expect(profile(createdAt: nil).memberSince == nil && profile(createdAt: "not a date").memberSince == nil, "No sign-up time")
        let decoded = try JSONDecoder().decode(AccountProfile.self, from: Data(#"{"id":"u1","email":"a@b.nz","display_name":"","country_code":"NZ","plan":"free","created_at":"2026-10-01T10:00:00.000Z"}"#.utf8))
        try expect(decoded.createdAt == "2026-10-01T10:00:00.000Z", "Decodes created_at")
        try expect(profile("  Tama  te Rangi Smith", createdAt: nil).initials == "TT" && profile(createdAt: nil).initials == nil, "Initials")

        let english = Locale(identifier: "en")
        let countries = Countries.all(locale: english)
        try expect(countries.first == .init(code: "NZ", name: "New Zealand"), "New Zealand first")
        try expect(countries.filter { $0.code == "NZ" }.count == 1, "New Zealand once")
        try expect(countries.contains { $0.code == "AU" && $0.name == "Australia" }, "Australia listed")
        let rest = countries.dropFirst().map(\.name)
        try expect(rest == rest.sorted { $0.compare($1, locale: english) == .orderedAscending }, "Sorted by name")
        try expect(Countries.flag("NZ") == "🇳🇿" && Countries.flag("nz").isEmpty && Countries.flag("").isEmpty, "Flags")

        var released = CatchEntry(id: "d", caughtAt: .now, species: "Gurnard"); released.deleted = true
        var three = CatchEntry(id: "a", caughtAt: .now, species: "Snapper"); three.count = 3
        let totals = CatchLog.totals([three, CatchEntry(id: "b", caughtAt: .now, species: " snapper "), CatchEntry(id: "c", caughtAt: .now, species: "Kahawai"),
                                      released, CatchEntry(id: "e", caughtAt: .now, species: " ")])
        try expect(totals.fish == 6 && totals.species == 2, "Totals skip deleted and blank entries")
        print("Account profile: \(checks) checks passed")
    }
}
