package nz.fishingnz.app.data

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nz.fishingnz.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate

/**
 * Finds a newer version and installs it inside the app (docs/in-app-updates.md). Google Play says whether this phone
 * can get an update; the service's /v1/app/version adds the "what's new" notes and the oldest build that still works.
 * Copies installed from Google Play download the update in the background with Play's in-app update flow and install
 * it when the person leaves the app; other copies open the Play page. The iPhone app does the same in `AppUpdates.swift`.
 */
object AppUpdates {
    private const val PREFS = "app_updates"
    private const val PLAY_STORE = "com.android.vending"
    private val checkGap: Duration = Duration.ofHours(12)
    private const val FALLBACK_STORE_URL = "https://play.google.com/store/apps/details?id=nz.fishingnz.app"

    data class State(
        /** The build this phone can update to, or null when it is up to date. */
        val availableBuild: Int? = null,
        val availableVersion: String? = null,
        val notes: List<String> = emptyList(),
        /** Size of the download in bytes when Play knows it, else 0. */
        val downloadBytes: Long = 0,
        /** Google Play can install it inside the app; otherwise Update opens the Play page. */
        val inApp: Boolean = false,
        val storeUrl: String = FALLBACK_STORE_URL,
        /** This build is older than the service supports: the app shows a full-screen Update instead of Home. */
        val required: Boolean = false,
        val downloading: Boolean = false,
        /** Share of the download done, 0 to 1, while it runs. */
        val progress: Float = 0f,
        /** Downloaded and waiting to install: it installs when the person leaves the app, or now with Restart. */
        val downloaded: Boolean = false,
        /** All three pop-ups for this version were shown, so More carries a dot. */
        val remindersDone: Boolean = false,
    ) {
        val available: Boolean get() = availableBuild != null
    }

    private data class Server(val minimumBuild: Int, val latestBuild: Int?, val storeUrl: String?, val releases: List<Triple<String, Int, List<String>>>)

    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    @Volatile private var appContext: Context? = null
    @Volatile private var manager: AppUpdateManager? = null

    val installedBuild: Int get() = BuildConfig.VERSION_CODE

    private val installListener = InstallStateUpdatedListener { install ->
        when (install.installStatus()) {
            InstallStatus.PENDING, InstallStatus.DOWNLOADING -> {
                val total = install.totalBytesToDownload()
                val share = if (total > 0) install.bytesDownloaded().toFloat() / total else 0f
                _state.value = _state.value.copy(downloading = true, progress = share.coerceIn(0f, 1f))
            }
            InstallStatus.DOWNLOADED -> {
                _state.value = _state.value.copy(downloading = false, downloaded = true, progress = 1f)
                Analytics.track("update_installed", "build" to (_state.value.availableBuild ?: 0), "outcome" to "downloaded")
            }
            InstallStatus.FAILED, InstallStatus.CANCELED -> _state.value = _state.value.copy(downloading = false, progress = 0f)
            else -> Unit
        }
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val preferences = preferences() ?: return
        if (!preferences.contains("first_use_day")) preferences.edit().putLong("first_use_day", today()).apply()
        if (manager == null && installedFromGooglePlay(context)) {
            manager = runCatching { AppUpdateManagerFactory.create(context.applicationContext) }.getOrNull()?.also { it.registerListener(installListener) }
        }
    }

    private fun preferences() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today(): Long = LocalDate.now().toEpochDay()

