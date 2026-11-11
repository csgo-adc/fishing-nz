package nz.fishingnz.app.data

import nz.fishingnz.app.model.ConditionItem
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.LandPriority
import nz.fishingnz.app.model.Recommendation
import nz.fishingnz.app.model.RecommendationSearch
import nz.fishingnz.app.model.ScoreFactor
import nz.fishingnz.app.model.WindowAssessment
import nz.fishingnz.app.model.WindowMood
import nz.fishingnz.app.model.WindowPriority
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The fishing windows found for recent plans, kept on the phone so Home, "See all" and the next launch show them at once
 * instead of searching again. The iPhone app has the same rules in `WindowCache.swift`.
 *
 * - Up to [FRESH_MS] old: shown as they are, with no new search.
 * - Up to [MAX_MS] old: shown at once while a new search runs quietly and replaces them.
 * - Older, or for another plan (place moved more than [MOVE_KM], days, hours, land or boat, distance, preferences), or
 *   from another app build: searched again. Up to [OFFLINE_MS] old they still stand in when that search fails.
 * - Windows that have already ended are left out; a list with none left is searched again.
 * - The last [MAX_ENTRIES] plans are kept, so switching between them is instant.
 */
object WindowCache {
    const val FRESH_MS = 60 * 60 * 1000L
    const val MAX_MS = 6 * 60 * 60 * 1000L
    const val OFFLINE_MS = 24 * 60 * 60 * 1000L
    const val MOVE_KM = 2.0
    const val MAX_ENTRIES = 6
    private const val FILE = "window_cache_v1.json"

    data class Entry(val key: String, val origin: GeoPoint, val savedAtMs: Long, val build: Int, val search: RecommendationSearch)
    enum class Freshness { FRESH, STALE }
    data class Hit(val entry: Entry, val freshness: Freshness)

    @Volatile private var memory: List<Entry>? = null

    /** The saved windows for this plan and place, without the ones that have ended, or null when they need a new search. */
    fun lookup(entries: List<Entry>, key: String, origin: GeoPoint, nowMs: Long, build: Int, maxAgeMs: Long = MAX_MS): Hit? {
        val entry = entries.firstOrNull { it.key == key && it.build == build && distanceKm(it.origin, origin) <= MOVE_KM } ?: return null
        val age = nowMs - entry.savedAtMs
        if (age < 0 || age > maxAgeMs) return null
        val current = withoutEnded(entry, nowMs) ?: return null
        return Hit(current, if (age <= FRESH_MS) Freshness.FRESH else Freshness.STALE)
    }

    /** [entries] with [entry] first, replacing the same plan at about the same place, at most [MAX_ENTRIES]. */
    fun storing(entries: List<Entry>, entry: Entry): List<Entry> =
        (listOf(entry) + entries.filterNot { it.key == entry.key && distanceKm(it.origin, entry.origin) <= MOVE_KM })
            .filter { entry.savedAtMs - it.savedAtMs <= OFFLINE_MS }
            .take(MAX_ENTRIES)

