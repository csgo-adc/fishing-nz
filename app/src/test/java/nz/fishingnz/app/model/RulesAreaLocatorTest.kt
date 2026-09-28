package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class RulesAreaLocatorTest {
    @Test fun suggestsMainlandAreasFromRepresentativeLocations() {
        assertEquals("auckland-kermadec", suggestedRulesAreaId(GeoPoint(-37.787, 175.279))) // Hamilton
        assertEquals("auckland-kermadec", suggestedRulesAreaId(GeoPoint(-37.799, 174.870))) // Raglan
        assertEquals("central", suggestedRulesAreaId(GeoPoint(-38.672, 178.020))) // Gisborne
        assertEquals("central", suggestedRulesAreaId(GeoPoint(-41.286, 174.776))) // Wellington
        assertEquals("challenger", suggestedRulesAreaId(GeoPoint(-41.271, 173.284))) // Nelson
        assertEquals("south-east", suggestedRulesAreaId(GeoPoint(-43.532, 172.636))) // Christchurch
        assertEquals("southland", suggestedRulesAreaId(GeoPoint(-46.598, 168.330))) // Bluff
    }

    @Test fun suggestsSpecialAreasOnlyNearTheirCoasts() {
        assertEquals("kaikoura", suggestedRulesAreaId(GeoPoint(-42.404, 173.684)))
        assertEquals("fiordland", suggestedRulesAreaId(GeoPoint(-44.669, 167.926)))
        assertEquals("chatham-rise", suggestedRulesAreaId(GeoPoint(-43.953, -176.558)))
        assertEquals("auckland-kermadec", suggestedRulesAreaId(GeoPoint(-29.25, -177.92))) // Raoul Island
        assertEquals("south-east", suggestedRulesAreaId(GeoPoint(-43.532, 172.636)))
    }

    @Test fun doesNotSuggestAnAreaOutsideKnownNewZealandLocations() {
        assertNull(suggestedRulesAreaId(GeoPoint(-33.8688, 151.2093))) // Sydney
        assertNull(suggestedRulesAreaId(GeoPoint(Double.NaN, 174.76)))
    }

    @Test fun raglanWestCoastHintIsLocalToRaglan() {
        assertTrue(isNearRaglan(GeoPoint(-37.799, 174.870)))
        assertFalse(isNearRaglan(GeoPoint(-36.8485, 174.7633))) // Auckland east coast
    }
}
