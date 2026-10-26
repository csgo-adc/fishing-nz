# Languages

From 1.0.60 both apps show English, 简体中文, 繁體中文, 한국어 and हिन्दी. They follow the phone's language by default
(including Android 13+ and iOS per-app language settings); Settings → Language can choose another. Wave 2 (te reo
Māori, Samoan, Tagalog, Tongan) waits for native-speaker review.

- The apps keep working in English underneath. Text is translated when shown: Android's `ui/LocalizedText.kt` (a
  same-package `Text`) and `tr()`, iPhone's `Views/LocalizedText.swift` overloads and `tr()`, both via `Translator`.
- One catalogue per language, keyed by the English text, shared by both apps: `i18n/strings.<language>.json`.
  Keys may have numbered gaps ("Gusts to {0} km/h"); dates and times are reworded per language.
- Place names, fish species names and MPI rule wording stay English on purpose.
- After changing app text: `python3 tools/i18n/extract.py`, add translations, then `python3 tools/i18n/check.py`
  (texts that stay English go in `tools/i18n/ignore.json`; joined texts in `tools/i18n/extra.json`). Debug Android
  builds log untranslated on-screen text under the `I18nMiss` tag.
