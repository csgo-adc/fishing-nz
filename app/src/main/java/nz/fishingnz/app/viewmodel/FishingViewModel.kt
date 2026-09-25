package nz.fishingnz.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nz.fishingnz.app.data.FishingRepository
import nz.fishingnz.app.data.AccountRequestException
import nz.fishingnz.app.data.RecommendationEngine
import nz.fishingnz.app.model.*
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

private val nzZone = ZoneId.of("Pacific/Auckland")
private val suggestedHours = PreferredTimeRange(LocalTime.of(7, 0), LocalTime.of(21, 0))

data class FishingUiState(
    val tab: Int = 0, val boat: Boolean = false, val dateLabel: String = "Next 3 days", val dateStart: LocalDate = LocalDate.now(nzZone), val dateEnd: LocalDate = LocalDate.now(nzZone).plusDays(2),
    val radiusKm: Int = 100, val preferredTime: PreferredTimeRange? = suggestedHours, val preferredTimeIsSuggested: Boolean = true,
    val location: GeoPoint = GeoPoint(-36.85, 174.76), val hasDeviceLocation: Boolean = false,
    val originName: String? = null, val locating: Boolean = false, val locationNotice: String? = null,
    val weather: WeatherState? = null, val tide: TideState? = null,
    val selectedStation: TideStation = tideStations.first(), val tideDate: LocalDate = LocalDate.now(nzZone),
    val stationTide: TideState? = null, val tideLoading: Boolean = false, val tideError: String? = null,
    val tideDeviceLocation: GeoPoint? = null, val tideLocationAttempted: Boolean = false,
    val tideStationManual: Boolean = false, val tideLocationNotice: String? = null, val error: String? = null,
    val fishPhoto: Uri? = null, val fishCheck: FishCheck? = null, val fishChecking: Boolean = false, val fishError: String? = null, val savedSpots: Set<String> = emptySet(), val activeTrip: Recommendation? = null,
    val selectedSpot: Recommendation? = null, val showResults: Boolean = false, val recommendationSearch: RecommendationSearch? = null,
    val recommendationsLoading: Boolean = false, val recommendationsError: String? = null, val savedRecommendations: List<Recommendation> = emptyList(),
    val account: AccountSnapshot? = null, val accountBusy: Boolean = false, val accountLoading: Boolean = true,
    val accountError: String? = null, val accountNotice: String? = null, val verificationPending: Boolean = false
)

