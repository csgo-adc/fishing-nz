import Foundation

/// Turns the app's English text into another language at the moment it is shown (docs/languages.md). The app keeps
/// working in English underneath, so code that compares labels such as "Next 3 days" is unaffected.
///
/// A text is looked up in this order, exactly as Android's `Translator.kt`, from the same catalogues
/// (`i18n/strings.<language>.json`, keyed by the English text):
/// 1. exactly;
/// 2. as a pattern, for keys with numbered gaps: "Gusts to {0} km/h" also translates "Gusts to 38 km/h", and what fills
///    each gap is translated too;
/// 3. piece by piece when it joins parts with " · ", lines or sentences;
/// 4. otherwise it stays English, with dates and times written the language's way.
final class Translator: @unchecked Sendable {
    static let english = "en"
    static let separator = " · "
    private static let maxDepth = 4
    private static let placeholder = try! NSRegularExpression(pattern: "\\{(\\d+)\\}")
    private static let sentenceBreak = try! NSRegularExpression(pattern: "(?<=[.!?])\\s+(?=[A-Z0-9\"“(])")
    private static let symbolPrefix = try! NSRegularExpression(pattern: "^([^\\p{L}\\p{N}\\s]+\\s+)([\\s\\S]*\\S)$")

    private struct Template { let regex: NSRegularExpression; let anchor: String; let order: [Int]; let target: String; let spansParts: Bool; let spansSentences: Bool }

    let language: String
    private var exact: [String: String] = [:]
    private var templates: [Template] = []
    private var cache: [String: String] = [:]
    private let lock = NSLock()

