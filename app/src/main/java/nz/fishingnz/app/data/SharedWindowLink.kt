package nz.fishingnz.app.data

import nz.fishingnz.app.model.ConditionItem
import nz.fishingnz.app.model.Recommendation
import nz.fishingnz.app.model.WindowAssessment
import nz.fishingnz.app.model.WindowMood
import nz.fishingnz.app.model.windowMood
import nz.fishingnz.app.model.windowOutlook
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Base64
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The link behind "Share this window": `https://fishing.fishnz.space/w/<token>`, where the token is the unpadded base64url
 * of a small JSON snapshot. The same format is written by the iPhone app and drawn by the Worker's web page; see
 * docs/share-fishing-window.md. Links can come from anywhere, so reading one validates and limits every field.
 */
object SharedWindowLink {
    const val ORIGIN = "https://fishing.fishnz.space"
    private const val VERSION = 1
    private const val MAX_ROWS = 8
    private const val MAX_WINDOW_SECONDS = 24 * 3600L
    private const val EARLIEST = 1_704_067_200L // 2024-01-01
    private const val LATEST = 4_102_444_800L // 2100-01-01
    private val token = Regex("[A-Za-z0-9_-]{1,4096}")
    // Eight characters from an alphabet without the look-alikes 0 O 1 I l. No real token is this short.
    private val shortId = Regex("[2-9A-HJ-NP-Za-km-z]{8}")
    // Control characters, line or paragraph separators and bidirectional overrides have no place in a place name.
    private val bidiAndInvisible = intArrayOf(0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF)
        .joinToString("") { String(Character.toChars(it)) }
    private val unsafe = Regex("[\\p{Cc}\\p{Zl}\\p{Zp}$bidiAndInvisible]")

    /** The long link, which holds the whole window in the address, or null when it has no start time to share. */
    fun longUrl(window: Recommendation, nowEpochSeconds: Long = Instant.now().epochSecond): String? =
        encode(window, nowEpochSeconds)?.let { "$ORIGIN/w/$it" }

    /** The short link the service made for a window, `https://fishing.fishnz.space/w/k3F9xQ2m`. */
    fun shortUrl(id: String): String = "$ORIGIN/w/$id"

    /** True for the service's short ids, which have to be looked up, and false for a token that holds the window itself. */
    fun isShortId(token: String): Boolean = shortId.matches(token)

    /** The text the share sheet sends: where and when, how it looks, and the link. */
    fun text(window: Recommendation, url: String): String =
        "Fishing window: ${window.name}, ${window.area}\n${window.time} · ${window.windowOutlook}\n$url"

    fun encode(window: Recommendation, nowEpochSeconds: Long = Instant.now().epochSecond): String? {
        if (window.startsAtEpochSeconds <= 0 || window.durationHours <= 0) return null
        val json = JSONObject()
            .put("v", VERSION)
            .put("n", clip(window.name, 80))
            .put("a", clip(window.area, 80))
            .put("b", if (window.boat) 1 else 0)
            .put("s", window.startsAtEpochSeconds)
            .put("e", window.startsAtEpochSeconds + window.durationHours * 3600L)
        if (window.latitude.isFinite() && window.longitude.isFinite()) json.put("la", round4(window.latitude)).put("lo", round4(window.longitude))
        json.put("o", mood(window.windowMood))
        window.assessment?.let { assessment ->
            json.put("f", mood(assessment.confidence))
            val rows = JSONArray()
            assessment.conditions.take(MAX_ROWS).forEach {
                rows.put(JSONArray().put(clip(it.title, 24)).put(clip(it.value, 120)).put(clip(it.mood.emoji, 8)).put(clip(it.mood.label, 40)))
            }
            json.put("c", rows)
        }
        reasonOf(window)?.let { json.put("r", clip(it, 200)) }
        json.put("t", nowEpochSeconds)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** The token in a shared-window link, or null for any other address. */
    fun tokenFrom(link: String?): String? {
        val text = link ?: return null
        val prefix = "$ORIGIN/w/"
        if (!text.startsWith(prefix, ignoreCase = true)) return null
        return text.substring(prefix.length).substringBefore('?').substringBefore('#').trimEnd('/').takeIf { token.matches(it) }
    }

    /** A window read from a long link, or null for anything else. A short link needs [ShareService.resolve]. */
    fun parse(link: String?): Recommendation? = tokenFrom(link)?.takeIf { !isShortId(it) }?.let(::decode)

    fun decode(token: String): Recommendation? = try {
        val json = JSONObject(String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8))
        read(json)
    } catch (_: Exception) {
        null
    }

