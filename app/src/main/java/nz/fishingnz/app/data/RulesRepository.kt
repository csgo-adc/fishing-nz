package nz.fishingnz.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.fishingnz.app.BuildConfig
import nz.fishingnz.app.model.GeoPoint
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class FishingRulesSection(val heading: String, val text: String)
data class FishingRulesPage(
    val areaName: String,
    val sourceUrl: String,
    val reviewedAt: String?,
    val sections: List<FishingRulesSection>,
    val tables: List<List<List<String>>>
)

class RulesRepository {
    private val baseUrl = BuildConfig.FISH_ID_API_BASE_URL.trimEnd('/').ifBlank { "https://fishing.fishnz.space" }

    suspend fun load(areaId: String): FishingRulesPage = withContext(Dispatchers.IO) {
        require(areaId in ruleAreaIds) { "Unknown fishing area." }
        val connection = (URL("$baseUrl/v1/rules?area=$areaId").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CatchCheckNZ-Android/1.0")
        }
        try {
            if (connection.responseCode !in 200..299) error("Saved rules are unavailable right now. Try again shortly.")
            val item = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .getJSONArray("rules").getJSONObject(0)
            val sections = item.getJSONArray("sections")
            val tables = item.getJSONArray("tables")
            FishingRulesPage(
                areaName = item.getString("area_name"),
                sourceUrl = item.getString("source_url"),
                reviewedAt = item.optString("reviewed_at").takeIf { it.isNotBlank() && it != "null" },
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
        } finally { connection.disconnect() }
    }
}

private val ruleAreaIds = setOf("auckland-kermadec", "central", "challenger", "south-east", "southland", "kaikoura", "chatham-rise", "fiordland")

/** Mirrors the service's broad area estimate; exact boundaries still need confirmation with MPI. */
fun rulesAreaForLocation(point: GeoPoint): String = when {
    point.longitude < -175 && point.latitude < -40 && point.latitude > -49 -> "chatham-rise"
    point.latitude <= -44.5 && point.longitude in 166.0..168.8 -> "fiordland"
    point.latitude <= -46.3 -> "southland"
    point.latitude < -42.4 && point.latitude > -44 && point.longitude in 172.2..174.4 -> "kaikoura"
    point.latitude <= -40 && point.latitude >= -46.3 && point.longitude < 171 -> "challenger"
    point.latitude <= -42.5 && point.latitude > -46.3 && point.longitude >= 171 -> "south-east"
    point.latitude > -37.7 -> "auckland-kermadec"
    point.latitude > -41.6 -> "central"
    else -> "challenger"
}
