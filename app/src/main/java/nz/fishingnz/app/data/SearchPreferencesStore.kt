package nz.fishingnz.app.data

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import nz.fishingnz.app.model.*

/** The last window-search scope is shared across app launches. */
object SearchPreferencesStore {
    private const val PREFS_NAME = "fishing_window_search"
    private const val MODE_KEY = "location_mode"
    private const val STATION_KEY = "station_id"
    private const val MANUAL_ORIGIN_KEY = "manual_origin"
    private const val RADIUS_KEY = "radius_km"
    private const val BOAT_KEY = "boat_fishing"
    private const val DATE_LABEL_KEY = "date_label"
    private const val DATE_START_KEY = "date_start"
    private const val DATE_END_KEY = "date_end"
    private const val HOURS_MODE_KEY = "hours_mode"
    private const val HOURS_START_KEY = "hours_start"
    private const val HOURS_END_KEY = "hours_end"
    private const val PRIORITY_KEY = "window_priority"
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    fun savedMode(): String? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getString(MODE_KEY, null)

    fun savedStationId(): String? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getString(STATION_KEY, null)

    fun savedManualOriginName(): String? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.getString(MANUAL_ORIGIN_KEY, null)

    fun savedRadiusKm(): Int? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?.getInt(RADIUS_KEY, 100)?.takeIf { it in listOf(10, 30, 50, 100, 200, 300, 400, 500) }

    fun savedBoat(): Boolean = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?.getBoolean(BOAT_KEY, false) ?: false

    fun savedDateLabel(): String? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?.getString(DATE_LABEL_KEY, null)

    fun savedCustomDates(): Pair<LocalDate, LocalDate>? {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return null
        val start = runCatching { LocalDate.parse(prefs.getString(DATE_START_KEY, null)) }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(prefs.getString(DATE_END_KEY, null)) }.getOrNull() ?: return null
        return start to end
    }

    fun savedHoursMode(): String? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?.getString(HOURS_MODE_KEY, null)

    fun savedCustomHours(): Pair<LocalTime, LocalTime>? {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return null
        val start = runCatching { LocalTime.parse(prefs.getString(HOURS_START_KEY, null)) }.getOrNull() ?: return null
        val end = runCatching { LocalTime.parse(prefs.getString(HOURS_END_KEY, null)) }.getOrNull() ?: return null
        return if (start == end) null else start to end
    }

    fun savedPriority(): String? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?.getString(PRIORITY_KEY, null)

    fun savedLandPreferences(): LandPreferences {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return LandPreferences()
        return LandPreferences(
            setting = runCatching { ShoreSetting.valueOf(prefs.getString("shore_setting", "UNKNOWN")!!) }.getOrDefault(ShoreSetting.UNKNOWN),
            priority = runCatching { LandPriority.valueOf(prefs.getString("land_priority", "BALANCED")!!) }.getOrDefault(LandPriority.BALANCED),
            maxBand = prefs.getInt("comfort_band", 1).coerceIn(0, 3),
            arrivalMinutes = prefs.getInt("arrival_minutes", -1).takeIf { it in 0..180 },
            returnMinutes = prefs.getInt("return_minutes", -1).takeIf { it in 0..180 },
            daylightOnly = prefs.getBoolean("daylight_only", false)
        )
    }
    fun saveLandPreferences(value: LandPreferences) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString("shore_setting", value.setting.name)?.putString("land_priority", value.priority.name)
            ?.putInt("comfort_band", value.maxBand)?.putInt("arrival_minutes", value.arrivalMinutes ?: -1)
            ?.putInt("return_minutes", value.returnMinutes ?: -1)?.putBoolean("daylight_only", value.daylightOnly)?.apply()
    }

    fun saveBoat(boat: Boolean) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.putBoolean(BOAT_KEY, boat)?.apply()
    }

    fun saveDate(label: String, start: LocalDate, end: LocalDate) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(DATE_LABEL_KEY, label)?.putString(DATE_START_KEY, start.toString())
            ?.putString(DATE_END_KEY, end.toString())?.apply()
    }

    fun saveHours(mode: String, start: LocalTime? = null, end: LocalTime? = null) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(HOURS_MODE_KEY, mode)?.putString(HOURS_START_KEY, start?.toString())
            ?.putString(HOURS_END_KEY, end?.toString())?.apply()
    }

    fun savePriority(priority: String) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()
            ?.putString(PRIORITY_KEY, priority)?.apply()
    }

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
