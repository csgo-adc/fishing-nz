package nz.fishingnz.app.ui

import nz.fishingnz.app.data.tr

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import nz.fishingnz.app.data.ConditionPlace
import nz.fishingnz.app.data.placeDistanceKm
import nz.fishingnz.app.data.recommendationTideStation
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import nz.fishingnz.app.model.FishingSpot
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.Recommendation
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
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import nz.fishingnz.app.data.MarineProtection
import nz.fishingnz.app.data.SpotListings
import java.util.Locale
import kotlin.math.floor

// LINZ permits this developer key to be embedded in a public client application.
private const val LINZ_BASEMAPS_API_KEY = "d01m0gkkx31k5jbq695p35xha3w"
private const val LINZ_COPYRIGHT_URL = "https://www.linz.govt.nz/copyright"
private const val LINZ_CONTRIBUTORS_URL =
    "https://www.linz.govt.nz/products-services/data/licensing-and-using-data/attributing-linz-basemaps-data"

private enum class BaseMap(val label: String, val description: String) {
    STANDARD("Standard", "Roads and places"),
    LIGHT("Light", "Less detail behind fishing spots"),
    DARK("Dark", "Easier to read at night"),
    AERIAL("Aerial", "New Zealand aerial and satellite imagery"),
    TOPOGRAPHIC("Topographic", "New Zealand terrain, roads and places");

    val isLinz get() = this == AERIAL || this == TOPOGRAPHIC

