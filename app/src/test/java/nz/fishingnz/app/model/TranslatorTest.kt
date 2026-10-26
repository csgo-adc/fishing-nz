package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslatorTest {
    private val chinese = Translator("zh-Hans", mapOf(
        "Settings" to "设置",
        "Land" to "岸钓",
        "Boat" to "船钓",
        "{0} fishing" to "{0}钓鱼",
        "{0} km" to "{0} 公里",
        "Gusts to {0} km/h" to "阵风达 {0} 公里/小时",
        "{0} of {1} spots" to "{1} 个钓点中的 {0} 个",
        "Version {0}" to "版本 {0}",
        "Next {0}" to "下一个 {0}",
        "{0} {1}" to "ignored: no words of its own",
        "Empty" to "",
        "Check the forecast." to "查看预报。",
        "Wind has picked up." to "风变大了。",
        "Okay" to "一般", "Breakwater" to "防波堤", "needs attention" to "需注意", "{0} total" to "共 {0}", "chance" to "概率",
        "{0} mm" to "{0} 毫米", "Near {0}" to "{0} 附近", "Finding the best times {0}…" to "正在查找{0}的最佳时间…",
        "Updated {0}" to "更新于 {0}", "Updated {0}." to "更新于 {0}。", "Tide: {0}" to "潮汐：{0}",
    ))

    @Test fun `exact text, patterns and filled gaps`() {
        assertEquals("设置", chinese.translate("Settings"))
        assertEquals("阵风达 38 公里/小时", chinese.translate("Gusts to 38 km/h"))
        assertEquals("版本 1.0.61", chinese.translate("Version 1.0.61"))
        assertEquals("船钓钓鱼", chinese.translate("Boat fishing"))
    }

    @Test fun `gaps can change order`() {
        assertEquals("139 个钓点中的 12 个", chinese.translate("12 of 139 spots"))
    }

    @Test fun `parts joined with a dot are translated one by one`() {
        assertEquals("Auckland · 岸钓 · 6 公里", chinese.translate("Auckland · Land · 6 km"))
    }

    @Test fun `several sentences or lines are translated one by one`() {
        assertEquals("风变大了。查看预报。", chinese.translate("Wind has picked up. Check the forecast."))
        assertEquals("设置\n岸钓", chinese.translate("Settings\nLand"))
    }

    @Test fun `leading symbols, lowercase copies and parts stay apart`() {
        assertEquals("🙂 一般", chinese.translate("🙂 Okay"))
        assertEquals(", 需注意", chinese.translate(", needs attention"))
        assertEquals("730 m E · 防波堤", chinese.translate("730 m E · breakwater"))
        assertEquals("概率 · 共 0.0 毫米", chinese.translate("chance · 0.0 mm total"))
        assertEquals("正在查找Auckland 附近的最佳时间…", chinese.translate("Finding the best times near Auckland…"))
        assertEquals("更新于 上午11:23。潮汐：Auckland", chinese.translate("Updated 11:23 AM. Tide: Auckland"))
    }

    @Test fun `unknown text, blank translations and spacing stay as they are`() {
        assertEquals("Mission Bay", chinese.translate("Mission Bay"))
        assertEquals("Empty", chinese.translate("Empty"))
        assertEquals("  设置 ", chinese.translate("  Settings "))
        assertEquals("a b", chinese.translate("a b"))
        assertEquals("Settings", Translator("en", mapOf("Settings" to "设置")).translate("Settings"))
    }

    @Test fun `dates and times follow the language`() {
        assertEquals("10月28日 周三", chinese.translate("Wed 28 Oct"))
        assertEquals("周四 5日", chinese.translate("Thu 5"))
        assertEquals("5일(목)", Translator("ko", emptyMap()).translate("Thu 5"))
        assertEquals("上午7:00–上午9:00", chinese.translate("7:00 AM–9:00 AM"))
        assertEquals("2026年10月27日 星期二", chinese.translate("Tuesday 27 October 2026"))
        assertEquals("2026年10月", chinese.translate("October 2026"))
        assertEquals("下一个 下午9:06", chinese.translate("Next 9:06 PM"))
        val korean = Translator("ko", emptyMap())
        assertEquals("10월 28일 (수)", korean.translate("Wed 28 Oct"))
        assertEquals("오전 7:00–오후 9:00", korean.translate("7:00 AM–9:00 PM"))
        assertEquals("2026년 10월 26일", korean.translate("26 Oct 2026"))
        assertEquals("10月28日 週三", Translator("zh-Hant", emptyMap()).translate("Wed 28 Oct"))
        val hindi = Translator("hi", emptyMap())
        assertEquals("बुध, 28 अक्तू॰", hindi.translate("Wed 28 Oct"))
        assertEquals("7:00 AM", hindi.translate("7:00 AM"))
    }

    @Test fun `phone languages map to the catalogues`() {
        assertEquals("zh-Hans", Translator.languageFor(listOf("zh-CN")))
        assertEquals("zh-Hans", Translator.languageFor(listOf("zh-Hans-NZ")))
        assertEquals("zh-Hant", Translator.languageFor(listOf("zh-TW")))
        assertEquals("zh-Hant", Translator.languageFor(listOf("zh_HK")))
        assertEquals("zh-Hant", Translator.languageFor(listOf("zh-Hant-NZ")))
        assertEquals("ko", Translator.languageFor(listOf("ja-JP", "ko-KR", "en-NZ")))
        assertEquals("en", Translator.languageFor(listOf("fr-FR", "en-NZ", "ko-KR")))
        assertEquals("hi", Translator.languageFor(listOf("hi-IN")))
        assertEquals("en", Translator.languageFor(listOf("fr-FR")))
    }
}
