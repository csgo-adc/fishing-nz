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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

@Composable fun HomeScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.setFishPhoto(it) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.height(18.dp)); Text("Kia ora, Alex", color = Color.Gray); Text("Plan your next catch", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy) }
        item { Card(colors = CardDefaults.cardColors(Navy), shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(20.dp)) { Text("What are you fishing for?", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = !s.boat, onClick = { vm.setBoat(false) }, label = { Text("Land fishing") }); FilterChip(selected = s.boat, onClick = { vm.setBoat(true) }, label = { Text("Boat fishing") }) }; Text("Auckland demo · ${s.dateLabel}", color = Color.White.copy(.8f), modifier = Modifier.padding(top = 12.dp)) } } }
        item { Text("When are you going?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Today", "Tomorrow", "Friday", "Saturday", "Sunday", "Next 7 days").forEach { FilterChip(selected = s.dateLabel == it, onClick = { vm.setDate(it) }, label = { Text(it) }) } } }
        item { ActionCard("Find the best time", "See the best fishing window near you", { vm.showResults() }); Spacer(Modifier.height(2.dp)); ActionCard("Find the best location", "Rank spots by conditions and distance", { vm.showResults() }, true) }
        item { FishIdentifierCard(s, picker, vm) }
        item { Text("Quick forecast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        item { Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) { Metric("Wind", s.weather?.wind ?: "—"); Metric("Tide", s.tide?.nextEvent ?: "—"); Metric("Temp", s.weather?.temperature ?: "—") } } }
        item { Text("Your next best window", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }
        item { RecommendationCard(sampleRecommendations.first()) { vm.showResults() } }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable private fun ActionCard(title: String, subtitle: String, click: () -> Unit, outlined: Boolean = false) { Card(onClick = click, colors = CardDefaults.cardColors(if (outlined) Color.White else Seafoam), shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(if (outlined) Seafoam else Color.White, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.LocationOn, null, tint = Navy) }; Spacer(Modifier.width(14.dp)); Column { Text(title, color = Navy, fontWeight = FontWeight.Bold); Text(subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall) } } } }

@Composable private fun FishIdentifierCard(s: FishingUiState, picker: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>, vm: FishingViewModel) {
    val context = LocalContext.current
    var showCamera by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) showCamera = true }
    Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(44.dp).background(Seafoam, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Default.CameraAlt, null, tint = Navy) }; Spacer(Modifier.width(12.dp)); Column { Text("What fish is this?", style = MaterialTheme.typography.titleLarge, color = Navy, fontWeight = FontWeight.Bold); Text("AI ID + local rules check", color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }
        s.fishPhoto?.let { uri -> val bitmap = remember(uri) { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }; bitmap?.let { Image(it.asImageBitmap(), "Selected fish photo", Modifier.fillMaxWidth().height(160.dp), contentScale = ContentScale.Crop) } }
        s.fishCheck?.let { result -> Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(14.dp)) { Column(Modifier.padding(14.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Text(result.commonName, color = Navy, fontWeight = FontWeight.Bold); Text("${result.confidence}% match", color = Orange, fontWeight = FontWeight.Bold) }; Text(result.scientificName, color = Color.Gray); Text("${result.status} · min ${result.minimumSize} · ${result.dailyLimit}", color = Navy, fontWeight = FontWeight.SemiBold); Text(result.note, color = Color.DarkGray, style = MaterialTheme.typography.bodySmall) } } }
        if (s.fishChecking) Text("Checking the photo…", color = Orange, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCamera = true else cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CameraAlt, null); Spacer(Modifier.width(4.dp)); Text("Take photo") }; OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PhotoLibrary, null); Spacer(Modifier.width(4.dp)); Text(if (s.fishPhoto == null) "Choose photo" else "Gallery") } }
        Button(enabled = s.fishPhoto != null && !s.fishChecking, onClick = { vm.identifyFish() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(4.dp)); Text("Identify fish") }
        Text("AI suggestions are a guide. Confirm species, area and current MPI rules before keeping a fish.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    } }
    if (showCamera) Dialog(onDismissRequest = { showCamera = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FishCameraScreen(onClose = { showCamera = false }, onPhotoCaptured = { vm.setFishPhoto(it); showCamera = false })
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

@Composable private fun RecommendationCard(item: Recommendation, click: () -> Unit = {}) { Card(onClick = click, colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Column { Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy); Text(item.area, color = Color.Gray) }; Text("${item.rating}/100", color = Orange, fontWeight = FontWeight.Bold) }; Text(item.time, color = Navy, fontWeight = FontWeight.SemiBold); Text(item.distance, color = Color.Gray, style = MaterialTheme.typography.bodySmall); Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { item.reasons.forEach { Text(it, color = Navy, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(Seafoam, RoundedCornerShape(50)).padding(7.dp)) } } } } }
@Composable private fun Metric(label: String, value: String) { Column { Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall); Text(value, color = Navy, fontWeight = FontWeight.SemiBold) } }

