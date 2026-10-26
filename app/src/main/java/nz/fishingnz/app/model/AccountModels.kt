package nz.fishingnz.app.model

import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class AccountProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val countryCode: String,
    val plan: String,
    // False for a Google or Apple account that never set a password. Treated as true when the service does not say.
    val hasPassword: Boolean = true,
    // ISO time the account was made; empty when the service does not say.
    val createdAt: String = "",
) {
    /** "Member since October 2026", in New Zealand time, or null without a sign-up time. */
    val memberSince: String? get() = runCatching {
        "Member since " + DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).format(Instant.parse(createdAt).atZone(ZoneId.of("Pacific/Auckland")))
    }.getOrNull()
}

data class FishIdentityQuota(val limit: Int, val used: Int, val remaining: Int, val day: String) {
    val remainingToday: Int get() = if (day == java.time.LocalDate.now(java.time.ZoneId.of("Pacific/Auckland")).toString()) remaining else limit
}

data class AccountSnapshot(val user: AccountProfile, val fishIdentity: Boolean, val fishIdentityQuota: FishIdentityQuota? = null)

data class Country(val code: String, val name: String) {
    val flag: String get() = Countries.flag(code)
}

/** The profile's country choices, named in the phone's language with New Zealand first. The iPhone app has the same in `Countries`. */
object Countries {
    fun all(locale: Locale = Locale.getDefault()): List<Country> {
        val collator = Collator.getInstance(locale)
        val others = Locale.getISOCountries().filter { it != "NZ" }.map { Country(it, name(it, locale)) }.sortedWith(compareBy(collator) { it.name })
        return listOf(Country("NZ", name("NZ", locale))) + others
    }

    @Suppress("DEPRECATION")
    fun name(code: String, locale: Locale = Locale.getDefault()): String = Locale("", code).getDisplayCountry(locale).ifBlank { code }

    /** The flag emoji for a two-letter code, or "" for anything else. */
    fun flag(code: String): String = if (!code.matches(Regex("[A-Z]{2}"))) "" else
        code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}
