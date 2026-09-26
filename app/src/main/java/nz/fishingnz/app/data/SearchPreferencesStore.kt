package nz.fishingnz.app.data

import android.content.Context

/** The last window-search scope is shared across app launches. */
object SearchPreferencesStore {
    private const val PREFS_NAME = "fishing_window_search"
    private const val MODE_KEY = "location_mode"
    private const val STATION_KEY = "station_id"
    private const val MANUAL_ORIGIN_KEY = "manual_origin"
    private const val RADIUS_KEY = "radius_km"
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    fun savedMode(): String? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getString(MODE_KEY, null)

    fun savedStationId(): String? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getString(STATION_KEY, null)

    fun savedManualOriginName(): String? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getString(MANUAL_ORIGIN_KEY, null)

    fun savedRadiusKm(): Int? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?.getInt(RADIUS_KEY, 100)?.takeIf { it in listOf(10, 30, 50, 100, 200, 300, 400, 500) }

    fun save(mode: String, stationId: String?) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(MODE_KEY, mode)
            ?.putString(STATION_KEY, stationId)
            ?.apply()
    }

    fun saveManualOrigin(name: String?) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(MANUAL_ORIGIN_KEY, name)
            ?.apply()
    }

    fun saveRadiusKm(km: Int) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putInt(RADIUS_KEY, km)
            ?.apply()
    }
}
