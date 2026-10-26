import Foundation

/// The iPhone translator matches Android's `TranslatorTest`. Run with `tools/test_translator_swift.sh`.
@main struct TranslatorRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ actual: String, _ expected: String) throws {
        checks += 1
        if actual != expected { throw Failure.assertion("expected “\(expected)”, got “\(actual)”") }
    }

    static func main() throws {
        let chinese = Translator(language: "zh-Hans", entries: [
            "Settings": "设置", "Land": "岸钓", "Boat": "船钓", "{0} fishing": "{0}钓鱼", "{0} km": "{0} 公里",
            "Gusts to {0} km/h": "阵风达 {0} 公里/小时", "{0} of {1} spots": "{1} 个钓点中的 {0} 个", "Version {0}": "版本 {0}",
            "Next {0}": "下一个 {0}", "{0} {1}": "ignored: no words of its own", "Empty": "",
            "Check the forecast.": "查看预报。", "Wind has picked up.": "风变大了。",
            "Okay": "一般", "Breakwater": "防波堤", "needs attention": "需注意", "{0} total": "共 {0}", "chance": "概率", "{0} mm": "{0} 毫米", "Near {0}": "{0} 附近", "Finding the best times {0}…": "正在查找{0}的最佳时间…",
            "Updated {0}": "更新于 {0}", "Updated {0}.": "更新于 {0}。", "Tide: {0}": "潮汐：{0}",
        ])
        try expect(chinese.translate("Settings"), "设置")
        try expect(chinese.translate("Gusts to 38 km/h"), "阵风达 38 公里/小时")
        try expect(chinese.translate("Version 1.0.61"), "版本 1.0.61")
        try expect(chinese.translate("Boat fishing"), "船钓钓鱼")
        try expect(chinese.translate("12 of 139 spots"), "139 个钓点中的 12 个")
        try expect(chinese.translate("Auckland · Land · 6 km"), "Auckland · 岸钓 · 6 公里")
        try expect(chinese.translate("Wind has picked up. Check the forecast."), "风变大了。查看预报。")
        try expect(chinese.translate("Settings\nLand"), "设置\n岸钓")
        try expect(chinese.translate("🙂 Okay"), "🙂 一般")
        try expect(chinese.translate(", needs attention"), ", 需注意")
        try expect(chinese.translate("730 m E · breakwater"), "730 m E · 防波堤")
        try expect(chinese.translate("chance · 0.0 mm total"), "概率 · 共 0.0 毫米")
        try expect(chinese.translate("Finding the best times near Auckland…"), "正在查找Auckland 附近的最佳时间…")
        try expect(chinese.translate("Updated 11:23 AM. Tide: Auckland"), "更新于 上午11:23。潮汐：Auckland")
        try expect(chinese.translate("Mission Bay"), "Mission Bay")
        try expect(chinese.translate("Empty"), "Empty")
        try expect(chinese.translate("  Settings "), "  设置 ")
        try expect(chinese.translate("a b"), "a b")
        try expect(Translator(language: "en", entries: ["Settings": "设置"]).translate("Settings"), "Settings")

        try expect(chinese.translate("Wed 28 Oct"), "10月28日 周三")
        try expect(chinese.translate("Thu 5"), "周四 5日")
        try expect(Translator(language: "ko", entries: [:]).translate("Thu 5"), "5일(목)")
        try expect(chinese.translate("7:00 AM–9:00 AM"), "上午7:00–上午9:00")
        try expect(chinese.translate("Tuesday 27 October 2026"), "2026年10月27日 星期二")
        try expect(chinese.translate("October 2026"), "2026年10月")
        try expect(chinese.translate("Next 9:06 PM"), "下一个 下午9:06")
        let korean = Translator(language: "ko", entries: [:])
        try expect(korean.translate("Wed 28 Oct"), "10월 28일 (수)")
        try expect(korean.translate("7:00 AM–9:00 PM"), "오전 7:00–오후 9:00")
        try expect(korean.translate("26 Oct 2026"), "2026년 10월 26일")
        try expect(Translator(language: "zh-Hant", entries: [:]).translate("Wed 28 Oct"), "10月28日 週三")
        let hindi = Translator(language: "hi", entries: [:])
        try expect(hindi.translate("Wed 28 Oct"), "बुध, 28 अक्तू॰")
        try expect(hindi.translate("7:00 AM"), "7:00 AM")

        for (tags, expected) in [(["zh-CN"], "zh-Hans"), (["zh-Hans-NZ"], "zh-Hans"), (["zh-TW"], "zh-Hant"), (["zh_HK"], "zh-Hant"),
                                 (["zh-Hant-NZ"], "zh-Hant"), (["ja-JP", "ko-KR", "en-NZ"], "ko"), (["fr-FR", "en-NZ", "ko-KR"], "en"),
                                 (["hi-IN"], "hi"), (["fr-FR"], "en")] {
            try expect(Translator.language(for: tags), expected)
        }
        print("Translator: \(checks) checks passed")
    }
}
