package nz.fishingnz.app.model

/**
 * Turns the app's English text into another language at the moment it is shown (docs/languages.md). The app keeps
 * working in English underneath, so the code that compares labels such as "Next 3 days" or "Boat" is unaffected.
 *
 * A text is looked up in this order:
 * 1. exactly, in the language's catalogue (`i18n/strings.<language>.json`, keyed by the English text);
 * 2. as a pattern, for catalogue keys with numbered gaps: "Gusts to {0} km/h" also translates "Gusts to 38 km/h", and
 *    what fills each gap is translated too, so "{0} fishing" turns "Boat fishing" into the language's words for both;
 * 3. piece by piece when it joins several parts with " · ", several lines or several sentences;
 * 4. otherwise it stays English, with dates and times written the language's way ("Wed 28 Oct", "7:00 AM").
 *
 * The iPhone app has the same rules in `Translator.swift`; both read the same catalogues.
 */
class Translator(val language: String, entries: Map<String, String>, private val onMiss: ((String) -> Unit)? = null) {
    private class Template(val regex: Regex, val anchor: String, val order: List<Int>, val target: String, val spansParts: Boolean, val spansSentences: Boolean)

    private val exact = HashMap<String, String>()
    private val templates: List<Template>
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3000
    }

    init {
        val patterns = mutableListOf<Pair<Int, Template>>()
        for ((source, target) in entries) {
            if (target.isBlank()) continue
            if (!placeholder.containsMatchIn(source)) { exact[source] = target; continue }
            val literals = placeholder.split(source)
            // A pattern needs some words of its own, or "{0} {1}" would match everything.
            if (literals.joinToString("").count(Char::isLetter) < 2) continue
            val regex = buildString {
                append('^')
                literals.forEachIndexed { index, literal ->
                    if (literal.isNotEmpty()) append(Regex.escape(literal))
                    if (index < literals.lastIndex) append("(.+?)")
                }
                append('$')
            }
            val order = placeholder.findAll(source).map { it.groupValues[1].toInt() }.toList()
            patterns += literals.sumOf { it.length } to Template(Regex(regex), literals.maxBy { it.length }, order, target, source.contains(SEPARATOR), sentenceBreak.containsMatchIn(source))
        }
        // The most specific pattern wins: the one with the most text of its own.
        templates = patterns.sortedByDescending { it.first }.map { it.second }
    }

    fun translate(text: String): String {
        if (language == ENGLISH || text.isBlank()) return text
        synchronized(cache) { cache[text] }?.let { return it }
        val result = compute(text, 0)
        synchronized(cache) { cache[text] = result }
        // Debug builds report English that reached the screen untranslated, to find texts the catalogues lack.
        if (result == text && text.count { it in 'a'..'z' || it in 'A'..'Z' } >= 3) onMiss?.invoke(text)
        return result
    }

    private fun compute(text: String, depth: Int): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        val lead = text.substring(0, text.indexOf(trimmed))
        val tail = text.substring(lead.length + trimmed.length)
        return lead + translateTrimmed(trimmed, depth) + tail
    }

    private fun translateTrimmed(text: String, depth: Int): String {
        exact[text]?.let { return it }
        // A lowercase copy of a known text, such as "breakwater" in "730 m E · breakwater".
        if (text[0].isLowerCase()) exact[text.replaceFirstChar { it.uppercaseChar() }]?.let { return it }
        if (depth < MAX_DEPTH) {
            // A leading emoji or symbol ("🙂 Okay", "· Protected areas", ", needs attention") stays as it is.
            symbolPrefix.matchEntire(text)?.let { return it.groupValues[1] + compute(it.groupValues[2], depth + 1) }
            for (template in templates) {
                if (!text.contains(template.anchor)) continue
                val match = template.regex.matchEntire(text) ?: continue
                val groups = match.groupValues.drop(1)
                // A gap never swallows several " · " parts unless the pattern itself has them.
                if (!template.spansParts && groups.any { it.contains(SEPARATOR) }) continue
                if (!template.spansSentences && groups.any { sentenceBreak.containsMatchIn(it) }) continue
                val fills = groups.map { compute(it, depth + 1) }
                var result = template.target
                template.order.forEachIndexed { index, number -> result = result.replace("{$number}", fills[index]) }
                return result
            }
            if (text.contains(SEPARATOR)) return text.split(SEPARATOR).joinToString(SEPARATOR) { compute(it, depth + 1) }
            if (text.contains('\n')) return text.split('\n').joinToString("\n") { compute(it, depth + 1) }
            val sentences = sentenceBreak.split(text)
            if (sentences.size > 1) return sentences.joinToString(if (language.startsWith("zh")) "" else " ") { compute(it, depth + 1) }
            // A lowercase copy of a known pattern, such as "near Auckland" for "Near {0}".
            if (text[0].isLowerCase()) {
                val capital = text.replaceFirstChar { it.uppercaseChar() }
                val translated = translateTrimmed(capital, depth + 1)
                if (translated != DateWords.localize(capital, language)) return translated
            }
        }
        return DateWords.localize(text, language)
    }

    companion object {
        const val ENGLISH = "en"
        const val SEPARATOR = " · "
        private const val MAX_DEPTH = 4
        private val placeholder = Regex("\\{(\\d+)\\}")
        private val sentenceBreak = Regex("(?<=[.!?])\\s+(?=[A-Z0-9\"“(])")
        private val symbolPrefix = Regex("^([^\\p{L}\\p{N}\\s]+\\s+)([\\s\\S]*\\S)$")

        /** The catalogue language for a list of preferred locale tags (most preferred first), or English. */
        fun languageFor(tags: List<String>): String {
            for (raw in tags) {
                val tag = raw.replace('_', '-')
                val parts = tag.lowercase().split('-')
                when (parts.firstOrNull()) {
                    "zh" -> return if (parts.any { it == "hant" || it == "tw" || it == "hk" || it == "mo" }) "zh-Hant" else "zh-Hans"
                    "ko" -> return "ko"
                    "hi" -> return "hi"
                    "en" -> return ENGLISH
                }
            }
            return ENGLISH
        }
    }
}

