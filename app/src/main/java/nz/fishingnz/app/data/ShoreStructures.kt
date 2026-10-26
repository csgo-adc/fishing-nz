package nz.fishingnz.app.data

import android.content.Context
import nz.fishingnz.app.model.GeoPoint
import org.json.JSONObject

/** One wharf, breakwater or groyne from OpenStreetMap. [kind] is "wharf", "breakwater" or "groyne"; [name] is null for unnamed ones. */
data class ShoreStructure(val point: GeoPoint, val name: String?, val kind: String) {
    val kindLabel: String get() = when (kind) { "breakwater" -> "breakwater"; "groyne" -> "groyne"; else -> "wharf" }
    val label: String get() = name ?: when (kind) { "breakwater" -> "Breakwater"; "groyne" -> "Groyne"; else -> "Wharf or jetty" }
    /** The map card's subtitle, such as "Breakwater · access and fishing rules not checked". */
    val mapSubtitle: String get() = kindLabel.replaceFirstChar { it.uppercase() } + " · access and fishing rules not checked"
}

/** A structure and how far it is from a place. */
data class NearbyStructure(val structure: ShoreStructure, val distanceKm: Double)

/**
 * Wharves, breakwaters and groynes bundled from `tools/build_shore_structures.py` (© OpenStreetMap contributors, ODbL).
 * The iPhone app has the same rules in `ShoreStructures.swift`. A mapped wharf isn't permission to fish, so the apps say so.
 */
object ShoreStructures {
    const val CREDIT_URL = "https://www.openstreetmap.org/copyright"
    const val CAUTION = "A wharf on the map doesn't mean you can fish there: ports, ferry wharves and some council wharves ban it. Check the signs."
    @Volatile private var loaded: List<ShoreStructure>? = null

    /** Reads the bundled structures once; call off the main thread. */
    fun load(context: Context): List<ShoreStructure> = loaded ?: synchronized(this) {
        loaded ?: parse(context.assets.open("shore_structures.json").bufferedReader(Charsets.UTF_8).use { it.readText() }).also { loaded = it }
    }

    fun loadedOrNull(): List<ShoreStructure>? = loaded

    internal fun parse(text: String): List<ShoreStructure> {
        val json = JSONObject(text)
        require(json.getInt("schema_version") == 1) { "Unknown shore structure data." }
        val structures = json.getJSONArray("structures")
        return (0 until structures.length()).map { index ->
            val item = structures.getJSONObject(index)
            ShoreStructure(GeoPoint(item.getDouble("lat"), item.getDouble("lon")),
                item.optString("name").takeIf { item.has("name") && !item.isNull("name") && it.isNotBlank() },
                item.getString("kind"))
        }
    }

    /** Up to [limit] structures within [withinKm] of [point], nearest first. */
    fun nearest(point: GeoPoint, structures: List<ShoreStructure>, limit: Int = 3, withinKm: Double = 10.0): List<NearbyStructure> =
        structures.asSequence()
            .filter { kotlin.math.abs(it.point.latitude - point.latitude) < withinKm / 100 && kotlin.math.abs(it.point.longitude - point.longitude) < withinKm / 70 }
            .map { NearbyStructure(it, placeDistanceKm(point, it.point)) }
            .filter { it.distanceKm <= withinKm }
            .sortedBy { it.distanceKm }
            .take(limit).toList()
}
