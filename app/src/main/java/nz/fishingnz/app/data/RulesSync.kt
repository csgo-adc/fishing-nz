package nz.fishingnz.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nz.fishingnz.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps every MPI area's rules and the species pictures on the phone, so the rules page works on a boat with no
 * signal. The service's /v1/rules/manifest gives a version for each area and a hash for each picture; only what
 * changed since the last sync is downloaded. It runs when the app opens (at most every 12 hours) and when the rules
 * page opens. The iPhone app does the same in `RulesOffline.swift`.
 */
object RulesSync {
    private const val STATE = "rules_sync.json"
    private const val CATALOG = "species_catalog.json"
    private val gap: Duration = Duration.ofHours(12)
    private val mutex = Mutex()
    @Volatile private var appContext: Context? = null
    @Volatile private var builtIn: JSONObject? = null
    @Volatile private var catalogCache: SpeciesCatalog? = null
    private val _revision = MutableStateFlow(0)
    /** Goes up whenever a sync saved new rules or pictures, so an open rules page can reload. */
    val revision: StateFlow<Int> = _revision

    val baseUrl: String get() = BuildConfig.FISH_ID_API_BASE_URL.trimEnd('/').ifBlank { "https://fishing.fishnz.space" }

    fun initialize(context: Context) { appContext = context.applicationContext }

    private fun builtIn(): JSONObject? = builtIn ?: runCatching {
        appContext?.assets?.open("offline_rules.json")?.bufferedReader()?.use { JSONObject(it.readText()) }
    }.getOrNull()?.also { builtIn = it }

    /** An area's rules from the copy built into the app, with the day that copy was made. */
    fun builtInRule(areaId: String): Pair<JSONObject, Instant?>? {
        val bundle = builtIn() ?: return null
        val rules = bundle.getJSONArray("rules")
        val item = (0 until rules.length()).map(rules::getJSONObject).firstOrNull { it.optString("area_id") == areaId } ?: return null
        val made = runCatching { LocalDate.parse(bundle.optString("generated_at")).atStartOfDay(ZoneId.of("Pacific/Auckland")).toInstant() }.getOrNull()
        return item to made
    }

    /** The species catalogue: the last one synced, else the one built into the app. */
    fun catalog(): SpeciesCatalog? = catalogCache ?: (OfflineCache.read(CATALOG)?.first?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: builtIn()?.optJSONObject("species"))?.let { runCatching { SpeciesCatalog.parse(it) }.getOrNull() }?.also { catalogCache = it }

    /** The picture kept on the phone, or null before it has been downloaded. */
    fun pictureFile(picture: SpeciesPicture): File? = OfflineCache.file("species/${picture.file}")

    /** The picture, downloading it now if the phone doesn't have it yet; null with no signal. */
    suspend fun picture(picture: SpeciesPicture): File? = pictureFile(picture) ?: withContext(Dispatchers.IO) {
        runCatching { savePicture(picture) }.getOrNull()
    }

    /** When the last sync finished, or null before the first. */
    fun lastSynced(): Instant? = readState().syncedAt

    /**
     * Downloads changed areas and pictures. Without [force] it does nothing within 12 hours of the last full sync.
     * Returns true when something new was saved.
     */
    suspend fun sync(force: Boolean = false): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val state = readState()
            if (!force && state.syncedAt?.let { Duration.between(it, Instant.now()) < gap } == true) return@withContext false
            val manifest = runCatching { JSONObject(String(get("$baseUrl/v1/rules/manifest"))) }.getOrElse { return@withContext false }
            var changed = false
            var complete = true
            val rules = manifest.getJSONArray("rules")
            val repository = RulesRepository()
            for (index in 0 until rules.length()) {
                val rule = rules.getJSONObject(index)
                val area = rule.getString("area_id")
                val version = rule.getString("version")
                if (state.areas[area] == version && OfflineCache.file("rules_$area.json") != null) continue
                if (runCatching { repository.downloadAndSave(area) }.isSuccess) { state.areas[area] = version; changed = true } else complete = false
            }
            manifest.optJSONObject("species")?.let { species ->
                val catalog = runCatching { SpeciesCatalog.parse(species) }.getOrNull() ?: return@let
                if (catalog.version != catalogCache?.version) {
                    OfflineCache.write(CATALOG, species.toString())
                    catalogCache = catalog
                    changed = true
                }
                catalog.pictures.forEach { picture ->
                    if (state.pictures[picture.file] == picture.sha256 && pictureFile(picture) != null) return@forEach
                    if (runCatching { savePicture(picture) }.getOrNull() != null) { state.pictures[picture.file] = picture.sha256; changed = true } else complete = false
                }
            }
            // A sync cut short by a lost signal is tried again next time instead of waiting 12 hours.
            if (complete) state.syncedAt = Instant.now()
            writeState(state)
            if (changed) _revision.value += 1
            changed
        }
    }

    private fun savePicture(picture: SpeciesPicture): File? {
        val bytes = get("$baseUrl/species/${picture.file}")
        if (sha256(bytes) != picture.sha256) return null
        OfflineCache.writeBytes("species/${picture.file}", bytes)
        return pictureFile(picture)
    }

    private fun get(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "CatchCheckNZ-Android/1.0")
            PrivacyPreferences.analyticsDeviceId()?.let { setRequestProperty("X-Device-Id", it) }
        }
        try {
            if (connection.responseCode !in 200..299) error("Request failed: ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class State(var syncedAt: Instant?, val areas: MutableMap<String, String>, val pictures: MutableMap<String, String>)

    private fun readState(): State {
        val json = OfflineCache.read(STATE)?.first?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        fun map(name: String) = json.optJSONObject(name)?.let { item -> item.keys().asSequence().associateWith { item.getString(it) }.toMutableMap() } ?: mutableMapOf()
        return State(json.optLong("synced_at").takeIf { it > 0 }?.let(Instant::ofEpochMilli), map("areas"), map("pictures"))
    }

    private fun writeState(state: State) {
        OfflineCache.write(STATE, JSONObject()
            .put("synced_at", state.syncedAt?.toEpochMilli() ?: 0)
            .put("areas", JSONObject(state.areas as Map<*, *>))
            .put("pictures", JSONObject(state.pictures as Map<*, *>)).toString())
    }
}
