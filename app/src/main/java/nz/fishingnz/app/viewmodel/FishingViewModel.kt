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
import nz.fishingnz.app.data.Analytics
import nz.fishingnz.app.data.AnalyticsPayload
import nz.fishingnz.app.data.SignInProviders
import nz.fishingnz.app.data.RecommendationEngine
import nz.fishingnz.app.data.SearchPreferencesStore
import nz.fishingnz.app.model.*
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

private val nzZone = ZoneId.of("Pacific/Auckland")
private val suggestedHours = PreferredTimeRange(LocalTime.of(7, 0), LocalTime.of(21, 0))

internal fun presetFishingDates(value: String, today: LocalDate): Pair<LocalDate, LocalDate>? = when (value) {
    "Today" -> today to today
    "In 3 days" -> today.plusDays(3) to today.plusDays(3)
    "Next 7 days" -> today to today.plusDays(6)
    "This weekend" -> {
        val saturday = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.minusDays(1)
            else today.plusDays((DayOfWeek.SATURDAY.value - today.dayOfWeek.value + 7L) % 7)
        maxOf(today, saturday) to saturday.plusDays(1)
    }
    else -> null
}

enum class SearchLocationMode { NEAR_ME, SPECIFIC_LOCATION }

data class FishingUiState(
    val tab: Int = 0, val boat: Boolean = false, val dateLabel: String = "In 3 days", val dateStart: LocalDate = LocalDate.now(nzZone).plusDays(3), val dateEnd: LocalDate = LocalDate.now(nzZone).plusDays(3),
    val radiusKm: Int = 100, val preferredTime: PreferredTimeRange? = suggestedHours, val preferredTimeIsSuggested: Boolean = true,
    val preference: WindowPriority = WindowPriority.WEATHER,
    val landPreferences: LandPreferences = LandPreferences(),
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
    val fishPhoto: Uri? = null, val fishCheck: FishCheck? = null, val fishChecking: Boolean = false, val fishError: String? = null,
    val fishRulesLoading: Boolean = false, val fishRulesError: String? = null,
    val savedSpots: Set<String> = emptySet(), val activeTrip: Recommendation? = null,
    val selectedSpot: Recommendation? = null, val showResults: Boolean = false, val recommendationSearch: RecommendationSearch? = null,
    val recommendationsLoading: Boolean = false, val recommendationsError: String? = null, val savedRecommendations: List<Recommendation> = emptyList(),
    val account: AccountSnapshot? = null, val accountBusy: Boolean = false, val accountLoading: Boolean = true,
    val hasStoredSession: Boolean = false,
    val signInProviders: SignInProviders = SignInProviders(),
    val accountError: String? = null, val accountNotice: String? = null, val verificationPending: Boolean = false
)