    init(language: String, entries: [String: String]) {
        self.language = language
        var patterns: [(Int, Template)] = []
        for (source, target) in entries where !target.trimmingCharacters(in: .whitespaces).isEmpty {
            let ns = source as NSString
            let matches = Self.placeholder.matches(in: source, range: NSRange(location: 0, length: ns.length))
            if matches.isEmpty { exact[source] = target; continue }
            var literals: [String] = []
            var cursor = 0
            for match in matches {
                literals.append(ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor)))
                cursor = match.range.location + match.range.length
            }
            literals.append(ns.substring(from: cursor))
            // A pattern needs some words of its own, or "{0} {1}" would match everything.
            guard literals.joined().filter(\.isLetter).count >= 2 else { continue }
            var pattern = "^"
            for (index, literal) in literals.enumerated() {
                pattern += NSRegularExpression.escapedPattern(for: literal)
                if index < literals.count - 1 { pattern += "(.+?)" }
            }
            pattern += "$"
            guard let regex = try? NSRegularExpression(pattern: pattern) else { continue }
            let order = matches.map { Int(ns.substring(with: $0.range(at: 1))) ?? 0 }
            let anchor = literals.max { $0.count < $1.count } ?? ""
            patterns.append((literals.reduce(0) { $0 + ($1 as NSString).length }, Template(regex: regex, anchor: anchor, order: order, target: target, spansParts: source.contains(Self.separator),
                                                                                             spansSentences: Self.sentenceBreak.firstMatch(in: source, range: NSRange(location: 0, length: ns.length)) != nil)))
        }
        // The most specific pattern wins: the one with the most text of its own. Ties keep a stable order.
        templates = patterns.enumerated().sorted { ($0.element.0, -$0.offset) > ($1.element.0, -$1.offset) }.map(\.element.1)
    }

    func translate(_ text: String) -> String {
        if language == Self.english || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return text }
        lock.lock()
        if let cached = cache[text] { lock.unlock(); return cached }
        lock.unlock()
        let result = compute(text, depth: 0)
        lock.lock()
        if cache.count > 3000 { cache.removeAll() }
        cache[text] = result
        lock.unlock()
        return result
    }

    private func compute(_ text: String, depth: Int) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let range = text.range(of: trimmed) else { return text }
        return String(text[..<range.lowerBound]) + translateTrimmed(trimmed, depth: depth) + String(text[range.upperBound...])
    }

    private func translateTrimmed(_ text: String, depth: Int) -> String {
        if let found = exact[text] { return found }
        // A lowercase copy of a known text, such as "breakwater" in "730 m E · breakwater".
        if text.first?.isLowercase == true, let found = exact[text.prefix(1).uppercased() + text.dropFirst()] { return found }
        if depth < Self.maxDepth {
            let ns = text as NSString
            // A leading emoji or symbol ("🙂 Okay", "· Protected areas", ", needs attention") stays as it is.
            if let match = Self.symbolPrefix.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) {
                return ns.substring(with: match.range(at: 1)) + compute(ns.substring(with: match.range(at: 2)), depth: depth + 1)
            }
            for template in templates where text.contains(template.anchor) {
                guard let match = template.regex.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { continue }
                let groups = template.order.indices.map { ns.substring(with: match.range(at: $0 + 1)) }
                // A gap never swallows several " · " parts unless the pattern itself has them.
                if !template.spansParts && groups.contains(where: { $0.contains(Self.separator) }) { continue }
                if !template.spansSentences && groups.contains(where: { Self.sentenceBreak.firstMatch(in: $0, range: NSRange(location: 0, length: ($0 as NSString).length)) != nil }) { continue }
                var result = template.target
                for (index, number) in template.order.enumerated() {
                    result = result.replacingOccurrences(of: "{\(number)}", with: compute(groups[index], depth: depth + 1))
                }
                return result
            }
            if text.contains(Self.separator) {
                return text.components(separatedBy: Self.separator).map { compute($0, depth: depth + 1) }.joined(separator: Self.separator)
            }
            if text.contains("\n") {
                return text.components(separatedBy: "\n").map { compute($0, depth: depth + 1) }.joined(separator: "\n")
            }
            let sentences = Self.split(text, by: Self.sentenceBreak)
            if sentences.count > 1 {
                return sentences.map { compute($0, depth: depth + 1) }.joined(separator: language.hasPrefix("zh") ? "" : " ")
            }
            // A lowercase copy of a known pattern, such as "near Auckland" for "Near {0}".
            if text.first?.isLowercase == true {
                let capital = text.prefix(1).uppercased() + text.dropFirst()
                let translated = translateTrimmed(capital, depth: depth + 1)
                if translated != DateWords.localize(capital, language: language) { return translated }
            }
        }
        return DateWords.localize(text, language: language)
    }

    private static func split(_ text: String, by regex: NSRegularExpression) -> [String] {
        let ns = text as NSString
        var parts: [String] = []
        var cursor = 0
        for match in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            parts.append(ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor)))
            cursor = match.range.location + match.range.length
        }
        parts.append(ns.substring(from: cursor))
        return parts
    }

    /// The catalogue language for a list of preferred language tags (most preferred first), or English.
    static func language(for tags: [String]) -> String {
        for raw in tags {
            let parts = raw.replacingOccurrences(of: "_", with: "-").lowercased().split(separator: "-").map(String.init)
            switch parts.first {
            case "zh": return parts.contains { ["hant", "tw", "hk", "mo"].contains($0) } ? "zh-Hant" : "zh-Hans"
            case "ko": return "ko"
            case "hi": return "hi"
            case "en": return english
            default: continue
            }
        }
        return english
    }
}

