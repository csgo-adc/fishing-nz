package nz.fishingnz.app.data

import nz.fishingnz.app.model.ConditionItem
import nz.fishingnz.app.model.Recommendation
import nz.fishingnz.app.model.WindowAssessment
import nz.fishingnz.app.model.WindowMood
import nz.fishingnz.app.model.windowOutlook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SharedWindowLinkTest {
    // The same link is decoded by the iPhone regression checks and the Worker tests, so all three agree on the format.
    private val goldenToken = "eyJ2IjoxLCJuIjoiVGFrYXB1bmEgQmVhY2giLCJhIjoiQXVja2xhbmQiLCJiIjowLCJzIjoxNzkxNjk0ODAwLCJlIjoxNzkxNzAyMDAwLCJsYSI6LTM2Ljc4NzEsImxvIjoxNzQuNzcwNSwibyI6WyLwn5mCIiwiT2theSJdLCJmIjpbIvCfl5PvuI8iLCJQbGFubmluZyBmb3JlY2FzdCJdLCJyIjoiTG93ZXIgZGlzY29tZm9ydCIsInQiOjE3OTE2MDg0MDAsImMiOltbIldpbmQiLCIxMiBrbS9oIMK3IGd1c3QgMjAgwrcgU1ciLCLwn5iMIiwiQ29tZm9ydGFibGUiXSxbIlRpZGUiLCJOZXh0IGhpZ2ggNzoxMiBQTSDCtyAyLjQgbSBDRFxuQXVja2xhbmQgKFdhaXRlbWF0xIEpIiwi8J-VkiIsIlRpZGUgdGltaW5nIl1dfQ"
    private val start = 1_791_694_800L // Sunday 11 October 2026, 6:00 PM NZDT

    private fun window(
        rows: List<ConditionItem> = listOf(
            ConditionItem("Wind", "12 km/h · gust 20 · SW", WindowMood("😌", "Comfortable")),
            ConditionItem("Tide", "Next high 7:12 PM · 2.4 m CD\nAuckland (Waitematā)", WindowMood("🕒", "Tide timing"))
        ),
        name: String = "Takapuna Beach"
    ) = Recommendation(
        name = name, area = "Auckland", rating = 70, time = "Sun 11 Oct, 6:00 PM–8:00 PM", distance = "12 km straight line",
        reasons = listOf("Lower discomfort"), boat = false, startsAtEpochSeconds = start, durationHours = 2,
        assessment = WindowAssessment(WindowMood("🙂", "Okay"), rows, WindowMood("🗓️", "Planning forecast")),
        latitude = -36.7871, longitude = 174.7705
    )

    private fun token(json: JSONObject) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
    private fun valid() = JSONObject().put("v", 1).put("n", "Spot").put("a", "Area").put("b", 0).put("s", start).put("e", start + 7200)

    @Test fun theSharedLinkFormatIsReadTheSameWayOnEveryPlatform() {
        val read = SharedWindowLink.decode(goldenToken)!!
        assertEquals("Takapuna Beach", read.name)
        assertEquals("Auckland", read.area)
        assertFalse(read.boat)
        assertEquals(start, read.startsAtEpochSeconds)
        assertEquals(2, read.durationHours)
        assertTrue(read.time, read.time.startsWith("Sun 11 Oct, 6:00"))
        assertEquals(-36.7871, read.latitude, 0.00001)
        assertEquals(174.7705, read.longitude, 0.00001)
        assertEquals("🙂 Okay", read.windowOutlook)
        assertEquals("Lower discomfort", read.reasons.single())
        val assessment = read.assessment!!
        assertEquals("🗓️ Planning forecast", "${assessment.confidence.emoji} ${assessment.confidence.label}")
        assertEquals(listOf("Wind", "Tide"), assessment.conditions.map { it.title })
        assertEquals("Next high 7:12 PM · 2.4 m CD\nAuckland (Waitematā)", assessment.conditions[1].value)
        assertEquals("🕒 Tide timing", "${assessment.conditions[1].mood.emoji} ${assessment.conditions[1].mood.label}")
    }

    @Test fun aWindowSurvivesBeingSharedAndOpened() {
        val original = window()
        val link = SharedWindowLink.longUrl(original, nowEpochSeconds = start - 86_400)!!
        assertTrue(link, link.startsWith("https://fishing.fishnz.space/w/"))
        val token = link.substringAfter("/w/")
        assertTrue("only URL-safe characters, no padding: $token", token.matches(Regex("[A-Za-z0-9_-]+")))
        val read = SharedWindowLink.parse(link)!!
        assertEquals(original.name, read.name)
        assertEquals(original.area, read.area)
        assertEquals(original.startsAtEpochSeconds, read.startsAtEpochSeconds)
        assertEquals(original.durationHours, read.durationHours)
        assertEquals(original.windowOutlook, read.windowOutlook)
        assertEquals(original.assessment!!.conditions, read.assessment!!.conditions)
        assertEquals(original.assessment!!.confidence, read.assessment!!.confidence)
        assertEquals(original.latitude, read.latitude, 0.00001)
        assertEquals(original.reasons, read.reasons)
        assertTrue(read.distance.startsWith("Shared with you"))
    }

    @Test fun theShareTextSaysWhereWhenAndHowItLooks() {
        val original = window()
        val text = SharedWindowLink.text(original, "https://fishing.fishnz.space/w/abc")
        assertEquals("Fishing window: Takapuna Beach, Auckland\nSun 11 Oct, 6:00 PM–8:00 PM · 🙂 Okay\nhttps://fishing.fishnz.space/w/abc", text)
    }

    @Test fun aSharedLinkIsLongEnoughForRealConditionsButStillShort() {
        val rows = listOf("Wind", "Rain", "Feels like", "Tide", "Offshore waves", "Daylight").map {
            ConditionItem(it, "14 km/h · gust 22 · SW and a few more words", WindowMood("🌤️", "Mostly dry"))
        }
        val link = SharedWindowLink.longUrl(window(rows))!!
        assertTrue("${link.length} characters", link.length < 1_000)
    }

    @Test fun aWindowWithoutAStartTimeCannotBeShared() {
        assertNull(SharedWindowLink.longUrl(window().copy(startsAtEpochSeconds = 0)))
        assertNull(SharedWindowLink.longUrl(window().copy(durationHours = 0)))
    }

    @Test fun onlyOurSharedWindowAddressesAreRecognised() {
        val token = "abc_DEF-123"
        assertEquals(token, SharedWindowLink.tokenFrom("https://fishing.fishnz.space/w/$token"))
        assertEquals(token, SharedWindowLink.tokenFrom("https://fishing.fishnz.space/w/$token/"))
        assertEquals(token, SharedWindowLink.tokenFrom("https://fishing.fishnz.space/w/$token?utm_source=chat#top"))
        assertEquals(token, SharedWindowLink.tokenFrom("HTTPS://FISHING.FISHNZ.SPACE/w/$token"))
        assertEquals("k3F9xQ2m", SharedWindowLink.tokenFrom("https://fishing.fishnz.space/w/k3F9xQ2m"))
        listOf(
            null, "", "https://fishing.fishnz.space/privacy", "https://fishing.fishnz.space/w/", "https://evil.example/w/$token",
            "https://fishing.fishnz.space.evil.example/w/$token", "http://fishing.fishnz.space/w/$token",
            "nz.fishingnz.app://auth/callback?code=1", "https://fishing.fishnz.space/w/bad token", "https://fishing.fishnz.space/w/${"a".repeat(5000)}"
        ).forEach { assertNull(it, SharedWindowLink.tokenFrom(it)) }
    }

    @Test fun shortIdsAreToldApartFromWindowsInTheAddress() {
        listOf("k3F9xQ2m", "23456789", "ZZzzZZzz").forEach { assertTrue(it, SharedWindowLink.isShortId(it)) }
        // Wrong length, or a look-alike character (0 O 1 I l) the service never uses, or a real window token.
        listOf("k3F9xQ2", "k3F9xQ2mm", "k3F9xQ20", "k3F9xQ2l", "k3F9xQ2I", "k3F9xQ2O", "k3F9xQ21", "", goldenToken)
            .forEach { assertFalse(it, SharedWindowLink.isShortId(it)) }
        assertEquals("https://fishing.fishnz.space/w/k3F9xQ2m", SharedWindowLink.shortUrl("k3F9xQ2m"))
        // A short link cannot be read without asking the service, so the offline reader declines it.
        assertNull(SharedWindowLink.parse("https://fishing.fishnz.space/w/k3F9xQ2m"))
        assertNotNull(SharedWindowLink.parse("https://fishing.fishnz.space/w/$goldenToken"))
    }

    @Test fun damagedOrForgedLinksAreRefused() {
        assertNull(SharedWindowLink.decode("not-base64!"))
        assertNull(SharedWindowLink.decode(Base64.getUrlEncoder().withoutPadding().encodeToString("not json".toByteArray())))
        assertNull(SharedWindowLink.decode(token(valid().put("v", 2))))
        assertNull(SharedWindowLink.decode(token(valid().put("n", "   "))))
        assertNull(SharedWindowLink.decode(token(valid().put("e", start))))
        assertNull(SharedWindowLink.decode(token(valid().put("e", start + 90_000))))
        assertNull(SharedWindowLink.decode(token(valid().put("s", 12))))
        assertNotNull(SharedWindowLink.decode(token(valid())))
    }

    @Test fun textFromALinkIsCleanedAndLimited() {
        val rows = JSONArray()
        repeat(12) { rows.put(JSONArray().put("Row $it").put("value").put("🙂").put("Okay")) }
        val read = SharedWindowLink.decode(token(valid().put("n", "A‮B\u0000C   D" + "x".repeat(200)).put("c", rows).put("la", 500.0).put("lo", 10.0)))!!
        assertTrue(read.name, read.name.startsWith("A B C D"))
        assertEquals(80, read.name.codePointCount(0, read.name.length))
        assertTrue(read.name.endsWith("…"))
        assertEquals(8, read.assessment!!.conditions.size)
        assertTrue("out-of-range coordinates are dropped", read.latitude.isNaN() && read.longitude.isNaN())
    }
}
