package nz.fishingnz.app.ui

import nz.fishingnz.app.data.tr

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nz.fishingnz.app.data.SpotListings
import nz.fishingnz.app.model.FishingSpot
import nz.fishingnz.app.model.MoonPhase
import nz.fishingnz.app.model.TidePoint
import nz.fishingnz.app.model.TideRange
import nz.fishingnz.app.model.fishingSpots
import nz.fishingnz.app.viewmodel.FishingUiState
import nz.fishingnz.app.viewmodel.FishingViewModel
import nz.fishingnz.app.viewmodel.SearchLocationMode
import nz.fishingnz.app.viewmodel.searchPlaceName
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val nzZone = ZoneId.of("Pacific/Auckland")

/** The tide height at [minute] of the day, read off the curve's samples; null without samples. */
internal fun tideLevelAt(points: List<TidePoint>, minute: Int): Double? {
    val sorted = points.sortedBy { it.minuteOfDay }
    if (sorted.isEmpty()) return null
    val after = sorted.indexOfFirst { it.minuteOfDay >= minute }
    if (after == -1) return sorted.last().level
    if (after == 0) return sorted.first().level
    val a = sorted[after - 1]; val b = sorted[after]
    val span = (b.minuteOfDay - a.minuteOfDay).takeIf { it > 0 } ?: return b.level
    return a.level + (b.level - a.level) * (minute - a.minuteOfDay) / span
}

/** True while the tide is coming in at [minute], false while it goes out; null without samples. */
internal fun tideRising(points: List<TidePoint>, minute: Int): Boolean? {
    val now = tideLevelAt(points, minute) ?: return null
    val later = tideLevelAt(points, (minute + 20).coerceAtMost(1440)) ?: return null
    return if (minute + 20 > 1440) null else later > now
}

private fun moonRangeShort(range: TideRange) = when (range) {
    TideRange.SPRING -> "Spring"
    TideRange.NEAP -> "Neap"
    TideRange.BETWEEN -> "Medium"
}

