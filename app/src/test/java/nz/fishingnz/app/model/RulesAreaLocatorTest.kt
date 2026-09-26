package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RulesAreaLocatorTest {
    @Test fun suggestsMainlandAreasFromRepresentativeLocations() {
        assertEquals("auckland-kermadec", suggestedRulesAreaId(GeoPoint(-37.787, 175.279))) // Hamilton
        assertEquals("central", suggestedRulesAreaId(GeoPoint(-41.286, 174.776))) // Wellington
        assertEquals("challenger", suggestedRulesAreaId(GeoPoint(-41.271, 173.284))) // Nelson
        assertEquals("south-east", suggestedRulesAreaId(GeoPoint(-43.532, 172.636))) // Christchurch
        assertEquals("southland", suggestedRulesAreaId(GeoPoint(-46.598, 168.330))) // Bluff
    }

    @Test fun suggestsSpecialAreasOnlyNearTheirCoasts() {
        assertEquals("kaikoura", suggestedRulesAreaId(GeoPoint(-42.404, 173.684)))
        assertEquals("fiordland", suggestedRulesAreaId(GeoPoint(-44.669, 167.926)))
        assertEquals("chatham-rise", suggestedRulesAreaId(GeoPoint(-43.953, -176.558)))
        assertEquals("south-east", suggestedRulesAreaId(GeoPoint(-43.532, 172.636)))
    }

    @Test fun doesNotSuggestAnAreaOutsideKnownNewZealandLocations() {
        assertNull(suggestedRulesAreaId(GeoPoint(-33.8688, 151.2093))) // Sydney
        assertNull(suggestedRulesAreaId(GeoPoint(Double.NaN, 174.76)))
    }
}
