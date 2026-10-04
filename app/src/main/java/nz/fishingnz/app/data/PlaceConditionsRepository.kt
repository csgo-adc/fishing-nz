package nz.fishingnz.app.data

import kotlinx.coroutines.*
import nz.fishingnz.app.model.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import java.util.Locale
import kotlin.math.roundToInt

data class ConditionPlace(val name: String, val point: GeoPoint, val region: String = "Selected place",
                          val boat: Boolean = false, val station: TideStation? = null) {
    val id get() = "$name:${point.latitude}:${point.longitude}"
    val initialTideStation get() = station ?: nearestTideStation(point)
}
data class PlaceWeatherHour(val at: Instant, val temperature: Double?, val feelsLike: Double?,
    val wind: Double?, val gust: Double?, val direction: Double?, val rain: Double?, val chance: Double?,
    val code: Int?, val isDay: Boolean?, val visibility: Double?)
data class PlaceWeatherDay(val date: LocalDate, val code: Int?, val high: Double?, val low: Double?,
    val feelsHigh: Double?, val feelsLow: Double?, val wind: Double?, val gust: Double?, val direction: Double?,
    val rain: Double?, val chance: Double?, val sunrise: Instant?, val sunset: Instant?, val uv: Double?)
data class PlaceMarineHour(val height: Double?, val period: Double?, val direction: Double?,
    val swell: Double?, val swellPeriod: Double?, val waterTemperature: Double?)
data class PlaceWeather(val hours: List<PlaceWeatherHour>, val days: List<PlaceWeatherDay>, val zone: ZoneId,
                        val fetchedAt: Instant, val grid: String)
data class PlaceMarine(val hours: Map<Instant, PlaceMarineHour>, val zone: ZoneId, val fetchedAt: Instant, val grid: String, val gridPoint: GeoPoint? = null)
data class PlaceConditions(val weather: PlaceWeather?, val marine: PlaceMarine?, val weatherIssue: String?, val marineIssue: String?) {
    val zone get() = weather?.zone ?: marine?.zone ?: ZoneId.of("Pacific/Auckland")
    val dates get() = (weather?.days?.map { it.date }.orEmpty() + marine?.hours?.keys?.map { it.atZone(zone).toLocalDate() }.orEmpty()).distinct().sorted()
    fun marineHours(date: LocalDate) = marine?.hours?.filterKeys { it.atZone(zone).toLocalDate() == date }?.values.orEmpty().toList()
    fun weatherHours(date: LocalDate) = weather?.hours?.filter { it.at.atZone(zone).toLocalDate() == date }.orEmpty()
    fun hourDates(date: LocalDate) = (weatherHours(date).map { it.at } + marine?.hours?.keys?.filter { it.atZone(zone).toLocalDate() == date }.orEmpty()).distinct().sorted()
    fun rows(date: LocalDate): List<ConditionItem> = PlaceConditionRows.make(this, date)
    fun details(date: LocalDate, title: String): List<String> = PlaceConditionRows.details(this, date, title)
}

