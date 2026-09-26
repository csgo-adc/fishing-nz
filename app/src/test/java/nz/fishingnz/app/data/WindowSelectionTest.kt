package nz.fishingnz.app.data

import nz.fishingnz.app.model.Recommendation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WindowSelectionTest {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val firstDay = LocalDate.of(2026, 9, 26)

    @Test fun stationSearchKeepsOneBestWindowForEveryDayInDateOrder() {
        val selection = WindowSelection(onePerDay = true)
        val lastDay = firstDay.plusDays(6)
        selection.consider(lastDay, recommendation(lastDay, 17, rating = 90))
        (0L..5L).forEach { offset ->
            val day = firstDay.plusDays(offset)
            selection.consider(day, recommendation(day, 8, rating = 60))
            selection.consider(day, recommendation(day, 17, rating = 80))
        }

        val windows = selection.windows()
        assertEquals(7, windows.size)
        assertEquals((0L..6L).map { firstDay.plusDays(it) }, windows.map {
            java.time.Instant.ofEpochSecond(it.startsAtEpochSeconds).atZone(zone).toLocalDate()
        })
        assertEquals(List(6) { 80 } + 90, windows.map { it.rating })
    }

    @Test fun equalScoresPreferLongerThenEarlierWindows() {
        val selection = WindowSelection(onePerDay = true)
        selection.consider(firstDay, recommendation(firstDay, 17, rating = 80, duration = 2))
        selection.consider(firstDay, recommendation(firstDay, 19, rating = 80, duration = 3))
        selection.consider(firstDay, recommendation(firstDay, 7, rating = 80, duration = 3))

        assertEquals(recommendation(firstDay, 7, rating = 80, duration = 3), selection.windows().single())
    }

    @Test fun radiusSearchStillKeepsOneBestWindowAcrossDays() {
        val selection = WindowSelection(onePerDay = false)
        selection.consider(firstDay, recommendation(firstDay, 8, rating = 70))
        selection.consider(firstDay.plusDays(1), recommendation(firstDay.plusDays(1), 8, rating = 85))

        assertEquals(listOf(85), selection.windows().map { it.rating })
    }

    private fun recommendation(day: LocalDate, hour: Int, rating: Int, duration: Int = 2) = Recommendation(
        name = "Raglan",
        area = "Waikato",
        rating = rating,
        time = "",
        distance = "Selected location",
        reasons = emptyList(),
        startsAtEpochSeconds = day.atTime(hour, 0).atZone(zone).toEpochSecond(),
        durationHours = duration
    )
}
