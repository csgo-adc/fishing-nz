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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

class AccountRequestException(
    val statusCode: Int, val code: String?, message: String,
    val fishIdentityQuota: FishIdentityQuota? = null,
) : IOException(message)
data class SignInProviders(val google: Boolean = false, val apple: Boolean = false, val connected: Set<String> = emptySet())

class FishingRepository {
    private val accountBaseUrl: String get() = BuildConfig.FISH_ID_API_BASE_URL.trimEnd('/').ifBlank { "https://fishing.fishnz.space" }
    private val nzZone = ZoneId.of("Pacific/Auckland")

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
        val predictions = LinzTideSource.predictions(station, date, date)
        val selected = predictions.filter { it.at.atZone(nzZone).toLocalDate() == date }
        require(selected.isNotEmpty()) { "No LINZ tide predictions for ${station.name} on $date." }
        val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        val eventFormat = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", Locale.US)
        fun typeOf(item: LinzPrediction) = if (item.high) "High" else "Low"
        val events = selected.map { item ->
            TideEvent(item.at.atZone(nzZone).format(timeFormat), "%.2f m".format(Locale.US, item.height), typeOf(item))
        }
        val start = date.atStartOfDay()
        val samples = (0..1440 step 15).mapNotNull { minute ->
            val local = start.plusMinutes(minute.toLong())
            // Do not silently shift nonexistent clocks or choose one occurrence of a repeated clock.
            nzZone.rules.getValidOffsets(local).singleOrNull()?.let { local.toInstant(it) }
        }.toMutableSet().apply {
            selected.forEach { item -> add(item.at) }
        }
        val points = samples.sorted().mapNotNull { at ->
            val local = at.atZone(nzZone)
            val minute = if (local.toLocalDate().isAfter(date)) 1440 else local.hour * 60 + local.minute
            interpolatedHeight(predictions, at)?.let { height -> TidePoint(local.format(timeFormat), height, minute) }
        }
        val now = Instant.now()
        val isToday = date == now.atZone(nzZone).toLocalDate()
        val shownAt = if (isToday) now else start.plusHours(12).atZone(nzZone).toInstant()
        val shownHeight = interpolatedHeight(predictions, shownAt)
        val next = if (isToday) predictions.firstOrNull { it.at > now } else selected.first()
        TideState(
            shownHeight?.let { "%.2f m".format(Locale.US, it) } ?: "—",
            next?.let(::typeOf) ?: "—",
            next?.at?.atZone(nzZone)?.format(eventFormat) ?: "No upcoming event",
            events, points, station.name
        )
    }

    private fun interpolatedHeight(predictions: List<LinzPrediction>, at: Instant): Double? {
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

    suspend fun identifyFish(image: ByteArray, rulesAreaId: String?): FishCheck = withContext(Dispatchers.IO) {
        val token = AccountSessionStore.token()
        require(token != null) { "Sign in to use fish identification." }
        val connection = (URL("$accountBaseUrl/v1/fish/identify").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "image/jpeg")
            setRequestProperty("Accept", "application/json")
            rulesAreaId?.let { setRequestProperty("X-Fishing-Rules-Area", it) }
            setRequestProperty("X-Client-Platform", "android")
            PrivacyPreferences.analyticsDeviceId()?.let { setRequestProperty("X-Device-Id", it) }
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            connection.outputStream.use { it.write(image) }
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val payload = JSONObject(body)
            if (connection.responseCode !in 200..299) throw AccountRequestException(
                connection.responseCode, payload.optString("code"),
                payload.optString("error", "Fish identification failed."),
                parseFishIdentityQuota(payload.optJSONObject("fish_identity_quota")),
            )
            val commonName = payload.getString("commonName")
            val scientificName = payload.getString("scientificName")
            val possibilities = payload.optJSONArray("otherPossibilities")
            val otherPossibilities = (0 until (possibilities?.length() ?: 0)).mapNotNull { index ->
                possibilities?.optString(index)?.takeIf { it.isNotBlank() }
            }
            FishCheck(
                commonName = commonName,
                scientificName = scientificName,
                confidence = payload.getDouble("confidence").times(100).toInt(),
                areaId = payload.optString("areaId").takeIf { it.isNotBlank() && it != "null" },
                areaName = payload.optString("areaName", "Fishing area"),
                areaIsEstimated = payload.optBoolean("areaIsEstimated", true),
                rulesReviewedAt = payload.optString("rulesReviewedAt").takeIf { it.isNotBlank() && it != "null" },
                fishRules = parseFishRules(payload),
                rulesNeedsReview = payload.optBoolean("rulesNeedsReview", false),
                rulesSourceUrl = payload.optString("rulesSourceUrl").takeIf { it.isNotBlank() && it != "null" },
                areaSelectionRequired = payload.optBoolean("areaSelectionRequired", false),
                isFish = payload.optBoolean("isFish", true),
                otherPossibilities = otherPossibilities,
                visibleClues = payload.optString("visibleClues"),
                identificationNote = payload.optString("identificationNote"),
                fishIdentityQuota = parseFishIdentityQuota(payload.optJSONObject("fish_identity_quota"))
            )
        } finally { connection.disconnect() }
    }

    suspend fun fishRules(species: String, areaId: String): FishRulesResult = withContext(Dispatchers.IO) {
        val encodedArea = URLEncoder.encode(areaId, "UTF-8")
        val encodedSpecies = URLEncoder.encode(species, "UTF-8")
        val connection = get("$accountBaseUrl/v1/fish/rules?area=$encodedArea&species=$encodedSpecies").apply {
            PrivacyPreferences.analyticsDeviceId()?.let { setRequestProperty("X-Device-Id", it) }
        }
        try {
            val body = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val payload = JSONObject(body)
            if (connection.responseCode !in 200..299) error(payload.optString("error", "Could not load MPI rules."))
            FishRulesResult(
                areaId = payload.getString("areaId"),
                areaName = payload.getString("areaName"),
                rulesReviewedAt = payload.optString("rulesReviewedAt").takeIf { it.isNotBlank() && it != "null" },
                fishRules = parseFishRules(payload),
                rulesNeedsReview = payload.optBoolean("rulesNeedsReview"),
                rulesSourceUrl = payload.optString("rulesSourceUrl").takeIf { it.isNotBlank() && it != "null" }
            )
        } finally { connection.disconnect() }
    }

    private fun parseFishRules(payload: JSONObject): List<FishRuleMatch> {
        val rules = payload.optJSONArray("fishRules")
        return (0 until (rules?.length() ?: 0)).map { index ->
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
                parsedDetails,
                item.optString("minimumSizeLabel").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }

    suspend fun signIn(email: String, password: String): AccountSnapshot = withContext(Dispatchers.IO) {
        AccountSessionStore.clearOAuthSecret()
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

    suspend fun signInProviders(): SignInProviders = withContext(Dispatchers.IO) {
        val response = accountRequest("/v1/auth/providers", "GET", token = AccountSessionStore.token())
        val connected = response.optJSONArray("connected")
        SignInProviders(response.optBoolean("google"), response.optBoolean("apple"),
            (0 until (connected?.length() ?: 0)).mapNotNull { connected?.optString(it) }.toSet())
    }

    suspend fun startSocialSignIn(provider: String, link: Boolean): String = withContext(Dispatchers.IO) {
        val response = accountRequest("/v1/auth/oauth/start", "POST", JSONObject()
            .put("provider", provider).put("link", link).put("return_uri", "nz.fishingnz.app://auth/callback"), AccountSessionStore.token())
        AccountSessionStore.saveOAuthSecret(response.getString("exchange_secret"))
        response.getString("authorization_url")
    }

    suspend fun completeSocialSignIn(code: String, secret: String): AccountSnapshot = withContext(Dispatchers.IO) {
        val response = accountRequest("/v1/auth/oauth/exchange", "POST", JSONObject().put("code", code).put("exchange_secret", secret))
        val snapshot = parseAccountSnapshot(response.getJSONObject("user"), response.getJSONObject("permissions"))
        AccountSessionStore.save(response.getString("token"))
        snapshot
    }

    suspend fun resendVerification(email: String): String = withContext(Dispatchers.IO) {
        val response = accountRequest("/v1/auth/resend-verification", "POST", JSONObject().put("email", email.trim()))
        response.optString("message", "If an unconfirmed account uses that email, a confirmation link will be sent.")
    }

    suspend fun trackEvent(eventName: String, feature: String?, platform: String) = withContext(Dispatchers.IO) {
        if (!PrivacyPreferences.analyticsEnabled()) return@withContext
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
        AccountSessionStore.clearOAuthSecret()
        AccountSessionStore.token()?.let { token -> runCatching { accountRequest("/v1/auth/logout", "POST", JSONObject(), token) } }
        AccountSessionStore.clear()
    }

    /** The service keeps this session signed in and ends the account's others. Errors carry a message fit to show. */
    suspend fun changePassword(currentPassword: String, newPassword: String) = withContext(Dispatchers.IO) {
        val token = AccountSessionStore.token() ?: error("Sign in again to change your password.")
        accountRequest("/v1/me/password", "POST", JSONObject().put("current_password", currentPassword).put("new_password", newPassword), token, "android")
        Unit
    }

    /** Ends every session for the account, including this one, so the saved session is cleared afterwards. */
    suspend fun signOutEverywhere() = withContext(Dispatchers.IO) {
        AccountSessionStore.token()?.let { token -> accountRequest("/v1/auth/logout-all", "POST", JSONObject(), token, "android") }
        AccountSessionStore.clearOAuthSecret()
        AccountSessionStore.clear()
    }

    suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        val token = AccountSessionStore.token() ?: error("Sign in again to delete your account.")
        val response = accountRequest("/v1/me", "DELETE", JSONObject().put("confirm", true), token)
        check(response.optBoolean("deleted")) { "Account deletion could not be confirmed. Please try again." }
        AccountSessionStore.clearOAuthSecret()
        AccountSessionStore.clear()
        PrivacyPreferences.setAnalyticsEnabled(false)
        PrivacyPreferences.setFirebaseEnabled(false)
        PrivacyPreferences.clearFishPhotos()
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
            AccountProfile(profile.getString("id"), profile.getString("email"), profile.optString("display_name"), profile.optString("country_code", "NZ"), profile.optString("plan", "free"), profile.optBoolean("has_password", true)),
            permissions.optJSONObject("features")?.optBoolean("fish_identity") == true,
            parseFishIdentityQuota(permissions.optJSONObject("fish_identity_quota")),
        )
    }

    private fun parseFishIdentityQuota(value: JSONObject?): FishIdentityQuota? = value?.let {
        FishIdentityQuota(it.getInt("limit"), it.getInt("used"), it.getInt("remaining"), it.getString("day"))
    }

    suspend fun uploadAnalytics(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        accountRequest("/v1/analytics/batch", "POST", payload, AccountSessionStore.token(), "android")
    }

    /** Ask the service for a short link to a shared window; the reply holds `id`. */
    suspend fun createShare(token: String): JSONObject = withContext(Dispatchers.IO) {
        accountRequest("/v1/share", "POST", JSONObject().put("token", token), platform = "android")
    }

    /** The token behind a short link; the reply holds `token`. */
    suspend fun readShare(id: String): JSONObject = withContext(Dispatchers.IO) {
        accountRequest("/v1/share/$id", "GET", platform = "android")
    }

    suspend fun eraseAnalyticsDevice(deviceId: String): JSONObject = withContext(Dispatchers.IO) {
        accountRequest("/v1/analytics/device", "DELETE", deviceId = deviceId)
    }

    private fun accountRequest(path: String, method: String, payload: JSONObject? = null, token: String? = null, platform: String? = null, deviceId: String? = null): JSONObject {
        val connection = (URL("$accountBaseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            platform?.let { setRequestProperty("X-Client-Platform", it) }
            // Lets the service count this device's API use; present only while optional analytics is on.
            (deviceId ?: PrivacyPreferences.analyticsDeviceId())?.let { setRequestProperty("X-Device-Id", it) }
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
