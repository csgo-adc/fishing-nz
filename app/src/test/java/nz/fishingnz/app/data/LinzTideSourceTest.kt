package nz.fishingnz.app.data

import nz.fishingnz.app.model.FishingSpot
import nz.fishingnz.app.model.tideStations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LinzTideSourceTest {
    private val raglan = tideStations.first { it.id == "raglan" }
    private val zone = ZoneId.of("Pacific/Auckland")
    private val fixture: String get() = checkNotNull(javaClass.getResourceAsStream("/tides/raglan-2026.csv"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun officialSeptember30EventsAreLocalDaylightTimesWithoutAnotherHourAdded() {
        val events = LinzTideSource.parse(fixture, raglan, 2026)
            .filter { it.at.atZone(zone).toLocalDate() == LocalDate.of(2026, 9, 30) }
        assertEquals(listOf("00:57", "07:02", "13:23", "19:24"), events.map { it.at.atZone(zone).toLocalTime().toString() })
        assertEquals(listOf(true, false, true, false), events.map { it.high })
        assertEquals(Instant.parse("2026-09-30T00:23:00Z"), events[2].at)
        assertEquals(3.2, events[2].height, 0.0)
    }

    @Test fun daylightSavingTransitionConvertsEachDateUsingItsOwnOffset() {
        val events = LinzTideSource.parse(fixture, raglan, 2026)
        val before = events.first { it.at.atZone(zone).toLocalDate() == LocalDate.of(2026, 9, 26) }
        val after = events.first { it.at.atZone(zone).toLocalDate() == LocalDate.of(2026, 9, 27) }
        assertEquals(12 * 3600, before.at.atZone(zone).offset.totalSeconds)
        assertEquals(13 * 3600, after.at.atZone(zone).offset.totalSeconds)
    }

    @Test fun sourceStationMustMatchRequestedStation() {
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(fixture.replaceFirst("180,Raglan", "180,Thames"), raglan, 2026)
        }
    }

    @Test fun yearAndDeclaredUnitsMustMatch() {
        assertThrows(IllegalArgumentException::class.java) { LinzTideSource.parse(fixture, raglan, 2027) }
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(fixture.replace("Tidal heights in metres.", "Tidal heights in feet."), raglan, 2026)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(fixture.replace("Local Std or Daylight Time", "UTC"), raglan, 2026)
        }
    }

    @Test fun emptyOrMalformedSourcesDoNotBecomePartialPredictions() {
        assertThrows(IllegalArgumentException::class.java) { LinzTideSource.parse("", raglan, 2026) }
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(table("30,We,9,2026,07:02,,13:23,3.2"), raglan, 2026)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(table("30,We,9,2026,07:02,NaN,13:23,3.2"), raglan, 2026)
        }
    }

    @Test fun duplicateEventsAndNonAlternatingHeightsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(table("30,We,9,2026,07:02,0.2,07:02,3.2"), raglan, 2026)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(table("30,We,9,2026,07:02,0.2,13:23,1.0,19:24,3.2"), raglan, 2026)
        }
    }

    @Test fun missingDaysAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(table("28,Mo,9,2026,07:00,0.2,13:00,3.2\n30,We,9,2026,07:02,0.2,13:23,3.2"), raglan, 2026)
        }
    }

    @Test fun nonexistentAndAmbiguousLocalClocksAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(table("27,Su,9,2026,02:30,0.2,08:30,3.2"), raglan, 2026)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(table("5,Su,4,2026,02:30,0.2,08:30,3.2"), raglan, 2026)
        }
    }

    @Test fun mappingUsesExplicitStationIdentityAndNeverNearestCoast() {
        val spot = FishingSpot("Raglan", "Waikato", -37.799, 174.87, false)
        assertEquals(raglan, recommendationTideStation(spot, null))
        assertNull(recommendationTideStation(spot.copy(name = "Unnamed rocks"), null))
        val thames = tideStations.first { it.id == "thames" }
        assertEquals(thames, recommendationTideStation(spot, thames))
        assertEquals("kawhia", recommendationTideStation(spot.copy(name = "Kāwhia"), null)?.id)
    }

    @Test fun validSimpleRowsClassifyBothFirstAndLastEvents() {
        val events = LinzTideSource.parse(table("30,We,9,2026,07:02,0.2,13:23,3.2"), raglan, 2026)
        assertFalse(events.first().high)
        assertTrue(events.last().high)
    }

    @Test fun thamesOctoberHeightsLoadDespiteAnUnrelatedAprilRepeatedClock() {
        val thames = tideStations.first { it.id == "thames" }
        val csv = thamesFixture()
        val day = LocalDate.of(2026, 10, 4)
        val events = LinzTideSource.parse(csv, thames, 2026, day, day)
        val daily = events.filter { it.at.atZone(zone).toLocalDate() == day }
        assertEquals(listOf("01:20", "07:24", "13:50", "20:07"), daily.map { it.at.atZone(zone).toLocalTime().toString() })
        assertEquals(listOf(3.5, 0.9, 3.5, 1.1), daily.map { it.height })
        assertEquals(listOf(true, false, true, false), daily.map { it.high })
        assertEquals(Instant.parse("2026-10-04T00:50:00Z"), daily[2].at)
        assertEquals(day.minusDays(1), events.first().at.atZone(zone).toLocalDate())
        assertEquals(day.plusDays(1), events.last().at.atZone(zone).toLocalDate())
    }

    @Test fun thamesRepeatedClockStillRejectsRequestsThatNeedIt() {
        val thames = tideStations.first { it.id == "thames" }
        val csv = thamesFixture()
        for (day in listOf(LocalDate.of(2026, 4, 4), LocalDate.of(2026, 4, 5))) {
            assertThrows(IllegalArgumentException::class.java) { LinzTideSource.parse(csv, thames, 2026, day, day) }
        }
        val following = LocalDate.of(2026, 4, 6)
        assertTrue(LinzTideSource.parse(csv, thames, 2026, following, following).isNotEmpty())
    }

    @Test fun scopedThamesRequestsStillValidateTheWholeTable() {
        val thames = tideStations.first { it.id == "thames" }
        val day = LocalDate.of(2026, 10, 4)
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(thamesFixture().replace("02:59,0.9", "02:59,NaN"), thames, 2026, day, day)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(thamesFixture(), raglan, 2026, day, day)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinzTideSource.parse(thamesFixture(), thames, 2026, day, day.minusDays(1))
        }
    }

    private fun thamesFixture(): String = checkNotNull(javaClass.getResourceAsStream("/tides/thames-2026.csv"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun table(rows: String) = """
        180,Raglan,37°48'S,174°53'E
        Based on constituent set with reference date:,01-Jul-2016
        Local Std or Daylight Time,Tidal heights in metres.
        $rows
    """.trimIndent()
}