    private fun installedFromGooglePlay(context: Context): Boolean = runCatching {
        val packages = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) packages.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") packages.getInstallerPackageName(context.packageName)
        installer == PLAY_STORE
    }.getOrDefault(false)

    /** Ask Play and the service again, at most every 12 hours unless [force]. */
    suspend fun check(language: String, force: Boolean = false) = mutex.withLock {
        val preferences = preferences() ?: return@withLock
        val now = System.currentTimeMillis()
        if (!force && now - preferences.getLong("checked_at", 0) < checkGap.toMillis()) return@withLock
        val server = withContext(Dispatchers.IO) { runCatching { fetchServer(language) }.getOrNull() }
        val play = manager?.let { runCatching { it.requestAppUpdateInfo() }.getOrNull() }
        val installed = installedBuild
        var available: Int? = null
        var inApp = false
        var downloaded = false
        var bytes = 0L
        if (play != null) {
            val availability = play.updateAvailability()
            if (availability == UpdateAvailability.UPDATE_AVAILABLE || availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                available = play.availableVersionCode().takeIf { it > installed }
                inApp = play.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) || play.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
                bytes = play.totalBytesToDownload()
            }
            downloaded = play.installStatus() == InstallStatus.DOWNLOADED
        } else if (manager == null) {
            // Not installed from Google Play (a test copy): offer the Play page when the service lists a newer build.
            available = server?.latestBuild?.takeIf { it > installed }
        }
        val release = server?.releases?.firstOrNull { it.second == available }
        if (server != null) preferences.edit().putLong("checked_at", now).apply()
        _state.value = _state.value.copy(
            availableBuild = available,
            availableVersion = release?.first,
            notes = release?.third.orEmpty(),
            downloadBytes = bytes,
            inApp = inApp,
            storeUrl = server?.storeUrl ?: FALLBACK_STORE_URL,
            // Only a fresh answer from the service can require an update, so a phone offline is never locked out.
            required = server != null && UpdatePolicy.required(installed, server.minimumBuild),
            downloaded = downloaded || _state.value.downloaded,
            remindersDone = available != null && UpdatePolicy.remindersDone(readRecord(), available, installed),
        )
    }

    private fun fetchServer(language: String): Server {
        val base = BuildConfig.FISH_ID_API_BASE_URL.trimEnd('/').ifBlank { "https://fishing.fishnz.space" }
        val connection = URL("$base/v1/app/version?platform=android&lang=${URLEncoder.encode(language, "UTF-8")}").openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        try {
            check(connection.responseCode == 200) { "Version check failed: ${connection.responseCode}" }
            val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val releases = body.optJSONArray("releases")?.let { list ->
                (0 until list.length()).map { index ->
                    val item = list.getJSONObject(index)
                    val notes = item.optJSONArray("notes")?.let { n -> (0 until n.length()).map(n::getString) }.orEmpty()
                    Triple(item.optString("version"), item.optInt("build"), notes)
                }
            }.orEmpty()
            return Server(
                minimumBuild = body.optInt("minimum_build", 1),
                latestBuild = body.optJSONObject("latest")?.optInt("build"),
                storeUrl = body.optString("store_url").takeIf { it.startsWith("https://") },
                releases = releases,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readRecord(): UpdatePromptRecord {
        val preferences = preferences() ?: return UpdatePromptRecord()
        return UpdatePromptRecord(
            build = preferences.getInt("prompt_build", 0),
            count = preferences.getInt("prompt_count", 0),
            lastShownDay = preferences.getLong("prompt_day", Long.MIN_VALUE),
        )
    }

    /**
     * Called when Home could show the pop-up now. Returns which pop-up this is (1 to 3) and records it, or null when
     * the rules say not today.
     */
    fun claimPrompt(): Int? {
        val current = _state.value
        val build = current.availableBuild ?: return null
        if (current.required || current.downloading || current.downloaded) return null
        val preferences = preferences() ?: return null
        val today = today()
        val record = readRecord()
        val lastNotice = preferences.getLong("notice_day", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        if (!UpdatePolicy.shouldPrompt(record, build, installedBuild, today, preferences.getLong("first_use_day", today), lastNotice)) return null
        val next = UpdatePolicy.shown(record, build, today)
        preferences.edit().putInt("prompt_build", next.build).putInt("prompt_count", next.count)
            .putLong("prompt_day", next.lastShownDay).putLong("notice_day", today).apply()
        _state.value = current.copy(remindersDone = UpdatePolicy.remindersDone(next, build, installedBuild))
        Analytics.track("update_prompt_shown", "build" to build, "prompt_number" to next.count)
        return next.count
    }

    /** Another pop-up was shown today (a shared window, say), so the update pop-up waits for another day. */
    fun noteOtherNotice() {
        preferences()?.edit()?.putLong("notice_day", today())?.apply()
    }

    fun later() {
        Analytics.track("update_prompt_answered", "build" to (_state.value.availableBuild ?: 0), "outcome" to "later")
    }

    /**
     * Update tapped on the pop-up, the More row or the required screen. Play's own sheet confirms the download; the
     * download then runs in the background. Without Play's in-app flow the Play page opens instead.
     */
    suspend fun startUpdate(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val current = _state.value
        Analytics.track("update_prompt_answered", "build" to (current.availableBuild ?: 0), "outcome" to "update", "required" to current.required)
        val updates = manager
        if (current.downloaded && updates != null) { updates.completeUpdate(); return }
        // An update info can start a flow only once, so ask Play afresh each time.
        val info = updates?.let { runCatching { it.requestAppUpdateInfo() }.getOrNull() }
        val type = when {
            info == null -> null
            current.required && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
            info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) -> AppUpdateType.FLEXIBLE
            info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE) -> AppUpdateType.IMMEDIATE
            else -> null
        }
        val started = info != null && type != null && runCatching {
            updates.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(type).build())
        }.getOrDefault(false)
        if (!started) openStore(activity, current.storeUrl)
    }

    /** The result of Play's confirmation sheet. */
    fun onFlowResult(resultCode: Int) {
        val accepted = resultCode == Activity.RESULT_OK
        Analytics.track("update_installed", "build" to (_state.value.availableBuild ?: 0), "outcome" to if (accepted) "accepted" else "declined")
        if (accepted) _state.value = _state.value.copy(downloading = true)
    }

    /** On return to the app: pick up an update downloaded meanwhile, or resume a required update Play had started. */
    suspend fun resume(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val updates = manager ?: return
        val info = runCatching { updates.requestAppUpdateInfo() }.getOrNull() ?: return
        if (info.installStatus() == InstallStatus.DOWNLOADED) _state.value = _state.value.copy(downloaded = true, downloading = false)
        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS && _state.value.required) {
            runCatching { updates.startUpdateFlowForResult(info, launcher, AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()) }
        }
    }

    /** Install a downloaded update now: Play restarts the app on the new version. */
    fun restartNow() {
        manager?.completeUpdate()
    }

    /** When the person leaves the app, a downloaded update installs quietly in the background. */
    fun installIfDownloaded() {
        if (_state.value.downloaded) manager?.completeUpdate()
    }

    private fun openStore(activity: Activity, url: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${activity.packageName}")).setPackage(PLAY_STORE)
        try { activity.startActivity(market) }
        catch (_: ActivityNotFoundException) {
            runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }
}
