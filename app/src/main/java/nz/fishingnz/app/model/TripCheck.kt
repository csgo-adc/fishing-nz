package nz.fishingnz.app.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The pre-trip forecast check. The iPhone app has the same rules in `TripCheck.swift`. */
object TripCheck {
    private val zone = ZoneId.of("Pacific/Auckland")

    /**
     * When to check: 7 PM the evening before a trip on a later day, otherwise 2 hours before it starts. Null when that
     * time is less than 10 minutes away, or the trip has no start.
     */
    fun checkTime(start: Instant, now: Instant): Instant? {
        val startDay = start.atZone(zone).toLocalDate()
        val soonest = now.plus(Duration.ofMinutes(10))
        val eveningBefore = startDay.minusDays(1).atTime(19, 0).atZone(zone).toInstant()
        val twoHoursBefore = start.minus(Duration.ofHours(2))
        return when {
            startDay.isAfter(now.atZone(zone).toLocalDate()) && eveningBefore.isAfter(soonest) -> eveningBefore
            twoHoursBefore.isAfter(soonest) -> twoHoursBefore
            else -> null
        }
    }

    /** The notification: "Raglan · Sat 18 Oct, 10:00 AM–12:00 PM" and what changed, if anything. */
    fun message(trip: Recommendation, latest: Recommendation?): Pair<String, String> {
        val (day, hours) = trip.windowDayAndHours()
        val title = "${trip.name} · $day, $hours"
        if (latest?.assessment == null) return title to "Check the latest forecast before you go: it couldn't be updated just now."
        val was = trip.windowMood
        val now = latest.windowMood
        val watch = latest.watchOuts().take(2).joinToString(" · ")
        val lead = if (now.label == was.label) "Still ${now.emoji} ${now.label}." else "Now ${now.emoji} ${now.label}, was ${was.emoji} ${was.label}."
        return title to listOf(lead, watch.takeIf { it.isNotEmpty() }?.let { "$it." }, "Tap to see your trip.").filterNotNull().joinToString(" ")
    }
}