    private fun read(json: JSONObject): Recommendation? {
        if (json.optInt("v", 0) != VERSION) return null
        val name = text(json, "n", 80)
        val area = text(json, "a", 80)
        val start = json.optLong("s", 0)
        val end = json.optLong("e", 0)
        if (name == null || area == null || start !in EARLIEST..LATEST || end !in EARLIEST..LATEST || end <= start || end - start > MAX_WINDOW_SECONDS) return null
        val rows = buildList {
            val source = json.optJSONArray("c") ?: JSONArray()
            for (index in 0 until minOf(source.length(), MAX_ROWS)) {
                val row = source.optJSONArray(index) ?: continue
                val title = row.optString(0).trim().takeIf { it.isNotEmpty() }?.let { clip(it, 24) }
                val value = row.optString(1).trim().takeIf { it.isNotEmpty() }?.let { clip(it, 120) }
                val mood = moodOf(row.optString(2), row.optString(3))
                if (title != null && value != null && mood != null) add(ConditionItem(title, value, mood))
            }
        }
        val reason = text(json, "r", 200).orEmpty()
        val outlook = readMood(json.optJSONArray("o")) ?: WindowMood("🤔", "Shared window")
        val confidence = readMood(json.optJSONArray("f")) ?: WindowMood("🤔", "Limited confidence")
        val lat = if (json.has("la") && json.has("lo")) json.optDouble("la", Double.NaN) else Double.NaN
        val lon = if (json.has("la") && json.has("lo")) json.optDouble("lo", Double.NaN) else Double.NaN
        val place = lat.isFinite() && lon.isFinite() && abs(lat) <= 90 && abs(lon) <= 180
        val snapshot = "Shared with you: these conditions were captured when the window was shared and may have changed. Search from Home for a live forecast."
        return Recommendation(
            name = name, area = area, rating = 0,
            time = WindowEvaluation.formatWindow(Instant.ofEpochSecond(start), Instant.ofEpochSecond(end)),
            distance = "Shared with you · forecast may have changed",
            reasons = listOfNotNull(reason.ifEmpty { null }), boat = json.optInt("b", 0) == 1,
            startsAtEpochSeconds = start, durationHours = ((end - start) / 3600.0).roundToInt().coerceAtLeast(1),
            summary = reason,
            conditions = rows.map { "${it.title}: ${it.value.replace('\n', ' ')} · ${it.mood.emoji} ${it.mood.label}" },
            assessment = WindowAssessment(outlook, rows, confidence, details = listOf(snapshot)),
            latitude = if (place) lat else Double.NaN, longitude = if (place) lon else Double.NaN
        )
    }

    private fun reasonOf(window: Recommendation): String? =
        window.reasons.firstOrNull { it.isNotBlank() }?.trim() ?: window.summary.trim().takeIf { it.isNotEmpty() }

    private fun mood(value: WindowMood) = JSONArray().put(clip(value.emoji, 8)).put(clip(value.label, 40))

    private fun readMood(array: JSONArray?): WindowMood? = array?.let { moodOf(it.optString(0), it.optString(1)) }

    private fun moodOf(emoji: String, label: String): WindowMood? {
        val symbol = emoji.trim()
        val name = label.trim()
        return if (symbol.isEmpty() || name.isEmpty()) null else WindowMood(clip(symbol, 8), clip(name, 40))
    }

    private fun text(json: JSONObject, key: String, max: Int): String? =
        json.optString(key).replace(unsafe, " ").replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }?.let { clip(it, max) }

    private fun round4(value: Double) = (value * 10_000).roundToLong() / 10_000.0

    /** At most [max] characters (code points), ending in an ellipsis when something was cut. */
    private fun clip(value: String, max: Int): String {
        val text = value.trim()
        if (text.codePointCount(0, text.length) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, max - 1)).trimEnd() + "…"
    }
}
