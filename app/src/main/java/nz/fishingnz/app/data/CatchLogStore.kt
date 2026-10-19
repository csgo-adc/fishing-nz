package nz.fishingnz.app.data

import android.content.Context
import android.net.Uri
import nz.fishingnz.app.model.CatchConditions
import nz.fishingnz.app.model.CatchEntry
import nz.fishingnz.app.model.CatchLog
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.MoonPhase
import nz.fishingnz.app.model.PlainConditions
import nz.fishingnz.app.model.TideStation
import nz.fishingnz.app.model.nearestTideStation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The catch log on this phone: `catch_log.json` and photos in `catch_photos/`, both in private app storage. */
object CatchLogStore {
    private const val FILE = "catch_log.json"
    private const val PHOTOS = "catch_photos"
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    @Synchronized fun load(): List<CatchEntry> {
        val file = appContext?.filesDir?.resolve(FILE) ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching { decode(file.readText()) }.getOrDefault(emptyList())
    }

    @Synchronized fun save(entries: List<CatchEntry>) {
        val dir = appContext?.filesDir ?: return
        val temporary = dir.resolve("$FILE.tmp")
        temporary.writeText(encode(entries))
        temporary.renameTo(dir.resolve(FILE))
    }

    /** Copies a photo (such as the one just identified) into the log's own folder and returns its file name. */
    fun keepPhoto(source: Uri, id: String): String? {
        val context = appContext ?: return null
        return runCatching {
            val dir = context.filesDir.resolve(PHOTOS).apply { mkdirs() }
            val name = "$id.jpg"
            context.contentResolver.openInputStream(source)!!.use { input -> dir.resolve(name).outputStream().use { input.copyTo(it) } }
            name
        }.getOrNull()
    }

    fun photo(name: String?): File? = name?.let { appContext?.filesDir?.resolve(PHOTOS)?.resolve(it)?.takeIf(File::exists) }

    fun deletePhoto(name: String?) { photo(name)?.delete() }

    /** Removes every entry and photo from this phone. */
    @Synchronized fun clear() {
        val dir = appContext?.filesDir ?: return
        dir.resolve(FILE).delete()
        dir.resolve(PHOTOS).deleteRecursively()
    }

    internal fun encode(entries: List<CatchEntry>): String = JSONObject().put("version", 1).put("entries", JSONArray().apply {
        entries.forEach { put(toJson(it)) }
    }).toString()

    internal fun decode(text: String): List<CatchEntry> {
        val array = JSONObject(text).optJSONArray("entries") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> runCatching { fromJson(array.getJSONObject(index)) }.getOrNull() }
    }

    /** The JSON for one entry; account sync sends the same shape without the photo. */
    fun toJson(entry: CatchEntry, includePhoto: Boolean = true): JSONObject = JSONObject().apply {
        put("id", entry.id); put("caught_at", entry.caughtAtEpochSeconds); put("species", entry.species)
        entry.lengthCm?.let { put("length_cm", it) }
        put("kept", entry.kept); put("count", entry.count); put("place", entry.placeName)
        entry.latitude?.let { put("latitude", it) }; entry.longitude?.let { put("longitude", it) }
        entry.rulesAreaId?.let { put("rules_area", it) }
        put("notes", entry.notes)
        if (includePhoto) entry.photoFile?.let { put("photo", it) }
        put("conditions", JSONObject().apply {
            entry.conditions.tidePhase?.let { put("tide_phase", it) }; entry.conditions.tide?.let { put("tide", it) }
            entry.conditions.wind?.let { put("wind", it) }; entry.conditions.waves?.let { put("waves", it) }
            entry.conditions.waterTemperature?.let { put("water_temperature", it) }; entry.conditions.moon?.let { put("moon", it) }
            entry.conditions.tideRange?.let { put("tide_range", it) }
        })
        put("updated_at", entry.updatedAtEpochSeconds); put("deleted", entry.deleted)
    }

    fun fromJson(json: JSONObject): CatchEntry {
        fun text(key: String, max: Int) = json.optString(key).takeIf { json.has(key) && !json.isNull(key) }?.trim()?.take(max)
        fun number(key: String) = if (json.has(key) && !json.isNull(key)) json.optDouble(key).takeIf { it.isFinite() } else null
        val conditions = json.optJSONObject("conditions") ?: JSONObject()
        fun condition(key: String) = conditions.optString(key).takeIf { conditions.has(key) && !conditions.isNull(key) && it.isNotBlank() }?.take(200)
        val id = text("id", 64)?.takeIf { it.isNotEmpty() } ?: error("Missing id")
        val species = text("species", 80)?.takeIf { it.isNotEmpty() } ?: error("Missing species")
        return CatchEntry(
            id = id, caughtAtEpochSeconds = json.getLong("caught_at"), species = species,
            lengthCm = number("length_cm")?.takeIf { it > 0 && it <= CatchLog.MAX_LENGTH_CM },
            kept = json.optBoolean("kept"), count = json.optInt("count", 1).coerceIn(1, 99),
            placeName = text("place", 120).orEmpty(),
            latitude = number("latitude")?.takeIf { abs(it) <= 90 }, longitude = number("longitude")?.takeIf { abs(it) <= 180 },
            rulesAreaId = text("rules_area", 40), notes = text("notes", 1000).orEmpty(), photoFile = text("photo", 80),
            conditions = CatchConditions(condition("tide_phase"), condition("tide"), condition("wind"), condition("waves"),
                condition("water_temperature"), condition("moon"), condition("tide_range")),
            updatedAtEpochSeconds = json.optLong("updated_at", json.getLong("caught_at")), deleted = json.optBoolean("deleted")
        )
    }
}

