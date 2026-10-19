package nz.fishingnz.app.data

import android.content.Context
import nz.fishingnz.app.model.CatchEntry
import nz.fishingnz.app.model.Recommendation
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Whether this device syncs trips and the catch log with the account, and when they last changed or synced. */
object SyncStore {
    private const val PREFS = "account_sync"
    @Volatile private var appContext: Context? = null
    fun initialize(context: Context) { appContext = context.applicationContext }
    private val prefs get() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs?.getBoolean("enabled", false) ?: false
    fun setEnabled(value: Boolean) { prefs?.edit()?.putBoolean("enabled", value)?.apply() }
    /** When the saved windows, active trip or back-by time last changed on this device, in epoch seconds. */
    fun tripsUpdatedAt(): Long = prefs?.getLong("trips_updated_at", 0L) ?: 0L
    fun setTripsUpdatedAt(value: Long) { prefs?.edit()?.putLong("trips_updated_at", value)?.apply() }
    fun lastSyncedAt(): Instant? = prefs?.getLong("last_synced_at", 0L)?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
    fun setLastSyncedAt(value: Instant?) { prefs?.edit()?.putLong("last_synced_at", value?.epochSecond ?: 0L)?.apply() }
}

/**
 * The items a device sends to `/v1/me/sync` and how the reply is merged back. Trips travel as one document per account;
 * catches one by one, without photos. The newest `updated_at` wins on the server and here. The iPhone app has the same
 * rules in `AccountSync.swift`.
 */
object AccountSync {
    data class Trips(val saved: List<Recommendation>, val active: Recommendation?, val backBy: Instant?)
    data class Merged(val trips: Trips?, val tripsUpdatedAt: Long, val catches: List<CatchEntry>)

    fun tripsDocument(trips: Trips): JSONObject = JSONObject()
        .put("saved", JSONArray().apply { trips.saved.mapNotNull { SharedWindowLink.encode(it) }.forEach { put(it) } })
        .apply { trips.active?.let(SharedWindowLink::encode)?.let { put("active", it) } }
        .apply { trips.backBy?.let { put("back_by", it.epochSecond) } }

    fun trips(document: JSONObject): Trips {
        val saved = document.optJSONArray("saved") ?: JSONArray()
        return Trips((0 until saved.length()).mapNotNull { TripStore.restore(saved.optString(it)) },
            document.optString("active").takeIf { it.isNotEmpty() }?.let(TripStore::restore),
            document.optLong("back_by", 0L).takeIf { it > 0 }?.let(Instant::ofEpochSecond))
    }

    fun items(trips: Trips, tripsUpdatedAt: Long, catches: List<CatchEntry>): JSONArray = JSONArray().apply {
        if (tripsUpdatedAt > 0) put(JSONObject().put("kind", "trips").put("id", "trips").put("payload", tripsDocument(trips))
            .put("updated_at", tripsUpdatedAt).put("deleted", false))
        catches.forEach { entry ->
            put(JSONObject().put("kind", "catch").put("id", entry.id).put("payload", CatchLogStore.toJson(entry, includePhoto = false))
                .put("updated_at", entry.updatedAtEpochSeconds).put("deleted", entry.deleted))
        }
    }

    /** Takes each server item that is newer than this device's copy. Photos stay with the local entry. */
    fun merge(response: JSONObject, tripsUpdatedAt: Long, catches: List<CatchEntry>): Merged {
        val items = response.optJSONArray("items") ?: JSONArray()
        var trips: Trips? = null
        var tripsTime = tripsUpdatedAt
        val byId = catches.associateBy { it.id }.toMutableMap()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val updatedAt = item.optLong("updated_at", 0L)
            val payload = item.optJSONObject("payload") ?: continue
            when (item.optString("kind")) {
                "trips" -> if (updatedAt > tripsTime) { trips = runCatching { trips(payload) }.getOrNull() ?: continue; tripsTime = updatedAt }
                "catch" -> {
                    val local = byId[item.optString("id")]
                    if (local != null && local.updatedAtEpochSeconds >= updatedAt) continue
                    val remote = runCatching { CatchLogStore.fromJson(payload) }.getOrNull() ?: continue
                    if (remote.id != item.optString("id")) continue
                    val deleted = item.optBoolean("deleted")
                    if (deleted) local?.photoFile?.let(CatchLogStore::deletePhoto)
                    byId[remote.id] = remote.copy(updatedAtEpochSeconds = updatedAt, deleted = deleted,
                        photoFile = if (deleted) null else local?.photoFile)
                }
            }
        }
        return Merged(trips, tripsTime, byId.values.toList())
    }
}
