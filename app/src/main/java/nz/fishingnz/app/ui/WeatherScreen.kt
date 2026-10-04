package nz.fishingnz.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import nz.fishingnz.app.data.DailyWeather
import nz.fishingnz.app.data.HourlyWeather
import nz.fishingnz.app.data.LocalWeatherForecast
import nz.fishingnz.app.data.WeatherRepository
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.nearestCityName
import nz.fishingnz.app.model.tideStations
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val hourFormatter = DateTimeFormatter.ofPattern("ha", Locale.ENGLISH)
private val refreshedFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

private data class WeatherPlace(val id: String, val name: String, val point: GeoPoint?)

private class WeatherLocationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("weather_locations", Context.MODE_PRIVATE)

    fun saved(): List<String> = prefs.getString("saved_station_ids", "").orEmpty().split(',')
        .filter { id -> id.isNotEmpty() && tideStations.any { it.id == id } }.distinct()

    fun selected(): String = prefs.getString("selected_station_id", "current")
        .orEmpty().takeIf { it == "current" || tideStations.any { station -> station.id == it } } ?: "current"

    fun saveSaved(ids: List<String>) { prefs.edit().putString("saved_station_ids", ids.joinToString(",")).apply() }
    fun saveSelected(id: String) { prefs.edit().putString("selected_station_id", id).apply() }
}

private fun weatherPlaces(savedIds: List<String>, extraId: String?): List<WeatherPlace> {
    val ids = (savedIds + listOfNotNull(extraId)).distinct()
    return listOf(WeatherPlace("current", "Current location", null)) + ids.mapNotNull { id ->
        tideStations.firstOrNull { it.id == id }?.let {
            WeatherPlace(it.id, it.name, GeoPoint(it.latitude, it.longitude))
        }
    }
}

@Composable
fun WeatherScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { WeatherLocationStore(context) }
    val initiallySelected = remember(store) { store.selected() }
    var savedIds by remember(store) { mutableStateOf(store.saved()) }
    var selectedId by remember(store) { mutableStateOf(initiallySelected) }
    val places = remember(savedIds, selectedId) {
        weatherPlaces(savedIds, selectedId.takeIf { it != "current" && it !in savedIds })
    }
    val pager = rememberPagerState(initialPage = places.indexOfFirst { it.id == initiallySelected }.coerceAtLeast(0)) { places.size }
    val selected = places.firstOrNull { it.id == selectedId } ?: places.first()
    var pendingId by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var refreshIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(pendingId, places) {
        val target = pendingId ?: return@LaunchedEffect
        val index = places.indexOfFirst { it.id == target }
        if (index >= 0) {
            pager.scrollToPage(index)
            pendingId = null
        }
    }
    LaunchedEffect(pager.settledPage, pendingId, places) {
        // Only a completed swipe changes the selected location. A picker selection must
        // not be overwritten while the pager is still moving to its new page.
        if (pendingId == null) {
            places.getOrNull(pager.settledPage)?.id?.let { visibleId ->
                if (visibleId != selectedId) {
                    selectedId = visibleId
                    store.saveSelected(visibleId)
                }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Weather", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(selected.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { pickerOpen = true }) {
                Icon(Icons.Default.Search, contentDescription = "Choose weather location")
            }
            if (selected.id != "current") {
                val isSaved = selected.id in savedIds
                IconButton(onClick = {
                    pendingId = selected.id
                    if (isSaved) {
                        savedIds = savedIds.filterNot { it == selected.id }
                    } else {
                        savedIds = savedIds + selected.id
                    }
                    store.saveSaved(savedIds)
                }) {
                    Icon(if (isSaved) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isSaved) "Remove ${selected.name} from saved weather locations" else "Save ${selected.name} as a weather location")
                }
            }
            IconButton(onClick = { refreshIndex++ }) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh ${selected.name} weather")
            }
        }
        if (places.size > 1) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                places.forEach { place ->
                    OutlinedButton(onClick = {
                        selectedId = place.id
                        store.saveSelected(place.id)
                        pendingId = place.id
                    }) {
                        Text(place.name, color = if (place.id == selected.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Text("Swipe left or right to switch weather locations", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp))
            if (selected.id != "current" && selected.id !in savedIds) {
                Text("Tap the star to save this location for later", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
            }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f), key = { places[it].id }) { index ->
            val place = places[index]
            WeatherForecastPage(place, place.id == selected.id, refreshIndex)
        }
    }

    if (pickerOpen) WeatherLocationPicker(savedIds, onDismiss = { pickerOpen = false }) { id ->
        selectedId = id
        store.saveSelected(id)
        pendingId = id
        pickerOpen = false
    }
}

