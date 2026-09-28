package nz.fishingnz.app.ui

import nz.fishingnz.app.model.FishRuleDetail
import nz.fishingnz.app.model.FishRuleMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDisplayTextTest {
    @Test fun mpiMarkersRemainIdentifiableAfterDisplayCleanup() {
        val rule = FishRuleMatch(
            "Snapper (Auckland West)**", "10", "27", emptyList(), "Min fish length (cm)"
        )
        assertTrue(hasMpiFootnote(rule))
        assertEquals("Snapper (Auckland West)", cleanMpiRuleText(rule.species))
        assertEquals("Oysters – Dredge – Rock and Pacific", cleanMpiRuleText("Oysters – Dredge*+ – Rock and Pacific†"))
        assertTrue(hasMpiFootnoteMarker("All others (combined) •"))
        assertTrue(hasMpiFootnoteMarker("Toheroa#"))
        assertEquals("Toheroa", cleanMpiRuleText("Toheroa#"))
        assertTrue(hasMpiFootnote(FishRuleMatch("Blue cod", "2", "33 headed**", listOf(FishRuleDetail("Rule", "See MPI")))))
        assertFalse(hasMpiFootnoteMarker("A + B"))
        assertEquals("A + B", cleanMpiRuleText("A + B"))
        assertEquals("Varies — see MPI", cleanMpiLimitValue("125/85** 80"))
    }

    @Test fun aiEmphasisDoesNotShowRawMarkdown() {
        assertEquals("Blue cod: dark bars", cleanIdentificationText("**Blue cod:** dark bars"))
        assertEquals("Check the tail", cleanIdentificationText("__Check__ the *tail*"))
        assertEquals("Visible clues\n• Dark bars", cleanIdentificationText("### Visible clues\n- **Dark bars**"))
    }
}
