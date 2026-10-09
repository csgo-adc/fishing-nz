package nz.fishingnz.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import nz.fishingnz.app.data.SharedWindowLink
import nz.fishingnz.app.model.Recommendation

/** Only a window with a start time can be shared; a spot saved from the map has none. */
internal fun canShareWindow(window: Recommendation) = window.startsAtEpochSeconds > 0 && window.durationHours > 0

/**
 * Opens the share sheet with where and when, and [url] (see ShareService.linkFor). In the app the link opens this window;
 * anywhere else it opens a web page that shows it and offers the app. Returns false when no app can share text.
 */
internal fun shareWindow(context: Context, window: Recommendation, url: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Fishing window · ${window.name}")
        putExtra(Intent.EXTRA_TEXT, SharedWindowLink.text(window, url))
    }
    return try {
        context.startActivity(Intent.createChooser(send, "Share this fishing window"))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
