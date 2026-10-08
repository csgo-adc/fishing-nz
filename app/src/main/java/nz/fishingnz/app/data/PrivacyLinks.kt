package nz.fishingnz.app.data

import nz.fishingnz.app.BuildConfig

object PrivacyLinks {
    private val origin get() = BuildConfig.FISH_ID_API_BASE_URL.trimEnd('/').ifBlank { "https://fishing.fishnz.space" }
    val policy get() = "$origin/privacy"
    val forgotPassword get() = "$origin/forgot-password"
}
