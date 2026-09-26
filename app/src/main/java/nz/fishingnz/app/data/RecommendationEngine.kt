package nz.fishingnz.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nz.fishingnz.app.model.FishingSpot
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.PreferredTimeRange
import nz.fishingnz.app.model.Recommendation
import nz.fishingnz.app.model.RecommendationSearch
import nz.fishingnz.app.model.ScoreFactor
import nz.fishingnz.app.model.TideStation
import nz.fishingnz.app.model.fishingSpots
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Ranks the best two- or three-hour window at each nearby named fishing area. */
class RecommendationEngine {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val timeFormat = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.US)
    private val endFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    suspend fun search(origin: GeoPoint, radiusKm: Int, start: LocalDate, end: LocalDate, boat: Boolean, preferredTime: PreferredTimeRange?, specificStation: TideStation? = null): RecommendationSearch = coroutineScope {
        val today = LocalDate.now(zone)
        require(!start.isBefore(today) && !end.isBefore(start) && !end.isAfter(today.plusDays(15))) {
            "Choose dates within the next 16 days."
        }
        val now = Instant.now()
        val mayCrossMidnight = preferredTime == null || preferredTime.end.isBefore(preferredTime.start)
        val forecastDays = min(16, ChronoUnit.DAYS.between(today, end).toInt() + 1 + if (mayCrossMidnight) 1 else 0)
        val candidates = if (specificStation != null) {
            listOf(FishingSpot(specificStation.name, specificStation.region, specificStation.latitude, specificStation.longitude, boat) to 0.0)
        } else {
            fishingSpots.asSequence()
                .filter { it.boat == boat }
                .map { it to distanceKm(origin, GeoPoint(it.latitude, it.longitude)) }
                .toList()
        }
        val nearby = if (specificStation != null) candidates else candidates.filter { it.second <= radiusKm }
        if (nearby.isEmpty()) {
            val closest = candidates.minByOrNull { it.second }
            return@coroutineScope RecommendationSearch(
                emptyList(), 0, 0, closest?.first?.name, closest?.second?.roundToInt()
            )
        }

        val limiter = Semaphore(4)
        val outcomes = nearby.map { (spot, distance) ->
            async(Dispatchers.IO) {
                limiter.withPermit {
                    try { Result.success(bestWindow(spot, distance, start, end, forecastDays, now, preferredTime, mayCrossMidnight)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure<SpotOutcome>(error) }
                }
            }
        }.awaitAll()
        val succeeded = outcomes.mapNotNull { it.getOrNull() }
        if (succeeded.isEmpty()) error("Weather forecasts are unavailable for nearby spots. Please try again later.")
        if (succeeded.none { it.usableWeather }) error("No usable hourly weather forecast was available for the selected days.")
        RecommendationSearch(
            succeeded.mapNotNull { it.window }
                .map { window ->
                    if (specificStation == null) window else window.copy(
                        distance = "Selected location",
                        warnings = window.warnings + "Confirm fishing access and local rules at this location"
                    )
                }
                .sortedWith(compareByDescending<Recommendation> { it.rating }.thenBy { it.distanceKm }.thenBy { it.name }),
            nearby.size,
            outcomes.count { it.isFailure }
        )
    }

    private fun bestWindow(spot: FishingSpot, distance: Double, start: LocalDate, end: LocalDate, forecastDays: Int, now: Instant,
                           preferredTime: PreferredTimeRange?, mayCrossMidnight: Boolean): SpotOutcome {
        val weather = weather(spot, forecastDays)
        val marine = try { marine(spot, forecastDays) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptyMap() }
        val hours = weather.hours.filter { hour ->
            val day = hour.time.atZone(zone).toLocalDate()
            day >= start && day <= end.plusDays(if (mayCrossMidnight) 1 else 0) && !hour.time.isBefore(now)
        }
        var best: Recommendation? = null
        for (index in hours.indices) {
            for (length in listOf(3, 2)) {
                if (index + length > hours.size) continue
                val samples = hours.subList(index, index + length)
                if (samples.zipWithNext().any { (a, b) -> Duration.between(a.time, b.time).seconds != 3_600L }) continue
                val firstDay = samples.first().time.atZone(zone).toLocalDate()
                if (firstDay !in start..end) continue
                val windowEnd = samples.first().time.plus(length.toLong(), ChronoUnit.HOURS)
                if (!withinPreferredTime(samples.first().time, windowEnd, firstDay, preferredTime)) continue
                val solar = weather.solar[firstDay] ?: continue
                val candidate = scoreWindow(spot, distance, samples, windowEnd, solar, marine, now) ?: continue
                if (best == null || candidate.rating > best.rating ||
                    (candidate.rating == best.rating && candidate.durationHours > best.durationHours) ||
                    (candidate.rating == best.rating && candidate.durationHours == best.durationHours && candidate.startsAtEpochSeconds < best.startsAtEpochSeconds)) {
                    best = candidate
                }
            }
        }
        return SpotOutcome(best, hours.isNotEmpty())
    }

    private fun withinPreferredTime(windowStart: Instant, windowEnd: Instant, startDay: LocalDate,
                                    preferredTime: PreferredTimeRange?): Boolean {
        if (preferredTime == null) return true
        val allowedStart = startDay.atTime(preferredTime.start).atZone(zone).toInstant()
        if (!preferredTime.end.isBefore(preferredTime.start)) {
            val allowedEnd = startDay.atTime(preferredTime.end).atZone(zone).toInstant()
            return !windowStart.isBefore(allowedStart) && !windowEnd.isAfter(allowedEnd)
        }
        val overnightEnd = startDay.plusDays(1).atTime(preferredTime.end).atZone(zone).toInstant()
        val earlyEnd = startDay.atTime(preferredTime.end).atZone(zone).toInstant()
        return (!windowStart.isBefore(allowedStart) && !windowEnd.isAfter(overnightEnd)) ||
            (windowStart.isBefore(earlyEnd) && !windowEnd.isAfter(earlyEnd))
    }

    private fun scoreWindow(
        spot: FishingSpot,
        distance: Double,
        hours: List<WeatherHour>,
        end: Instant,
        solar: SolarDay,
        marine: Map<Instant, MarineHour>,
        now: Instant
    ): Recommendation? {
        val maxWind = hours.maxOf { it.wind }
        val maxGust = hours.maxOf { it.gust }
        if (hours.any { it.weatherCode in 95..99 }) return null
        if (spot.boat && (maxWind >= 40 || maxGust >= 55)) return null
        if (!spot.boat && (maxWind >= 55 || maxGust >= 70)) return null

        val marineSamples = hours.map { marine[it.time] }
        val waves = marineSamples.mapNotNull { it?.waveHeight }
        val worstWave = waves.takeIf { it.size == hours.size }?.maxOrNull()
        if (spot.boat && worstWave != null && worstWave >= 2) return null
        if (!spot.boat && worstWave != null && worstWave >= 3) return null
        val periods = marineSamples.mapNotNull { it?.wavePeriod }
        val longestPeriod = periods.takeIf { it.size == hours.size }?.maxOrNull()
        val levels = marineSamples.mapNotNull { it?.seaLevel }
        val tide = if (spot.boat) null else levels.takeIf { it.size == hours.size }?.let { tideScore(it, hours.first().time, marine) }
        val wave = worstWave?.let { waveScore(it, longestPeriod, spot.boat) }

        val meanWind = hours.map { it.wind }.average()
        val meanRain = hours.map { it.precipitation }.average()
        val highestRainProbability = hours.maxOf { it.rainProbability }
        val wind = .65 * lowerIsBetter(maxWind, best = if (spot.boat) 10.0 else 12.0, worst = if (spot.boat) 40.0 else 55.0) +
            .35 * lowerIsBetter(maxGust, best = if (spot.boat) 18.0 else 20.0, worst = if (spot.boat) 55.0 else 70.0)
        val weather = .45 * (1 - clamp(highestRainProbability / 100)) +
            .35 * lowerIsBetter(meanRain, best = 0.0, worst = 2.5) +
            .20 * hours.map { weatherCodeScore(it.weatherCode) }.average()
        val midpoint = hours.first().time.plusMillis(Duration.between(hours.first().time, end).toMillis() / 2)
        val sunDistanceHours = min(abs(Duration.between(midpoint, solar.sunrise).seconds), abs(Duration.between(midpoint, solar.sunset).seconds)) / 3_600.0
        val daylight = !midpoint.isBefore(solar.sunrise) && !midpoint.isAfter(solar.sunset)
        val partiallyDark = hours.first().time.isBefore(solar.sunrise) || end.isAfter(solar.sunset)
        val timeScore = when {
            daylight && sunDistanceHours <= 1.5 -> 1.0
            daylight && sunDistanceHours <= 3 -> .8
            daylight -> if (spot.boat) .62 else .68
            sunDistanceHours <= 1 -> if (spot.boat) .65 else .75
            else -> if (spot.boat) .2 else .15
        }
        // Radius only filters candidates; changing it cannot change an existing spot's score.
        val distanceScore = clamp(1 - distance / 500)

        val tideWeight = if (spot.boat) 0 else 25
        val windWeight = if (spot.boat) 30 else 20
        val weatherWeight = if (spot.boat) 15 else 20
        val waveWeight = if (spot.boat) 35 else 10
        val timeWeight = if (spot.boat) 10 else 15
        val distanceWeight = 10
        var weighted = windWeight * wind + weatherWeight * weather + timeWeight * timeScore + distanceWeight * distanceScore
        var availableWeight = windWeight + weatherWeight + timeWeight + distanceWeight
        if (tide != null) { weighted += tideWeight * tide.value; availableWeight += tideWeight }
        if (wave != null) { weighted += waveWeight * wave; availableWeight += waveWeight }
        var score = (100 * weighted / availableWeight).roundToInt()
        val missingMarine = wave == null || (!spot.boat && tide == null)
        if (missingMarine) score = min(score, 79)
        val longRange = Duration.between(now, hours.first().time).seconds > 7 * 86_400L
        if (longRange) score = min(score, 89)

        val factors = buildList {
            if (tide != null) add(ScoreFactor("Tide", (100 * tide.value).roundToInt(), tideWeight,
                "${if (tide.incoming) "Rising" else "Falling"} tide, ${"%+.2f".format(Locale.US, tide.meanChange)} m/hour"))
            add(ScoreFactor("Wind", (100 * wind).roundToInt(), windWeight,
                "${meanWind.roundToInt()} km/h mean, ${maxWind.roundToInt()} km/h maximum, ${maxGust.roundToInt()} km/h gusts"))
            add(ScoreFactor("Weather", (100 * weather).roundToInt(), weatherWeight,
                "${highestRainProbability.roundToInt()}% maximum rain chance, ${"%.1f".format(Locale.US, meanRain)} mm/hour mean rain"))
            if (wave != null) add(ScoreFactor("Wave", (100 * wave).roundToInt(), waveWeight,
                "${"%.1f".format(Locale.US, worstWave)} m maximum wave height${longestPeriod?.let { ", ${it.roundToInt()} s period" } ?: ""}"))
            add(ScoreFactor("Daylight", (100 * timeScore).roundToInt(), timeWeight,
                if (!daylight && sunDistanceHours <= 1) "Twilight; check visibility" else if (!daylight) "After dark"
                else if (sunDistanceHours <= 3) "Near sunrise or sunset" else "Daylight window"))
            add(ScoreFactor("Distance", (100 * distanceScore).roundToInt(), distanceWeight,
                "${distance.roundToInt()} km straight line from search origin"))
        }

        val reasons = buildList {
            if (tide != null && tide.value >= .65) add(if (tide.incoming) "Moving incoming tide" else "Tide movement")
            if (meanWind <= (if (spot.boat) 16 else 20) && maxGust <= (if (spot.boat) 30 else 40)) add("Light wind around ${meanWind.roundToInt()} km/h")
            if (meanRain < .2 && highestRainProbability <= 30) add("Low rain chance")
            if (worstWave != null && worstWave < (if (spot.boat) .75 else 1.0)) add("Waves around ${"%.1f".format(Locale.US, worstWave)} m or less")
            if (daylight && sunDistanceHours <= 2) add("Near sunrise or sunset")
            if (isEmpty()) add("Best available ${hours.size}-hour forecast window")
        }
        val warnings = buildList {
            if (!spot.boat && tide == null) add("Tide forecast unavailable for this window")
            if (wave == null) add("Wave forecast unavailable for this window")
            if (partiallyDark) add("Part of this window is after dark; check access, lighting and navigation")
            if (maxGust >= (if (spot.boat) 40 else 55)) add("Strong gusts forecast")
            if (worstWave != null && worstWave >= (if (spot.boat) 1.2 else 1.5)) add("Elevated waves; check local exposure")
            if (worstWave != null && longestPeriod != null && worstWave >= (if (spot.boat) 1.0 else .8) && longestPeriod >= (if (spot.boat) 10 else 12))
                add("Long-period swell may increase surf and surge")
            if (highestRainProbability >= 60 || meanRain >= 1.5) add("Rain likely during this window")
            if (hours.any { it.weatherCode == 45 || it.weatherCode == 48 }) add("Fog may reduce visibility")
            if (longRange) add("Long-range forecast; check again closer to the day")
            if (tide != null) add("Coastal tide model is approximate; check local tide tables")
        }
        return Recommendation(
            name = spot.name,
            area = spot.area,
            rating = score.coerceIn(0, 100),
            time = formatWindow(hours.first().time, end),
            distance = "${distance.roundToInt()} km straight line",
            reasons = reasons,
            boat = spot.boat,
            factors = factors,
            coveragePercent = availableWeight,
            warning = if (missingMarine) "Partial marine forecast: ${if (spot.boat) "wave" else "tide or wave"} data missing. Score capped at 79." else null,
            warnings = warnings,
            distanceKm = distance,
            startsAtEpochSeconds = hours.first().time.epochSecond,
            durationHours = hours.size
        )
    }

    private fun formatWindow(start: Instant, end: Instant): String {
        val localStart = start.atZone(zone)
        val localEnd = end.atZone(zone)
        val endFormatter = if (localEnd.toLocalDate() == localStart.toLocalDate()) endFormat else timeFormat
        return "${localStart.format(timeFormat)}–${localEnd.format(endFormatter)}"
    }

    private fun tideScore(levels: List<Double>, start: Instant, marine: Map<Instant, MarineHour>): TideScore? {
        if (levels.size < 2 || levels.any { !it.isFinite() }) return null
        val differences = levels.zipWithNext { previous, current -> current - previous }
        val meanChange = differences.average()
        val meanMovement = differences.map { abs(it) }.average()
        val nearby = marine.entries.mapNotNull { (time, value) ->
            if (abs(Duration.between(start, time).seconds) <= 6 * 3_600L) value.seaLevel else null
        }
        val localRange = (nearby.maxOrNull() ?: 0.0) - (nearby.minOrNull() ?: 0.0)
        val movement = clamp(meanMovement / max(localRange / 5, .05))
        val incoming = meanChange > .01
        val value = .2 + .65 * movement + (if (incoming) .15 else 0.0)
        return TideScore(clamp(value), incoming, meanChange)
    }

    private fun waveScore(height: Double, period: Double?, boat: Boolean): Double {
        val heightScore = lowerIsBetter(height, best = if (boat) .5 else .7, worst = if (boat) 2.0 else 2.5)
        if (period == null || height < (if (boat) 1.0 else .8) || period < (if (boat) 10.0 else 12.0)) return heightScore
        return heightScore * .75
    }

    private fun weatherCodeScore(code: Int): Double = when (code) {
        in 0..3 -> 1.0
        45, 48 -> .35
        in 51..57 -> .65
        61, 63, 80, 81 -> .4
        65, 66, 67, 82 -> .1
        in 71..77, 85, 86 -> .15
        else -> .5
    }

    private fun lowerIsBetter(value: Double, best: Double, worst: Double): Double = clamp((worst - value) / (worst - best))
    private fun clamp(value: Double): Double = value.coerceIn(0.0, 1.0)

    private fun weather(spot: FishingSpot, forecastDays: Int): WeatherForecast {
        val json = getJson("https://api.open-meteo.com/v1/forecast?latitude=${spot.latitude}&longitude=${spot.longitude}" +
            "&hourly=wind_speed_10m,wind_gusts_10m,precipitation,precipitation_probability,weather_code" +
            "&daily=sunrise,sunset&cell_selection=${if (spot.boat) "sea" else "land"}" +
            "&forecast_days=$forecastDays&timeformat=unixtime&timezone=Pacific%2FAuckland")
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val wind = hourly.getJSONArray("wind_speed_10m")
        val gust = hourly.getJSONArray("wind_gusts_10m")
        val precipitation = hourly.getJSONArray("precipitation")
        val rainProbability = hourly.getJSONArray("precipitation_probability")
        val code = hourly.getJSONArray("weather_code")
        val valid = (0 until times.length()).mapNotNull { i ->
            val speed = wind.number(i) ?: return@mapNotNull null
            val gustSpeed = gust.number(i) ?: return@mapNotNull null
            val rain = precipitation.number(i) ?: return@mapNotNull null
            val probability = rainProbability.number(i) ?: return@mapNotNull null
            val weatherCode = code.number(i)?.roundToInt() ?: return@mapNotNull null
            if (speed < 0 || gustSpeed < 0 || rain < 0) return@mapNotNull null
            WeatherHour(Instant.ofEpochSecond(times.getLong(i)), speed, gustSpeed, rain, probability, weatherCode)
        }
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val sunrise = daily.getJSONArray("sunrise")
        val sunset = daily.getJSONArray("sunset")
        val solar = (0 until dates.length()).mapNotNull { i ->
            val rise = sunrise.number(i)?.toLong() ?: return@mapNotNull null
            val set = sunset.number(i)?.toLong() ?: return@mapNotNull null
            Instant.ofEpochSecond(dates.getLong(i)).atZone(zone).toLocalDate() to SolarDay(Instant.ofEpochSecond(rise), Instant.ofEpochSecond(set))
        }.toMap()
        return WeatherForecast(valid, solar)
    }

    private fun marine(spot: FishingSpot, forecastDays: Int): Map<Instant, MarineHour> {
        val json = getJson("https://marine-api.open-meteo.com/v1/marine?latitude=${spot.latitude}&longitude=${spot.longitude}" +
            "&hourly=sea_level_height_msl,wave_height,wave_period&cell_selection=sea" +
            "&forecast_days=$forecastDays&timeformat=unixtime&timezone=Pacific%2FAuckland")
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val levels = hourly.optJSONArray("sea_level_height_msl")
        val waves = hourly.optJSONArray("wave_height")
        val periods = hourly.optJSONArray("wave_period")
        return (0 until times.length()).associate { i ->
            Instant.ofEpochSecond(times.getLong(i)) to MarineHour(levels?.number(i), waves?.number(i), periods?.number(i))
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) error("Forecast request failed ($status).")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun JSONArray.number(index: Int): Double? = if (index >= length() || isNull(index)) null
        else optDouble(index, Double.NaN).takeIf { it.isFinite() }

    private fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val latitudeDifference = Math.toRadians(b.latitude - a.latitude)
        val longitudeDifference = Math.toRadians(b.longitude - a.longitude)
        val haversine = sin(latitudeDifference / 2).pow(2.0) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(longitudeDifference / 2).pow(2.0)
        return 6_371.0 * 2 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    private data class WeatherHour(val time: Instant, val wind: Double, val gust: Double, val precipitation: Double, val rainProbability: Double, val weatherCode: Int)
    private data class WeatherForecast(val hours: List<WeatherHour>, val solar: Map<LocalDate, SolarDay>)
    private data class SolarDay(val sunrise: Instant, val sunset: Instant)
    private data class MarineHour(val seaLevel: Double?, val waveHeight: Double?, val wavePeriod: Double?)
    private data class TideScore(val value: Double, val incoming: Boolean, val meanChange: Double)
    private data class SpotOutcome(val window: Recommendation?, val usableWeather: Boolean)
}
