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
    val temperatureC: Double,
    val rainChancePercent: Int,
    val windKmh: Double,
    val code: Int
)

data class DailyWeather(
    val date: LocalDate,
    val highC: Double,
    val lowC: Double,
    val rainChancePercent: Int,
    val windMaxKmh: Double,
    val code: Int
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
            "&hourly=temperature_2m,precipitation_probability,wind_speed_10m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max" +
            "&forecast_days=7&timezone=auto"
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
            temperatureC = temperatures.getDouble(index),
            rainChancePercent = rainChances.optInt(index, 0),
            windKmh = windSpeeds.getDouble(index),
            code = hourlyCodes.getInt(index)
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
            highC = highs.getDouble(index),
            lowC = lows.getDouble(index),
            rainChancePercent = dailyRain.optInt(index, 0),
            windMaxKmh = dailyWind.getDouble(index),
            code = dailyCodes.getInt(index)
        )
    }
    return LocalWeatherForecast(current, hourly, daily, zone, fetchedAt)
}
