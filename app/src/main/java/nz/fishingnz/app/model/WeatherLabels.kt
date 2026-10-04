package nz.fishingnz.app.model

/** WMO meanings shared by the weather and place-condition screens. */
object WeatherLabels {
    fun describe(code: Int?): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73, 77 -> "Snow"
        75 -> "Heavy snow"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorms"
        96, 99 -> "Thunderstorms with hail"
        else -> "Weather unavailable"
    }
}
