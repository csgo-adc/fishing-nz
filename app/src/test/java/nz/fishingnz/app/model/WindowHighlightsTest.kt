package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WindowHighlightsTest {
    private val start = ZonedDateTime.of(2026, 10, 14, 10, 0, 0, 0, ZoneId.of("Pacific/Auckland")).toEpochSecond()

    private fun window(boat: Boolean = false, conditions: List<ConditionItem>, mood: WindowMood = comfortMood(2),
                       matchesComfort: Boolean = false, hours: Int = 2, startsAt: Long = start) = Recommendation(
        "Manu Bay Coast", "Waikato", 50, "", "41 km straight line", listOf("Outside your comfort preference"), boat = boat,
        distanceKm = 41.0, startsAtEpochSeconds = startsAt, durationHours = hours,
        assessment = WindowAssessment(mood, conditions, WindowMood("🗓️", "Planning forecast"), matchesComfort = matchesComfort, comfortComplete = true))

    private val land = listOf(
        ConditionItem("Wind", "20 km/h · gust 45 · NW", comfortMood(2)),
        ConditionItem("Rain", "0.8 mm · peak 0.4 mm/h · 70% hourly", WindowMood("🌧️", "Light rain")),
        ConditionItem("Feels like", "16°C", WindowMood("😌", "Mild")),
        ConditionItem("Tide", "Next high 12:53 PM · 3.3 m CD\nLINZ Raglan", WindowMood("🎯", "Late incoming")),
        ConditionItem("Offshore waves", "1.6 m · 11.9–12.1 s", WindowMood("🌊", "Check exposure")),
        ConditionItem("Daylight", "100% session", WindowMood("🌞", "Daylight")))

    @Test fun landTilesShowWindTideAndRainWithWindHighlighted() {
        val tiles = window(conditions = land).keyTiles()
        assertEquals(listOf("Wind", "Tide", "Rain"), tiles.map { it.label })
        assertEquals(KeyTile("Wind", "20 km/h", "Breezy · gusts 45 · from NW", true), tiles[0])
        assertEquals(KeyTile("Tide", "High", "next 12:53 PM", false), tiles[1])
        assertEquals(KeyTile("Rain", "70%", "chance · 0.8 mm total", false), tiles[2])
    }

    @Test fun boatTilesLeadWithWaves() {
        val boat = window(boat = true, matchesComfort = true, conditions = listOf(
            ConditionItem("Offshore waves", "1.4 m · 5.0–5.5 s", WindowMood("🌊", "Choppy")),
            ConditionItem("Wind", "12 km/h · gust 20", comfortMood(1)),
            ConditionItem("Tide", "low Thu 15 Oct, 1:20 AM · 0.4 m CD\nLINZ Auckland", WindowMood("🕒", "Tide timing"))))
        assertEquals(listOf(KeyTile("Waves", "1.4 m", "5–6 s apart", true), KeyTile("Wind", "12 km/h", "Gentle breeze · gusts 20", false),
            KeyTile("Tide", "Low", "1:20 AM", false)), boat.keyTiles())
        assertEquals(listOf("Short, choppy waves (1.4 m)"), boat.watchOuts())
    }

    @Test fun watchOutsListOnlyWhatNeedsAttention() {
        assertEquals(listOf("Gusts to 45 km/h", "Outside your comfort preference"), window(conditions = land).watchOuts())
        val calm = window(conditions = land.map { if (it.title == "Wind") it.copy(mood = comfortMood(1)) else it }, mood = comfortMood(1), matchesComfort = true)
        assertTrue(calm.watchOuts().isEmpty())
        val dark = window(conditions = land.map { if (it.title == "Daylight") ConditionItem("Daylight", "75% session", WindowMood("🌙", "After dark")) else it })
        assertTrue("Part of the session is after dark" in dark.watchOuts())
    }

    @Test fun missingValuesShowNoData() {
        val tiles = window(conditions = listOf(ConditionItem("Tide", "—", needsDataMood))).keyTiles()
        assertEquals(KeyTile("Tide", "—", "No data", false), tiles[1])
        assertEquals(KeyTile("Wind", "—", "No data", false), tiles[0])
    }

    @Test fun dayAndHoursIncludeTheEndDayAfterMidnight() {
        assertEquals("Wed 14 Oct" to "10:00 AM–12:00 PM", window(conditions = land).windowDayAndHours())
        val late = ZonedDateTime.of(2026, 10, 14, 23, 0, 0, 0, ZoneId.of("Pacific/Auckland")).toEpochSecond()
        assertEquals("Wed 14 Oct" to "11:00 PM–Thu 1:00 AM", window(conditions = land, startsAt = late).windowDayAndHours())
    }

    @Test fun outlookColoursAndLayoutEligibility() {
        assertEquals(OutlookTone.GOOD, outlookTone("Okay"))
        assertEquals(OutlookTone.CAUTION, outlookTone("Demanding"))
        assertEquals(OutlookTone.POOR, outlookTone("Uncomfortable"))
        assertEquals(OutlookTone.UNKNOWN, outlookTone("Needs more data"))
        assertTrue(window(conditions = land).hasHighlights)
        assertFalse(window(conditions = land, hours = 0).hasHighlights)
        assertEquals("Planning forecast · local checks needed", window(conditions = land).forecastNote())
    }
}
