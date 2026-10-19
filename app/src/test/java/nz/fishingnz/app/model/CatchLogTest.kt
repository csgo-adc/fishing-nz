package nz.fishingnz.app.model

import nz.fishingnz.app.data.CatchConditionsLookup
import nz.fishingnz.app.data.CatchLogStore
import nz.fishingnz.app.data.LinzPrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CatchLogTest {
    private val snapperEast = FishRuleMatch("Snapper — Auckland East", "7", "30", emptyList(), "Minimum size (cm)")
    private val snapperWest = FishRuleMatch("Snapper — Auckland West", "7", "27", emptyList(), "Minimum size (cm)")
    private val paua = FishRuleMatch("Pāua", "10", "125", emptyList(), "Minimum size (mm)")
    private val cray = FishRuleMatch("Rock lobster", "6", "54 tail width (mm)", emptyList(), "Minimum size")
    private val noon = Instant.parse("2026-10-17T23:00:00Z") // Sunday 18 October, 12 PM in New Zealand

    private fun entry(id: String, species: String, at: Instant = noon, kept: Boolean = true, count: Int = 1, tide: String? = null) =
        CatchEntry(id, at.epochSecond, species, kept = kept, count = count, conditions = CatchConditions(tidePhase = tide))

    @Test fun comparesLengthWithEachMatchingRule() {
        val verdicts = CatchLog.sizeVerdicts(28.0, listOf(snapperEast, snapperWest))
        assertEquals(listOf(false, true), verdicts.map { it.meets })
        assertEquals("28 cm is under the 30 cm minimum for Snapper — Auckland East. Release it.", verdicts[0].text)
        assertEquals("28 cm meets the 27 cm minimum for Snapper — Auckland West.", verdicts[1].text)
    }

    @Test fun convertsMillimetresAndLeavesTailWidthsToMpi() {
        assertEquals(true, CatchLog.sizeVerdicts(12.6, listOf(paua)).single().meets)
        assertEquals(false, CatchLog.sizeVerdicts(12.4, listOf(paua)).single().meets)
        val tail = CatchLog.sizeVerdicts(30.0, listOf(cray)).single()
        assertNull(tail.meets)
        assertTrue(tail.text.startsWith("Check MPI"))
        assertTrue(CatchLog.sizeVerdicts(30.0, listOf(FishRuleMatch("Kahawai", "10", null, emptyList()))).isEmpty())
    }

    @Test fun countsKeptFishOnTheSameNewZealandDay() {
        val entries = listOf(entry("a", "Snapper", count = 2), entry("b", "snapper "), entry("c", "Snapper", kept = false),
            entry("d", "Snapper", at = noon.minusSeconds(13 * 3600)), entry("e", "Kahawai"),
            entry("f", "Snapper").copy(deleted = true))
        assertEquals(3, CatchLog.keptOnDay(entries, "Snapper", noon))
        assertEquals(2, CatchLog.keptOnDay(entries, "Snapper", noon, excludingId = "b"))
    }

    @Test fun describesPatternsOnlyWithThreeCatches() {
        assertTrue(CatchLog.insights(listOf(entry("a", "Snapper"), entry("b", "Snapper"))).isEmpty())
        val lines = CatchLog.insights(listOf(entry("a", "Snapper", tide = "Incoming"), entry("b", "Snapper", tide = "Incoming"),
            entry("c", "Kahawai", kept = false, tide = "Outgoing")))
        assertEquals("3 fish logged · 2 kept, 1 released", lines[0])
        assertEquals("Most caught: Snapper (2)", lines[1])
        assertTrue(lines.contains("Tide: mostly incoming (2 of 3 fish)"))
        assertTrue(lines.contains("Time of day: mostly afternoon (3 of 3 fish)"))
    }

    @Test fun exportsCsvWithQuotedCells() {
        val csv = CatchLog.csv(listOf(entry("a", "Snapper").copy(lengthCm = 32.5, notes = "Pilchard, \"ledger\" rig", placeName = "Raglan")))
        val row = csv.lines()[1]
        assertTrue(row, row.startsWith("2026-10-18 12:00,Snapper,32.5,Kept,1,Raglan,"))
        assertTrue(row.endsWith(",\"Pilchard, \"\"ledger\"\" rig\""))
    }

    @Test fun storeRoundTripsEveryField() {
        val original = CatchEntry("id-1", noon.epochSecond, "Snapper", 32.0, true, 2, "Raglan", -37.8, 174.88, "auckland-kermadec",
            "Notes", "id-1.jpg", CatchConditions("Incoming", "Incoming · 2 h after low (LINZ Raglan)", "Breezy", "Up to 1.0 m", "16°C", "🌕 Full moon", "Spring tides"),
            noon.epochSecond + 60, false)
        assertEquals(listOf(original), CatchLogStore.decode(CatchLogStore.encode(listOf(original))))
        assertTrue(!CatchLogStore.toJson(original, includePhoto = false).has("photo"))
    }

    @Test fun readsTheSharedFixtureLikeTheIphone() {
        // The same file is read by iosApp/Tests/CatchLogRegression.swift, so both apps keep one format for sync.
        val text = checkNotNull(javaClass.getResourceAsStream("/catches/catch-log.json")).bufferedReader().use { it.readText() }
        val entries = CatchLogStore.decode(text)
        assertEquals(listOf("7d0f3c1e-2a4b-4c55-9b1e-0f6b8e2d9a10", "b2", "b3"), entries.map { it.id })
        val first = entries[0]
        assertEquals(32.5, first.lengthCm!!, 0.0)
        assertEquals(2, first.count)
        assertEquals("Incoming", first.conditions.tidePhase)
        assertEquals("Neap tides", first.conditions.tideRange)
        assertTrue(entries[1].deleted)
        assertNull(entries[2].lengthCm)
        assertNull(entries[2].latitude)
        assertEquals(99, entries[2].count)
    }

    @Test fun namesThePartOfTheTide() {
        val low = LinzPrediction(Instant.parse("2026-10-18T00:00:00Z"), 0.4, false)
        val high = LinzPrediction(Instant.parse("2026-10-18T06:10:00Z"), 3.1, true)
        val nextLow = LinzPrediction(Instant.parse("2026-10-18T12:20:00Z"), 0.5, false)
        val tides = listOf(low, high, nextLow)
        fun at(time: String) = CatchConditionsLookup.tideAt(tides, Instant.parse(time), "Raglan")
        assertEquals("Incoming" to "Incoming · 2 h after low (LINZ Raglan)", at("2026-10-18T02:00:00Z"))
        assertEquals("Around high", at("2026-10-18T04:30:00Z")?.first)
        assertEquals("Around high", at("2026-10-18T07:00:00Z")?.first)
        assertEquals("Outgoing", at("2026-10-18T09:00:00Z")?.first)
        assertEquals("Around low", at("2026-10-18T11:30:00Z")?.first)
        assertEquals("Around low", at("2026-10-18T01:00:00Z")?.first)
        assertNull(at("2026-10-18T13:00:00Z"))
    }
}