@Composable
private fun WeatherLocationPicker(savedIds: List<String>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query) {
        tideStations.sortedBy { it.name.lowercase(Locale.getDefault()) }
            .filter { it.name.contains(query, ignoreCase = true) || it.region.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weather locations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search locations") }, singleLine = true)
                Text("New Zealand coastal places", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item {
                        TextButton(onClick = { onSelect("current") }, modifier = Modifier.fillMaxWidth()) {
                            Text("Current location", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    items(matches.size) { index ->
                        val station = matches[index]
                        TextButton(onClick = { onSelect(station.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("${station.name}${if (station.id in savedIds) " ★" else ""}")
                                Text(station.region, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun WeatherForecastPage(place: WeatherPlace, isActive: Boolean, refreshIndex: Int) {
    val context = LocalContext.current
    val repository = remember { WeatherRepository() }
    var point by remember { mutableStateOf<GeoPoint?>(null) }
    var forecast by remember { mutableStateOf<LocalWeatherForecast?>(null) }
    var locationLoading by remember { mutableStateOf(false) }
    var weatherLoading by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var weatherMessage by remember { mutableStateOf<String?>(null) }
    var fetchIndex by remember { mutableIntStateOf(0) }
    var locationRequestVersion by remember { mutableIntStateOf(0) }

    fun locate() {
        locationRequestVersion++
        val requestVersion = locationRequestVersion
        locationLoading = true
        locationMessage = null
        point = null
        forecast = null
        requestCurrentLocation(context,
            onLocation = { found ->
                if (requestVersion == locationRequestVersion) {
                    locationLoading = false
                    point = found
                    fetchIndex++
                }
            },
            onUnavailable = {
                if (requestVersion == locationRequestVersion) {
                    locationLoading = false
                    point = null
                    forecast = null
                    locationMessage = "Your current location is unavailable. Check location services and try again."
                }
            })
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (!isActive) return@rememberLauncherForActivityResult
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true || results[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locate()
        else {
            point = null
            forecast = null
            locationMessage = "Allow location access to see the weather where you are."
        }
    }
    fun requestWeatherLocation() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) locate()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(isActive, refreshIndex) {
        if (!isActive) {
            locationRequestVersion++
            return@LaunchedEffect
        }
        if (place.point == null) requestWeatherLocation()
        else {
            point = place.point
            fetchIndex++
        }
    }
    LaunchedEffect(point, fetchIndex, isActive) {
        if (!isActive) return@LaunchedEffect
        val selectedPoint = point ?: return@LaunchedEffect
        weatherLoading = true
        weatherMessage = null
        try {
            forecast = repository.forecast(selectedPoint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            weatherMessage = "The forecast could not be loaded. Please try again."
        } finally {
            weatherLoading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (place.point == null && point != null) item {
            val city = nearestCityName(point!!)
            Text(if (city == "Current location") "At your current location" else "Near $city · current location",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (locationLoading || (weatherLoading && forecast == null)) item {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        locationMessage?.let { message -> item { WeatherNotice(message, ::requestWeatherLocation) } }
        weatherMessage?.let { message -> item { WeatherNotice(message) { fetchIndex++ } } }
        forecast?.let { data ->
            item { WeatherNowCard(data, weatherLoading) }
            item { WeatherHourlyCard(data.hourly) }
            item { WeatherWeekCard(data.daily) }
            item {
                Text(
                    "Forecast: Open-Meteo, refreshed ${data.fetchedAt.atZone(data.timeZone).format(refreshedFormatter)}. Conditions can differ at exposed fishing spots; check official marine warnings before heading out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WeatherNotice(message: String, retry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onSecondaryContainer)
            OutlinedButton(onClick = retry) { Text("Try again") }
        }
    }
}

@Composable
private fun WeatherNowCard(forecast: LocalWeatherForecast, updating: Boolean) {
    val now = forecast.current
    val heroBrush = Brush.linearGradient(listOf(Color(0xFF1B579B), Color(0xFF397FC6)))
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().background(heroBrush).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Current forecast", color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
                Text(now.observedAt.format(refreshedFormatter), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(now.code, now.isDay), null, tint = Color.White, modifier = Modifier.size(52.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("${now.temperatureC.roundToInt()}°", color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                    Text(weatherDescription(now.code), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (updating) Text("Updating…", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WeatherMetric("Feels like", "${now.feelsLikeC.roundToInt()}°C", Modifier.weight(1f))
                WeatherMetric("Wind", "${now.windKmh.roundToInt()} km/h", Modifier.weight(1f))
                WeatherMetric("Rain · 15 min", "${"%.1f".format(Locale.US, now.precipitationMm)} mm", Modifier.weight(1f))
            }
            Text("Wind from ${windDirection(now.windDirectionDegrees)} · gusts ${now.windGustKmh.roundToInt()} km/h · humidity ${now.humidityPercent}%",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WeatherMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WeatherHourlyCard(hours: List<HourlyWeather>) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Next 24 hours", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
            if (hours.isEmpty()) Text("Hourly forecast is unavailable.", modifier = Modifier.padding(horizontal = 18.dp))
            else Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hours.forEach { hour ->
                    Column(
                        Modifier.width(102.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(vertical = 12.dp, horizontal = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(hour.at.format(hourFormatter), style = MaterialTheme.typography.labelMedium)
                        Icon(weatherIcon(hour.code, hour.isDay ?: true), contentDescription = weatherDescription(hour.code), tint = MaterialTheme.colorScheme.primary)
                        Text(weatherDescription(hour.code), style = MaterialTheme.typography.labelSmall)
                        Text("${weatherNumber(hour.temperatureC)}°", fontWeight = FontWeight.Bold)
                        Text("Rain ${hour.rainChancePercent ?: "—"}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${weatherNumber(hour.windKmh)} km/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherWeekCard(days: List<DailyWeather>) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${days.size}-day forecast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Rain chance: highest hourly value · later days are an early outlook", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            days.forEachIndexed { index, day ->
                if (index > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (index == 0) "Today" else day.date.format(DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)), modifier = Modifier.width(62.dp), fontWeight = FontWeight.SemiBold)
                    Icon(weatherIcon(day.code, true), contentDescription = weatherDescription(day.code), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(weatherDescription(day.code), style = MaterialTheme.typography.bodySmall)
                        Text("Rain ${day.rainChancePercent ?: "—"}% · wind to ${weatherNumber(day.windMaxKmh)} km/h",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${weatherNumber(day.highC)}°", fontWeight = FontWeight.Bold)
                    Text("${weatherNumber(day.lowC)}°", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun weatherNumber(value: Double?) = value?.roundToInt()?.toString() ?: "—"

internal fun weatherDescription(code: Int?): String = nz.fishingnz.app.model.WeatherLabels.describe(code)

private fun weatherIcon(code: Int?, day: Boolean): ImageVector = knownWeatherIcon(code, day)

internal fun windDirection(degrees: Int): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return directions[((degrees % 360 + 360) % 360 + 22) / 45 % 8]
}
