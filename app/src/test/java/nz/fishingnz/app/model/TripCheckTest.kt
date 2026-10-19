package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TripCheckTest {
    // Sunday 18 October 2026, 10 AM in New Zealand (UTC+13).
    private val start = Instant.parse("2026-10-17T21:00:00Z")

    @Test fun checksTheEveningBeforeOrTwoHoursAhead() {
        // Planned on Friday: 7 PM on Saturday.
        assertEquals(Instant.parse("2026-10-17T06:00:00Z"), TripCheck.checkTime(start, Instant.parse("2026-10-15T22:00:00Z")))
        // Planned on Saturday at 8 PM, after 7 PM: 2 hours before, at 8 AM on Sunday.
        assertEquals(Instant.parse("2026-10-17T19:00:00Z"), TripCheck.checkTime(start, Instant.parse("2026-10-17T07:00:00Z")))
        // Planned on Sunday at 7:55 AM: too close for a check.
        assertNull(TripCheck.checkTime(start, Instant.parse("2026-10-17T18:55:00Z")))
    }

    private fun window(mood: String, emoji: String, gusts: Boolean = false) = Recommendation(
        name = "Raglan", area = "Waikato", rating = 80, time = "", distance = "", reasons = emptyList(),
        startsAtEpochSeconds = start.epochSecond, durationHours = 2,
        assessment = WindowAssessment(WindowMood(emoji, mood),
            if (gusts) listOf(ConditionItem("Wind", "22 km/h · gust 45 · SW", WindowMood("😣", "Uncomfortable"))) else emptyList(),
            WindowMood("👍", "Good confidence")))

    @Test fun saysWhetherTheOutlookChanged() {
        val trip = window("Comfortable", "😌")
        assertEquals("Raglan · Sun 18 Oct, 10:00 AM–12:00 PM", TripCheck.message(trip, null).first)
        assertEquals("Still 😌 Comfortable. Tap to see your trip.", TripCheck.message(trip, window("Comfortable", "😌")).second)
        assertEquals("Now 😣 Uncomfortable, was 😌 Comfortable. Gusts to 45 km/h. Tap to see your trip.",
            TripCheck.message(trip, window("Uncomfortable", "😣", gusts = true)).second)
        assertEquals("Check the latest forecast before you go: it couldn't be updated just now.", TripCheck.message(trip, null).second)
    }
}
