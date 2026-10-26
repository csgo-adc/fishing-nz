import Foundation

/// The Tides widget's language: the phone's (docs/languages.md). It reads the same catalogues as the app; a language
/// chosen in the app's own Settings stays with the app, as widgets run in their own process.
private let widgetTranslator: Translator = {
    let code = Translator.language(for: Locale.preferredLanguages)
    guard code != Translator.english, let url = Bundle.main.url(forResource: "strings.\(code)", withExtension: "json"),
          let data = try? Data(contentsOf: url), let entries = try? JSONDecoder().decode([String: String].self, from: data) else {
        return Translator(language: Translator.english, entries: [:])
    }
    return Translator(language: code, entries: entries)
}()

func tr(_ text: String) -> String { widgetTranslator.translate(text) }
