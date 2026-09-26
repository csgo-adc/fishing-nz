package nz.fishingnz.app.viewmodel

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FishingDatePresetTest {
    @Test fun nextSevenDaysIncludesTodayAndSixFollowingDays() {
        val today = LocalDate.of(2026, 9, 26)
        assertEquals(today to today.plusDays(6), presetFishingDates("Next 7 days", today))
    }

    @Test fun inThreeDaysRemainsASingleDay() {
        val today = LocalDate.of(2026, 9, 26)
        val day = today.plusDays(3)
        assertEquals(day to day, presetFishingDates("In 3 days", today))
    }
}
