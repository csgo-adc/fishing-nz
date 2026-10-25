package nz.fishingnz.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.fishingnz.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class FishingRulesSection(val heading: String, val text: String)

/** Where the rules on screen came from: kept on the phone (from the service) or the copy built into the app. */
enum class RulesSource { SAVED, BUILT_IN }

data class FishingRulesPage(
    val areaName: String,
    val sourceUrl: String,
    val reviewedAt: String?,
    val needsReview: Boolean,
    val sections: List<FishingRulesSection>,
    val tables: List<List<List<String>>>,
    /** When this copy was saved on the phone; for the built-in copy, the day the app's copy was made. */
    val savedAt: java.time.Instant? = null,
    val source: RulesSource = RulesSource.SAVED
)

/**
 * An MPI area's rules. They are kept on the phone so they show with no signal: [RulesSync] downloads every area when
 * it changes on the service, and the app carries a copy of all of them for a first launch with no signal.
 */
class RulesRepository {
    private val baseUrl = RulesSync.baseUrl

    /** The copy kept on the phone, else the service's (then kept), else the copy built into the app. */
    suspend fun load(areaId: String): FishingRulesPage = withContext(Dispatchers.IO) {
        OfflineCache.read(key(areaId))?.let { (text, at) -> runCatching { parse(areaId, firstRule(text)).copy(savedAt = at) }.getOrNull() }
            ?.let { return@withContext it }
        try {
            downloadAndSave(areaId)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            RulesSync.builtInRule(areaId)?.let { (item, made) -> runCatching { parse(areaId, item).copy(savedAt = made, source = RulesSource.BUILT_IN) }.getOrNull() }
                ?: throw error
        }
    }

    /** Downloads the area's rules from the service and keeps them on the phone. */
    suspend fun downloadAndSave(areaId: String): FishingRulesPage = withContext(Dispatchers.IO) {
        val text = download(areaId)
        parse(areaId, firstRule(text)).also { OfflineCache.write(key(areaId), text) }.copy(savedAt = java.time.Instant.now())
    }

    private fun key(areaId: String) = "rules_$areaId.json"

    private fun firstRule(text: String): JSONObject = JSONObject(text).getJSONArray("rules").getJSONObject(0)

    private fun download(areaId: String): String {
        val connection = (URL("$baseUrl/v1/rules?area=$areaId").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CatchCheckNZ-Android/1.0")
            PrivacyPreferences.analyticsDeviceId()?.let { setRequestProperty("X-Device-Id", it) }
        }
        try {
            if (connection.responseCode !in 200..299) error("Saved rules are unavailable right now. Try again shortly.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    internal fun parse(areaId: String, item: JSONObject): FishingRulesPage {
        val slug = ruleAreaSlugs[areaId] ?: error("Unknown fishing area.")
        val officialUrl = "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/$slug"
        require(item.optString("area_id") == areaId && item.optString("source_url").trimEnd('/') == officialUrl) {
            "Saved rules do not match the selected fishing area. Open the official MPI page instead."
        }
        val sections = item.getJSONArray("sections")
        val tables = item.getJSONArray("tables")
        return FishingRulesPage(
            areaName = item.getString("area_name"),
            sourceUrl = item.getString("source_url"),
            reviewedAt = item.optString("reviewed_at").takeIf { it.isNotBlank() && it != "null" },
            needsReview = item.optBoolean("needsReview", false),
            sections = (0 until sections.length()).map { index ->
                val section = sections.getJSONObject(index)
                FishingRulesSection(section.optString("heading"), section.optString("text"))
            },
            tables = (0 until tables.length()).map { tableIndex ->
                val rows = tables.getJSONArray(tableIndex)
                (0 until rows.length()).map { rowIndex ->
                    val cells = rows.getJSONArray(rowIndex)
                    (0 until cells.length()).map { cellIndex -> cells.optString(cellIndex) }
                }
            }
        )
    }
}

private val ruleAreaSlugs = mapOf(
    "auckland-kermadec" to "auckland-kermadec-fishing-rules",
    "central" to "central-fishing-rules",
    "challenger" to "challenger-fishing-rules",
    "south-east" to "south-east-fishing-rules",
    "southland" to "southland-fishing-rules",
    "kaikoura" to "kaikoura-fishing-rules",
    "chatham-rise" to "chatham-rise-area-recreational-fishing-rules",
    "fiordland" to "fiordland-marine-area-fishing-rules"
)
