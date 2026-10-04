package nz.fishingnz.app.model

/** WMO meanings shared by the weather and place-condition screens. */
object WeatherLabels {
    /** Intensity comes from the WMO condition, not rain probability or daily totals. */
    fun rainDrops(code: Int?): Int = when (code) {
        51, 56, 61, 66, 80 -> 1
        53, 63, 81 -> 2
        55, 57, 65, 67, 82 -> 3
        else -> 0
    }

    fun isDrizzle(code: Int?) = code in listOf(51, 53, 55, 56, 57)

    fun describe(code: Int?): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53 -> "Moderate drizzle"
        55 -> "Heavy drizzle"
        56 -> "Light freezing drizzle"
        57 -> "Heavy freezing drizzle"
        61 -> "Light rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66 -> "Light freezing rain"
        67 -> "Heavy freezing rain"
        71 -> "Light snow"
        73, 77 -> "Snow"
        75 -> "Heavy snow"
        80 -> "Light showers"
        81 -> "Moderate showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorms"
        97 -> "Heavy thunderstorms"
        96, 99 -> "Thunderstorms with hail"
        else -> "Weather unavailable"
    }
}