    private fun withoutEnded(entry: Entry, nowMs: Long): Entry? {
        val nowSeconds = nowMs / 1000
        val items = entry.search.items.filter { it.startsAtEpochSeconds <= 0 || it.startsAtEpochSeconds + it.durationHours * 3600L > nowSeconds }
        if (items.isEmpty() && entry.search.items.isNotEmpty()) return null
        return if (items.size == entry.search.items.size) entry else entry.copy(search = entry.search.copy(items = items))
    }

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val radians = Math.PI / 180
        val h = sin((b.latitude - a.latitude) * radians / 2).pow(2) +
            cos(a.latitude * radians) * cos(b.latitude * radians) * sin((b.longitude - a.longitude) * radians / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    // --- On the phone -----------------------------------------------------------------------------------------------

    fun load(): List<Entry> {
        memory?.let { return it }
        val text = OfflineCache.read(FILE)?.first
        val entries = text?.let { runCatching { decodeAll(it) }.getOrNull() } ?: emptyList()
        memory = entries
        return entries
    }

    fun save(entry: Entry) {
        val entries = storing(load(), entry)
        memory = entries
        OfflineCache.write(FILE, encodeAll(entries))
    }

    fun clear() { memory = emptyList(); OfflineCache.delete(FILE) }

    // --- JSON -------------------------------------------------------------------------------------------------------

    internal fun encodeAll(entries: List<Entry>): String = JSONObject()
        .put("v", 1)
        .put("entries", JSONArray().apply { entries.forEach { put(encodeEntry(it)) } })
        .toString()

    internal fun decodeAll(text: String): List<Entry> {
        val json = JSONObject(text)
        if (json.optInt("v") != 1) return emptyList()
        val list = json.optJSONArray("entries") ?: return emptyList()
        return (0 until list.length()).mapNotNull { index -> runCatching { decodeEntry(list.getJSONObject(index)) }.getOrNull() }
    }

    private fun encodeEntry(entry: Entry) = JSONObject()
        .put("key", entry.key).put("lat", entry.origin.latitude).put("lon", entry.origin.longitude)
        .put("saved", entry.savedAtMs).put("build", entry.build)
        .put("items", JSONArray().apply { entry.search.items.forEach { put(encodeWindow(it)) } })
        .put("nearby", entry.search.nearbySpots).put("failed", entry.search.failedSpots)
        .putOpt("nearest", entry.search.nearestSpot).putOpt("nearestKm", entry.search.nearestSpotDistanceKm)
        .put("without", JSONArray(entry.search.spotsWithoutWindows))

    private fun decodeEntry(json: JSONObject) = Entry(
        key = json.getString("key"), origin = GeoPoint(json.getDouble("lat"), json.getDouble("lon")),
        savedAtMs = json.getLong("saved"), build = json.getInt("build"),
        search = RecommendationSearch(
            items = json.getJSONArray("items").let { items -> (0 until items.length()).map { decodeWindow(items.getJSONObject(it)) } },
            nearbySpots = json.getInt("nearby"), failedSpots = json.getInt("failed"),
            nearestSpot = json.optString("nearest").takeIf { json.has("nearest") },
            nearestSpotDistanceKm = if (json.has("nearestKm")) json.getInt("nearestKm") else null,
            spotsWithoutWindows = strings(json.optJSONArray("without")),
        ),
    )

    private fun strings(array: JSONArray?): List<String> = array?.let { list -> (0 until list.length()).map { list.getString(it) } } ?: emptyList()
    /** JSON has no NaN, so a missing number is stored as null. */
    private fun JSONObject.putNumber(name: String, value: Double): JSONObject = put(name, if (value.isFinite()) value else JSONObject.NULL)
    private fun JSONObject.number(name: String, fallback: Double): Double = if (isNull(name)) fallback else optDouble(name, fallback)
    private fun mood(value: WindowMood) = JSONArray().put(value.emoji).put(value.label)
    private fun mood(array: JSONArray) = WindowMood(array.getString(0), array.getString(1))

    private fun encodeWindow(window: Recommendation): JSONObject = JSONObject()
        .put("name", window.name).put("area", window.area).put("rating", window.rating).put("time", window.time)
        .put("distance", window.distance).put("reasons", JSONArray(window.reasons)).put("boat", window.boat)
        .put("factors", JSONArray().apply { window.factors.forEach { put(JSONArray().put(it.name).put(it.score).put(it.weight).put(it.explanation)) } })
        .put("coverage", window.coveragePercent).putOpt("warning", window.warning).put("warnings", JSONArray(window.warnings))
        .putNumber("distanceKm", window.distanceKm).put("start", window.startsAtEpochSeconds).put("hours", window.durationHours)
        .put("summary", window.summary).put("conditions", JSONArray(window.conditions)).put("source", window.sourceNote)
        .putOpt("alternative", window.alternative).putNumber("ranking", window.rankingValue).put("complete", window.dataComplete)
        .putNumber("daylight", window.daylightFraction).putNumber("tideFit", window.tidePreferenceFit)
        .putOpt("assessment", window.assessment?.let(::encodeAssessment))
        .putNumber("lat", window.latitude).putNumber("lon", window.longitude)

    private fun decodeWindow(json: JSONObject) = Recommendation(
        name = json.getString("name"), area = json.getString("area"), rating = json.getInt("rating"), time = json.getString("time"),
        distance = json.getString("distance"), reasons = strings(json.optJSONArray("reasons")), boat = json.getBoolean("boat"),
        factors = json.optJSONArray("factors")?.let { list ->
            (0 until list.length()).map { list.getJSONArray(it).let { f -> ScoreFactor(f.getString(0), f.getInt(1), f.getInt(2), f.getString(3)) } }
        } ?: emptyList(),
        coveragePercent = json.optInt("coverage", 100), warning = json.optString("warning").takeIf { json.has("warning") },
        warnings = strings(json.optJSONArray("warnings")), distanceKm = json.number("distanceKm", Double.NaN),
        startsAtEpochSeconds = json.optLong("start"), durationHours = json.optInt("hours"), summary = json.optString("summary"),
        conditions = strings(json.optJSONArray("conditions")), sourceNote = json.optString("source"),
        alternative = json.optString("alternative").takeIf { json.has("alternative") },
        rankingValue = json.number("ranking", json.getInt("rating").toDouble()), dataComplete = json.optBoolean("complete", true),
        daylightFraction = json.number("daylight", 1.0), tidePreferenceFit = json.number("tideFit", 0.0),
        assessment = json.optJSONObject("assessment")?.let(::decodeAssessment),
        latitude = json.number("lat", Double.NaN), longitude = json.number("lon", Double.NaN),
    )

    private fun encodeAssessment(value: WindowAssessment): JSONObject = JSONObject()
        .put("mood", mood(value.mood))
        .put("conditions", JSONArray().apply { value.conditions.forEach { put(JSONArray().put(it.title).put(it.value).put(mood(it.mood))) } })
        .put("confidence", mood(value.confidence)).put("checks", mood(value.checks)).put("details", JSONArray(value.details))
        .put("band", value.band).putNumber("demanding", value.demandingHours).putNumber("uncomfortable", value.uncomfortableHours)
        .put("matches", value.matchesComfort).put("comfortComplete", value.comfortComplete)
        .putNumber("maxWind", value.maxWind).putNumber("maxGust", value.maxGust).putNumber("rain", value.rainTotal)
        .put("priority", value.priority.name).put("preference", value.preference.name)

    private fun decodeAssessment(json: JSONObject) = WindowAssessment(
        mood = mood(json.getJSONArray("mood")),
        conditions = json.getJSONArray("conditions").let { list ->
            (0 until list.length()).map { list.getJSONArray(it).let { c -> ConditionItem(c.getString(0), c.getString(1), mood(c.getJSONArray(2))) } }
        },
        confidence = mood(json.getJSONArray("confidence")), checks = mood(json.getJSONArray("checks")),
        details = strings(json.optJSONArray("details")), band = json.optInt("band"),
        demandingHours = json.number("demanding", 0.0), uncomfortableHours = json.number("uncomfortable", 0.0),
        matchesComfort = json.optBoolean("matches", true), comfortComplete = json.optBoolean("comfortComplete", true),
        maxWind = json.number("maxWind", 0.0), maxGust = json.number("maxGust", 0.0), rainTotal = json.number("rain", 0.0),
        priority = runCatching { LandPriority.valueOf(json.getString("priority")) }.getOrDefault(LandPriority.BALANCED),
        preference = runCatching { WindowPriority.valueOf(json.getString("preference")) }.getOrDefault(WindowPriority.WEATHER),
    )
}
