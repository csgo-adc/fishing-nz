package nz.fishingnz.app.data

import nz.fishingnz.app.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PlaceConditionsShareTest {
    private val today = LocalDate.parse("2026-10-16")
    private val rows = listOf(
        ConditionItem("Offshore waves", "Up to 0.8 m, 6–7 seconds apart", WindowMood("🌊", "More motion")),
        ConditionItem("Wind", "Breezy: up to 22 km/h, gusts to 28 km/h, from the south", comfortMood(1)),
        ConditionItem("Rain", "0.4 mm all day, up to 40% chance in any hour", WindowMood("☔", "Some rain")),
        ConditionItem("Feels like", "—", needsDataMood),
        ConditionItem("Daylight", "6:32 AM–7:41 PM", WindowMood("🌞", "Plan your return")),
        ConditionItem("UV", "5.2", WindowMood("☀️", "Sun protection")))

    @Test fun messageListsTheDayTideAndAMapLink() {
        val place = ConditionPlace("Takapuna Beach", GeoPoint(-36.787123, 174.770456), "Auckland · Land fishing")
        val station = tideStations.first { it.name == "Auckland" }
        val tide = TideState("", "", "", listOf(TideEvent("7:12 AM", "2.41 m", "High"), TideEvent("1:20 PM", "0.52 m", "Low")))
        val distance = String.format(java.util.Locale.US, "%.1f", placeDistanceKm(place.point, GeoPoint(station.latitude, station.longitude)))
        assertEquals("""
            Fishing conditions · Takapuna Beach
            Auckland · Land fishing
            Sun 18 Oct · Short-range forecast

            Offshore waves – Up to 0.8 m, 6–7 seconds apart (More motion)
            Wind – Breezy: up to 22 km/h, gusts to 28 km/h, from the south (Okay)
            Rain – 0.4 mm all day, up to 40% chance in any hour (Some rain)
            Daylight – 6:32 AM–7:41 PM
            Tide at Auckland ($distance km away) – High 7:12 AM, low 1:20 PM

            Map: https://www.google.com/maps/search/?api=1&query=-36.7871,174.7705
            Weather and waves: Open-Meteo. Tides: LINZ. Check local conditions and marine warnings before you go.
        """.trimIndent(), PlaceConditionsShare.text(place, LocalDate.parse("2026-10-18"), today, rows, tide, station))
    }

    @Test fun aDroppedPinHasNoPlaceNameOrTide() {
        val pin = ConditionPlace("Dropped pin", GeoPoint(-37.8, 174.87), "Selected on map")
        val text = PlaceConditionsShare.text(pin, LocalDate.parse("2026-10-14"), today, rows, null, null)
        assertEquals("Fishing conditions · pin on the map\nWed 14 Oct · Recent model data", text.lines().take(2).joinToString("\n"))
        assertEquals(false, text.contains("Tide at"))
    }

    @Test fun forecastLabelsFollowTheConditionsPage() {
        assertEquals(listOf("Recent model data", "Short-range forecast", "Short-range forecast", "Planning forecast", "Planning forecast", "Early outlook"),
            listOf(-1L, 0L, 2L, 3L, 5L, 6L).map { PlaceConditionsShare.forecastLabel(today.plusDays(it), today) })
    }
}
