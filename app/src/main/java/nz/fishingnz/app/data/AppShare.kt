package nz.fishingnz.app.data

import nz.fishingnz.app.BuildConfig

/** What the Share row on the More page sends: a short invitation and the app's Google Play page. */
object AppShare {
    const val SUBJECT = "Fishdays - NZ"
    const val MESSAGE = "Fishdays - NZ helps you plan fishing days around New Zealand with tides, weather, fishing windows and MPI fishing rules."
    val playStoreUrl get() = "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"
    fun text() = "$MESSAGE $playStoreUrl"
}
