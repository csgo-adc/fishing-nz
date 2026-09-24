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
import nz.fishingnz.app.model.AccountProfile
import nz.fishingnz.app.model.AccountSnapshot

class FishingRepository {
    private val accountBaseUrl: String get() = BuildConfig.FISH_ID_API_BASE_URL.trimEnd('/').ifBlank { "https://fishing.fishnz.space" }

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

    suspend fun signIn(email: String, password: String, displayName: String?, createAccount: Boolean): AccountSnapshot = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        if (createAccount) body.put("display_name", displayName.orEmpty().trim())
        val route = if (createAccount) "register" else "login"
        val response = accountRequest("/v1/auth/$route", "POST", body)
        val token = response.getString("token")
        AccountSessionStore.save(token)
        accountSnapshot(token)
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
        try { accountSnapshot(token) } catch (error: Exception) {
            if (error.message?.contains("401") == true) AccountSessionStore.clear()
            null
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
            if (status !in 200..299) error(response.optString("error").ifBlank { "Account request failed ($status)." } + " ($status)")
            return response
        } finally { connection.disconnect() }
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "GET"; connectTimeout = 8_000; readTimeout = 8_000 }
}
