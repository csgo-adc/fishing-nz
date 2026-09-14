package nz.fishingnz.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.fishingnz.app.model.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class FishingRepository {
    suspend fun conditions(point: GeoPoint): Pair<WeatherState, TideState> = withContext(Dispatchers.IO) {
        val connection = get("https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}&current=temperature_2m,wind_speed_10m,precipitation&timezone=auto")
        try {
            if (connection.responseCode !in 200..299) error("Weather request failed")
            val current = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            WeatherState("${current.getDouble("temperature_2m").toInt()}°C", "${current.getDouble("wind_speed_10m").toInt()} km/h", "${current.getDouble("precipitation")} mm") to tide(TideStation("current", "Current location", "", point.latitude, point.longitude), LocalDate.now())
        } finally { connection.disconnect() }
    }

    suspend fun tide(station: TideStation, date: LocalDate): TideState = withContext(Dispatchers.IO) {
        val connection = get("https://marine-api.open-meteo.com/v1/marine?latitude=${station.latitude}&longitude=${station.longitude}&hourly=sea_level_height_msl&start_date=$date&end_date=${date.plusDays(1)}&cell_selection=sea&timezone=auto")
        try {
            if (connection.responseCode !in 200..299) error("Tide request failed")
            val hourly = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("hourly")
            val times = hourly.getJSONArray("time"); val levels = hourly.getJSONArray("sea_level_height_msl")
            val parsed = (0 until times.length()).map { LocalDateTime.parse(times.getString(it)) }
            val indices = parsed.indices.filter { parsed[it].toLocalDate() == date }
            val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            val points = indices.map { TidePoint(parsed[it].format(timeFormat), levels.getDouble(it)) }
            val eventPairs = (1 until levels.length() - 1).mapNotNull { i ->
                val previous = levels.getDouble(i - 1); val current = levels.getDouble(i); val next = levels.getDouble(i + 1)
                if ((current > previous && current >= next) || (current < previous && current <= next)) i to TideEvent(parsed[i].format(timeFormat), "%.2f m".format(Locale.US, current), if (current > previous) "High" else "Low") else null
            }
            val today = LocalDate.now()
            val currentIndex = if (date == today) parsed.indexOfLast { !it.isAfter(LocalDateTime.now()) }.coerceAtLeast(0) else indices.firstOrNull() ?: 0
            val selectedPairs = eventPairs.filter { parsed[it.first].toLocalDate() == date }
            val nextPair = if (date == today) eventPairs.firstOrNull { parsed[it.first].isAfter(LocalDateTime.now()) } else selectedPairs.firstOrNull()
            val events = selectedPairs.map { it.second }
            TideState("%.2f m".format(Locale.US, levels.getDouble(currentIndex)), nextPair?.second?.type ?: "—", nextPair?.first?.let { parsed[it].format(DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.US)) } ?: "No event", events, points, station.name)
        } finally { connection.disconnect() }
    }

    suspend fun identifyFish(): FishCheck = withContext(Dispatchers.Default) {
        kotlinx.coroutines.delay(650)
        FishCheck("Snapper", "Pagrus auratus", 91, "30 cm", "7 per person / day", "Likely legal", "Confirm the region and current MPI rules before keeping it.")
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 8_000 }
}