/** Dates and times the app writes in English ("Wed 28 Oct", "October 2026", "7:00 AM"), in another language's order and words. */
object DateWords {
    private val shortDays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val longDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val shortMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val longMonths = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private val days = mapOf(
        "zh-Hans" to (listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日") to listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")),
        "zh-Hant" to (listOf("週一", "週二", "週三", "週四", "週五", "週六", "週日") to listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")),
        "ko" to (listOf("월", "화", "수", "목", "금", "토", "일") to listOf("월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일")),
        "hi" to (listOf("सोम", "मंगल", "बुध", "गुरु", "शुक्र", "शनि", "रवि") to listOf("सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार")),
    )
    private val hindiShortMonths = listOf("जन॰", "फ़र॰", "मार्च", "अप्रैल", "मई", "जून", "जुल॰", "अग॰", "सित॰", "अक्तू॰", "नव॰", "दिस॰")
    private val hindiLongMonths = listOf("जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून", "जुलाई", "अगस्त", "सितंबर", "अक्तूबर", "नवंबर", "दिसंबर")

    private fun alternatives(words: List<String>) = words.joinToString("|")
    private val longDate = Regex("\\b(${alternatives(longDays)}),? (\\d{1,2}) (${alternatives(longMonths)})(?: (\\d{4}))?\\b")
    private val shortDate = Regex("\\b(${alternatives(shortDays)}),? (\\d{1,2}) (${alternatives(shortMonths)})(?: (\\d{4}))?\\b")
    private val dayMonth = Regex("\\b(\\d{1,2}) (${alternatives(longMonths)}|${alternatives(shortMonths)})(?: (\\d{4}))?\\b")
    private val monthYear = Regex("\\b(${alternatives(longMonths)}) (\\d{4})\\b")
    private val dayOfMonth = Regex("\\b(${alternatives(shortDays)}) (\\d{1,2})\\b")
    private val clock = Regex("\\b(\\d{1,2})(:\\d{2})? ?(AM|PM)\\b")

    private fun month(name: String): Int = longMonths.indexOf(name).takeIf { it >= 0 } ?: shortMonths.indexOf(name)

    fun localize(text: String, language: String): String {
        val (short, long) = days[language] ?: return text
        val hindi = language == "hi"
        val korean = language == "ko"
        fun dayAndMonth(day: String, monthName: String, year: String?, longForm: Boolean): String {
            val m = month(monthName)
            return when {
                hindi -> "$day ${(if (longForm) hindiLongMonths else hindiShortMonths)[m]}" + (year?.let { " $it" } ?: "")
                korean -> (year?.let { "${it}년 " } ?: "") + "${m + 1}월 ${day}일"
                else -> (year?.let { "${it}年" } ?: "") + "${m + 1}月${day}日"
            }
        }
        var result = longDate.replace(text) { match ->
            val (weekday, day, monthName, year) = match.destructured
            val name = long[longDays.indexOf(weekday)]
            val date = dayAndMonth(day, monthName, year.ifEmpty { null }, longForm = true)
            if (hindi) "$name, $date" else "$date $name"
        }
        result = shortDate.replace(result) { match ->
            val (weekday, day, monthName, year) = match.destructured
            val name = short[shortDays.indexOf(weekday)]
            val date = dayAndMonth(day, monthName, year.ifEmpty { null }, longForm = false)
            when { hindi -> "$name, $date"; korean -> "$date ($name)"; else -> "$date $name" }
        }
        result = dayOfMonth.replace(result) { match ->
            val (weekday, day) = match.destructured
            val name = short[shortDays.indexOf(weekday)]
            when { hindi -> "$name $day"; korean -> "${day}일($name)"; else -> "$name ${day}日" }
        }
        result = dayMonth.replace(result) { match ->
            val (day, monthName, year) = match.destructured
            dayAndMonth(day, monthName, year.ifEmpty { null }, longForm = monthName.length > 3)
        }
        result = monthYear.replace(result) { match ->
            val (monthName, year) = match.destructured
            val m = month(monthName)
            when { hindi -> "${hindiLongMonths[m]} $year"; korean -> "${year}년 ${m + 1}월"; else -> "${year}年${m + 1}月" }
        }
        if (!hindi) result = clock.replace(result) { match ->
            val (hour, minutes, half) = match.destructured
            val period = when { korean -> if (half == "AM") "오전 " else "오후 "; else -> if (half == "AM") "上午" else "下午" }
            period + hour + minutes
        }
        return result
    }
}
