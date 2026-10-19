package nz.fishingnz.app.data

import android.content.Context
import nz.fishingnz.app.model.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** What a protected area allows. Wording follows DOC's descriptions of each kind of area. */
enum class ProtectionKind(val id: String, val label: String, val rule: String, val noFishing: Boolean) {
    RESERVE("reserve", "Marine reserve", "No fishing or taking of any marine life.", true),
    HIGH_PROTECTION("high_protection", "High Protection Area", "No recreational or commercial fishing.", true),
    SEAFLOOR_PROTECTION("seafloor_protection", "Seafloor Protection Area",
        "Line fishing is allowed; trawling and dredging are banned, and some areas also restrict nets, pots or longlines.", false)
}

/** One area from `marine_protection.json`. Each polygon is an outer ring then its holes, as [longitude, latitude] pairs. */
class ProtectedArea(val name: String, val kind: ProtectionKind, val bbox: DoubleArray, val polygons: List<List<DoubleArray>>) {
    /** Even-odd over every ring, so islands cut out of an area are outside it. */
    fun contains(point: GeoPoint): Boolean {
        if (point.longitude !in bbox[0]..bbox[2] || point.latitude !in bbox[1]..bbox[3]) return false
        var inside = false
        polygons.forEach { rings -> rings.forEach { ring ->
            var index = 0
            while (index + 3 < ring.size) {
                val x1 = ring[index]; val y1 = ring[index + 1]; val x2 = ring[index + 2]; val y2 = ring[index + 3]
                if ((y1 > point.latitude) != (y2 > point.latitude) &&
                    point.longitude < (x2 - x1) * (point.latitude - y1) / (y2 - y1) + x1) inside = !inside
                index += 2
            }
        } }
        return inside
    }

    /** Kilometres from [point] to the nearest boundary, on a local flat projection (fine within a few kilometres). */
    fun distanceKm(point: GeoPoint): Double {
        val kx = 111.32 * cos(Math.toRadians(point.latitude))
        val ky = 110.57
        var best = Double.MAX_VALUE
        polygons.forEach { rings -> rings.forEach { ring ->
            var index = 0
            while (index + 3 < ring.size) {
                val ax = (ring[index] - point.longitude) * kx; val ay = (ring[index + 1] - point.latitude) * ky
                val bx = (ring[index + 2] - point.longitude) * kx; val by = (ring[index + 3] - point.latitude) * ky
                val dx = bx - ax; val dy = by - ay
                val length = dx * dx + dy * dy
                val t = if (length == 0.0) 0.0 else (-(ax * dx + ay * dy) / length).coerceIn(0.0, 1.0)
                best = minOf(best, hypot(ax + t * dx, ay + t * dy))
                index += 2
            }
        } }
        return best
    }

    /** True when [point] is within roughly [km] of the area's bounding box, a cheap test before measuring. */
    fun near(point: GeoPoint, km: Double): Boolean {
        val dLat = km / 110.57
        val dLon = km / (111.32 * cos(Math.toRadians(point.latitude)))
        return point.longitude in bbox[0] - dLon..bbox[2] + dLon && point.latitude in bbox[1] - dLat..bbox[3] + dLat
    }
}

/** A protected area at or near a place. [distanceKm] is 0 inside. */
data class ProtectionNotice(val area: ProtectedArea, val inside: Boolean, val distanceKm: Double) {
    val emoji: String get() = if (area.kind.noFishing) "⛔" else "⚠️"
    val text: String get() = (if (inside) "Inside ${area.name}. " else "${MarineProtection.distanceLabel(distanceKm)} from ${area.name}. ") + area.kind.rule
}

/**
 * DOC marine reserves and Hauraki Gulf protection areas, bundled from `tools/build_marine_protection.py`. The iPhone
 * app has the same rules in `MarineProtection.swift`. Boundaries are simplified and can be out of date, so the apps
 * always point to DOC and MPI for official boundaries, temporary closures and rāhui.
 */
