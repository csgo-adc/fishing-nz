package nz.fishingnz.app

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.analytics.FirebaseAnalytics
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlinx.coroutines.launch
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition

private val Navy = Color(0xFF123B43)
private val Seafoam = Color(0xFFDCEFE7)
private val Cream = Color(0xFFF7F8F4)
private val Orange = Color(0xFFE4784A)

data class Recommendation(
    val name: String,
    val area: String,
    val rating: Int,
    val time: String,
    val distance: String,
    val reasons: List<String>,
    val boat: Boolean = false
)

data class WeatherState(val temperature: String, val wind: String, val rain: String)
data class TideEvent(val time: String, val height: String, val type: String)
data class TidePoint(val time: String, val level: Double)
data class TideState(
    val currentLevel: String,
    val nextEvent: String,
    val eventTime: String,
    val events: List<TideEvent> = emptyList(),
    val points: List<TidePoint> = emptyList(),
    val stationName: String = ""
)
data class FishCheck(
    val commonName: String,
    val scientificName: String,
    val confidence: Int,
    val minimumSize: String,
    val dailyLimit: String,
    val status: String,
    val note: String
)
data class GeoPoint(val latitude: Double, val longitude: Double)
data class TideStation(val id: String, val name: String, val region: String, val latitude: Double, val longitude: Double)

private val sampleRecommendations = listOf(
    Recommendation("Mission Bay", "Auckland", 86, "6:10 – 8:40 AM", "18 min away", listOf("Incoming tide", "Light SW wind", "17–20°C")),
    Recommendation("Rangitoto Channel", "Auckland", 82, "7:00 – 10:00 AM", "25 min to ramp", listOf("Sheltered water", "Gentle swell", "Good current movement"), boat = true),
    Recommendation("Takapuna Beach", "Auckland", 74, "5:30 – 7:30 PM", "22 min away", listOf("Low rain chance", "Outgoing tide", "Good evening light"))
)

private val tideStations = listOf(
    TideStation("auckland", "Auckland Harbour", "Auckland", -36.84, 174.76),
    TideStation("manukau", "Manukau Harbour", "Auckland", -37.05, 174.65),
    TideStation("whangarei", "Whangārei Harbour", "Northland", -35.72, 174.32),
    TideStation("tauranga", "Tauranga Harbour", "Bay of Plenty", -37.64, 176.18),
    TideStation("coromandel", "Coromandel Harbour", "Waikato", -36.83, 175.50),
    TideStation("gisborne", "Gisborne Harbour", "Gisborne", -38.66, 178.02),
    TideStation("napier", "Napier", "Hawke's Bay", -39.48, 176.92),
    TideStation("taranaki", "Port Taranaki", "Taranaki", -39.05, 174.07),
    TideStation("wellington", "Wellington Harbour", "Wellington", -41.28, 174.78),
    TideStation("nelson", "Nelson Harbour", "Nelson", -41.27, 173.28),
    TideStation("picton", "Picton", "Marlborough", -41.29, 174.00),
    TideStation("kaikoura", "Kaikōura", "Canterbury", -42.42, 173.68),
    TideStation("lyttelton", "Lyttelton Harbour", "Canterbury", -43.61, 172.72),
    TideStation("timaru", "Timaru", "Canterbury", -44.39, 171.25),
    TideStation("oamaru", "Oamaru", "Otago", -45.10, 170.97),
    TideStation("otago", "Otago Harbour", "Otago", -45.88, 170.51),
    TideStation("bluff", "Bluff Harbour", "Southland", -46.60, 168.33),
    TideStation("milford", "Milford Sound", "Fiordland", -44.67, 167.93)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseAnalytics.getInstance(this).logEvent("app_opened", Bundle().apply { putString("app_version", "0.1") })
        setContent { FishingNzApp() }
    }
}