/** Home's "Today" card: the day's tide as a curve with a marker at now, then wind, temperature, moon and sunset. */
@Composable internal fun TodayCard(s: FishingUiState, onChoosePlace: () -> Unit) {
    val moon = remember { MoonPhase.forDay(LocalDate.now(nzZone)) }
    val nowMinute = remember { LocalTime.now(nzZone).let { it.hour * 60 + it.minute } }
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val tide = s.tide
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
                tide?.let { tideRising(it.points, nowMinute) }?.let { rising ->
                    Text(if (rising) "Tide coming in ↑" else "Tide going out ↓", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
            }
            if (s.originName == null) {
                Text("Choose where you'll fish to see today's tide and weather.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onChoosePlace) { Text("Choose a place") }
            } else {
                if (tide != null && tide.points.size > 1) {
                    TideSparkline(tide.points, nowMinute)
                    Text(tide.events.joinToString("  ·  ") { "${it.type} ${it.time}" }, color = Navy, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold)
                    if (tide.stationName.isNotBlank()) Text("${tide.stationName} tide station", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall)
                } else Text(if (s.error != null) "Today's tide is unavailable right now." else "Loading today's tide…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth()) {
                    TodayMetric("Wind", s.weather?.wind ?: "—", s.weather?.windFrom?.let { "from $it" }, Modifier.weight(1f))
                    TodayMetric("Temp", s.weather?.temperature ?: "—", null, Modifier.weight(1f))
                    TodayMetric("Moon", "${moon.emoji} ${moonRangeShort(moon.tideRange)}", moon.name, Modifier.weight(1.2f))
                    TodayMetric("Sunset", s.weather?.sunset ?: "—", null, Modifier.weight(1f))
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable private fun TodayMetric(label: String, value: String, detail: String?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Text(value, color = Navy, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        detail?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
}

/** A small tide curve for one day, midnight to midnight, with a dot where the tide is now. */
@Composable internal fun TideSparkline(points: List<TidePoint>, nowMinute: Int) {
    val line = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.tertiary
    val guide = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = tr("Today's tide curve with the time now marked") }) {
        val sorted = points.sortedBy { it.minuteOfDay }
        val low = sorted.minOf { it.level }
        val span = (sorted.maxOf { it.level } - low).takeIf { it > 0.01 } ?: 1.0
        val inset = 6.dp.toPx()
        fun x(minute: Int) = size.width * minute / 1440f
        fun y(level: Double) = inset + (size.height - 2 * inset) * (1 - ((level - low) / span)).toFloat()
        val curve = Path().apply {
            sorted.forEachIndexed { index, point -> if (index == 0) moveTo(x(point.minuteOfDay), y(point.level)) else lineTo(x(point.minuteOfDay), y(point.level)) }
        }
        val fill = Path().apply {
            addPath(curve)
            lineTo(x(sorted.last().minuteOfDay), size.height)
            lineTo(x(sorted.first().minuteOfDay), size.height)
            close()
        }
        drawPath(fill, line.copy(alpha = .12f))
        drawPath(curve, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        tideLevelAt(sorted, nowMinute)?.let { level ->
            drawLine(guide, Offset(x(nowMinute), 0f), Offset(x(nowMinute), size.height), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(marker, radius = 5.dp.toPx(), center = Offset(x(nowMinute), y(level)))
        }
    }
}

/** Home's four shortcuts. */
@Composable internal fun HomeShortcuts(onRules: () -> Unit, onIdentify: () -> Unit, onCatch: () -> Unit, onTrips: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf<Triple<ImageVector, String, () -> Unit>>(
            Triple(Icons.Default.MenuBook, "Rules", onRules),
            Triple(Icons.Default.PhotoCamera, "Identify", onIdentify),
            Triple(Icons.Default.Phishing, "Log catch", onCatch),
            Triple(Icons.Default.CalendarMonth, "Trips", onTrips)
        ).forEach { (icon, label, open) ->
            Card(onClick = open, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(label, color = Navy, style = MaterialTheme.typography.labelMedium, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** The three spots nearest the plan's place for the plan's land or boat choice. */
@Composable internal fun NearbySpots(s: FishingUiState, onSpot: (FishingSpot) -> Unit, onSeeAll: () -> Unit) {
    if (s.originName == null) return
    val nearest = remember(s.location, s.boat) {
        SpotListings.build(fishingSpots.filter { it.boat == s.boat }, s.location, null, null, null).take(3)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.boat) "Boat spots near you" else "Spots near you", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
            TextButton(onClick = onSeeAll) { Text("See all") }
        }
        Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            nearest.forEachIndexed { index, listing ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text(listing.spot.name, color = Navy, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(listing.spot.area) },
                    leadingContent = { Icon(if (listing.spot.boat) Icons.Default.DirectionsBoat else Icons.Default.Phishing, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { listing.distanceKm?.let { Text(SpotListings.distanceLabel(it), color = Navy, fontWeight = FontWeight.SemiBold) } },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().clickable { onSpot(listing.spot) })
            }
        }
    }
}

/**
 * The first launch: land or boat, then the phone's location or a town, before Home. The iPhone app asks the same in
 * `CatchCheckRootView.swift`. Everything here can be changed later in the plan on Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun FirstRunSetup(s: FishingUiState, vm: FishingViewModel, done: () -> Unit) {
    val context = LocalContext.current
    var locating by remember { mutableStateOf(false) }
    var locationOff by remember { mutableStateOf(false) }
    var showWhere by remember { mutableStateOf(false) }
    val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.useDeviceSearchOrigin()
            requestCurrentLocation(context, { point -> locating = false; vm.updateLocation(point) }, { locating = false; locationOff = true })
        } else { locating = false; locationOff = true }
    }
    fun useLocation() { locating = true; locationOff = false; launcher.launch(permissions) }
    val nearMe = s.searchLocationMode == SearchLocationMode.NEAR_ME && !s.manualOriginSelected
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Kia ora", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Let's set up your fishing day", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
            Text("Two quick questions. You can change both later in your plan on Home.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("How do you usually fish?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupChoice(Icons.Default.Phishing, "From land", "Beach, rocks or wharf", !s.boat, Modifier.weight(1f)) { vm.setBoat(false) }
                SetupChoice(Icons.Default.DirectionsBoat, "From a boat", "Ramps and sea state", s.boat, Modifier.weight(1f)) { vm.setBoat(true) }
            }
            Spacer(Modifier.height(4.dp))
            Text("Where are you?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
            SetupRow(Icons.Default.NearMe, "Use my location", "Finds your nearest spots, tides and rules", nearMe && s.originName != null, ::useLocation)
            SetupRow(Icons.Default.Search, "Choose a town instead", "Or a fishing spot or tide location", !nearMe && (s.originName != null || s.searchPlaceName != null)) { showWhere = true }
            Text(when {
                locating -> "Finding your location…"
                locationOff -> "Location is off. Choose a town instead."
                s.searchPlaceName != null -> "At ${s.searchPlaceName}"
                s.originName != null -> "Near ${s.originName}"
                else -> "Nothing leaves your phone until you choose."
            }, color = if (locationOff) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = done, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Show my fishing day") }
            TextButton(onClick = done, modifier = Modifier.fillMaxWidth()) { Text("Skip for now") }
        }
    }
    if (showWhere) ModalBottomSheet(onDismissRequest = { showWhere = false }) {
        WherePicker(s, onBack = null, onUseCurrentLocation = { showWhere = false; useLocation() },
            onTown = { vm.selectManualOrigin(it); showWhere = false },
            onSpot = { vm.selectSearchSpot(it); showWhere = false },
            onStation = { vm.selectSearchStation(it); showWhere = false })
    }
}

@Composable private fun SetupChoice(icon: ImageVector, title: String, detail: String, selected: Boolean, modifier: Modifier, choose: () -> Unit) {
    Card(onClick = choose, modifier = modifier, shape = RoundedCornerShape(18.dp),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Text(title, color = Navy, fontWeight = FontWeight.Bold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun SetupRow(icon: ImageVector, title: String, detail: String, chosen: Boolean, choose: () -> Unit) {
    Card(onClick = choose, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
        ListItem(headlineContent = { Text(title, fontWeight = FontWeight.SemiBold, color = Navy) },
            supportingContent = { Text(detail) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { if (chosen) Icon(Icons.Default.CheckCircle, contentDescription = tr("Chosen"), tint = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface))
    }
}
