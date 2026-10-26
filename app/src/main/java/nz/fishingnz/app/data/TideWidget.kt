package nz.fishingnz.app.data

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
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
import java.time.Duration
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

/**
 * The Tides widget's figure: the tide from 8 hours ago to 16 hours ahead, eased between LINZ highs and lows like the
 * app's curve, with each high and low labelled and a dot at now. The iPhone widget draws the same in `TideWidget.swift`.
 */
object TideWidgetCurve {
    private val zone = ZoneId.of("Pacific/Auckland")
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    val window: Duration = Duration.ofHours(24)
    val before: Duration = Duration.ofHours(8)

    /** The height at [at]; null outside the predictions. */
    fun level(predictions: List<LinzPrediction>, at: Instant): Double? {
        val sorted = predictions.sortedBy { it.at }
        val afterIndex = sorted.indexOfFirst { !it.at.isBefore(at) }
        if (afterIndex < 0) return null
        val after = sorted[afterIndex]
        if (after.at == at) return after.height
        val before = sorted.getOrNull(afterIndex - 1) ?: return null
        val total = Duration.between(before.at, after.at).seconds.toDouble()
        if (total <= 0) return null
        val fraction = Duration.between(before.at, at).seconds / total
        return before.height + (after.height - before.height) * (1 - kotlin.math.cos(Math.PI * fraction)) / 2
    }

    /** "Coming in ↑ · 1.9 m" while the next tide is a high, "Going out ↓ · …" before a low; null without tides. */
    fun trend(predictions: List<LinzPrediction>, now: Instant): String? {
        val next = predictions.filter { it.at.isAfter(now) }.minByOrNull { it.at } ?: return null
        val height = level(predictions, now) ?: return null
        return "${if (next.high) "Coming in ↑" else "Going out ↓"} · ${String.format(Locale.US, "%.1f", height)} m"
    }

    /** Draws the figure at [width] × [height] pixels for the widget's dark background; null without enough tides. */
    fun draw(predictions: List<LinzPrediction>, now: Instant, width: Int, height: Int, density: Float): Bitmap? {
        if (width < 40 || height < 30) return null
        val start = now.minus(before)
        val end = start.plus(window)
        val samples = (0..96).mapNotNull { step -> start.plus(window.dividedBy(96).multipliedBy(step.toLong())).let { at -> level(predictions, at)?.let { at to it } } }
        if (samples.size < 2) return null
        val events = predictions.filter { !it.at.isBefore(start) && !it.at.isAfter(end) }
        val low = samples.minOf { it.second }
        val span = (samples.maxOf { it.second } - low).takeIf { it > 0.05 } ?: 1.0
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt(); textSize = 11 * density; textAlign = Paint.Align.CENTER }
        val labelRoom = text.textSize + 4 * density
        fun x(at: Instant) = width * Duration.between(start, at).seconds.toFloat() / window.seconds
        fun y(level: Double) = labelRoom + (height - 2 * labelRoom) * (1 - ((level - low) / span)).toFloat()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val curve = Path().apply {
            samples.forEachIndexed { index, (at, level) -> if (index == 0) moveTo(x(at), y(level)) else lineTo(x(at), y(level)) }
        }
        val fill = Path(curve).apply {
            lineTo(x(samples.last().first), height.toFloat())
            lineTo(x(samples.first().first), height.toFloat())
            close()
        }
        canvas.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4085B7EB })
        canvas.drawPath(curve, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF85B7EB.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.5f * density; strokeCap = Paint.Cap.ROUND
        })
        events.forEach { event ->
            val label = "${if (event.high) "H" else "L"} ${event.at.atZone(zone).format(clock)}"
            val half = text.measureText(label) / 2
            val ex = x(event.at).coerceIn(half, width - half)
            val ey = y(event.height)
            canvas.drawText(label, ex, if (event.high) ey - 5 * density else ey + text.textSize + 3 * density, text)
        }
        level(predictions, now)?.let { level ->
            canvas.drawLine(x(now), 0f, x(now), height.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x80FFFFFF.toInt(); strokeWidth = 1 * density; pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
            })
            canvas.drawCircle(x(now), y(level), 4.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFAC775.toInt() })
        }
        return bitmap
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

/** Home-screen widget: the tide curve, next tide, moon and wind for the Tide tab's station. Tapping it opens the Tide tab. */
class TideWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    // A resized widget redraws its curve at the new size.
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)

    companion object {
        const val OPEN_TIDE = "nz.fishingnz.app.OPEN_TIDE"
        private const val WORK = "tide-widget"

        fun refresh(context: Context) {
            val ids = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, TideWidget::class.java))
            if (ids.isEmpty()) return
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<TideWidgetWorker>().build())
        }

        internal fun views(context: Context, station: TideStation, next: Pair<String, String>?, trend: String?, curve: Bitmap?,
                           extra: String, updated: String): RemoteViews =
            RemoteViews(context.packageName, R.layout.widget_tide).apply {
                setTextViewText(R.id.widget_station, tr("${station.name} tides"))
                setTextViewText(R.id.widget_trend, tr(trend ?: ""))
                if (curve != null) setImageViewBitmap(R.id.widget_curve, curve)
                setTextViewText(R.id.widget_next, tr(next?.first ?: "Tides unavailable · open the app to try again"))
                setTextViewText(R.id.widget_extra, tr(extra))
                setTextViewText(R.id.widget_updated, tr(updated))
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 1,
                    Intent(context, MainActivity::class.java).setAction(OPEN_TIDE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
    }
}

/** Loads the widget's tides (from the saved LINZ table when offline) and the current wind, then redraws every widget. */
class TideWidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        I18n.initialize(applicationContext)
        OfflineCache.initialize(applicationContext)
        val zone = ZoneId.of("Pacific/Auckland")
        val station = TideWidgetStation.current(applicationContext)
        val now = Instant.now()
        val today = LocalDate.now(zone)
        val tides = runCatching { LinzTideSource.predictions(station, today.minusDays(1), today.plusDays(1)) }.getOrNull()
        val weather = runCatching { FishingRepository().conditions(nz.fishingnz.app.model.GeoPoint(station.latitude, station.longitude)).first }.getOrNull()
        val manager = AppWidgetManager.getInstance(applicationContext)
        val density = applicationContext.resources.displayMetrics.density
        // Each widget gets a curve drawn for its own size; the text around it takes about 100 dp of height.
        manager.getAppWidgetIds(ComponentName(applicationContext, TideWidget::class.java)).forEach { id ->
            val options = manager.getAppWidgetOptions(id)
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 250
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180
            val curve = tides?.let { TideWidgetCurve.draw(it, now, ((widthDp - 28) * density).toInt(), ((heightDp - 100).coerceIn(36, 200) * density).toInt(), density) }
            manager.updateAppWidget(id, TideWidget.views(applicationContext, station, tides?.let { TideWidgetText.nextTides(it, now) },
                tides?.let { TideWidgetCurve.trend(it, now) }, curve, TideWidgetText.extra(today, weather),
                "Updated ${now.atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}"))
        }
        return Result.success()
    }
}
