package nz.fishingnz.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.fishingnz.app.model.GeoPoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class CurrentWeather(
    val observedAt: LocalDateTime,
    val temperatureC: Double,
    val feelsLikeC: Double,
    val humidityPercent: Int,
    val precipitationMm: Double,
    val windKmh: Double,
    val windGustKmh: Double,
    val windDirectionDegrees: Int,
    val code: Int,
    val isDay: Boolean
)

data class HourlyWeather(
    val at: LocalDateTime,
    val temperatureC: Double?,
    val rainChancePercent: Int?,
    val windKmh: Double?,
    val code: Int?,
    val isDay: Boolean? = null
)

data class DailyWeather(
    val date: LocalDate,
    val highC: Double?,
    val lowC: Double?,
    val rainChancePercent: Int?,
    val windMaxKmh: Double?,
    val code: Int?
)

data class LocalWeatherForecast(
    val current: CurrentWeather,
    val hourly: List<HourlyWeather>,
    val daily: List<DailyWeather>,
    val timeZone: ZoneId,
    val fetchedAt: Instant
)

class WeatherRepository {
    suspend fun forecast(point: GeoPoint): LocalWeatherForecast = withContext(Dispatchers.IO) {
        require(point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0) { "Invalid location" }
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${point.latitude}&longitude=${point.longitude}" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,wind_speed_10m,wind_gusts_10m,wind_direction_10m,weather_code,is_day" +
            "&hourly=temperature_2m,precipitation_probability,wind_speed_10m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max" +
            "&forecast_days=16&timezone=auto&wind_speed_unit=kmh&temperature_unit=celsius&precipitation_unit=mm"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode !in 200..299) error("Weather service returned ${connection.responseCode}.")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            decodeWeatherForecast(JSONObject(body), Instant.now())
        } finally {
            connection.disconnect()
        }
    }
}

internal fun decodeWeatherForecast(json: JSONObject, fetchedAt: Instant): LocalWeatherForecast {
    val zone = ZoneId.of(json.getString("timezone"))
    val currentJson = json.getJSONObject("current")
    val current = CurrentWeather(
        observedAt = LocalDateTime.parse(currentJson.getString("time")),
        temperatureC = currentJson.getDouble("temperature_2m"),
        feelsLikeC = currentJson.getDouble("apparent_temperature"),
        humidityPercent = currentJson.getInt("relative_humidity_2m"),
        precipitationMm = currentJson.getDouble("precipitation"),
        windKmh = currentJson.getDouble("wind_speed_10m"),
        windGustKmh = currentJson.getDouble("wind_gusts_10m"),
        windDirectionDegrees = currentJson.getInt("wind_direction_10m"),
        code = currentJson.getInt("weather_code"),
        isDay = currentJson.getInt("is_day") == 1
    )

    val hourlyJson = json.getJSONObject("hourly")
    val hourlyTimes = hourlyJson.getJSONArray("time")
    val temperatures = hourlyJson.getJSONArray("temperature_2m")
    val rainChances = hourlyJson.getJSONArray("precipitation_probability")
    val windSpeeds = hourlyJson.getJSONArray("wind_speed_10m")
    val hourlyCodes = hourlyJson.getJSONArray("weather_code")
    val hourly = (0 until hourlyTimes.length()).map { index ->
        HourlyWeather(
            at = LocalDateTime.parse(hourlyTimes.getString(index)),
            temperatureC = temperatures.optDouble(index, Double.NaN).takeIf { it.isFinite() },
            rainChancePercent = if (rainChances.isNull(index)) null else rainChances.getInt(index).takeIf { it in 0..100 },
            windKmh = windSpeeds.optDouble(index, Double.NaN).takeIf { it.isFinite() && it >= 0 },
            code = if (hourlyCodes.isNull(index)) null else hourlyCodes.getInt(index),
            isDay = hourlyJson.optJSONArray("is_day")?.let { if (it.isNull(index)) null else it.getInt(index).takeIf { value -> value in 0..1 }?.let { value -> value == 1 } }
        )
    }.filter { !it.at.isBefore(current.observedAt.withMinute(0).withSecond(0)) }.take(24)

    val dailyJson = json.getJSONObject("daily")
    val dailyTimes = dailyJson.getJSONArray("time")
    val highs = dailyJson.getJSONArray("temperature_2m_max")
    val lows = dailyJson.getJSONArray("temperature_2m_min")
    val dailyRain = dailyJson.getJSONArray("precipitation_probability_max")
    val dailyWind = dailyJson.getJSONArray("wind_speed_10m_max")
    val dailyCodes = dailyJson.getJSONArray("weather_code")
    val daily = (0 until dailyTimes.length()).map { index ->
        DailyWeather(
            date = LocalDate.parse(dailyTimes.getString(index)),
            highC = highs.optDouble(index, Double.NaN).takeIf { it.isFinite() },
            lowC = lows.optDouble(index, Double.NaN).takeIf { it.isFinite() },
            rainChancePercent = if (dailyRain.isNull(index)) null else dailyRain.getInt(index).takeIf { it in 0..100 },
            windMaxKmh = dailyWind.optDouble(index, Double.NaN).takeIf { it.isFinite() && it >= 0 },
            code = if (dailyCodes.isNull(index)) null else dailyCodes.getInt(index)
        )
    }
    return LocalWeatherForecast(current, hourly, daily, zone, fetchedAt)
}
