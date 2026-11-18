package nz.fishingnz.app.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open-Meteo forecast requests, with a second server to fall back on.
 *
 * api.open-meteo.com sends New Zealand to a single server, and since 13 November 2026 that server has often left new
 * connections hanging until they time out (github.com/open-meteo/open-meteo/issues/2329), so the forecast failed to
 * load. historical-forecast-api.open-meteo.com runs on a different server and answers the same `/v1/forecast` request
 * with the same forecast, so a forecast request that fails is tried there as well, and that server goes first for
 * half an hour after a failure. Marine and every other request go out unchanged, with no second try.
 */
internal object OpenMeteo {
    const val PRIMARY_HOST = "api.open-meteo.com"
    const val FALLBACK_HOST = "historical-forecast-api.open-meteo.com"
    private const val PREFER_FALLBACK_MILLIS = 30 * 60 * 1000L
    // The main server gives up sooner when the second server follows it, so a hang costs seconds, not half a minute.
    // The second server always gets the caller's full limits: a request it has not answered lately can take 15 s.
    private const val QUICK_CONNECT_MILLIS = 6_000
    private const val QUICK_READ_MILLIS = 10_000

    @Volatile private var primaryFailedAt: Long? = null

    /** The URLs to try for [url], in order. */
    fun candidates(url: String, now: Long = System.currentTimeMillis()): List<String> {
        val parsed = URL(url)
        if (parsed.protocol != "https" || parsed.host != PRIMARY_HOST || parsed.path != "/v1/forecast") return listOf(url)
        val fallback = URL("https", FALLBACK_HOST, parsed.file).toString()
        val failedAt = primaryFailedAt
        return if (failedAt != null && now - failedAt in 0 until PREFER_FALLBACK_MILLIS) listOf(fallback, url) else listOf(url, fallback)
    }

    /** The body of the first 2xx answer for [url]; throws the last [IOException] when no server gives one. */
    fun getText(
        url: String, connectTimeoutMillis: Int, readTimeoutMillis: Int,
        now: () -> Long = System::currentTimeMillis,
        download: (url: String, connectTimeoutMillis: Int, readTimeoutMillis: Int) -> String = ::download,
    ): String {
        val urls = candidates(url, now())
        var failure: IOException? = null
        for ((index, candidate) in urls.withIndex()) {
            val primary = URL(candidate).host == PRIMARY_HOST
            try {
                val text = if (primary && index < urls.lastIndex) download(candidate, minOf(connectTimeoutMillis, QUICK_CONNECT_MILLIS), minOf(readTimeoutMillis, QUICK_READ_MILLIS))
                    else download(candidate, connectTimeoutMillis, readTimeoutMillis)
                if (primary) primaryFailedAt = null
                return text
            } catch (error: IOException) {
                if (primary && urls.size > 1) primaryFailedAt = now()
                failure?.let(error::addSuppressed)
                failure = error
            }
        }
        throw failure ?: IOException("Forecast unavailable.")
    }

    /** Forgets an earlier failure; for tests. */
    internal fun reset() { primaryFailedAt = null }

    private fun download(url: String, connectTimeoutMillis: Int, readTimeoutMillis: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = connectTimeoutMillis; readTimeout = readTimeoutMillis
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Forecast service returned $status.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