@Composable fun ResultsScreen(s: FishingUiState, vm: FishingViewModel) { Surface(modifier = Modifier.fillMaxSize(), color = Cream) { LazyColumn(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Best options", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy); OutlinedButton(onClick = { vm.closeResults() }) { Text("Back") } } }; items(sampleRecommendations.filter { !s.boat || it.boat }) { RecommendationCard(it) { vm.openSpot(it) } }; item { Text("Safety and fishing rules always override the score.", color = Color.Gray) } } } }
@Composable fun SpotDetailScreen(spot: Recommendation, saved: Boolean, vm: FishingViewModel) { Surface(modifier = Modifier.fillMaxSize(), color = Cream) { Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) { OutlinedButton(onClick = { vm.closeSpot() }) { Text("Back") }; Text(spot.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Navy); Text(spot.area, color = Color.Gray); Text("${spot.rating}/100 · ${spot.time}", color = Orange, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text("Why this spot?", fontWeight = FontWeight.Bold, color = Navy); spot.reasons.forEach { Text("✓  $it", color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp)) } } }; OutlinedButton(onClick = { vm.toggleSaved(spot.name) }, modifier = Modifier.fillMaxWidth()) { Text(if (saved) "Remove saved spot" else "Save spot") }; Button(onClick = { vm.startTrip() }, modifier = Modifier.fillMaxWidth()) { Text("Start fishing trip") } } } }

