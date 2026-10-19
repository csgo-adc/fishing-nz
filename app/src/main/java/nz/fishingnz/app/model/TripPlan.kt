package nz.fishingnz.app.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Tell someone": the trip plan a person leaves with a friend or family member, as AdventureSmart and Coastguard
 * advise. The iPhone app writes the same text in `TripPlan.swift`.
 */
object TripPlan {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val dayTime = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.US)
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** An hour after the window ends, rounded up to the next quarter hour. */
    fun defaultBackBy(trip: Recommendation): Instant? {
        if (trip.startsAtEpochSeconds <= 0) return null
        val end = trip.startsAtEpochSeconds + trip.durationHours.coerceAtLeast(1) * 3600L + 3600L
        return Instant.ofEpochSecond(((end + 899) / 900) * 900)
    }

    fun mapLink(trip: Recommendation): String? = if (trip.latitude.isFinite() && trip.longitude.isFinite())
        String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%.5f,%.5f", trip.latitude, trip.longitude) else null

    fun text(trip: Recommendation, backBy: Instant, who: String, details: String, forecastLink: String?): String {
        val (day, hours) = trip.windowDayAndHours()
        val back = backBy.atZone(zone)
        return listOfNotNull(
            "My fishing trip plan (Fishdays - NZ)",
            "Where: ${trip.name}, ${trip.area} · ${if (trip.boat) "boat" else "land"} fishing",
            mapLink(trip)?.let { "Map: $it" },
            if (trip.startsAtEpochSeconds > 0) "When: $day, $hours" else null,
            "Back by: ${back.format(dayTime)}",
            who.trim().takeIf { it.isNotEmpty() }?.let { "Who: $it" },
            details.trim().takeIf { it.isNotEmpty() }?.let { "Details: $it" },
            forecastLink?.let { "Forecast: $it" },
            "",
            "If you haven't heard from me by ${back.format(time)}, try calling me. If you can't reach me and you're worried, call 111 and ask for the Police."
        ).joinToString("\n")
    }

    /** The reminder at the back-by time. */
    fun checkInMessage(trip: Recommendation): Pair<String, String> =
        "Back from ${trip.name}?" to "Let the person you told know you're back safe."
}
