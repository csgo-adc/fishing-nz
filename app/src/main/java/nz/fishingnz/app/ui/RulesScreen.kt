package nz.fishingnz.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.fishingnz.app.data.FishingRulesPage
import nz.fishingnz.app.data.OfflineCache
import nz.fishingnz.app.data.RulesRepository
import nz.fishingnz.app.data.RulesSource
import nz.fishingnz.app.data.RulesSync
import nz.fishingnz.app.data.SpeciesCatalog
import nz.fishingnz.app.data.SpeciesInfo
import nz.fishingnz.app.data.SpeciesPicture
import nz.fishingnz.app.model.isNearRaglan
import nz.fishingnz.app.viewmodel.FishingUiState
import nz.fishingnz.app.viewmodel.FishingViewModel
import java.time.Duration
import java.time.Instant

/**
 * The fishing rules page: the MPI area's species with pictures, minimum sizes and daily limits as big figures, a catch
 * size check, and a note on how fresh the copy kept on the phone is. The iPhone app has the same page in
 * `FeatureViews.swift`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun RulesScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val repository = remember { RulesRepository() }
    val selectedAreaId = s.fishRulesAreaId
    var showAreas by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(SpeciesGroup.POPULAR) }
    var page by remember { mutableStateOf<FishingRulesPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var checking by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<RuleSpeciesRow?>(null) }
    var locationLoading by remember { mutableStateOf(false) }
    var locationNotice by remember { mutableStateOf<String?>(null) }
    val revision by RulesSync.revision.collectAsState()
    val catalog = remember(revision) { RulesSync.catalog() }
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
    // Opening the page checks the service for changed rules and pictures; nothing is downloaded when nothing changed.
    LaunchedEffect(Unit) { RulesSync.sync() }
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
    LaunchedEffect(selectedAreaId, reloadToken, revision) {
        error = null
        val id = selectedAreaId ?: run { page = null; loading = false; return@LaunchedEffect }
        loading = page == null
        // A newer area or reload cancels this load; it must not overwrite the newer result.
        try { page = repository.load(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (exception: Exception) { page = null; error = exception.message ?: "Saved rules are unavailable right now." }
        finally { if (isActive) loading = false }
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
            TextButton(onClick = { uriHandler.openUri("https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules") }) { Text("View MPI fishing-area maps") }
        }
    }

    val search = query.trim()
    val nearRaglanWest = s.fishRulesAreaIsSuggested && selectedAreaId == "auckland-kermadec" && isNearRaglan(s.deviceLocation)
    val speciesRules = page?.let(::ruleSpeciesRows).orEmpty().let { rows ->
        if (nearRaglanWest && rows.any { it.species.contains("Auckland West", ignoreCase = true) })
            rows.filter { !it.species.startsWith("Snapper", ignoreCase = true) || it.species.contains("Auckland West", ignoreCase = true) }
        else rows
    }
    fun infoFor(row: RuleSpeciesRow): SpeciesInfo? = catalog?.forRule(row.species)
    val shown = when {
        search.isNotEmpty() -> speciesRules.filter { row ->
            row.species.contains(search, ignoreCase = true) || infoFor(row)?.let { it.name.contains(search, true) || it.maori?.contains(search, true) == true } == true
        }.take(40)
        group == SpeciesGroup.POPULAR -> popularRows(speciesRules)
        group == SpeciesGroup.ALL -> speciesRules
        else -> speciesRules.filter { infoFor(it)?.group == group.id }
    }
    val officialUrl = area?.officialUrl ?: page?.sourceUrl ?: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules"

    LazyColumn(modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Fishing rules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Sizes and daily limits for the MPI area where you fish. Kept on your phone for when there's no signal.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Where will you fish?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { showAreas = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(area?.name ?: "Choose an area", modifier = Modifier.weight(1f))
                        Text("⌄")
                    }
                    Text(when {
                        locationLoading -> "Checking current location…"
                        s.fishRulesAreaIsSuggested -> "Suggested from your current location. Near an area boundary? Check the MPI map."
                        area != null -> "${area.description}. Near a boundary? Check the MPI map."
                        else -> "Choose the area to see its sizes and limits."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (nearRaglanWest) Text("Near Raglan: snapper is in the Auckland West subarea. Check the exact catch spot on MPI.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    locationNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (loading) item { Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        error?.let { message -> item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                TextButton(onClick = { reloadToken++ }) { Text("Try again") }
                TextButton(onClick = { uriHandler.openUri(officialUrl) }) { Text("Open official MPI rules") }
            }
        } } }
        page?.let { rules ->
            item {
                RulesFreshness(rules, checking) {
                    scope.launch { checking = true; RulesSync.sync(force = true); checking = false; reloadToken++ }
                }
            }
            if (rules.needsReview) item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MPI has updated this area", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy)
                    Text("Open the current MPI page for limits and local restrictions. The saved summary is being reviewed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { uriHandler.openUri(officialUrl) }) { Text("Open current MPI rules") }
                }
            } }
            else {
                item { CombinedLimit(rules, selectedAreaId) }
                val popular = popularRows(speciesRules).filter { it.minimum != null }
                val checkable = popular + speciesRules.filter { row -> row.minimum != null && popular.none { it.id == row.id } }
                if (checkable.isNotEmpty()) item { CheckMyCatch(checkable, catalog) }
                item {
                    OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search a fish or shellfish") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } })
                }
                if (search.isEmpty()) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpeciesGroup.entries.forEach { option ->
                            FilterChip(selected = group == option, onClick = { group = option }, label = { Text(option.label) })
                        }
                    }
                }
                shown.forEach { row -> item(key = "species-${row.id}") { SpeciesRuleCard(row, infoFor(row)) { detail = row } } }
                if (shown.isEmpty()) item {
                    Text(if (search.isEmpty()) "No species in this group have a simple limit for ${rules.areaName}. Try All or check MPI."
                        else "No reliable simple limit can be shown for “$search” in ${rules.areaName}. Check MPI for this species and exact subarea.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Text("Summary only. Closures, gear rules and subareas can change what applies: check MPI for your exact spot. " +
                        (catalog?.note ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                item { Button(onClick = { uriHandler.openUri(officialUrl) }, modifier = Modifier.fillMaxWidth()) { Text("See all rules on MPI") } }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    detail?.let { row ->
        ModalBottomSheet(onDismissRequest = { detail = null }) { SpeciesDetail(row, infoFor(row), catalog, officialUrl) }
    }
}

/** How fresh the rules on screen are, and a way to check the service now. */
@Composable private fun RulesFreshness(page: FishingRulesPage, checking: Boolean, check: () -> Unit) {
    val checked = listOfNotNull(page.savedAt, RulesSync.lastSynced().takeIf { page.source == RulesSource.SAVED }).maxOrNull()
    val old = checked == null || Duration.between(checked, Instant.now()) > Duration.ofDays(30)
    val text = when {
        page.source == RulesSource.BUILT_IN -> "Showing the copy built into the app" + (page.reviewedAt?.let { " (MPI page reviewed $it)" } ?: "") +
            ". Connect once to get the latest rules and pictures."
        old -> "Saved on this phone ${checked?.let(OfflineCache::savedLabel) ?: ""}. Connect to check for MPI changes."
        else -> "Saved on this phone for use with no signal · checked ${OfflineCache.savedLabel(checked!!)}" +
            (page.reviewedAt?.let { " · MPI page reviewed $it" } ?: "")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = if (page.source == RulesSource.BUILT_IN || old) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = check, enabled = !checking) { Text(if (checking) "Checking…" else "Check now") }
    }
}

