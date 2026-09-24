package nz.fishingnz.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import nz.fishingnz.app.model.*
import nz.fishingnz.app.viewmodel.FishingUiState
import nz.fishingnz.app.viewmodel.FishingViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val preferredTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

@Composable fun HomeScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.setFishPhoto(it) }
    val context = LocalContext.current
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
        item { Spacer(Modifier.height(18.dp)); Text("Kia ora, Alex", color = Color.Gray); Text("Plan your next catch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy) }
        item { Card(colors = CardDefaults.cardColors(Navy), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(20.dp)) { Text("What are you fishing for?", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = !s.boat, onClick = { vm.setBoat(false) }, label = { Text("Land fishing") }); FilterChip(selected = s.boat, onClick = { vm.setBoat(true) }, label = { Text("Boat fishing") }) }; Text(s.originName?.let { "Searching from $it" } ?: "Search with device location or choose a city", color = Color.White.copy(.8f), modifier = Modifier.padding(top = 12.dp)) } } }
        item { RecommendationFilters(s, vm) }
        item { ActionCard("Find the best time", "See the best forecast window near you", { search() }); Spacer(Modifier.height(2.dp)); ActionCard("Find the best location", "Rank nearby spots by forecast and distance", { search() }, true) }
        item { FishIdentifierCard(s, picker, vm) }
        item { Text("Quick forecast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        item { Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
            if (s.originName == null) Text("Use current location or choose a city to see local conditions.", color = Color.Gray, modifier = Modifier.padding(18.dp))
            else Column(Modifier.padding(18.dp)) {
                Text("From ${s.originName}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Metric("Wind", s.weather?.wind ?: "—"); Metric("Tide", s.tide?.nextEvent ?: "—"); Metric("Temp", s.weather?.temperature ?: "—") }
            }
        } }
        item { Text("Your next best window", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        item { s.recommendationSearch?.items?.firstOrNull()?.let { RecommendationCard(it) { vm.showResults() } } ?: Text("Choose a date and search to see forecast-based scores.", color = Color.Gray) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable private fun RecommendationFilters(s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("When are you going?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Today", "In 3 days", "Next 3 days", "This weekend").forEach { choice ->
                FilterChip(selected = s.dateLabel == choice, onClick = { vm.setDate(choice) }, label = { Text(choice) })
            }
            FilterChip(selected = s.dateLabel == "Custom", onClick = {
                showCustomDateRange(context, s.dateStart, s.dateEnd) { start, end -> vm.setCustomDates(start, end) }
            }, label = { Text("Choose dates") })
        }
        Text("${s.dateStart} to ${s.dateEnd}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Text("What time suits you?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.preferredTimeIsSuggested, onClick = { vm.setSuggestedHours() }, label = { Text("7:00 AM–9:00 PM") })
            FilterChip(selected = s.preferredTime == null, onClick = { vm.clearPreferredHours() }, label = { Text("Anytime") })
            FilterChip(selected = s.preferredTime != null && !s.preferredTimeIsSuggested, onClick = {
                showCustomTimeRange(context, s.preferredTime) { start, end -> vm.setPreferredHours(start, end) }
            }, label = { Text(if (s.preferredTime != null && !s.preferredTimeIsSuggested) "Change times" else "Choose times") })
        }
        if (s.preferredTime != null && !s.preferredTimeIsSuggested) {
            Text("${s.preferredTime.start.format(preferredTimeFormatter)}–${s.preferredTime.end.format(preferredTimeFormatter)}" +
                if (s.preferredTime.end.isBefore(s.preferredTime.start)) " (ends next day)" else "",
                color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        Text("Only full 2–3 hour windows within these hours are shown. Each selected day is a window's start day.",
            color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Text("Search radius", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 30, 50, 100, 200, 300, 400, 500).forEach { radius ->
                FilterChip(selected = s.radiusKm == radius, onClick = { vm.setRadius(radius) }, label = { Text("$radius km") })
            }
        }
    }
}

private fun showCustomTimeRange(context: android.content.Context, selected: PreferredTimeRange?, done: (LocalTime, LocalTime) -> Unit) {
    val initialStart = selected?.start ?: LocalTime.of(7, 0)
    val initialEnd = selected?.end ?: LocalTime.of(21, 0)
    android.app.TimePickerDialog(context, { _, startHour, startMinute ->
        android.app.TimePickerDialog(context, { _, endHour, endMinute ->
            val start = LocalTime.of(startHour, startMinute)
            val end = LocalTime.of(endHour, endMinute)
            if (start == end) android.widget.Toast.makeText(context, "Choose different start and end times", android.widget.Toast.LENGTH_SHORT).show()
            else done(start, end)
        }, initialEnd.hour, initialEnd.minute, false).apply { setTitle("Preferred end time") }.show()
    }, initialStart.hour, initialStart.minute, false).apply { setTitle("Preferred start time") }.show()
}

private fun showCustomDateRange(context: android.content.Context, selectedStart: java.time.LocalDate, selectedEnd: java.time.LocalDate, done: (java.time.LocalDate, java.time.LocalDate) -> Unit) {
    val zone = java.time.ZoneId.of("Pacific/Auckland")
    val today = java.time.LocalDate.now(zone)
    val maxDate = today.plusDays(15)
    val start = selectedStart.coerceIn(today, maxDate)
    val startPicker = android.app.DatePickerDialog(context, { _, year, month, day ->
        val selected = java.time.LocalDate.of(year, month + 1, day)
        val end = selectedEnd.coerceIn(selected, maxDate)
        android.app.DatePickerDialog(context, { _, endYear, endMonth, endDay ->
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

@Composable private fun ActionCard(title: String, subtitle: String, click: () -> Unit, outlined: Boolean = false) { Card(onClick = click, colors = CardDefaults.cardColors(if (outlined) Color.White else Seafoam), shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(if (outlined) Seafoam else Color.White, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.LocationOn, null, tint = Navy) }; Spacer(Modifier.width(14.dp)); Column { Text(title, color = Navy, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall) } } } }

@Composable private fun FishIdentifierCard(s: FishingUiState, picker: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>, vm: FishingViewModel) {
    val context = LocalContext.current
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
    Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(Seafoam, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.CameraAlt, null, tint = Navy) }; Spacer(Modifier.width(12.dp)); Column { Text("What fish is this?", style = MaterialTheme.typography.titleLarge, color = Navy, fontWeight = FontWeight.Bold); Text("AI ID + local rules check", color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }
        s.fishPhoto?.let { uri -> val bitmap = remember(uri) { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }; bitmap?.let { Image(it.asImageBitmap(), "Selected fish photo", Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop) } }
        s.fishCheck?.let { result -> Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(result.commonName, color = Navy, fontWeight = FontWeight.Bold); Text("${result.confidence}% match", color = Orange, fontWeight = FontWeight.Bold) }
            Text(result.scientificName, color = Color.Gray)
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("MPI rules · ${result.areaName}", color = Navy, fontWeight = FontWeight.SemiBold); result.rulesReviewedAt?.let { Text("Reviewed $it", color = Color.Gray, style = MaterialTheme.typography.labelSmall) } }
            if (result.fishRules.isEmpty()) Text("No species-specific size or catch-limit entry was found in the saved rules for this area. Check local closures and restrictions before keeping this fish.", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp))
            result.fishRules.forEach { rule ->
                Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(rule.species, color = Navy, fontWeight = FontWeight.Bold)
                    rule.minimumSize?.let { Text("Minimum size: $it", color = Navy) }
                    rule.dailyLimit?.let { Text("Daily limit: $it", color = Navy) }
                    rule.details.forEach { Text("${it.label}: ${it.value}", color = Navy) }
                }
            }
            Text(if (result.areaIsEstimated) "Fishing area is estimated because device location was unavailable. Confirm where you are fishing." else "Area selected from current device location. Confirm the exact fishing location.", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
            Text("Check local closures and current MPI rules before keeping a fish.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } } }
        if (s.fishChecking) Text("Checking the photo…", color = Orange, fontWeight = FontWeight.SemiBold)
        s.fishError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera = true else cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(4.dp)); Text("Take photo") }; OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(4.dp)); Text(if (s.fishPhoto == null) "Choose photo" else "Gallery") } }
        Button(enabled = s.fishPhoto != null && !s.fishChecking, onClick = {
            if (s.account?.fishIdentity != true) vm.selectTab(5)
            else s.fishPhoto?.let { uri -> context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { bitmap ->
                        val output = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, output)
                        identifyAtCurrentLocation(output.toByteArray())
                    }
                } }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(4.dp)); Text(if (s.account?.fishIdentity == true) "Identify fish" else "Sign in for fish ID") }
        if (s.account?.fishIdentity != true) Text(if (s.account == null) "Create an account or sign in, then choose the paid plan for fish identification." else "Fish identification is included with the paid plan.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Text("AI suggestions are a guide. Confirm species, area and current MPI rules before keeping a fish.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
    var createAccount by rememberSaveable { mutableStateOf(true) }
    var category by rememberSaveable { mutableStateOf("general") }
    var message by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableIntStateOf(5) }
    LaunchedEffect(s.account) {
        s.account?.let { displayName = it.user.displayName; countryCode = it.user.countryCode; email = it.user.email }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
        s.accountNotice?.let { Text(it, color = Navy) }
        s.accountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (s.account == null) {
            if (s.verificationPending) Card(colors = CardDefaults.cardColors(Seafoam)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Check your inbox", color = Navy, fontWeight = FontWeight.Bold); Text("Open the confirmation email before signing in. The link expires after 24 hours.", color = Navy); OutlinedButton(enabled = !s.accountBusy, onClick = { vm.resendVerification(email) }) { Text("Resend confirmation email") } } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = createAccount, onClick = { createAccount = true }, label = { Text("Create account") })
                FilterChip(selected = !createAccount, onClick = { createAccount = false }, label = { Text("Sign in") })
            }
            if (createAccount) OutlinedTextField(displayName, { displayName = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, supportingText = { Text(if (createAccount) "At least 10 characters" else "Enter your password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(enabled = !s.accountBusy && email.isNotBlank() && password.isNotBlank(), onClick = { vm.signIn(email, password, displayName, createAccount); password = "" }, modifier = Modifier.fillMaxWidth()) { Text(if (s.accountBusy) "Please wait…" else if (createAccount) "Create account" else "Sign in") }
            if (!createAccount || s.verificationPending || s.accountError != null) TextButton(enabled = !s.accountBusy && email.isNotBlank(), onClick = { vm.resendVerification(email) }) { Text("Resend confirmation email") }
        } else {
            Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${s.account.user.plan.replaceFirstChar { it.uppercase() }} plan", color = Navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (s.account.fishIdentity) "Fish identification is included." else "Basic tools are available. Paid access is currently enabled by the CatchCheck team.", color = Navy)
            } }
            Text("Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy)
            Text(s.account.user.email, color = Color.Gray)
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
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Rating", color = Navy, modifier = Modifier.weight(1f)); (1..5).forEach { value -> FilterChip(selected = rating == value, onClick = { rating = value }, label = { Text(value.toString()) }, modifier = Modifier.padding(end = 3.dp)) } }
            Button(enabled = !s.accountBusy && message.trim().length >= 3, onClick = { vm.sendFeedback(category, message, rating); message = "" }, modifier = Modifier.fillMaxWidth()) { Text(if (s.accountBusy) "Please wait…" else "Send feedback") }
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
    Surface(Modifier.fillMaxSize(), color = Navy) {
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
    Canvas(Modifier.fillMaxWidth().height(210.dp)) {
        drawRoundRect(Color.White.copy(alpha = .18f), style = Stroke(width = 2.dp.toPx()))
        val body = Path().apply {
            moveTo(size.width * .18f, size.height * .5f)
            cubicTo(size.width * .3f, size.height * .18f, size.width * .7f, size.height * .18f, size.width * .84f, size.height * .5f)
            cubicTo(size.width * .7f, size.height * .82f, size.width * .3f, size.height * .82f, size.width * .18f, size.height * .5f)
            close()
        }
        drawPath(body, Seafoam)
        val tail = Path().apply { moveTo(size.width * .18f, size.height * .5f); lineTo(size.width * .06f, size.height * .28f); lineTo(size.width * .06f, size.height * .72f); close() }
        drawPath(tail, Seafoam)
        drawCircle(Navy, size.minDimension * .018f, androidx.compose.ui.geometry.Offset(size.width * .77f, size.height * .42f))
        drawLine(Navy, androidx.compose.ui.geometry.Offset(size.width * .25f, size.height * .5f), androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .5f), strokeWidth = 2.dp.toPx())
        drawLine(Color.White.copy(alpha = .7f), androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .1f), androidx.compose.ui.geometry.Offset(size.width * .92f, size.height * .1f), strokeWidth = 2.dp.toPx())
        drawLine(Color.White.copy(alpha = .7f), androidx.compose.ui.geometry.Offset(size.width * .08f, size.height * .9f), androidx.compose.ui.geometry.Offset(size.width * .92f, size.height * .9f), strokeWidth = 2.dp.toPx())
    }
}

@Composable private fun RecommendationCard(item: Recommendation, click: () -> Unit = {}) {
    Card(onClick = click, colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Column { Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy); Text("${item.area} · ${if (item.boat) "Boat" else "Land"}", color = Color.Gray) }
                Text("${item.rating}/100", color = Orange, fontWeight = FontWeight.Bold)
            }
            Text(item.time, color = Navy, fontWeight = FontWeight.SemiBold)
            Text(item.distance, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            item.reasons.forEach { Text("• $it", color = Navy, style = MaterialTheme.typography.bodySmall) }
            if (item.warning != null) Text("Partial forecast · ${item.coveragePercent}% of score factors available", color = Orange, style = MaterialTheme.typography.bodySmall)
            item.warnings.firstOrNull { it.startsWith("Long-range") || it.startsWith("Strong gusts") || it.startsWith("Elevated waves") }?.let {
                Text(it, color = Orange, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
@Composable private fun Metric(label: String, value: String) { Column { Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall); Text(value, color = Navy, fontWeight = FontWeight.SemiBold) } }

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
                    Text(s.originName?.let { "From $it · straight-line radius" } ?: "Choose where to search from", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
            item { Text("Scores compare the best 2–3 hour window at each named area. Land scores include tide movement. Boat scores omit tide and give more weight to waves and wind. Severe conditions found in available forecasts are excluded. Check local access, marine warnings and fishing rules before leaving.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            item { Text("Weather and marine forecasts: Open-Meteo (open-meteo.com). Tide model accuracy is limited near shore; do not use it for navigation.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable fun SpotDetailScreen(spot: Recommendation, saved: Boolean, vm: FishingViewModel) {
    Surface(modifier = Modifier.fillMaxSize(), color = Cream) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedButton(onClick = { vm.closeSpot() }) { Text("Back") }
            Text(spot.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Navy)
            Text(spot.area, color = Color.Gray)
            Text("${spot.rating}/100 · ${spot.time}", color = Orange, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(spot.distance, color = Color.Gray)
            Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Score breakdown", fontWeight = FontWeight.Bold, color = Navy)
                    spot.factors.forEach { factor ->
                        Text("${factor.name}: ${factor.score}/100 × ${factor.weight}%", color = Navy, fontWeight = FontWeight.SemiBold)
                        Text(factor.explanation, color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
                    }
                    spot.warning?.let { Text(it, color = Orange, style = MaterialTheme.typography.bodySmall) }
                    Text("Available weights are normalized to 100. Scores with missing marine factors are capped at 79; forecasts more than seven days away are capped at 89.", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (spot.warnings.isNotEmpty()) Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Check before you go", fontWeight = FontWeight.Bold, color = Navy)
                    spot.warnings.forEach { Text("• $it", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall) }
                }
            }
            OutlinedButton(onClick = { vm.toggleSaved(spot) }, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "Remove saved spot" else "Save spot") }
            Button(onClick = { vm.startTrip() }, modifier = Modifier.fillMaxWidth()) { Text("Start fishing trip") }
        }
    }
}

@Composable fun TripsScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) { LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Text("Your trips", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy); Text("Saved spots and active plans", color = Color.Gray) }; s.activeTrip?.let { item { Card(colors = CardDefaults.cardColors(Seafoam)) { Column(Modifier.padding(16.dp)) { Text("Active trip", color = Navy, fontWeight = FontWeight.Bold); Text(it.name, style = MaterialTheme.typography.titleLarge, color = Navy); OutlinedButton({ vm.endTrip() }) { Text("End trip") } } } } }; item { Text("Saved spots", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }; items(s.savedRecommendations) { RecommendationCard(it) { vm.openSpot(it) } } }
}
private data class FishingRulesArea(val name: String, val url: String)
private val fishingRulesAreas = listOf(
    FishingRulesArea("Auckland / Kermadec", "https://fishnz.space/?area=auckland-kermadec"),
    FishingRulesArea("Central", "https://fishnz.space/?area=central"),
    FishingRulesArea("Challenger", "https://fishnz.space/?area=challenger"),
    FishingRulesArea("South-East", "https://fishnz.space/?area=south-east"),
    FishingRulesArea("Southland", "https://fishnz.space/?area=southland"),
    FishingRulesArea("Kaikōura", "https://fishnz.space/?area=kaikoura"),
    FishingRulesArea("Chatham Rise", "https://fishnz.space/?area=chatham-rise"),
    FishingRulesArea("Fiordland", "https://fishnz.space/?area=fiordland")
)

@Composable fun RulesScreen(modifier: Modifier) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val area = fishingRulesAreas[selected]
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Fishing rules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
            Text("Choose an area to view rules saved from Fisheries New Zealand (MPI).", color = Color.Gray)
            Text("Rules include legal sizes, catch limits, closures and gear restrictions. Check the exact location before fishing.", color = Color.DarkGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { fishingRulesAreas.forEachIndexed { index, item -> FilterChip(selected == index, onClick = { selected = index }, label = { Text(item.name) }) } } }
        item {
            Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(680.dp),
                    factory = { context -> WebView(context).apply { settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false; webViewClient = WebViewClient(); tag = area.url; loadUrl(area.url) } },
                    update = { webView -> if (webView.tag != area.url) { webView.tag = area.url; webView.loadUrl(area.url) } }
                )
            }
        }
        item { Text("Official source: mpi.govt.nz · MPI says to check the rules each time you fish.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
@Composable private fun RuleCard(title: String, body: String) { Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text(title, color = Navy, fontWeight = FontWeight.Bold); Text(body, color = Color.DarkGray, modifier = Modifier.padding(top = 6.dp)) } } }
@Composable fun MapScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("All") }
    var mapLoaded by remember { mutableStateOf(false) }
    var mapTimedOut by remember { mutableStateOf(false) }
    var requestedPermission by rememberSaveable { mutableStateOf(false) }
    val visibleSpots = fishingSpots.filter { filter == "All" || (filter == "Boat" && it.boat) || (filter == "Land" && !it.boat) }
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(-41.2, 174.8), 5.1f) }
    fun locate() { requestCurrentLocation(context, onLocation = { point -> vm.updateLocation(point); cameraState.move(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 13f)) }) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locate()
    }
    LaunchedEffect(Unit) {
        if (!requestedPermission) {
            requestedPermission = true
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (fine || coarse) locate() else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    LaunchedEffect(s.location, s.hasDeviceLocation) { if (s.hasDeviceLocation) cameraState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(s.location.latitude, s.location.longitude), 13f)) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(8_000)
        if (!mapLoaded) mapTimedOut = true
    }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("Fishing map", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
            Text("New Zealand spots · tap a marker to explore", color = Color.Gray)
            Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Land", "Boat").forEach { option -> FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(if (option == "All") "All spots" else "$option fishing") }) }
            }
        }
        Box(Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(), cameraPositionState = cameraState,
                properties = MapProperties(isBuildingEnabled = true), uiSettings = MapUiSettings(zoomControlsEnabled = true), onMapLoaded = { mapLoaded = true }
            ) {
                if (s.hasDeviceLocation) Marker(state = MarkerState(LatLng(s.location.latitude, s.location.longitude)), title = "Your location")
                visibleSpots.forEach { spot -> Marker(state = MarkerState(LatLng(spot.latitude, spot.longitude)), title = spot.name, snippet = "${spot.area} · ${if (spot.boat) "Boat" else "Land"} area") }
            }
            IconButton(onClick = { locate() }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 112.dp).background(Color.White, CircleShape)) {
                Icon(Icons.Default.NearMe, contentDescription = "Jump to my location", tint = Navy)
            }
            if (!mapLoaded) Card(Modifier.align(Alignment.Center).padding(18.dp), colors = CardDefaults.cardColors(Color.White)) {
                Text(if (mapTimedOut) "Map tiles unavailable. Check the Google Maps Android API key and billing." else "Loading map…", Modifier.padding(16.dp), color = Navy)
            }
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
            Text("${s.selectedStation.name} · official LINZ tide times", color = Color.Gray)
        }
        item { Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Tide station", color = Navy, fontWeight = FontWeight.Bold)
                    TextButton(onClick = ::useCurrentLocation) { Text("Use my location") }
                }
                Text(if (s.tideStationManual) "Selected: ${s.selectedStation.name}"
                    else if (s.tideDeviceLocation != null) "Nearest to your location: ${s.selectedStation.name}"
                    else "Using ${s.selectedStation.name} until your location is available", color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall)
                s.tideLocationNotice?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
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
        item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text(s.tideDate.format(formatter), color = Navy, fontWeight = FontWeight.Bold)
            Row {
                TextButton(enabled = s.tideDate > today, onClick = { vm.changeTideDate(s.tideDate.minusDays(1)) }) { Text("‹") }
                TextButton(enabled = s.tideDate < java.time.LocalDate.of(2029, 12, 31), onClick = { vm.changeTideDate(s.tideDate.plusDays(1)) }) { Text("›") }
            }
        } }
        s.stationTide?.let { tide ->
            item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text((if (s.tideDate == today) "Estimated now" else "Estimated at 12:00 PM") +
                        " · ${tide.currentLevel} above Chart Datum", color = Navy, fontWeight = FontWeight.Bold)
                    Text("Next ${tide.nextEvent} · ${tide.eventTime}", color = Color.DarkGray)
                }
            } }
            item { Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tide curve", color = Navy, fontWeight = FontWeight.Bold)
                    Text("Slide left or right to inspect the time and height", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    TideCurve(tide.points, s.tideDate)
                    if (tide.points.firstOrNull()?.minuteOfDay != 0 || tide.points.lastOrNull()?.minuteOfDay != 1440)
                        Text("Curve is limited where an adjacent day's table is unavailable.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    Text("Curve heights between LINZ high and low tides are estimates.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            } }
            item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Official high and low tides", color = Navy, fontWeight = FontWeight.Bold)
                tide.events.forEach { event -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), Arrangement.SpaceBetween) {
                    Text(event.type, color = Navy, fontWeight = FontWeight.SemiBold)
                    Text("${event.time} · ${event.height}", color = Color.DarkGray)
                } }
            } }
        } ?: item {
            if (s.tideLoading) CircularProgressIndicator()
            else Text(s.tideError ?: "LINZ tide data unavailable for this station and date.", color = Navy)
        }
        item { Text("High and low tide predictions: Toitū Te Whenua Land Information New Zealand (LINZ). Times are New Zealand local time; heights are above the station's Chart Datum. Check the official table before planning around water depth.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun TideCurve(points: List<TidePoint>, date: java.time.LocalDate) {
    if (points.size < 2) return
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
    Text("$selectedTime  ·  ${"%.2f".format(Locale.US, selectedHeight)} m", color = Navy,
        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
            drawLine(Seafoam, Offset(0f, guideY), Offset(size.width, guideY), 1.dp.toPx())
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
        drawPath(area, Brush.verticalGradient(listOf(Seafoam, Color.White), startY = top, endY = bottom))
        drawPath(path, Navy, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        val selectedX = size.width * minute / 1440f
        drawLine(Orange, Offset(selectedX, top), Offset(selectedX, bottom), 1.5.dp.toPx())
        drawCircle(Orange, 6.dp.toPx(), Offset(selectedX, y(selectedHeight)))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM").forEach { label ->
            Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun requestCurrentLocation(context: android.content.Context, onLocation: (GeoPoint) -> Unit, onUnavailable: () -> Unit = {}) {
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
