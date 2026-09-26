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
import nz.fishingnz.app.data.AccountSessionStore
import nz.fishingnz.app.data.RecommendationEngine
import nz.fishingnz.app.data.SearchPreferencesStore
import nz.fishingnz.app.model.*
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

private val nzZone = ZoneId.of("Pacific/Auckland")
private val suggestedHours = PreferredTimeRange(LocalTime.of(7, 0), LocalTime.of(21, 0))

enum class SearchLocationMode { NEAR_ME, SPECIFIC_LOCATION }

data class FishingUiState(
    val tab: Int = 0, val boat: Boolean = false, val dateLabel: String = "In 3 days", val dateStart: LocalDate = LocalDate.now(nzZone).plusDays(3), val dateEnd: LocalDate = LocalDate.now(nzZone).plusDays(3),
    val radiusKm: Int = 100, val preferredTime: PreferredTimeRange? = suggestedHours, val preferredTimeIsSuggested: Boolean = true,
    val searchLocationMode: SearchLocationMode = SearchLocationMode.NEAR_ME, val selectedSearchStation: TideStation? = null,
    val manualOriginSelected: Boolean = false,
    val location: GeoPoint = GeoPoint(-36.85, 174.76), val deviceLocation: GeoPoint? = null, val hasDeviceLocation: Boolean = false,
    val originName: String? = null, val locating: Boolean = false, val locationNotice: String? = null,
    val fishRulesAreaId: String? = null, val fishRulesAreaIsSuggested: Boolean = false,
    val weather: WeatherState? = null, val tide: TideState? = null,
    val selectedStation: TideStation = tideStations.first { it.name == "Auckland" }, val tideDate: LocalDate = LocalDate.now(nzZone),
    val stationTide: TideState? = null, val tideLoading: Boolean = false, val tideError: String? = null,
    val tideDeviceLocation: GeoPoint? = null, val tidePlaceName: String? = null, val tideLocationAttempted: Boolean = false,
    val tideStationManual: Boolean = false, val tideLocationNotice: String? = null, val error: String? = null,
    val fishPhoto: Uri? = null, val fishCheck: FishCheck? = null, val fishChecking: Boolean = false, val fishError: String? = null, val savedSpots: Set<String> = emptySet(), val activeTrip: Recommendation? = null,
    val selectedSpot: Recommendation? = null, val showResults: Boolean = false, val recommendationSearch: RecommendationSearch? = null,
    val recommendationsLoading: Boolean = false, val recommendationsError: String? = null, val savedRecommendations: List<Recommendation> = emptyList(),
    val account: AccountSnapshot? = null, val accountBusy: Boolean = false, val accountLoading: Boolean = true,
    val hasStoredSession: Boolean = false,
    val accountError: String? = null, val accountNotice: String? = null, val verificationPending: Boolean = false
)

