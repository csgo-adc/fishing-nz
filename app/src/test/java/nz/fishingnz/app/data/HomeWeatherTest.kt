package nz.fishingnz.app.data

import nz.fishingnz.app.model.WeatherState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeWeatherTest {
    @Test fun readsWindDirectionAndSunset() {
        val text = """{"current":{"temperature_2m":14.6,"wind_speed_10m":12.9,"wind_direction_10m":226,"precipitation":0.0},
            "daily":{"time":["2026-10-25"],"sunset":["2026-10-25T19:48"]}}"""
        assertEquals(WeatherState("14°C", "12 km/h", "0.0 mm", "SW", "7:48 PM"), FishingRepository().currentWeather(text))
    }

    @Test fun leavesOutWhatIsMissing() {
        val text = """{"current":{"temperature_2m":9.2,"wind_speed_10m":30.4,"precipitation":1.2}}"""
        assertEquals(WeatherState("9°C", "30 km/h", "1.2 mm"), FishingRepository().currentWeather(text))
    }
}
