package nz.fishingnz.app.data

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import nz.fishingnz.app.MainActivity
import nz.fishingnz.app.R
import nz.fishingnz.app.model.MoonPhase
import nz.fishingnz.app.model.TideStation
import nz.fishingnz.app.model.tideStations
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The Tides widget's words. The iPhone widget shows the same in `TideWidget.swift`. */
object TideWidgetText {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val dayClock = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

    /** "Next high 2:10 PM · 2.4 m" and "Then low 8:25 PM · 0.6 m"; a time on another day names the day. */
    fun nextTides(predictions: List<LinzPrediction>, now: Instant): Pair<String, String>? {
        val upcoming = predictions.filter { it.at.isAfter(now) }.sortedBy { it.at }
        if (upcoming.isEmpty()) return null
        val today = now.atZone(zone).toLocalDate()
        fun describe(prefix: String, item: LinzPrediction): String {
            val at = item.at.atZone(zone)
            val time = if (at.toLocalDate() == today) at.format(clock) else at.format(dayClock)
            return "$prefix ${if (item.high) "high" else "low"} $time · ${String.format(Locale.US, "%.1f", item.height)} m"
        }
        return describe("Next", upcoming[0]) to (upcoming.getOrNull(1)?.let { describe("Then", it) } ?: "")
    }

    /** "🌗 First quarter · Neap tides", with the wind when it loaded: "· Wind 12 km/h, 14°C". */
    fun extra(day: LocalDate, weather: nz.fishingnz.app.model.WeatherState?): String {
        val moon = MoonPhase.forDay(day)
        return listOfNotNull("${moon.emoji} ${moon.name}", moon.tideRange.label, weather?.let { "Wind ${it.wind}, ${it.temperature}" }).joinToString(" · ")
    }
}

/** The station the widget shows: the one last chosen or found on the Tide tab. */
object TideWidgetStation {
    private const val PREFS = "tide_widget"
    @Volatile private var appContext: Context? = null
    fun initialize(context: Context) { appContext = context.applicationContext }

    fun current(context: Context? = appContext): TideStation {
        val id = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("station", null)
        return tideStations.firstOrNull { it.id == id } ?: tideStations.first { it.name == "Auckland" }
    }

    /** Remembers [station] and refreshes any widgets on the home screen. */
    fun set(station: TideStation) {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("station", null) == station.id) return
        prefs.edit().putString("station", station.id).apply()
        TideWidget.refresh(context)
    }
}

/** Home-screen widget: next high and low tide, moon and wind for the Tide tab's station. Tapping it opens the Tide tab. */
class TideWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        const val OPEN_TIDE = "nz.fishingnz.app.OPEN_TIDE"
        private const val WORK = "tide-widget"

        fun refresh(context: Context) {
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TideWidget::class.java))
            if (ids.isEmpty()) return
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<TideWidgetWorker>().build())
        }

        internal fun views(context: Context, station: TideStation, next: Pair<String, String>?, extra: String, updated: String): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_tide).apply {
                setTextViewText(R.id.widget_station, "${station.name} tides")
                setTextViewText(R.id.widget_next, next?.first ?: "Tides unavailable")
                setTextViewText(R.id.widget_then, next?.second ?: "Open the app to try again")
                setTextViewText(R.id.widget_extra, extra)
                setTextViewText(R.id.widget_updated, updated)
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 1,
                    Intent(context, MainActivity::class.java).setAction(OPEN_TIDE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
    }
}

/** Loads the widget's tides (from the saved LINZ table when offline) and the current wind, then redraws every widget. */
class TideWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        OfflineCache.initialize(applicationContext)
        val zone = ZoneId.of("Pacific/Auckland")
        val station = TideWidgetStation.current(applicationContext)
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val tides = runCatching { LinzTideSource.predictions(station, today, today.plusDays(1)) }.getOrNull()
        val weather = runCatching { FishingRepository().conditions(nz.fishingnz.app.model.GeoPoint(station.latitude, station.longitude)).first }.getOrNull()
        val views = TideWidget.views(applicationContext, station, tides?.let { TideWidgetText.nextTides(it, now) },
            TideWidgetText.extra(today, weather), "Updated ${now.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}")
        val manager = AppWidgetManager.getInstance(applicationContext)
        manager.updateAppWidget(ComponentName(applicationContext, TideWidget::class.java), views)
        return Result.success()
    }
}
