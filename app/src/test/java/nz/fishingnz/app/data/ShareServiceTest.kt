package nz.fishingnz.app.data

import kotlinx.coroutines.runBlocking
import nz.fishingnz.app.model.ConditionItem
import nz.fishingnz.app.model.Recommendation
import nz.fishingnz.app.model.WindowAssessment
import nz.fishingnz.app.model.WindowMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareServiceTest {
    private class FakeBackend(var id: String? = "k3F9xQ2m", var stored: String? = null) : ShareBackend {
        val created = mutableListOf<String>()
        val read = mutableListOf<String>()
        override suspend fun create(token: String): String? { created += token; return id }
        override suspend fun read(id: String): String? { read += id; return stored }
    }

    private fun window(startsAt: Long = 1_791_694_800L) = Recommendation(
        name = "Takapuna Beach", area = "Auckland", rating = 70, time = "Sun 11 Oct, 6:00 PM–8:00 PM", distance = "12 km straight line",
        reasons = listOf("Lower discomfort"), boat = false, startsAtEpochSeconds = startsAt, durationHours = 2,
        assessment = WindowAssessment(WindowMood("🙂", "Okay"), listOf(ConditionItem("Wind", "12 km/h", WindowMood("😌", "Comfortable"))), WindowMood("🗓️", "Planning forecast"))
    )

    @Test fun aWindowIsSharedAsAShortLinkWhenTheServiceAnswers() = runBlocking {
        val backend = FakeBackend()
        val link = ShareService(backend).linkFor(window())!!
        assertEquals("https://fishing.fishnz.space/w/k3F9xQ2m", link)
        assertEquals("the service is sent the window's token", 1, backend.created.size)
        assertEquals("Takapuna Beach", SharedWindowLink.decode(backend.created.single())!!.name)
    }

    @Test fun sharingStillWorksOfflineWithTheLongLink() = runBlocking {
        val link = ShareService(FakeBackend(id = null)).linkFor(window())!!
        assertTrue(link, link.startsWith("https://fishing.fishnz.space/w/") && link.length > 200)
        assertEquals("Takapuna Beach", SharedWindowLink.parse(link)!!.name)
    }

    @Test fun anIdThatIsNotOneOfOursIsNeverShared() = runBlocking {
        listOf("", "evil", "https://evil.example/x", "k3F9xQ20", "k3F9xQ2mextra").forEach { id ->
            val link = ShareService(FakeBackend(id = id)).linkFor(window())!!
            assertTrue("$id -> $link", link.startsWith("https://fishing.fishnz.space/w/") && link.length > 200)
        }
    }

    @Test fun aWindowWithoutAStartTimeIsNotSentAnywhere() = runBlocking {
        val backend = FakeBackend()
        assertNull(ShareService(backend).linkFor(window(startsAt = 0)))
        assertTrue(backend.created.isEmpty())
    }

    @Test fun aShortLinkIsLookedUpAndALongOneIsNot() = runBlocking {
        val long = SharedWindowLink.encode(window())!!
        val backend = FakeBackend(stored = long)
        val fromShort = ShareService(backend).resolve("k3F9xQ2m")!!
        assertEquals("Takapuna Beach", fromShort.name)
        assertEquals(listOf("k3F9xQ2m"), backend.read)
        assertEquals("Takapuna Beach", ShareService(backend).resolve(long)!!.name)
        assertEquals("a long link needs no lookup", 1, backend.read.size)
    }

    @Test fun anUnknownExpiredOrBrokenShortLinkOpensNothing() = runBlocking {
        assertNull(ShareService(FakeBackend(stored = null)).resolve("k3F9xQ2m"))
        assertNull(ShareService(FakeBackend(stored = "garbage")).resolve("k3F9xQ2m"))
        assertNull(ShareService(FakeBackend()).resolve("not-a-window"))
        assertNotNull(ShareService(FakeBackend(stored = SharedWindowLink.encode(window()))).resolve("k3F9xQ2m"))
    }
}
