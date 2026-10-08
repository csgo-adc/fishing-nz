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

/** Whether our own usage statistics may be collected, and whether the person still has to be told about them. */
data class AnalyticsConsentState(val needsNotice: Boolean, val enabled: Boolean)

/**
 * Our own anonymous usage statistics are on by default, but only after the one-time notice has been answered.
 * Nothing is collected before that, and an earlier "off" always stays off.
 */
object AnalyticsConsent {
    /**
     * One-time upgrade from the old single analytics switch. A choice already stored for the new setting wins; otherwise an
     * explicit earlier choice on the old switch carries over (on or off); otherwise the person has not decided yet.
     */
    fun migratedChoice(usageStats: Boolean?, oldSwitch: Boolean?): Boolean? = usageStats ?: oldSwitch

    /** [choice] is null until the person has answered the notice or used the switch. */
    fun resolve(choice: Boolean?): AnalyticsConsentState =
        if (choice == null) AnalyticsConsentState(needsNotice = true, enabled = false) else AnalyticsConsentState(needsNotice = false, enabled = choice)
}
