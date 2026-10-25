package nz.fishingnz.app.data

import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.fishingSpots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpotListingTest {
    // Unit tests run from the app module, so the bundled assets are read straight from the source tree.
    private val ramps = BoatRamps.parse(File("src/main/assets/boat_ramps.json").readText())
    private val structures = ShoreStructures.parse(File("src/main/assets/shore_structures.json").readText())
    private val areas = MarineProtection.parse(File("src/main/assets/marine_protection.json").readText()).areas

    @Test fun listsNearestFirstFromThePhone() {
        val takapuna = GeoPoint(-36.786, 174.773)
        val listings = SpotListings.build(fishingSpots, takapuna, ramps, structures, areas)
        assertEquals(fishingSpots.size, listings.size)
        assertEquals("Takapuna Beach", listings.first().spot.name)
        assertEquals(listings.sortedBy { it.distanceKm }, listings)
        assertEquals("0 m", SpotListings.distanceLabel(listings.first().distanceKm!!))
    }

    @Test fun listsByAreaWithoutAPosition() {
        val listings = SpotListings.build(fishingSpots, null, ramps, structures, areas)
        assertTrue(listings.all { it.distanceKm == null })
        assertEquals(listings.sortedWith(compareBy({ it.spot.area }, { it.spot.name })), listings)
    }

    @Test fun notesRampsWharvesAndProtectedAreas() {
        val listings = SpotListings.build(fishingSpots, null, ramps, structures, areas)
        assertTrue(listings.filter { it.spot.boat }.count { it.nearby?.startsWith("Boat ramp ") == true } * 2 > listings.count { it.spot.boat })
        assertTrue(listings.filter { !it.spot.boat }.any { it.nearby?.matches(Regex("(Wharf|Breakwater|Groyne) .+ away")) == true })
        assertTrue(listings.any { it.warning == "No-fishing area nearby" || it.warning == "Inside a no-fishing area" })
        val withoutData = SpotListings.build(fishingSpots.take(3), null, null, null, null)
        assertTrue(withoutData.all { it.nearby == null && it.warning == null })
    }

    @Test fun searchesNameAndArea() {
        val listing = SpotListings.build(fishingSpots, null, null, null, null).first { it.spot.name == "Takapuna Beach" }
        assertTrue(SpotListings.matches(listing, ""))
        assertTrue(SpotListings.matches(listing, " taka"))
        assertTrue(SpotListings.matches(listing, "auckland"))
        assertFalse(SpotListings.matches(listing, "Kaikōura"))
        assertNull(listing.distanceKm)
        assertEquals("120 km", SpotListings.distanceLabel(120.4))
        assertEquals("4.2 km", SpotListings.distanceLabel(4.24))
    }
}