@Composable
fun FishingNzApp() {
    var tab by remember { mutableIntStateOf(0) }
    var boat by remember { mutableStateOf(false) }
    var showResults by remember { mutableStateOf(false) }
    var selectedSpot by remember { mutableStateOf<Recommendation?>(null) }
    var date by remember { mutableStateOf("This Saturday") }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val preferences = context.getSharedPreferences("fishing_nz", android.content.Context.MODE_PRIVATE)
    var savedSpots by remember { mutableStateOf(preferences.getStringSet("saved_spots", emptySet())?.toSet() ?: emptySet()) }
    var activeTrip by remember { mutableStateOf<Recommendation?>(null) }
    var weather by remember { mutableStateOf<WeatherState?>(null) }
    var tide by remember { mutableStateOf<TideState?>(null) }
    var selectedTideStation by remember { mutableStateOf(tideStations.first()) }
    var tideDate by remember { mutableStateOf(java.time.LocalDate.now()) }
    var stationTide by remember { mutableStateOf<TideState?>(null) }
    var stationTideError by remember { mutableStateOf(false) }
    var weatherError by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf(GeoPoint(-36.85, 174.76)) }
    var usingDeviceLocation by remember { mutableStateOf(false) }
    var fishPhoto by remember { mutableStateOf<Uri?>(null) }
    var fishCheck by remember { mutableStateOf<FishCheck?>(null) }
    var fishChecking by remember { mutableStateOf(false) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        fishPhoto = uri
        fishCheck = null
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            requestCurrentLocation(context) { point ->
                location = point
                usingDeviceLocation = true
            }
        }
    }
    val refreshLocation = {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            requestCurrentLocation(context) { point ->
                location = point
                usingDeviceLocation = true
            }
        } else {
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    LaunchedEffect(location.latitude, location.longitude) {
        runCatching { fetchConditions(location.latitude, location.longitude) }
            .onSuccess { (liveWeather, liveTide) -> weather = liveWeather; tide = liveTide; weatherError = false }
            .onFailure { weatherError = true }
    }
    LaunchedEffect(selectedTideStation.id, tideDate) {
        stationTide = null
        stationTideError = false
        runCatching { fetchTideForecast(selectedTideStation, tideDate) }
            .onSuccess { stationTide = it }
            .onFailure { stationTideError = true }
    }
    val useNearestTideStation = {
        selectedTideStation = tideStations.minByOrNull { station ->
            val latitudeDistance = station.latitude - location.latitude
            val longitudeDistance = station.longitude - location.longitude
            latitudeDistance * latitudeDistance + longitudeDistance * longitudeDistance
        } ?: tideStations.first()
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Orange, background = Cream)) {
        Scaffold(
            containerColor = Cream,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    listOf(Icons.Default.NearMe to "Home", Icons.Default.Map to "Map", Icons.Default.Waves to "Tide", Icons.Default.CalendarMonth to "Trips", Icons.Default.MenuBook to "Rules").forEachIndexed { index, item ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(item.first, null) }, label = { Text(item.second) })
                    }
                }
            }
        ) { padding ->
            when (tab) {
                0 -> HomeScreen(Modifier.padding(padding), boat, { boat = it }, date, { date = it }, weather, tide, weatherError, usingDeviceLocation, { showResults = true }, fishPhoto, fishCheck, fishChecking, { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, {
                    fishChecking = true
                    fishCheck = null
                    coroutineScope.launch {
                        fishCheck = identifyFishPhoto(fishPhoto)
                        fishChecking = false
                    }
                })
                1 -> MapScreen(Modifier.padding(padding), location, refreshLocation)
                2 -> TideScreen(
                    modifier = Modifier.padding(padding),
                    station = selectedTideStation,
                    date = tideDate,
                    tide = stationTide,
                    loadingError = stationTideError,
                    stations = tideStations,
                    onStationSelected = { selectedTideStation = it },
                    onUseNearestStation = useNearestTideStation,
                    onDateChanged = { tideDate = it }
                )
                3 -> TripsScreen(Modifier.padding(padding), savedSpots, activeTrip, { activeTrip = null }, { selectedSpot = it })
                else -> RulesScreen(Modifier.padding(padding))
            }
        }
        if (showResults) ResultsSheet(boat = boat, date = date, onClose = { showResults = false }, onSelect = { selectedSpot = it })
        selectedSpot?.let { spot -> SpotDetailScreen(spot, savedSpots.contains(spot.name), onBack = { selectedSpot = null }, onSave = {
            val updated = if (savedSpots.contains(spot.name)) savedSpots - spot.name else savedSpots + spot.name
            savedSpots = updated
            preferences.edit().putStringSet("saved_spots", updated).apply()
        }, onStartTrip = { activeTrip = spot; selectedSpot = null }) }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, boat: Boolean, setBoat: (Boolean) -> Unit, date: String, setDate: (String) -> Unit, weather: WeatherState?, tide: TideState?, weatherError: Boolean, usingDeviceLocation: Boolean, find: () -> Unit, fishPhoto: Uri?, fishCheck: FishCheck?, fishChecking: Boolean, chooseFishPhoto: () -> Unit, checkFish: () -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column { Text("Kia ora, Alex", style = MaterialTheme.typography.labelLarge, color = Color.Gray); Text("Plan your next catch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy) }
                IconButton(onClick = {}) { Icon(Icons.Default.FavoriteBorder, "Saved spots", tint = Navy) }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Navy), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("What are you fishing for?", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !boat, onClick = { setBoat(false) }, label = { Text("Land fishing") }, leadingIcon = { Icon(Icons.Default.NearMe, null) })
                        FilterChip(selected = boat, onClick = { setBoat(true) }, label = { Text("Boat fishing") }, leadingIcon = { Icon(Icons.Default.Waves, null) })
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("${if (usingDeviceLocation) "Near your location" else "Auckland demo"} · $date", color = Color.White.copy(alpha = .82f))
                }
            }
        }
        item {
            Text("When are you going?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf("Today", "Tomorrow", "Friday", "Saturday", "Sunday", "Next 7 days", "Choose date").forEach { option -> FilterChip(selected = date == option, onClick = { setDate(option) }, label = { Text(option) }) }
            }
        }
        item {
            ActionCard("Find the best time", "See the best fishing window near you", Icons.Default.CalendarMonth, find)
            Spacer(Modifier.height(10.dp))
            ActionCard("Find the best location", "Rank spots by conditions and distance", Icons.Default.LocationOn, find, outlined = true)
        }
        item { FishIdentifierCard(fishPhoto, fishCheck, fishChecking, chooseFishPhoto, checkFish) }
        item { Text("Quick forecast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        item { ForecastCard(boat, weather, tide, weatherError) }
        item { Text("Your next best window", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        item { RecommendationCard(sampleRecommendations.first(), onClick = find) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun FishIdentifierCard(photo: Uri?, result: FishCheck?, loading: Boolean, choosePhoto: () -> Unit, checkFish: () -> Unit) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Seafoam, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.CameraAlt, null, tint = Navy) }
                Spacer(Modifier.width(12.dp))
                Column { Text("What fish is this?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy); Text("AI ID + local rules check", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            }
            if (photo != null) {
                val bitmap = remember(photo) { context.contentResolver.openInputStream(photo)?.use { BitmapFactory.decodeStream(it) } }
                bitmap?.let { androidx.compose.foundation.Image(it.asImageBitmap(), contentDescription = "Selected fish photo", modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            }
            if (result != null) {
                Card(colors = CardDefaults.cardColors(containerColor = Seafoam), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(result.commonName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy); Text("${result.confidence}% match", color = Orange, fontWeight = FontWeight.Bold) }
                        Text(result.scientificName, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Text("${result.status} · min ${result.minimumSize} · ${result.dailyLimit}", color = Navy, fontWeight = FontWeight.SemiBold)
                        Text(result.note, color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (loading) Text("Checking the photo…", color = Orange, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = choosePhoto, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(6.dp)); Text(if (photo == null) "Choose photo" else "Change") }
                Button(enabled = photo != null && !loading, onClick = checkFish, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(6.dp)); Text("Identify") }
            }
            Text("AI suggestions are a guide. Confirm species, area and current MPI rules before keeping a fish.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private suspend fun identifyFishPhoto(photo: Uri?): FishCheck = withContext(Dispatchers.Default) {
    // Replace this demo adapter with the secured server-side vision endpoint in production.
    kotlinx.coroutines.delay(650)
    FishCheck("Snapper", "Pagrus auratus", 91, "30 cm", "7 per person / day", "Likely legal", "Check the regional MPI rules and measure from the nose to the shortest tail length before keeping it.")
}

@Composable
private fun ActionCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, outlined: Boolean = false) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = if (outlined) Color.White else Seafoam), shape = RoundedCornerShape(18.dp), border = if (outlined) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD5DDD8)) else null) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(if (outlined) Seafoam else Color.White, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Navy) }
            Spacer(Modifier.width(14.dp)); Column { Text(title, fontWeight = FontWeight.Bold, color = Navy); Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ForecastCard(boat: Boolean, weather: WeatherState?, tide: TideState?, weatherError: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(if (boat) "Boat conditions" else "Land conditions", fontWeight = FontWeight.Bold, color = Navy); Text(if (weatherError) "Unavailable" else if (weather == null) "Loading…" else "Live data", color = Orange, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("Wind", weather?.wind ?: "—"); Metric("Tide", tide?.nextEvent ?: "—"); Metric(if (boat) "Swell" else "Temp", weather?.temperature ?: "—") }
            if (weather != null) { Spacer(Modifier.height(8.dp)); Text("Rain: ${weather.rain} · Live weather · Open-Meteo", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            if (tide != null) { Text("Tide level: ${tide.currentLevel} · next ${tide.nextEvent} at ${tide.eventTime} · Open-Meteo marine model", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            if (weatherError) { Spacer(Modifier.height(8.dp)); Text("Live conditions are currently unavailable. Check your connection and try again.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private suspend fun fetchConditions(latitude: Double, longitude: Double): Pair<WeatherState, TideState> = withContext(Dispatchers.IO) {
    val endpoint = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,wind_speed_10m,precipitation&timezone=auto"
    val connection = openGet(endpoint)
    try {
        if (connection.responseCode !in 200..299) error("Weather request failed: ${connection.responseCode}")
        val current = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
        val weather = WeatherState(
            temperature = "${current.getDouble("temperature_2m").toInt()}°C",
            wind = "${current.getDouble("wind_speed_10m").toInt()} km/h",
            rain = "${current.getDouble("precipitation")} mm"
        )
        val tide = fetchTideForecast(
            TideStation("current", "Current location", "", latitude, longitude),
            java.time.LocalDate.now()
        )
        weather to tide
    } finally {
        connection.disconnect()
    }
}

private suspend fun fetchTideForecast(station: TideStation, date: java.time.LocalDate): TideState = withContext(Dispatchers.IO) {
    val endpoint = "https://marine-api.open-meteo.com/v1/marine?latitude=${station.latitude}&longitude=${station.longitude}" +
        "&hourly=sea_level_height_msl&start_date=$date&end_date=${date.plusDays(1)}&cell_selection=sea&timezone=auto"
    val connection = openGet(endpoint)
    try {
        if (connection.responseCode !in 200..299) error("Tide request failed: ${connection.responseCode}")
        val hourly = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val levels = hourly.getJSONArray("sea_level_height_msl")
        if (times.length() == 0 || levels.length() == 0) error("No tide data returned")

        val timeFormat = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
        val parsedTimes = (0 until times.length()).map { java.time.LocalDateTime.parse(times.getString(it)) }
        val selectedIndices = parsedTimes.indices.filter { parsedTimes[it].toLocalDate() == date }
        val points = selectedIndices.map { index -> TidePoint(parsedTimes[index].format(timeFormat), levels.getDouble(index)) }
        val eventIndices = mutableListOf<Int>()
        for (index in 1 until levels.length() - 1) {
            val previous = levels.getDouble(index - 1)
            val current = levels.getDouble(index)
            val next = levels.getDouble(index + 1)
            if (current > previous && current >= next || current < previous && current <= next) eventIndices += index
        }
        val eventPairs = eventIndices.map { index ->
            val current = levels.getDouble(index)
            val previous = levels.getDouble(index - 1)
            index to TideEvent(
                time = parsedTimes[index].format(timeFormat),
                height = "${current.formatLevel()} m",
                type = if (current > previous) "High" else "Low"
            )
        }
        val selectedEventPairs = eventPairs.filter { parsedTimes[it.first].toLocalDate() == date }
        val selectedEvents = selectedEventPairs.map { it.second }
        val now = java.time.LocalDateTime.now()
        val currentIndex = if (date == java.time.LocalDate.now()) {
            parsedTimes.indexOfLast { !it.isAfter(now) }.coerceAtLeast(0)
        } else {
            selectedIndices.firstOrNull() ?: 0
        }
        val nextEventPair = if (date == java.time.LocalDate.now()) {
            eventPairs.firstOrNull { parsedTimes[it.first].isAfter(now) }
        } else {
            selectedEventPairs.firstOrNull()
        }
        TideState(
            currentLevel = "${levels.getDouble(currentIndex).formatLevel()} m",
            nextEvent = nextEventPair?.second?.type ?: "—",
            eventTime = nextEventPair?.first?.let { parsedTimes[it].format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM · h:mm a", java.util.Locale.US)) } ?: "No event",
            events = selectedEvents,
            points = points,
            stationName = station.name
        )
    } finally {
        connection.disconnect()
    }
}

private fun openGet(endpoint: String) = (URL(endpoint).openConnection() as HttpURLConnection).apply {
    requestMethod = "GET"
    connectTimeout = 8_000
    readTimeout = 8_000
}

private fun Double.formatLevel(): String = String.format(java.util.Locale.US, "%.2f", this)

private fun requestCurrentLocation(context: android.content.Context, onLocation: (GeoPoint) -> Unit) {
    try {
        val tokenSource = CancellationTokenSource()
        LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
            .addOnSuccessListener { current ->
                current?.let { onLocation(GeoPoint(it.latitude, it.longitude)) }
            }
    } catch (_: SecurityException) {
        // Permission can be revoked while the request is in flight.
    }
}

@Composable
private fun Metric(label: String, value: String) { Column { Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall); Text(value, color = Navy, fontWeight = FontWeight.SemiBold) } }

@Composable
private fun ResultsSheet(boat: Boolean, date: String, onClose: () -> Unit, onSelect: (Recommendation) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Cream) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Spacer(Modifier.height(18.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Column { Text("Best options", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy); Text("${if (boat) "Boat" else "Land"} · $date", color = Color.Gray) }; OutlinedButton(onClick = onClose) { Text("Back") } } }
            item { Text("Based on tide, weather, conditions and distance", color = Color.Gray) }
            items(sampleRecommendations.filter { it.boat == boat || !boat }) { RecommendationCard(it, onClick = { onSelect(it) }) }
            item { Card(colors = CardDefaults.cardColors(containerColor = Seafoam), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text("How we chose these", fontWeight = FontWeight.Bold, color = Navy); Text("We combine the tide window, wind, rain, temperature, swell and travel distance. Safety and fishing rules always override the score.", color = Color.DarkGray) } } }
        }
    }
}

@Composable
private fun SpotDetailScreen(spot: Recommendation, saved: Boolean, onBack: () -> Unit, onSave: () -> Unit, onStartTrip: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Cream) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onBack) { Text("Back") }
                    IconButton(onClick = onSave) { Icon(Icons.Default.FavoriteBorder, "Save spot", tint = if (saved) Orange else Navy) }
                }
            }
            item {
                Text(spot.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Navy)
                Text(spot.area, color = Color.Gray)
                Spacer(Modifier.height(8.dp))
                Text("${spot.rating}/100 · ${spot.time}", style = MaterialTheme.typography.titleLarge, color = Orange, fontWeight = FontWeight.Bold)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Seafoam), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Why this spot?", fontWeight = FontWeight.Bold, color = Navy)
                        Spacer(Modifier.height(8.dp))
                        spot.reasons.forEach { reason -> Text("✓  $reason", color = Color.DarkGray, modifier = Modifier.padding(vertical = 3.dp)) }
                        Text("✓  ${spot.distance}", color = Color.DarkGray, modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Conditions", fontWeight = FontWeight.Bold, color = Navy)
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("Tide", "Incoming"); Metric("Wind", "8 km/h SW"); Metric("Rain", "10%") }
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("Temperature", "17–20°C"); Metric("Swell", if (spot.boat) "0.7 m" else "Low"); Metric("Access", if (spot.boat) "Boat ramp" else "Shore") }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E8)), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) { Text("Safety reminder", fontWeight = FontWeight.Bold, color = Color(0xFF9B4A2B)); Text(if (spot.boat) "Check the latest marine forecast, fuel, life jackets and launch conditions before departing." else "Rock and shoreline conditions can change quickly. Watch slippery surfaces and rising water.", color = Color.DarkGray) }
                }
            }
            item { Button(onClick = onStartTrip, modifier = Modifier.fillMaxWidth()) { Text("Start fishing trip") } }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun RecommendationCard(item: Recommendation, onClick: () -> Unit = {}) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column { Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy); Text(item.area, color = Color.Gray) }; Text("${item.rating}/100", color = Orange, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp)); Text(item.time, color = Navy, fontWeight = FontWeight.SemiBold); Text(item.distance, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { item.reasons.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Navy, modifier = Modifier.background(Seafoam, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 5.dp)) } }
        }
    }
}