/// Dates and times the app writes in English ("Wed 28 Oct", "October 2026", "7:00 AM"), in another language's order
/// and words. Matches Android's `DateWords`.
enum DateWords {
    private static let shortDays = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    private static let longDays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    private static let shortMonths = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    private static let longMonths = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
    private static let days: [String: (short: [String], long: [String])] = [
        "zh-Hans": (["周一", "周二", "周三", "周四", "周五", "周六", "周日"], ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]),
        "zh-Hant": (["週一", "週二", "週三", "週四", "週五", "週六", "週日"], ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]),
        "ko": (["월", "화", "수", "목", "금", "토", "일"], ["월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"]),
        "hi": (["सोम", "मंगल", "बुध", "गुरु", "शुक्र", "शनि", "रवि"], ["सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार"]),
    ]
    private static let hindiShortMonths = ["जन॰", "फ़र॰", "मार्च", "अप्रैल", "मई", "जून", "जुल॰", "अग॰", "सित॰", "अक्तू॰", "नव॰", "दिस॰"]
    private static let hindiLongMonths = ["जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्तूबर", "नवंबर", "दिसंबर"]

    private static func regex(_ pattern: String) -> NSRegularExpression { try! NSRegularExpression(pattern: pattern) }
    private static let longDate = regex("\\b(\(longDays.joined(separator: "|"))),? (\\d{1,2}) (\(longMonths.joined(separator: "|")))(?: (\\d{4}))?\\b")
    private static let shortDate = regex("\\b(\(shortDays.joined(separator: "|"))),? (\\d{1,2}) (\(shortMonths.joined(separator: "|")))(?: (\\d{4}))?\\b")
    private static let dayMonth = regex("\\b(\\d{1,2}) (\(longMonths.joined(separator: "|"))|\(shortMonths.joined(separator: "|")))(?: (\\d{4}))?\\b")
    private static let monthYear = regex("\\b(\(longMonths.joined(separator: "|"))) (\\d{4})\\b")
    private static let dayOfMonth = regex("\\b(\(shortDays.joined(separator: "|"))) (\\d{1,2})\\b")
    private static let clock = regex("\\b(\\d{1,2})(:\\d{2})? ?(AM|PM)\\b")

    private static func month(_ name: String) -> Int { longMonths.firstIndex(of: name) ?? shortMonths.firstIndex(of: name) ?? 0 }

    /// Replaces each match of `regex` with what `make` returns for its capture groups ("" for a group that didn't take part).
    private static func replace(_ text: String, _ regex: NSRegularExpression, _ make: ([String]) -> String) -> String {
        let ns = text as NSString
        var result = ""
        var cursor = 0
        for match in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            result += ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            let groups = (1..<match.numberOfRanges).map { index -> String in
                let range = match.range(at: index)
                return range.location == NSNotFound ? "" : ns.substring(with: range)
            }
            result += make(groups)
            cursor = match.range.location + match.range.length
        }
        return result + ns.substring(from: cursor)
    }

    static func localize(_ text: String, language: String) -> String {
        guard let names = days[language] else { return text }
        let hindi = language == "hi", korean = language == "ko"
        func dayAndMonth(_ day: String, _ monthName: String, _ year: String, long: Bool) -> String {
            let m = month(monthName)
            if hindi { return "\(day) \((long ? hindiLongMonths : hindiShortMonths)[m])" + (year.isEmpty ? "" : " \(year)") }
            if korean { return (year.isEmpty ? "" : "\(year)년 ") + "\(m + 1)월 \(day)일" }
            return (year.isEmpty ? "" : "\(year)年") + "\(m + 1)月\(day)日"
        }
        var result = replace(text, longDate) { g in
            let name = names.long[longDays.firstIndex(of: g[0]) ?? 0]
            let date = dayAndMonth(g[1], g[2], g[3], long: true)
            return hindi ? "\(name), \(date)" : "\(date) \(name)"
        }
        result = replace(result, shortDate) { g in
            let name = names.short[shortDays.firstIndex(of: g[0]) ?? 0]
            let date = dayAndMonth(g[1], g[2], g[3], long: false)
            return hindi ? "\(name), \(date)" : korean ? "\(date) (\(name))" : "\(date) \(name)"
        }
        result = replace(result, dayOfMonth) { g in
            let name = names.short[shortDays.firstIndex(of: g[0]) ?? 0]
            return hindi ? "\(name) \(g[1])" : korean ? "\(g[1])일(\(name))" : "\(name) \(g[1])日"
        }
        result = replace(result, dayMonth) { g in dayAndMonth(g[0], g[1], g[2], long: g[1].count > 3) }
        result = replace(result, monthYear) { g in
            let m = month(g[0])
            return hindi ? "\(hindiLongMonths[m]) \(g[1])" : korean ? "\(g[1])년 \(m + 1)월" : "\(g[1])年\(m + 1)月"
        }
        if !hindi {
            result = replace(result, clock) { g in
                let period = korean ? (g[2] == "AM" ? "오전 " : "오후 ") : (g[2] == "AM" ? "上午" : "下午")
                return period + g[0] + g[1]
            }
        }
        return result
    }
}