/** The area's combined daily finfish limit as a big number. */
@Composable private fun CombinedLimit(page: FishingRulesPage, areaId: String?) {
    val limit = combinedFinfishLimit(page).takeIf { areaId != "fiordland" }
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (limit != null) {
                Text("$limit", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Text("finfish per person per day, all species combined. Each species also has its own limit.", color = Navy)
            } else Text(if (areaId == "fiordland") "Daily limits differ between the outer Fiordland Marine Area and the inner fiords. Check the exact subarea on MPI."
                else "Daily limits vary by species and place. Check the MPI page for your exact spot.", color = Navy)
        }
    }
}

/** A species' picture, from the phone or downloaded now; a fish outline until it loads or with no signal. */
@Composable internal fun SpeciesPictureView(picture: SpeciesPicture?, description: String, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, picture?.file) {
        value = picture?.let { item -> withContext(Dispatchers.IO) { RulesSync.picture(item)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } } }
    }
    Box(modifier.background(Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        image?.let { Image(it, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(2.dp)) }
            ?: Icon(Icons.Default.Phishing, contentDescription = null, tint = Color(0xFF8490A4))
    }
}

/** A size or limit as a big figure: "30 cm" over "minimum", or "No take". */
@Composable private fun RuleFigure(value: String, unit: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = if (value.length <= 4) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = Navy, maxLines = 1)
            if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodyMedium, color = Navy, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A bar from 0 to 100 cm (or 200 mm for shellfish) with the minimum size filled in. */
