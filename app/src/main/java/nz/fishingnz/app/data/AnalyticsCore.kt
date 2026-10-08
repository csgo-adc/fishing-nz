package nz.fishingnz.app.data

import org.json.JSONArray
import org.json.JSONObject

/** One behaviour event waiting to be uploaded. */
data class AnalyticsEvent(val name: String, val props: Map<String, Any>, val atMillis: Long)

/** The random per-install id plus coarse device details. Nothing here identifies a person or a hardware unit. */
data class AnalyticsDevice(
    val id: String,
    val platform: String,
    val osVersion: String,
    val deviceModel: String,
    val appVersion: String,
    val locale: String,
    val timeZone: String,
)

/** A small thread-safe queue that stays bounded while the network is unavailable. */
class AnalyticsBuffer(private val capacity: Int = 200) {
    private val lock = Any()
    private val items = ArrayDeque<AnalyticsEvent>()

    fun size(): Int = synchronized(lock) { items.size }

    fun add(event: AnalyticsEvent) = synchronized(lock) {
        if (items.size >= capacity) items.removeFirst()
        items.addLast(event)
    }

    /** Remove and return up to [max] of the oldest events. */
    fun take(max: Int): List<AnalyticsEvent> = synchronized(lock) {
        val count = minOf(max, items.size)
        List(count) { items.removeFirst() }
    }

    /** Return events to the front after a failed upload, dropping the oldest if the queue is now over capacity. */
    fun putBack(events: List<AnalyticsEvent>) = synchronized(lock) {
        for (event in events.asReversed()) items.addFirst(event)
        while (items.size > capacity) items.removeFirst()
    }

    fun clear() = synchronized(lock) { items.clear() }
}

object AnalyticsPayload {
    const val MAX_EVENTS_PER_UPLOAD = 50

    /** Body for POST /v1/analytics/batch. Each event carries how long ago it happened, not the phone's clock time. */
    fun build(device: AnalyticsDevice, events: List<AnalyticsEvent>, nowMillis: Long): JSONObject {
        val list = JSONArray()
        for (event in events) {
            val props = JSONObject()
            for ((key, value) in event.props) props.put(key, value)
            list.put(JSONObject().put("name", event.name).put("offset_ms", (nowMillis - event.atMillis).coerceAtLeast(0L)).put("props", props))
        }
        val deviceJson = JSONObject()
            .put("id", device.id).put("platform", device.platform).put("os_version", device.osVersion)
            .put("device_model", device.deviceModel).put("app_version", device.appVersion)
            .put("locale", device.locale).put("time_zone", device.timeZone)
        return JSONObject().put("device", deviceJson).put("events", list)
    }

    fun confidenceLevel(percent: Int): String = if (percent >= 80) "high" else if (percent >= 50) "medium" else "low"
}
