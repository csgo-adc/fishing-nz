package nz.fishingnz.app.data

import nz.fishingnz.app.model.WeatherState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TideWidgetTextTest {
    // Sunday 18 October 2026 in New Zealand (UTC+13).
    private val tides = listOf(
        LinzPrediction(Instant.parse("2026-10-17T19:00:00Z"), 0.5, false), // 8:00 AM
        LinzPrediction(Instant.parse("2026-10-18T01:10:00Z"), 2.43, true),  // 2:10 PM
        LinzPrediction(Instant.parse("2026-10-18T07:25:00Z"), 0.61, false), // 8:25 PM
        LinzPrediction(Instant.parse("2026-10-18T13:40:00Z"), 2.5, true))   // Mon 2:40 AM

    @Test fun showsTheNextTwoTides() {
        assertEquals("Next high 2:10 PM · 2.4 m" to "Then low 8:25 PM · 0.6 m",
            TideWidgetText.nextTides(tides, Instant.parse("2026-10-17T22:00:00Z")))
        assertEquals("Next low 8:25 PM · 0.6 m" to "Then high Mon 2:40 AM · 2.5 m",
            TideWidgetText.nextTides(tides, Instant.parse("2026-10-18T02:00:00Z")))
        assertEquals("Next high Mon 2:40 AM · 2.5 m" to "", TideWidgetText.nextTides(tides, Instant.parse("2026-10-18T08:00:00Z")))
        assertNull(TideWidgetText.nextTides(tides, Instant.parse("2026-10-18T14:00:00Z")))
    }

    @Test fun addsTheMoonAndWind() {
        // Full moon on Wednesday 4 March 2026.
        assertEquals("🌕 Full moon · Spring tides · Wind 12 km/h, 14°C",
            TideWidgetText.extra(LocalDate.of(2026, 3, 4), WeatherState("14°C", "12 km/h", "0.0 mm")))
        assertEquals("🌕 Full moon · Spring tides", TideWidgetText.extra(LocalDate.of(2026, 3, 4), null))
    }

    @Test fun readsTheCurveBetweenHighsAndLows() {
        assertEquals(0.5, TideWidgetCurve.level(tides, Instant.parse("2026-10-17T19:00:00Z"))!!, 1e-9)
        // Halfway from the 8:00 AM low to the 2:10 PM high is halfway up.
        assertEquals((0.5 + 2.43) / 2, TideWidgetCurve.level(tides, Instant.parse("2026-10-17T22:05:00Z"))!!, 1e-9)
        assertNull(TideWidgetCurve.level(tides, Instant.parse("2026-10-17T18:00:00Z")))
        assertNull(TideWidgetCurve.level(tides, Instant.parse("2026-10-18T14:00:00Z")))
    }

    @Test fun saysWhetherTheTideIsComingIn() {
        assertEquals("Coming in ↑ · 1.5 m", TideWidgetCurve.trend(tides, Instant.parse("2026-10-17T22:05:00Z")))
        assertEquals("Going out ↓ · 2.4 m", TideWidgetCurve.trend(tides, Instant.parse("2026-10-18T01:20:00Z")))
        assertNull(TideWidgetCurve.trend(tides, Instant.parse("2026-10-18T14:00:00Z")))
    }
}
