package nz.fishingnz.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Geocoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import nz.fishingnz.app.model.*
import nz.fishingnz.app.data.FishingRulesPage
import nz.fishingnz.app.data.RulesRepository
import nz.fishingnz.app.viewmodel.FishingUiState
import nz.fishingnz.app.viewmodel.FishingViewModel
import nz.fishingnz.app.viewmodel.SearchLocationMode
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val preferredTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

private fun encodedFishPhoto(context: android.content.Context, uri: android.net.Uri?): ByteArray? {
    if (uri == null) return null
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bitmap = BitmapFactory.decodeStream(input) ?: return null
            try {
                val output = java.io.ByteArrayOutputStream()
                if (bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output))
                    output.toByteArray().takeIf { it.isNotEmpty() }
                else null
            } finally { bitmap.recycle() }
        }
    } catch (_: Exception) { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HomeScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setFishPhoto(uri) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showPlan by rememberSaveable { mutableStateOf(false) }
    var showCities by rememberSaveable { mutableStateOf(false) }
    var showSearchStations by rememberSaveable { mutableStateOf(false) }
    var homeLocationLoading by remember { mutableStateOf(false) }
    var switchToDeviceOriginOnGrant by remember { mutableStateOf(false) }
    val locationOnboarding = remember(context) { context.getSharedPreferences("location_onboarding", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(s.deviceLocation, s.searchLocationMode, s.manualOriginSelected) {
        if (s.deviceLocation != null && s.searchLocationMode == SearchLocationMode.NEAR_ME && !s.manualOriginSelected)
            resolvedCity(context, s.deviceLocation)?.let(vm::setResolvedCity)
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            if (switchToDeviceOriginOnGrant) vm.useDeviceSearchOrigin()
            requestCurrentLocation(context, { point -> homeLocationLoading = false; vm.updateLocation(point) }, { homeLocationLoading = false; vm.locationUnavailable() })
        } else { homeLocationLoading = false; vm.locationUnavailable() }
        switchToDeviceOriginOnGrant = false
    }
    fun hasLocationPermission(): Boolean =
        (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    fun useCurrentLocation() {
        homeLocationLoading = true
        if (hasLocationPermission()) {
            vm.useDeviceSearchOrigin()
            requestCurrentLocation(context, { point -> homeLocationLoading = false; vm.updateLocation(point) }, { homeLocationLoading = false })
        } else {
            switchToDeviceOriginOnGrant = true
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    LaunchedEffect(Unit) {
        if (hasLocationPermission()) {
            homeLocationLoading = true
            requestCurrentLocation(context, { point -> homeLocationLoading = false; vm.updateLocation(point) }, { homeLocationLoading = false })
        } else if (!locationOnboarding.getBoolean("requested", false)) {
            locationOnboarding.edit().putBoolean("requested", true).apply()
            homeLocationLoading = true
            locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    DisposableEffect(lifecycle) {
        var initialResumeSeen = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (initialResumeSeen && hasLocationPermission() && !homeLocationLoading) {
                    homeLocationLoading = true
                    requestCurrentLocation(context, { point -> homeLocationLoading = false; vm.updateLocation(point) },
                        { homeLocationLoading = false })
                }
                initialResumeSeen = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun search() {
        if (s.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION && s.selectedSearchStation == null) {
            showPlan = true
            return
        }
        if (s.originName != null) { vm.showResults(); return }
        vm.startLocationSearch()
        if (hasLocationPermission()) requestCurrentLocation(context, { point -> vm.completeLocationSearch(point) }, { vm.locationUnavailable() })
        else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Text(s.account?.user?.displayName?.trim()?.substringBefore(' ')?.takeIf { it.isNotBlank() }?.let { "Kia ora, $it" } ?: "Kia ora", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Find your next catch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
        }
        item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("YOUR PLAN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                            Text(if (s.boat) "Boat fishing" else "Land fishing", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { showPlan = true }) { Text("Edit plan") }
                    }
                    val time = when {
                        s.preferredTime == null -> "Anytime"
                        s.preferredTimeIsSuggested -> "7 AM–9 PM"
                        else -> "${s.preferredTime.start.format(preferredTimeFormatter)}–${s.preferredTime.end.format(preferredTimeFormatter)}"
                    }
                    val dateSummary = if (s.dateLabel == "Custom") {
                        val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.US)
                        if (s.dateStart == s.dateEnd) s.dateStart.format(dateFormat)
                        else "${s.dateStart.format(dateFormat)}–${s.dateEnd.format(dateFormat)}"
                    } else s.dateLabel
                    val searchScope = if (s.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION)
                        s.selectedSearchStation?.name ?: "Choose location" else "${s.radiusKm} km"
                    Text("$dateSummary  ·  $time  ·  $searchScope", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                    Text(s.preference.label(s.boat),
                        color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().clickable {
                        if (s.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION) showSearchStations = true
                        else showCities = true
                    }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(s.originName?.let { "From $it" }
                            ?: if (homeLocationLoading && s.searchLocationMode == SearchLocationMode.NEAR_ME) "Finding your location…" else "Choose location",
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Default.ChevronRight, contentDescription = null,
                            modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Button(onClick = ::search, modifier = Modifier.fillMaxWidth()) {
                        Text(if (s.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION && s.selectedSearchStation == null)
                            "Choose a fishing location" else "Find fishing windows")
                    }
                }
            }
        }
        item { FishIdentifierCard(s, picker, vm) }
        item { Text("Quick forecast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            if (s.originName == null) Text("Use current location or choose a city to see local conditions.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp))
            else Column(Modifier.padding(18.dp)) {
                Text("From ${s.originName}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("Wind", s.weather?.wind ?: "—"); Metric("Tide", s.tide?.nextEvent ?: "—"); Metric("Temp", s.weather?.temperature ?: "—") }
            }
        } }
        item { Text("Your next planning window", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        item { s.recommendationSearch?.items?.firstOrNull()?.let {
            RecommendationCard(it, showWhy = shouldShowWindowReason(it, s.recommendationSearch?.items.orEmpty())) { vm.showResults() }
        } ?: Text("Choose a date and search to compare fishing conditions.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.height(12.dp)) }
    }
    if (showPlan) ModalBottomSheet(onDismissRequest = { showPlan = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Plan your fishing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Fishing from", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !s.boat, onClick = { vm.setBoat(false) }, label = { Text("Land") })
                FilterChip(selected = s.boat, onClick = { vm.setBoat(true) }, label = { Text("Boat") })
            }
            RecommendationFilters(s, vm)
            Button(onClick = { showPlan = false; search() },
                enabled = s.searchLocationMode != SearchLocationMode.SPECIFIC_LOCATION || s.selectedSearchStation != null,
                modifier = Modifier.fillMaxWidth()) { Text("Find fishing windows") }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showCities) OriginPickerSheet(onDismiss = { showCities = false }, onUseCurrentLocation = {
        showCities = false
        useCurrentLocation()
    }, onChoose = {
        vm.selectManualOrigin(it)
        showCities = false
    })
    if (showSearchStations) SearchStationPickerSheet(s.selectedSearchStation?.id,
        onDismiss = { showSearchStations = false },
        onUseCurrentLocation = { showSearchStations = false; useCurrentLocation() },
        onChoose = { vm.selectSearchStation(it); showSearchStations = false })
}

@Composable private fun RecommendationFilters(s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    var showSearchStations by rememberSaveable { mutableStateOf(false) }
    var searchStationQuery by rememberSaveable { mutableStateOf("") }
    val pickerTheme = if (MaterialTheme.colorScheme.background.luminance() < .5f)
        android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("When are you going?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Today", "In 3 days", "Next 7 days", "This weekend").forEach { choice ->
                FilterChip(selected = s.dateLabel == choice, onClick = { vm.setDate(choice) }, label = { Text(choice) })
            }
            FilterChip(selected = s.dateLabel == "Custom", onClick = {
                showCustomDateRange(context, s.dateStart, s.dateEnd, pickerTheme) { start, end -> vm.setCustomDates(start, end) }
            }, label = { Text("Choose dates") })
        }
        Text("${s.dateStart} to ${s.dateEnd}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text("What time suits you?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.preferredTimeIsSuggested, onClick = { vm.setSuggestedHours() }, label = { Text("7:00 AM–9:00 PM") })
            FilterChip(selected = s.preferredTime == null, onClick = { vm.clearPreferredHours() }, label = { Text("Anytime") })
            FilterChip(selected = s.preferredTime != null && !s.preferredTimeIsSuggested, onClick = {
                showCustomTimeRange(context, s.preferredTime, pickerTheme) { start, end -> vm.setPreferredHours(start, end) }
            }, label = { Text(if (s.preferredTime != null && !s.preferredTimeIsSuggested) "Change times" else "Choose times") })
        }
        if (s.preferredTime != null && !s.preferredTimeIsSuggested) {
            Text("${s.preferredTime.start.format(preferredTimeFormatter)}–${s.preferredTime.end.format(preferredTimeFormatter)}" +
                if (s.preferredTime.end.isBefore(s.preferredTime.start)) " (ends next day)" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Text("Only complete two-hour sessions within these hours are shown. Each selected day is a session's start day.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text("What matters most?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.preference == WindowPriority.WEATHER,
                onClick = { vm.setWindowPriority(WindowPriority.WEATHER) }, label = { Text(WindowPriority.WEATHER.label(s.boat)) })
            FilterChip(selected = s.preference == WindowPriority.LATE_INCOMING,
                onClick = { vm.setWindowPriority(WindowPriority.LATE_INCOMING) }, label = { Text("Late incoming tide") })
        }
        Text(if (s.preference == WindowPriority.LATE_INCOMING)
            "Focus on the last part of the incoming tide, around high water, using the shown LINZ reference station. Tide timing at your spot can differ; this does not guarantee better fishing." +
                if (s.boat) " Wave comfort remains the main factor when comparing sessions that fit." else ""
        else if (s.boat) "Give waves the most weight, including short wave periods that can make fishing uncomfortable. Compare wind and rain too, preferring daylight within your selected hours."
        else "Compare wind, rain and feels-like temperature.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (!s.boat) LandOptions(s.landPreferences, vm::setLandPreferences)
        Text("Find windows by", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.searchLocationMode == SearchLocationMode.NEAR_ME,
                onClick = { vm.setSearchLocationMode(SearchLocationMode.NEAR_ME); showSearchStations = false },
                label = { Text("Search radius") })
            FilterChip(selected = s.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION,
                onClick = {
                    vm.setSearchLocationMode(SearchLocationMode.SPECIFIC_LOCATION)
                    showSearchStations = s.selectedSearchStation == null
                }, label = { Text("Choose location") })
        }
        if (s.searchLocationMode == SearchLocationMode.NEAR_ME) {
            Text("Search radius", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 30, 50, 100, 200, 300, 400, 500).forEach { radius ->
                    FilterChip(selected = s.radiusKm == radius, onClick = { vm.setRadius(radius) }, label = { Text("$radius km") })
                }
            }
        } else {
            OutlinedButton(onClick = { showSearchStations = !showSearchStations }, modifier = Modifier.fillMaxWidth()) {
                Text(s.selectedSearchStation?.name ?: "Choose a tide location", modifier = Modifier.weight(1f))
                Text(if (showSearchStations) "⌃" else "⌄")
            }
            if (showSearchStations) {
                OutlinedTextField(searchStationQuery, onValueChange = { searchStationQuery = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search tide locations") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) })
                val matches = remember(searchStationQuery) {
                    tideStations.filter { station -> station.name.contains(searchStationQuery.trim(), ignoreCase = true) ||
                        station.region.contains(searchStationQuery.trim(), ignoreCase = true) }
                }
                Column(Modifier.fillMaxWidth().height(240.dp).verticalScroll(rememberScrollState())) {
                    matches.forEach { station ->
                        ListItem(headlineContent = { Text(station.name) },
                            supportingContent = { if (station.region != "New Zealand") Text(station.region) },
                            trailingContent = { if (station.id == s.selectedSearchStation?.id) Icon(Icons.Default.CheckCircle, null) },
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.selectSearchStation(station)
                                showSearchStations = false
                                searchStationQuery = ""
                            })
                        HorizontalDivider()
                    }
                    if (matches.isEmpty()) Text("No tide locations found", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

private fun showCustomTimeRange(context: android.content.Context, selected: PreferredTimeRange?, pickerTheme: Int, done: (LocalTime, LocalTime) -> Unit) {
    val initialStart = selected?.start ?: LocalTime.of(7, 0)
    val initialEnd = selected?.end ?: LocalTime.of(21, 0)
    android.app.TimePickerDialog(context, pickerTheme, { _, startHour, startMinute ->
        android.app.TimePickerDialog(context, pickerTheme, { _, endHour, endMinute ->
            val start = LocalTime.of(startHour, startMinute)
            val end = LocalTime.of(endHour, endMinute)
            if (start == end) android.widget.Toast.makeText(context, "Choose different start and end times", android.widget.Toast.LENGTH_SHORT).show()
            else done(start, end)
        }, initialEnd.hour, initialEnd.minute, false).apply { setTitle("Preferred end time") }.show()
    }, initialStart.hour, initialStart.minute, false).apply { setTitle("Preferred start time") }.show()
}

private fun showCustomDateRange(context: android.content.Context, selectedStart: java.time.LocalDate, selectedEnd: java.time.LocalDate, pickerTheme: Int, done: (java.time.LocalDate, java.time.LocalDate) -> Unit) {
    val zone = java.time.ZoneId.of("Pacific/Auckland")
    val today = java.time.LocalDate.now(zone)
    val maxDate = today.plusDays(15)
    val start = selectedStart.coerceIn(today, maxDate)
    val startPicker = android.app.DatePickerDialog(context, pickerTheme, { _, year, month, day ->
        val selected = java.time.LocalDate.of(year, month + 1, day)
        val end = selectedEnd.coerceIn(selected, maxDate)
        android.app.DatePickerDialog(context, pickerTheme, { _, endYear, endMonth, endDay ->
            done(selected, java.time.LocalDate.of(endYear, endMonth + 1, endDay))
        }, end.year, end.monthValue - 1, end.dayOfMonth).apply {
            setTitle("Last fishing day")
            datePicker.minDate = selected.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            datePicker.maxDate = maxDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.show()
    }, start.year, start.monthValue - 1, start.dayOfMonth)
    startPicker.setTitle("First fishing day")
    startPicker.datePicker.minDate = today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    startPicker.datePicker.maxDate = maxDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    startPicker.show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun FishIdentifierCard(s: FishingUiState, picker: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>, vm: FishingViewModel) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val hasFishAccess = s.account != null
    val needsAccountRefresh = s.account == null && s.hasStoredSession && !s.accountLoading
    var showCamera by remember { mutableStateOf(false) }
    var showRuleAreas by remember { mutableStateOf(false) }
    var photoAwaitingConsent by remember { mutableStateOf<android.net.Uri?>(null) }
    var showAiReport by remember { mutableStateOf(false) }
    var aiReportText by remember { mutableStateOf("") }
    var aiReportSubmitted by remember { mutableStateOf(false) }
    LaunchedEffect(s.accountBusy, s.accountNotice) {
        if (aiReportSubmitted && !s.accountBusy && s.accountNotice == "Thanks for your feedback.") {
            showAiReport = false
            aiReportSubmitted = false
            aiReportText = ""
        }
    }
    var pendingPhotoForLocationPermission by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) showCamera = true }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val photo = pendingPhotoForLocationPermission
        pendingPhotoForLocationPermission = null
        if (photo == null || vm.state.value.fishPhoto != photo) return@rememberLauncherForActivityResult
        val image = encodedFishPhoto(context, photo)
        if (image == null) vm.reportUnreadableFishPhoto()
        else if (granted) requestCurrentLocation(context,
            { if (vm.state.value.fishPhoto == photo) vm.identifyFish(image, it, true) },
            { if (vm.state.value.fishPhoto == photo) vm.identifyFish(image, s.deviceLocation ?: s.location, s.deviceLocation != null) })
        else vm.identifyFish(image, s.deviceLocation ?: s.location, false)
    }
    fun identifyAtCurrentLocation(image: ByteArray, photo: android.net.Uri) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) requestCurrentLocation(context,
            { if (vm.state.value.fishPhoto == photo) vm.identifyFish(image, it, true) },
            { if (vm.state.value.fishPhoto == photo) vm.identifyFish(image, s.deviceLocation ?: s.location, s.deviceLocation != null) })
        else {
            pendingPhotoForLocationPermission = photo
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(Seafoam, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.CameraAlt, null, tint = Navy) }; Spacer(Modifier.width(12.dp)); Column { Text("What fish is this?", style = MaterialTheme.typography.titleLarge, color = Navy, fontWeight = FontWeight.Bold); Text("AI ID + local rules check", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
        s.fishPhoto?.let { uri -> val bitmap = remember(uri) { try { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null } }; bitmap?.let { Image(it.asImageBitmap(), "Selected fish photo", Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop) } }
        s.fishCheck?.let { FishResultCard(it, s, vm::retryFishRules) }
        if (s.fishCheck != null && s.account != null) TextButton(onClick = { showAiReport = true }) { Text("Report this AI result") }
        if (s.fishChecking) Text("Checking the photo…", color = Orange, fontWeight = FontWeight.SemiBold)
        s.fishError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (s.fishCheck?.isFish != false) {
        OutlinedButton(onClick = { showRuleAreas = true }, modifier = Modifier.fillMaxWidth()) {
            Text(fishingRulesAreas.firstOrNull { it.id == s.fishRulesAreaId }?.name ?: "Choose MPI rules area", modifier = Modifier.weight(1f))
            Text("⌄")
        }
        if (s.fishRulesAreaIsSuggested) Text("Suggested from your current location. Choose the MPI area where the fish was caught.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera = true else cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(4.dp)); Text("Camera")
            }
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f).height(48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(4.dp)); Text("Gallery")
            }
        }
        val remainingIdentifications = s.account?.fishIdentityQuota?.remainingToday
        Button(enabled = !s.fishChecking && !s.accountLoading && (!hasFishAccess || s.fishPhoto != null), onClick = {
            if (needsAccountRefresh) vm.refreshAccount()
            else if (s.account == null) vm.selectTab(5)
            else if (hasFishAccess) s.fishPhoto?.let { uri ->
                photoAwaitingConsent = uri
            }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(4.dp)); Text(when {
            hasFishAccess -> "Identify fish"
            s.accountLoading -> "Checking account…"
            needsAccountRefresh -> "Retry account check"
            else -> "Sign in to identify fish"
        }) }
        if (hasFishAccess) Text(
            remainingIdentifications?.let { "$it of ${s.account?.fishIdentityQuota?.limit} identifications left today · Resets at midnight NZ time" }
                ?: "5 fish identifications per day · Resets at midnight NZ time",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (hasFishAccess && remainingIdentifications == 0) TextButton(enabled = !s.accountLoading, onClick = vm::refreshAccount) {
            Text("Refresh daily allowance")
        }
        if (!hasFishAccess) Text(when {
            s.accountLoading -> "Checking your account access."
            needsAccountRefresh -> "Your saved session is still on this device, but account access could not be checked."
            else -> "Sign in to use fish identification."
        }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text("AI suggestions are a guide. Confirm species, area and current MPI rules before keeping a fish.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    } }
    photoAwaitingConsent?.let { photo ->
        AlertDialog(onDismissRequest = { photoAwaitingConsent = null },
            title = { Text("Send this photo for AI identification?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your selected photo will be sent through the Fishdays - NZ Cloudflare service to OpenAI to suggest a fish species. Original photo metadata is removed. We do not save the photo in your account. OpenAI response storage is disabled, but security records can remain for up to 30 days or longer where legally required.")
                Text("Avoid photos containing people or private information. You can cancel and keep using the other tools.")
                TextButton(onClick = { uriHandler.openUri(nz.fishingnz.app.data.PrivacyLinks.policy) }) { Text("Privacy policy") }
            } },
            confirmButton = { TextButton(onClick = {
                photoAwaitingConsent = null
                if (vm.state.value.fishPhoto == photo && vm.state.value.account != null) {
                    val image = encodedFishPhoto(context, photo)
                    if (image == null) vm.reportUnreadableFishPhoto()
                    else identifyAtCurrentLocation(image, photo)
                }
            }) { Text("Agree and upload") } },
            dismissButton = { TextButton(onClick = { photoAwaitingConsent = null }) { Text("Cancel") } })
    }
    if (showAiReport) AlertDialog(onDismissRequest = { if (!s.accountBusy) showAiReport = false },
        title = { Text("Report an AI result") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tell us if this result is incorrect, offensive or unsafe. The report includes the suggested species and your message, and is linked to your account. Your photo is not included.")
            OutlinedTextField(aiReportText, { aiReportText = it.take(3000) }, label = { Text("What went wrong?") }, minLines = 3)
            if (aiReportSubmitted) s.accountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !s.accountBusy && aiReportText.trim().length >= 3, onClick = {
            aiReportSubmitted = true
            vm.sendFeedback("bug", "AI result report: ${s.fishCheck?.commonName.orEmpty()}\n${aiReportText.trim()}", 1)
        }) { Text(if (s.accountBusy) "Sending…" else "Send report") } },
        dismissButton = { TextButton(enabled = !s.accountBusy, onClick = { showAiReport = false }) { Text("Cancel") } })
    if (showRuleAreas) ModalBottomSheet(onDismissRequest = { showRuleAreas = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Where was the fish caught?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Choose the MPI area for this fishing spot.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.deviceLocation != null || s.tideDeviceLocation != null) TextButton(onClick = {
                vm.resetFishRulesAreaToCurrentLocation(); showRuleAreas = false
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.NearMe, null); Spacer(Modifier.width(8.dp))
                Text("Use my current location", modifier = Modifier.weight(1f))
            }
            fishingRulesAreas.forEach { area ->
                TextButton(onClick = { vm.chooseFishRulesArea(area.id); showRuleAreas = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(area.name, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    if (area.id == s.fishRulesAreaId) Icon(Icons.Default.CheckCircle, null)
                }
            }
        }
    }
    if (showCamera) Dialog(onDismissRequest = { showCamera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FishCameraScreen(onClose = { showCamera = false }, onPhotoCaptured = { vm.setFishPhoto(it); showCamera = false })
    }
}

@Composable fun FeedbackScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    var category by rememberSaveable { mutableStateOf("general") }
    var message by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableIntStateOf(5) }
    var submittedFeedback by remember { mutableStateOf<String?>(null) }
    var categoryExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(s.accountBusy, s.accountNotice, s.accountError) {
        if (!s.accountBusy && submittedFeedback != null) {
            if (s.accountNotice == "Thanks for your feedback." && message == submittedFeedback) message = ""
            submittedFeedback = null
        }
    }
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Feedback", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
        Text("Report a problem or tell us what would improve your fishing trips.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.account == null) {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sign in to send feedback", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Feedback is linked to your Fishdays - NZ account so we can review it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { vm.selectTab(5) }) { Text("Go to account") }
                }
            }
        } else {
            s.accountNotice?.let { Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(12.dp)) { Text(it, Modifier.fillMaxWidth().padding(14.dp), color = Navy) } }
            s.accountError?.let { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(12.dp)) { Text(it, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer) } }
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box {
                        OutlinedButton(onClick = { categoryExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Type: ${category.replaceFirstChar { it.uppercase() }}") }
                        DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                            listOf("general" to "General", "bug" to "Report a problem", "idea" to "Idea").forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { category = value; categoryExpanded = false }) }
                        }
                    }
                    OutlinedTextField(message, { message = it.take(4000) }, label = { Text("Message") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    Text("Rating", color = Navy, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { (1..5).forEach { value -> FilterChip(selected = rating == value, onClick = { rating = value }, label = { Text(value.toString()) }) } }
                    Button(enabled = !s.accountBusy && message.trim().length >= 3, onClick = { submittedFeedback = message; vm.sendFeedback(category, message, rating) }, modifier = Modifier.fillMaxWidth()) { Text(if (s.accountBusy) "Please wait…" else "Send feedback") }
                }
            }
        }
    }
}

@Composable private fun FishCameraScreen(onClose: () -> Unit, onPhotoCaptured: (android.net.Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> PreviewView(ctx).also { previewView = it } }, modifier = Modifier.fillMaxSize())
        FishCameraOverlay()
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { Text("Cancel", color = Color.White) }
        Text("Hold the fish vertically — head up", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp), fontWeight = FontWeight.Bold)
        captureError?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)) }
        FloatingActionButton(onClick = {
            val capture = imageCapture ?: return@FloatingActionButton
            captureError = null
            val photoDirectory = java.io.File(context.cacheDir, "fish-photos").apply { mkdirs() }
            photoDirectory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000L }?.forEach { it.delete() }
            val photoFile = java.io.File(photoDirectory, "fish-${System.currentTimeMillis()}.jpg")
            val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            capture.takePicture(output, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                    onPhotoCaptured(android.net.Uri.fromFile(photoFile))
                }
                override fun onError(exception: ImageCaptureException) { captureError = "Could not take this photo. Try again." }
            })
        }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), containerColor = Orange) { Icon(Icons.Default.CameraAlt, "Take photo", tint = Color.White) }
    }
    DisposableEffect(previewView, lifecycleOwner) {
        val view = previewView
        if (view == null) return@DisposableEffect onDispose { }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    captureError = "A back camera is unavailable. Close this screen and choose a photo from Gallery."
                    return@addListener
                }
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
            } catch (_: Exception) {
                captureError = "Could not open the camera. Close this screen and choose a photo from Gallery."
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { providerFuture.addListener({ runCatching { providerFuture.get().unbindAll() } }, ContextCompat.getMainExecutor(context)) }
    }
}

