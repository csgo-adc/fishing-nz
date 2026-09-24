package nz.fishingnz.app.model

data class AccountProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val countryCode: String,
    val plan: String,
)

data class AccountSnapshot(val user: AccountProfile, val fishIdentity: Boolean)
