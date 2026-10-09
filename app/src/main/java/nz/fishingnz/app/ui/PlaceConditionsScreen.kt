package nz.fishingnz.app.ui

import android.location.Geocoder
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import nz.fishingnz.app.data.*
import nz.fishingnz.app.model.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val dayDate = DateTimeFormatter.ofPattern("EEE d", Locale.ENGLISH)
private val timeOfDay = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceConditionsScreen(place: ConditionPlace, dismiss: () -> Unit) {
    val repository = remember { PlaceConditionsRepository() }
    var data by remember(place.id) { mutableStateOf<PlaceConditions?>(null) }
    var loading by remember(place.id) { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var pastDays by remember { mutableIntStateOf(3) }
    var recent by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var more by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf(false) }
    var station by remember(place.id) { mutableStateOf<TideStation?>(place.initialTideStation) }
    var selectedStation by remember(place.id) { mutableStateOf<TideStation?>(null) }
    var tide by remember { mutableStateOf<TideState?>(null) }
    var tideLoading by remember { mutableStateOf(false) }
    var tideIssue by remember { mutableStateOf(false) }
    var tideRefresh by remember { mutableIntStateOf(0) }
    var showStations by remember { mutableStateOf(false) }
    var tideDetails by remember { mutableStateOf(false) }
    LaunchedEffect(place.id, pastDays, refresh) {
        loading = true
        try { data = repository.load(place.point, pastDays) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { data = PlaceConditions(null, null, "Conditions unavailable · try refreshing", null) }
        finally { loading = false }
    }
    val today = LocalDate.now(data?.zone ?: java.time.ZoneId.of("Pacific/Auckland"))
    val availableDates = data?.dates.orEmpty().ifEmpty {
        (-pastDays until PlaceConditionsRepository.WEATHER_DAYS).map { today.plusDays(it.toLong()) }
    }
    val dates = availableDates.filter { if (recent) it < today else it >= today }.let { if (recent) it.reversed() else it }
    val day = selectedDate?.takeIf { it in dates } ?: dates.firstOrNull()
    LaunchedEffect(place.id, selectedStation?.id, day, tideRefresh, refresh) {
        tide = null; tideIssue = false
        station = selectedStation ?: place.initialTideStation
        if (day != null) {
            tideLoading = true
            try {
                val result = loadPlaceTide(place, selectedStation, day, FishingRepository()::tide)
                ensureActive()
                station = result.station; tide = result.tide
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { tideIssue = true }
            finally { tideLoading = false }
        } else tideLoading = false
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(modifier = Modifier.systemBarsPadding(), contentWindowInsets = WindowInsets(0), topBar = {
                TopAppBar(title = {
                    Column {
                        Text(place.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("Conditions · ${place.region}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }, navigationIcon = {
                    IconButton(onClick = dismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to map") }
                }, actions = {
                    IconButton(onClick = { refresh++ }) { Icon(Icons.Default.Refresh, "Refresh conditions") }
                })
            }) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!recent, { recent = false; selectedDate = null }, label = { Text("Upcoming") })
                            FilterChip(recent, { recent = true; selectedDate = null }, label = { Text("Recent days") })
                        }
                        if (recent) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(3, 7, 30, 92).forEach { count -> FilterChip(pastDays == count, { pastDays = count; selectedDate = null }, label = { Text("$count days") }) }
                        }
                    }
                    if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Updating conditions…", style = MaterialTheme.typography.bodySmall) }
                    data?.weatherIssue?.let { issue -> item { Text(issue, color = MaterialTheme.colorScheme.error) } }
                    data?.marineIssue?.let { issue -> item { Text(issue, color = MaterialTheme.colorScheme.error) } }
                    if (dates.isNotEmpty()) item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            dates.forEach { date ->
                                val code = data?.weather?.days?.firstOrNull { it.date == date }?.code
                                FilterChip(date == day, { selectedDate = date }, label = {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 5.dp)) {
                                        Text(if (date == today) "Today" else if (date == today.minusDays(1)) "Yesterday" else date.format(dayDate))
                                        Icon(knownWeatherIcon(code), WeatherLabels.describe(code), Modifier.size(25.dp))
                                        Text(date.format(shortDate), style = MaterialTheme.typography.labelSmall)
                                    }
                                })
                            }
                        }
                    }
                    if (!loading && dates.isEmpty()) item { Text("No conditions returned for these days."); OutlinedButton(onClick = { refresh++ }) { Text("Try again") } }
                    if (day != null && data != null) {
                        val snapshot = data!!
                        val daily = snapshot.weather?.days?.firstOrNull { it.date == day }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(knownWeatherIcon(daily?.code), WeatherLabels.describe(daily?.code), Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) {
                                    Text(day.format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)), fontWeight = FontWeight.Bold)
                                    Text("${WeatherLabels.describe(daily?.code)} · ${PlaceConditionRows.range(listOf(daily?.low, daily?.high))}°C")
                                    Text(if (recent) "Recent model data" else if (day > today.plusDays(5)) "🔭 Early outlook" else if (day > today.plusDays(2)) "🗓️ Planning forecast" else "🤔 Limited confidence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            snapshot.marine?.gridPoint?.let { grid -> Text("Offshore wave model · ${placeDistanceKm(place.point, grid).toInt()} km from pin", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    snapshot.rows(day).take(5).forEachIndexed { i, row -> if (i > 0) HorizontalDivider(); ConditionRow(row, snapshot.details(day, row.title)) }
                                }
                            }
                        }
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Tide", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                                        TextButton(onClick = { showStations = true }) { Text(station?.name ?: "Choose station") }
                                    }
                                    if (station == null) Text("Choose a LINZ reference station.", style = MaterialTheme.typography.bodySmall)
                                    else {
                                        val distance = placeDistanceKm(place.point, GeoPoint(station!!.latitude, station!!.longitude))
                                        Text("Reference station · ${PlaceConditionRows.number(distance, 1)} km away", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (tideLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                        else if (tideIssue) { Text(if (selectedStation == null) "Nearby tide stations could not be loaded." else "Tide unavailable for this station and day."); TextButton(onClick = { tideRefresh++ }) { Text("Retry tide") } }
                                        else tide?.let { state ->
                                            // The same curve, readout and slider as the Tide tab, then the day's published highs and lows.
                                            TideCurve(state.points, day)
                                            if (state.points.firstOrNull()?.minuteOfDay != 0 || state.points.lastOrNull()?.minuteOfDay != 1440)
                                                Text("Curve is limited where an adjacent day's table is unavailable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("Published high and low tides", color = Navy, fontWeight = FontWeight.Bold)
                                            Text("Heights above Chart Datum", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            state.events.forEach { event -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
                                                Text(event.type, color = Navy, fontWeight = FontWeight.SemiBold)
                                                Text("${event.time} · ${event.height}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            } }
                                        }
                                        TextButton(onClick = { tideDetails = !tideDetails }, contentPadding = PaddingValues(0.dp)) {
                                            Text("Tide details")
                                            Icon(if (tideDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (tideDetails) "Hide tide details" else "Show tide details")
                                        }
                                        if (tideDetails) {
                                            Text("LINZ ${station!!.name} · heights above Chart Datum", style = MaterialTheme.typography.bodySmall)
                                            Text("High and low times are for this reference station. Check it represents this place. Tide height does not tell you current speed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            TextButton(onClick = { more = !more }, contentPadding = PaddingValues(0.dp)) { Text(if (more) "Hide extra conditions" else "UV, visibility & sea details") }
                            if (more) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { snapshot.rows(day).drop(5).forEach { ConditionRow(it, snapshot.details(day, it.title)) } }
                        }
                        val hours = snapshot.hourDates(day)
                        val weatherByHour = snapshot.weatherHours(day).associateBy { it.at }
                        if (hours.isNotEmpty()) item {
                            Text("Hour by hour", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Rain is for the hour ending at the shown time.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                hours.forEach { at ->
                                    val hour = weatherByHour[at]
                                    val sea = snapshot.marine?.hours?.get(at)
                                    Column(Modifier.width(130.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(at.atZone(snapshot.zone).format(timeOfDay), fontWeight = FontWeight.SemiBold)
                                        Icon(knownWeatherIcon(hour?.code, hour?.isDay ?: true), WeatherLabels.describe(hour?.code), Modifier.size(28.dp))
                                        Text(WeatherLabels.describe(hour?.code), style = MaterialTheme.typography.labelSmall)
                                        Text("${PlaceConditionRows.number(hour?.temperature)}°C")
                                        Text("Feels ${PlaceConditionRows.number(hour?.feelsLike)}°C", style = MaterialTheme.typography.labelSmall)
                                        Text("Wind ${PlaceConditionRows.number(hour?.wind)} km/h", style = MaterialTheme.typography.labelSmall)
                                        Text("Gust ${PlaceConditionRows.number(hour?.gust)} km/h", style = MaterialTheme.typography.labelSmall)
                                        Text("Wave ${PlaceConditionRows.number(sea?.height, 1)} m · ${PlaceConditionRows.number(sea?.period, 1)} s", style = MaterialTheme.typography.labelSmall)
                                        Text("Rain ${PlaceConditionRows.number(hour?.rain, 1)} mm", style = MaterialTheme.typography.labelSmall)
                                        Text("Chance ${PlaceConditionRows.number(hour?.chance)}%", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        item {
                            val weatherEnd = snapshot.weather?.days?.lastOrNull()?.date
                            val waveEnd = snapshot.marine?.hours?.filterValues { it.height != null }?.keys?.maxOrNull()?.atZone(snapshot.zone)?.toLocalDate()
                            Text("Weather ${weatherEnd?.let { "through ${it.format(shortDate)}" } ?: "unavailable"} · waves ${waveEnd?.let { "through ${it.format(shortDate)}" } ?: "unavailable here"}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { sources = !sources }, contentPadding = PaddingValues(0.dp)) { Text(if (sources) "Hide forecast details" else "Forecast details") }
                            if (sources) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Open-Meteo model data · ${snapshot.zone.id}. Recent days use archived model output. Local exposure and official warnings need checking.")
                                Text("Waves are offshore significant height and mean period. Hourly gusts and rain cover the preceding hour. Rain chance is an hourly maximum, not a whole-day probability.")
                                snapshot.weather?.let { Text("Weather fetched ${it.fetchedAt.atZone(snapshot.zone).format(timeOfDay)} · grid ${it.grid}") }
                                snapshot.marine?.let { Text("Marine fetched ${it.fetchedAt.atZone(snapshot.zone).format(timeOfDay)} · grid ${it.grid}") }
                                Text("Fetch time is separate from model issue time. Feelings are general guides. Choose hours that suit your experience.")
                            }
                        }
                    }
                }
            }
        }
    }
    if (showStations) TideReferencePicker(place.point, station, { selectedStation = it; tideRefresh++; showStations = false }, { showStations = false })
}

@Composable private fun ConditionRow(row: ConditionItem, details: List<String>) {
    var expanded by remember(row.title) { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = if (expanded) "Hide details" else "Show details") { expanded = !expanded }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                Text(row.value, style = MaterialTheme.typography.bodyMedium)
                Text("${row.mood.emoji} ${row.mood.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) "Hide ${row.title.lowercase()} details" else "Show ${row.title.lowercase()} details")
        }
        androidx.compose.animation.AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.title == "Rain") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(61 to "Light", 63 to "Moderate", 65 to "Heavy").forEach { (code, label) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(knownWeatherIcon(code), WeatherLabels.describe(code), Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable private fun TideReferencePicker(point: GeoPoint, selected: TideStation?, choose: (TideStation?) -> Unit, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val stations = tideStations.filter { it.name.contains(query, true) || it.region.contains(query, true) }
        .sortedBy { placeDistanceKm(point, GeoPoint(it.latitude, it.longitude)) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Reference tide station") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Search stations") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item { TextButton(onClick = { choose(null) }) { Text("Use nearest available station") } }
                items(stations, key = { it.id }) { station -> TextButton(onClick = { choose(station) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text("${if (station.id == selected?.id) "✓ " else ""}${station.name}")
                        Text("${station.region} · ${placeDistanceKm(point, GeoPoint(station.latitude, station.longitude)).toInt()} km away", style = MaterialTheme.typography.labelSmall)
                    }
                } }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

@Composable fun MapPlaceSearch(choose: (ConditionPlace) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<ConditionPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        found = emptyList(); issue = null
        if (query.trim().length < 2) { searching = false; return@LaunchedEffect }
        searching = true
        delay(400)
        try {
            val result = withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.ENGLISH).getFromLocationName("${query.trim()}, New Zealand", 8).orEmpty().filter { it.hasLatitude() && it.hasLongitude() }.map {
                    val name = it.featureName?.takeIf { n -> !n.all(Char::isDigit) } ?: it.locality ?: query.trim()
                    ConditionPlace(name, GeoPoint(it.latitude, it.longitude), it.locality ?: it.adminArea ?: "New Zealand")
                }
            }
            ensureActive(); found = result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { issue = "Place search unavailable. You can still choose a fishing area or tap the map." }
        finally { searching = false }
    }
    val local = (tideStations.map { ConditionPlace(it.name, GeoPoint(it.latitude, it.longitude), "${it.region} · Tide station", station = it) } +
        fishingSpots.map { ConditionPlace(it.name, GeoPoint(it.latitude, it.longitude), "${it.area} · ${if (it.boat) "Boat" else "Land"} fishing", it.boat, recommendationTideStation(it, null)) })
        .filter { query.isNotBlank() && (it.name.contains(query, true) || it.region.contains(query, true)) }.distinctBy { it.id }.take(12)
    AlertDialog(onDismissRequest = dismiss, title = { Text("Find a place") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Town, beach or place") }, singleLine = true)
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            issue?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items((local + found).distinctBy { it.id }, key = { it.id }) { place ->
                    ListItem(headlineContent = { Text(place.name) }, supportingContent = { Text(place.region) },
                        modifier = Modifier.clickable { choose(place) })
                }
                if (!searching && query.length >= 2 && local.isEmpty() && found.isEmpty()) item { Text("No places found. Try another name or tap the map.") }
                if (query.isBlank()) item { Text("Search a town or beach, or drop a pin on the map.") }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}