@Composable private fun FishCameraOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val centerX = size.width * .5f; val top = size.height * .18f; val bottom = size.height * .82f
        val body = Path().apply { moveTo(centerX, top + size.height * .08f); cubicTo(size.width * .28f, size.height * .3f, size.width * .28f, size.height * .7f, centerX, bottom - size.height * .08f); cubicTo(size.width * .72f, size.height * .7f, size.width * .72f, size.height * .3f, centerX, top + size.height * .08f); close() }
        drawPath(body, Color.White, style = Stroke(width = 5.dp.toPx()))
        val tail = Path().apply { moveTo(centerX, bottom - size.height * .06f); lineTo(size.width * .34f, bottom); lineTo(size.width * .66f, bottom); close() }
        drawPath(tail, Color.White, style = Stroke(width = 5.dp.toPx()))
        drawCircle(Color.White, 6.dp.toPx(), androidx.compose.ui.geometry.Offset(centerX, top + size.height * .14f))
    }
}

@Composable private fun FishPhotoGuide(onBack: () -> Unit, onTakePhoto: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFF17233A)) {
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text("Back", color = Color.White) }
                Text("Take a clear fish photo", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
                Text("Place the whole fish horizontally inside the guide.", color = Color.White.copy(alpha = .8f))
            }
            Card(colors = CardDefaults.cardColors(Color.White.copy(alpha = .12f)), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FishFrame()
                    Text("Keep the fish side-on, head and tail visible, with good light and a plain background.", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Before taking the photo", color = Color.White, fontWeight = FontWeight.Bold)
                Text("✓ Clean the lens   ✓ Avoid glare   ✓ Fill the frame   ✓ Keep the fish in focus", color = Color.White.copy(alpha = .85f), style = MaterialTheme.typography.bodySmall)
                Button(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Open camera") }
            }
        }
    }
}