class FishingViewModel(private val repository: FishingRepository = FishingRepository()) : ViewModel() {
    private val recommendationEngine = RecommendationEngine()
    private var recommendationJob: Job? = null
    private var tideJob: Job? = null
    private var fishRulesJob: Job? = null
    private var fishIdentifyGeneration = 0L
    private var fishRulesAreaManuallySelected = false
    private var accountVersion = 0
    private val savedSearchStation = tideStations.firstOrNull { it.id == SearchPreferencesStore.savedStationId() }
    private val savedSearchMode = if (SearchPreferencesStore.savedMode() == SearchLocationMode.SPECIFIC_LOCATION.name && savedSearchStation != null)
        SearchLocationMode.SPECIFIC_LOCATION else SearchLocationMode.NEAR_ME
    private val savedManualOrigin = searchOrigins.firstOrNull { it.name == SearchPreferencesStore.savedManualOriginName() }
    private val today = LocalDate.now(nzZone)
    private val savedDateLabel = SearchPreferencesStore.savedDateLabel()?.takeIf {
        it in listOf("Today", "In 3 days", "Next 7 days", "This weekend", "Custom")
    } ?: "In 3 days"
    private val savedDates = if (savedDateLabel == "Custom") {
        SearchPreferencesStore.savedCustomDates()?.let { (start, end) ->
            val validStart = start.coerceIn(today, today.plusDays(15))
            validStart to end.coerceIn(validStart, today.plusDays(15))
        } ?: (today to today)
    } else presetFishingDates(savedDateLabel, today) ?: (today.plusDays(3) to today.plusDays(3))
    private val savedHoursMode = SearchPreferencesStore.savedHoursMode()
    private val savedCustomHours = SearchPreferencesStore.savedCustomHours()
    private val savedPreferredTime = when (savedHoursMode) {
        "ANYTIME" -> null
        "CUSTOM" -> savedCustomHours?.let { PreferredTimeRange(it.first, it.second) } ?: suggestedHours
        else -> suggestedHours
    }
    private val _state = MutableStateFlow(FishingUiState(
        hasStoredSession = AccountSessionStore.token() != null,
        boat = SearchPreferencesStore.savedBoat(),
        dateLabel = savedDateLabel, dateStart = savedDates.first, dateEnd = savedDates.second,
        radiusKm = SearchPreferencesStore.savedRadiusKm() ?: 100,
        preferredTime = savedPreferredTime,
        preferredTimeIsSuggested = savedHoursMode != "ANYTIME" && !(savedHoursMode == "CUSTOM" && savedCustomHours != null),
        preference = runCatching { WindowPriority.valueOf(SearchPreferencesStore.savedPriority() ?: "WEATHER") }.getOrDefault(WindowPriority.WEATHER),
        landPreferences = SearchPreferencesStore.savedLandPreferences(),
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
        if (value !in 0..11 || old.tab == value) return
        _state.value = old.copy(tab = value)
        val features = listOf("home", "map", "tide", "trip_planning", "fishing_rules", "account", "more", "settings", "feedback", "terms_privacy", "appearance", "weather")
        Analytics.track("screen_view", "screen" to features[value])
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
    fun setBoat(value: Boolean) { _state.value = _state.value.copy(boat = value, recommendationSearch = null); SearchPreferencesStore.saveBoat(value); if (_state.value.showResults) refreshRecommendations() }
    fun setWindowPriority(value: WindowPriority) {
        _state.value = _state.value.copy(preference = value, recommendationSearch = null)
        SearchPreferencesStore.savePriority(value.name)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setDate(value: String) {
        val today = LocalDate.now(nzZone)
        val range = presetFishingDates(value, today) ?: return
        _state.value = _state.value.copy(dateLabel = value, dateStart = range.first, dateEnd = range.second, recommendationSearch = null)
        SearchPreferencesStore.saveDate(value, range.first, range.second)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setCustomDates(start: LocalDate, end: LocalDate) {
        val today = LocalDate.now(nzZone)
        if (start < today || end < start || end > today.plusDays(15)) return
        _state.value = _state.value.copy(dateLabel = "Custom", dateStart = start, dateEnd = end, recommendationSearch = null)
        SearchPreferencesStore.saveDate("Custom", start, end)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setRadius(km: Int) {
        if (km !in listOf(10, 30, 50, 100, 200, 300, 400, 500)) return
        _state.value = _state.value.copy(radiusKm = km, recommendationSearch = null)
        SearchPreferencesStore.saveRadiusKm(km)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setLandPreferences(value: LandPreferences) {
        _state.value = _state.value.copy(landPreferences = value, recommendationSearch = null)
        SearchPreferencesStore.saveLandPreferences(value)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setSuggestedHours() {
        _state.value = _state.value.copy(preferredTime = suggestedHours, preferredTimeIsSuggested = true, recommendationSearch = null)
        SearchPreferencesStore.saveHours("SUGGESTED")
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setPreferredHours(start: LocalTime, end: LocalTime) {
        if (start == end) return
        _state.value = _state.value.copy(preferredTime = PreferredTimeRange(start, end), preferredTimeIsSuggested = false, recommendationSearch = null)
        SearchPreferencesStore.saveHours("CUSTOM", start, end)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun clearPreferredHours() {
        _state.value = _state.value.copy(preferredTime = null, preferredTimeIsSuggested = false, recommendationSearch = null)
        SearchPreferencesStore.saveHours("ANYTIME")
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
        if (id != null) Analytics.track("rules_area_selected", "area_id" to id)
        fishRulesAreaManuallySelected = id != null
        _state.value = _state.value.copy(fishRulesAreaId = id, fishRulesAreaIsSuggested = false)
        refreshFishRulesForSelection()
    }
    fun syncRulesAreaFromDeviceLocation(point: GeoPoint) {
        if (fishRulesAreaManuallySelected) return
        val suggestion = suggestedRulesAreaId(point)
        if (_state.value.fishRulesAreaId != suggestion || _state.value.fishRulesAreaIsSuggested != (suggestion != null)) {
            _state.value = _state.value.copy(fishRulesAreaId = suggestion,
                fishRulesAreaIsSuggested = suggestion != null)
            refreshFishRulesForSelection()
        }
    }
    fun resetFishRulesAreaToCurrentLocation() {
        fishRulesAreaManuallySelected = false
        val point = _state.value.deviceLocation ?: _state.value.tideDeviceLocation
        val suggestion = point?.let(::suggestedRulesAreaId)
        _state.value = _state.value.copy(fishRulesAreaId = suggestion,
            fishRulesAreaIsSuggested = suggestion != null)
        refreshFishRulesForSelection()
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
        Analytics.track("tide_station_selected", "station_id" to value.id)
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
    fun openSpot(value: Recommendation) {
        Analytics.track("spot_opened", "spot_id" to recommendationKey(value), "mode" to if (value.boat) "boat" else "land")
        _state.value = _state.value.copy(selectedSpot = value)
    }
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
                    query.preferredTime, query.selectedSearchStation.takeIf { query.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION },
                    priority = query.preference, land = query.landPreferences)
                _state.value = _state.value.copy(recommendationSearch = result, recommendationsLoading = false)
                Analytics.track("search_run", "mode" to if (query.boat) "boat" else "land", "radius_km" to query.radiusKm,
                    "date_preset" to query.dateLabel, "result_count" to result.items.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(recommendationsLoading = false, recommendationsError = error.message ?: "Forecasts are unavailable. Try again.")
            }
        }
    }
    fun setFishPhoto(uri: Uri?) {
        fishIdentifyGeneration += 1
        fishRulesJob?.cancel()
        _state.value = _state.value.copy(fishPhoto = uri, fishCheck = null, fishError = null,
            fishChecking = false, fishRulesLoading = false, fishRulesError = null)
    }
    fun reportUnreadableFishPhoto() {
        fishIdentifyGeneration += 1
        fishRulesJob?.cancel()
        _state.value = _state.value.copy(fishCheck = null, fishChecking = false,
            fishRulesLoading = false, fishRulesError = null,
            fishError = "Could not read this photo. Choose another image and try again.")
    }
    fun identifyFish(image: ByteArray, point: GeoPoint, hasDeviceLocation: Boolean) {
        if (_state.value.fishChecking) return
        val accountRequestVersion = accountVersion
        val requestGeneration = ++fishIdentifyGeneration
        if (hasDeviceLocation) updateLocation(point)
        _state.value = _state.value.copy(fishChecking = true, fishError = null)
        Analytics.track("fish_identify_started")
        val requestedPhoto = _state.value.fishPhoto
        val selectedArea = _state.value.fishRulesAreaId
        viewModelScope.launch {
            runCatching { repository.identifyFish(image, selectedArea) }
                .onSuccess { result ->
                    Analytics.track("fish_identify_succeeded", "is_fish" to result.isFish, "confidence_level" to AnalyticsPayload.confidenceLevel(result.confidence))
                    val current = _state.value
                    if (requestGeneration == fishIdentifyGeneration && current.fishPhoto == requestedPhoto) {
                        fishRulesJob?.cancel()
                        _state.value = current.copy(fishCheck = result, fishChecking = false,
                            fishRulesLoading = false, fishRulesError = null,
                            account = current.account?.copy(fishIdentityQuota = result.fishIdentityQuota ?: current.account.fishIdentityQuota))
                        if (result.isFish && result.areaId != _state.value.fishRulesAreaId) refreshFishRulesForSelection()
                    }
                }
                .onFailure {
                    Analytics.track("fish_identify_failed", "error_code" to Analytics.errorCode(it))
                    val current = _state.value
                    if (requestGeneration == fishIdentifyGeneration && current.fishPhoto == requestedPhoto)
                        _state.value = current.copy(fishChecking = false,
                            fishError = it.message ?: "Could not identify this photo.",
                            account = current.account?.copy(fishIdentityQuota =
                                (it as? AccountRequestException)?.fishIdentityQuota ?: current.account.fishIdentityQuota))
                }
            runCatching { repository.currentAccount() }.onSuccess { account ->
                if (accountRequestVersion == accountVersion && account != null) _state.value = _state.value.copy(
                    account = account.copy(fishIdentityQuota = account.fishIdentityQuota ?: _state.value.account?.fishIdentityQuota))
            }
        }
    }
    fun retryFishRules() = refreshFishRulesForSelection(force = true)

    private fun refreshFishRulesForSelection(force: Boolean = false) {
        fishRulesJob?.cancel()
        val current = _state.value
        val check = current.fishCheck ?: return
        if (!check.isFish) return
        val areaId = current.fishRulesAreaId
        if (!force && areaId == check.areaId && !current.fishRulesLoading) return
        val cleared = check.copy(areaId = areaId, areaName = if (areaId == null) "Choose an MPI fishing area" else check.areaName,
            fishRules = emptyList(), rulesReviewedAt = null,
            rulesNeedsReview = false, rulesSourceUrl = null, areaSelectionRequired = areaId == null)
        if (areaId == null) {
            _state.value = current.copy(fishCheck = cleared, fishRulesLoading = false, fishRulesError = null)
            return
        }
        val photo = current.fishPhoto
        _state.value = current.copy(fishCheck = cleared, fishRulesLoading = true, fishRulesError = null)
        fishRulesJob = viewModelScope.launch {
            try {
                val rules = repository.fishRules(check.commonName, areaId)
                val latest = _state.value
                if (latest.fishPhoto == photo && latest.fishRulesAreaId == areaId && latest.fishCheck?.commonName == check.commonName) {
                    _state.value = latest.copy(fishCheck = latest.fishCheck.copy(areaId = rules.areaId,
                        areaName = rules.areaName, fishRules = rules.fishRules, rulesReviewedAt = rules.rulesReviewedAt,
                        rulesNeedsReview = rules.rulesNeedsReview, rulesSourceUrl = rules.rulesSourceUrl,
                        areaSelectionRequired = false), fishRulesLoading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val latest = _state.value
                if (latest.fishPhoto == photo && latest.fishRulesAreaId == areaId && latest.fishCheck?.commonName == check.commonName)
                    _state.value = latest.copy(fishRulesLoading = false,
                        fishRulesError = "Could not load this area's saved limits. Check the official MPI rules.")
            }
        }
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
                    Analytics.track("sign_up_succeeded")
                    if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                        verificationPending = true, accountNotice = notice)
                } else {
                    val account = repository.signIn(email, password)
                    Analytics.track("sign_in_succeeded", "method" to "password")
                    if (version == accountVersion) _state.value = _state.value.copy(account = account,
                        hasStoredSession = true,
                        accountBusy = false, verificationPending = false, accountError = null, accountNotice = "You’re signed in.")
                    refreshSignInProviders()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Analytics.track("sign_in_failed", "method" to if (createAccount) "sign_up" else "password", "error_code" to Analytics.errorCode(error))
                if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                    verificationPending = _state.value.verificationPending || (error as? AccountRequestException)?.code in
                        setOf("email_not_verified", "email_delivery_failed"),
                    accountError = error.message ?: if (createAccount) "Could not create account." else "Could not sign in.")
            }
        }
    }
    fun refreshSignInProviders() {
        viewModelScope.launch {
            try { _state.value = _state.value.copy(signInProviders = repository.signInProviders()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Email sign-in remains available if provider discovery fails. */ }
        }
    }

    fun startSocialSignIn(provider: String, openBrowser: (String) -> Unit) {
        Analytics.track("social_sign_in_started", "method" to provider)
        val version = ++accountVersion
        _state.value = _state.value.copy(accountBusy = true, accountLoading = false, accountError = null, accountNotice = null)
        viewModelScope.launch {
            try {
                val url = repository.startSocialSignIn(provider, _state.value.account != null)
                if (version != accountVersion) return@launch
                openBrowser(url)
                _state.value = _state.value.copy(accountBusy = false, accountNotice = "Finish signing in in your browser.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                AccountSessionStore.clearOAuthSecret()
                if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                    accountError = error.message ?: "Could not start sign-in.")
            }
        }
    }

    fun completeSocialSignIn(uri: Uri?) {
        if (uri?.scheme != "nz.fishingnz.app" || uri.host != "auth" || uri.path != "/callback") return
        val secret = AccountSessionStore.consumeOAuthSecret() ?: return
        val version = ++accountVersion
        selectTab(5)
        val error = uri.getQueryParameter("oauth_error")
        if (error != null) {
            _state.value = _state.value.copy(accountBusy = false, accountLoading = false, accountNotice = null,
                accountError = when (error) {
                    "cancelled" -> "Sign-in was cancelled. You can try again."
                    "account_exists" -> "This account already exists or is connected elsewhere. Sign in with your usual method, then connect Google or Apple from your profile."
                    else -> "Could not finish sign-in. Please try again."
                })
            return
        }
        val code = uri.getQueryParameter("oauth_code") ?: return
        _state.value = _state.value.copy(accountBusy = true, accountLoading = false, accountError = null, accountNotice = null)
        viewModelScope.launch {
            try {
                val account = repository.completeSocialSignIn(code, secret)
                Analytics.track("sign_in_succeeded", "method" to "social")
                if (version == accountVersion) _state.value = _state.value.copy(account = account, hasStoredSession = true,
                    accountBusy = false, verificationPending = false, accountNotice = "You’re signed in.")
                refreshSignInProviders()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                    accountError = error.message ?: "Could not finish sign-in. Please try again.")
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
                Analytics.track("sign_out")
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
    fun changePassword(currentPassword: String, newPassword: String) {
        _state.value = _state.value.copy(accountBusy = true, accountError = null, accountNotice = null)
        viewModelScope.launch { runCatching { repository.changePassword(currentPassword, newPassword) }
            .onSuccess {
                Analytics.track("password_changed")
                _state.value = _state.value.copy(accountBusy = false, accountNotice = "Password changed. Your other devices were signed out.")
            }
            .onFailure { _state.value = _state.value.copy(accountBusy = false, accountError = it.message ?: "Could not change your password.") } }
    }
    fun signOutEverywhere() {
        val version = ++accountVersion
        _state.value = _state.value.copy(accountBusy = true, accountLoading = false, accountError = null, accountNotice = null)
        viewModelScope.launch {
            try {
                repository.signOutEverywhere()
                Analytics.track("signed_out_everywhere")
                if (version == accountVersion) _state.value = _state.value.copy(account = null, accountBusy = false,
                    hasStoredSession = false, verificationPending = false, accountNotice = "You’re signed out of every device.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                    accountError = error.message ?: "Could not sign out of every device. Please try again.")
            }
        }
    }
    fun deleteAccount() {
        if (_state.value.accountBusy) return
        val version = ++accountVersion
        _state.value = _state.value.copy(accountBusy = true, accountLoading = false, accountError = null, accountNotice = null)
        viewModelScope.launch {
            try {
                repository.deleteAccount()
                if (version != accountVersion) return@launch
                fishIdentifyGeneration += 1
                fishRulesJob?.cancel()
                _state.value = _state.value.copy(account = null, accountBusy = false, hasStoredSession = false,
                    signInProviders = SignInProviders(), verificationPending = false,
                    fishPhoto = null, fishCheck = null, fishChecking = false, fishError = null,
                    fishRulesLoading = false, fishRulesError = null, savedSpots = emptySet(),
                    savedRecommendations = emptyList(), activeTrip = null,
                    accountNotice = "Your account and associated data have been deleted.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                    accountError = error.message ?: "Could not delete your account. Please try again.")
            }
        }
    }
    fun sendFeedback(category: String, message: String, rating: Int) {
        _state.value = _state.value.copy(accountBusy = true, accountError = null, accountNotice = null)
        viewModelScope.launch { runCatching { repository.sendFeedback(category, message, rating) }
            .onSuccess {
                Analytics.track("feedback_sent")
                _state.value = _state.value.copy(accountBusy = false, accountNotice = "Thanks for your feedback.")
            }
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