class FishingViewModel(private val repository: FishingRepository = FishingRepository()) : ViewModel() {
    private val recommendationEngine = RecommendationEngine()
    private var recommendationJob: Job? = null
    private var tideJob: Job? = null
    private var fishRulesAreaManuallySelected = false
    private var accountVersion = 0
    private val savedSearchStation = tideStations.firstOrNull { it.id == SearchPreferencesStore.savedStationId() }
    private val savedSearchMode = if (SearchPreferencesStore.savedMode() == SearchLocationMode.SPECIFIC_LOCATION.name && savedSearchStation != null)
        SearchLocationMode.SPECIFIC_LOCATION else SearchLocationMode.NEAR_ME
    private val savedManualOrigin = searchOrigins.firstOrNull { it.name == SearchPreferencesStore.savedManualOriginName() }
    private val _state = MutableStateFlow(FishingUiState(
        hasStoredSession = AccountSessionStore.token() != null,
        radiusKm = SearchPreferencesStore.savedRadiusKm() ?: 100,
        searchLocationMode = savedSearchMode,
        selectedSearchStation = savedSearchStation,
        manualOriginSelected = savedSearchMode == SearchLocationMode.NEAR_ME && savedManualOrigin != null,
        location = savedSearchStation?.takeIf { savedSearchMode == SearchLocationMode.SPECIFIC_LOCATION }
            ?.let { GeoPoint(it.latitude, it.longitude) } ?: savedManualOrigin?.point ?: GeoPoint(-36.85, 174.76),
        originName = savedSearchStation?.takeIf { savedSearchMode == SearchLocationMode.SPECIFIC_LOCATION }?.name
            ?: savedManualOrigin?.takeIf { savedSearchMode == SearchLocationMode.NEAR_ME }?.name
    ))
    val state: StateFlow<FishingUiState> = _state.asStateFlow()
    init {
        refreshStationTide()
        if (_state.value.originName != null) refreshConditions()
        refreshAccount()
    }
    fun selectTab(value: Int) {
        val old = _state.value
        if (value !in 0..10 || old.tab == value) return
        _state.value = old.copy(tab = value)
        val features = listOf("home", "map", "tide", "trip_planning", "fishing_rules", "account", "more", "settings", "feedback", "terms_privacy", "appearance")
        if (old.account != null) viewModelScope.launch { runCatching { repository.trackEvent("feature_used", features[value], "android") } }
    }
    fun canGoBack(): Boolean = _state.value.let { it.selectedSpot != null || it.showResults || it.tab != 0 }
    fun goBack() {
        val current = _state.value
        when {
            current.selectedSpot != null -> closeSpot()
            current.showResults -> closeResults()
            current.tab in 8..10 -> selectTab(7)
            current.tab == 7 || current.tab in 3..5 -> selectTab(6)
            current.tab != 0 -> selectTab(0)
        }
    }
    fun setBoat(value: Boolean) { _state.value = _state.value.copy(boat = value, recommendationSearch = null); if (_state.value.showResults) refreshRecommendations() }
    fun setDate(value: String) {
        val today = LocalDate.now(nzZone)
        val range = when (value) {
            "Today" -> today to today
            "In 3 days" -> today.plusDays(3) to today.plusDays(3)
            "In 7 days" -> today.plusDays(7) to today.plusDays(7)
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
        SearchPreferencesStore.saveRadiusKm(km)
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
    fun setSearchLocationMode(mode: SearchLocationMode) {
        val old = _state.value
        if (old.searchLocationMode == mode) return
        val station = old.selectedSearchStation
        val nearbyOrigin = searchOrigins.firstOrNull { it.name == SearchPreferencesStore.savedManualOriginName() }
        val point = when (mode) {
            SearchLocationMode.SPECIFIC_LOCATION -> station?.let { GeoPoint(it.latitude, it.longitude) }
            SearchLocationMode.NEAR_ME -> nearbyOrigin?.point ?: old.deviceLocation
        }
        val name = when (mode) {
            SearchLocationMode.SPECIFIC_LOCATION -> station?.name
            SearchLocationMode.NEAR_ME -> nearbyOrigin?.name ?: old.deviceLocation?.let(::nearestCityName)
        }
        _state.value = old.copy(searchLocationMode = mode,
            manualOriginSelected = mode == SearchLocationMode.NEAR_ME && nearbyOrigin != null,
            location = point ?: old.location, originName = name,
            weather = null, tide = null, recommendationSearch = null, locationNotice = null)
        SearchPreferencesStore.save(mode.name, station?.id)
        if (name != null) refreshConditions()
        if (_state.value.showResults) refreshRecommendations()
    }
    fun selectSearchStation(station: TideStation) {
        val old = _state.value
        _state.value = old.copy(searchLocationMode = SearchLocationMode.SPECIFIC_LOCATION, selectedSearchStation = station,
            location = GeoPoint(station.latitude, station.longitude), originName = station.name,
            weather = null, tide = null, recommendationSearch = null, locationNotice = null)
        SearchPreferencesStore.save(SearchLocationMode.SPECIFIC_LOCATION.name, station.id)
        refreshConditions()
        if (_state.value.showResults) refreshRecommendations()
    }
    fun useDeviceSearchOrigin() {
        val old = _state.value
        val point = old.deviceLocation
        _state.value = old.copy(searchLocationMode = SearchLocationMode.NEAR_ME, manualOriginSelected = false,
            location = point ?: old.location, originName = point?.let(::nearestCityName), locating = false,
            weather = null, tide = null, recommendationSearch = null, locationNotice = null)
        SearchPreferencesStore.save(SearchLocationMode.NEAR_ME.name, old.selectedSearchStation?.id)
        SearchPreferencesStore.saveManualOrigin(null)
        if (point != null) refreshConditions()
        if (_state.value.showResults && point != null) refreshRecommendations()
    }
    fun updateLocation(value: GeoPoint, placeName: String? = null) {
        val old = _state.value
        val nearest = if (old.tideStationManual) old.selectedStation else nearestTideStation(value)
        val useDeviceOrigin = old.searchLocationMode == SearchLocationMode.NEAR_ME && !old.manualOriginSelected
        val newOriginName = if (useDeviceOrigin) placeName ?: nearestCityName(value) else old.originName
        val originChanged = useDeviceOrigin && (old.location != value || old.originName != newOriginName)
        _state.value = old.copy(location = if (useDeviceOrigin) value else old.location, deviceLocation = value,
            hasDeviceLocation = true, originName = newOriginName, locating = false,
            locationNotice = null, weather = if (originChanged) null else old.weather,
            tide = if (originChanged) null else old.tide,
            recommendationSearch = if (originChanged) null else old.recommendationSearch,
            tideDeviceLocation = value, tidePlaceName = placeName ?: nearestCityName(value), tideLocationAttempted = true, tideLocationNotice = null, selectedStation = nearest)
        syncRulesAreaFromDeviceLocation(value)
        if (nearest != old.selectedStation) refreshStationTide()
        if (originChanged) refreshConditions()
        if (_state.value.showResults && (originChanged || old.locating)) refreshRecommendations()
    }
    fun completeLocationSearch(value: GeoPoint, placeName: String? = null) {
        if (_state.value.locating) updateLocation(value, placeName)
    }
    fun setResolvedCity(value: String) {
        val old = _state.value
        if (old.deviceLocation != null && old.searchLocationMode == SearchLocationMode.NEAR_ME && !old.manualOriginSelected && value.isNotBlank())
            _state.value = old.copy(originName = value, tidePlaceName = value)
    }
    fun chooseFishRulesArea(id: String?) {
        fishRulesAreaManuallySelected = id != null
        _state.value = _state.value.copy(fishRulesAreaId = id, fishRulesAreaIsSuggested = false, fishCheck = null)
    }
    fun syncRulesAreaFromDeviceLocation(point: GeoPoint) {
        if (fishRulesAreaManuallySelected) return
        val suggestion = suggestedRulesAreaId(point)
        if (_state.value.fishRulesAreaId != suggestion || _state.value.fishRulesAreaIsSuggested != (suggestion != null)) {
            _state.value = _state.value.copy(fishRulesAreaId = suggestion,
                fishRulesAreaIsSuggested = suggestion != null, fishCheck = null)
        }
    }
    fun resetFishRulesAreaToCurrentLocation() {
        fishRulesAreaManuallySelected = false
        val point = _state.value.deviceLocation ?: _state.value.tideDeviceLocation
        val suggestion = point?.let(::suggestedRulesAreaId)
        _state.value = _state.value.copy(fishRulesAreaId = suggestion,
            fishRulesAreaIsSuggested = suggestion != null, fishCheck = null)
    }
    fun selectManualOrigin(origin: SearchOrigin) {
        val old = _state.value
        val nearest = if (old.tideStationManual || old.tideDeviceLocation != null) old.selectedStation else nearestTideStation(origin.point)
        _state.value = old.copy(searchLocationMode = SearchLocationMode.NEAR_ME, manualOriginSelected = true,
            location = origin.point, originName = origin.name, locating = false,
            locationNotice = null, weather = null, tide = null, recommendationSearch = null, selectedStation = nearest)
        SearchPreferencesStore.save(SearchLocationMode.NEAR_ME.name, old.selectedSearchStation?.id)
        SearchPreferencesStore.saveManualOrigin(origin.name)
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
            locationNotice = if (existingOrigin == null) "Current location unavailable. Go back to Home to choose a city." else "Current location unavailable. Continuing from $existingOrigin.")
        if (_state.value.originName != null) refreshRecommendations()
        else _state.value = _state.value.copy(recommendationsError = "Device location is unavailable. Go back to Home to choose a city.")
    }
    fun chooseStation(value: TideStation) {
        _state.value = _state.value.copy(selectedStation = value, tideStationManual = true, tideLocationNotice = null)
        refreshStationTide()
    }
    fun beginTideLocationSearch() {
        _state.value = _state.value.copy(tideLocationAttempted = true, tideStationManual = false, tideLocationNotice = null)
    }
    fun updateTideLocation(point: GeoPoint) {
        updateLocation(point)
    }
    fun setResolvedTidePlace(value: String) {
        if (_state.value.tideDeviceLocation != null && value.isNotBlank()) _state.value = _state.value.copy(tidePlaceName = value)
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
        if (query.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION && query.selectedSearchStation == null) {
            _state.value = query.copy(locating = false, recommendationsLoading = false,
                recommendationsError = "Choose a tide location in your plan before searching.", recommendationSearch = null)
            return
        }
        if (query.originName == null) {
            _state.value = query.copy(locating = false, recommendationsLoading = false,
                recommendationsError = "Go back to Home to choose a city for the search.", recommendationSearch = null)
            return
        }
        _state.value = query.copy(recommendationsLoading = true, recommendationsError = null, recommendationSearch = null)
        recommendationJob = viewModelScope.launch {
            try {
                val result = recommendationEngine.search(query.location, query.radiusKm, query.dateStart, query.dateEnd, query.boat,
                    query.preferredTime, query.selectedSearchStation.takeIf { query.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION })
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
        if (hasDeviceLocation) updateLocation(point)
        _state.value = _state.value.copy(fishChecking = true, fishError = null)
        val selectedArea = _state.value.fishRulesAreaId
        viewModelScope.launch { runCatching { repository.identifyFish(image, point, hasDeviceLocation, selectedArea) }.onSuccess { _state.value = _state.value.copy(fishCheck = it, fishChecking = false) }.onFailure { _state.value = _state.value.copy(fishChecking = false, fishError = it.message ?: "Could not identify this photo.") } }
    }
    fun refreshAccount() {
        val version = accountVersion
        _state.value = _state.value.copy(accountLoading = true, accountError = null)
        viewModelScope.launch {
            try {
                val account = repository.currentAccount()
                if (version != accountVersion) return@launch
                _state.value = _state.value.copy(account = account, accountLoading = false,
                    hasStoredSession = AccountSessionStore.token() != null)
                if (account != null) runCatching { repository.trackEvent("app_opened", null, "android") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version == accountVersion) _state.value = _state.value.copy(accountLoading = false,
                    hasStoredSession = AccountSessionStore.token() != null,
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
                        hasStoredSession = true,
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
                    hasStoredSession = false,
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