    fun style(): Style.Builder = when (this) {
        STANDARD -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")
        LIGHT -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/positron")
        DARK -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")
        TOPOGRAPHIC -> Style.Builder().fromUri(
            "https://basemaps.linz.govt.nz/v1/styles/topographic-v2.json?api=$LINZ_BASEMAPS_API_KEY"
        )
        AERIAL -> Style.Builder().fromJson(
            """{
                "version":8,
                "name":"LINZ aerial",
                "sources":{
                    "linz-aerial":{
                        "type":"raster",
                        "tiles":["https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api=$LINZ_BASEMAPS_API_KEY"],
                        "tileSize":256,
                        "maxzoom":22,
                        "attribution":"© <a href='${LINZ_COPYRIGHT_URL}'>LINZ CC BY 4.0</a> © <a href='${LINZ_CONTRIBUTORS_URL}'>Imagery Basemap contributors</a>"
                    }
                },
                "layers":[{"id":"linz-aerial","type":"raster","source":"linz-aerial"}]
            }""".trimIndent()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var filter by remember { mutableStateOf("All") }
    var selectedPlace by remember { mutableStateOf<ConditionPlace?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var showConditions by remember { mutableStateOf(false) }
    var navigationError by remember { mutableStateOf(false) }
    var selectedCardHeightPx by remember { mutableIntStateOf(0) }
    // The Spots tab opens on the map; the list, nearest first, is one tap away.
    var showMap by rememberSaveable { mutableStateOf(true) }
    var spotQuery by rememberSaveable { mutableStateOf("") }
    var showMapStyles by remember { mutableStateOf(false) }
    var baseMap by remember { mutableStateOf(BaseMap.STANDARD) }
    var mapLoaded by remember { mutableStateOf(false) }
    var mapError by remember { mutableStateOf(false) }
    var locationNotice by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableDoubleStateOf(5.0) }
    // Where the last button zoom is heading, so quick presses add up instead of starting from a half-finished zoom.
    var zoomTarget by remember { mutableStateOf<Double?>(null) }
    var bearing by remember { mutableDoubleStateOf(0.0) }
    var styleRevision by remember { mutableIntStateOf(0) }
    var showProtection by rememberSaveable { mutableStateOf(true) }
    val protection = rememberMarineProtection()
    val ramps = rememberBoatRamps()
    val structures = rememberShoreStructures()
    // Bumped when the camera stops, so ramp and wharf pins follow panning as well as zooming.
    var cameraTick by remember { mutableIntStateOf(0) }
    BackHandler(enabled = showMapStyles || selectedPlace != null) {
        when {
            showMapStyles -> showMapStyles = false
            else -> selectedPlace = null
        }
    }
    val nativeMap = remember { mutableStateOf<MapLibreMap?>(null) }
    val selectedCardHeight = with(density) { selectedCardHeightPx.toDp() }
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
                map.addOnCameraIdleListener { zoom = map.cameraPosition.zoom; zoomTarget = null; cameraTick++ }
                map.addOnMapClickListener { point ->
                    selectedPlace = ConditionPlace("Dropped pin", GeoPoint(point.latitude, point.longitude), "Selected on map", boat = filter == "Boat")
                    navigationError = false
                    true
                }
                map.addOnCameraMoveListener { bearing = map.cameraPosition.bearing }
                map.cameraPosition = CameraPosition.Builder()
                    .target(s.deviceLocation?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(-41.0, 173.5))
                    .zoom(if (s.deviceLocation != null) 11.0 else 4.7)
                    .build()
                map.setStyle(baseMap.style()) { nativeMap.value = map; styleRevision++ }
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

    val rampTick = if (filter != "All" && zoom >= 10.0) cameraTick else 0
    LaunchedEffect(nativeMap.value, styleRevision, visibleSpots, zoom, s.deviceLocation, selectedPlace?.id, filter, ramps, structures, rampTick) {
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
        // Boat ramps in Boat mode once zoomed in, only for the area on screen.
        val rampMarkers = mutableMapOf<Long, nz.fishingnz.app.data.BoatRamp>()
        if (filter == "Boat" && zoom >= 10.0 && ramps != null) {
            val bounds = map.projection.visibleRegion.latLngBounds
            val rampIcon = icons.fromBitmap(mapPin(context, 0xFF0E7C86.toInt()))
            ramps.asSequence().filter { bounds.contains(LatLng(it.point.latitude, it.point.longitude)) }.take(150).forEach { ramp ->
                val marker = map.addMarker(MarkerOptions().position(LatLng(ramp.point.latitude, ramp.point.longitude)).title(ramp.label).icon(rampIcon))
                rampMarkers[marker.id] = ramp
            }
        }
        // Wharves, breakwaters and groynes in Land mode once zoomed in, the same way.
        val structureMarkers = mutableMapOf<Long, nz.fishingnz.app.data.ShoreStructure>()
        if (filter == "Land" && zoom >= 10.0 && structures != null) {
            val bounds = map.projection.visibleRegion.latLngBounds
            val structureIcon = icons.fromBitmap(mapPin(context, 0xFF633806.toInt()))
            structures.asSequence().filter { bounds.contains(LatLng(it.point.latitude, it.point.longitude)) }.take(150).forEach { structure ->
                val marker = map.addMarker(MarkerOptions().position(LatLng(structure.point.latitude, structure.point.longitude)).title(structure.label).icon(structureIcon))
                structureMarkers[marker.id] = structure
            }
        }
        s.deviceLocation?.let { point -> map.addMarker(MarkerOptions()
            .position(LatLng(point.latitude, point.longitude))
            .title("Your location")
            .icon(icons.fromBitmap(mapPin(context, 0xFF2B8ACB.toInt())))) }
        val placeMarker = selectedPlace?.let { place -> map.addMarker(MarkerOptions()
            .position(LatLng(place.point.latitude, place.point.longitude)).title(place.name)
            .icon(icons.fromBitmap(mapPin(context, 0xFF166B58.toInt())))) }
        map.setOnMarkerClickListener { marker ->
            if (marker.id == placeMarker?.id) { showConditions = true; return@setOnMarkerClickListener true }
            rampMarkers[marker.id]?.let { ramp ->
                selectedPlace = ConditionPlace(ramp.label, ramp.point, "Access and tide limits not checked", boat = true)
                navigationError = false
                return@setOnMarkerClickListener true
            }
            structureMarkers[marker.id]?.let { structure ->
                selectedPlace = ConditionPlace(structure.label, structure.point, structure.mapSubtitle, boat = false)
                navigationError = false
                return@setOnMarkerClickListener true
            }
            val group = markerGroups[marker.id]
            navigationError = false
            if (group != null && group.size > 1) {
                selectedPlace = null
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, (map.cameraPosition.zoom + 2.5).coerceAtMost(12.0)))
            } else selectedPlace = group?.firstOrNull()?.asConditionPlace()
            true
        }
    }

