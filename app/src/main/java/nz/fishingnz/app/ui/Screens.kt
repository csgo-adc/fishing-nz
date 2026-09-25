package nz.fishingnz.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import nz.fishingnz.app.data.rulesAreaForLocation
import nz.fishingnz.app.viewmodel.FishingUiState
import nz.fishingnz.app.viewmodel.FishingViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val preferredTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HomeScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.setFishPhoto(it) }
    val context = LocalContext.current
    var showPlan by rememberSaveable { mutableStateOf(false) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            requestCurrentLocation(context, { point -> vm.completeLocationSearch(point) }, { vm.locationUnavailable() })
        else vm.locationUnavailable()
    }
    fun search() {
        vm.startLocationSearch()
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) requestCurrentLocation(context, { point -> vm.completeLocationSearch(point) }, { vm.locationUnavailable() })
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
                    Text("$dateSummary  ·  $time  ·  ${s.radiusKm} km", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                    Text(s.originName?.let { "From $it" } ?: "From your location or a chosen city", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = ::search, modifier = Modifier.fillMaxWidth()) { Text("Find fishing windows") }
                    TextButton(onClick = { vm.showResults() }) { Text(if (s.originName == null) "Choose a city" else "Change city") }
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
        item { Text("Your next best window", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        item { s.recommendationSearch?.items?.firstOrNull()?.let { RecommendationCard(it) { vm.showResults() } } ?: Text("Choose a date and search to see forecast-based scores.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Spacer(Modifier.height(12.dp)) }
    }
    if (showPlan) ModalBottomSheet(onDismissRequest = { showPlan = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Plan your fishing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Fishing from", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !s.boat, onClick = { vm.setBoat(false) }, label = { Text("Land") })
                FilterChip(selected = s.boat, onClick = { vm.setBoat(true) }, label = { Text("Boat") })
            }
            RecommendationFilters(s, vm)
            Button(onClick = { showPlan = false; search() }, modifier = Modifier.fillMaxWidth()) { Text("Find fishing windows") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable private fun RecommendationFilters(s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    val pickerTheme = if (MaterialTheme.colorScheme.background.luminance() < .5f)
        android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("When are you going?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Today", "In 3 days", "Next 3 days", "This weekend").forEach { choice ->
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
        Text("Only full 2–3 hour windows within these hours are shown. Each selected day is a window's start day.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text("Search radius", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 30, 50, 100, 200, 300, 400, 500).forEach { radius ->
                FilterChip(selected = s.radiusKm == radius, onClick = { vm.setRadius(radius) }, label = { Text("$radius km") })
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

@Composable private fun FishIdentifierCard(s: FishingUiState, picker: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>, vm: FishingViewModel) {
    val context = LocalContext.current
    val hasFishAccess = s.account != null
    val needsAccountRefresh = s.account == null && s.hasStoredSession && !s.accountLoading
    var showCamera by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) showCamera = true }
    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val image = s.fishPhoto?.let { uri -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { bitmap -> java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray() } }
        if (image != null && granted) requestCurrentLocation(context, { vm.identifyFish(image, it, true) }, { vm.identifyFish(image, s.location, false) })
        else if (image != null) vm.identifyFish(image, s.location, false)
    }
    fun identifyAtCurrentLocation(image: ByteArray) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) requestCurrentLocation(context, { vm.identifyFish(image, it, true) }, { vm.identifyFish(image, s.location, s.hasDeviceLocation) })
        else locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(Seafoam, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.CameraAlt, null, tint = Navy) }; Spacer(Modifier.width(12.dp)); Column { Text("What fish is this?", style = MaterialTheme.typography.titleLarge, color = Navy, fontWeight = FontWeight.Bold); Text("AI ID + local rules check", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
        s.fishPhoto?.let { uri -> val bitmap = remember(uri) { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }; bitmap?.let { Image(it.asImageBitmap(), "Selected fish photo", Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop) } }
        s.fishCheck?.let { result -> Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(result.commonName, color = Navy, fontWeight = FontWeight.Bold); Text("${result.confidence}% match", color = Orange, fontWeight = FontWeight.Bold) }
            Text(result.scientificName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("MPI rules · ${result.areaName}", color = Navy, fontWeight = FontWeight.SemiBold); result.rulesReviewedAt?.let { Text("Reviewed $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) } }
            if (result.fishRules.isEmpty()) Text("No species-specific size or catch-limit entry was found in the saved rules for this area. Check local closures and restrictions before keeping this fish.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(12.dp))
            result.fishRules.forEach { rule ->
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(rule.species, color = Navy, fontWeight = FontWeight.Bold)
                    rule.minimumSize?.let { Text("Minimum size: $it", color = Navy) }
                    rule.dailyLimit?.let { Text("Daily limit: $it", color = Navy) }
                    rule.details.forEach { Text("${it.label}: ${it.value}", color = Navy) }
                }
            }
            Text(if (result.areaIsEstimated) "Fishing area is estimated because device location was unavailable. Confirm where you are fishing." else "Area selected from current device location. Confirm the exact fishing location.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text("Check local closures and current MPI rules before keeping a fish.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } } }
        if (s.fishChecking) Text("Checking the photo…", color = Orange, fontWeight = FontWeight.SemiBold)
        s.fishError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera = true else cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(4.dp)); Text("Take photo") }; OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(4.dp)); Text(if (s.fishPhoto == null) "Choose photo" else "Gallery") } }
        Button(enabled = !s.fishChecking && !s.accountLoading && (!hasFishAccess || s.fishPhoto != null), onClick = {
            if (needsAccountRefresh) vm.refreshAccount()
            else if (s.account == null) vm.selectTab(5)
            else if (hasFishAccess) s.fishPhoto?.let { uri -> context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { bitmap ->
                        val output = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)
                        identifyAtCurrentLocation(output.toByteArray())
                    }
                } }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(4.dp)); Text(when {
            hasFishAccess -> "Identify fish"
            s.accountLoading -> "Checking account…"
            needsAccountRefresh -> "Retry account check"
            else -> "Sign in to identify fish"
        }) }
        if (!hasFishAccess) Text(when {
            s.accountLoading -> "Checking your account access."
            needsAccountRefresh -> "Your saved session is still on this device, but account access could not be checked."
            else -> "Sign in to use fish identification."
        }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text("AI suggestions are a guide. Confirm species, area and current MPI rules before keeping a fish.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    } }
    if (showCamera) Dialog(onDismissRequest = { showCamera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FishCameraScreen(onClose = { showCamera = false }, onPhotoCaptured = { vm.setFishPhoto(it); showCamera = false })
    }
}

@Composable fun AccountScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var countryCode by rememberSaveable { mutableStateOf("NZ") }
    var createAccount by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf("general") }
    var message by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableIntStateOf(5) }
    var submittedFeedback by remember { mutableStateOf<String?>(null) }
    val validEmail = remember(email) { android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() }
    val validPassword = password.isNotBlank() && (!createAccount || password.length >= 10)
    LaunchedEffect(s.account) {
        s.account?.let { displayName = it.user.displayName; countryCode = it.user.countryCode; email = it.user.email }
    }
    LaunchedEffect(s.verificationPending, s.account) {
        if (s.verificationPending || s.account != null) {
            createAccount = false
            password = ""
        }
    }
    LaunchedEffect(s.accountBusy, s.accountNotice, s.accountError) {
        if (!s.accountBusy && submittedFeedback != null) {
            if (s.accountNotice == "Thanks for your feedback." && message == submittedFeedback) message = ""
            submittedFeedback = null
        }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
        Text("Save your details, share feedback, and check your CatchCheck features.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        s.accountNotice?.let { Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(12.dp)) { Text(it, Modifier.fillMaxWidth().padding(14.dp), color = Navy) } }
        s.accountError?.let { Card(colors = CardDefaults.cardColors(Color(0xFFFFEBE7)), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text(it, color = MaterialTheme.colorScheme.error)
                if (s.account == null && it.startsWith("Could not check your account")) TextButton(onClick = vm::refreshAccount) { Text("Retry account check") }
            }
        } }
        if (s.account == null && s.accountLoading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Checking your account…", color = Navy)
            }
        } else if (s.account == null && s.hasStoredSession) {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Session saved", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
                    Text("Your sign-in is saved, but we could not check account access. Check your connection and try again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = vm::refreshAccount) { Text("Retry account check") }
                }
            }
        } else if (s.account == null) {
            if (s.verificationPending) Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(16.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Check your inbox", color = Navy, fontWeight = FontWeight.Bold); Text("Open the confirmation email, then sign in. The link expires after 24 hours.", color = Navy); OutlinedButton(enabled = !s.accountBusy && validEmail, onClick = { vm.resendVerification(email) }) { Text("Resend confirmation email") } } }
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(2.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Secure access", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
                Text("Create an account or sign in to manage your profile.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = createAccount, onClick = { createAccount = true }, label = { Text("Create account") })
                    FilterChip(selected = !createAccount, onClick = { createAccount = false }, label = { Text("Sign in") })
                }
                if (createAccount) OutlinedTextField(displayName, { displayName = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Password") }, supportingText = { Text(if (createAccount) "At least 10 characters" else "Enter your password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Button(enabled = !s.accountBusy && validEmail && validPassword, onClick = { vm.signIn(email.trim(), password, displayName, createAccount) }, modifier = Modifier.fillMaxWidth()) { Text(if (s.accountBusy) "Please wait…" else if (createAccount) "Create account" else "Sign in") }
                if (!createAccount && !s.verificationPending) TextButton(enabled = !s.accountBusy && validEmail, onClick = { vm.resendVerification(email.trim()) }) { Text("Resend confirmation email") }
            } }
        } else {
            Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Signed in", color = Navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Fish identification and your fishing tools are ready.", color = Navy)
            } }
            Text("Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy)
            Text(s.account.user.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(displayName, { displayName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(countryCode, { countryCode = it.take(2).uppercase() }, label = { Text("Country code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = !s.accountBusy, onClick = { vm.saveAccountProfile(displayName, countryCode) }, modifier = Modifier.fillMaxWidth()) { Text("Save profile") }
            Text("Send feedback", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy)
            var categoryExpanded by remember { mutableStateOf(false) }
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
            OutlinedButton(enabled = !s.accountBusy, onClick = vm::signOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        }
    }
}

@Composable private fun FishCameraScreen(onClose: () -> Unit, onPhotoCaptured: (android.net.Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> PreviewView(ctx).also { previewView = it } }, modifier = Modifier.fillMaxSize())
        FishCameraOverlay()
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) { Text("Cancel", color = Color.White) }
        Text("Hold the fish vertically — head up", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp), fontWeight = FontWeight.Bold)
        FloatingActionButton(onClick = {
            val capture = imageCapture ?: return@FloatingActionButton
            val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "catchcheck-fish-${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CatchCheck") }
            val output = ImageCapture.OutputFileOptions.Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
            capture.takePicture(output, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) { result.savedUri?.let(onPhotoCaptured) }
                override fun onError(exception: ImageCaptureException) { }
            })
        }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp), containerColor = Orange) { Icon(Icons.Default.CameraAlt, "Take photo", tint = Color.White) }
    }
    DisposableEffect(previewView, lifecycleOwner) {
        val view = previewView
        if (view == null) return@DisposableEffect onDispose { }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            imageCapture = capture
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
        }, ContextCompat.getMainExecutor(context))
        onDispose { providerFuture.addListener({ providerFuture.get().unbindAll() }, ContextCompat.getMainExecutor(context)) }
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

