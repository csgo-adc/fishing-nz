package nz.fishingnz.app.data

import nz.fishingnz.app.model.ConditionItem
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.LandPriority
import nz.fishingnz.app.model.Recommendation
import nz.fishingnz.app.model.RecommendationSearch
import nz.fishingnz.app.model.ScoreFactor
import nz.fishingnz.app.model.WindowAssessment
import nz.fishingnz.app.model.WindowMood
import nz.fishingnz.app.model.WindowPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowCacheTest {
    private val raglan = GeoPoint(-37.8, 174.87)
    private val start = 1_792_360_800L
    private val now = start * 1000 - 60 * 60 * 1000L
    private val window = Recommendation(name = "Raglan", area = "Waikato", rating = 80, time = "Sat 18 Oct · 10:00 AM–12:00 PM",
        distance = "12 km", reasons = listOf("Light wind"), boat = true, factors = listOf(ScoreFactor("Wind", 90, 3, "Light")),
        warnings = listOf("Rain could affect the end"), distanceKm = 12.4, startsAtEpochSeconds = start, durationHours = 2,
        summary = "Calm morning", conditions = listOf("Wind: 8 km/h"), sourceNote = "Updated 9:00 AM.", rankingValue = 81.5,
        daylightFraction = 0.75, tidePreferenceFit = 0.5,
        assessment = WindowAssessment(WindowMood("😌", "Comfortable"), listOf(ConditionItem("Wind", "Light wind: 8 km/h", WindowMood("🙂", "Good"))),
            WindowMood("👍", "Good confidence"), details = listOf("Check the bar"), band = 1, maxWind = 12.0, maxGust = 20.5, rainTotal = 0.4,
            priority = LandPriority.CASTING, preference = WindowPriority.TIDE),
        latitude = -37.8, longitude = 174.88)
    private val search = RecommendationSearch(listOf(window, window.copy(name = "Kawhia", startsAtEpochSeconds = start - 3 * 3600, latitude = Double.NaN)),
        nearbySpots = 4, failedSpots = 0, nearestSpot = "Raglan", nearestSpotDistanceKm = 12, spotsWithoutWindows = listOf("Aotea"))
    private fun entry(savedAt: Long = now, key: String = "land|50", origin: GeoPoint = raglan, build: Int = 72) =
        WindowCache.Entry(key, origin, savedAt, build, search)

    @Test fun windowsSurviveARestartExactly() {
        val restored = WindowCache.decodeAll(WindowCache.encodeAll(listOf(entry()))).single()
        assertEquals(entry().copy(search = search.copy(items = listOf(window, search.items[1]))).search.items[0], restored.search.items[0])
        assertTrue(restored.search.items[1].latitude.isNaN())
        assertEquals(search.spotsWithoutWindows, restored.search.spotsWithoutWindows)
        assertEquals(12, restored.search.nearestSpotDistanceKm)
        assertEquals("land|50", restored.key)
    }

    @Test fun freshForAnHourThenShownWhileRefreshingUntilSixHours() {
        val entries = listOf(entry())
        assertEquals(WindowCache.Freshness.FRESH, WindowCache.lookup(entries, "land|50", raglan, now + 59 * 60_000L, 72)?.freshness)
        // Only the Kawhia window (ended at the start of Raglan's) is gone after it ends.
        val later = now + 2 * 60 * 60_000L
        val hit = WindowCache.lookup(entries, "land|50", raglan, later, 72)
        assertEquals(WindowCache.Freshness.STALE, hit?.freshness)
        assertEquals(listOf("Raglan"), hit?.entry?.search?.items?.map { it.name })
        assertNull(WindowCache.lookup(listOf(entry(savedAt = now - 6 * 3_600_000L - 1)), "land|50", raglan, now, 72))
    }

    @Test fun anotherPlanPlaceOrBuildSearchesAgain() {
        val entries = listOf(entry())
        assertNull(WindowCache.lookup(entries, "boat|50", raglan, now, 72))
        assertNull(WindowCache.lookup(entries, "land|50", raglan, now, 73))
        // GPS drift of about 1 km keeps the windows; 3 km away does not.
        assertNotNull(WindowCache.lookup(entries, "land|50", GeoPoint(-37.809, 174.87), now, 72))
        assertNull(WindowCache.lookup(entries, "land|50", GeoPoint(-37.827, 174.87), now, 72))
    }

    @Test fun aListWhoseWindowsHaveAllEndedIsNotShown() {
        val afterAll = (start + 3 * 3600) * 1000
        assertNull(WindowCache.lookup(listOf(entry(savedAt = afterAll - 60_000)), "land|50", raglan, afterAll, 72))
    }

    @Test fun keepsTheLastSixPlansNewestFirst() {
        var entries = emptyList<WindowCache.Entry>()
        for (index in 0 until 8) entries = WindowCache.storing(entries, entry(savedAt = now + index, key = "plan$index"))
        assertEquals(listOf("plan7", "plan6", "plan5", "plan4", "plan3", "plan2"), entries.map { it.key })
        entries = WindowCache.storing(entries, entry(savedAt = now + 10, key = "plan4"))
        assertEquals(listOf("plan4", "plan7", "plan6", "plan5", "plan3", "plan2"), entries.map { it.key })
    }

    @Test fun aSearchFailureCanFallBackToADayOldList() {
        val dayOld = listOf(entry(savedAt = now - 20 * 3_600_000L))
        assertNull(WindowCache.lookup(dayOld, "land|50", raglan, now, 72))
        assertNotNull(WindowCache.lookup(dayOld, "land|50", raglan, now, 72, maxAgeMs = WindowCache.OFFLINE_MS))
    }
}