    // DOC marine reserves and Hauraki Gulf protection areas under the pins: red where fishing is banned, amber for
    // Seafloor Protection Areas. A new base map style drops the layers, so they are added again on each style.
    LaunchedEffect(nativeMap.value, styleRevision, protection, showProtection) {
        val map = nativeMap.value ?: return@LaunchedEffect
        map.getStyle { style ->
            listOf("marine-protection-line", "marine-protection-fill").forEach { style.getLayer(it)?.let(style::removeLayer) }
            style.getSource("marine-protection")?.let(style::removeSource)
            val catalogue = protection ?: return@getStyle
            if (!showProtection) return@getStyle
            style.addSource(GeoJsonSource("marine-protection", catalogue.geoJson))
            val colour = Expression.match(Expression.get("kind"), Expression.color(0xFFD9822B.toInt()),
                Expression.stop("reserve", Expression.color(0xFFD64541.toInt())),
                Expression.stop("high_protection", Expression.color(0xFFD64541.toInt())))
            style.addLayer(FillLayer("marine-protection-fill", "marine-protection").withProperties(
                PropertyFactory.fillColor(colour), PropertyFactory.fillOpacity(0.22f)))
            style.addLayer(LineLayer("marine-protection-line", "marine-protection").withProperties(
                PropertyFactory.lineColor(colour), PropertyFactory.lineWidth(1.4f), PropertyFactory.lineOpacity(0.85f)))
        }
    }

