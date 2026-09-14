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
    val fishPhoto: Uri? = null, val fishCheck: FishCheck? = null, val fishChecking: Boolean = false, val savedSpots: Set<String> = emptySet(), val activeTrip: Recommendation? = null,
    val selectedSpot: Recommendation? = null, val showResults: Boolean = false
)

class FishingViewModel(private val repository: FishingRepository = FishingRepository()) : ViewModel() {
    private val _state = MutableStateFlow(FishingUiState())
    val state: StateFlow<FishingUiState> = _state.asStateFlow()
    init { refreshConditions(); refreshStationTide() }
    fun selectTab(value: Int) { _state.value = _state.value.copy(tab = value) }
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
    fun setFishPhoto(uri: Uri?) { _state.value = _state.value.copy(fishPhoto = uri, fishCheck = null) }
    fun identifyFish() { _state.value = _state.value.copy(fishChecking = true); viewModelScope.launch { _state.value = _state.value.copy(fishCheck = repository.identifyFish(), fishChecking = false) } }
    fun refreshConditions() { viewModelScope.launch { runCatching { repository.conditions(_state.value.location) }.onSuccess { _state.value = _state.value.copy(weather = it.first, tide = it.second, error = null) }.onFailure { _state.value = _state.value.copy(error = "Live conditions unavailable") } } }
    fun refreshStationTide() { viewModelScope.launch { runCatching { repository.tide(_state.value.selectedStation, _state.value.tideDate) }.onSuccess { _state.value = _state.value.copy(stationTide = it) } } }
}