@Composable private fun SizeBar(minimum: Pair<Double, String>) {
    val scale = if (minimum.second == "mm") 200.0 else 100.0
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth((minimum.first / scale).coerceIn(0.03, 1.0).toFloat()).height(8.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
        }
        Row(Modifier.fillMaxWidth()) {
            Text("0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${(scale / 2).toInt()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${scale.toInt()} ${minimum.second}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

private fun figure(value: Double) = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

@Composable private fun SpeciesRuleCard(row: RuleSpeciesRow, info: SpeciesInfo?, open: () -> Unit) {
    Card(onClick = open, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeciesPictureView(info?.pictures?.firstOrNull(), info?.name ?: row.species, Modifier.size(width = 108.dp, height = 72.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.species, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Navy, maxLines = 2)
                    info?.maori?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val minimum = row.minimum
                        val minimumText = row.facts.firstOrNull { it.first.startsWith("Minimum") }
                        when {
                            minimum != null -> RuleFigure(figure(minimum.first), minimum.second, "minimum")
                            minimumText != null -> RuleFigure(minimumText.second, "", minimumText.first.lowercase())
                            else -> RuleFigure("None", "", "minimum")
                        }
                        row.dailyLimit?.let { RuleFigure(it, "", "per day") }
                    }
                }
            }
            row.minimum?.let { SizeBar(it) }
            row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
    }
}

/** A species' pictures, every rule fact, the catch check for it and the MPI link. */
@Composable private fun SpeciesDetail(row: RuleSpeciesRow, info: SpeciesInfo?, catalog: SpeciesCatalog?, officialUrl: String) {
    val uriHandler = LocalUriHandler.current
    var selected by remember(row.id) { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        info?.pictures?.takeIf { it.isNotEmpty() }?.let { pictures ->
            val picture = pictures[selected.coerceIn(pictures.indices)]
            SpeciesPictureView(picture, "${info.name}, ${picture.caption}", Modifier.fillMaxWidth().aspectRatio(1.5f))
            if (pictures.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pictures.forEachIndexed { index, item -> FilterChip(selected = index == selected, onClick = { selected = index }, label = { Text(item.caption) }) }
            }
            Text("Illustration · ${picture.caption}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(row.species, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy)
        info?.let { Text(listOfNotNull(it.maori, it.scientific.takeIf(String::isNotBlank)).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        row.facts.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, fontWeight = FontWeight.Bold, color = Navy, style = MaterialTheme.typography.titleMedium)
            }
        }
        row.minimum?.let { SizeBar(it) }
        row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (row.minimum != null) CheckMyCatch(listOf(row), catalog)
        OutlinedButton(onClick = { uriHandler.openUri(officialUrl) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (row.hasFootnote) "Read the MPI footnote" else "Check the official MPI rule")
        }
        catalog?.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Pick a species, type a length and see whether it meets the minimum; with one row, the species is fixed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CheckMyCatch(rows: List<RuleSpeciesRow>, catalog: SpeciesCatalog?) {
    var selectedId by rememberSaveable(rows.map { it.id }) { mutableStateOf(rows.first().id) }
    var lengthText by rememberSaveable(selectedId) { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val row = rows.firstOrNull { it.id == selectedId } ?: rows.first()
    val unit = row.minimum?.second ?: "cm"
    val result = lengthText.replace(',', '.').toDoubleOrNull()?.let { checkCatch(row, it) }
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Check my catch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            if (rows.size > 1) ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(value = row.species, onValueChange = {}, readOnly = true, singleLine = true, label = { Text("Species") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    rows.forEach { option ->
                        DropdownMenuItem(text = { Text(option.species + (catalog?.forRule(option.species)?.maori?.let { " · $it" } ?: "")) },
                            onClick = { selectedId = option.id; expanded = false })
                    }
                }
            }
            // The answer sits above the field so the keyboard never hides it.
            result?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (it.meets) Icons.Default.CheckCircle else Icons.Default.Cancel, contentDescription = null,
                        tint = if (it.meets) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(it.text, color = if (it.meets) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedTextField(value = lengthText, onValueChange = { lengthText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) },
                label = { Text("Length ($unit)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("Measure the way MPI describes for this species. Works with no signal.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}
