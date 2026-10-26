import Foundation
import SwiftUI

/// The app language: the phone's by default (including iOS Settings → Fishdays - NZ → Language), or one chosen in the
/// app's Settings → Language (docs/languages.md). Android does the same in `I18n.kt`.
enum I18n {
    /// "Use phone language".
    static let phone = ""
    static let languages: [(code: String, name: String)] = [
        ("en", "English"), ("zh-Hans", "简体中文"), ("zh-Hant", "繁體中文"), ("ko", "한국어"), ("hi", "हिन्दी"),
    ]
    private static let choiceKey = "app_language"
    private static let store = TranslatorStore()

    /// The language text is shown in now: "en", "zh-Hans", "zh-Hant", "ko" or "hi".
    static var language: String { store.current.language }
    static func translate(_ text: String) -> String { store.current.translate(text) }

    /// Settings → Language: `phone` or a language code.
    static var choice: String { UserDefaults.standard.string(forKey: choiceKey) ?? phone }

    static func choose(_ code: String) {
        UserDefaults.standard.set(code, forKey: choiceKey)
        reload()
    }

    static func reload() { store.use(resolve()) }

    static func resolve() -> String {
        if !choice.isEmpty { return choice }
        // The bundle's best match for the phone's languages, which also honours the per-app language in iOS Settings.
        return Translator.language(for: Bundle.main.preferredLocalizations + Locale.preferredLanguages)
    }
}

private final class TranslatorStore: @unchecked Sendable {
    private let lock = NSLock()
    private var loaded: [String: Translator] = [:]
    private var active: Translator?

    var current: Translator {
        lock.lock(); defer { lock.unlock() }
        if let active { return active }
        let first = translator(I18n.resolve())
        active = first
        return first
    }

    func use(_ code: String) {
        lock.lock(); defer { lock.unlock() }
        active = translator(code)
    }

    /// Call with the lock held.
    private func translator(_ code: String) -> Translator {
        if let found = loaded[code] { return found }
        var entries: [String: String] = [:]
        if code != Translator.english, let url = Bundle.main.url(forResource: "strings.\(code)", withExtension: "json"),
           let data = try? Data(contentsOf: url), let decoded = try? JSONDecoder().decode([String: String].self, from: data) {
            entries = decoded
        }
        let made = Translator(language: code, entries: entries)
        loaded[code] = made
        return made
    }
}

/// The text in the app's language. SwiftUI's `Text`, `Button`, `Label` and friends get this through
/// `LocalizedText.swift`; other places (notifications, share text) call it.
func tr(_ text: String) -> String { I18n.translate(text) }

/// Publishes the language so the app can redraw in it the moment it changes.
@MainActor final class LanguageSettings: ObservableObject {
    static let shared = LanguageSettings()
    @Published private(set) var language = I18n.language

    func choose(_ code: String) {
        I18n.choose(code)
        Analytics.track("language_changed", ["language": .text(code.isEmpty ? "phone" : code)])
        language = I18n.language
    }

    /// The phone's language may have changed while the app was away.
    func refresh() {
        I18n.reload()
        if language != I18n.language { language = I18n.language }
    }
}
