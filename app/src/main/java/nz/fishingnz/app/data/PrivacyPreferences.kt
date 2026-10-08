package nz.fishingnz.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.FirebaseApp

object PrivacyPreferences {
    private const val FILE = "privacy"
    private const val DEVICE_ID_KEY = "analytics_device_id"
    // Our own anonymous usage statistics. Absent until the one-time notice is answered or the switch is used.
    private const val USAGE_KEY = "usage_stats"
    // Google Analytics (Firebase). Opt-in: off unless the person switches it on. This is the key the old single switch used.
    private const val FIREBASE_KEY = "analytics_enabled"
    private const val MIGRATED_KEY = "consent_migrated"
    private var appContext: Context? = null

    private fun preferences(): SharedPreferences? = appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        migrateOldSwitch()
        FirebaseApp.initializeApp(context)
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(firebaseEnabled())
    }

    /** Carry an explicit choice made on the old single switch over to the new setting, once. */
    @Synchronized
    private fun migrateOldSwitch() {
        val preferences = preferences() ?: return
        if (preferences.getBoolean(MIGRATED_KEY, false)) return
        val editor = preferences.edit().putBoolean(MIGRATED_KEY, true)
        val choice = AnalyticsConsent.migratedChoice(
            usageStats = if (preferences.contains(USAGE_KEY)) preferences.getBoolean(USAGE_KEY, false) else null,
            oldSwitch = if (preferences.contains(FIREBASE_KEY)) preferences.getBoolean(FIREBASE_KEY, false) else null,
        )
        if (choice != null) editor.putBoolean(USAGE_KEY, choice)
        editor.apply()
    }

    private fun usageChoice(): Boolean? =
        preferences()?.takeIf { it.contains(USAGE_KEY) }?.getBoolean(USAGE_KEY, false)

    /** True while the one-time notice about our usage statistics has not been answered. */
    fun analyticsNoticeNeeded(): Boolean = AnalyticsConsent.resolve(usageChoice()).needsNotice

    /** Our own usage statistics: on by default, but only once the notice has been answered. */
    fun analyticsEnabled(): Boolean = AnalyticsConsent.resolve(usageChoice()).enabled

    /** Google Analytics (Firebase) stays opt-in. */
    fun firebaseEnabled(): Boolean = preferences()?.getBoolean(FIREBASE_KEY, false) ?: false

    /**
     * The random id that tells our analytics one install from another. It is made when analytics is first used, is never an
     * advertising or hardware id, and is returned only while analytics is on.
     */
    @Synchronized
    fun analyticsDeviceId(): String? {
        if (!analyticsEnabled()) return null
        val preferences = preferences() ?: return null
        preferences.getString(DEVICE_ID_KEY, null)?.let { return it }
        val created = java.util.UUID.randomUUID().toString()
        preferences.edit().putString(DEVICE_ID_KEY, created).apply()
        return created
    }

    @Synchronized
    fun setAnalyticsEnabled(enabled: Boolean) {
        val preferences = preferences() ?: return
        // Turning it off forgets the id and asks the server to erase what it stored for it.
        val forgottenDeviceId = if (enabled) null else preferences.getString(DEVICE_ID_KEY, null)
        val editor = preferences.edit().putBoolean(USAGE_KEY, enabled)
        if (!enabled) editor.remove(DEVICE_ID_KEY)
        editor.apply()
        if (!enabled) Analytics.eraseDevice(forgottenDeviceId)
    }

    fun setFirebaseEnabled(enabled: Boolean) {
        val context = appContext ?: return
        preferences()?.edit()?.putBoolean(FIREBASE_KEY, enabled)?.apply()
        FirebaseAnalytics.getInstance(context).apply {
            setAnalyticsCollectionEnabled(enabled)
            if (!enabled) resetAnalyticsData()
        }
    }

    fun clearFishPhotos() {
        appContext?.let { java.io.File(it.cacheDir, "fish-photos").deleteRecursively() }
    }
}
