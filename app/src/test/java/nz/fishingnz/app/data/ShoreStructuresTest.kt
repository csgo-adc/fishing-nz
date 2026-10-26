package nz.fishingnz.app.data

import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.fishingSpots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShoreStructuresTest {
    // Unit tests run from the app module, so the bundled asset is read straight from the source tree.
    private val structures = ShoreStructures.parse(File("src/main/assets/shore_structures.json").readText())

    @Test fun readsTheBundledStructures() {
        assertTrue(structures.size > 400)
        assertTrue(structures.all { it.point.latitude in -48.5..-33.5 && it.point.longitude in 165.0..179.5 })
        assertEquals(setOf("wharf", "breakwater", "groyne"), structures.map { it.kind }.toSet())
        assertEquals("Wharf or jetty", structures.first { it.name == null && it.kind == "wharf" }.label)
        assertEquals("Breakwater", structures.first { it.name == null && it.kind == "breakwater" }.label)
        assertEquals("Breakwater · access and fishing rules not checked", structures.first { it.kind == "breakwater" }.mapSubtitle)
    }

    @Test fun findsTheNearestStructuresForMostLandAreas() {
        val landSpots = fishingSpots.filter { !it.boat }
        val covered = landSpots.count { ShoreStructures.nearest(GeoPoint(it.latitude, it.longitude), structures, limit = 1).isNotEmpty() }
        assertTrue("$covered of ${landSpots.size}", covered * 10 >= landSpots.size * 6)
        val oamaru = fishingSpots.first { it.name == "Oamaru Harbour" && !it.boat }
        val nearest = ShoreStructures.nearest(GeoPoint(oamaru.latitude, oamaru.longitude), structures)
        assertEquals(3, nearest.size)
        assertEquals(nearest.sortedBy { it.distanceKm }, nearest)
        assertTrue(nearest.all { it.distanceKm <= 10 })
        assertTrue(nearest.any { it.structure.name == "Oamaru Breakwater" })
    }
}