@Composable fun TripsScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) { LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Text("Your trips", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy); Text("Saved spots and active plans", color = Color.Gray) }; s.activeTrip?.let { item { Card(colors = CardDefaults.cardColors(Seafoam)) { Column(Modifier.padding(16.dp)) { Text("Active trip", color = Navy, fontWeight = FontWeight.Bold); Text(it.name, style = MaterialTheme.typography.titleLarge, color = Navy); OutlinedButton({ vm.endTrip() }) { Text("End trip") } } } } }; item { Text("Saved spots", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Navy) }; items(sampleRecommendations.filter { it.name in s.savedSpots }) { RecommendationCard(it) { vm.openSpot(it) } } }
}
@Composable fun RulesScreen(modifier: Modifier) { LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { item { Text("Fishing rules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy); Text("Auckland / Kermadec example", color = Color.Gray) }; item { RuleCard("Before you go", "Rules vary by fishing area. Check the latest official MPI rules before every trip.") }; item { RuleCard("Catch limits", "Daily limits and minimum sizes depend on species and region. Keep only legal-sized catch.") }; item { RuleCard("Closed areas", "Marine reserves, mātaitai and taiāpure may have additional restrictions or complete closures.") } } }
@Composable private fun RuleCard(title: String, body: String) { Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text(title, color = Navy, fontWeight = FontWeight.Bold); Text(body, color = Color.DarkGray, modifier = Modifier.padding(top = 6.dp)) } } }
private data class MapSpot(val name: String, val latitude: Double, val longitude: Double, val detail: String, val boat: Boolean)
private val mapSpots = listOf(
    MapSpot("Whangārei Harbour", -35.72, 174.32, "Good · Land fishing", false), MapSpot("Mission Bay", -36.8485, 174.7633, "86/100 · Best 6:10–8:40 AM", false),
    MapSpot("Coromandel Harbour", -37.0, 175.35, "Good · Land fishing", false), MapSpot("Gisborne Harbour", -38.02, 177.29, "Good · Land fishing", false),
    MapSpot("Wellington Harbour", -41.28, 174.78, "Good · Boat fishing", true), MapSpot("Nelson Harbour", -41.27, 173.28, "Good · Boat fishing", true),
    MapSpot("Lyttelton Harbour", -43.53, 172.64, "Good · Boat fishing", true), MapSpot("Otago Harbour", -45.88, 170.51, "Good · Boat fishing", true),
    MapSpot("Bluff Harbour", -46.41, 168.35, "Good · Boat fishing", true)
)

@Composable fun MapScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("All") }
    var mapLoaded by remember { mutableStateOf(false) }
    var mapTimedOut by remember { mutableStateOf(false) }
    var requestedPermission by rememberSaveable { mutableStateOf(false) }
    val visibleSpots = mapSpots.filter { filter == "All" || (filter == "Boat" && it.boat) || (filter == "Land" && !it.boat) }
    val cameraState = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(-41.2, 174.8), 5.1f) }
    fun locate() { requestCurrentLocation(context) { point -> vm.updateLocation(point); cameraState.move(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 13f)) } }
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
                visibleSpots.forEach { spot -> Marker(state = MarkerState(LatLng(spot.latitude, spot.longitude)), title = spot.name, snippet = spot.detail) }
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
    val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM", java.util.Locale.US)
    LazyColumn(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Tide forecast", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy); Text("Live marine forecast · ${s.selectedStation.name}", color = Color.Gray) }
        item { Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Choose harbour", color = Navy, fontWeight = FontWeight.Bold); Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { tideStations.take(6).forEach { station -> FilterChip(selected = station.id == s.selectedStation.id, onClick = { vm.chooseStation(station) }, label = { Text(station.name.substringBefore(" Harbour")) }) } } } } }
        item { Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text(s.tideDate.format(formatter), color = Navy, fontWeight = FontWeight.Bold); Row { TextButton(enabled = s.tideDate > java.time.LocalDate.now(), onClick = { vm.changeTideDate(s.tideDate.minusDays(1)) }) { Text("‹") }; TextButton(enabled = s.tideDate < java.time.LocalDate.now().plusDays(7), onClick = { vm.changeTideDate(s.tideDate.plusDays(1)) }) { Text("›") } } } }
        s.stationTide?.let { tide ->
            item { Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(18.dp)) { Text("Current level", color = Color.Gray); Text(tide.currentLevel, style = MaterialTheme.typography.headlineMedium, color = Navy, fontWeight = FontWeight.Bold); Text("Next ${tide.nextEvent} · ${tide.eventTime}", color = Color.DarkGray) } } }
            item { Card(colors = CardDefaults.cardColors(Color.White), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text("Tide curve", color = Navy, fontWeight = FontWeight.Bold); TideCurve(tide.points) } } }
            item { tide.events.forEach { event -> Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), Arrangement.SpaceBetween) { Text(event.type, color = Navy, fontWeight = FontWeight.SemiBold); Text("${event.time} · ${event.height}", color = Color.DarkGray) } } }
        } ?: item { Text("Loading live tide data…", color = Navy) }
        item { Text("Source: Open-Meteo marine model. Confirm official LINZ predictions for safety-critical decisions.", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun TideCurve(points: List<TidePoint>) {
    if (points.size < 2) return
    val min = points.minOf { it.level }; val max = points.maxOf { it.level }; val range = (max - min).coerceAtLeast(0.1)
    Canvas(Modifier.fillMaxWidth().height(150.dp).padding(vertical = 10.dp)) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = size.width * index / (points.lastIndex.toFloat())
            val y = size.height * (1f - ((point.level - min) / range).toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Navy, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
    }
}

private fun requestCurrentLocation(context: android.content.Context, onLocation: (GeoPoint) -> Unit) {
    try {
        val token = CancellationTokenSource()
        LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token).addOnSuccessListener { location -> location?.let { onLocation(GeoPoint(it.latitude, it.longitude)) } }
    } catch (_: SecurityException) { }
}
