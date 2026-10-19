package nz.fishingnz.app.data

import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.fishingSpots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MarineProtectionTest {
    // Unit tests run from the app module, so the bundled asset is read straight from the source tree.
    private val catalogue = MarineProtection.parse(File("src/main/assets/marine_protection.json").readText())
    private fun area(name: String) = catalogue.areas.first { it.name == name }

    @Test fun readsEveryKindOfArea() {
        assertTrue(catalogue.areas.size >= 60)
        assertTrue(catalogue.areas.any { it.kind == ProtectionKind.RESERVE })
        assertTrue(catalogue.areas.any { it.kind == ProtectionKind.HIGH_PROTECTION })
        assertTrue(catalogue.areas.any { it.kind == ProtectionKind.SEAFLOOR_PROTECTION })
        assertTrue(org.json.JSONObject(catalogue.geoJson).getJSONArray("features").length() == catalogue.areas.size)
    }

    @Test fun findsPointsInsideAndOutside() {
        // Goat Island Bay, inside Cape Rodney-Okakari Point Marine Reserve; Leigh township is outside it.
        val goat = GeoPoint(-36.2667, 174.7958)
        assertTrue(area("Cape Rodney-Okakari Point Marine Reserve").contains(goat))
        assertEquals("Cape Rodney-Okakari Point Marine Reserve", MarineProtection.notices(goat, catalogue.areas).first().area.name)
        assertTrue(MarineProtection.notices(goat, catalogue.areas).first().inside)
        assertTrue(MarineProtection.notices(GeoPoint(-36.85, 174.76), catalogue.areas).isEmpty())
    }

    @Test fun islandsCutOutOfAnAreaAreOutsideIt() {
        // Tawhiti Rahi, the northern Poor Knights island, is a hole in the reserve around it.
        val reserve = area("Poor Knights Islands Marine Reserve")
        assertFalse(reserve.contains(GeoPoint(-35.4576, 174.7373)))
        assertTrue(reserve.contains(GeoPoint(-35.4576, 174.7480)))
    }

    @Test fun listedSpotsAreOutsideButSomeAreClose() {
        // The catalogue excludes spots inside protected areas; a few sit right beside one.
        fishingSpots.forEach { spot ->
            assertTrue(spot.name, MarineProtection.notices(GeoPoint(spot.latitude, spot.longitude), catalogue.areas).none { it.inside })
        }
        val stClair = fishingSpots.first { it.name == "St Clair Beach" }
        val notice = MarineProtection.notices(GeoPoint(stClair.latitude, stClair.longitude), catalogue.areas).first()
        assertEquals("Ōrau Marine Reserve", notice.area.name)
        assertTrue(notice.text, notice.text.endsWith("from Ōrau Marine Reserve. No fishing or taking of any marine life."))
    }

    @Test fun labelsDistances() {
        assertEquals("Under 50 m", MarineProtection.distanceLabel(0.02))
        assertEquals("200 m", MarineProtection.distanceLabel(0.203))
        assertEquals("1.5 km", MarineProtection.distanceLabel(1.48))
    }
}
