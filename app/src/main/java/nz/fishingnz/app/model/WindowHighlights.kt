package nz.fishingnz.app.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a fishing window card puts first: the day and hours, an outlook colour, three key numbers and anything that needs
 * attention. Built from the window's own condition rows, so shared windows highlight the same way. The iPhone app has the
 * same rules in `WindowHighlights` (FishingModels.swift).
 */
enum class OutlookTone { GOOD, CAUTION, POOR, UNKNOWN }

data class KeyTile(val label: String, val value: String, val detail: String, val concern: Boolean)

private val nzZone: ZoneId = ZoneId.of("Pacific/Auckland")
private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val clockFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val shortDayFormat = DateTimeFormatter.ofPattern("EEE", Locale.US)

private val goodLabels = setOf("Excellent", "Good", "Comfortable", "Okay")
private val poorLabels = setOf("Uncomfortable", "Challenging", "Concerning", "Check conditions")
private val cautionLabels = setOf("Demanding", "Fair", "Mixed conditions", "Unfavourable")
/** Condition moods that make a key number amber and add a line to the watch-out box. */
private val concernLabels = setOf("Demanding", "Uncomfortable", "Choppy", "More motion", "High waves", "Wet", "Cold", "Hot",
    "After dark", "Limited daylight", "Challenging", "Concerning", "Check conditions", "Unfavourable")

fun outlookTone(label: String): OutlookTone = when (label) {
    in goodLabels -> OutlookTone.GOOD
    in poorLabels -> OutlookTone.POOR
    in cautionLabels -> OutlookTone.CAUTION
    else -> OutlookTone.UNKNOWN
}

/** True when the card can use the highlighted layout: a timed window with condition rows. */
val Recommendation.hasHighlights: Boolean
    get() = assessment != null && startsAtEpochSeconds > 0 && durationHours > 0

/** "Wed 14 Oct" and "10:00 AM–12:00 PM" (or "11:00 PM–Thu 1:00 AM" across midnight), in New Zealand time. */
fun Recommendation.windowDayAndHours(): Pair<String, String> {
    val start = Instant.ofEpochSecond(startsAtEpochSeconds).atZone(nzZone)
    val end = start.plusHours(durationHours.toLong())
    val endText = if (end.toLocalDate() == start.toLocalDate()) end.format(clockFormat)
        else "${end.format(shortDayFormat)} ${end.format(clockFormat)}"
    return start.format(dayFormat) to "${start.format(clockFormat)}–$endText"
}

private fun Recommendation.condition(title: String): ConditionItem? = assessment?.conditions?.firstOrNull { it.title == title }

private fun ConditionItem.isConcern() = mood.label in concernLabels

private fun segments(value: String) = value.lineSequence().first().split(" · ").map(String::trim)

private fun missingTile(label: String) = KeyTile(label, "—", "No data", false)

private fun tile(label: String, item: ConditionItem?): KeyTile {
    if (item == null || item.value.trim() == "—") return missingTile(label)
    val parts = segments(item.value)
    return when (item.title) {
        "Rain" -> {
            val chance = parts.firstOrNull { it.contains('%') }?.substringBefore(' ')
            if (chance != null) KeyTile(label, chance, parts.first(), item.isConcern())
            else KeyTile(label, parts.first(), parts.getOrElse(1) { "" }, item.isConcern())
        }
        "Tide" -> {
            val event = parts.first()
            val high = event.contains("high", ignoreCase = true)
            val time = event.substringAfter(if (high) "high " else "low ").substringAfterLast(", ")
            KeyTile(label, if (high) "High" else "Low", if (event.startsWith("Next")) "next $time" else time, item.isConcern())
        }
        else -> KeyTile(label, parts.first(), parts.getOrElse(1) { "" }, item.isConcern())
    }
}

/** Land: wind, tide and rain. Boat: waves, wind and tide. */
fun Recommendation.keyTiles(): List<KeyTile> = if (boat)
    listOf(tile("Waves", condition("Offshore waves")), tile("Wind", condition("Wind")), tile("Tide", condition("Tide")))
else listOf(tile("Wind", condition("Wind")), tile("Tide", condition("Tide")), tile("Rain", condition("Rain")))

/** Short lines for the amber box: only what needs attention, most important first. */
fun Recommendation.watchOuts(): List<String> {
    val assessment = assessment ?: return emptyList()
    val lines = mutableListOf<String>()
    condition("Wind")?.takeIf { it.isConcern() }?.let { wind ->
        val gust = Regex("gust (\\d+)").find(wind.value)?.groupValues?.get(1)
        lines += gust?.let { "Gusts to $it km/h" } ?: "Wind ${segments(wind.value).first()}"
    }
    condition("Offshore waves")?.takeIf { it.isConcern() }?.let { waves ->
        val height = segments(waves.value).first()
        lines += if (waves.mood.label == "Choppy") "Short, choppy waves ($height)" else "Waves to $height"
    }
    condition("Rain")?.takeIf { it.isConcern() }?.let { lines += "Rain: ${segments(it.value).first()} expected" }
    condition("Feels like")?.takeIf { it.isConcern() }?.let { lines += "${it.mood.label}: feels like ${segments(it.value).first()}" }
    condition("Daylight")?.takeIf { it.isConcern() }?.let { lines += "Part of the session is after dark" }
    if (assessment.comfortComplete && !assessment.matchesComfort) lines += "Outside your comfort preference"
    return lines
}

/** "Planning forecast · local checks needed". */
fun Recommendation.forecastNote(): String? = assessment?.let { "${it.confidence.label} · ${it.checks.label.replaceFirstChar(Char::lowercase)}" }
