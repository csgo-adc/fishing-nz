package nz.fishingnz.app.data

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics

object PrivacyPreferences {
    private var appContext: Context? = null
    fun initialize(context: Context) {
        appContext = context.applicationContext
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(analyticsEnabled())
    }
    fun analyticsEnabled(): Boolean = appContext?.getSharedPreferences("privacy", Context.MODE_PRIVATE)
        ?.getBoolean("analytics_enabled", false) ?: false

    fun setAnalyticsEnabled(enabled: Boolean) {
        val context = appContext ?: return
        context.getSharedPreferences("privacy", Context.MODE_PRIVATE).edit().putBoolean("analytics_enabled", enabled).apply()
        FirebaseAnalytics.getInstance(context).apply {
            setAnalyticsCollectionEnabled(enabled)
            if (!enabled) resetAnalyticsData()
        }
    }

    fun clearFishPhotos() {
        appContext?.let { java.io.File(it.cacheDir, "fish-photos").deleteRecursively() }
    }
}