private data class MapSpot(val name: String, val latitude: Double, val longitude: Double, val detail: String, val boat: Boolean)

private val nzMapSpots = listOf(
    MapSpot("Whangarei Harbour", -35.72, 174.32, "Good · Land fishing", false),
    MapSpot("Mission Bay", -36.8485, 174.7633, "86/100 · Best 6:10–8:40 AM", false),
    MapSpot("Coromandel Harbour", -37.0, 175.35, "Good · Land fishing", false),
    MapSpot("Gisborne Harbour", -38.02, 177.29, "Good · Land fishing", false),
    MapSpot("Wellington Harbour", -41.28, 174.78, "Good · Boat fishing", true),
    MapSpot("Nelson Harbour", -41.27, 173.28, "Good · Boat fishing", true),
    MapSpot("Lyttelton Harbour", -43.53, 172.64, "Good · Boat fishing", true),
    MapSpot("Otago Harbour", -45.88, 170.51, "Good · Boat fishing", true),
    MapSpot("Bluff Harbour", -46.41, 168.35, "Good · Boat fishing", true)
)

@Composable
private fun MapScreen(modifier: Modifier, location: GeoPoint, refreshLocation: () -> Unit) {
    var filter by remember { mutableStateOf("All") }
    var mapLoaded by remember { mutableStateOf(false) }
    val visibleSpots = nzMapSpots.filter { filter == "All" || (filter == "Boat" && it.boat) || (filter == "Land" && !it.boat) }
    val cameraPositionState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(-41.2, 174.8), 5.1f) }
    LaunchedEffect(mapLoaded, location.latitude, location.longitude) {
        if (mapLoaded) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 11f))
        }
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("Fishing map", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
            Text("New Zealand spots · tap a marker to explore", color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState())) {
                listOf("All", "Land", "Boat").forEach { option -> FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(if (option == "All") "All spots" else "$option fishing") }) }
            }
        }
        Box(Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isBuildingEnabled = true),
                uiSettings = MapUiSettings(zoomControlsEnabled = true, myLocationButtonEnabled = false),
                onMapLoaded = { mapLoaded = true }
            ) {
                visibleSpots.forEach { spot ->
                    key(spot.name) {
                        Marker(state = MarkerState(LatLng(spot.latitude, spot.longitude)), title = spot.name, snippet = spot.detail)
                    }
                }
                key("user-location") {
                    Marker(state = MarkerState(LatLng(location.latitude, location.longitude)), title = "Your location", snippet = "Current device position")
                }
            }
            Button(
                onClick = {
                    refreshLocation()
                    if (mapLoaded) {
                        cameraPositionState.move(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 11f))
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            ) {
                Icon(Icons.Default.NearMe, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("My location")
            }
        }
    }
}

