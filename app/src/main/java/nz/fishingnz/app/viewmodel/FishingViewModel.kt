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
import nz.fishingnz.app.data.ConditionPlace
import nz.fishingnz.app.data.FishingRepository
import nz.fishingnz.app.data.AccountRequestException
import nz.fishingnz.app.data.AccountSessionStore
import nz.fishingnz.app.data.CatchConditionsLookup
import nz.fishingnz.app.data.CatchLogStore
import nz.fishingnz.app.data.PlaceConditionsRepository
import nz.fishingnz.app.data.RulesRepository
import nz.fishingnz.app.data.AccountSync
import nz.fishingnz.app.data.SyncStore
import nz.fishingnz.app.data.TripReminders
import nz.fishingnz.app.data.TripStore
import nz.fishingnz.app.data.Analytics
import nz.fishingnz.app.data.AnalyticsPayload
import nz.fishingnz.app.data.PrivacyPreferences
import nz.fishingnz.app.data.SignInProviders
import nz.fishingnz.app.data.RecommendationEngine
import nz.fishingnz.app.data.SearchPreferencesStore
import nz.fishingnz.app.data.SharedWindowLink
import nz.fishingnz.app.data.ShareService
import nz.fishingnz.app.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

private val nzZone = ZoneId.of("Pacific/Auckland")

/** The date choices on the plan page, in the order they are shown. */
internal val fishingDatePresets = listOf("Today", "Tomorrow", "This weekend", "Next 3 days", "Next 7 days")

internal fun presetFishingDates(value: String, today: LocalDate): Pair<LocalDate, LocalDate>? = when (value) {
    "Today" -> today to today
    "Tomorrow" -> today.plusDays(1) to today.plusDays(1)
    "Next 3 days" -> today to today.plusDays(2)
    "Next 7 days" -> today to today.plusDays(6)
    "This weekend" -> {
        val saturday = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.minusDays(1)
            else today.plusDays((DayOfWeek.SATURDAY.value - today.dayOfWeek.value + 7L) % 7)
        maxOf(today, saturday) to saturday.plusDays(1)
    }
    else -> null
}

enum class SearchLocationMode { NEAR_ME, SPECIFIC_LOCATION }

/** What "Log this catch" carries from fish identification into a new catch log entry. */
data class CatchPrefill(val species: String, val photo: Uri?, val rulesAreaId: String?)

data class FishingUiState(
    val tab: Int = 0, val boat: Boolean = false, val dateLabel: String = "Next 3 days", val dateStart: LocalDate = LocalDate.now(nzZone), val dateEnd: LocalDate = LocalDate.now(nzZone).plusDays(2),
    val radiusKm: Int = 50, val timeChoice: TimeChoice = TimeChoice.DAYTIME, val customHours: PreferredTimeRange? = null,
    val preference: WindowPriority = WindowPriority.WEATHER,
    val tidePhase: TidePhase = TidePhase.AROUND_HIGH, val sessionHours: Int = 2,
    val landPreferences: LandPreferences = LandPreferences(),
    /** NEAR_ME searches the radius around the origin; SPECIFIC_LOCATION searches just the chosen tide station or spot. */
    val searchLocationMode: SearchLocationMode = SearchLocationMode.NEAR_ME, val selectedSearchStation: TideStation? = null,
    val selectedSearchSpot: FishingSpot? = null,
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
    val accountError: String? = null, val accountNotice: String? = null, val verificationPending: Boolean = false,
    val sharedWindowNotice: String? = null,
    /** A place the Map tab should centre on and open, set by "Show on map" on a fishing window. */
    val mapFocus: ConditionPlace? = null,
    /** Every catch log entry on this phone, including deleted ones kept for sync; see [CatchLog.visible]. */
    val catches: List<CatchEntry> = emptyList(),
    val catchPrefill: CatchPrefill? = null,
    /** When the active trip's forecast is checked again (see TripCheck), or null when no check fits. */
    val tripCheckAt: Instant? = null,
    /** The back-by time in the trip plan shared with someone, if one was shared. */
    val tripBackBy: Instant? = null,
    /** Account sync for trips and the catch log: on for this device, and how the last sync went. */
    val syncEnabled: Boolean = false, val syncing: Boolean = false, val syncError: String? = null, val lastSyncedAt: Instant? = null
)

