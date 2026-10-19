package nz.fishingnz.app.data

import nz.fishingnz.app.model.CatchEntry
import nz.fishingnz.app.model.Recommendation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AccountSyncTest {
    private val t0 = 1_792_300_000L
    private val window = Recommendation(name = "Raglan", area = "Waikato", rating = 80, time = "", distance = "", reasons = emptyList(),
        startsAtEpochSeconds = 1_792_360_800, durationHours = 2, latitude = -37.8, longitude = 174.88)
    private fun catch(id: String, species: String, updated: Long, photo: String? = null) =
        CatchEntry(id, t0, species, photoFile = photo, updatedAtEpochSeconds = updated)

    @Test fun sendsTripsAsOneDocumentAndCatchesWithoutPhotos() {
        val items = AccountSync.items(AccountSync.Trips(listOf(window), window, Instant.ofEpochSecond(t0)), t0, listOf(catch("a", "Snapper", t0, "a.jpg")))
        assertEquals(2, items.length())
        val trips = items.getJSONObject(0)
        assertEquals("trips", trips.getString("kind"))
        assertEquals(t0, trips.getJSONObject("payload").getLong("back_by"))
        val sent = items.getJSONObject(1)
        assertEquals("a", sent.getString("id"))
        assertTrue(!sent.getJSONObject("payload").has("photo"))
        // Trips that never changed on this device are not sent.
        assertEquals(1, AccountSync.items(AccountSync.Trips(emptyList(), null, null), 0, listOf(catch("a", "Snapper", t0))).length())
    }

    @Test fun takesOnlyNewerItemsAndKeepsLocalPhotos() {
        val local = listOf(catch("a", "Snapper", t0 + 10, "a.jpg"), catch("b", "Kahawai", t0, "b.jpg"))
        fun item(entry: CatchEntry, updated: Long, deleted: Boolean = false) = JSONObject().put("kind", "catch").put("id", entry.id)
            .put("payload", CatchLogStore.toJson(entry, includePhoto = false)).put("updated_at", updated).put("deleted", deleted)
        val response = JSONObject().put("items", JSONArray()
            .put(item(catch("a", "Old snapper", 0), t0))                // older than this device's copy
            .put(item(catch("b", "Trevally", 0), t0 + 20))              // newer: species changes, photo stays
            .put(item(catch("c", "Gurnard", 0), t0 + 5))                // from another device
            .put(JSONObject().put("kind", "trips").put("id", "trips").put("updated_at", t0 + 30)
                .put("payload", AccountSync.tripsDocument(AccountSync.Trips(listOf(window), null, null)))))
        val merged = AccountSync.merge(response, t0, local)
        val byId = merged.catches.associateBy { it.id }
        assertEquals("Snapper", byId.getValue("a").species)
        assertEquals("Trevally", byId.getValue("b").species)
        assertEquals("b.jpg", byId.getValue("b").photoFile)
        assertEquals(t0 + 20, byId.getValue("b").updatedAtEpochSeconds)
        assertEquals("Gurnard", byId.getValue("c").species)
        assertEquals(listOf("Raglan"), merged.trips?.saved?.map { it.name })
        assertNull(merged.trips?.active)
        assertEquals(t0 + 30, merged.tripsUpdatedAt)
        // A trips document older than this device's is ignored.
        assertNull(AccountSync.merge(response, t0 + 60, local).trips)
    }

    @Test fun aRemoteDeletionRemovesTheEntryAndItsPhoto() {
        val response = JSONObject().put("items", JSONArray().put(JSONObject().put("kind", "catch").put("id", "a")
            .put("payload", CatchLogStore.toJson(catch("a", "Snapper", 0), includePhoto = false)).put("updated_at", t0 + 99).put("deleted", true)))
        val merged = AccountSync.merge(response, 0, listOf(catch("a", "Snapper", t0, "a.jpg"))).catches.single()
        assertTrue(merged.deleted)
        assertNull(merged.photoFile)
    }
}
