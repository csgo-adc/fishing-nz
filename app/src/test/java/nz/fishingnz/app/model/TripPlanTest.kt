package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TripPlanTest {
    // Sunday 18 October 2026, 10 AM–12 PM in New Zealand.
    private val trip = Recommendation(name = "Raglan", area = "Waikato", rating = 80, time = "", distance = "", reasons = emptyList(),
        startsAtEpochSeconds = Instant.parse("2026-10-17T21:00:00Z").epochSecond, durationHours = 2, latitude = -37.8, longitude = 174.88)

    @Test fun backByIsAnHourAfterTheWindowOnAQuarterHour() {
        assertEquals(Instant.parse("2026-10-18T00:00:00Z"), TripPlan.defaultBackBy(trip))
        assertEquals(Instant.parse("2026-10-18T00:15:00Z"), TripPlan.defaultBackBy(trip.copy(startsAtEpochSeconds = trip.startsAtEpochSeconds + 600)))
    }

    @Test fun writesThePlanWithWhatToDoIfOverdue() {
        val text = TripPlan.text(trip, Instant.parse("2026-10-18T00:00:00Z"), "Me and Sam", " White Hilux at the ramp ", "https://fishing.fishnz.space/w/k3F9xQ2m")
        assertEquals("""
            My fishing trip plan (Fishdays - NZ)
            Where: Raglan, Waikato · land fishing
            Map: https://www.google.com/maps/search/?api=1&query=-37.80000,174.88000
            When: Sun 18 Oct, 10:00 AM–12:00 PM
            Back by: Sun 18 Oct, 1:00 PM
            Who: Me and Sam
            Details: White Hilux at the ramp
            Forecast: https://fishing.fishnz.space/w/k3F9xQ2m

            If you haven't heard from me by 1:00 PM, try calling me. If you can't reach me and you're worried, call 111 and ask for the Police.
        """.trimIndent(), text)
        val bare = TripPlan.text(trip.copy(boat = true), Instant.parse("2026-10-18T00:00:00Z"), "", "", null)
        assertTrue(bare.contains("· boat fishing") && !bare.contains("Who:") && !bare.contains("Details:") && !bare.contains("Forecast:"))
    }
}
