package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MoonPhaseTest {
    private fun hoursFrom(position: Double, target: Double) = ((position - target + 1.5) % 1.0 - 0.5) * 29.530588853 * 24

    @Test fun matchesPublishedPhaseTimes() {
        // Eclipse dates, whose phase times are well published (UTC).
        listOf("2024-04-08T18:21:00Z" to 0.0, "2025-03-14T06:55:00Z" to 0.5, "2025-09-07T18:09:00Z" to 0.5,
            "2026-03-03T11:38:00Z" to 0.5, "2026-08-12T17:37:00Z" to 0.0, "2026-08-28T04:18:00Z" to 0.5).forEach { (at, target) ->
            val error = hoursFrom(MoonPhase.position(Instant.parse(at)), target)
            assertTrue("$at is $error hours off", kotlin.math.abs(error) < 1.0)
        }
    }

    @Test fun namesThePhaseOnTheNewZealandDayItHappens() {
        // Full moon 2026-03-03 11:38 UTC is Wednesday 4 March, 12:38 AM in New Zealand.
        assertEquals("Waxing gibbous", MoonPhase.forDay(LocalDate.of(2026, 3, 3)).name)
        val full = MoonPhase.forDay(LocalDate.of(2026, 3, 4))
        assertEquals("Full moon", full.name)
        assertEquals("🌕", full.emoji)
        assertTrue(full.illuminationPercent >= 99)
        assertEquals("Waning gibbous", MoonPhase.forDay(LocalDate.of(2026, 3, 5)).name)
        // New moon 2026-08-12 17:37 UTC is Thursday 13 August, 5:37 AM.
        assertEquals("New moon", MoonPhase.forDay(LocalDate.of(2026, 8, 13)).name)
        // Seen from New Zealand, a waxing crescent is lit on the left.
        val crescent = MoonPhase.forDay(LocalDate.of(2026, 8, 15))
        assertEquals("Waxing crescent", crescent.name)
        assertEquals("🌘", crescent.emoji)
    }

    @Test fun springTidesFollowNewAndFullMoons() {
        assertEquals(TideRange.SPRING, MoonPhase.forDay(LocalDate.of(2026, 3, 4)).tideRange)
        assertEquals(TideRange.SPRING, MoonPhase.forDay(LocalDate.of(2026, 3, 6)).tideRange)
        assertEquals(TideRange.NEAP, MoonPhase.tideRange(0.25))
        assertEquals(TideRange.NEAP, MoonPhase.tideRange(0.80))
        assertEquals(TideRange.BETWEEN, MoonPhase.tideRange(0.65))
        assertEquals(TideRange.SPRING, MoonPhase.tideRange(0.99))
    }

    @Test fun findsTheNextFullMoon() {
        assertEquals(LocalDate.of(2026, 3, 4), MoonPhase.nextDay("Full moon", LocalDate.of(2026, 2, 20)))
        assertEquals(LocalDate.of(2026, 8, 13), MoonPhase.nextDay("New moon", LocalDate.of(2026, 8, 1)))
        assertTrue(MoonPhase.nextPhasesLine(LocalDate.of(2026, 2, 20)).startsWith("Next full moon Wed 4 Mar"))
    }
}
