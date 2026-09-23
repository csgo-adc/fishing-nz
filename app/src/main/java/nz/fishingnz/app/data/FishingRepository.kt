package nz.fishingnz.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.fishingnz.app.BuildConfig
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

    suspend fun identifyFish(image: ByteArray, point: GeoPoint, hasDeviceLocation: Boolean): FishCheck = withContext(Dispatchers.IO) {
        val baseUrl = BuildConfig.FISH_ID_API_BASE_URL.trimEnd('/')
        require(baseUrl.isNotBlank()) { "Fish identification is not configured yet." }
        val connection = (URL("$baseUrl/v1/fish/identify").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "image/jpeg")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Location-Lat-Lon", "${point.latitude},${point.longitude}")
            setRequestProperty("X-Location-Source", if (hasDeviceLocation) "device" else "fallback")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            connection.outputStream.use { it.write(image) }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val payload = JSONObject(body)
            if (connection.responseCode !in 200..299) error(payload.optString("error", "Fish identification failed."))
            val commonName = payload.getString("commonName")
            val scientificName = payload.getString("scientificName")
            val rules = payload.optJSONArray("fishRules")
            val fishRules = (0 until (rules?.length() ?: 0)).map { index ->
                val item = rules!!.getJSONObject(index)
                val details = item.optJSONArray("details")
                val parsedDetails = (0 until (details?.length() ?: 0)).map { detailIndex ->
                    val detail = details!!.getJSONObject(detailIndex)
                    FishRuleDetail(detail.optString("label"), detail.optString("value"))
                }
                FishRuleMatch(
                    item.optString("species"),
                    item.optString("dailyLimit").takeIf { it.isNotBlank() && it != "null" },
                    item.optString("minimumSize").takeIf { it.isNotBlank() && it != "null" },
                    parsedDetails
                )
            }
            FishCheck(
                commonName = commonName,
                scientificName = scientificName,
                confidence = payload.getDouble("confidence").times(100).toInt(),
                areaName = payload.optString("areaName", "Fishing area"),
                areaIsEstimated = payload.optBoolean("areaIsEstimated", true),
                rulesReviewedAt = payload.optString("rulesReviewedAt").takeIf { it.isNotBlank() && it != "null" },
                fishRules = fishRules
            )
        } finally { connection.disconnect() }
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 8_000 }
}
