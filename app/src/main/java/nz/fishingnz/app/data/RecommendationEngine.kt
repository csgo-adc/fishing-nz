package nz.fishingnz.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nz.fishingnz.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.*

/** Compares complete two-hour sessions. Explanations use the same facts as selection. */
class RecommendationEngine {
    private val zone = WindowEvaluation.zone

    suspend fun search(
        origin: GeoPoint, radiusKm: Int, start: LocalDate, end: LocalDate, boat: Boolean,
        preferredTime: PreferredTimeRange?, specificStation: TideStation? = null,
        priority: WindowPriority = WindowPriority.WEATHER
    ): RecommendationSearch = coroutineScope {
        val today = LocalDate.now(zone)
        require(!start.isBefore(today) && !end.isBefore(start) && !end.isAfter(today.plusDays(15))) {
            "Choose dates within the next 16 days."
        }
        val now = Instant.now()
        val mayCrossMidnight = preferredTime == null || preferredTime.end.isBefore(preferredTime.start)
        val forecastDays = min(16, ChronoUnit.DAYS.between(today, end).toInt() + 1 + if (mayCrossMidnight) 1 else 0)
        val candidates = if (specificStation != null) {
            listOf(FishingSpot(specificStation.name, specificStation.region, specificStation.latitude, specificStation.longitude, boat) to 0.0)
        } else fishingSpots.filter { it.boat == boat }.map { it to distanceKm(origin, GeoPoint(it.latitude, it.longitude)) }
        val nearby = if (specificStation != null) candidates else candidates.filter { it.second <= radiusKm }
        if (nearby.isEmpty()) {
            val closest = candidates.minByOrNull { it.second }
            return@coroutineScope RecommendationSearch(emptyList(), 0, 0, closest?.first?.name, closest?.second?.roundToInt())
        }
        val limiter = Semaphore(4)
        val outcomes = nearby.map { (spot, distance) ->
            async(Dispatchers.IO) {
                limiter.withPermit {
                    try { Result.success(bestWindows(spot, distance, start, end, forecastDays, now, preferredTime, mayCrossMidnight, specificStation, priority)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure<SpotOutcome>(error) }
                }
            }
        }.awaitAll()
        val succeeded = outcomes.mapNotNull { it.getOrNull() }
        if (succeeded.isEmpty()) error("Weather forecasts are unavailable for nearby spots. Please try again later.")
        if (succeeded.none { it.usableWeather }) error("No usable hourly weather forecast was available for the selected days.")
        RecommendationSearch(
            succeeded.flatMap { it.windows }.map {
                if (specificStation == null) it else it.copy(distance = "Selected tide location · access not verified")
            }.let {
                if (specificStation != null) it.sortedBy { item -> item.startsAtEpochSeconds }
                else it.sortedWith(windowOrder.thenBy { item -> item.distanceKm }.thenBy { item -> item.name })
            }, nearby.size, outcomes.count { it.isFailure }
        )
    }

    private fun bestWindows(
        spot: FishingSpot, distance: Double, start: LocalDate, end: LocalDate, forecastDays: Int,
        now: Instant, preferredTime: PreferredTimeRange?, mayCrossMidnight: Boolean,
        selectedStation: TideStation?, priority: WindowPriority
    ): SpotOutcome {
        val weather = weather(spot, forecastDays)
        val marine = try { marine(spot, forecastDays) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val station = recommendationTideStation(spot, selectedStation)
        val tides = try { station?.let { LinzTideSource.predictions(it, start, end.plusDays(if (mayCrossMidnight) 1 else 0)) }.orEmpty() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyList() }
        val retrieval = DateTimeFormatter.ofPattern("d MMM, h:mm a z", Locale.US)
        val source = "Weather: Open-Meteo automatic best match, retrieved ${weather.retrievedAt.atZone(zone).format(retrieval)}; forecast grid ${weather.grid}. " +
            (marine?.let { "Offshore waves: Open-Meteo automatic best match, retrieved ${it.retrievedAt.atZone(zone).format(retrieval)}; grid ${it.grid}. " } ?: "Offshore wave data unavailable. ") +
            (station?.takeIf { tides.isNotEmpty() }?.let { "Tides: LINZ ${it.name}, published NZ local times. " } ?: "No verified local LINZ tide data. ") +
            "Retrieval times are not model issue times. These are planning conditions, not a prediction of catches."
        val hours = weather.hours.filter {
            val day = it.time.atZone(zone).toLocalDate()
            day >= start && day <= end.plusDays(1) && it.time >= now
        }
        val evaluated = buildList {
            for (index in 0 until (hours.size - 2).coerceAtLeast(0)) {
                val samples = hours.subList(index, index + 3)
                val startDay = samples.first().time.atZone(zone).toLocalDate()
                if (startDay !in start..end) continue
                if (!withinPreferredTime(samples.first().time, samples.last().time, startDay, preferredTime)) continue
                WindowEvaluation.evaluate(spot, distance, samples, marine?.hours.orEmpty(), weather.solar,
                    tides, station, now, source, priority)?.let { add(it) }
            }
        }
        val selection = WindowSelection(selectedStation != null)
        evaluated.filter { priority != WindowPriority.LATE_INCOMING || it.tidePreferenceFit >= .8 }.forEach {
            selection.consider(Instant.ofEpochSecond(it.startsAtEpochSeconds).atZone(zone).toLocalDate(), it)
        }
        val windows = selection.windows().map { selected ->
            val day = Instant.ofEpochSecond(selected.startsAtEpochSeconds).atZone(zone).toLocalDate()
            val alternative = evaluated.filter {
                Instant.ofEpochSecond(it.startsAtEpochSeconds).atZone(zone).toLocalDate() == day &&
                    (priority == WindowPriority.LATE_INCOMING || it.tidePreferenceFit >= .8)
            }.minWithOrNull(windowOrder)?.takeIf { it.startsAtEpochSeconds != selected.startsAtEpochSeconds }
            selected.copy(alternative = alternative?.let {
                val title = if (priority == WindowPriority.WEATHER) "If you prefer late incoming tide"
                    else if (spot.boat) "For an alternative focused on wave comfort" else "For a weather-focused alternative"
                val details = if (spot.boat) "${it.conditions[3]} ${it.conditions[1]}" else "${it.conditions[1]} ${it.conditions[2]}"
                "$title: ${it.time}. $details Compare its tide timing and conditions before changing your plan."
            })
        }
        return SpotOutcome(windows, hours.any { it.wind != null })
    }

    private fun withinPreferredTime(windowStart: Instant, windowEnd: Instant, startDay: LocalDate, preferredTime: PreferredTimeRange?): Boolean {
        if (preferredTime == null) return true
        val allowedStart = startDay.atTime(preferredTime.start).atZone(zone).toInstant()
        if (!preferredTime.end.isBefore(preferredTime.start)) {
            val allowedEnd = startDay.atTime(preferredTime.end).atZone(zone).toInstant()
            return windowStart >= allowedStart && windowEnd <= allowedEnd
        }
        val overnightEnd = startDay.plusDays(1).atTime(preferredTime.end).atZone(zone).toInstant()
        val earlyEnd = startDay.atTime(preferredTime.end).atZone(zone).toInstant()
        return (windowStart >= allowedStart && windowEnd <= overnightEnd) || (windowStart < earlyEnd && windowEnd <= earlyEnd)
    }

    private fun weather(spot: FishingSpot, forecastDays: Int): WeatherForecast {
        val json = getJson("https://api.open-meteo.com/v1/forecast?latitude=${spot.latitude}&longitude=${spot.longitude}" +
            "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,precipitation_probability,weather_code" +
            "&daily=sunrise,sunset&wind_speed_unit=kmh&precipitation_unit=mm&cell_selection=${if (spot.boat) "sea" else "land"}" +
            "&forecast_days=$forecastDays&timeformat=unixtime&timezone=Pacific%2FAuckland")
        return decodeWeather(json, Instant.now())
    }

    internal fun decodeWeather(json: JSONObject, retrievedAt: Instant): WeatherForecast {
        checkUnits(json.getJSONObject("hourly_units"), mapOf("time" to "unixtime", "wind_speed_10m" to "km/h", "wind_gusts_10m" to "km/h", "precipitation" to "mm", "precipitation_probability" to "%", "weather_code" to "wmo code", "wind_direction_10m" to "°"))
        val hourly = json.getJSONObject("hourly")
        val times = checkedTimes(hourly)
        checkLengths(hourly, times.size)
        val wind = hourly.optJSONArray("wind_speed_10m")
        val gust = hourly.optJSONArray("wind_gusts_10m")
        val rain = hourly.optJSONArray("precipitation")
        val probabilities = hourly.optJSONArray("precipitation_probability")
        val codes = hourly.optJSONArray("weather_code")
        val direction = hourly.optJSONArray("wind_direction_10m")
        val hours = times.mapIndexed { i, at ->
            ForecastHour(at, wind?.number(i)?.takeIf { it >= 0 }, gust?.number(i)?.takeIf { it >= 0 },
                rain?.number(i)?.takeIf { it >= 0 }, probabilities?.number(i)?.takeIf { it in 0.0..100.0 },
                codes?.number(i)?.takeIf { it in 0.0..99.0 && it % 1.0 == 0.0 }?.toInt(),
                direction?.number(i)?.takeIf { it in 0.0..360.0 })
        }
        val daily = json.getJSONObject("daily")
        checkUnits(json.getJSONObject("daily_units"), mapOf("time" to "unixtime", "sunrise" to "unixtime", "sunset" to "unixtime"))
        val dates = checkedTimes(daily)
        checkLengths(daily, dates.size)
        val sunrise = daily.optJSONArray("sunrise")
        val sunset = daily.optJSONArray("sunset")
        val solar = dates.mapIndexedNotNull { i, _ ->
            val rise = sunrise?.number(i)?.toLong()?.let(Instant::ofEpochSecond) ?: return@mapIndexedNotNull null
            val set = sunset?.number(i)?.toLong()?.let(Instant::ofEpochSecond) ?: return@mapIndexedNotNull null
            if (set <= rise) return@mapIndexedNotNull null
            rise.atZone(zone).toLocalDate() to DaylightPeriod(rise, set)
        }.toMap()
        return WeatherForecast(hours, solar, retrievedAt, gridDescription(json))
    }

    private fun marine(spot: FishingSpot, forecastDays: Int): MarineForecast {
        val json = getJson("https://marine-api.open-meteo.com/v1/marine?latitude=${spot.latitude}&longitude=${spot.longitude}" +
            "&hourly=wave_height,wave_period,wave_direction&cell_selection=sea&length_unit=metric" +
            "&forecast_days=$forecastDays&timeformat=unixtime&timezone=Pacific%2FAuckland")
        return decodeMarine(json, Instant.now())
    }

    internal fun decodeMarine(json: JSONObject, retrievedAt: Instant): MarineForecast {
        checkUnits(json.getJSONObject("hourly_units"), mapOf("time" to "unixtime", "wave_height" to "m", "wave_period" to "s", "wave_direction" to "°"))
        val hourly = json.getJSONObject("hourly")
        val times = checkedTimes(hourly)
        checkLengths(hourly, times.size)
        val waves = hourly.optJSONArray("wave_height")
        val periods = hourly.optJSONArray("wave_period")
        val direction = hourly.optJSONArray("wave_direction")
        return MarineForecast(times.mapIndexed { i, at ->
            at to MarineSample(waves?.number(i)?.takeIf { it >= 0 }, periods?.number(i)?.takeIf { it > 0 }, direction?.number(i)?.takeIf { it in 0.0..360.0 })
        }.toMap(), retrievedAt, gridDescription(json))
    }

    private fun checkUnits(units: JSONObject, expected: Map<String, String>) {
        expected.forEach { (field, unit) -> require(units.optString(field) == unit) { "Unexpected forecast unit for $field." } }
    }
    private fun checkedTimes(group: JSONObject): List<Instant> {
        val array = group.getJSONArray("time")
        val times = (0 until array.length()).map { i ->
            val value = array.number(i) ?: error("Invalid forecast timestamp.")
            require(value % 1.0 == 0.0) { "Invalid forecast timestamp." }
            Instant.ofEpochSecond(value.toLong())
        }
        require(times.zipWithNext().all { (a, b) -> b > a }) { "Forecast timestamps are duplicated or unordered." }
        return times
    }
    private fun checkLengths(group: JSONObject, count: Int) {
        group.keys().forEach { key -> group.optJSONArray(key)?.let { require(it.length() == count) { "Misaligned forecast values for $key." } } }
    }
    private fun gridDescription(json: JSONObject): String {
        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Forecast grid location is missing." }
        return "%.4f, %.4f".format(Locale.US, latitude, longitude)
    }
    private fun JSONArray.number(index: Int): Double? = if (index >= length() || isNull(index)) null else optDouble(index, Double.NaN).takeIf { it.isFinite() }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8_000; readTimeout = 15_000 }
        try {
            require(connection.responseCode in 200..299) { "Forecast request failed (${connection.responseCode})." }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val arc = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2.0)
        return 6_371.0 * 2 * asin(sqrt(arc.coerceIn(0.0, 1.0)))
    }
    internal data class WeatherForecast(val hours: List<ForecastHour>, val solar: Map<LocalDate, DaylightPeriod>, val retrievedAt: Instant, val grid: String)
    internal data class MarineForecast(val hours: Map<Instant, MarineSample>, val retrievedAt: Instant, val grid: String)
    private data class SpotOutcome(val windows: List<Recommendation>, val usableWeather: Boolean)
}
