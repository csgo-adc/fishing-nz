package nz.fishingnz.app.model

data class AccountProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val countryCode: String,
    val plan: String,
)

data class FishIdentityQuota(val limit: Int, val used: Int, val remaining: Int, val day: String) {
    val remainingToday: Int get() = if (day == java.time.LocalDate.now(java.time.ZoneId.of("Pacific/Auckland")).toString()) remaining else limit
}

data class AccountSnapshot(val user: AccountProfile, val fishIdentity: Boolean, val fishIdentityQuota: FishIdentityQuota? = null)
