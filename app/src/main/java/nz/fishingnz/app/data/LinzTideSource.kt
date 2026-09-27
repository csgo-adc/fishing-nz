package nz.fishingnz.app.data

import nz.fishingnz.app.model.FishingSpot
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

/** One source for the Tide screen and recommendation explanations. Call from an IO dispatcher. */
object LinzTideSource {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val lifetime = Duration.ofHours(24)
    private data class AnnualTable(val fetchedAt: Instant, val raw: String, val events: List<LinzPrediction>)
    private val tables = ConcurrentHashMap<String, AnnualTable>()

    /** Returns the requested local days and one bracketing event on either side when available. */
    fun predictions(station: TideStation, start: LocalDate, end: LocalDate): List<LinzPrediction> {
        require(!end.isBefore(start)) { "The tide date range is reversed." }
        val events = (start.year..end.year).flatMap { annual(station, it) }.toMutableList()
        val from = start.atStartOfDay(zone).toInstant()
        val until = end.plusDays(1).atStartOfDay(zone).toInstant()
        // An unavailable adjacent year's table must not hide valid predictions in the requested year.
        if (start.dayOfYear == 1 && events.none { it.at < from }) {
            runCatching { annual(station, start.year - 1) }.getOrNull()?.let(events::addAll)
        }
        if (end.dayOfYear == end.lengthOfYear() && events.none { it.at >= until }) {
            runCatching { annual(station, end.year + 1) }.getOrNull()?.let(events::addAll)
        }
        val ordered = events.sortedBy { it.at }
        require(ordered.zipWithNext().all { (a, b) -> a.at < b.at && a.high != b.high }) {
            "LINZ tide tables contain conflicting events for ${station.name}."
        }
        val inRange = ordered.filter { it.at >= from && it.at < until }
        require(inRange.isNotEmpty()) { "No LINZ tide predictions for ${station.name} in this date range." }
        return buildList {
            ordered.lastOrNull { it.at < from }?.let(::add)
            addAll(inRange)
            ordered.firstOrNull { it.at >= until }?.let(::add)
        }
    }

    private fun annual(station: TideStation, year: Int): List<LinzPrediction> {
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
                val events = parse(raw, station, year)
                require(events.map { it.at.atZone(zone).toLocalDate() }.distinct().size == LocalDate.of(year, 1, 1).lengthOfYear()) {
                    "LINZ annual tide table is incomplete for ${station.name} in $year."
                }
                AnnualTable(fetchedAt, raw, events)
            }
        }!!.events
    }

    /** Fails closed on a wrong station, wrong units, malformed data or an ambiguous local clock. */
    internal fun parse(text: String, station: TideStation, year: Int): List<LinzPrediction> {
        val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.size >= 4) { "LINZ tide table is empty or incomplete." }
        val identity = lines[0].split(',').map(String::trim)
        require(identity.size >= 4 && identity[0].toIntOrNull() != null && tideIdentity(identity[1]) == tideIdentity(station.csvName)) {
            "LINZ tide table station does not match ${station.name}."
        }
        require(lines[1].startsWith("Based on constituent set with reference date:")) { "Unrecognised LINZ tide table header." }
        require(lines[2].split(',').map(String::trim) == listOf("Local Std or Daylight Time", "Tidal heights in metres.")) {
            "LINZ tide table time zone or height units are not recognised."
        }
        data class Event(val at: Instant, val height: Double)
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
                    val offsets = zone.rules.getValidOffsets(local)
                    require(offsets.size == 1) { "LINZ tide event has an ambiguous or nonexistent local time." }
                    val height = heightText.toDouble()
                    require(height.isFinite()) { "LINZ tide height is invalid." }
                    Event(local.toInstant(offsets.single()), height)
                }
            }
            require(row.isNotEmpty()) { "LINZ tide day has no events." }
            row
        }
        require(dates.zipWithNext().all { (a, b) -> b == a.plusDays(1) }) { "LINZ tide table has duplicate, missing or unordered days." }
        require(events.size >= 2 && events.zipWithNext().all { (a, b) -> a.at < b.at && a.height != b.height }) {
            "LINZ tide table has duplicate, unordered or unclassifiable events."
        }
        return events.mapIndexed { index, event ->
            val high = if (index == 0) event.height > events[1].height else event.height > events[index - 1].height
            if (index < events.lastIndex) require(high == (event.height > events[index + 1].height)) {
                "LINZ tide table does not alternate between high and low water."
            }
            LinzPrediction(event.at, event.height, high)
        }
    }
}

private fun tideIdentity(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .replace('‘', '\'').replace('’', '\'')
    .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

/** Only selected or explicitly identified stations; geographic proximity cannot establish tidal compatibility. */
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
