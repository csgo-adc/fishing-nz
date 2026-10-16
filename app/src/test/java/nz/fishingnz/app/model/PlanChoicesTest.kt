package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class PlanChoicesTest {
    @Test fun oldRadiiMoveToTheNearestOffered() {
        assertEquals(listOf(10, 25, 50, 100, 200), searchRadiiKm)
        assertEquals(listOf(10, 25, 50, 100, 200, 200, 200), listOf(10, 30, 50, 100, 300, 400, 500).map(::nearestSearchRadius))
    }

    @Test fun harbourBankBecomesWharfOrHarbour() {
        assertEquals(ShoreSetting.WHARF, ShoreSetting.saved("BANK"))
        assertEquals(ShoreSetting.ROCKS, ShoreSetting.saved("ROCKS"))
        assertEquals(ShoreSetting.UNKNOWN, ShoreSetting.saved(null))
        assertEquals(listOf("Not sure", "Wharf or harbour", "Beach", "Rocks"), ShoreSetting.entries.map { it.label })
    }

    @Test fun timeChoicesHaveFixedHoursOnlyForMorningAndEvening() {
        assertEquals(PreferredTimeRange(LocalTime.of(5, 0), LocalTime.of(12, 0)), TimeChoice.MORNING.hours)
        assertEquals(PreferredTimeRange(LocalTime.of(15, 0), LocalTime.of(22, 0)), TimeChoice.EVENING.hours)
        assertNull(TimeChoice.DAYTIME.hours)
        assertNull(TimeChoice.ANYTIME.hours)
    }

    @Test fun plainChoiceLabels() {
        assertEquals(listOf("Calm only", "A bit of wind or rain", "I don't mind rough"), roughnessChoices.map { it.second })
        assertEquals(listOf(0, 15, 30, 60), walkChoices.map { it.first })
        assertEquals("Calm water", WindowPriority.WEATHER.label(boat = true))
        assertEquals("Less wind", LandPriority.CASTING.label)
    }
}
