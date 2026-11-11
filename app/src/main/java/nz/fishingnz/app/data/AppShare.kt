package nz.fishingnz.app.data

import nz.fishingnz.app.BuildConfig

/**
 * What the Share row on the More page sends: a short invitation and both store pages, so a friend on either phone can
 * get the app. Shared fishing windows end with the same two links ([storeLines]). The iPhone app sends the same text
 * (`AppShare` and `StoreLinks` in SharedWindow.swift).
 */
object AppShare {
    const val SUBJECT = "Fishdays - NZ"
    const val MESSAGE = "Fishdays - NZ helps you plan fishing days around New Zealand with tides, weather, fishing windows and MPI fishing rules."
    const val APP_STORE_URL = "https://apps.apple.com/app/id6820858116"
    val playStoreUrl get() = "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"

    /** "iPhone: …" and "Android: …", one per line. */
    fun storeLines() = "iPhone: $APP_STORE_URL\nAndroid: $playStoreUrl"

    fun text() = "$MESSAGE\n${storeLines()}"
}
