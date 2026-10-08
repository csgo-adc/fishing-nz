package nz.fishingnz.app.data

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.FirebaseApp

object PrivacyPreferences {
    private const val DEVICE_ID_KEY = "analytics_device_id"
    private var appContext: Context? = null
    fun initialize(context: Context) {
        appContext = context.applicationContext
        FirebaseApp.initializeApp(context)
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(analyticsEnabled())
    }
    fun analyticsEnabled(): Boolean =
        (appContext?.getSharedPreferences("privacy", Context.MODE_PRIVATE)?.getBoolean("analytics_enabled", false) ?: false)

    /**
     * The random id that tells our analytics one install from another. It is made when analytics is first used, is never an
     * advertising or hardware id, and is returned only while analytics is on.
     */
    @Synchronized
    fun analyticsDeviceId(): String? {
        if (!analyticsEnabled()) return null
        val preferences = appContext?.getSharedPreferences("privacy", Context.MODE_PRIVATE) ?: return null
        preferences.getString(DEVICE_ID_KEY, null)?.let { return it }
        val created = java.util.UUID.randomUUID().toString()
        preferences.edit().putString(DEVICE_ID_KEY, created).apply()
        return created
    }

    @Synchronized
    fun setAnalyticsEnabled(enabled: Boolean) {
        val context = appContext ?: return
        val preferences = context.getSharedPreferences("privacy", Context.MODE_PRIVATE)
        // Turning analytics off forgets the id and asks the server to erase what it stored for it.
        val forgottenDeviceId = if (enabled) null else preferences.getString(DEVICE_ID_KEY, null)
        val editor = preferences.edit().putBoolean("analytics_enabled", enabled)
        if (!enabled) editor.remove(DEVICE_ID_KEY)
        editor.apply()
        FirebaseAnalytics.getInstance(context).apply {
            setAnalyticsCollectionEnabled(enabled)
            if (!enabled) resetAnalyticsData()
        }
        if (!enabled) Analytics.eraseDevice(forgottenDeviceId)
    }

    fun clearFishPhotos() {
        appContext?.let { java.io.File(it.cacheDir, "fish-photos").deleteRecursively() }
    }
}