@Composable private fun RecommendationCard(item: Recommendation, click: () -> Unit = {}) {
    Card(onClick = click, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Column { Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy); Text("${item.area} · ${if (item.boat) "Boat" else "Land"}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("${item.rating}/100", color = Orange, fontWeight = FontWeight.Bold)
            }
            Text(item.time, color = Navy, fontWeight = FontWeight.SemiBold)
            Text(item.distance, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            item.reasons.forEach { Text("• $it", color = Navy, style = MaterialTheme.typography.bodySmall) }
            if (item.warning != null) Text("Partial forecast · ${item.coveragePercent}% of score factors available", color = Orange, style = MaterialTheme.typography.bodySmall)
            item.warnings.firstOrNull { it.startsWith("Long-range") || it.startsWith("Strong gusts") || it.startsWith("Elevated waves") }?.let {
                Text(it, color = Orange, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
@Composable private fun Metric(label: String, value: String) { Column { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall); Text(value, color = Navy, fontWeight = FontWeight.SemiBold) } }

@Composable fun ResultsScreen(s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            requestCurrentLocation(context, { vm.completeLocationSearch(it) }, { vm.locationUnavailable() })
        else vm.locationUnavailable()
    }
    fun useCurrentLocation() {
        vm.startLocationSearch()
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) requestCurrentLocation(context, { vm.completeLocationSearch(it) }, { vm.locationUnavailable() })
        else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
        LazyColumn(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Best options", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy); OutlinedButton(onClick = { vm.closeResults() }) { Text("Back") } } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = !s.boat, onClick = { vm.setBoat(false) }, label = { Text("Land") }); FilterChip(selected = s.boat, onClick = { vm.setBoat(true) }, label = { Text("Boat") }) } }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(s.originName?.let { "From $it · straight-line radius" } ?: "Choose where to search from", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text("Search from a city", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        searchOrigins.forEach { origin -> FilterChip(selected = !s.hasDeviceLocation && s.originName == origin.name,
                            onClick = { vm.selectManualOrigin(origin) }, label = { Text(origin.name) }) }
                    }
                    OutlinedButton(onClick = { useCurrentLocation() }) { Icon(Icons.Default.NearMe, null); Spacer(Modifier.width(6.dp)); Text("Use current location") }
                    s.locationNotice?.let { Text(it, color = Orange, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { RecommendationFilters(s, vm) }
            when {
                s.locating -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Getting current location…", color = Navy) } }
                s.recommendationsLoading -> item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Checking hourly forecasts…", color = Navy) } }
                s.recommendationsError != null -> item { Column { Text(s.recommendationsError, color = Orange); TextButton(onClick = { vm.refreshRecommendations() }) { Text("Try again") } } }
                s.recommendationSearch?.nearbySpots == 0 -> item {
                    val nearest = s.recommendationSearch
                    Text("No known ${if (s.boat) "boat" else "land"} fishing areas are within ${s.radiusKm} km." +
                        (if (nearest?.nearestSpot != null) " The nearest is ${nearest.nearestSpot}, about ${nearest.nearestSpotDistanceKm} km away. Try a wider radius." else " Try a wider radius or another city."), color = Navy)
                }
                s.recommendationSearch?.items?.isEmpty() == true -> item {
                    Text(when {
                        s.recommendationSearch.failedSpots == s.recommendationSearch.nearbySpots -> "Forecasts could not be loaded for nearby areas. Try again."
                        s.dateLabel == "Today" -> "No safe 2–3 hour window remains today within your selected hours. Try Next 3 days, wider hours or Anytime."
                        else -> "No safe 2–3 hour windows fit these dates and hours. Try wider hours, Anytime or another date."
                    }, color = Navy)
                }
                else -> items(s.recommendationSearch?.items ?: emptyList()) { RecommendationCard(it) { vm.openSpot(it) } }
            }
            if ((s.recommendationSearch?.failedSpots ?: 0) > 0 && !s.recommendationsLoading) item { Text("Forecasts failed for ${s.recommendationSearch?.failedSpots} nearby spot(s); those spots have no score.", color = Orange, style = MaterialTheme.typography.bodySmall) }
            item { Text("Scores compare the best 2–3 hour window at each named area. Land scores include tide movement. Boat scores omit tide and give more weight to waves and wind. Severe conditions found in available forecasts are excluded. Check local access, marine warnings and fishing rules before leaving.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            item { Text("Weather and marine forecasts: Open-Meteo (open-meteo.com). Tide model accuracy is limited near shore; do not use it for navigation.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable fun SpotDetailScreen(spot: Recommendation, saved: Boolean, vm: FishingViewModel) {
    val context = LocalContext.current
    var addToCalendar by rememberSaveable(recommendationKey(spot)) { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedButton(onClick = { vm.closeSpot() }) { Text("Back") }
            Text(spot.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Navy)
            Text(spot.area, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${spot.rating}/100 · ${spot.time}", color = Orange, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(spot.distance, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Score breakdown", fontWeight = FontWeight.Bold, color = Navy)
                    spot.factors.forEach { factor ->
                        Text("${factor.name}: ${factor.score}/100 × ${factor.weight}%", color = Navy, fontWeight = FontWeight.SemiBold)
                        Text(factor.explanation, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    spot.warning?.let { Text(it, color = Orange, style = MaterialTheme.typography.bodySmall) }
                    Text("Available weights are normalized to 100. Scores with missing marine factors are capped at 79; forecasts more than seven days away are capped at 89.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (spot.warnings.isNotEmpty()) Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Check before you go", fontWeight = FontWeight.Bold, color = Navy)
                    spot.warnings.forEach { Text("• $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                }
            }
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
        else items(s.savedRecommendations) { RecommendationCard(it) { vm.openSpot(it) } }
    }
}
private data class FishingRulesArea(val id: String, val name: String, val slug: String) {
    val officialUrl: String get() = "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/$slug"
}
private val fishingRulesAreas = listOf(
    FishingRulesArea("auckland-kermadec", "Auckland / Kermadec", "auckland-kermadec-fishing-rules"),
    FishingRulesArea("central", "Central", "central-fishing-rules"),
    FishingRulesArea("challenger", "Challenger", "challenger-fishing-rules"),
    FishingRulesArea("south-east", "South-East", "south-east-fishing-rules"),
    FishingRulesArea("southland", "Southland", "southland-fishing-rules"),
    FishingRulesArea("kaikoura", "Kaikōura", "kaikoura-fishing-rules"),
    FishingRulesArea("chatham-rise", "Chatham Rise", "chatham-rise-area-recreational-fishing-rules"),
    FishingRulesArea("fiordland", "Fiordland", "fiordland-marine-area-fishing-rules")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RulesScreen(modifier: Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val repository = remember { RulesRepository() }
    var selectedAreaId by rememberSaveable { mutableStateOf<String?>(null) }
    var manuallyChosen by rememberSaveable { mutableStateOf(false) }
    var locationAttempted by rememberSaveable { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var showAreas by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by remember { mutableStateOf<FishingRulesPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var expandedSections by remember(selectedAreaId) { mutableStateOf(setOf<Int>()) }
    var expandedTables by remember(selectedAreaId) { mutableStateOf(setOf<Int>()) }
    val area = fishingRulesAreas.firstOrNull { it.id == selectedAreaId }

    fun locationUnavailable() {
        if (selectedAreaId == null) selectedAreaId = fishingRulesAreas.first().id
        locationMessage = "Location unavailable. Choose the area where you plan to fish."
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
            requestCurrentLocation(context, { point ->
                if (!manuallyChosen) selectedAreaId = rulesAreaForLocation(point)
                locationMessage = "Area estimated from your current location. Confirm the exact fishing spot."
            }, ::locationUnavailable)
        else locationUnavailable()
    }
    fun useCurrentLocation() {
        manuallyChosen = false
        locationAttempted = true
        locationMessage = "Finding your fishing area…"
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) requestCurrentLocation(context, { point ->
            if (!manuallyChosen) selectedAreaId = rulesAreaForLocation(point)
            locationMessage = "Area estimated from your current location. Confirm the exact fishing spot."
        }, ::locationUnavailable)
        else locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(Unit) { if (!locationAttempted) useCurrentLocation() }
    LaunchedEffect(selectedAreaId, reloadToken) {
        val id = selectedAreaId ?: return@LaunchedEffect
        loading = true
        page = null
        error = null
        try { page = repository.load(id) }
        catch (exception: Exception) { error = exception.message ?: "Saved rules are unavailable right now." }
        finally { loading = false }
    }

    if (showAreas) ModalBottomSheet(onDismissRequest = { showAreas = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Choose fishing area", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Match the area to where you will fish.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            fishingRulesAreas.forEach { option ->
                TextButton(onClick = {
                    selectedAreaId = option.id
                    manuallyChosen = true
                    locationMessage = null
                    showAreas = false
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(option.name, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    if (option.id == selectedAreaId) Icon(Icons.Default.CheckCircle, contentDescription = "Selected")
                }
            }
        }
    }

    val search = query.trim()
    val matchingSections = page?.sections?.withIndex()?.filter { search.isEmpty() ||
        it.value.heading.contains(search, ignoreCase = true) || it.value.text.contains(search, ignoreCase = true) }.orEmpty()
    val matchingTables = page?.tables?.withIndex()?.filter { search.isEmpty() ||
        it.value.any { row -> row.any { cell -> cell.contains(search, ignoreCase = true) } } }.orEmpty()
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Fishing rules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Sizes, limits and restrictions from Fisheries New Zealand", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fishing area", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { showAreas = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(area?.name ?: "Choose an area", modifier = Modifier.weight(1f))
                        Text("⌄")
                    }
                    TextButton(onClick = ::useCurrentLocation) { Icon(Icons.Default.NearMe, null); Spacer(Modifier.width(6.dp)); Text("Use my location") }
                    locationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Search species or rules") }, singleLine = true,
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
            item {
                Text("${matchingSections.size + matchingTables.size} ${if (search.isEmpty()) "rule topics" else "matching topics"}",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                rules.reviewedAt?.let { Text("MPI last reviewed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            matchingSections.forEach { (index, section) ->
                item(key = "rule-section-$index") {
                    RuleSectionCard(section.heading, section.text, search.isNotEmpty() || index in expandedSections) {
                        expandedSections = if (index in expandedSections) expandedSections - index else expandedSections + index
                    }
                }
            }
            if (matchingTables.isNotEmpty()) item { Text("Species and limits", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            matchingTables.forEach { (index, table) ->
                item(key = "rule-table-$index") {
                    val headerMatches = table.firstOrNull()?.any { it.contains(search, ignoreCase = true) } == true
                    val rows = table.drop(1).filter { search.isEmpty() || headerMatches || it.any { cell -> cell.contains(search, ignoreCase = true) } }
                    RuleTableCard(table.firstOrNull().orEmpty(), rows, search.isNotEmpty() || index in expandedTables) {
                        expandedTables = if (index in expandedTables) expandedTables - index else expandedTables + index
                    }
                }
            }
            if (search.isNotEmpty() && matchingSections.isEmpty() && matchingTables.isEmpty()) item {
                Text("No saved rules match “$search” in ${rules.areaName}. Try a species or another term.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { TextButton(onClick = { uriHandler.openUri(rules.sourceUrl) }) { Text("Open official MPI rules") } }
        }
        item { Text("Confirm the exact location and latest official rules each time you fish.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
@Composable private fun RuleSectionCard(title: String, body: String, expanded: Boolean, onToggle: () -> Unit) {
    Card(onClick = onToggle, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body.trim(), maxLines = if (expanded) Int.MAX_VALUE else 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (expanded) "Show less" else "Read section", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable private fun RuleTableCard(headers: List<String>, rows: List<List<String>>, expanded: Boolean, onToggle: () -> Unit) {
    if (rows.isEmpty()) return
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(headers.firstOrNull().orEmpty().ifBlank { "Rules table" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            (if (expanded) rows else rows.take(6)).forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(row.firstOrNull().orEmpty(), fontWeight = FontWeight.SemiBold)
                row.drop(1).forEachIndexed { index, value ->
                    if (value.isNotBlank() && value != "—") Text("${headers.getOrNull(index + 1).orEmpty()}: $value",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (rows.size > 6) TextButton(onClick = onToggle) { Text(if (expanded) "Show fewer" else "Show all ${rows.size} entries") }
        }
    }
}
@Composable fun TideScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
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
                    TextButton(onClick = ::useCurrentLocation) { Text("Use my location") }
                }
                Text(if (s.tideStationManual) "Selected: ${s.selectedStation.name}"
                    else if (s.tideDeviceLocation != null) "Nearest to your location: ${s.selectedStation.name}"
                    else "Using ${s.selectedStation.name} until your location is available", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                s.tideLocationNotice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                key(s.selectedStation.id) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(s.selectedStation) + tideStations.filter { it.id != s.selectedStation.id }).forEach { station ->
                            FilterChip(selected = station.id == s.selectedStation.id,
                                onClick = { vm.chooseStation(station) }, label = { Text(station.name) })
                        }
                    }
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
        fun useRecentCachedLocation() {
            client.lastLocation.addOnSuccessListener { cached ->
                if (cached != null && System.currentTimeMillis() - cached.time in 0L..300_000L)
                    onLocation(GeoPoint(cached.latitude, cached.longitude))
                else onUnavailable()
            }.addOnFailureListener { onUnavailable() }
        }
        val token = CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
            .addOnSuccessListener { location ->
                if (location != null) onLocation(GeoPoint(location.latitude, location.longitude))
                else useRecentCachedLocation()
            }.addOnFailureListener { useRecentCachedLocation() }
    } catch (_: SecurityException) { onUnavailable() }
}
