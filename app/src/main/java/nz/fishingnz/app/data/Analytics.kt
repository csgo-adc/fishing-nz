package nz.fishingnz.app.data

import android.os.Build
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nz.fishingnz.app.BuildConfig

/**
 * Behaviour tracking for Fishdays - NZ. It does nothing unless the person turned on optional analytics, and it works
 * whether or not they are signed in. Events are queued and sent in batches; a signed-in session links them to the account.
 *
 * Only names and properties the server allows are kept (see server/fishial-proxy/src/analytics.ts). Never pass coordinates,
 * email addresses, photos or typed text.
 */
object Analytics {
    private const val FIRST_UPLOAD_DELAY_MS = 2_000L
    private const val UPLOAD_INTERVAL_MS = 60_000L

    private val buffer = AnalyticsBuffer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uploadLock = Mutex()
    private val repository by lazy { FishingRepository() }
    @Volatile private var scheduled: Job? = null
    @Volatile private var lastUploadAt = 0L

    fun track(name: String, vararg props: Pair<String, Any>) {
        if (!PrivacyPreferences.analyticsEnabled()) return
        buffer.add(AnalyticsEvent(name, props.toMap(), System.currentTimeMillis()))
        schedule()
    }

    /** Send what is queued now, for example when the app goes to the background. */
    fun flush() {
        if (!PrivacyPreferences.analyticsEnabled()) {
            // Events queued under an earlier consent must never be sent after it was withdrawn.
            buffer.clear()
            return
        }
        scope.launch { upload() }
    }

    /**
     * Called when analytics is switched off: drop anything queued and, if an id had been made, ask the server to erase
     * everything it stored for that device.
     */
    fun eraseDevice(deviceId: String?) {
        buffer.clear()
        scheduled?.cancel()
        if (deviceId != null) scope.launch { runCatching { repository.eraseAnalyticsDevice(deviceId) } }
    }

    /** A short, non-identifying reason for a failed request. */
    fun errorCode(error: Throwable): String =
        (error as? AccountRequestException)?.let { it.code ?: "http_${it.statusCode}" } ?: "network"

    private fun schedule() {
        if (scheduled?.isActive == true) return
        val wait = (lastUploadAt + UPLOAD_INTERVAL_MS - System.currentTimeMillis()).coerceIn(FIRST_UPLOAD_DELAY_MS, UPLOAD_INTERVAL_MS)
        scheduled = scope.launch { delay(wait); upload() }
    }

    private suspend fun upload() {
        uploadLock.withLock {
            while (PrivacyPreferences.analyticsEnabled()) {
                val device = device() ?: return@withLock
                val batch = buffer.take(AnalyticsPayload.MAX_EVENTS_PER_UPLOAD)
                if (batch.isEmpty()) return@withLock
                try {
                    repository.uploadAnalytics(AnalyticsPayload.build(device, batch, System.currentTimeMillis()))
                    lastUploadAt = System.currentTimeMillis()
                } catch (cancelled: CancellationException) {
                    buffer.putBack(batch)
                    throw cancelled
                } catch (error: AccountRequestException) {
                    // A rejected upload would be rejected again; keep the events only for problems that may clear up.
                    if (error.statusCode == 429 || error.statusCode >= 500) buffer.putBack(batch)
                    return@withLock
                } catch (_: Exception) {
                    buffer.putBack(batch)
                    return@withLock
                }
            }
            // Analytics was switched off while events were queued.
            buffer.clear()
        }
    }

    private fun device(): AnalyticsDevice? {
        val id = PrivacyPreferences.analyticsDeviceId() ?: return null
        return AnalyticsDevice(
            id = id, platform = "android", osVersion = Build.VERSION.RELEASE.orEmpty(), deviceModel = Build.MODEL.orEmpty(),
            appVersion = BuildConfig.VERSION_NAME, locale = Locale.getDefault().toLanguageTag(), timeZone = TimeZone.getDefault().id,
        )
    }
}
