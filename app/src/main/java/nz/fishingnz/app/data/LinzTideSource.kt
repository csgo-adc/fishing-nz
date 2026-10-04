package nz.fishingnz.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import nz.fishingnz.app.model.FishingSpot
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.TideStation
import nz.fishingnz.app.model.tideStations
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Official high/low prediction, in metres above the station's chart datum. */
data class LinzPrediction(val at: Instant, val height: Double, val high: Boolean)

internal data class TideReference<T>(val station: TideStation, val value: T)

internal fun nearbyTideStations(point: GeoPoint, linked: TideStation? = null): List<TideStation> =
    (listOfNotNull(linked) + tideStations.sortedBy { placeDistanceKm(point, GeoPoint(it.latitude, it.longitude)) })
        .distinctBy { it.id }.take(3)

/** Shared by map conditions and fishing windows; manual references do not silently switch. */
internal suspend fun <T> loadTideReference(point: GeoPoint, linked: TideStation? = null, selected: TideStation? = null,
                                          load: suspend (TideStation) -> T): TideReference<T> {
    val candidates = selected?.let(::listOf) ?: nearbyTideStations(point, linked)
    var failure: Exception? = null
    for (station in candidates) {
        currentCoroutineContext().ensureActive()
        try { return TideReference(station, load(station)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error }
    }
    throw failure ?: IllegalStateException("No nearby LINZ tide stations.")
}

internal fun tideReferenceLabel(spot: FishingSpot, station: TideStation): String {
    val distance = placeDistanceKm(GeoPoint(spot.latitude, spot.longitude), GeoPoint(station.latitude, station.longitude))
    return "LINZ ${station.name}" + if (distance >= .1) " · ${String.format(Locale.US, "%.1f", distance)} km away" else ""
}

/** One source for the Tide screen and recommendation explanations. Call from an IO dispatcher. */
object LinzTideSource {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val lifetime = Duration.ofHours(24)
    private data class LocalPrediction(val at: LocalDateTime, val height: Double)
    private data class AnnualTable(val fetchedAt: Instant, val events: List<LocalPrediction>)
    private val tables = ConcurrentHashMap<String, AnnualTable>()

    /** Returns the requested local days and one bracketing event on either side when available. */
    fun predictions(station: TideStation, start: LocalDate, end: LocalDate): List<LinzPrediction> {
        require(!end.isBefore(start)) { "The tide date range is reversed." }
        val events = (start.year..end.year).flatMap { annual(station, it) }.toMutableList()
        val from = start.atStartOfDay()
        val until = end.plusDays(1).atStartOfDay()
        // An unavailable adjacent year's table must not hide valid predictions in the requested year.
        if (start.dayOfYear == 1 && events.none { it.at < from }) {
            runCatching { annual(station, start.year - 1) }.getOrNull()?.let(events::addAll)
        }
        if (end.dayOfYear == end.lengthOfYear() && events.none { it.at >= until }) {
            runCatching { annual(station, end.year + 1) }.getOrNull()?.let(events::addAll)
        }
        return resolveRange(events, start, end, station.name)
    }

    private fun resolveRange(events: List<LocalPrediction>, start: LocalDate, end: LocalDate, stationName: String): List<LinzPrediction> {
        require(!end.isBefore(start)) { "The tide date range is reversed." }
        val from = start.atStartOfDay()
        val until = end.plusDays(1).atStartOfDay()
        val ordered = events.sortedBy { it.at }
        require(ordered.zipWithNext().all { (a, b) -> a.at < b.at }) {
            "LINZ tide tables contain conflicting events for $stationName."
        }
        val inRange = ordered.filter { it.at >= from && it.at < until }
        require(inRange.isNotEmpty()) { "No LINZ tide predictions for $stationName in this date range." }
        return classifyAndResolve(buildList {
            ordered.lastOrNull { it.at < from }?.let(::add)
            addAll(inRange)
            ordered.firstOrNull { it.at >= until }?.let(::add)
        })
    }

    private fun annual(station: TideStation, year: Int): List<LocalPrediction> {
        val key = "${station.id}:${station.csvName}:$year"
        val now = Instant.now()
        val cached = tables[key]
        if (cached != null && Duration.between(cached.fetchedAt, now) in Duration.ZERO..lifetime) return cached.events
        return tables.compute(key) { _, previous ->
            val fetchedAt = Instant.now()
            if (previous != null && Duration.between(previous.fetchedAt, fetchedAt) in Duration.ZERO..lifetime) previous
            else {
                val filename = URLEncoder.encode("${station.csvName} $year.csv", Charsets.UTF_8.name()).replace("+", "%20")
                val connection = (URL("https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/$filename").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                val raw = try {
                    check(connection.responseCode in 200..299) { "LINZ tide table unavailable for ${station.name} in $year." }
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally { connection.disconnect() }
                val events = parseLocal(raw, station, year)
                require(events.map { it.at.toLocalDate() }.distinct().size == LocalDate.of(year, 1, 1).lengthOfYear()) {
                    "LINZ annual tide table is incomplete for ${station.name} in $year."
                }
                AnnualTable(fetchedAt, events)
            }
        }!!.events
    }

    /** Resolve only the requested dates and their bracketing events, never an unrelated repeated clock. */
    internal fun parse(text: String, station: TideStation, year: Int, start: LocalDate, end: LocalDate): List<LinzPrediction> =
        resolveRange(parseLocal(text, station, year), start, end, station.name)

    internal fun parse(text: String, station: TideStation, year: Int): List<LinzPrediction> =
        classifyAndResolve(parseLocal(text, station, year))

    private fun resolve(event: LocalPrediction, high: Boolean): LinzPrediction {
        val offsets = zone.rules.getValidOffsets(event.at)
        require(offsets.size == 1) { "LINZ tide event has an ambiguous or nonexistent local time: ${event.at}." }
        return LinzPrediction(event.at.toInstant(offsets.single()), event.height, high)
    }

    private fun classifyAndResolve(events: List<LocalPrediction>): List<LinzPrediction> {
        require(events.size >= 2) { "LINZ tide range has too few events." }
        return events.mapIndexed { index, event ->
            val high = if (index == 0) event.height > events[1].height else event.height > events[index - 1].height
            val neighbours = listOf(index - 1, index + 1).filter { it in events.indices }.map { events[it].height }
            require(neighbours.all { event.height > it } || neighbours.all { event.height < it }) {
                "LINZ tide range has unclassifiable high and low water."
            }
            resolve(event, high)
        }
    }

    /** Validate the complete table before resolving wall clocks to instants for a requested range. */
    private fun parseLocal(text: String, station: TideStation, year: Int): List<LocalPrediction> {
        val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size >= 4) { "LINZ tide table is empty or incomplete." }
        val identity = lines[0].split(',').map(String::trim)
        require(identity.size >= 4 && identity[0].toIntOrNull() != null && matchesTideHeader(identity[1], station.csvName)) {
            "LINZ tide table station does not match ${station.name}."
        }
        require(lines[1].startsWith("Based on constituent set with reference date:")) { "Unrecognised LINZ tide table header." }
        require(lines[2].split(',').map(String::trim) == listOf("Local Std or Daylight Time", "Tidal heights in metres.")) {
            "LINZ tide table time zone or height units are not recognised."
        }
        val dates = mutableListOf<LocalDate>()
        val events = lines.drop(3).flatMap { line ->
            val fields = line.split(',').map(String::trim)
            require(fields.size >= 6 && fields.size % 2 == 0) { "Malformed LINZ tide row." }
            val date = LocalDate.of(fields[3].toInt(), fields[2].toInt(), fields[0].toInt())
            require(date.year == year) { "LINZ tide table year does not match $year." }
            dates += date
            var foundBlank = false
            val row = (4 until fields.size step 2).mapNotNull { index ->
                val time = fields[index]
                val heightText = fields[index + 1]
                if (time.isEmpty() && heightText.isEmpty()) { foundBlank = true; null }
                else {
                    require(!foundBlank && time.isNotEmpty() && heightText.isNotEmpty()) { "Incomplete LINZ tide event." }
                    val local = LocalDateTime.of(date, LocalTime.parse(time))
                    val height = heightText.toDouble()
                    require(height.isFinite()) { "LINZ tide height is invalid." }
                    LocalPrediction(local, height)
                }
            }
            require(row.isNotEmpty()) { "LINZ tide day has no events." }
            row
        }
        require(dates.zipWithNext().all { (a, b) -> b == a.plusDays(1) }) { "LINZ tide table has duplicate, missing or unordered days." }
        require(events.size >= 2 && events.zipWithNext().all { (a, b) -> a.at < b.at }) {
            "LINZ tide table has duplicate or unordered events."
        }
        // Rounded, equal-height extrema on another day must not hide valid requested tides.
        return events
    }
}

private fun tideIdentity(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .replace('‘', '\'').replace('’', '\'')
    .replace(Regex("\\s*'\\s*"), "'")
    .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

/** Published CSV headers can differ from LINZ's filenames; aliases checked against the 2026 tables. */
private fun matchesTideHeader(header: String, filename: String): Boolean {
    val expected = tideIdentity(filename)
    val actual = tideIdentity(header)
    val aliases = mapOf(
        "halfmoon bay - oban" to "halfmoon bay / oban",
        "kaituna river entrance" to "kaituna river",
        "lottin point - wakatiri" to "lottin point / wakatiri",
        "north cape - otou" to "north cape / otou",
        "rangitaiki river entrance" to "rangitaiki river",
        "town basin" to "town basin - whangarei"
    )
    return actual == expected || actual == aliases[expected]
}

/** Finds an exact station link; loadTideReference supplies automatic nearby references. */
fun recommendationTideStation(spot: FishingSpot, selected: TideStation?): TideStation? {
    if (selected != null) return selected
    val name = tideIdentity(spot.name)
    val explicit = mapOf("raglan" to "raglan", "thames" to "thames", "tauranga" to "tauranga", "kawhia" to "kawhia")
    explicit[name]?.let { id -> return tideStations.firstOrNull { it.id == id } }
    return tideStations.firstOrNull {
        tideIdentity(it.name) == name || tideIdentity(it.csvName) == name ||
            (kotlin.math.abs(it.latitude - spot.latitude) < .00001 && kotlin.math.abs(it.longitude - spot.longitude) < .00001)
    }
}
