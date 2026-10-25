package nz.fishingnz.app.ui

import nz.fishingnz.app.data.FishingRulesPage
import nz.fishingnz.app.data.FishingRulesSection
import nz.fishingnz.app.data.SpeciesCatalog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleRowsTest {
    private val page = FishingRulesPage("Central", "https://example.com", "04.12.25", false,
        listOf(FishingRulesSection("Finfish", "There is a combined daily bag limit of 20 finfish per person.")),
        listOf(listOf(listOf("Finfish species", "Maximum daily limit per active fisher", "Min fish length (cm)"),
            listOf("Snapper", "7", "30"), listOf("Kahawai", "10", ""), listOf("Blue cod – Titahi Bay to Cape Runaway", "2", "33")),
            listOf(listOf("Shellfish species", "Daily limit", "Min size (mm)"), listOf("Pāua – Ordinary black foot", "10", "125"))))
    private val rows = ruleSpeciesRows(page)

    @Test fun readsMinimumsAndLimits() {
        val snapper = rows.first { it.species == "Snapper" }
        assertEquals(30.0 to "cm", snapper.minimum)
        assertEquals("7", snapper.dailyLimit)
        assertNull(rows.first { it.species == "Kahawai" }.minimum)
        assertEquals(125.0 to "mm", rows.first { it.species.startsWith("Pāua") }.minimum)
        assertEquals(20, combinedFinfishLimit(page))
    }

    @Test fun checksACatch() {
        val snapper = rows.first { it.species == "Snapper" }
        val small = checkCatch(snapper, 28.0)!!
        assertFalse(small.meets)
        assertEquals("28 cm is under the 30 cm minimum. Release it carefully.", small.text)
        val keep = checkCatch(snapper, 31.5)!!
        assertTrue(keep.meets)
        assertEquals("31.5 cm meets the 30 cm minimum. It counts toward the daily limit of 7.", keep.text)
        assertNull(checkCatch(rows.first { it.species == "Kahawai" }, 40.0))
        assertNull(checkCatch(snapper, 0.0))
    }

    @Test fun listsPopularSpeciesFirst() {
        assertEquals(listOf("Snapper", "Blue cod – Titahi Bay to Cape Runaway", "Kahawai", "Pāua – Ordinary black foot"), popularRows(rows).map { it.species })
    }

    @Test fun matchesEveryCommonRuleToItsPicture() {
        // Unit tests run from the app module, so the bundled copy is read straight from the source tree.
        val bundle = JSONObject(File("src/main/assets/offline_rules.json").readText())
        val catalog = SpeciesCatalog.parse(bundle.getJSONObject("species"))
        assertEquals("snapper", catalog.forRule("Snapper: Paringa, Popotai Taumaka/Open Bay Islands Mātaitai Reserves")?.id)
        assertEquals("paua", catalog.forRule("Paua – Ordinary – Yellow foot")?.id)
        assertEquals("packhorse-lobster", catalog.forRule("Packhorse rock lobster")?.id)
        assertEquals("rock-lobster", catalog.forRule("Red or spiny rock lobster")?.id)
        assertEquals("green-lipped-mussel", catalog.forRule("Green-lipped mussels")?.id)
        assertEquals("mussel", catalog.forRule("Mussels")?.id)
        assertEquals("blue-cod", catalog.forRule("Blue cod –Te Whaka ā Te Wera/Paterson Inlet Mātaitai")?.id)
        assertNull(catalog.forRule("All other finfish"))
        assertEquals(94, catalog.pictures.size)
        val common = catalog.commonCatches(listOf("Snapper", "Kahawai"))
        assertEquals(listOf("john-dory", "leatherjacket", "porae", "sweep", "blue-maomao", "red-snapper", "blue-mackerel"), common.map { it.id })
        assertTrue(common.all { it.combined && it.pictures.isNotEmpty() })
        // A common catch that gets a rule of its own in an area shows with that rule instead.
        assertEquals(6, catalog.commonCatches(listOf("John dory")).size)
        assertEquals(listOf("Hāpuku", "Bass"), catalog.forRule("Groper (Hapuku) / Bass")?.pictures?.map { it.caption })
        assertEquals(7, catalog.forRule("Flatfish (except Sand flounder)")?.pictures?.size)
        assertNull(catalog.forRule("Ringed something"))
        val rules = bundle.getJSONArray("rules")
        val names = (0 until rules.length()).flatMap { index ->
            val item = rules.getJSONObject(index)
            val sections = item.getJSONArray("sections"); val tables = item.getJSONArray("tables")
            ruleSpeciesRows(FishingRulesPage("", "", null, false,
                (0 until sections.length()).map { FishingRulesSection("", sections.getJSONObject(it).optString("text")) },
                (0 until tables.length()).map { t -> tables.getJSONArray(t).let { table -> (0 until table.length()).map { r -> table.getJSONArray(r).let { row -> (0 until row.length()).map(row::optString) } } } }))
        }.map { it.species }
        val unmatched = names.filter { catalog.forRule(it) == null }
        // Only catch-all and area rows ("All other finfish", "Both species combined", mātaitai names) may lack a picture.
        assertTrue(unmatched.toString(), unmatched.size * 10 < names.size)
    }
}
