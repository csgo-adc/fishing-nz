package nz.fishingnz.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import nz.fishingnz.app.MainActivity
import nz.fishingnz.app.R
import nz.fishingnz.app.model.Recommendation
import nz.fishingnz.app.model.TripCheck
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Schedules the pre-trip forecast check for the active trip and posts its notification. WorkManager keeps the check
 * across restarts; it runs once there is a connection, at or after the check time.
 */
object TripReminders {
    const val CHANNEL = "trip_checks"
    const val OPEN_TRIPS = "nz.fishingnz.app.OPEN_TRIPS"
    private const val WORK = "trip-check"
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "Trip forecast checks", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "The forecast for your active trip, checked the evening before or 2 hours before it starts."
                })
        }
    }

    fun notificationsAllowed(context: Context? = appContext): Boolean = context != null &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Replaces any earlier check with one for [trip]; returns the check time, or null when none fits. */
    fun schedule(trip: Recommendation, now: Instant = Instant.now()): Instant? {
        val context = appContext ?: return null
        val at = TripCheck.checkTime(Instant.ofEpochSecond(trip.startsAtEpochSeconds), now)
        val work = WorkManager.getInstance(context)
        if (at == null || trip.startsAtEpochSeconds <= 0) { work.cancelUniqueWork(WORK); return null }
        work.enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<TripCheckWorker>()
            .setInitialDelay(Duration.between(now, at).toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build())
        return at
    }

    fun cancel() { appContext?.let { WorkManager.getInstance(it).cancelUniqueWork(WORK) } }

    internal fun post(context: Context, title: String, body: String) {
        if (!notificationsAllowed(context)) return
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(OPEN_TRIPS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(1, notification) } catch (_: SecurityException) { /* Permission withdrawn. */ }
    }
}

/** Re-evaluates the active trip's hours with the latest forecast and the current plan preferences. */
class TripCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TripStore.initialize(applicationContext)
        SearchPreferencesStore.initialize(applicationContext)
        OfflineCache.initialize(applicationContext)
        val trip = TripStore.load().second ?: return Result.success()
        if (trip.startsAtEpochSeconds <= Instant.now().epochSecond) return Result.success()
        val latest = try {
            RecommendationEngine().recheck(trip, nz.fishingnz.app.model.WindowPriority.saved(SearchPreferencesStore.savedPriority()),
                SearchPreferencesStore.savedLandPreferences(), nz.fishingnz.app.model.TidePhase.saved(SearchPreferencesStore.savedTidePhase()))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) {
            if (runAttemptCount < 2) return Result.retry() else null
        }
        val (title, body) = TripCheck.message(trip, latest)
        TripReminders.post(applicationContext, title, body)
        return Result.success()
    }
}