class FishingViewModel(private val repository: FishingRepository = FishingRepository()) : ViewModel() {
    private val recommendationEngine = RecommendationEngine()
    private var recommendationJob: Job? = null
    private var tideJob: Job? = null
    private var accountVersion = 0
    private val _state = MutableStateFlow(FishingUiState())
    val state: StateFlow<FishingUiState> = _state.asStateFlow()
    init { refreshStationTide(); refreshAccount() }
    fun selectTab(value: Int) {
        val old = _state.value
        _state.value = old.copy(tab = value)
        val features = listOf("home", "map", "tide", "trip_planning", "fishing_rules", "account")
        if (old.tab != value && old.account != null && value in features.indices) viewModelScope.launch { runCatching { repository.trackEvent("feature_used", features[value], "android") } }
    }
    fun setBoat(value: Boolean) { _state.value = _state.value.copy(boat = value, recommendationSearch = null); if (_state.value.showResults) refreshRecommendations() }
    fun setDate(value: String) {
        val today = LocalDate.now(nzZone)
        val range = when (value) {
            "Today" -> today to today
            "In 3 days" -> today.plusDays(3) to today.plusDays(3)
            "Next 3 days" -> today to today.plusDays(2)
            "This weekend" -> {
                val saturday = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.minusDays(1)
                    else today.plusDays((DayOfWeek.SATURDAY.value - today.dayOfWeek.value + 7L) % 7)
                maxOf(today, saturday) to saturday.plusDays(1)
            }
            else -> return
        }
        _state.value = _state.value.copy(dateLabel = value, dateStart = range.first, dateEnd = range.second, recommendationSearch = null)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setCustomDates(start: LocalDate, end: LocalDate) {
        val today = LocalDate.now(nzZone)
        if (start < today || end < start || end > today.plusDays(15)) return
        _state.value = _state.value.copy(dateLabel = "Custom", dateStart = start, dateEnd = end, recommendationSearch = null)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setRadius(km: Int) {
        if (km !in listOf(10, 30, 50, 100, 200, 300, 400, 500)) return
        _state.value = _state.value.copy(radiusKm = km, recommendationSearch = null)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setSuggestedHours() {
        _state.value = _state.value.copy(preferredTime = suggestedHours, preferredTimeIsSuggested = true, recommendationSearch = null)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setPreferredHours(start: LocalTime, end: LocalTime) {
        if (start == end) return
        _state.value = _state.value.copy(preferredTime = PreferredTimeRange(start, end), preferredTimeIsSuggested = false, recommendationSearch = null)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun clearPreferredHours() {
        _state.value = _state.value.copy(preferredTime = null, preferredTimeIsSuggested = false, recommendationSearch = null)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun updateLocation(value: GeoPoint) {
        val old = _state.value
        val nearest = if (old.tideStationManual) old.selectedStation else nearestTideStation(value)
        _state.value = old.copy(location = value, hasDeviceLocation = true, originName = "Current location", locating = false,
            locationNotice = null, weather = null, tide = null, recommendationSearch = null,
            tideDeviceLocation = value, tideLocationAttempted = true, tideLocationNotice = null, selectedStation = nearest)
        if (nearest != old.selectedStation) refreshStationTide()
        refreshConditions()
        if (_state.value.showResults) refreshRecommendations()
    }
    fun completeLocationSearch(value: GeoPoint) {
        if (_state.value.locating) updateLocation(value)
    }
    fun selectManualOrigin(origin: SearchOrigin) {
        val old = _state.value
        val nearest = if (old.tideStationManual || old.tideDeviceLocation != null) old.selectedStation else nearestTideStation(origin.point)
        _state.value = old.copy(location = origin.point, hasDeviceLocation = false, originName = origin.name, locating = false,
            locationNotice = null, weather = null, tide = null, recommendationSearch = null, selectedStation = nearest)
        if (nearest != old.selectedStation) refreshStationTide()
        refreshConditions()
        if (_state.value.showResults) refreshRecommendations()
    }
    fun startLocationSearch() {
        recommendationJob?.cancel()
        _state.value = _state.value.copy(showResults = true, locating = true, recommendationsLoading = false,
            recommendationsError = null, recommendationSearch = null, locationNotice = null)
    }
    fun locationUnavailable() {
        if (!_state.value.locating) return
        val existingOrigin = _state.value.originName
        _state.value = _state.value.copy(locating = false,
            locationNotice = if (existingOrigin == null) "Current location unavailable. Choose a city to search." else "Current location unavailable. Continuing from $existingOrigin.")
        if (_state.value.originName != null) refreshRecommendations()
        else _state.value = _state.value.copy(recommendationsError = "Device location is unavailable. Choose a city below to search nearby fishing areas.")
    }
    fun chooseStation(value: TideStation) {
        _state.value = _state.value.copy(selectedStation = value, tideStationManual = true, tideLocationNotice = null)
        refreshStationTide()
    }
    fun beginTideLocationSearch() {
        _state.value = _state.value.copy(tideLocationAttempted = true, tideStationManual = false, tideLocationNotice = null)
    }
    fun updateTideLocation(point: GeoPoint) {
        val old = _state.value
        val nearest = if (old.tideStationManual) old.selectedStation else nearestTideStation(point)
        _state.value = old.copy(tideDeviceLocation = point, tideLocationAttempted = true,
            tideLocationNotice = null, selectedStation = nearest)
        if (nearest != old.selectedStation) refreshStationTide()
    }
    fun tideLocationUnavailable() {
        _state.value = _state.value.copy(tideLocationAttempted = true,
            tideLocationNotice = "Current location unavailable. Showing ${_state.value.selectedStation.name}; choose a station below or try again.")
    }
    fun changeTideDate(value: LocalDate) { _state.value = _state.value.copy(tideDate = value); refreshStationTide() }
    fun showResults() { _state.value = _state.value.copy(showResults = true); refreshRecommendations() }
    fun closeResults() { _state.value = _state.value.copy(showResults = false) }
    fun openSpot(value: Recommendation) { _state.value = _state.value.copy(selectedSpot = value) }
    fun closeSpot() { _state.value = _state.value.copy(selectedSpot = null) }
    fun startTrip() { _state.value.selectedSpot?.let { _state.value = _state.value.copy(activeTrip = it, selectedSpot = null) } }
    fun endTrip() { _state.value = _state.value.copy(activeTrip = null) }
    fun toggleSaved(spot: Recommendation) {
        val current = _state.value
        val key = recommendationKey(spot)
        val saved = if (key in current.savedSpots) current.savedSpots - key else current.savedSpots + key
        val recommendations = if (key in current.savedSpots) current.savedRecommendations.filterNot { recommendationKey(it) == key }
            else current.savedRecommendations.filterNot { recommendationKey(it) == key } + spot
        _state.value = current.copy(savedSpots = saved, savedRecommendations = recommendations)
    }
    fun refreshRecommendations() {
        recommendationJob?.cancel()
        val query = _state.value
        if (query.originName == null) {
            _state.value = query.copy(locating = false, recommendationsLoading = false,
                recommendationsError = "Choose a city below to search nearby fishing areas.", recommendationSearch = null)
            return
        }
        _state.value = query.copy(recommendationsLoading = true, recommendationsError = null, recommendationSearch = null)
        recommendationJob = viewModelScope.launch {
            try {
                val result = recommendationEngine.search(query.location, query.radiusKm, query.dateStart, query.dateEnd, query.boat, query.preferredTime)
                _state.value = _state.value.copy(recommendationSearch = result, recommendationsLoading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(recommendationsLoading = false, recommendationsError = error.message ?: "Forecasts are unavailable. Try again.")
            }
        }
    }
    fun setFishPhoto(uri: Uri?) { _state.value = _state.value.copy(fishPhoto = uri, fishCheck = null, fishError = null) }
    fun identifyFish(image: ByteArray, point: GeoPoint, hasDeviceLocation: Boolean) {
        _state.value = _state.value.copy(location = point, hasDeviceLocation = hasDeviceLocation, fishChecking = true, fishError = null)
        viewModelScope.launch { runCatching { repository.identifyFish(image, point, hasDeviceLocation) }.onSuccess { _state.value = _state.value.copy(fishCheck = it, fishChecking = false) }.onFailure { _state.value = _state.value.copy(fishChecking = false, fishError = it.message ?: "Could not identify this photo.") } }
    }
    fun refreshAccount() {
        val version = accountVersion
        _state.value = _state.value.copy(accountLoading = true, accountError = null)
        viewModelScope.launch {
            try {
                val account = repository.currentAccount()
                if (version != accountVersion) return@launch
                _state.value = _state.value.copy(account = account, accountLoading = false)
                if (account != null) runCatching { repository.trackEvent("app_opened", null, "android") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == accountVersion) _state.value = _state.value.copy(accountLoading = false,
                    accountError = "Could not check your account. Check your connection and try again.")
            }
        }
    }
    fun signIn(email: String, password: String, displayName: String, createAccount: Boolean) {
        val version = ++accountVersion
        _state.value = _state.value.copy(accountBusy = true, accountLoading = false, accountError = null, accountNotice = null)
        viewModelScope.launch {
            try {
                if (createAccount) {
                    val notice = repository.createAccount(email, password, displayName)
                    if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                        verificationPending = true, accountNotice = notice)
                } else {
                    val account = repository.signIn(email, password)
                    if (version == accountVersion) _state.value = _state.value.copy(account = account,
                        accountBusy = false, verificationPending = false, accountError = null, accountNotice = "You’re signed in.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                    verificationPending = _state.value.verificationPending || (error as? AccountRequestException)?.code in
                        setOf("email_not_verified", "email_delivery_failed"),
                    accountError = error.message ?: if (createAccount) "Could not create account." else "Could not sign in.")
            }
        }
    }
    fun resendVerification(email: String) {
        _state.value = _state.value.copy(accountBusy = true, accountError = null, accountNotice = null)
        viewModelScope.launch { runCatching { repository.resendVerification(email) }
            .onSuccess { _state.value = _state.value.copy(accountBusy = false, verificationPending = true, accountNotice = it) }
            .onFailure { _state.value = _state.value.copy(accountBusy = false, accountError = it.message ?: "Could not send the confirmation email.") } }
    }
    fun signOut() {
        val version = ++accountVersion
        _state.value = _state.value.copy(accountBusy = true, accountLoading = false, accountError = null, accountNotice = null)
        viewModelScope.launch {
            try {
                repository.signOut()
                if (version == accountVersion) _state.value = _state.value.copy(account = null, accountBusy = false,
                    verificationPending = false, accountNotice = "You’re signed out.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                    accountError = error.message ?: "Could not sign out. Please try again.")
            }
        }
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
    fun refreshConditions() {
        val query = _state.value
        if (query.originName == null) return
        viewModelScope.launch { runCatching { repository.conditions(query.location) }
            .onSuccess { if (_state.value.location == query.location) _state.value = _state.value.copy(weather = it.first, tide = it.second, error = null) }
            .onFailure { if (_state.value.location == query.location) _state.value = _state.value.copy(error = "Live conditions unavailable") } }
    }
    fun refreshStationTide() {
        tideJob?.cancel()
        val station = _state.value.selectedStation
        val date = _state.value.tideDate
        _state.value = _state.value.copy(stationTide = null, tideLoading = true, tideError = null)
        tideJob = viewModelScope.launch {
            try {
                val result = repository.tide(station, date)
                if (_state.value.selectedStation == station && _state.value.tideDate == date)
                    _state.value = _state.value.copy(stationTide = result, tideLoading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (_state.value.selectedStation == station && _state.value.tideDate == date)
                    _state.value = _state.value.copy(tideLoading = false, tideError = error.message ?: "Tide table unavailable.")
            }
        }
    }
}