class PlaceConditionsRepository {
    companion object { const val WEATHER_DAYS = 16; const val MARINE_DAYS = 8; const val MAX_PAST_DAYS = 92 }
    suspend fun load(point: GeoPoint, pastDays: Int = 3): PlaceConditions = coroutineScope {
        require(point.latitude.isFinite() && point.longitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
        require(pastDays in 0..MAX_PAST_DAYS)
        val weather = async { outcome { decodeWeather(request(weatherURL(point, pastDays)), Instant.now()) } }
        val marine = async { outcome { decodeMarine(request(marineURL(point, pastDays)), Instant.now()) } }
        val w = weather.await(); val m = marine.await()
        PlaceConditions(w.getOrNull(), m.getOrNull(), w.exceptionOrNull()?.let { "Weather unavailable · try refreshing" },
            m.exceptionOrNull()?.let { "Offshore waves unavailable · try refreshing" })
    }
    private suspend fun <T> outcome(work: suspend () -> T): Result<T> = try { Result.success(work()) }
        catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
    private suspend fun request(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000
        try {
            require(connection.responseCode in 200..299) { "Provider unavailable" }
            val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            ensureActive(); response
        } finally { connection.disconnect() }
    }
    internal fun weatherURL(point: GeoPoint, past: Int) = "https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}" +
        "&hourly=temperature_2m,apparent_temperature,wind_speed_10m,wind_gusts_10m,wind_direction_10m,precipitation,precipitation_probability,weather_code,is_day,visibility" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant,precipitation_sum,precipitation_probability_max,sunrise,sunset,uv_index_max" +
        "&forecast_days=$WEATHER_DAYS&past_days=$past&wind_speed_unit=kmh&temperature_unit=celsius&precipitation_unit=mm&timeformat=unixtime&timezone=auto"
    internal fun marineURL(point: GeoPoint, past: Int) = "https://marine-api.open-meteo.com/v1/marine?latitude=${point.latitude}&longitude=${point.longitude}" +
        "&hourly=wave_height,wave_period,wave_direction,swell_wave_height,swell_wave_period,sea_surface_temperature" +
        "&forecast_days=$MARINE_DAYS&past_days=$past&length_unit=metric&cell_selection=sea&timeformat=unixtime&timezone=auto"

    internal fun decodeWeather(json: JSONObject, fetchedAt: Instant): PlaceWeather {
        val zone = ZoneId.of(json.getString("timezone"))
        val h = Series(json.getJSONObject("hourly"), json.getJSONObject("hourly_units"), mapOf(
            "temperature_2m" to "°C", "apparent_temperature" to "°C", "wind_speed_10m" to "km/h", "wind_gusts_10m" to "km/h",
            "wind_direction_10m" to "°", "precipitation" to "mm", "precipitation_probability" to "%", "weather_code" to "wmo code", "is_day" to "", "visibility" to "m"))
        val hours = h.times.mapIndexed { i, at -> PlaceWeatherHour(at, h.number("temperature_2m", i, -100.0..80.0), h.number("apparent_temperature", i, -100.0..80.0),
            h.number("wind_speed_10m", i), h.number("wind_gusts_10m", i), h.number("wind_direction_10m", i, 0.0..360.0),
            h.number("precipitation", i), h.number("precipitation_probability", i, 0.0..100.0), h.integer("weather_code", i, 0..99),
            h.integer("is_day", i, 0..1)?.let { it == 1 }, h.number("visibility", i)) }
        val d = Series(json.getJSONObject("daily"), json.getJSONObject("daily_units"), mapOf(
            "weather_code" to "wmo code", "temperature_2m_max" to "°C", "temperature_2m_min" to "°C", "apparent_temperature_max" to "°C", "apparent_temperature_min" to "°C",
            "wind_speed_10m_max" to "km/h", "wind_gusts_10m_max" to "km/h", "wind_direction_10m_dominant" to "°", "precipitation_sum" to "mm",
            "precipitation_probability_max" to "%", "sunrise" to "unixtime", "sunset" to "unixtime", "uv_index_max" to ""))
        val days = d.times.mapIndexed { i, at -> PlaceWeatherDay(at.atZone(zone).toLocalDate(), d.integer("weather_code", i, 0..99),
            d.number("temperature_2m_max", i, -100.0..80.0), d.number("temperature_2m_min", i, -100.0..80.0),
            d.number("apparent_temperature_max", i, -100.0..80.0), d.number("apparent_temperature_min", i, -100.0..80.0),
            d.number("wind_speed_10m_max", i), d.number("wind_gusts_10m_max", i), d.number("wind_direction_10m_dominant", i, 0.0..360.0),
            d.number("precipitation_sum", i), d.number("precipitation_probability_max", i, 0.0..100.0), d.instant("sunrise", i), d.instant("sunset", i), d.number("uv_index_max", i)) }
        require(days.map { it.date }.distinct().size == days.size)
        return PlaceWeather(hours, days, zone, fetchedAt, grid(json))
    }
    internal fun decodeMarine(json: JSONObject, fetchedAt: Instant): PlaceMarine {
        val zone = ZoneId.of(json.getString("timezone"))
        val h = Series(json.getJSONObject("hourly"), json.getJSONObject("hourly_units"), mapOf("wave_height" to "m", "wave_period" to "s",
            "wave_direction" to "°", "swell_wave_height" to "m", "swell_wave_period" to "s", "sea_surface_temperature" to "°C"))
        return PlaceMarine(h.times.mapIndexed { i, at -> at to PlaceMarineHour(h.number("wave_height", i), h.number("wave_period", i)?.takeIf { it > 0 },
            h.number("wave_direction", i, 0.0..360.0), h.number("swell_wave_height", i), h.number("swell_wave_period", i)?.takeIf { it > 0 },
            h.number("sea_surface_temperature", i, -5.0..50.0)) }.toMap(), zone, fetchedAt, grid(json), GeoPoint(json.getDouble("latitude"), json.getDouble("longitude")))
    }
    private fun grid(json: JSONObject): String = "${json.getDouble("latitude")}, ${json.getDouble("longitude")}".also {
        require(json.getDouble("latitude").isFinite() && json.getDouble("latitude") in -90.0..90.0 && json.getDouble("longitude").isFinite() && json.getDouble("longitude") in -180.0..180.0)
    }
    private class Series(val data: JSONObject, units: JSONObject, fields: Map<String, String>) {
        val times: List<Instant>
        init {
            require(units.optString("time") == "unixtime")
            val array = data.getJSONArray("time")
            times = (0 until array.length()).map { i ->
                val number = array.getDouble(i); require(number.isFinite() && number % 1.0 == 0.0)
                Instant.ofEpochSecond(number.toLong())
            }
            require(times.isNotEmpty() && times.zipWithNext().all { (a, b) -> b > a })
            fields.forEach { (field, unit) ->
                if (data.has(field) && !data.isNull(field)) {
                    val values = data.getJSONArray(field)
                    require(values.length() == times.size && units.optString(field, "missing") == unit) { "Invalid $field units or alignment" }
                }
            }
        }
        fun number(field: String, i: Int, limits: ClosedFloatingPointRange<Double> = 0.0..Double.MAX_VALUE): Double? =
            data.optJSONArray(field)?.let { if (it.isNull(i)) null else it.optDouble(i, Double.NaN) }?.takeIf { it.isFinite() && it in limits }
        fun integer(field: String, i: Int, limits: IntRange): Int? = number(field, i)?.takeIf { it % 1.0 == 0.0 && it.toInt() in limits }?.toInt()
        fun instant(field: String, i: Int) = number(field, i)?.takeIf { it % 1.0 == 0.0 }?.let { Instant.ofEpochSecond(it.toLong()) }
    }
}

internal object PlaceConditionRows {
    fun details(data: PlaceConditions, date: LocalDate, title: String): List<String> {
        val hours = data.weatherHours(date)
        val clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val wettest = hours.filter { it.rain != null && it.rain > 0 }.maxByOrNull { it.rain!! }
        val rainPeak = wettest?.let { "Wettest hour ends ${it.at.atZone(data.zone).format(clock)} · ${number(it.rain, 1)} mm." }
        return when (title) {
            "Offshore waves" -> listOf("Height is the largest significant wave height; individual waves can be higher. Period is the time between waves.", "🚤 Boat: short periods can make the ride choppy; longer swells can cause rolling.", "🎣 Shore: check breaking waves, shelter and swell direction. Offshore height does not describe waves at your feet.")
            "Wind" -> listOf("Max is the strongest sustained wind for the day. Gust is a brief stronger burst. Direction shows where wind comes from.", "🎣 Shore: headwinds make casting harder. 🚤 Boat: wind can roughen the water and push the boat.")
            "Rain" -> listOfNotNull(rainPeak, "mm is the day's precipitation total. % is the highest hourly chance, not a whole-day chance.", "Rain drops on weather icons: 1 light · 2 moderate · 3 heavy. Smaller drops mean drizzle.", "Wet clothes, slippery ground and a wet deck can make fishing uncomfortable.")
            "Feels like" -> listOf("The range includes overnight hours and accounts for wind, humidity and sunshine.", "Check the hourly values for your visit. Wet clothes can make you feel colder.")
            "Daylight" -> listOf("Sunrise–sunset in local time.", "Allow time to walk back or return to the ramp before dark.")
            "UV" -> listOf("The day's peak UV index. Protection is useful from UV 3, even when it feels cool.", "Shade, sunscreen, a hat and sunglasses help on shore and on the water.")
            "Visibility" -> listOf("The lowest model visibility for the day.", "Fog or rain can hide landmarks and other boats. Check your visit's hours.")
            "Water temperature" -> listOf("Model temperature at the sea surface, not a measurement at this pin.", "It can help compare days; it does not measure water clarity or fish activity.")
            "Offshore swell" -> listOf("Swell travels from weather farther away. The period is the time between swells.", "Check swell direction and local exposure; calm wind does not guarantee calm water.")
            else -> emptyList()
        }
    }
    fun number(value: Double?, decimals: Int = 0) = value?.let { String.format(Locale.US, "%.$decimals" + "f", it) } ?: "—"
    fun range(values: List<Double?>, decimals: Int = 0): String {
        val known = values.filterNotNull(); if (known.isEmpty()) return "—"
        val a = number(known.min(), decimals); val b = number(known.max(), decimals)
        return if (a == b) a else "$a–$b"
    }
    fun direction(value: Double?) = value?.let { listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(it / 45).roundToInt().mod(8)] } ?: "—"
    fun make(data: PlaceConditions, date: LocalDate): List<ConditionItem> {
        val day = data.weather?.days?.firstOrNull { it.date == date }
        val hours = data.weatherHours(date); val sea = data.marineHours(date)
        val heights = sea.map { it.height }; val periods = sea.map { it.period }
        val maxWave = heights.filterNotNull().maxOrNull()
        val expectedHours = Duration.between(date.atStartOfDay(data.zone), date.plusDays(1).atStartOfDay(data.zone)).toHours().toInt()
        val completeSea = sea.size == expectedHours && heights.all { it != null } && periods.all { it != null }
        val seaFeeling = when {
            maxWave != null && maxWave >= 2.0 -> WindowMood("🌊", "High waves")
            !completeSea -> needsDataMood
            sea.any { (it.height ?: 0.0) >= .5 && (it.period ?: 99.0) <= 5 } -> WindowMood("🌊", "Choppy")
            else -> WindowMood("🌊", if ((maxWave ?: 0.0) <= .5) "Lower waves" else "More motion")
        }
        val wind = ConditionItem("Wind", "max ${number(day?.wind)} km/h · gust ${number(day?.gust)} · ${direction(day?.direction)}",
            if (day?.wind == null || day.gust == null) needsDataMood else comfortMood(LandAssessment.windBand(day.wind, day.gust)))
        val waves = ConditionItem("Offshore waves", if (maxWave == null) "—" else "max ${number(maxWave, 1)} m · ${range(periods, 1)} s", seaFeeling)
        val rain = ConditionItem("Rain", "${number(day?.rain, 1)} mm · ${number(day?.chance)}% hourly max", if (day?.rain == null) needsDataMood else
            WindowMood(if (day.rain > 0) "☔" else "🌤️", if (day.rain > 3) "Wet day" else if (day.rain > 0) "Some rain" else if ((day.chance ?: 0.0) >= 60) "Rain possible" else "Mostly dry"))
        val feels = ConditionItem("Feels like", "${range(listOf(day?.feelsLow, day?.feelsHigh))}°C", if (day?.feelsLow == null || day.feelsHigh == null) needsDataMood else
            WindowMood(if (day.feelsLow < 12) "🥶" else if (day.feelsHigh > 26) "🥵" else "😌", if (day.feelsLow < 12) "Cold at times" else if (day.feelsHigh > 26) "Hot at times" else "Mild"))
        val solarFormat = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val light = ConditionItem("Daylight", if (day?.sunrise == null || day.sunset == null) "—" else "${day.sunrise.atZone(data.zone).format(solarFormat)}–${day.sunset.atZone(data.zone).format(solarFormat)}", if (day?.sunrise == null || day.sunset == null) needsDataMood else WindowMood("🌞", "Plan your return"))
        val visibility = hours.mapNotNull { it.visibility }.minOrNull()
        val uv = ConditionItem("UV", number(day?.uv, 1), if (day?.uv == null) needsDataMood else WindowMood("☀️", if (day.uv >= 3) "Sun protection" else "Lower UV"))
        val view = ConditionItem("Visibility", visibility?.let { "min ${number(it / 1000, 1)} km" } ?: "—", if (visibility == null) needsDataMood else WindowMood(if (visibility < 1000) "🌫️" else "👀", if (visibility < 1000) "Poor at times" else "Check visibility"))
        val water = sea.mapNotNull { it.waterTemperature }
        val waterRow = ConditionItem("Water temperature", if (water.isEmpty()) "—" else "${range(water, 1)}°C", if (water.isEmpty()) needsDataMood else WindowMood("🌡️", "Offshore model"))
        val swells = sea.map { it.swell }
        val swell = ConditionItem("Offshore swell", swells.filterNotNull().maxOrNull()?.let { "max ${number(it, 1)} m · ${range(sea.map { hour -> hour.swellPeriod }, 1)} s" } ?: "—",
            if (swells.isEmpty() || swells.any { it == null } || sea.any { it.swellPeriod == null }) needsDataMood else WindowMood("🌊", "Offshore model"))
        return listOf(waves, wind) + listOf(rain, feels, light, uv, view, waterRow, swell)
    }
}

internal fun placeDistanceKm(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(b.longitude - a.longitude)
    val h = kotlin.math.sin(lat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(a.latitude)) * kotlin.math.cos(Math.toRadians(b.latitude)) * kotlin.math.sin(lon / 2).let { it * it }
    return 6371.0 * 2 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}
