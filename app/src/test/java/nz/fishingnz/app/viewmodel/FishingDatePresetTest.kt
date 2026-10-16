package nz.fishingnz.app.viewmodel

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FishingDatePresetTest {
    private val today = LocalDate.of(2026, 9, 26)

    @Test fun nextSevenDaysIncludesTodayAndSixFollowingDays() {
        assertEquals(today to today.plusDays(6), presetFishingDates("Next 7 days", today))
    }

    @Test fun nextThreeDaysStartsToday() {
        assertEquals(today to today.plusDays(2), presetFishingDates("Next 3 days", today))
    }

    @Test fun tomorrowIsOneDay() {
        assertEquals(today.plusDays(1) to today.plusDays(1), presetFishingDates("Tomorrow", today))
    }

    @Test fun theOldSingleDayThreeDaysAheadIsGone() {
        // Saved plans with "In 3 days" are moved to "Next 3 days" when the app starts.
        assertNull(presetFishingDates("In 3 days", today))
        assertEquals(listOf("Today", "Tomorrow", "This weekend", "Next 3 days", "Next 7 days"), fishingDatePresets)
    }
}