@Composable private fun FishFrame() {
    val fishColor = Color(0xFFDCE7FF)
    val detailColor = Color(0xFF17233A)
    Canvas(Modifier.fillMaxWidth().height(210.dp)) {
        drawRoundRect(Color.White.copy(alpha = .18f), style = Stroke(width = 2.dp.toPx()))
        val body = Path().apply {
            moveTo(size.width * .18f, size.height * .5f)
            cubicTo(size.width * .3f, size.height * .18f, size.width * .7f, size.height * .18f, size.width * .84f, size.height * .5f)
            cubicTo(size.width * .7f, size.height * .82f, size.width * .3f, size.height * .82f, size.width * .18f, size.height * .5f)
            close()
        }
        drawPath(body, fishColor)
        val tail = Path().apply { moveTo(size.width * .18f, size.height * .5f); lineTo(size.width * .06f, size.height * .28f); lineTo(size.width * .06f, size.height * .72f); close() }
        drawPath(tail, fishColor)
        drawCircle(detailColor, size.minDimension * .018f, androidx.compose.ui.geometry.Offset(size.width * .77f, size.height * .42f))
        drawLine(detailColor, androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .5f), androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .5f), strokeWidth = 2.dp.toPx())
        drawLine(Color.White.copy(alpha = .7f), androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .1f), androidx.compose.ui.geometry.Offset(size.width * .92f, size.height * .1f), strokeWidth = 2.dp.toPx())
        drawLine(Color.White.copy(alpha = .7f), androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .9f), androidx.compose.ui.geometry.Offset(size.width * .92f, size.height * .9f), strokeWidth = 2.dp.toPx())
    }
}