@Composable
private fun TideScreen(
    modifier: Modifier,
    station: TideStation,
    date: java.time.LocalDate,
    tide: TideState?,
    loadingError: Boolean,
    stations: List<TideStation>,
    onStationSelected: (TideStation) -> Unit,
    onUseNearestStation: () -> Unit,
    onDateChanged: (java.time.LocalDate) -> Unit
) {
    var showStationPicker by remember { mutableStateOf(false) }
    val dateFormat = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM", java.util.Locale.US)
    val today = java.time.LocalDate.now()
    val lastForecastDate = today.plusDays(7)
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("Tide forecast", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
            Text("Choose a bay, harbour or coastal station", color = Color.Gray)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onUseNearestStation, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.NearMe, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Near me")
                }
                Button(onClick = { showStationPicker = !showStationPicker }, modifier = Modifier.weight(1.7f)) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text(station.name, maxLines = 1)
                }
            }
        }
        if (showStationPicker) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().height(290.dp).verticalScroll(rememberScrollState())) {
                        Text("NZ tide stations", Modifier.padding(start = 16.dp, top = 14.dp), color = Navy, fontWeight = FontWeight.Bold)
                        stations.forEach { option ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onStationSelected(option)
                                    showStationPicker = false
                                }.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(option.name, color = Navy, fontWeight = FontWeight.SemiBold)
                                    Text(option.region, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                }
                                if (option.id == station.id) Text("Selected", color = Orange, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Seafoam), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(station.name, color = Navy, fontWeight = FontWeight.Bold)
                    Text(station.region, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = date.isAfter(today), onClick = { onDateChanged(date.minusDays(1)) }) { Text("‹") }
                        Text(date.format(dateFormat), color = Navy, fontWeight = FontWeight.Bold)
                        OutlinedButton(enabled = date.isBefore(lastForecastDate), onClick = { onDateChanged(date.plusDays(1)) }) { Text("›") }
                    }
                }
            }
        }
        item {
            if (loadingError) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E8)), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Tide data unavailable", color = Color(0xFF9B4A2B), fontWeight = FontWeight.Bold)
                        Text("Check your internet connection and choose another date or station.", color = Color.DarkGray)
                    }
                }
            } else if (tide == null) {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                    Text("Loading live tide data…", Modifier.padding(18.dp), color = Color.Gray)
                }
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Tide curve", style = MaterialTheme.typography.titleLarge, color = Navy, fontWeight = FontWeight.Bold)
                        Text("Sea level above global mean", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        TideCurve(tide.points)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("Current", tide.currentLevel)
                            Metric("Next", tide.nextEvent)
                            Metric("At", tide.eventTime)
                        }
                    }
                }
            }
        }
        item {
            if (tide != null && tide.events.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Seafoam), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("High and low water", style = MaterialTheme.typography.titleLarge, color = Navy, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        tide.events.forEach { event ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(event.type, color = if (event.type == "High") Orange else Navy, fontWeight = FontWeight.Bold)
                                Text(event.time, color = Navy)
                                Text(event.height, color = Color.DarkGray)
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E8)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Fishing and safety", color = Color(0xFF9B4A2B), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Moving water can improve feeding activity, but the best tide depends on the spot and target species. For boat fishing, check tide direction together with wind, swell and launch conditions.", color = Color.DarkGray)
                }
            }
        }
        item {
            Text("Live Open-Meteo marine model. Coastal accuracy is limited; do not use this screen as a replacement for official LINZ tide predictions or nautical safety information.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun TideCurve(points: List<TidePoint>) {
    if (points.size < 2) return
    val minimum = points.minOf { it.level }
    val maximum = points.maxOf { it.level }
    val range = (maximum - minimum).coerceAtLeast(0.1)
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 4.dp, vertical = 10.dp)) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = size.width * index / (points.lastIndex.toFloat())
            val y = size.height - ((point.level - minimum) / range).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(
            color = Color(0xFFE5ECE8),
            start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
            strokeWidth = 1.dp.toPx()
        )
        drawPath(path, color = Navy, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
        points.forEachIndexed { index, point ->
            if (index % 3 == 0 || index == points.lastIndex) {
                val x = size.width * index / (points.lastIndex.toFloat())
                val y = size.height - ((point.level - minimum) / range).toFloat() * size.height
                drawCircle(Orange, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
            }
        }
    }
}
@Composable
private fun TripsScreen(modifier: Modifier, savedSpots: Set<String>, activeTrip: Recommendation?, stopTrip: () -> Unit, openSpot: (Recommendation) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.height(18.dp)); Text("Your trips", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy); Text("Saved spots and active plans", color = Color.Gray) }
        if (activeTrip != null) item { Card(colors = CardDefaults.cardColors(containerColor = Seafoam), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text("Active trip", color = Navy, fontWeight = FontWeight.Bold); Text(activeTrip.name, style = MaterialTheme.typography.titleLarge, color = Navy); Text("${activeTrip.time} · ${activeTrip.distance}", color = Color.Gray); Spacer(Modifier.height(10.dp)); OutlinedButton(onClick = stopTrip) { Text("End trip") } } } }
        item { Text("Saved spots", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        if (savedSpots.isEmpty()) item { Text("No saved spots yet. Open a recommendation and tap the heart to save it.", color = Color.Gray) }
        items(sampleRecommendations.filter { savedSpots.contains(it.name) }) { spot -> RecommendationCard(spot, onClick = { openSpot(spot) }) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun RulesScreen(modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.height(18.dp)); Text("Fishing rules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy); Text("Auckland / Kermadec example", color = Color.Gray) }
        item { RuleCard("Before you go", "Rules vary by fishing area. Check the latest official MPI rules before every trip.") }
        item { RuleCard("Catch limits", "Daily limits and minimum sizes depend on species and region. Keep only legal-sized catch.") }
        item { RuleCard("Closed areas", "Marine reserves, mātaitai and taiāpure may have additional restrictions or complete closures.") }
        item { RuleCard("Boat reminder", "Rules apply to recreational boat fishing too, including gear and set-line restrictions.") }
        item { Text("This demo screen is not a legal source. Live MPI rules will be connected in the rules-data phase.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun RuleCard(title: String, body: String) { Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold, color = Navy); Spacer(Modifier.height(6.dp)); Text(body, color = Color.DarkGray) } } }

@Preview(showBackground = true)
@Composable
private fun PreviewFishingNz() { FishingNzApp() }
