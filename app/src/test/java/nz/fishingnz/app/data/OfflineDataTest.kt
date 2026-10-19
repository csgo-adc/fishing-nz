package nz.fishingnz.app.data

import nz.fishingnz.app.model.ConditionItem
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.Recommendation
import nz.fishingnz.app.model.WindowAssessment
import nz.fishingnz.app.model.WindowMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OfflineDataTest {
    private val window = Recommendation(name = "Raglan", area = "Waikato", rating = 80, time = "Sat 18 Oct · 10:00 AM–12:00 PM",
        distance = "12 km", reasons = listOf("Light wind"), startsAtEpochSeconds = 1_792_360_800, durationHours = 2,
        assessment = WindowAssessment(WindowMood("😌", "Comfortable"), listOf(ConditionItem("Wind", "Light wind: 8 km/h", WindowMood("🙂", "Good"))),
            WindowMood("👍", "Good confidence")), latitude = -37.8, longitude = 174.88)

    @Test fun tripsSurviveARestartAsSavedWindows() {
        val (saved, active) = TripStore.decode(TripStore.encode(listOf(window, window.copy(name = "Kawhia")), window))
        assertEquals(listOf("Raglan", "Kawhia"), saved.map { it.name })
        assertEquals("Saved · forecast may have changed", saved[0].distance)
        assertEquals(listOf(TripStore.SAVED_NOTE), saved[0].assessment?.details)
        assertEquals("Comfortable", saved[0].assessment?.mood?.label)
        assertEquals(1_792_360_800, saved[0].startsAtEpochSeconds)
        assertEquals(-37.8, saved[0].latitude, 0.0001)
        assertEquals("Raglan", active?.name)
        // A window without a time cannot be stored, so it is left out rather than breaking the rest.
        assertTrue(TripStore.decode(TripStore.encode(listOf(window.copy(startsAtEpochSeconds = 0)), null)).first.isEmpty())
        assertNull(TripStore.decode("{}").second)
    }

    @Test fun cacheKeepsTheLastResponseWithItsTime() {
        val dir = Files.createTempDirectory("offline").toFile()
        try {
            OfflineCache.useFolder(dir)
            val key = OfflineCache.placeKey("weather", GeoPoint(-37.80012, 174.87991))
            assertEquals("weather_-37.800_174.880.json", key)
            assertNull(OfflineCache.read(key))
            OfflineCache.write(key, "{\"a\":1}")
            val (text, at) = OfflineCache.read(key)!!
            assertEquals("{\"a\":1}", text)
            assertTrue(at.toEpochMilli() > 0)
            assertEquals("Sun 18 Oct, 12:00 PM", OfflineCache.savedLabel(java.time.Instant.parse("2026-10-17T23:00:00Z")))
        } finally {
            OfflineCache.useFolder(null)
            dir.deleteRecursively()
        }
    }
}
