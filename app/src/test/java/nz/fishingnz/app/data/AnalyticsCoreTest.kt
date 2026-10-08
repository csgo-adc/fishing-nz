package nz.fishingnz.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsCoreTest {
    private val device = AnalyticsDevice(
        id = "00000000-0000-4000-8000-000000000001", platform = "android", osVersion = "14", deviceModel = "Pixel 8",
        appVersion = "1.0.6", locale = "en-NZ", timeZone = "Pacific/Auckland",
    )

    private fun event(name: String, at: Long, vararg props: Pair<String, Any>) = AnalyticsEvent(name, props.toMap(), at)

    @Test
    fun payloadCarriesDeviceDetailsAndHowLongAgoEachEventHappened() {
        val body = AnalyticsPayload.build(
            device,
            listOf(event("app_open", 10_000), event("search_run", 7_500, "mode" to "boat", "radius_km" to 80, "is_fish" to true)),
            nowMillis = 12_000,
        )
        val sent = body.getJSONObject("device")
        assertEquals(device.id, sent.getString("id"))
        assertEquals("android", sent.getString("platform"))
        assertEquals("Pixel 8", sent.getString("device_model"))
        assertEquals("14", sent.getString("os_version"))
        assertEquals("1.0.6", sent.getString("app_version"))
        assertEquals("en-NZ", sent.getString("locale"))
        assertEquals("Pacific/Auckland", sent.getString("time_zone"))

        val events = body.getJSONArray("events")
        assertEquals(2, events.length())
        assertEquals("app_open", events.getJSONObject(0).getString("name"))
        assertEquals(2_000L, events.getJSONObject(0).getLong("offset_ms"))
        val search: JSONObject = events.getJSONObject(1)
        assertEquals(4_500L, search.getLong("offset_ms"))
        assertEquals("boat", search.getJSONObject("props").getString("mode"))
        assertEquals(80, search.getJSONObject("props").getInt("radius_km"))
        assertTrue(search.getJSONObject("props").getBoolean("is_fish"))
    }

    @Test
    fun anEventFromTheFutureNeverGetsANegativeOffset() {
        val body = AnalyticsPayload.build(device, listOf(event("app_open", 20_000)), nowMillis = 12_000)
        assertEquals(0L, body.getJSONArray("events").getJSONObject(0).getLong("offset_ms"))
    }

    @Test
    fun bufferHandsOutOldestEventsFirstInBatches() {
        val buffer = AnalyticsBuffer()
        (1..5).forEach { buffer.add(event("e$it", it.toLong())) }
        assertEquals(listOf("e1", "e2"), buffer.take(2).map { it.name })
        assertEquals(3, buffer.size())
        assertEquals(listOf("e3", "e4", "e5"), buffer.take(10).map { it.name })
        assertEquals(0, buffer.size())
        assertTrue(buffer.take(5).isEmpty())
    }

    @Test
    fun bufferDropsTheOldestEventsWhenFull() {
        val buffer = AnalyticsBuffer(capacity = 3)
        (1..5).forEach { buffer.add(event("e$it", it.toLong())) }
        assertEquals(listOf("e3", "e4", "e5"), buffer.take(10).map { it.name })
    }

    @Test
    fun aFailedUploadPutsEventsBackInOrderAtTheFront() {
        val buffer = AnalyticsBuffer(capacity = 10)
        (1..4).forEach { buffer.add(event("e$it", it.toLong())) }
        val failed = buffer.take(2)
        buffer.add(event("e5", 5))
        buffer.putBack(failed)
        assertEquals(listOf("e1", "e2", "e3", "e4", "e5"), buffer.take(10).map { it.name })
    }

    @Test
    fun puttingEventsBackNeverExceedsCapacity() {
        val buffer = AnalyticsBuffer(capacity = 3)
        (1..3).forEach { buffer.add(event("e$it", it.toLong())) }
        val failed = buffer.take(2)
        (4..6).forEach { buffer.add(event("e$it", it.toLong())) }
        buffer.putBack(failed)
        assertEquals(3, buffer.size())
        buffer.clear()
        assertEquals(0, buffer.size())
    }

    @Test
    fun confidenceLevelsMatchTheServer() {
        assertEquals("high", AnalyticsPayload.confidenceLevel(80))
        assertEquals("medium", AnalyticsPayload.confidenceLevel(79))
        assertEquals("medium", AnalyticsPayload.confidenceLevel(50))
        assertEquals("low", AnalyticsPayload.confidenceLevel(49))
    }
}
