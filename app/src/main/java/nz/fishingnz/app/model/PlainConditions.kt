package nz.fishingnz.app.model

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Condition rows in plain words: "22 km/h · gust 28 · S" reads "Breezy: 22 km/h, gusts to 28 km/h, from the south".
 * A window's rows keep their short form because shared links carry it and older apps read it, so a window is put
 * into words when it is shown ([windowValue]). The map's conditions page builds its rows with the same words. The
 * iPhone app has the same rules in `PlainConditions` (FishingModels.swift), the shared-window page in
 * `plain-conditions.ts`.
 */
object PlainConditions {
    private val compass = mapOf("N" to "north", "NE" to "north-east", "E" to "east", "SE" to "south-east",
        "S" to "south", "SW" to "south-west", "W" to "west", "NW" to "north-west")

    /** The word for a steady wind, in km/h. The steps follow the land comfort bands (12, 20 and 30 km/h). */
    fun windStrength(kmh: Int): String = when {
        kmh < 12 -> "Light wind"
        kmh < 20 -> "Gentle breeze"
        kmh < 30 -> "Breezy"
        kmh < 40 -> "Windy"
        else -> "Very windy"
    }

    /** "from the south" for one direction, "from SW, then W" when it changes; null when unknown. */
    fun from(directions: List<String>): String? {
        val known = directions.map(String::trim).filter { it in compass }
        return when (known.size) {
            0 -> null
            1 -> "from the ${compass.getValue(known[0])}"
            else -> "from ${known.joinToString(", then ")}"
        }
    }

    /** Whole seconds from a period range: "6.5–7.0" is "7", "5.6–8.2" is "6–8"; null without a number. */
    fun seconds(range: String?): String? {
        val values = range.orEmpty().split("–").mapNotNull { it.trim().toDoubleOrNull() }
        if (values.isEmpty()) return null
        val low = values.min().roundToInt()
        val high = values.max().roundToInt()
        return if (low == high) "$low" else "$low–$high"
    }

    /** "Breezy: 22 km/h, gusts to 28 km/h, from the south"; [upTo] for a whole day's strongest wind. */
    fun wind(speed: Double?, gust: Double?, directions: List<String>, upTo: Boolean = false): String {
        val steady = speed?.roundToInt() ?: return "—"
        return listOfNotNull("${windStrength(steady)}: ${if (upTo) "up to " else ""}$steady km/h",
            gust?.let { "gusts to ${it.roundToInt()} km/h" }, from(directions)).joinToString(", ")
    }

    /** "Up to 1.2 m, 6–7 seconds apart". */
    fun waves(height: Double?, periods: String?): String {
        val metres = height ?: return "—"
        val apart = seconds(periods)?.let { ", $it ${if (it == "1") "second" else "seconds"} apart" }.orEmpty()
        return "Up to ${String.format(Locale.US, "%.1f", metres)} m$apart"
    }

    /** "0.4 mm in total, up to 40% chance in any hour", or "… all day" for a whole day on the map. A 0% chance is left out. */
    fun rain(total: Double?, chance: Double?, wholeDay: Boolean = false): String {
        val amount = total?.let { String.format(Locale.US, "%.1f", it) }
        val parts = listOfNotNull(
            amount?.let { if (it == "0.0") "No rain expected" else "$it mm ${if (wholeDay) "all day" else "in total"}" },
            chance?.roundToInt()?.takeIf { it > 0 }?.let { "up to $it% chance in any hour" })
        return if (parts.isEmpty()) "—" else parts.joinToString(", ").replaceFirstChar(Char::uppercase)
    }

    private val windRow = Regex("^(\\d+) km/h · gust (\\d+)(?: · ([A-Z/]+))?$")
    private val waveRow = Regex("^(\\d+(?:\\.\\d+)?) m · (.+) s$")
    private val rainRow = Regex("^(\\d+(?:\\.\\d+)?) mm · peak \\S+ mm/h · (?:(\\d+)% hourly|chance —)$")
    private val tideEvent = Regex("^(Next )?(high|low) (.+) · (\\d+(?:\\.\\d+)?) m CD$")
    private val daylightShare = Regex("^(\\d+)% (session|visit|known time)$")
    private val shorePlan = Regex("^(.+) · before (.+) · return (.+)$")

    /** A window's condition row in plain words. Text it does not recognise, such as a cut-off shared row, stays as it is. */
    fun windowValue(item: ConditionItem): String {
        val value = item.value.trim()
        return when (item.title) {
            "Wind" -> windRow.find(value)?.destructured?.let { (speed, gust, directions) ->
                wind(speed.toDouble(), gust.toDouble(), directions.split("/")) }
            "Offshore waves" -> waveRow.find(value)?.destructured?.let { (height, periods) -> waves(height.toDouble(), periods) }
            "Rain" -> rainRow.find(value)?.destructured?.let { (total, chance) -> rain(total.toDouble(), chance.toDoubleOrNull()) }
            "Tide" -> tide(value)
            "Daylight" -> daylightShare.find(value)?.destructured?.let { (percent, of) -> daylight(percent, of) }
            "Shore plan" -> shorePlan.find(value)?.destructured?.let { (setting, before, back) ->
                listOf(if (setting == "Not set") "Shore type not set" else setting,
                    if (before == "—") "walk-in time not set" else "$before walk in",
                    if (back == "—") "return time not set" else "$back back").joinToString(" · ") }
            else -> null
        } ?: value
    }

    /** "Next high tide 7:12 PM, 2.4 m" and "Tide station: Auckland, 3.0 km away". */
    private fun tide(value: String): String? {
        val lines = value.lines().map(String::trim).filter(String::isNotEmpty)
        val (next, kind, time, height) = tideEvent.find(lines.firstOrNull() ?: return null)?.destructured ?: return null
        val event = "${if (next.isEmpty()) kind.replaceFirstChar(Char::uppercase) else "Next $kind"} tide $time, $height m"
        return (listOf(event) + lines.drop(1).map { "Tide station: " + it.removePrefix("LINZ ").replace(" · ", ", ") }).joinToString("\n")
    }

    private fun daylight(percent: String, of: String): String = when {
        percent == "100" -> if (of == "visit") "Whole visit in daylight" else "All in daylight"
        percent == "0" -> "All after dark"
        of == "visit" -> "$percent% of your visit in daylight"
        of == "known time" -> "$percent% of the known times in daylight"
        else -> "$percent% in daylight"
    }

    /** "22 km/h" over "Breezy · gusts 28 · from S" ("from SW to E" when it changes). Null when the row is not recognised. */
    fun windTile(value: String): Pair<String, String>? {
        val (speed, gust, directions) = windRow.find(value.trim())?.destructured ?: return null
        val known = directions.split("/").filter { it in compass }
        val from = when (known.size) { 0 -> null; 1 -> "from ${known[0]}"; else -> "from ${known.first()} to ${known.last()}" }
        return "$speed km/h" to listOfNotNull(windStrength(speed.toInt()), "gusts $gust", from).joinToString(" · ")
    }

    /** "1.2 m" over "6–7 s apart". Null when the row is not recognised. */
    fun waveTile(value: String): Pair<String, String>? {
        val (height, periods) = waveRow.find(value.trim())?.destructured ?: return null
        return "$height m" to (seconds(periods)?.let { "$it s apart" } ?: "")
    }
}