    LaunchedEffect(s.deviceLocation) {
        // A fishing window sent here with "Show on map" keeps the camera on its area.
        if (s.mapFocus != null) return@LaunchedEffect
        s.deviceLocation?.let { point ->
            val map = snapshotFlow { nativeMap.value }.filterNotNull().first()
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude, point.longitude), 12.0))
        }
    }

    LaunchedEffect(s.mapFocus) {
        val place = s.mapFocus ?: return@LaunchedEffect
        selectedPlace = place; showConditions = false; showMap = true; showSearch = false; navigationError = false
        val map = snapshotFlow { nativeMap.value }.filterNotNull().first()
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(place.point.latitude, place.point.longitude), 11.0))
        vm.mapFocusShown()
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
    fun zoomBy(step: Double) {
        val map = nativeMap.value ?: return
        val target = ((zoomTarget ?: map.cameraPosition.zoom) + step).coerceIn(map.minZoomLevel, map.maxZoomLevel)
        zoomTarget = target
        map.animateCamera(CameraUpdateFactory.zoomTo(target))
    }
    fun selectBaseMap(option: BaseMap) {
        showMapStyles = false
        if (baseMap == option) return
        baseMap = option
        mapLoaded = false
        mapError = false
        nativeMap.value?.let { map ->
            nativeMap.value = null
            map.setStyle(option.style()) { nativeMap.value = map; styleRevision++ }
        }
    }
    // Resolve the position when the map opens, including after a fresh permission grant.
    LaunchedEffect(Unit) { requestLocation() }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Spots", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy)
                    Text(when {
                        showMap -> "Search a place or tap anywhere on the map"
                        s.deviceLocation != null -> "Nearest fishing spots first"
                        else -> "Fishing spots by area"
                    }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { showSearch = true }) { Icon(Icons.Default.Search, "Search places") }
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = !showMap, onClick = { showMap = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("List") }
                    SegmentedButton(selected = showMap, onClick = { showMap = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("Map") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Land", "Boat").forEach { option ->
                    FilterChip(selected = filter == option, onClick = { filter = option; selectedPlace = null },
                        label = { Text(if (option == "All") "All spots" else "$option fishing") })
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Seafoam)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // The end padding makes long text wrap instead of running under the layer buttons on narrow phones.
            Surface(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 72.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                Text("${visibleSpots.size} spots" + when (filter) {
                    "Boat" -> if (zoom >= 10.0) " · teal pins are boat ramps" else " · zoom in for boat ramps"
                    "Land" -> if (zoom >= 10.0) " · brown pins are wharves and breakwaters" else " · zoom in for wharves and breakwaters"
                    else -> ""
                },
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Navy, style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.align(Alignment.TopEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = { showMapStyles = true }, modifier = Modifier.size(48.dp),
                    shape = CircleShape, containerColor = MaterialTheme.colorScheme.surface, contentColor = Navy) {
                    Icon(Icons.Default.Layers, contentDescription = tr("Choose map layers"))
                }
                FloatingActionButton(onClick = { nativeMap.value?.animateCamera(CameraUpdateFactory.bearingTo(0.0)) },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = tr("Reset map to north") }, shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surface, contentColor = Navy) {
                    MapCompass(bearing)
                }
            }
            // Zoom and location buttons sit together at the bottom right, where a thumb reaches them.
            Column(Modifier.align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = when {
                    selectedPlace != null -> maxOf(selectedCardHeight, 196.dp) + 24.dp
                    locationNotice != null -> 84.dp
                    else -> 20.dp
                }),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val map = nativeMap.value
                MapZoomControl(
                    canZoomIn = map != null && zoom < map.maxZoomLevel - 0.05,
                    canZoomOut = map != null && zoom > map.minZoomLevel + 0.05,
                    onZoomIn = { zoomBy(1.0) }, onZoomOut = { zoomBy(-1.0) })
                FloatingActionButton(onClick = ::requestLocation, modifier = Modifier.size(48.dp),
                    shape = CircleShape, containerColor = MaterialTheme.colorScheme.surface, contentColor = Navy) {
                    Icon(Icons.Default.NearMe, contentDescription = tr("Center map on my location"))
                }
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
                        TextButton(onClick = { mapError = false; mapLoaded = false; nativeMap.value = null; mapView.getMapAsync { map -> map.setStyle(baseMap.style()) { nativeMap.value = map; styleRevision++ } } }) { Text("Retry map") }
                        TextButton(onClick = { showMap = false }) { Text("Browse spots") }
                    }
                }
            }
            selectedPlace?.let { place ->
                val spot = FishingSpot(place.name, place.region, place.point.latitude, place.point.longitude, place.boat)
                Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                    .onSizeChanged { selectedCardHeightPx = it.height },
                    shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(6.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(spot.name, color = Navy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(place.region, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { selectedPlace = null }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Close spot details") }
                        }
                        MarineProtectionNotice(place.point, compact = true)
                        Button(onClick = { showConditions = true }, modifier = Modifier.fillMaxWidth()) { Text("Check conditions") }
                        OutlinedButton(onClick = {
                            navigationError = !openPositionInMaps(context, place.point, spot.name)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Place, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open in Maps")
                        }
                        if (navigationError) Text("No map app could open this position.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            vm.setBoat(spot.boat)
                            vm.selectManualOrigin(SearchOrigin(spot.name, GeoPoint(spot.latitude, spot.longitude)))
                            vm.showResults()
                        }, modifier = Modifier.fillMaxWidth()) { Text("Find a fishing window nearby") }
                    }
                }
            }
            locationNotice?.let { notice ->
                if (selectedPlace == null) Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    Text(notice, Modifier.padding(12.dp), color = Navy, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.align(Alignment.BottomStart)
                .padding(start = 8.dp, bottom = if (selectedPlace != null) maxOf(selectedCardHeight, 196.dp) + 16.dp else if (locationNotice != null) 84.dp else 32.dp)
                .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (baseMap.isLinz) {
                    AttributionLink("© LINZ CC BY 4.0", LINZ_COPYRIGHT_URL)
                    AttributionLink(
                        if (baseMap == BaseMap.AERIAL) "© Imagery contributors" else "© Topographic contributors",
                        LINZ_CONTRIBUTORS_URL
                    )
                } else {
                    AttributionLink("© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright")
                    Text("· OpenFreeMap", color = Color.DarkGray, style = MaterialTheme.typography.labelSmall)
                }
                if (showProtection && protection != null) AttributionLink("· Protected areas © DOC", MarineProtection.DOC_URL)
                if (baseMap.isLinz && filter == "Boat" && zoom >= 10.0) AttributionLink("· Ramps © OpenStreetMap", nz.fishingnz.app.data.BoatRamps.CREDIT_URL)
                if (baseMap.isLinz && filter == "Land" && zoom >= 10.0) AttributionLink("· Wharves © OpenStreetMap", nz.fishingnz.app.data.ShoreStructures.CREDIT_URL)
            }
            // The list covers the map rather than replacing it, so the map keeps its place and loads in the background.
            if (!showMap) SpotList(visibleSpots, s.deviceLocation, ramps, structures, protection?.areas, spotQuery, { spotQuery = it }) { spot ->
                selectedPlace = spot.asConditionPlace()
                navigationError = false
                showMap = true
                nativeMap.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(spot.latitude, spot.longitude), 11.0))
            }
        }
    }

    if (showSearch) MapPlaceSearch(choose = { place ->
        selectedPlace = place; showSearch = false; navigationError = false; showMap = true
        nativeMap.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(place.point.latitude, place.point.longitude), 11.0))
    }, dismiss = { showSearch = false })
    if (showConditions && selectedPlace != null) key(selectedPlace!!.id) {
        PlaceConditionsScreen(selectedPlace!!, dismiss = { showConditions = false })
    }
    if (showMapStyles) ModalBottomSheet(onDismissRequest = { showMapStyles = false }) {
        Text("Map layers", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.titleLarge, color = Navy, fontWeight = FontWeight.Bold)
        Text("Choose how the map looks. Fishing spots stay visible on every style.",
            Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BaseMap.entries.forEach { option ->
            ListItem(
                headlineContent = { Text(option.label) },
                supportingContent = { Text(option.description) },
                leadingContent = { Icon(when (option) {
                    BaseMap.STANDARD -> Icons.Default.Map
                    BaseMap.LIGHT -> Icons.Default.LightMode
                    BaseMap.DARK -> Icons.Default.DarkMode
                    BaseMap.AERIAL, BaseMap.TOPOGRAPHIC -> Icons.Default.Layers
                }, null) },
                trailingContent = { RadioButton(selected = baseMap == option, onClick = null) },
                modifier = Modifier.fillMaxWidth().clickable { selectBaseMap(option) })
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(
            headlineContent = { Text("Marine protected areas") },
            supportingContent = { Text("Red: marine reserves and High Protection Areas, no fishing. Amber: Seafloor Protection Areas, where line fishing is allowed but trawling and dredging are not. Approximate boundaries; not for navigation.") },
            trailingContent = { Switch(checked = showProtection, onCheckedChange = null) },
            modifier = Modifier.fillMaxWidth().clickable { showProtection = !showProtection })
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A fishing window's area as a map place, for "Show on map": the listed spot when there is one, so its tide station
 * comes along. Null for a window without a position, such as an older shared link.
 */
internal fun Recommendation.mapPlace(): ConditionPlace? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    val point = GeoPoint(latitude, longitude)
    val spot = fishingSpots.firstOrNull { it.name == name && it.boat == boat && placeDistanceKm(point, GeoPoint(it.latitude, it.longitude)) < 1 }
    return (spot ?: FishingSpot(name, area, latitude, longitude, boat)).asConditionPlace()
}

internal fun FishingSpot.asConditionPlace() = ConditionPlace(name, GeoPoint(latitude, longitude), "$area · ${if (boat) "Boat" else "Land"} fishing", boat, recommendationTideStation(this, null))

/** Shows [point] as a pin in the phone's map app, where directions are one more tap; false when nothing can open it. */
internal fun openPositionInMaps(context: Context, point: GeoPoint, label: String): Boolean {
    val coordinates = String.format(Locale.US, "%.6f,%.6f", point.latitude, point.longitude)
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates(${Uri.encode(label)})")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$coordinates"))
    )
    intents.forEach { intent ->
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // Try another installed map handler or its web fallback.
        } catch (_: SecurityException) {
            // The selected app cannot accept this intent; try the next handler.
        }
    }
    return false
}