/** Looks up the tide, wind, waves and water temperature for a catch's time and place. Missing parts stay null. */
object CatchConditionsLookup {
    private val zone = ZoneId.of("Pacific/Auckland")

    suspend fun lookup(point: GeoPoint?, at: Instant, station: TideStation? = null): CatchConditions {
        val moon = MoonPhase.forDay(at.atZone(zone).toLocalDate())
        val base = CatchConditions(moon = "${moon.emoji} ${moon.name}", tideRange = moon.tideRange.label)
        if (point == null) return base
        val tide = runCatching {
            val reference = station ?: nearestTideStation(point)
            val day = at.atZone(zone).toLocalDate()
            tideAt(LinzTideSource.predictions(reference, day.minusDays(1), day.plusDays(1)), at, reference.name)
        }.getOrNull()
        val pastDays = Duration.between(at, Instant.now()).toDays().toInt().coerceIn(1, PlaceConditionsRepository.MAX_PAST_DAYS)
        val place = runCatching { PlaceConditionsRepository().load(point, pastDays) }.getOrNull()
        val hour = at.epochSecond.let { Instant.ofEpochSecond(((it / 3600.0).roundToLong()) * 3600) }
        val weather = place?.weather?.hours?.minByOrNull { abs(Duration.between(it.at, hour).seconds) }?.takeIf { abs(Duration.between(it.at, hour).toMinutes()) <= 90 }
        val marine = place?.marine?.hours?.entries?.minByOrNull { abs(Duration.between(it.key, hour).seconds) }?.takeIf { abs(Duration.between(it.key, hour).toMinutes()) <= 90 }?.value
        return base.copy(
            tidePhase = tide?.first, tide = tide?.second,
            wind = weather?.wind?.let { PlainConditions.wind(it, weather.gust, listOf(PlaceConditionRows.direction(weather.direction))) },
            waves = marine?.height?.let { PlainConditions.waves(it, marine.period?.let { p -> String.format(Locale.US, "%.1f", p) }) + " offshore" },
            waterTemperature = marine?.waterTemperature?.let { "${it.roundToInt()}°C" }
        )
    }

    /**
     * The part of the tide at [at], timed from the LINZ highs and lows with the same windows as the fishing plan:
     * around high is 2 hours before to 1 hour after high, around low 1.5 hours either side. Null outside the predictions.
     */
    internal fun tideAt(predictions: List<LinzPrediction>, at: Instant, stationName: String): Pair<String, String>? {
        val before = predictions.lastOrNull { !it.at.isAfter(at) } ?: return null
        val after = predictions.firstOrNull { it.at.isAfter(at) } ?: return null
        val since = Duration.between(before.at, at).toMinutes()
        val until = Duration.between(at, after.at).toMinutes()
        val phase = if (after.high) when {
            until <= 120 -> "Around high"
            since <= 90 -> "Around low"
            else -> "Incoming"
        } else when {
            since <= 60 -> "Around high"
            until <= 90 -> "Around low"
            else -> "Outgoing"
        }
        val rising = if (after.high) "Incoming" else "Outgoing"
        val relative = if (since <= until) "${span(since)} after ${if (before.high) "high" else "low"}"
            else "${span(until)} before ${if (after.high) "high" else "low"}"
        return phase to "$rising · $relative (LINZ $stationName)"
    }

    private fun span(minutes: Long): String = when {
        minutes < 15 -> "just"
        minutes < 60 -> "${minutes} min"
        else -> { val half = (minutes / 30.0).roundToInt() / 2.0; if (half == half.roundToInt().toDouble()) "${half.roundToInt()} h" else "$half h" }
    }
}
