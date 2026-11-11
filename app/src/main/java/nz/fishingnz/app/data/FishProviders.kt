package nz.fishingnz.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import nz.fishingnz.app.BuildConfig
import nz.fishingnz.app.model.FishProviderInfo
import nz.fishingnz.app.model.PhotoConsentText
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The AI services set in the CMS, fetched from the Fishdays - NZ service and kept on the phone, so the photo consent
 * names the services photos really go to, even with no signal. The iPhone app has `FishProviders` in FishingRepository.swift.
 */
object FishProviders {
    private const val PREFS = "fish_providers"
    private const val KEY = "last_answer"
    private var appContext: Context? = null
    private val current = MutableStateFlow(FishProviderInfo.DEFAULT)
    val state: StateFlow<FishProviderInfo> = current.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val saved = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null)
        saved?.let { runCatching { PhotoConsentText.parse(JSONObject(it)) }.getOrNull() }?.let { current.value = it }
    }

    /** Asks the service for the current list; keeps the last one when it can't be reached. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val base = BuildConfig.FISH_ID_API_BASE_URL.trimEnd('/').ifBlank { "https://fishing.fishnz.space" }
        runCatching {
            val connection = (URL("$base/v1/fish/providers").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000; readTimeout = 8_000; setRequestProperty("accept", "application/json")
            }
            try {
                if (connection.responseCode != 200) return@runCatching
                val text = connection.inputStream.bufferedReader().use { it.readText() }.take(8_000)
                val info = PhotoConsentText.parse(JSONObject(text)) ?: return@runCatching
                current.value = info
                appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(KEY, text)?.apply()
            } finally { connection.disconnect() }
        }
    }
}
