package nz.fishingnz.app.ui

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import nz.fishingnz.app.model.Recommendation

/** Opens the device calendar's event editor so the user can review and save the trip. */
internal fun openTripInCalendar(context: Context, trip: Recommendation): Boolean {
    val intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, "Fishing at ${trip.name}")
        putExtra(CalendarContract.Events.EVENT_LOCATION, "${trip.name}, ${trip.area}, New Zealand")
        putExtra(
            CalendarContract.Events.DESCRIPTION,
            "Fishing plan from CatchCheck NZ. Check the latest forecast, local access and fishing rules before you go."
        )
        if (trip.startsAtEpochSeconds > 0 && trip.durationHours > 0) {
            val startMillis = trip.startsAtEpochSeconds * 1_000
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + trip.durationHours * 3_600_000L)
        }
    }
    return runCatching { context.startActivity(intent) }.isSuccess
}
