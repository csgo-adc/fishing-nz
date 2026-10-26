package nz.fishingnz.app.data

import android.content.Context
import nz.fishingnz.app.model.GeoPoint
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One public boat ramp from OpenStreetMap. [name] is null for the many unnamed ramps. */
data class BoatRamp(val point: GeoPoint, val name: String?, val fee: Boolean?) {
    val label: String get() = name ?: "Boat ramp"
}

/** A ramp and how far it is from a place. */
data class NearbyRamp(val ramp: BoatRamp, val distanceKm: Double)

/**
 * Boat ramps bundled from `tools/build_boat_ramps.py` (© OpenStreetMap contributors, ODbL). The iPhone app has the same
 * rules in `BoatRamps.swift`. Access, condition and tide limits are not known, so the apps say so wherever ramps show.
 */
object BoatRamps {
    const val CREDIT_URL = "https://www.openstreetmap.org/copyright"
    const val CAUTION = "Public access, condition and tide limits aren't checked."
    @Volatile private var loaded: List<BoatRamp>? = null

    /** Reads the bundled ramps once; call off the main thread. */
    fun load(context: Context): List<BoatRamp> = loaded ?: synchronized(this) {
        loaded ?: parse(context.assets.open("boat_ramps.json").bufferedReader(Charsets.UTF_8).use { it.readText() }).also { loaded = it }
    }

    fun loadedOrNull(): List<BoatRamp>? = loaded

    internal fun parse(text: String): List<BoatRamp> {
        val json = JSONObject(text)
        require(json.getInt("schema_version") == 1) { "Unknown boat ramp data." }
        val ramps = json.getJSONArray("ramps")
        return (0 until ramps.length()).map { index ->
            val item = ramps.getJSONObject(index)
            BoatRamp(GeoPoint(item.getDouble("lat"), item.getDouble("lon")),
                item.optString("name").takeIf { item.has("name") && !item.isNull("name") && it.isNotBlank() },
                if (item.isNull("fee")) null else item.optString("fee") == "yes")
        }
    }

    /** Up to [limit] ramps within [withinKm] of [point], nearest first. */
    fun nearest(point: GeoPoint, ramps: List<BoatRamp>, limit: Int = 3, withinKm: Double = 25.0): List<NearbyRamp> =
        ramps.asSequence()
            .filter { kotlin.math.abs(it.point.latitude - point.latitude) < withinKm / 100 && kotlin.math.abs(it.point.longitude - point.longitude) < withinKm / 70 }
            .map { NearbyRamp(it, placeDistanceKm(point, it.point)) }
            .filter { it.distanceKm <= withinKm }
            .sortedBy { it.distanceKm }
            .take(limit).toList()

    /** "Low tide 1:05 PM, during your trip: …" when a low tide falls within an hour of the trip, else null. */
    fun lowTideNote(lows: List<Instant>, start: Instant, end: Instant): String? {
        val low = lows.firstOrNull { !it.isBefore(start.minusSeconds(3600)) && !it.isAfter(end.plusSeconds(3600)) } ?: return null
        val time = low.atZone(ZoneId.of("Pacific/Auckland")).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        val position = when {
            low.isBefore(start) -> "just before your trip"
            low.isAfter(end) -> "just after your trip"
            else -> "during your trip"
        }
        return "Low tide $time, $position. Some ramps can't be used near low tide: check the ramp's limits."
    }

    /** The 8-point compass direction from [from] to [to], such as "SW". */
    fun direction(from: GeoPoint, to: GeoPoint): String {
        val east = (to.longitude - from.longitude) * kotlin.math.cos(Math.toRadians(from.latitude))
        val north = to.latitude - from.latitude
        val degrees = (Math.toDegrees(kotlin.math.atan2(east, north)) + 360) % 360
        return listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((degrees + 22.5) / 45).toInt() % 8]
    }

    /** "450 m" or "2.1 km". */
    fun distanceLabel(km: Double): String = if (km < 1) "${((km * 1000) / 10).toInt() * 10} m" else String.format(Locale.US, "%.1f km", km)
}
