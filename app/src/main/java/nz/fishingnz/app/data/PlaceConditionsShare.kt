package nz.fishingnz.app.data

import nz.fishingnz.app.model.ConditionItem
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.TideState
import nz.fishingnz.app.model.TideStation
import nz.fishingnz.app.model.needsDataMood
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The message the map's conditions page shares: one day's main rows in plain words, the tide and a map link anyone can
 * open. The iPhone app writes the same message (`PlaceConditionsShare` in PlaceConditionsService.swift).
 */
object PlaceConditionsShare {
    private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** "Short-range forecast" for today and the next two days, "Planning forecast" to day 5, then "Early outlook". */
    fun forecastLabel(day: LocalDate, today: LocalDate): String = when {
        day < today -> "Recent model data"
        day > today.plusDays(5) -> "Early outlook"
        day > today.plusDays(2) -> "Planning forecast"
        else -> "Short-range forecast"
    }

    /** A Google Maps link to the position, which opens on any phone or computer. */
    fun mapLink(point: GeoPoint): String =
        "https://www.google.com/maps/search/?api=1&query=${coordinate(point.latitude)},${coordinate(point.longitude)}"

    fun subject(place: ConditionPlace): String = "Fishing conditions · ${name(place)}"

    fun text(place: ConditionPlace, day: LocalDate, today: LocalDate, rows: List<ConditionItem>, tide: TideState?, station: TideStation?): String {
        val lines = mutableListOf(subject(place))
        place.region.takeIf { it !in setOf("Selected on map", "Selected place") }?.let(lines::add)
        lines += "${day.format(dayFormat)} · ${forecastLabel(day, today)}"
        lines += ""
        rows.take(5).filter { it.value.trim() != "—" }.forEach { row ->
            val feeling = if (row.title == "Daylight" || row.mood == needsDataMood) "" else " (${row.mood.label})"
            lines += "${row.title} – ${row.value}$feeling"
        }
        if (tide != null && station != null && tide.events.isNotEmpty()) {
            val distance = placeDistanceKm(place.point, GeoPoint(station.latitude, station.longitude))
            val events = tide.events.joinToString(", ") { "${it.type.lowercase()} ${it.time}" }.replaceFirstChar(Char::uppercase)
            lines += "Tide at ${station.name} (${String.format(Locale.US, "%.1f", distance)} km away) – $events"
        }
        lines += ""
        lines += "Map: ${mapLink(place.point)}"
        lines += "Weather and waves: Open-Meteo. Tides: LINZ. Check local conditions and marine warnings before you go."
        return lines.joinToString("\n")
    }

    private fun name(place: ConditionPlace) = if (place.name == "Dropped pin") "pin on the map" else place.name

    private fun coordinate(value: Double) = ((value * 10_000).roundToLong() / 10_000.0).toString()
}
