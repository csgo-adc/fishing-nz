package nz.fishingnz.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import nz.fishingnz.app.BuildConfig
import nz.fishingnz.app.model.*
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos

class AccountRequestException(val statusCode: Int, val code: String?, message: String) : IOException(message)

class FishingRepository {
    private val accountBaseUrl: String get() = BuildConfig.FISH_ID_API_BASE_URL.trimEnd('/').ifBlank { "https://fishing.fishnz.space" }
    private val nzZone = ZoneId.of("Pacific/Auckland")
    private val annualTides = ConcurrentHashMap<String, List<TidePrediction>>()
    private data class TidePrediction(val at: LocalDateTime, val height: Double)

    suspend fun conditions(point: GeoPoint): Pair<WeatherState, TideState?> = withContext(Dispatchers.IO) {
        val connection = get("https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}&current=temperature_2m,wind_speed_10m,precipitation&timezone=auto")
        try {
            if (connection.responseCode !in 200..299) error("Weather request failed")
            val current = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            val weather = WeatherState("${current.getDouble("temperature_2m").toInt()}°C", "${current.getDouble("wind_speed_10m").toInt()} km/h", "${current.getDouble("precipitation")} mm")
            val tide = try { tide(nearestTideStation(point), LocalDate.now(nzZone)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            weather to tide
        } finally { connection.disconnect() }
    }

    suspend fun tide(station: TideStation, date: LocalDate): TideState = withContext(Dispatchers.IO) {
        // LINZ publishes these highs and lows above the station's Chart Datum.
        // The curve between events is only a visual interpolation, never an official prediction.
        val predictions = buildList {
            if (date.dayOfYear == 1) addAll(runCatching { annualPredictions(station, date.year - 1) }.getOrDefault(emptyList()))
            addAll(annualPredictions(station, date.year))
            if (date.dayOfYear == date.lengthOfYear()) addAll(runCatching { annualPredictions(station, date.year + 1) }.getOrDefault(emptyList()))
        }.sortedBy { it.at }
        val selected = predictions.withIndex().filter { it.value.at.toLocalDate() == date }
        require(selected.isNotEmpty()) { "No LINZ tide predictions for ${station.name} on $date." }
        val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val eventFormat = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.US)
        fun typeAt(index: Int): String {
            val current = predictions[index].height
            val neighbour = predictions.getOrNull(index - 1)?.height ?: predictions.getOrNull(index + 1)?.height ?: current
            return if (current >= neighbour) "High" else "Low"
        }
        val events = selected.map { (index, item) ->
            TideEvent(item.at.format(timeFormat), "%.2f m".format(Locale.US, item.height), typeAt(index))
        }
        val start = date.atStartOfDay()
        val sampleMinutes = (0..1440 step 15).toMutableSet().apply {
            selected.forEach { (_, item) -> add(Duration.between(start, item.at).toMinutes().toInt()) }
        }
        val points = sampleMinutes.sorted().mapNotNull { minute ->
            val at = start.plusMinutes(minute.toLong())
            interpolatedHeight(predictions, at)?.let { height -> TidePoint(at.format(timeFormat), height, minute) }
        }
        val now = LocalDateTime.now(nzZone)
        val shownAt = if (date == now.toLocalDate()) now else start.plusHours(12)
        val shownHeight = interpolatedHeight(predictions, shownAt)
        val nextIndex = if (date == now.toLocalDate()) predictions.indexOfFirst { it.at.isAfter(now) }
            else selected.first().index
        val next = predictions.getOrNull(nextIndex)
        TideState(
            shownHeight?.let { "%.2f m".format(Locale.US, it) } ?: "—",
            next?.let { typeAt(nextIndex) } ?: "—",
            next?.at?.format(eventFormat) ?: "No upcoming event",
            events, points, station.name
        )
    }

    private fun annualPredictions(station: TideStation, year: Int): List<TidePrediction> {
        val key = "${station.id}:$year"
        annualTides[key]?.let { return it }
        val filename = URLEncoder.encode("${station.name} $year.csv", Charsets.UTF_8.name()).replace("+", "%20")
        val connection = get("https://static.charts.linz.govt.nz/tide-tables/maj-ports/csv/$filename")
        val text = try {
            if (connection.responseCode !in 200..299) error("LINZ tide table unavailable for ${station.name} in $year.")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
        val parsed = text.lineSequence().mapNotNull { line ->
            val fields = line.trimStart('\uFEFF').trimEnd('\r').split(',')
            if (fields.size < 6) return@mapNotNull null
            val day = fields[0].trim().toIntOrNull() ?: return@mapNotNull null
            val month = fields[2].trim().toIntOrNull() ?: return@mapNotNull null
            val rowYear = fields[3].trim().toIntOrNull() ?: return@mapNotNull null
            val date = runCatching { LocalDate.of(rowYear, month, day) }.getOrNull() ?: return@mapNotNull null
            (4 until fields.size - 1 step 2).mapNotNull { index ->
                val time = runCatching { LocalTime.parse(fields[index].trim()) }.getOrNull()
                val height = fields[index + 1].trim().toDoubleOrNull()
                if (time == null || height == null) null else TidePrediction(date.atTime(time), height)
            }
        }.flatten().sortedBy { it.at }.toList()
        require(parsed.isNotEmpty()) { "LINZ tide table was empty for ${station.name} in $year." }
        return annualTides.putIfAbsent(key, parsed) ?: parsed
    }

    private fun interpolatedHeight(predictions: List<TidePrediction>, at: LocalDateTime): Double? {
        val afterIndex = predictions.indexOfFirst { !it.at.isBefore(at) }
        if (afterIndex < 0) return null
        val after = predictions[afterIndex]
        if (after.at == at) return after.height
        val before = predictions.getOrNull(afterIndex - 1) ?: return null
        val totalMinutes = Duration.between(before.at, after.at).toMinutes().toDouble()
        if (totalMinutes <= 0) return null
        val fraction = Duration.between(before.at, at).toMinutes().toDouble() / totalMinutes
        return before.height + (after.height - before.height) * (1.0 - cos(PI * fraction)) / 2.0
    }

    suspend fun identifyFish(image: ByteArray, point: GeoPoint, hasDeviceLocation: Boolean): FishCheck = withContext(Dispatchers.IO) {
        val token = AccountSessionStore.token()
        require(token != null) { "Sign in to use fish identification." }
        val connection = (URL("$accountBaseUrl/v1/fish/identify").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "image/jpeg")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Location-Lat-Lon", "${point.latitude},${point.longitude}")
            setRequestProperty("X-Location-Source", if (hasDeviceLocation) "device" else "fallback")
            setRequestProperty("X-Client-Platform", "android")
            setRequestProperty("Authorization", "Bearer $token")
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

    suspend fun signIn(email: String, password: String): AccountSnapshot = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val response = accountRequest("/v1/auth/login", "POST", body)
        val token = response.getString("token")
        val snapshot = parseAccountSnapshot(response.getJSONObject("user"), response.getJSONObject("permissions"))
        AccountSessionStore.save(token)
        snapshot
    }

    suspend fun createAccount(email: String, password: String, displayName: String): String = withContext(Dispatchers.IO) {
        val response = accountRequest("/v1/auth/register", "POST", JSONObject().put("email", email.trim()).put("password", password).put("display_name", displayName.trim()))
        response.optString("message", "Check your email to confirm your account.")
    }

    suspend fun resendVerification(email: String): String = withContext(Dispatchers.IO) {
        val response = accountRequest("/v1/auth/resend-verification", "POST", JSONObject().put("email", email.trim()))
        response.optString("message", "If an unconfirmed account uses that email, a confirmation link will be sent.")
    }

    suspend fun trackEvent(eventName: String, feature: String?, platform: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("event_name", eventName).put("platform", platform)
        if (feature != null) payload.put("feature", feature)
        accountRequest("/v1/analytics/events", "POST", payload, AccountSessionStore.token())
    }

    suspend fun currentAccount(): AccountSnapshot? = withContext(Dispatchers.IO) {
        val token = AccountSessionStore.token() ?: return@withContext null
        try {
            val snapshot = accountSnapshot(token)
            if (AccountSessionStore.token() == token) snapshot else null
        } catch (error: AccountRequestException) {
            if (error.statusCode == 401) {
                if (AccountSessionStore.token() == token) AccountSessionStore.clear()
                null
            } else throw error
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        AccountSessionStore.token()?.let { token -> runCatching { accountRequest("/v1/auth/logout", "POST", JSONObject(), token) } }
        AccountSessionStore.clear()
    }

    suspend fun saveProfile(displayName: String, countryCode: String): AccountSnapshot = withContext(Dispatchers.IO) {
        val token = AccountSessionStore.token() ?: error("Sign in to update your profile.")
        accountRequest("/v1/me", "PATCH", JSONObject().put("display_name", displayName.trim()).put("country_code", countryCode.trim().uppercase()), token)
        accountSnapshot(token)
    }

    suspend fun sendFeedback(category: String, message: String, rating: Int) = withContext(Dispatchers.IO) {
        val token = AccountSessionStore.token() ?: error("Sign in to send feedback.")
        accountRequest("/v1/feedback", "POST", JSONObject().put("category", category).put("message", message.trim()).put("rating", rating), token, "android")
    }

    private fun accountSnapshot(token: String): AccountSnapshot {
        val profile = accountRequest("/v1/me", "GET", token = token).getJSONObject("user")
        val permissions = accountRequest("/v1/me/permissions", "GET", token = token)
        return parseAccountSnapshot(profile, permissions)
    }

    private fun parseAccountSnapshot(profile: JSONObject, permissions: JSONObject): AccountSnapshot {
        return AccountSnapshot(
            AccountProfile(profile.getString("id"), profile.getString("email"), profile.optString("display_name"), profile.optString("country_code", "NZ"), profile.optString("plan", "free")),
            permissions.optJSONObject("features")?.optBoolean("fish_identity") == true,
        )
    }

    private fun accountRequest(path: String, method: String, payload: JSONObject? = null, token: String? = null, platform: String? = null): JSONObject {
        val connection = (URL("$accountBaseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            platform?.let { setRequestProperty("X-Client-Platform", it) }
            if (payload != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            payload?.let { connection.outputStream.use { output -> output.write(it.toString().toByteArray(Charsets.UTF_8)) } }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            if (status !in 200..299) throw AccountRequestException(
                status,
                response.optString("code").takeIf { it.isNotBlank() },
                response.optString("error").ifBlank { "Account request failed. Please try again." },
            )
            return response
        } finally { connection.disconnect() }
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 8_000 }
}