private fun windowReason(item: Recommendation): String? =
    item.reasons.firstOrNull { it.isNotBlank() }?.trim()
        ?: item.summary.trim().takeIf { it.isNotEmpty() }

private fun comparableWindowReason(reason: String): String = reason
    .removePrefix("Some local forecast data is missing; treat this as a time to investigate. ")
    .trim()

private fun shouldShowWindowReason(item: Recommendation, results: List<Recommendation>): Boolean {
    val reason = windowReason(item) ?: return false
    return results.size < 2 || results.any { comparableWindowReason(windowReason(it).orEmpty()) != comparableWindowReason(reason) }
}

@Composable private fun WindowOutlook(outlook: String) {
    val emoji = outlook.substringBefore(' ')
    val label = outlook.substringAfter(' ', outlook)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("WINDOW OUTLOOK", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(label, color = Navy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(emoji, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable private fun WindowConditions(item: Recommendation) {
    item.assessment?.let { assessment ->
        assessment.conditions.forEachIndexed { position, condition ->
            if (position > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${condition.title}  ${condition.value}", color = Navy, style = MaterialTheme.typography.bodyMedium)
                Text("${condition.mood.emoji} ${condition.mood.label}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val labels = listOf("Tide", "Wind", "Rain", "Waves", "Daylight")
    val indices = if (item.boat) listOf(3) + item.conditions.indices.filter { it != 3 } else item.conditions.indices.toList()
    indices.filter { it in item.conditions.indices }.forEachIndexed { position, index ->
        val detail = item.conditions[index]
        if (position > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val label = labels.getOrElse(index) { "Other" }
        val mood = item.conditionMood(index)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge)
                Text("${mood.emoji} ${mood.label}", style = MaterialTheme.typography.labelMedium)
            }
            Text(detail, color = Navy, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun RecommendationCard(item: Recommendation, showWhy: Boolean = true, click: () -> Unit = {}) {
    Card(onClick = click, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy)
            Text("${item.area} · ${if (item.boat) "Boat" else "Land"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            WindowOutlook(item.windowOutlook)
            WindowChecks(item)
            Text(item.time, color = Navy, fontWeight = FontWeight.SemiBold)
            Text(item.distance, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (showWhy) Column(Modifier.fillMaxWidth().background(Seafoam, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("WHY THIS WINDOW", color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                if (!item.dataComplete && item.assessment == null) Text("Some local forecast data is missing; treat this as a time to investigate.",
                    color = Navy, style = MaterialTheme.typography.bodySmall)
                Text(windowReason(item).orEmpty(),
                    color = Navy, style = MaterialTheme.typography.bodyMedium)
            }
            if (item.conditions.isNotEmpty()) {
                Text("Conditions", color = Navy, fontWeight = FontWeight.SemiBold)
                WindowConditions(item)
            }
            val warnings = (item.warnings + listOfNotNull(item.warning)).distinct()
            if (warnings.isNotEmpty() && item.assessment == null) Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiary.copy(alpha = .10f), RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CHECK BEFORE YOU GO", color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                warnings.forEach {
                    Text("${warningMood(it).emoji} $it", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
@Composable private fun WindowChecks(item: Recommendation) {
    val assessment = item.assessment ?: return
    var expanded by rememberSaveable(item.startsAtEpochSeconds, item.name) { mutableStateOf(false) }
    Text("${assessment.checks.emoji} ${assessment.checks.label}", style = MaterialTheme.typography.labelMedium)
    Text("${assessment.confidence.emoji} ${assessment.confidence.label}", style = MaterialTheme.typography.labelMedium)
    if (assessment.comfortComplete && !assessment.matchesComfort) Text("Outside your comfort preference", color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelMedium)
    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Hide details" else "Details & sources") }
    if (expanded) {
        assessment.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (item.sourceNote.isNotEmpty()) Text(item.sourceNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun LandOptions(value: LandPreferences, change: (LandPreferences) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Hide shore options" else "Shore options") }
    if (!expanded) return
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Your shore setting", fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ShoreSetting.entries.forEach { setting -> FilterChip(value.setting == setting, { change(value.copy(setting = setting)) }, label = { Text(setting.label) }) }
        }
        Text("Comfort limit", fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..3).forEach { band -> FilterChip(value.maxBand == band, { change(value.copy(maxBand = band)) }, label = { Text("${comfortMood(band).emoji} ${comfortMood(band).label}") }) }
        }
        Text("Prefer", fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LandPriority.entries.forEach { priority -> FilterChip(value.priority == priority, { change(value.copy(priority = priority)) }, label = { Text(priority.label) }) }
        }
        listOf("Access + setup" to value.arrivalMinutes, "Return" to value.returnMinutes).forEachIndexed { index, (title, selected) ->
            Text(title, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null, 0, 15, 30, 60, 120, 180).forEach { minutes ->
                    FilterChip(selected == minutes, {
                        change(if (index == 0) value.copy(arrivalMinutes = minutes) else value.copy(returnMinutes = minutes))
                    }, label = { Text(minutes?.let { "$it min" } ?: "Not set") })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Whole visit in daylight", Modifier.weight(1f))
            Switch(value.daylightOnly, { change(value.copy(daylightOnly = it)) })
        }
        if (value.daylightOnly && (value.arrivalMinutes == null || value.returnMinutes == null))
            Text("Set access/setup and return time.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun Metric(label: String, value: String) { Column { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall); Text(value, color = Navy, fontWeight = FontWeight.SemiBold) } }

private suspend fun resolvedCity(context: android.content.Context, point: GeoPoint): String? = withContext(Dispatchers.IO) {
    runCatching {
        @Suppress("DEPRECATION")
        val address = Geocoder(context, Locale.getDefault()).getFromLocation(point.latitude, point.longitude, 1)?.firstOrNull()
        address?.locality?.takeIf { it.isNotBlank() } ?: address?.subAdminArea?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun OriginPickerSheet(onDismiss: () -> Unit, onUseCurrentLocation: () -> Unit, onChoose: (SearchOrigin) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query) { searchOrigins.filter { it.name.contains(query.trim(), ignoreCase = true) }.sortedBy { it.name } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Choose a location", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ListItem(headlineContent = { Text("Use current location") },
                leadingContent = { Icon(Icons.Default.NearMe, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onUseCurrentLocation))
            HorizontalDivider()
            OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Search cities") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.72f)) {
                items(matches, key = { it.name }) { origin ->
                    ListItem(headlineContent = { Text(origin.name) }, modifier = Modifier.fillMaxWidth().clickable { onChoose(origin) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SearchStationPickerSheet(selectedId: String?, onDismiss: () -> Unit,
    onUseCurrentLocation: () -> Unit, onChoose: (TideStation) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query) {
        tideStations.filter { it.name.contains(query.trim(), ignoreCase = true) || it.region.contains(query.trim(), ignoreCase = true) }
            .sortedBy { it.name }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Choose a tide location", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            ListItem(headlineContent = { Text("Use current location") },
                leadingContent = { Icon(Icons.Default.NearMe, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().clickable(onClick = onUseCurrentLocation))
            HorizontalDivider()
            OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Search tide locations") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.72f)) {
                items(matches, key = { it.id }) { station ->
                    ListItem(headlineContent = { Text(station.name) },
                        supportingContent = { if (station.region != "New Zealand") Text(station.region) },
                        trailingContent = { if (station.id == selectedId) Icon(Icons.Default.CheckCircle, null) },
                        modifier = Modifier.fillMaxWidth().clickable { onChoose(station) })
                    HorizontalDivider()
                }
                if (matches.isEmpty()) item { Text("No tide locations found", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable fun ResultsScreen(s: FishingUiState, vm: FishingViewModel) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Fishing windows", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy)
                OutlinedButton(onClick = vm::closeResults) { Text("Back") }
            } }
            item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(s.originName?.let { if (s.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION) "At $it" else "Near $it" }
                        ?: "Finding your location", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
                    Text("${if (s.boat) "Boat" else "Land"} fishing · ${s.dateLabel}" +
                        if (s.searchLocationMode == SearchLocationMode.NEAR_ME) " · ${s.radiusKm} km radius" else " · selected location",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val hours = when {
                        s.preferredTime == null -> "Anytime"
                        s.preferredTimeIsSuggested -> "7 AM–9 PM"
                        else -> "${s.preferredTime.start.format(preferredTimeFormatter)}–${s.preferredTime.end.format(preferredTimeFormatter)}"
                    }
                    Text("$hours · ${s.preference.label(s.boat)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } }
            if (s.searchLocationMode == SearchLocationMode.SPECIFIC_LOCATION) s.recommendationSearch?.let { search ->
                item {
                    val selectedDays = java.time.temporal.ChronoUnit.DAYS.between(s.dateStart, s.dateEnd) + 1
                    Text("Planning windows on ${search.items.size} of $selectedDays selected days. Local site checks are still needed.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            s.locationNotice?.let { item { Text(it, color = Orange, style = MaterialTheme.typography.bodySmall) } }
            when {
                s.locating -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Getting current location…", color = Navy) } }
                s.recommendationsLoading -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Checking hourly forecasts…", color = Navy) } }
                s.recommendationsError != null -> item { Column {
                    Text(s.recommendationsError, color = Orange)
                    if (s.originName != null) TextButton(onClick = vm::refreshRecommendations) { Text("Try again") }
                } }
                s.recommendationSearch?.nearbySpots == 0 -> item {
                    val nearest = s.recommendationSearch
                    Text("No known ${if (s.boat) "boat" else "land"} fishing areas are within ${s.radiusKm} km." +
                        (if (nearest?.nearestSpot != null) " The nearest is ${nearest.nearestSpot}, about ${nearest.nearestSpotDistanceKm} km away. Try a wider radius." else " Try a wider radius or another city."), color = Navy)
                }
                s.recommendationSearch?.items?.isEmpty() == true -> item {
                    Text(when {
                        s.recommendationSearch.failedSpots == s.recommendationSearch.nearbySpots -> "Forecasts could not be loaded for nearby areas. Try again."
                        s.preference == WindowPriority.LATE_INCOMING -> "No two-hour window matches the late-incoming preference with the available tide and weather data. Try ${WindowPriority.WEATHER.label(s.boat)} or another date."
                        s.dateLabel == "Today" -> "No two-hour planning window remains today within your selected hours and the available forecasts. Try another date or adjust your hours."
                        else -> "No two-hour planning window matches these dates, hours and the available forecasts. Try another date or adjust your hours."
                    }, color = Navy)
                }
                else -> {
                    val results = s.recommendationSearch?.items.orEmpty()
                    if (!s.boat && results.isNotEmpty() && results.none { it.assessment?.matchesComfort == true }) item {
                        Text(if (results.all { it.assessment?.comfortComplete == false }) "More data needed · partial options below"
                            else "No window meets your comfort preference · alternatives below", color = Orange)
                    }
                    items(results) { RecommendationCard(it, showWhy = shouldShowWindowReason(it, results)) { vm.openSpot(it) } }
                }
            }
            if ((s.recommendationSearch?.failedSpots ?: 0) > 0 && !s.recommendationsLoading) item { Text("Forecasts failed for ${s.recommendationSearch?.failedSpots} nearby spot(s); no planning windows are shown for those spots.", color = Orange, style = MaterialTheme.typography.bodySmall) }
            item { Text("Planning windows compare forecast conditions. Local access, wave exposure, marine warnings and current MPI rules still need checking.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            item { Text("Weather and offshore waves: Open-Meteo. Tide predictions, where verified: LINZ. See each window for its sources and limitations.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable fun SpotDetailScreen(spot: Recommendation, saved: Boolean, vm: FishingViewModel) {
    val context = LocalContext.current
    val appState = vm.state.value
    val reasonPeers = appState.recommendationSearch?.items?.takeIf { spot in it } ?: appState.savedRecommendations
    var addToCalendar by rememberSaveable(recommendationKey(spot)) { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedButton(onClick = { vm.closeSpot() }) { Text("Back") }
            Text(spot.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Navy)
            Text(spot.area, color = MaterialTheme.colorScheme.onSurfaceVariant)
            WindowOutlook(spot.windowOutlook)
            WindowChecks(spot)
            Text(spot.time, color = Orange, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(spot.distance, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (shouldShowWindowReason(spot, reasonPeers)) Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Why this window", fontWeight = FontWeight.Bold, color = Navy)
                    if (!spot.dataComplete && spot.assessment == null) Text("Some local forecast data is missing; treat this as a time to investigate.", color = Navy)
                    Text(windowReason(spot).orEmpty(), color = Navy)
                    spot.alternative?.takeIf { it.isNotBlank() }?.let {
                        Text("Another option", fontWeight = FontWeight.SemiBold, color = Navy)
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else spot.alternative?.takeIf { it.isNotBlank() }?.let {
                Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Another option", fontWeight = FontWeight.SemiBold, color = Navy)
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (spot.conditions.isNotEmpty()) Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Conditions", fontWeight = FontWeight.Bold, color = Navy)
                    WindowConditions(spot)
                }
            }
            val warnings = (spot.warnings + listOfNotNull(spot.warning)).distinct()
            if (warnings.isNotEmpty() && spot.assessment == null) Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiary.copy(alpha = .10f)), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Check before you go", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                    warnings.forEach { Text("${warningMood(it).emoji} $it", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (spot.sourceNote.isNotBlank() && spot.assessment == null) Text(spot.sourceNote, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.toggleSaved(spot) }, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "Remove saved spot" else "Save spot") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = addToCalendar, onCheckedChange = { addToCalendar = it })
                Text("Add this trip to my calendar")
            }
            if (addToCalendar && (spot.startsAtEpochSeconds <= 0 || spot.durationHours <= 0))
                Text("Choose the date and time in your calendar.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                vm.startTrip()
                if (addToCalendar && !openTripInCalendar(context, spot))
                    android.widget.Toast.makeText(context, "No calendar app is available on this device.", android.widget.Toast.LENGTH_LONG).show()
            }, modifier = Modifier.fillMaxWidth()) { Text("Start fishing trip") }
        }
    }
}

@Composable fun TripsScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Your trips", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
            Text("Saved spots and active plans", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        s.activeTrip?.let { trip -> item {
            Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Active trip", color = Navy, fontWeight = FontWeight.Bold)
                    Text(trip.name, style = MaterialTheme.typography.titleLarge, color = Navy)
                    if (trip.time.isNotBlank()) Text(trip.time, color = Navy)
                    if (trip.summary.isNotBlank()) Text(trip.summary, color = Navy)
                    (trip.warnings + listOfNotNull(trip.warning)).distinct().forEach {
                        Text(it, color = Orange, style = MaterialTheme.typography.bodySmall)
                    }
                    if (trip.startsAtEpochSeconds <= 0 || trip.durationHours <= 0)
                        Text("Choose the date and time in your calendar.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        if (!openTripInCalendar(context, trip))
                            android.widget.Toast.makeText(context, "No calendar app is available on this device.", android.widget.Toast.LENGTH_LONG).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Add to calendar") }
                    OutlinedButton(onClick = vm::endTrip) { Text("End trip") }
                }
            }
        } }
        item { Text("Saved spots", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        if (s.savedRecommendations.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No saved spots yet", color = Navy, fontWeight = FontWeight.Bold)
                    Text("Search for a fishing window, then save a spot to find it here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.selectTab(0) }) { Text("Find a spot") }
                }
            }
        }
        else items(s.savedRecommendations) {
            RecommendationCard(it, showWhy = shouldShowWindowReason(it, s.savedRecommendations)) { vm.openSpot(it) }
        }
    }
}
internal data class FishingRulesArea(val id: String, val name: String, val slug: String, val description: String) {
    val officialUrl: String get() = "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/$slug"
}
internal val fishingRulesAreas = listOf(
    FishingRulesArea("auckland-kermadec", "Auckland / Kermadec", "auckland-kermadec-fishing-rules", "Northland, Auckland, Waikato, Bay of Plenty and the Kermadec Islands"),
    FishingRulesArea("central", "Central", "central-fishing-rules", "North Island coast from Cape Runaway to Tirua Point"),
    FishingRulesArea("challenger", "Challenger", "challenger-fishing-rules", "West Coast north of Awarua Point, through Marlborough to Clarence Point"),
    FishingRulesArea("south-east", "South-East", "south-east-fishing-rules", "South Island east coast from Clarence Point to Slope Point"),
    FishingRulesArea("southland", "Southland", "southland-fishing-rules", "Coast from Awarua Point around the south to Slope Point, including Rakiura"),
    FishingRulesArea("kaikoura", "Kaikōura Marine Area", "kaikoura-fishing-rules", "Special area from Clarence Point to the Conway River mouth, up to 12 nautical miles offshore"),
    FishingRulesArea("chatham-rise", "Chatham Rise", "chatham-rise-area-recreational-fishing-rules", "Chatham Islands and surrounding Chatham Rise waters"),
    FishingRulesArea("fiordland", "Fiordland Marine Area", "fiordland-marine-area-fishing-rules", "Special area from Awarua Point to Sand Hill Point, up to 12 nautical miles offshore")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RulesScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current
    val repository = remember { RulesRepository() }
    val selectedAreaId = s.fishRulesAreaId
    var showAreas by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by remember { mutableStateOf<FishingRulesPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var locationLoading by remember { mutableStateOf(false) }
    var locationNotice by remember { mutableStateOf<String?>(null) }
    val area = fishingRulesAreas.firstOrNull { it.id == selectedAreaId }

    fun hasLocationPermission() =
        (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    fun refreshLocation(selectCurrentArea: Boolean = false) {
        if (!hasLocationPermission() || locationLoading) return
        locationLoading = true
        locationNotice = null
        requestCurrentLocation(context, { point ->
            locationLoading = false
            vm.updateLocation(point)
            if (selectCurrentArea) vm.resetFishRulesAreaToCurrentLocation()
        }, {
            locationLoading = false
            if (selectCurrentArea) locationNotice = "Current location is unavailable. Choose the fishing area below."
        })
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            refreshLocation(selectCurrentArea = true)
        else locationNotice = "Location permission is off. Choose the area where you will fish."
    }
    LaunchedEffect(Unit) { refreshLocation() }
    DisposableEffect(lifecycle) {
        var initialResumeSeen = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (initialResumeSeen) refreshLocation()
                initialResumeSeen = true
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(selectedAreaId, reloadToken) {
        page = null
        error = null
        loading = false
        val id = selectedAreaId ?: return@LaunchedEffect
        loading = true
        try { page = repository.load(id) }
        catch (exception: Exception) { error = exception.message ?: "Saved rules are unavailable right now." }
        finally { loading = false }
    }

    if (showAreas) ModalBottomSheet(onDismissRequest = { showAreas = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Choose fishing area", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("MPI divides the coast into these fishing areas. Choose where you will fish.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                if (hasLocationPermission()) refreshLocation(selectCurrentArea = true)
                else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                query = ""
                showAreas = false
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.NearMe, null); Spacer(Modifier.width(8.dp))
                Text("Use my current location", modifier = Modifier.weight(1f))
            }
            fishingRulesAreas.forEach { option ->
                TextButton(onClick = {
                    vm.chooseFishRulesArea(option.id)
                    query = ""
                    showAreas = false
                }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(option.name, color = MaterialTheme.colorScheme.onSurface)
                        Text(option.description, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (option.id == selectedAreaId) Icon(Icons.Default.CheckCircle, contentDescription = "Selected")
                }
            }
        }
    }

    val search = query.trim()
    val bagSummary = if (selectedAreaId == "fiordland")
        "Daily limits differ between the outer Fiordland Marine Area and the inner Fiords. Check the exact subarea on MPI."
    else page?.sections?.firstNotNullOfOrNull { section ->
        Regex("combined daily bag limit of\\s+\\d+\\s+finfish[^.]*\\.", RegexOption.IGNORE_CASE)
            .find(section.text)?.value?.let { source ->
                cleanMpiRuleText(source) + if (hasMpiFootnoteMarker(source)) " See MPI's definition of finfish." else ""
            }
    } ?: "Daily limits vary by species and location. Check the MPI page for the exact fishing spot."
    val nearRaglanWest = s.fishRulesAreaIsSuggested && selectedAreaId == "auckland-kermadec" && isNearRaglan(s.deviceLocation)
    val speciesRules = page?.let(::ruleSpeciesRows).orEmpty().let { rows ->
        if (nearRaglanWest && rows.any { it.species.contains("Auckland West", ignoreCase = true) })
            rows.filter { !it.species.startsWith("Snapper", ignoreCase = true) || it.species.contains("Auckland West", ignoreCase = true) }
        else rows
    }
    val matchingSpecies = if (search.isEmpty()) {
        val preferred = listOf("snapper", "blue cod", "kingfish", "kahawai", "pāua", "paua", "cockle")
        speciesRules.filter { rule -> preferred.any { rule.species.startsWith(it, ignoreCase = true) } }
            .sortedBy { rule -> preferred.indexOfFirst { rule.species.startsWith(it, ignoreCase = true) } }
            .take(8)
    } else speciesRules.filter { it.species.contains(search, ignoreCase = true) }.take(40)
    LazyColumn(modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Fishing rules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Choose an MPI area, then search a species for its key limits.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Independent app; not affiliated with or endorsed by the New Zealand Government or MPI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Where will you fish?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { showAreas = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(area?.name ?: "Choose an area", modifier = Modifier.weight(1f))
                        Text("⌄")
                    }
                    if (s.fishRulesAreaIsSuggested) Text("Suggested from your current location", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (area != null) Text("Manually selected fishing area", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (nearRaglanWest) Text("Near Raglan: snapper is in the Auckland West subarea. Check the exact catch spot on MPI.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (locationLoading) Text("Checking current location…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    locationNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    area?.let { Text(it.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("GPS on land cannot safely identify the exact marine rule boundary. Select the MPI area for your fishing spot.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (selectedAreaId != null && page?.needsReview != true) item { OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Search a fish or shellfish") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } }) }
        if (loading) item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        error?.let { message -> item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                TextButton(onClick = { reloadToken++ }) { Text("Try again") }
                TextButton(onClick = { area?.let { uriHandler.openUri(it.officialUrl) } }) { Text("Open official MPI rules") }
            }
        } } }
        page?.let { rules ->
            if (rules.needsReview) item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MPI has updated this area", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
                    Text("Open the current MPI page for limits and local restrictions. The saved summary is being reviewed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { uriHandler.openUri(area?.officialUrl ?: rules.sourceUrl) }) { Text("Open current MPI rules") }
                }
            } }
            else {
            item {
                Text(if (search.isEmpty()) "At a glance" else "${matchingSpecies.size} matching species",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                rules.reviewedAt?.let { Text("MPI last reviewed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (search.isEmpty()) item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Combined finfish limit", fontWeight = FontWeight.Bold, color = Navy)
                    Text(bagSummary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
            if (matchingSpecies.isNotEmpty()) item { Text(if (search.isEmpty()) "Common species" else "Species and limits", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            matchingSpecies.forEach { rule -> item(key = "species-${rule.id}") { RuleSpeciesCard(rule, area?.officialUrl ?: rules.sourceUrl) } }
            if (matchingSpecies.isEmpty()) item {
                Text(if (search.isEmpty()) "Search for a species to see its saved limits."
                    else "No reliable simple limit can be shown for “$search” in ${rules.areaName}. Check MPI for this species and exact subarea.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { Text("Local closures, gear restrictions and subarea rules can change what applies. Check MPI for your exact fishing spot.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            item { Button(onClick = { uriHandler.openUri(area?.officialUrl ?: rules.sourceUrl) }, modifier = Modifier.fillMaxWidth()) { Text("See all rules on MPI") } }
            }
        }
        if (selectedAreaId == null) item { TextButton(onClick = { uriHandler.openUri("https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules") }) { Text("View MPI fishing-area maps") } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
private data class RuleSpeciesRow(val id: String, val species: String, val facts: List<Pair<String, String>>, val note: String?, val hasFootnote: Boolean)

private fun ruleFactLabel(header: String): String? {
    val text = header.lowercase()
    return when {
        text.contains("daily limit") && text.contains("outside fiord") -> "Daily limit · outer area"
        text.contains("daily limit") && text.contains("the fiords") -> "Daily limit · inner fiords"
        text.contains("daily limit") && text.contains("auckland coromandel") -> "Daily limit · Auckland/Coromandel"
        text.contains("daily limit") -> "Daily limit"
        text.contains("fish length") -> "Minimum length"
        text.contains("min size") || text.contains("minimum size") -> "Minimum size"
        text.contains("tail width") -> "Minimum tail width"
        else -> null
    }
}

private fun ruleSpeciesRows(page: FishingRulesPage): List<RuleSpeciesRow> = page.tables.flatMapIndexed { tableIndex, table ->
    val headers = table.firstOrNull().orEmpty()
    if (headers.size < 2 || !headers.first().contains("species", ignoreCase = true)) return@flatMapIndexed emptyList()
    val factColumns = headers.indices.drop(1).mapNotNull { index -> ruleFactLabel(headers[index])?.let { index to it } }
    if (factColumns.isEmpty()) return@flatMapIndexed emptyList()
    table.drop(1).mapIndexedNotNull { rowIndex, row ->
        val rawSpecies = row.firstOrNull()?.trim().orEmpty()
        if (rawSpecies.isEmpty() || rawSpecies.startsWith("*") || rawSpecies.length > 180 || rawSpecies.equals("Finfish species", true)) return@mapIndexedNotNull null
        val qualifier = if (row.size == headers.size && headers.getOrNull(1)?.equals(headers.first(), true) == true)
            row.getOrNull(1)?.trim().orEmpty() else ""
        val species = cleanMpiRuleText(rawSpecies).let { name ->
            if (qualifier.isNotBlank() && !qualifier.equals(rawSpecies, true))
                "$name — ${cleanMpiRuleText(qualifier)}" else name
        }
        if (species.isEmpty()) return@mapIndexedNotNull null
        val notes = mutableListOf<String>()
        val hasFootnote = row.any(::hasMpiFootnoteMarker)
        if (hasFootnote) notes += "MPI footnote applies. Read the full condition on the official page."
        if (rawSpecies.contains("refer to map", true)) notes += "Check the exact area boundary on MPI."
        val facts = if (row.size != headers.size) {
            notes += "This saved row cannot be matched safely to its columns. Check the official MPI limit."
            listOf("Area-specific rule" to "See MPI")
        } else factColumns.mapNotNull { (index, label) ->
            val rawValue = row.getOrNull(index)?.trim().orEmpty()
            if (rawValue.isBlank() || rawValue in listOf("—", "–", "-", "none")) return@mapNotNull null
            val clean = cleanMpiRuleText(rawValue)
            val value = when {
                clean.contains("No take allowed", true) -> "No take"
                clean.contains("See below", true) -> { notes += "Check the area-specific rule on MPI."; "See MPI" }
                label == "Minimum tail width" -> clean
                Regex("\\d+").findAll(clean).count() > 1 -> { notes += "This value covers multiple species or subareas; check MPI."; "Varies — see MPI" }
                label.startsWith("Minimum") && clean.matches(Regex("^\\d+.*")) -> {
                    val number = Regex("^\\d+").find(clean)!!.value
                    val remainder = clean.removePrefix(number).trim()
                    if (remainder.isNotEmpty()) notes += remainder
                    "$number ${if (headers[index].contains("(cm)", true)) "cm" else "mm"}"
                }
                else -> clean
            }
            label to value
        }
        if (facts.isEmpty()) return@mapIndexedNotNull null
        RuleSpeciesRow("$tableIndex-$rowIndex", species, facts, notes.distinct().takeIf { it.isNotEmpty() }?.joinToString(" "), hasFootnote)
    }
}

@Composable private fun RuleSpeciesCard(rule: RuleSpeciesRow, officialUrl: String) {
    val uriHandler = LocalUriHandler.current
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(rule.species, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Navy)
            rule.facts.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(12.dp))
                    Text(value, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
            }
            rule.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (rule.hasFootnote || rule.facts.any { (_, value) -> value.contains("MPI") }) {
                TextButton(onClick = { uriHandler.openUri(officialUrl) }) {
                    Text(if (rule.hasFootnote) "Read MPI footnote" else "Check official MPI rule")
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TideScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    var showStations by rememberSaveable { mutableStateOf(false) }
    var stationQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(s.tideDeviceLocation) {
        s.tideDeviceLocation?.let { resolvedCity(context, it)?.let(vm::setResolvedTidePlace) }
    }
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Pacific/Auckland"))
    val formatter = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.US)
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            requestCurrentLocation(context, { vm.updateTideLocation(it) }, { vm.tideLocationUnavailable() })
        else vm.tideLocationUnavailable()
    }
    fun useCurrentLocation() {
        vm.beginTideLocationSearch()
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) requestCurrentLocation(context, { vm.updateTideLocation(it) }, { vm.tideLocationUnavailable() })
        else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) { if (!s.tideLocationAttempted) useCurrentLocation() }
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Tide forecast", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
            Text("${s.selectedStation.name} · official LINZ tide times", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Tide station", color = Navy, fontWeight = FontWeight.Bold)
                    TextButton(onClick = ::useCurrentLocation) { Icon(Icons.Default.NearMe, null); Spacer(Modifier.width(4.dp)); Text(s.tidePlaceName ?: "Use my location") }
                }
                Text(if (s.tideStationManual) "Selected: ${s.selectedStation.name}"
                    else if (s.tideDeviceLocation != null) "Nearest to your location: ${s.selectedStation.name}"
                    else "Using ${s.selectedStation.name} until your location is available", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                s.tideLocationNotice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { showStations = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(s.selectedStation.name, modifier = Modifier.weight(1f))
                    Text("Change station  ⌄")
                }
            }
        } }
        item {
            val lastDay = java.time.LocalDate.of(2029, 12, 31)
            Column(Modifier.fillMaxWidth().pointerInput(s.tideDate) {
                var drag = 0f
                detectHorizontalDragGestures(onDragStart = { drag = 0f }, onHorizontalDrag = { change, amount ->
                    drag += amount
                    change.consume()
                }, onDragEnd = {
                    if (drag < -72.dp.toPx() && s.tideDate < lastDay) vm.changeTideDate(s.tideDate.plusDays(1))
                    if (drag > 72.dp.toPx() && s.tideDate > today) vm.changeTideDate(s.tideDate.minusDays(1))
                })
            }) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    IconButton(enabled = s.tideDate > today, onClick = { vm.changeTideDate(s.tideDate.minusDays(1)) }) {
                        Icon(Icons.Default.ChevronLeft, "Previous day")
                    }
                    Text(s.tideDate.format(formatter), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    IconButton(enabled = s.tideDate < lastDay, onClick = { vm.changeTideDate(s.tideDate.plusDays(1)) }) {
                        Icon(Icons.Default.ChevronRight, "Next day")
                    }
                }
                Text("Swipe this date bar to change day", modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        s.stationTide?.let { tide ->
            item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text((if (s.tideDate == today) "Estimated now" else "Estimated at 12:00 PM") +
                        " · ${tide.currentLevel} above Chart Datum", color = Navy, fontWeight = FontWeight.Bold)
                    Text("Next ${tide.nextEvent} · ${tide.eventTime}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
            item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tide curve", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    Text("Tap or drag the curve to inspect a time and height. Swipe the date bar to change day.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    TideCurve(tide.points, s.tideDate)
                    if (tide.points.firstOrNull()?.minuteOfDay != 0 || tide.points.lastOrNull()?.minuteOfDay != 1440)
                        Text("Curve is limited where an adjacent day's table is unavailable.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text("Curve heights between LINZ high and low tides are estimates.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } }
            item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Official high and low tides", color = Navy, fontWeight = FontWeight.Bold)
                tide.events.forEach { event -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), Arrangement.SpaceBetween) {
                    Text(event.type, color = Navy, fontWeight = FontWeight.SemiBold)
                    Text("${event.time} · ${event.height}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            } }
        } ?: item {
            if (s.tideLoading) CircularProgressIndicator()
            else Text(s.tideError ?: "LINZ tide data unavailable for this station and date.", color = Navy)
        }
        item { Text("High and low tide predictions: Toitū Te Whenua Land Information New Zealand (LINZ). Times are New Zealand local time; heights are above the station's Chart Datum. Check the official table before planning around water depth.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
    if (showStations) ModalBottomSheet(onDismissRequest = { showStations = false }) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Choose tide location", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("${tideStations.size} LINZ daily-prediction locations", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(stationQuery, onValueChange = { stationQuery = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Search location") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
            val matching = remember(stationQuery) { tideStations.filter { it.name.contains(stationQuery.trim(), ignoreCase = true) } }
            LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(.72f)) {
                items(matching, key = { it.id }) { station ->
                    ListItem(headlineContent = { Text(station.name) },
                        trailingContent = { if (station.id == s.selectedStation.id) Icon(Icons.Default.CheckCircle, null) },
                        modifier = Modifier.fillMaxWidth().clickable { vm.chooseStation(station); showStations = false })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable private fun TideCurve(points: List<TidePoint>, date: java.time.LocalDate) {
    if (points.size < 2) return
    val scheme = MaterialTheme.colorScheme
    val curveColor = scheme.primary
    val fillColor = scheme.primaryContainer
    val surfaceColor = scheme.surface
    val guideColor = scheme.outlineVariant
    val markerColor = scheme.tertiary
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Pacific/Auckland"))
    val currentTime = LocalTime.now(java.time.ZoneId.of("Pacific/Auckland"))
    var minute by remember(points, date) { mutableFloatStateOf(
        (if (date == today) currentTime.hour * 60 + currentTime.minute else 720).toFloat()
            .coerceIn(points.first().minuteOfDay.toFloat(), points.last().minuteOfDay.toFloat())) }
    val left = points.lastOrNull { it.minuteOfDay <= minute } ?: points.first()
    val right = points.firstOrNull { it.minuteOfDay >= minute } ?: points.last()
    val fraction = if (right.minuteOfDay == left.minuteOfDay) 0.0
        else ((minute - left.minuteOfDay) / (right.minuteOfDay - left.minuteOfDay)).toDouble()
    val selectedHeight = left.level + (right.level - left.level) * fraction
    val selectedMinute = minute.roundToInt().coerceIn(0, 1440)
    val selectedTime = LocalTime.of((selectedMinute / 60) % 24, selectedMinute % 60).format(preferredTimeFormatter) +
        if (selectedMinute == 1440) " next day" else ""
    val minimum = points.minOf { it.level }; val maximum = points.maxOf { it.level }
    val range = (maximum - minimum).coerceAtLeast(0.2)
    Text("$selectedTime  ·  ${"%.2f".format(Locale.US, selectedHeight)} m", color = scheme.onSurface,
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("Estimated height above Chart Datum", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    Canvas(Modifier.fillMaxWidth().height(190.dp)
        .pointerInput(points) { detectTapGestures { offset ->
            minute = (offset.x / size.width * 1440f).coerceIn(points.first().minuteOfDay.toFloat(), points.last().minuteOfDay.toFloat())
        } }
        .pointerInput(points) { detectHorizontalDragGestures(onDragStart = { offset ->
            minute = (offset.x / size.width * 1440f).coerceIn(points.first().minuteOfDay.toFloat(), points.last().minuteOfDay.toFloat())
        }, onHorizontalDrag = { change, _ ->
            minute = (change.position.x / size.width * 1440f).coerceIn(points.first().minuteOfDay.toFloat(), points.last().minuteOfDay.toFloat())
            change.consume()
        }) }) {
        val top = 12.dp.toPx(); val bottom = size.height - 12.dp.toPx()
        fun x(point: TidePoint) = size.width * point.minuteOfDay / 1440f
        fun y(level: Double) = (top + (maximum - level) / range * (bottom - top)).toFloat()
        for (step in 0..3) {
            val guideY = top + (bottom - top) * step / 3f
            drawLine(guideColor, Offset(0f, guideY), Offset(size.width, guideY), 1.dp.toPx())
        }
        val path = Path().apply { points.forEachIndexed { index, point ->
            if (index == 0) moveTo(x(point), y(point.level)) else lineTo(x(point), y(point.level))
        } }
        val area = Path().apply {
            moveTo(x(points.first()), bottom)
            points.forEach { lineTo(x(it), y(it.level)) }
            lineTo(x(points.last()), bottom)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(fillColor, surfaceColor), startY = top, endY = bottom))
        drawPath(path, curveColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        val selectedX = size.width * minute / 1440f
        drawLine(markerColor, Offset(selectedX, top), Offset(selectedX, bottom), 1.5.dp.toPx())
        drawCircle(markerColor, 6.dp.toPx(), Offset(selectedX, y(selectedHeight)))
    }
    Slider(value = minute, onValueChange = { minute = it },
        valueRange = points.first().minuteOfDay.toFloat()..points.last().minuteOfDay.toFloat(),
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(thumbColor = markerColor, activeTrackColor = curveColor))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM").forEach { label ->
            Text(label, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal fun requestCurrentLocation(context: android.content.Context, onLocation: (GeoPoint) -> Unit, onUnavailable: () -> Unit = {}) {
    try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        fun requestFresh(cached: android.location.Location?) {
            val token = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                .addOnSuccessListener { location ->
                    val usable = location ?: cached?.takeIf { System.currentTimeMillis() - it.time in 0L..300_000L }
                    if (usable != null) onLocation(GeoPoint(usable.latitude, usable.longitude)) else onUnavailable()
                }.addOnFailureListener {
                    val usable = cached?.takeIf { System.currentTimeMillis() - it.time in 0L..300_000L }
                    if (usable != null) onLocation(GeoPoint(usable.latitude, usable.longitude)) else onUnavailable()
                }
        }
        client.lastLocation.addOnSuccessListener { cached ->
            if (cached != null && System.currentTimeMillis() - cached.time in 0L..60_000L)
                onLocation(GeoPoint(cached.latitude, cached.longitude))
            else requestFresh(cached)
        }.addOnFailureListener { requestFresh(null) }
    } catch (_: SecurityException) { onUnavailable() }
}