@Composable
private fun AttributionLink(label: String, url: String) {
    val context = LocalContext.current
    Text(label,
        Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
        color = Color(0xFF154E8A), style = MaterialTheme.typography.labelSmall)
}

/** Zoom in and out with one thumb, without pinching. */
@Composable
private fun MapZoomControl(canZoomIn: Boolean, canZoomOut: Boolean, onZoomIn: () -> Unit, onZoomOut: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, contentColor = Navy, shadowElevation = 6.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onZoomIn, enabled = canZoomIn, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Add, "Zoom in") }
            HorizontalDivider(Modifier.width(26.dp))
            IconButton(onClick = onZoomOut, enabled = canZoomOut, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Remove, "Zoom out") }
        }
    }
}

@Composable
private fun MapCompass(bearing: Double) {
    Canvas(Modifier.size(26.dp).rotate(-bearing.toFloat())) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val tip = size.minDimension * 0.43f
        val wing = size.minDimension * 0.16f
        val north = ComposePath().apply {
            moveTo(cx, cy - tip)
            lineTo(cx - wing, cy)
            lineTo(cx + wing, cy)
            close()
        }
        val south = ComposePath().apply {
            moveTo(cx, cy + tip)
            lineTo(cx - wing, cy)
            lineTo(cx + wing, cy)
            close()
        }
        drawPath(north, Color(0xFFE44B4B))
        drawPath(south, Color(0xFF52647A))
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

/** The Spots tab's list: every spot for the filter, nearest first when the phone's position is known. */
@Composable
private fun SpotList(spots: List<FishingSpot>, from: GeoPoint?, ramps: List<nz.fishingnz.app.data.BoatRamp>?,
                     structures: List<nz.fishingnz.app.data.ShoreStructure>?, protectedAreas: List<nz.fishingnz.app.data.ProtectedArea>?,
                     query: String, onQuery: (String) -> Unit, choose: (FishingSpot) -> Unit) {
    val listings = remember(spots, from, ramps, structures, protectedAreas) { SpotListings.build(spots, from, ramps, structures, protectedAreas) }
    val matching = listings.filter { SpotListings.matches(it, query) }
    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                placeholder = { Text("Search spots or areas") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Clear search") } })
        }
        item {
            Text(when {
                matching.isEmpty() -> "No spot matches “${query.trim()}”. Try a town or region, or search a place with the magnifier."
                from != null -> "${matching.size} spots · nearest first"
                else -> "${matching.size} spots · turn on location to sort by distance"
            }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        items(matching, key = { "${it.spot.name}:${it.spot.latitude}:${it.spot.longitude}:${it.spot.boat}" }) { listing ->
            Card(onClick = { choose(listing.spot) }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (listing.spot.boat) Icons.Default.DirectionsBoat else Icons.Default.Phishing,
                        contentDescription = tr(if (listing.spot.boat) "Boat fishing" else "Land fishing"), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(listing.spot.name, color = Navy, fontWeight = FontWeight.SemiBold)
                        Text("${listing.spot.area} · ${if (listing.spot.boat) "Boat" else "Land"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        listing.nearby?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                        listing.warning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                    }
                    listing.distanceKm?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(SpotListings.distanceLabel(it), color = Navy, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
