package nz.fishingnz.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nz.fishingnz.app.data.FishingRepository
import nz.fishingnz.app.model.*
import java.time.LocalDate

data class FishingUiState(
    val tab: Int = 0, val boat: Boolean = false, val dateLabel: String = "This Saturday", val location: GeoPoint = GeoPoint(-36.85, 174.76), val hasDeviceLocation: Boolean = false, val weather: WeatherState? = null, val tide: TideState? = null,
    val selectedStation: TideStation = tideStations.first(), val tideDate: LocalDate = LocalDate.now(), val stationTide: TideState? = null, val error: String? = null,
    val fishPhoto: Uri? = null, val fishCheck: FishCheck? = null, val fishChecking: Boolean = false, val fishError: String? = null, val savedSpots: Set<String> = emptySet(), val activeTrip: Recommendation? = null,
    val selectedSpot: Recommendation? = null, val showResults: Boolean = false,
    val account: AccountSnapshot? = null, val accountBusy: Boolean = false, val accountError: String? = null, val accountNotice: String? = null, val verificationPending: Boolean = false
)

class FishingViewModel(private val repository: FishingRepository = FishingRepository()) : ViewModel() {
    private val _state = MutableStateFlow(FishingUiState())
    val state: StateFlow<FishingUiState> = _state.asStateFlow()
    init { refreshConditions(); refreshStationTide(); refreshAccount() }
    fun selectTab(value: Int) {
        val old = _state.value
        _state.value = old.copy(tab = value)
        val features = listOf("home", "map", "tide", "trip_planning", "fishing_rules", "account")
        if (old.tab != value && old.account != null && value in features.indices) viewModelScope.launch { runCatching { repository.trackEvent("feature_used", features[value], "android") } }
    }
    fun setBoat(value: Boolean) { _state.value = _state.value.copy(boat = value) }
    fun setDate(value: String) { _state.value = _state.value.copy(dateLabel = value) }
    fun updateLocation(value: GeoPoint) { _state.value = _state.value.copy(location = value, hasDeviceLocation = true); refreshConditions() }
    fun chooseStation(value: TideStation) { _state.value = _state.value.copy(selectedStation = value); refreshStationTide() }
    fun changeTideDate(value: LocalDate) { _state.value = _state.value.copy(tideDate = value); refreshStationTide() }
    fun showResults() { _state.value = _state.value.copy(showResults = true) }
    fun closeResults() { _state.value = _state.value.copy(showResults = false) }
    fun openSpot(value: Recommendation) { _state.value = _state.value.copy(selectedSpot = value, showResults = false) }
    fun closeSpot() { _state.value = _state.value.copy(selectedSpot = null) }
    fun startTrip() { _state.value.selectedSpot?.let { _state.value = _state.value.copy(activeTrip = it, selectedSpot = null) } }
    fun endTrip() { _state.value = _state.value.copy(activeTrip = null) }
    fun toggleSaved(name: String) { val spots = _state.value.savedSpots; _state.value = _state.value.copy(savedSpots = if (name in spots) spots - name else spots + name) }
    fun setFishPhoto(uri: Uri?) { _state.value = _state.value.copy(fishPhoto = uri, fishCheck = null, fishError = null) }
    fun identifyFish(image: ByteArray, point: GeoPoint, hasDeviceLocation: Boolean) {
        _state.value = _state.value.copy(location = point, hasDeviceLocation = hasDeviceLocation, fishChecking = true, fishError = null)
        viewModelScope.launch { runCatching { repository.identifyFish(image, point, hasDeviceLocation) }.onSuccess { _state.value = _state.value.copy(fishCheck = it, fishChecking = false) }.onFailure { _state.value = _state.value.copy(fishChecking = false, fishError = it.message ?: "Could not identify this photo.") } }
    }
    fun refreshAccount() {
        viewModelScope.launch {
            val account = repository.currentAccount()
            _state.value = _state.value.copy(account = account)
            if (account != null) runCatching { repository.trackEvent("app_opened", null, "android") }
        }
    }
    fun signIn(email: String, password: String, displayName: String, createAccount: Boolean) {
        _state.value = _state.value.copy(accountBusy = true, accountError = null, accountNotice = null)
        if (createAccount) viewModelScope.launch { runCatching { repository.createAccount(email, password, displayName) }
            .onSuccess { _state.value = _state.value.copy(accountBusy = false, verificationPending = true, accountError = null, accountNotice = it) }
            .onFailure { _state.value = _state.value.copy(accountBusy = false, accountError = it.message ?: "Could not create account.") } }
        else viewModelScope.launch { runCatching { repository.signIn(email, password, null, false) }
            .onSuccess { _state.value = _state.value.copy(account = it, accountBusy = false, verificationPending = false, accountError = null, accountNotice = "You’re signed in.") }
            .onFailure { _state.value = _state.value.copy(accountBusy = false, accountError = it.message ?: "Could not sign in.") } }
    }
    fun resendVerification(email: String) {
        _state.value = _state.value.copy(accountBusy = true, accountError = null, accountNotice = null)
        viewModelScope.launch { runCatching { repository.resendVerification(email) }
            .onSuccess { _state.value = _state.value.copy(accountBusy = false, verificationPending = true, accountNotice = it) }
            .onFailure { _state.value = _state.value.copy(accountBusy = false, accountError = it.message ?: "Could not send the confirmation email.") } }
    }
    fun signOut() {
        _state.value = _state.value.copy(accountBusy = true, accountError = null, accountNotice = null)
        viewModelScope.launch { repository.signOut(); _state.value = _state.value.copy(account = null, accountBusy = false, accountNotice = "You’re signed out.") }
    }
    fun saveAccountProfile(displayName: String, countryCode: String) {
        _state.value = _state.value.copy(accountBusy = true, accountError = null, accountNotice = null)
        viewModelScope.launch { runCatching { repository.saveProfile(displayName, countryCode) }
            .onSuccess { _state.value = _state.value.copy(account = it, accountBusy = false, accountNotice = "Profile saved.") }
            .onFailure { _state.value = _state.value.copy(accountBusy = false, accountError = it.message ?: "Could not save profile.") } }
    }
    fun sendFeedback(category: String, message: String, rating: Int) {
        _state.value = _state.value.copy(accountBusy = true, accountError = null, accountNotice = null)
        viewModelScope.launch { runCatching { repository.sendFeedback(category, message, rating) }
            .onSuccess { _state.value = _state.value.copy(accountBusy = false, accountNotice = "Thanks for your feedback.") }
            .onFailure { _state.value = _state.value.copy(accountBusy = false, accountError = it.message ?: "Could not send feedback.") } }
    }
    fun refreshConditions() { viewModelScope.launch { runCatching { repository.conditions(_state.value.location) }.onSuccess { _state.value = _state.value.copy(weather = it.first, tide = it.second, error = null) }.onFailure { _state.value = _state.value.copy(error = "Live conditions unavailable") } } }
    fun refreshStationTide() { viewModelScope.launch { runCatching { repository.tide(_state.value.selectedStation, _state.value.tideDate) }.onSuccess { _state.value = _state.value.copy(stationTide = it) } } }
}