/** The fixed hours a search keeps to; null for Daytime (sunrise to sunset) and Any time. */
val FishingUiState.searchHours: PreferredTimeRange? get() = if (timeChoice == TimeChoice.CUSTOM) customHours else timeChoice.hours

/** The tide station or fishing spot picked as the place, whether searched alone or as the centre of a radius. */
val FishingUiState.searchPlaceName: String? get() = selectedSearchSpot?.name ?: selectedSearchStation?.name

/** True when the plan searches just one place and that place is known. */
val FishingUiState.searchesOnePlace: Boolean get() = searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION && searchPlaceName != null

class FishingViewModel(private val repository: FishingRepository = FishingRepository()) : ViewModel() {
    private val recommendationEngine = RecommendationEngine()
    private var recommendationJob: Job? = null
    private var tideJob: Job? = null
    private var fishRulesJob: Job? = null
    private var fishIdentifyGeneration = 0L
    private var fishRulesAreaManuallySelected = false
    private var accountVersion = 0
    private val savedSearchSpot = SearchPreferencesStore.savedSpotName()?.let { name -> fishingSpots.firstOrNull { it.name == name } }
    private val savedSearchStation = tideStations.firstOrNull { it.id == SearchPreferencesStore.savedStationId() }.takeIf { savedSearchSpot == null }
    private val savedSearchMode = if (SearchPreferencesStore.savedMode() == SearchLocationMode.SPECIFIC_LOCATION.name && (savedSearchStation != null || savedSearchSpot != null))
        SearchLocationMode.SPECIFIC_LOCATION else SearchLocationMode.NEAR_ME
    private val savedManualOrigin = SearchPreferencesStore.savedManualOrigin()
    private val today = LocalDate.now(nzZone)
    // "In 3 days" (one day, three days ahead) became "Next 3 days" in 1.0.32.
    private val savedDateLabel = SearchPreferencesStore.savedDateLabel()?.let { if (it == "In 3 days") "Next 3 days" else it }
        ?.takeIf { it in fishingDatePresets || it == "Custom" } ?: "Next 3 days"
    private val savedDates = if (savedDateLabel == "Custom") {
        SearchPreferencesStore.savedCustomDates()?.let { (start, end) ->
            val validStart = start.coerceIn(today, today.plusDays(15))
            validStart to end.coerceIn(validStart, today.plusDays(15))
        } ?: (today to today)
    } else presetFishingDates(savedDateLabel, today) ?: (today to today.plusDays(2))
    private val savedCustomHours = SearchPreferencesStore.savedCustomHours()?.let { PreferredTimeRange(it.first, it.second) }
    // Before 1.0.32 the default hours were 7 AM–9 PM ("SUGGESTED"); Daytime replaces them.
    private val savedTimeChoice = when (val mode = SearchPreferencesStore.savedHoursMode()) {
        "CUSTOM" -> if (savedCustomHours != null) TimeChoice.CUSTOM else TimeChoice.DAYTIME
        null, "SUGGESTED" -> TimeChoice.DAYTIME
        else -> runCatching { TimeChoice.valueOf(mode) }.getOrDefault(TimeChoice.DAYTIME)
    }
    private val savedPlace = savedSearchSpot?.let { SearchOrigin(it.name, GeoPoint(it.latitude, it.longitude)) }
        ?: savedSearchStation?.let { SearchOrigin(it.name, GeoPoint(it.latitude, it.longitude)) }
    // A radius around a picked spot or station searches from that place; a town or the phone's location drops the place.
    private val keepsPlace = savedSearchMode == SearchLocationMode.SPECIFIC_LOCATION || (savedPlace != null && savedManualOrigin?.name == savedPlace.name)
    private val savedOrigin = if (savedSearchMode == SearchLocationMode.SPECIFIC_LOCATION) savedPlace else savedManualOrigin
    private val _state = MutableStateFlow(FishingUiState(
        hasStoredSession = AccountSessionStore.token() != null,
        boat = SearchPreferencesStore.savedBoat(),
        dateLabel = savedDateLabel, dateStart = savedDates.first, dateEnd = savedDates.second,
        radiusKm = SearchPreferencesStore.savedRadiusKm()?.let(::nearestSearchRadius) ?: 50,
        timeChoice = savedTimeChoice, customHours = savedCustomHours,
        preference = WindowPriority.saved(SearchPreferencesStore.savedPriority()),
        tidePhase = TidePhase.saved(SearchPreferencesStore.savedTidePhase()), sessionHours = SearchPreferencesStore.savedSessionHours(),
        landPreferences = SearchPreferencesStore.savedLandPreferences(),
        searchLocationMode = savedSearchMode,
        selectedSearchStation = savedSearchStation.takeIf { keepsPlace }, selectedSearchSpot = savedSearchSpot.takeIf { keepsPlace },
        manualOriginSelected = savedSearchMode == SearchLocationMode.NEAR_ME && savedOrigin != null,
        location = savedOrigin?.point ?: GeoPoint(-36.85, 174.76),
        originName = savedOrigin?.name
    ))
    val state: StateFlow<FishingUiState> = _state.asStateFlow()
    private val rulesCache = mutableMapOf<Pair<String, String>, FishRulesResult>()
    init {
        // Every area's rules and the species pictures stay on the phone for use with no signal.
        viewModelScope.launch { nz.fishingnz.app.data.RulesSync.sync() }
        val (saved, active) = TripStore.load()
        _state.value = _state.value.copy(catches = CatchLogStore.load(), savedRecommendations = saved,
            savedSpots = saved.map(::recommendationKey).toSet(), activeTrip = active,
            tripCheckAt = active?.let { TripCheck.checkTime(Instant.ofEpochSecond(it.startsAtEpochSeconds), Instant.now()) },
            tripBackBy = TripStore.backBy().takeIf { active != null },
            syncEnabled = SyncStore.enabled(), lastSyncedAt = SyncStore.lastSyncedAt())
        refreshStationTide()
        prefetchForOffline(listOfNotNull(active) + saved)
        if (_state.value.originName != null) refreshConditions()
        refreshAccount()
    }
    fun selectTab(value: Int) {
        val old = _state.value
        if (value !in 0..12 || old.tab == value) return
        _state.value = old.copy(tab = value)
        val features = listOf("home", "map", "tide", "trip_planning", "fishing_rules", "account", "more", "settings", "feedback", "terms_privacy", "appearance", "weather", "catch_log")
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
            current.tab == 7 || current.tab in 3..5 || current.tab == 12 -> selectTab(6)
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
    /** A distance searches around the current origin; with a picked spot or station, around that place. */
    fun setRadius(km: Int) {
        if (km !in searchRadiiKm) return
        val old = _state.value
        val aroundPlace = old.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION && old.searchPlaceName != null
        _state.value = old.copy(radiusKm = km, searchLocationMode = SearchLocationMode.NEAR_ME,
            manualOriginSelected = old.manualOriginSelected || aroundPlace, recommendationSearch = null)
        SearchPreferencesStore.saveRadiusKm(km)
        if (aroundPlace) {
            SearchPreferencesStore.save(SearchLocationMode.NEAR_ME.name, old.selectedSearchStation?.id)
            SearchPreferencesStore.saveManualOrigin(SearchOrigin(old.originName ?: old.searchPlaceName!!, old.location))
        }
        if (_state.value.showResults) refreshRecommendations()
    }
    /** "Just this spot": search only the picked fishing spot or tide station. */
    fun searchJustThisPlace() {
        val old = _state.value
        val name = old.searchPlaceName ?: return
        val point = old.selectedSearchSpot?.let { GeoPoint(it.latitude, it.longitude) }
            ?: old.selectedSearchStation!!.let { GeoPoint(it.latitude, it.longitude) }
        _state.value = old.copy(searchLocationMode = SearchLocationMode.SPECIFIC_LOCATION, location = point, originName = name,
            recommendationSearch = null, locationNotice = null)
        SearchPreferencesStore.save(SearchLocationMode.SPECIFIC_LOCATION.name, old.selectedSearchStation?.id)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setLandPreferences(value: LandPreferences) {
        _state.value = _state.value.copy(landPreferences = value, recommendationSearch = null)
        SearchPreferencesStore.saveLandPreferences(value)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setTimeChoice(choice: TimeChoice) {
        if (choice == TimeChoice.CUSTOM) return
        _state.value = _state.value.copy(timeChoice = choice, recommendationSearch = null)
        SearchPreferencesStore.saveHours(choice.name)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setCustomHours(start: LocalTime, end: LocalTime) {
        if (start == end) return
        _state.value = _state.value.copy(timeChoice = TimeChoice.CUSTOM, customHours = PreferredTimeRange(start, end), recommendationSearch = null)
        SearchPreferencesStore.saveHours(TimeChoice.CUSTOM.name, start, end)
        if (_state.value.showResults) refreshRecommendations()
    }
    /** "What matters most?": one choice that sets the window preference and, on land, the tie-break. */
    fun setWhatMatters(priority: WindowPriority, land: LandPriority) {
        val old = _state.value
        if (old.landPreferences.priority != land) setLandPreferences(old.landPreferences.copy(priority = land))
        if (old.preference != priority) setWindowPriority(priority)
    }
    fun setTidePhase(value: TidePhase) {
        _state.value = _state.value.copy(tidePhase = value, recommendationSearch = null)
        SearchPreferencesStore.saveTidePhase(value.name)
        if (_state.value.showResults) refreshRecommendations()
    }
    fun setSessionHours(value: Int) {
        if (value !in sessionLengths) return
        _state.value = _state.value.copy(sessionHours = value, recommendationSearch = null)
        SearchPreferencesStore.saveSessionHours(value)
        if (_state.value.showResults) refreshRecommendations()
    }
    /** "Reset to recommended" for everything under More options. */
    fun resetMoreOptions() {
        setLandPreferences(LandPreferences(arrivalMinutes = 0, returnMinutes = 0))
        setWindowPriority(WindowPriority.WEATHER)
        setTidePhase(TidePhase.AROUND_HIGH)
        setSessionHours(2)
    }
    fun selectSearchStation(station: TideStation) = selectSearchPlace(station.name, GeoPoint(station.latitude, station.longitude), station, null)
    fun selectSearchSpot(spot: FishingSpot) = selectSearchPlace(spot.name, GeoPoint(spot.latitude, spot.longitude), null, spot)
    /** A picked tide station or fishing spot is searched alone until a distance is chosen. */
    private fun selectSearchPlace(name: String, point: GeoPoint, station: TideStation?, spot: FishingSpot?) {
        val old = _state.value
        _state.value = old.copy(searchLocationMode = SearchLocationMode.SPECIFIC_LOCATION, selectedSearchStation = station,
            selectedSearchSpot = spot, location = point, originName = name, manualOriginSelected = false,
            weather = null, tide = null, recommendationSearch = null, locationNotice = null)
        SearchPreferencesStore.save(SearchLocationMode.SPECIFIC_LOCATION.name, station?.id)
        SearchPreferencesStore.saveSpot(spot?.name)
        refreshConditions()
        if (_state.value.showResults) refreshRecommendations()
    }
    fun useDeviceSearchOrigin() {
        val old = _state.value
        val point = old.deviceLocation
        _state.value = old.copy(searchLocationMode = SearchLocationMode.NEAR_ME, manualOriginSelected = false,
            selectedSearchStation = null, selectedSearchSpot = null,
            location = point ?: old.location, originName = point?.let(::nearestCityName), locating = false,
            weather = null, tide = null, recommendationSearch = null, locationNotice = null)
        SearchPreferencesStore.save(SearchLocationMode.NEAR_ME.name, null)
        SearchPreferencesStore.saveSpot(null)
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
            selectedSearchStation = null, selectedSearchSpot = null,
            location = origin.point, originName = origin.name, locating = false,
            locationNotice = null, weather = null, tide = null, recommendationSearch = null, selectedStation = nearest)
        SearchPreferencesStore.save(SearchLocationMode.NEAR_ME.name, null)
        SearchPreferencesStore.saveSpot(null)
        SearchPreferencesStore.saveManualOrigin(origin)
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
    /** Shows the window in a shared link the app was opened with. Other addresses are ignored. */
    fun openSharedWindow(uri: Uri?) {
        val token = SharedWindowLink.tokenFrom(uri?.toString()) ?: return
        viewModelScope.launch {
            val window = ShareService.live.resolve(token)
            if (window == null) {
                _state.value = _state.value.copy(sharedWindowNotice = "This shared window couldn't be opened. The link may have expired or be damaged, or you may be offline.")
            } else {
                _state.value = _state.value.copy(showResults = false)
                openSpot(window)
            }
        }
    }
    fun dismissSharedWindowNotice() { _state.value = _state.value.copy(sharedWindowNotice = null) }
    fun closeSpot() { _state.value = _state.value.copy(selectedSpot = null) }
    /** Closes the window list and details and opens the Map tab on [place] (see Recommendation.mapPlace). */
    fun showOnMap(place: ConditionPlace) {
        _state.value = _state.value.copy(selectedSpot = null, showResults = false, mapFocus = place)
        selectTab(1)
    }
    fun mapFocusShown() { _state.value = _state.value.copy(mapFocus = null) }
    fun startTrip() {
        val trip = _state.value.selectedSpot ?: return
        _state.value = _state.value.copy(activeTrip = trip, selectedSpot = null, tripBackBy = null)
        TripStore.saveBackBy(null)
        saveTrips()
        prefetchForOffline(listOf(trip))
        _state.value = _state.value.copy(tripCheckAt = TripReminders.schedule(trip))
    }
    fun endTrip() {
        _state.value = _state.value.copy(activeTrip = null, tripCheckAt = null, tripBackBy = null)
        saveTrips()
        TripStore.saveBackBy(null)
        TripReminders.cancel()
    }
    /** After the trip plan is shared: remember the back-by time and remind the person then to check in. */
    fun tripPlanShared(backBy: Instant) {
        _state.value = _state.value.copy(tripBackBy = backBy)
        TripStore.saveBackBy(backBy)
        SyncStore.setTripsUpdatedAt(Instant.now().epochSecond)
        requestSync()
        TripReminders.scheduleCheckIn(backBy)
        Analytics.track("trip_plan_shared")
    }
    /** A link to the trip's window for the plan: short when the service answers, otherwise the long link. */
    suspend fun tripLink(trip: Recommendation): String? = ShareService.live.linkFor(trip)
    private fun saveTrips() {
        _state.value.let { TripStore.save(it.savedRecommendations, it.activeTrip) }
        SyncStore.setTripsUpdatedAt(Instant.now().epochSecond)
        requestSync()
    }

    private var syncJob: Job? = null
    /** Syncs a few seconds after the last change, so a burst of edits makes one request. */
    private fun requestSync(delayMillis: Long = 3_000) {
        if (!SyncStore.enabled() || _state.value.account == null) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch { kotlinx.coroutines.delay(delayMillis); syncNow() }
    }
    /** Sends this device's trips and catches and applies whatever is newer on the account. */
    fun syncNow() {
        if (!SyncStore.enabled() || _state.value.account == null || _state.value.syncing) return
        val start = _state.value
        val sentTrips = SyncStore.tripsUpdatedAt()
        _state.value = start.copy(syncing = true, syncError = null)
        viewModelScope.launch {
            try {
                val trips = AccountSync.Trips(start.savedRecommendations, start.activeTrip, start.tripBackBy)
                val response = repository.sync(AccountSync.items(trips, sentTrips, start.catches))
                val current = _state.value
                // Changes made while the request was out stay: they are sent with the next sync.
                if (current.catches != start.catches || SyncStore.tripsUpdatedAt() != sentTrips) {
                    _state.value = current.copy(syncing = false); requestSync(); return@launch
                }
                val merged = AccountSync.merge(response, sentTrips, current.catches)
                var next = current.copy(catches = merged.catches, syncing = false, lastSyncedAt = Instant.now())
                merged.trips?.let { remote ->
                    next = next.copy(savedRecommendations = remote.saved, savedSpots = remote.saved.map(::recommendationKey).toSet(),
                        activeTrip = remote.active, tripBackBy = remote.backBy)
                    TripStore.save(remote.saved, remote.active)
                    TripStore.saveBackBy(remote.backBy)
                    SyncStore.setTripsUpdatedAt(merged.tripsUpdatedAt)
                    if (remote.active?.startsAtEpochSeconds != current.activeTrip?.startsAtEpochSeconds || remote.active?.name != current.activeTrip?.name) {
                        next = next.copy(tripCheckAt = remote.active?.let(TripReminders::schedule))
                        if (remote.active == null) TripReminders.cancel() else prefetchForOffline(listOf(remote.active))
                    }
                }
                _state.value = next
                SyncStore.setLastSyncedAt(next.lastSyncedAt)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CatchLogStore.save(merged.catches) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _state.value = _state.value.copy(syncing = false, syncError = if ((error as? AccountRequestException)?.statusCode == 404)
                    "Sync isn't available on the service yet. Your trips and catch log are safe on this phone." else error.message ?: "Couldn't sync. It will try again later.")
            }
        }
    }
    /** Turns account sync on or off for this device; turning it off can also delete the account's synced copy. */
    fun setSyncEnabled(enabled: Boolean, deleteSyncedCopy: Boolean = false) {
        SyncStore.setEnabled(enabled)
        _state.value = _state.value.copy(syncEnabled = enabled, syncError = null)
        Analytics.track(if (enabled) "sync_enabled" else "sync_disabled")
        if (enabled) {
            // A first sync sends everything on this phone.
            if (SyncStore.tripsUpdatedAt() == 0L) SyncStore.setTripsUpdatedAt(Instant.now().epochSecond)
            syncNow()
        } else if (deleteSyncedCopy) viewModelScope.launch {
            runCatching { repository.deleteSynced() }
                .onSuccess { SyncStore.setLastSyncedAt(null); _state.value = _state.value.copy(lastSyncedAt = null, accountNotice = "The synced copy was deleted from your account.") }
                .onFailure { _state.value = _state.value.copy(syncError = "Couldn't delete the synced copy. Check your connection and try again.") }
        }
    }
    /**
     * Fetches the forecast, a year of LINZ tides and the MPI rules for each window's place while there is a signal,
     * so the active trip and saved spots still show conditions at the water. Failures are ignored.
     */
    private fun prefetchForOffline(windows: List<Recommendation>) {
        val points = windows.filter { it.latitude.isFinite() && it.longitude.isFinite() }
            .map { GeoPoint(it.latitude, it.longitude) }.distinct().take(6)
        if (points.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            points.forEach { point ->
                runCatching { PlaceConditionsRepository().load(point, 1) }
                runCatching { repository.tide(nearestTideStation(point), LocalDate.now(nzZone)) }
                suggestedRulesAreaId(point)?.let { area -> runCatching { RulesRepository().load(area) } }
            }
        }
    }
    fun toggleSaved(spot: Recommendation) {
        val current = _state.value
        val key = recommendationKey(spot)
        val saved = if (key in current.savedSpots) current.savedSpots - key else current.savedSpots + key
        val recommendations = if (key in current.savedSpots) current.savedRecommendations.filterNot { recommendationKey(it) == key }
            else current.savedRecommendations.filterNot { recommendationKey(it) == key } + spot
        _state.value = current.copy(savedSpots = saved, savedRecommendations = recommendations)
        saveTrips()
        if (key !in current.savedSpots) prefetchForOffline(listOf(spot))
    }
    /** Opens the catch log with a new entry for the fish just identified. */
    fun logIdentifiedCatch() {
        val check = _state.value.fishCheck?.takeIf { it.isFish } ?: return
        _state.value = _state.value.copy(catchPrefill = CatchPrefill(check.commonName, _state.value.fishPhoto, _state.value.fishRulesAreaId))
        selectTab(12)
    }
    fun catchPrefillUsed() { _state.value = _state.value.copy(catchPrefill = null) }
    /** "Log a catch" on the active trip: a new entry at the trip's spot. */
    fun logCatchOnTrip() {
        _state.value = _state.value.copy(catchPrefill = CatchPrefill("", null, _state.value.fishRulesAreaId))
        selectTab(12)
    }

    /** Adds or replaces [entry], keeps [photo] with it, then looks up the conditions at that time and place. */
    fun saveCatch(entry: CatchEntry, photo: Uri?) {
        val now = Instant.now().epochSecond
        val previous = _state.value.catches.firstOrNull { it.id == entry.id }
        val photoFile = photo?.let { CatchLogStore.keepPhoto(it, entry.id) } ?: entry.photoFile
        // A changed time or place needs fresh conditions; otherwise the recorded ones stay.
        val moved = previous == null || previous.caughtAtEpochSeconds != entry.caughtAtEpochSeconds ||
            previous.latitude != entry.latitude || previous.longitude != entry.longitude
        val saved = entry.copy(photoFile = photoFile, updatedAtEpochSeconds = now,
            conditions = if (moved) CatchConditions() else entry.conditions)
        updateCatches { list -> list.filterNot { it.id == saved.id } + saved }
        Analytics.track(if (previous == null) "catch_logged" else "catch_edited", "kept" to saved.kept, "has_length" to (saved.lengthCm != null))
        if (moved) viewModelScope.launch {
            val point = if (saved.latitude != null && saved.longitude != null) GeoPoint(saved.latitude, saved.longitude) else null
            val conditions = runCatching { CatchConditionsLookup.lookup(point, Instant.ofEpochSecond(saved.caughtAtEpochSeconds)) }.getOrNull()
                ?: return@launch
            updateCatches { list -> list.map { if (it.id == saved.id && it.updatedAtEpochSeconds == saved.updatedAtEpochSeconds) it.copy(conditions = conditions) else it } }
        }
    }
    /** Hides the entry and deletes its photo; the entry stays as a tombstone so account sync removes it everywhere. */
    fun deleteCatch(id: String) {
        val entry = _state.value.catches.firstOrNull { it.id == id } ?: return
        CatchLogStore.deletePhoto(entry.photoFile)
        updateCatches { list -> list.map { if (it.id == id) it.copy(deleted = true, photoFile = null, updatedAtEpochSeconds = Instant.now().epochSecond) else it } }
    }
    fun deleteAllCatches() {
        val now = Instant.now().epochSecond
        _state.value.catches.forEach { CatchLogStore.deletePhoto(it.photoFile) }
        updateCatches { list -> list.map { it.copy(deleted = true, photoFile = null, updatedAtEpochSeconds = now) } }
    }
    private fun updateCatches(change: (List<CatchEntry>) -> List<CatchEntry>) {
        val updated = change(_state.value.catches)
        _state.value = _state.value.copy(catches = updated)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { CatchLogStore.save(updated) }
        requestSync()
    }
    /** MPI rows for [species] in an area, for the catch log's size check. Remembered for this session. */
    suspend fun rulesFor(species: String, areaId: String): FishRulesResult {
        val key = species.trim().lowercase() to areaId
        rulesCache[key]?.let { return it }
        return repository.fishRules(species.trim(), areaId).also { rulesCache[key] = it }
    }

    fun refreshRecommendations() {
        recommendationJob?.cancel()
        val query = _state.value
        if (query.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION && query.searchPlaceName == null) {
            _state.value = query.copy(locating = false, recommendationsLoading = false,
                recommendationsError = "Choose a place in your plan before searching.", recommendationSearch = null)
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
                val single = query.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION
                val result = recommendationEngine.search(query.location, query.radiusKm, query.dateStart, query.dateEnd, query.boat,
                    query.searchHours, query.selectedSearchStation.takeIf { single },
                    priority = query.preference, land = query.landPreferences,
                    daylightOnly = query.timeChoice == TimeChoice.DAYTIME, specificSpot = query.selectedSearchSpot.takeIf { single },
                    tidePhase = query.tidePhase, sessionHours = query.sessionHours)
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
                if (account != null) syncNow()
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
                    syncNow()
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
                syncNow()
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
        PrivacyPreferences.setPhotoConsentRemembered(false)
        // Sync belongs to the signed-in account: another account signing in on this phone must not receive this log.
        SyncStore.setEnabled(false)
        _state.value = _state.value.copy(syncEnabled = false)
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
                SyncStore.setEnabled(false)
                _state.value = _state.value.copy(syncEnabled = false)
                PrivacyPreferences.setPhotoConsentRemembered(false)
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
                saveTrips()
                TripReminders.cancel()
                SyncStore.setEnabled(false)
                _state.value = _state.value.copy(syncEnabled = false, lastSyncedAt = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (version == accountVersion) _state.value = _state.value.copy(accountBusy = false,
                    accountError = error.message ?: "Could not delete your account. Please try again.")
            }
        }
    }
    fun sendFeedback(category: String, message: String, rating: Int?) {
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
        // The Tides widget follows the station on the Tide tab.
        nz.fishingnz.app.data.TideWidgetStation.set(station)
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
                // A connection failure reads as the iPhone app does; LINZ data problems keep their own explanation.
                val message = if (error is java.io.IOException) "Tide predictions are unavailable for ${station.name} on this date. Check your connection and try again."
                    else error.message ?: "Tide table unavailable."
                if (_state.value.selectedStation == station && _state.value.tideDate == date)
                    _state.value = _state.value.copy(tideLoading = false, tideError = message)
            }
        }
    }
}
