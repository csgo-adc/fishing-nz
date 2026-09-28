package nz.fishingnz.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class WeatherRepositoryTest {
    @Test fun parsesLocalCurrentHourlyAndDailyForecasts() {
        val response = JSONObject("""
            {
              "timezone": "Pacific/Auckland",
              "current": {
                "time": "2026-09-28T09:15", "temperature_2m": 16.4,
                "apparent_temperature": 14.2, "relative_humidity_2m": 78,
                "precipitation": 0.2, "wind_speed_10m": 21.3,
                "wind_gusts_10m": 33.1, "wind_direction_10m": 245,
                "weather_code": 61, "is_day": 1
              },
              "hourly": {
                "time": ["2026-09-28T08:00", "2026-09-28T09:00", "2026-09-28T10:00"],
                "temperature_2m": [15.0, 16.0, 17.0],
                "precipitation_probability": [20, 30, 40],
                "wind_speed_10m": [18.0, 21.0, 24.0],
                "weather_code": [3, 61, 2]
              },
              "daily": {
                "time": ["2026-09-28", "2026-09-29"],
                "temperature_2m_max": [19.0, 20.0],
                "temperature_2m_min": [12.0, 11.0],
                "precipitation_probability_max": [60, 20],
                "wind_speed_10m_max": [35.0, 22.0],
                "weather_code": [61, 2]
              }
            }
        """.trimIndent())

        val forecast = decodeWeatherForecast(response, Instant.parse("2026-09-27T21:20:00Z"))
        assertEquals(LocalDateTime.parse("2026-09-28T09:15"), forecast.current.observedAt)
        assertEquals(2, forecast.hourly.size)
        assertEquals(LocalDateTime.parse("2026-09-28T09:00"), forecast.hourly.first().at)
        assertEquals(30, forecast.hourly.first().rainChancePercent)
        assertEquals(LocalDate.parse("2026-09-29"), forecast.daily.last().date)
        assertEquals(20, forecast.daily.last().rainChancePercent)
        assertEquals("Pacific/Auckland", forecast.timeZone.id)
    }
}
