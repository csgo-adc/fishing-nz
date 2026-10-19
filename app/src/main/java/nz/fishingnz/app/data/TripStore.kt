package nz.fishingnz.app.data

import android.content.Context
import nz.fishingnz.app.model.Recommendation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved windows and the active trip, kept across launches. Each window is stored as its share-link token, the same
 * small snapshot both apps and the web page read, so account sync can carry it between Android and iPhone too.
 */
object TripStore {
    private const val PREFS = "trips"
    private const val KEY = "trips_v1"
    private const val BACK_BY = "back_by"
    const val SAVED_NOTE = "Saved: these conditions were captured when you saved this window and may have changed. Search from Home for a live forecast."
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    /** The saved windows (oldest first) and the active trip. */
    fun load(): Pair<List<Recommendation>, Recommendation?> {
        val text = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null) ?: return emptyList<Recommendation>() to null
        return runCatching { decode(text) }.getOrDefault(emptyList<Recommendation>() to null)
    }

    fun save(saved: List<Recommendation>, active: Recommendation?) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, encode(saved, active))?.apply()
    }

    /** The back-by time of the active trip's shared plan, if one was shared. */
    fun backBy(): java.time.Instant? = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        ?.getLong(BACK_BY, 0L)?.takeIf { it > 0 }?.let(java.time.Instant::ofEpochSecond)

    fun saveBackBy(at: java.time.Instant?) {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.apply { if (at == null) remove(BACK_BY) else putLong(BACK_BY, at.epochSecond) }?.apply()
    }

    internal fun encode(saved: List<Recommendation>, active: Recommendation?): String = JSONObject()
        .put("saved", JSONArray().apply { saved.mapNotNull { SharedWindowLink.encode(it) }.forEach { put(it) } })
        .apply { active?.let(SharedWindowLink::encode)?.let { put("active", it) } }
        .toString()

    internal fun decode(text: String): Pair<List<Recommendation>, Recommendation?> {
        val json = JSONObject(text)
        val saved = json.optJSONArray("saved") ?: JSONArray()
        return (0 until saved.length()).mapNotNull { restore(saved.optString(it)) } to json.optString("active").takeIf { it.isNotEmpty() }?.let(::restore)
    }

    /** A window from its token, labelled as saved rather than shared. */
    fun restore(token: String): Recommendation? = SharedWindowLink.decode(token)?.let { window ->
        window.copy(distance = "Saved · forecast may have changed", assessment = window.assessment?.copy(details = listOf(SAVED_NOTE)))
    }
}
