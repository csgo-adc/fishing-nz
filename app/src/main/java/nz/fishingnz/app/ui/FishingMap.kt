package nz.fishingnz.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import nz.fishingnz.app.model.FishingSpot
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.SearchOrigin
import nz.fishingnz.app.model.fishingSpots
import nz.fishingnz.app.viewmodel.FishingUiState
import nz.fishingnz.app.viewmodel.FishingViewModel
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.floor

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var filter by remember { mutableStateOf("All") }
    var selectedSpot by remember { mutableStateOf<FishingSpot?>(null) }
    var showList by remember { mutableStateOf(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf(false) }
    var locationNotice by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableDoubleStateOf(5.0) }
    BackHandler(enabled = showList || selectedSpot != null) {
        if (showList) showList = false else selectedSpot = null
    }
    val nativeMap = remember { mutableStateOf<MapLibreMap?>(null) }
    val visibleSpots = remember(filter) { fishingSpots.filter { filter == "All" || (filter == "Boat" && it.boat) || (filter == "Land" && !it.boat) } }

    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            addOnDidFinishLoadingMapListener { mapLoaded = true; mapError = false }
            addOnDidFailLoadingMapListener { mapError = true }
            getMapAsync { map ->
                map.uiSettings.isCompassEnabled = false
                map.uiSettings.isAttributionEnabled = true
                map.addOnCameraIdleListener { zoom = map.cameraPosition.zoom }
                map.cameraPosition = CameraPosition.Builder()
                    .target(if (s.hasDeviceLocation) LatLng(s.location.latitude, s.location.longitude) else LatLng(-41.0, 173.5))
                    .zoom(if (s.hasDeviceLocation) 11.0 else 4.7)
                    .build()
                map.setStyle(MAP_STYLE) { nativeMap.value = map }
            }
        }
    }

    DisposableEffect(mapView, lifecycle) {
        var started = false
        var resumed = false
        var destroyed = false
        fun start() { if (!started) { mapView.onStart(); started = true } }
        fun resume() { start(); if (!resumed) { mapView.onResume(); resumed = true } }
        fun pause() { if (resumed) { mapView.onPause(); resumed = false } }
        fun stop() { pause(); if (started) { mapView.onStop(); started = false } }
        fun destroy() { if (!destroyed) { stop(); mapView.onDestroy(); destroyed = true } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                Lifecycle.Event.ON_DESTROY -> destroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); destroy() }
    }

    LaunchedEffect(mapLoaded, mapError) {
        if (!mapLoaded && !mapError) {
            delay(15_000)
            if (!mapLoaded) mapError = true
        }
    }

    LaunchedEffect(nativeMap.value, visibleSpots, zoom, s.hasDeviceLocation, s.location) {
        val map = nativeMap.value ?: return@LaunchedEffect
        map.clear()
        val icons = IconFactory.getInstance(context)
        val landIcon = icons.fromBitmap(mapPin(context, 0xFFF47C59.toInt()))
        val boatIcon = icons.fromBitmap(mapPin(context, 0xFF315DE0.toInt()))
        val markerGroups = mutableMapOf<Long, List<FishingSpot>>()
        val cellSize = when { zoom < 7.0 -> 1.4; zoom < 9.0 -> 0.24; else -> 0.0 }
        val groups = if (cellSize > 0) visibleSpots.groupBy {
            floor(it.latitude / cellSize).toInt() to floor(it.longitude / cellSize).toInt()
        }.values.toList() else visibleSpots.map { listOf(it) }
        groups.forEach { group ->
            val first = group.first()
            val position = LatLng(group.map { it.latitude }.average(), group.map { it.longitude }.average())
            val marker = map.addMarker(MarkerOptions()
                .position(position)
                .title(if (group.size == 1) first.name else "${group.size} fishing spots")
                .icon(if (group.size > 1) icons.fromBitmap(clusterPin(context, group.size))
                    else if (first.boat) boatIcon else landIcon))
            markerGroups[marker.id] = group
        }
        if (s.hasDeviceLocation) map.addMarker(MarkerOptions()
            .position(LatLng(s.location.latitude, s.location.longitude))
            .title("Your location")
            .icon(icons.fromBitmap(mapPin(context, 0xFF2B8ACB.toInt()))))
        map.setOnMarkerClickListener { marker ->
            val group = markerGroups[marker.id]
            if (group != null && group.size > 1) {
                selectedSpot = null
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, (map.cameraPosition.zoom + 2.5).coerceAtMost(12.0)))
            } else selectedSpot = group?.firstOrNull()
            true
        }
    }

    LaunchedEffect(nativeMap.value, s.location, s.hasDeviceLocation) {
        if (s.hasDeviceLocation) nativeMap.value?.animateCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(s.location.latitude, s.location.longitude), 11.0))
    }

    fun locate() {
        locationNotice = null
        requestCurrentLocation(context,
            onLocation = { point -> vm.updateLocation(point); nativeMap.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 12.0)) },
            onUnavailable = { locationNotice = "Current location is unavailable. Try again or choose a spot from the list." })
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locate()
        else locationNotice = "Location permission is off. You can still explore fishing spots on the map."
    }
    fun requestLocation() {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) locate()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fishing map", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy)
                    Text("Tap a spot or cluster to explore", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showList = true }) { Icon(Icons.Default.List, null); Spacer(Modifier.width(4.dp)); Text("List") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Land", "Boat").forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option; selectedSpot = null },
                        label = { Text(if (option == "All") "All spots" else "$option fishing") })
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Seafoam)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Surface(Modifier.align(Alignment.TopStart).padding(12.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                Text("${visibleSpots.size} spots", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Navy, style = MaterialTheme.typography.labelMedium)
            }
            FloatingActionButton(onClick = ::requestLocation,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp),
                shape = CircleShape, containerColor = MaterialTheme.colorScheme.surface, contentColor = Navy) {
                Icon(Icons.Default.NearMe, contentDescription = "Jump to my location")
            }
            if (!mapLoaded && !mapError) Card(Modifier.align(Alignment.Center).padding(24.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Loading fishing map…", color = Navy)
                }
            }
            if (mapError) Card(Modifier.align(Alignment.Center).padding(24.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Map unavailable", color = Navy, fontWeight = FontWeight.Bold)
                    Text("Check your connection, then try again. The fishing spots are available in the list.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { mapError = false; mapLoaded = false; nativeMap.value = null; mapView.getMapAsync { map -> map.setStyle(MAP_STYLE) { nativeMap.value = map } } }) { Text("Retry map") }
                        TextButton(onClick = { showList = true }) { Text("Browse spots") }
                    }
                }
            }
            selectedSpot?.let { spot ->
                Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(6.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(spot.name, color = Navy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${spot.area} · ${if (spot.boat) "Boat" else "Land"} fishing", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { selectedSpot = null }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Close spot details") }
                        }
                        Text("Approximate fishing area. Confirm access and local rules before leaving.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            vm.setBoat(spot.boat)
                            vm.selectManualOrigin(SearchOrigin(spot.name, GeoPoint(spot.latitude, spot.longitude)))
                            vm.showResults()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Find a fishing window nearby") }
                    }
                }
            }
            locationNotice?.let { notice ->
                if (selectedSpot == null) Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(notice, Modifier.padding(12.dp), color = Navy, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("© OpenStreetMap contributors · OpenFreeMap", Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = if (selectedSpot != null || locationNotice != null) 180.dp else 32.dp)
                .background(Color.White.copy(alpha = 0.88f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
                color = Color.DarkGray, style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showList) ModalBottomSheet(onDismissRequest = { showList = false }) {
        Text("Fishing areas", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, color = Navy, fontWeight = FontWeight.Bold)
        Text(if (mapError) "Choose an area to plan a fishing window" else "Tap an area to see it on the map",
            Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.8f), contentPadding = PaddingValues(12.dp)) {
            items(visibleSpots, key = { "${it.name}:${it.latitude}:${it.longitude}" }) { spot ->
                Column(Modifier.fillMaxWidth().clickable {
                    selectedSpot = spot
                    showList = false
                    nativeMap.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(spot.latitude, spot.longitude), 11.0))
                }.padding(horizontal = 10.dp, vertical = 12.dp)) {
                    Text(spot.name, color = Navy, fontWeight = FontWeight.SemiBold)
                    Text("${spot.area} · ${if (spot.boat) "Boat" else "Land"} fishing", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun mapPin(context: android.content.Context, color: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val width = (24 * density).toInt().coerceAtLeast(24)
    val height = (32 * density).toInt().coerceAtLeast(32)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE }
    val centerX = width / 2f
    val radius = width * 0.38f
    val centerY = radius + width * 0.07f
    canvas.drawPath(Path().apply {
        moveTo(centerX - radius * 0.60f, centerY + radius * 0.7f)
        lineTo(centerX, height.toFloat() - 2 * density)
        lineTo(centerX + radius * 0.60f, centerY + radius * 0.7f)
        close()
    }, fill)
    canvas.drawCircle(centerX, centerY, radius, fill)
    canvas.drawCircle(centerX, centerY, radius * 0.38f, white)
    return bitmap
}

private fun clusterPin(context: android.content.Context, count: Int): Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (34 * density).toInt().coerceAtLeast(34)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    canvas.drawCircle(center, center, center - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    canvas.drawCircle(center, center, center - 3 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF123B43.toInt() })
    val label = count.toString()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 14 * density
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    canvas.drawText(label, center, center - (paint.ascent() + paint.descent()) / 2, paint)
    return bitmap
}