object MarineProtection {
    /** Within this distance of a no-fishing area, a place gets a notice. */
    const val NO_FISHING_NOTICE_KM = 2.0
    /** Seafloor Protection Areas allow line fishing, so only a place in or right beside one gets a notice. */
    const val SEAFLOOR_NOTICE_KM = 0.5
    const val DOC_URL = "https://www.doc.govt.nz/marinereserves"
    const val HAURAKI_GULF_URL = "https://www.doc.govt.nz/haurakigulfmarinepark"
    @Volatile private var loaded: Catalogue? = null

    class Catalogue(val retrievedOn: String, val attribution: String, val areas: List<ProtectedArea>, val geoJson: String)

    /** Reads the bundled boundaries once; call off the main thread. */
    fun load(context: Context): Catalogue = loaded ?: synchronized(this) {
        loaded ?: parse(context.assets.open("marine_protection.json").bufferedReader(Charsets.UTF_8).use { it.readText() }).also { loaded = it }
    }

    fun loadedOrNull(): Catalogue? = loaded

    internal fun parse(text: String): Catalogue {
        val json = JSONObject(text)
        require(json.getInt("schema_version") == 1) { "Unknown marine protection data." }
        val features = JSONArray()
        val source = json.getJSONArray("areas")
        val areas = (0 until source.length()).mapNotNull { index ->
            val item = source.getJSONObject(index)
            val kind = ProtectionKind.entries.firstOrNull { it.id == item.getString("kind") } ?: return@mapNotNull null
            val box = item.getJSONArray("bbox").let { DoubleArray(4) { i -> it.getDouble(i) } }
            val polygonsJson = item.getJSONArray("polygons")
            val polygons = (0 until polygonsJson.length()).map { p ->
                val rings = polygonsJson.getJSONArray(p)
                (0 until rings.length()).map { r ->
                    val ring = rings.getJSONArray(r)
                    DoubleArray(ring.length() * 2) { i -> ring.getJSONArray(i / 2).getDouble(i % 2) }
                }
            }
            features.put(JSONObject().put("type", "Feature")
                .put("properties", JSONObject().put("name", item.getString("name")).put("kind", kind.id))
                .put("geometry", JSONObject().put("type", "MultiPolygon").put("coordinates", polygonsJson)))
            ProtectedArea(item.getString("name"), kind, box, polygons)
        }
        val geoJson = JSONObject().put("type", "FeatureCollection").put("features", features).toString()
        return Catalogue(json.getString("retrieved_on"), json.getString("attribution"), areas, geoJson)
    }

    /** Areas a place is inside, then nearby ones, nearest first: at most [limit]. */
    fun notices(point: GeoPoint, areas: List<ProtectedArea>, limit: Int = 2): List<ProtectionNotice> =
        areas.mapNotNull { area ->
            val reach = if (area.kind.noFishing) NO_FISHING_NOTICE_KM else SEAFLOOR_NOTICE_KM
            when {
                !area.near(point, reach) -> null
                area.contains(point) -> ProtectionNotice(area, true, 0.0)
                else -> area.distanceKm(point).takeIf { it <= reach }?.let { ProtectionNotice(area, false, it) }
            }
        }.sortedWith(compareBy({ !it.inside }, { !it.area.kind.noFishing }, { it.distanceKm })).take(limit)

    /** "Under 50 m", "200 m" or "1.4 km". */
    fun distanceLabel(km: Double): String = when {
        km < 0.05 -> "Under 50 m"
        km < 1.0 -> "${((km * 1000) / 10).roundToInt() * 10} m"
        else -> String.format(Locale.US, "%.1f km", km)
    }

    /** "DOC boundaries, simplified · retrieved 19 Oct 2026". */
    fun sourceLine(catalogue: Catalogue): String {
        val date = runCatching { java.time.LocalDate.parse(catalogue.retrievedOn).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)) }
            .getOrDefault(catalogue.retrievedOn)
        return "Approximate DOC boundaries retrieved $date; they may be out of date and are not for navigation. " +
            "Temporary closures and rāhui are not shown: check DOC and MPI before you fish."
    }
}
