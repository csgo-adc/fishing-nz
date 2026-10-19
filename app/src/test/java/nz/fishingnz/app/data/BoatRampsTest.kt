package nz.fishingnz.app.data

import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.fishingSpots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class BoatRampsTest {
    // Unit tests run from the app module, so the bundled asset is read straight from the source tree.
    private val ramps = BoatRamps.parse(File("src/main/assets/boat_ramps.json").readText())

    @Test fun readsTheBundledRamps() {
        assertTrue(ramps.size > 900)
        assertTrue(ramps.all { it.point.latitude in -48.5..-33.5 && it.point.longitude in 165.0..179.5 })
        assertEquals("Boat ramp", ramps.first { it.name == null }.label)
    }

    @Test fun findsTheNearestRampsForMostBoatAreas() {
        val boatSpots = fishingSpots.filter { it.boat }
        val covered = boatSpots.count { BoatRamps.nearest(GeoPoint(it.latitude, it.longitude), ramps, limit = 1, withinKm = 15.0).isNotEmpty() }
        assertTrue("$covered of ${boatSpots.size}", covered * 10 >= boatSpots.size * 8)
        val kawakawa = fishingSpots.first { it.name == "Kawakawa Bay" && it.boat }
        val nearest = BoatRamps.nearest(GeoPoint(kawakawa.latitude, kawakawa.longitude), ramps)
        assertTrue(nearest.isNotEmpty() && nearest.size <= 3)
        assertEquals(nearest.sortedBy { it.distanceKm }, nearest)
        assertTrue(nearest.all { it.distanceKm <= 25 })
    }

    @Test fun notesALowTideNearTheTrip() {
        val start = Instant.parse("2026-10-17T21:00:00Z") // Sunday 10 AM in New Zealand
        val end = start.plusSeconds(7200)
        assertEquals("Low tide 11:05 AM, during your trip. Some ramps can't be used near low tide: check the ramp's limits.",
            BoatRamps.lowTideNote(listOf(Instant.parse("2026-10-17T22:05:00Z")), start, end))
        assertTrue(BoatRamps.lowTideNote(listOf(Instant.parse("2026-10-17T20:30:00Z")), start, end)!!.contains("just before your trip"))
        assertNull(BoatRamps.lowTideNote(listOf(Instant.parse("2026-10-18T03:00:00Z")), start, end))
        assertEquals("450 m", BoatRamps.distanceLabel(0.456))
        assertEquals("2.1 km", BoatRamps.distanceLabel(2.06))
        assertEquals("SW", BoatRamps.direction(GeoPoint(-36.8, 174.9), GeoPoint(-36.85, 174.84)))
        assertEquals("N", BoatRamps.direction(GeoPoint(-36.8, 174.9), GeoPoint(-36.7, 174.9)))
    }
}
