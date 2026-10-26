package nz.fishingnz.app.ui

import nz.fishingnz.app.data.tr

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import nz.fishingnz.app.data.CatchLogStore
import nz.fishingnz.app.data.placeDistanceKm
import nz.fishingnz.app.model.*
import nz.fishingnz.app.viewmodel.FishingUiState
import nz.fishingnz.app.viewmodel.FishingViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val nzZone = ZoneId.of("Pacific/Auckland")
private val whenFormat = DateTimeFormatter.ofPattern("EEE d MMM yyyy · h:mm a", Locale.US)

/** More → Catch log: the list, a few patterns once there are three catches, export and the entry form. */
@Composable fun CatchLogScreen(modifier: Modifier, s: FishingUiState, vm: FishingViewModel) {
    val context = LocalContext.current
    val entries = remember(s.catches) { CatchLog.visible(s.catches) }
    var editing by remember { mutableStateOf<CatchEntry?>(null) }
    var prefillPhoto by remember { mutableStateOf<Uri?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    LaunchedEffect(s.catchPrefill) {
        val prefill = s.catchPrefill ?: return@LaunchedEffect
        editing = newCatch(s).copy(species = prefill.species, rulesAreaId = prefill.rulesAreaId ?: s.fishRulesAreaId)
        prefillPhoto = prefill.photo
        vm.catchPrefillUsed()
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("Catch log", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Navy)
            Text("Your catches with the tide, wind and moon at the time. Saved on this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Button(onClick = { editing = newCatch(s); prefillPhoto = null }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Log a catch")
            }
        }
        val insights = CatchLog.insights(s.catches)
        if (insights.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Your patterns", fontWeight = FontWeight.Bold, color = Navy)
                    insights.forEach { Text(it, color = Navy) }
                    Text("From your own log only; it doesn't predict catches.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (entries.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No catches yet", fontWeight = FontWeight.Bold, color = Navy)
                    Text("Log a fish to check its size against the MPI summary and keep a record of where and when you caught it. After identifying a fish from a photo, tap Log this catch.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(entries, key = { it.id }) { entry -> CatchCard(entry) { editing = entry; prefillPhoto = null } }
        if (entries.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shareCatchLog(context, CatchLog.csv(s.catches)) }) { Text("Export as CSV") }
                TextButton(onClick = { confirmDeleteAll = true }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    editing?.let { entry ->
        CatchForm(entry, prefillPhoto, s, vm, isNew = s.catches.none { it.id == entry.id },
            close = { editing = null; prefillPhoto = null })
    }
    if (confirmDeleteAll) AlertDialog(onDismissRequest = { confirmDeleteAll = false },
        title = { Text("Delete your catch log?") },
        text = { Text("Every catch and photo in the log is removed from this phone.") },
        confirmButton = { TextButton(onClick = { vm.deleteAllCatches(); confirmDeleteAll = false }) { Text("Delete all", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } })
}

/** A new entry at the current time: the active trip's spot, else the phone's position and nearest listed spot. */
private fun newCatch(s: FishingUiState): CatchEntry {
    val trip = s.activeTrip?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
    val device = s.deviceLocation
    val tripPoint = trip?.let { GeoPoint(it.latitude, it.longitude) }
    val atTrip = tripPoint != null && (device == null || placeDistanceKm(device, tripPoint) <= 5)
    val point = if (atTrip) tripPoint else device
    val place = when {
        atTrip -> trip!!.name
        device != null -> fishingSpots.filter { placeDistanceKm(device, GeoPoint(it.latitude, it.longitude)) <= 3 }
            .minByOrNull { placeDistanceKm(device, GeoPoint(it.latitude, it.longitude)) }?.name ?: "Near ${nearestCityName(device)}"
        else -> ""
    }
    return CatchEntry(id = UUID.randomUUID().toString(), caughtAtEpochSeconds = Instant.now().epochSecond, species = "",
        placeName = place, latitude = point?.latitude, longitude = point?.longitude, rulesAreaId = s.fishRulesAreaId)
}

@Composable private fun CatchCard(entry: CatchEntry, open: () -> Unit) {
    val photo = remember(entry.photoFile) { CatchLogStore.photo(entry.photoFile)?.let { decodeThumbnail(it.path) } }
    Card(Modifier.fillMaxWidth().clickable(onClick = open), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            photo?.let { Image(it.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(CatchLog.headline(entry), fontWeight = FontWeight.Bold, color = Navy)
                Text(listOf(entry.placeName, Instant.ofEpochSecond(entry.caughtAtEpochSeconds).atZone(nzZone).format(whenFormat))
                    .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val conditions = listOfNotNull(entry.conditions.tide?.substringBefore(" ("), entry.conditions.wind?.substringBefore(":"), entry.conditions.moon)
                if (conditions.isNotEmpty()) Text(conditions.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (entry.notes.isNotBlank()) Text(entry.notes, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CatchForm(original: CatchEntry, prefillPhoto: Uri?, s: FishingUiState, vm: FishingViewModel, isNew: Boolean, close: () -> Unit) {
    val context = LocalContext.current
    var species by rememberSaveable(original.id) { mutableStateOf(original.species) }
    var length by rememberSaveable(original.id) { mutableStateOf(original.lengthCm?.let { CatchLog.formatCm(it).removeSuffix(" cm") }.orEmpty()) }
    var kept by rememberSaveable(original.id) { mutableStateOf(original.kept) }
    var count by rememberSaveable(original.id) { mutableIntStateOf(original.count) }
    var place by rememberSaveable(original.id) { mutableStateOf(original.placeName) }
    var notes by rememberSaveable(original.id) { mutableStateOf(original.notes) }
    var areaId by rememberSaveable(original.id) { mutableStateOf(original.rulesAreaId) }
    var caughtAt by rememberSaveable(original.id) { mutableLongStateOf(original.caughtAtEpochSeconds) }
    var photo by remember(original.id) { mutableStateOf(prefillPhoto) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }
    val lengthCm = length.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= CatchLog.MAX_LENGTH_CM }
    // Size limits for the species in the chosen MPI area, looked up after typing pauses.
    var rules by remember { mutableStateOf<FishRulesResult?>(null) }
    var rulesState by remember { mutableStateOf("") }
    LaunchedEffect(species.trim(), areaId) {
        rules = null
        val name = species.trim(); val area = areaId
        if (name.length < 3 || area == null) { rulesState = ""; return@LaunchedEffect }
        rulesState = "Checking the MPI summary…"
        delay(500)
        rulesState = try { rules = vm.rulesFor(name, area); "" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Couldn't load MPI limits. Check the MPI rules for this area." }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Cream) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isNew) "Log a catch" else "Edit catch", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy)
                    IconButton(onClick = close) { Icon(Icons.Default.Close, "Close without saving") }
                }
                OutlinedTextField(species, { species = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Species") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CatchLog.commonSpecies.forEach { name -> SuggestionChip(onClick = { species = name }, label = { Text(name) }) }
                }
                OutlinedTextField(length, { length = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) }, Modifier.fillMaxWidth(),
                    label = { Text("Length (cm)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("Measure from the snout to the fork of the tail for most finfish.") })
                Text("MPI area", fontWeight = FontWeight.SemiBold, color = Navy)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fishingRulesAreas.forEach { area -> FilterChip(areaId == area.id, { areaId = area.id }, label = { Text(area.name) }) }
                }
                SizeCheck(species.trim(), lengthCm, kept, count, caughtAt, original.id, areaId, rules, rulesState, s.catches)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!kept, { kept = false }, label = { Text("Released") })
                    FilterChip(kept, { kept = true }, label = { Text("Kept") })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { count = (count - 1).coerceAtLeast(1) }, enabled = count > 1) { Icon(Icons.Default.Remove, "One fewer") }
                    Text("$count", fontWeight = FontWeight.Bold)
                    IconButton(onClick = { count = (count + 1).coerceAtMost(99) }) { Icon(Icons.Default.Add, "One more") }
                }
                OutlinedTextField(place, { place = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Place") }, singleLine = true,
                    supportingText = { Text(if (original.latitude != null) "Position saved for the tide and weather lookup." else "Without a position, only the moon is recorded.") })
                val whenText = Instant.ofEpochSecond(caughtAt).atZone(nzZone).format(whenFormat)
                OutlinedButton(onClick = {
                    val current = Instant.ofEpochSecond(caughtAt).atZone(nzZone).toLocalDateTime()
                    DatePickerDialog(context, { _, year, month, day ->
                        TimePickerDialog(context, { _, hour, minute ->
                            val chosen = LocalDateTime.of(year, month + 1, day, hour, minute).atZone(nzZone).toInstant().epochSecond
                            caughtAt = minOf(chosen, Instant.now().epochSecond)
                        }, current.hour, current.minute, false).show()
                    }, current.year, current.monthValue - 1, current.dayOfMonth).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                }, modifier = Modifier.fillMaxWidth()) { Text("When: $whenText") }
                OutlinedTextField(notes, { notes = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text("Notes (bait, rig, spot details)") }, minLines = 2)
                val shownPhoto = remember(photo, original.photoFile) {
                    photo?.let { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) } }.getOrNull() }
                        ?: CatchLogStore.photo(original.photoFile)?.let { decodeThumbnail(it.path) }
                }
                shownPhoto?.let { Image(it.asImageBitmap(), "Catch photo", Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop) }
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (shownPhoto == null) "Add a photo" else "Change photo")
                }
                if (!original.conditions.isEmpty && !isNew) Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Conditions at the time", fontWeight = FontWeight.Bold, color = Navy)
                        // Catches saved before 1.0.56 name the station as "(LINZ Raglan)".
                        listOfNotNull(original.conditions.tide?.let { "Tide: " + it.replace("(LINZ ", "(") }, original.conditions.wind?.let { "Wind: $it" },
                            original.conditions.waves?.let { "Waves: $it" }, original.conditions.waterTemperature?.let { "Water: $it" },
                            original.conditions.moon?.let { "Moon: $it · ${original.conditions.tideRange.orEmpty()}" })
                            .forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Button(onClick = {
                    vm.saveCatch(original.copy(species = species.trim(), lengthCm = lengthCm, kept = kept, count = count, placeName = place.trim(),
                        notes = notes.trim(), rulesAreaId = areaId, caughtAtEpochSeconds = caughtAt), photo)
                    close()
                }, enabled = species.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (isNew) "Save catch" else "Save changes") }
                if (!isNew) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete this catch", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this catch?") }, text = { Text("The entry and its photo are removed from your log.") },
        confirmButton = { TextButton(onClick = { vm.deleteCatch(original.id); confirmDelete = false; close() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

/** The size check and daily count against the MPI summary rows for the species. */
@Composable private fun SizeCheck(species: String, lengthCm: Double?, kept: Boolean, count: Int, caughtAt: Long, id: String, areaId: String?,
                                  rules: FishRulesResult?, state: String, catches: List<CatchEntry>) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    if (species.length < 3) return
    val area = fishingRulesAreas.firstOrNull { it.id == areaId }
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Size and daily limit", fontWeight = FontWeight.Bold, color = Navy)
            when {
                area == null -> Text("Choose the MPI area where you caught it.", style = MaterialTheme.typography.bodySmall)
                state.isNotEmpty() -> Text(state, style = MaterialTheme.typography.bodySmall)
                rules == null -> Unit
                rules.fishRules.isEmpty() -> Text("No size or daily limit for \"$species\" in the ${area.name} summary. Check the spelling, or MPI's rules.", style = MaterialTheme.typography.bodySmall)
                else -> {
                    if (lengthCm == null) Text("Enter the length to compare it with the minimum size.", style = MaterialTheme.typography.bodySmall)
                    else CatchLog.sizeVerdicts(lengthCm, rules.fishRules).forEach { verdict ->
                        val mark = when (verdict.meets) { true -> "✅"; false -> "⚠️"; null -> "🔎" }
                        Text("$mark ${verdict.text}", style = MaterialTheme.typography.bodySmall,
                            color = if (verdict.meets == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    val already = CatchLog.keptOnDay(catches, species, Instant.ofEpochSecond(caughtAt), excludingId = id)
                    rules.fishRules.mapNotNull { rule -> rule.dailyLimit?.let { "${cleanMpiRuleText(rule.species)}: ${cleanMpiLimitValue(it)} a day" } }.distinct().forEach {
                        Text("Daily limit · $it", style = MaterialTheme.typography.bodySmall)
                    }
                    val total = already + if (kept) count else 0
                    if (total > 0) Text("Kept that day in your log, including this one: $total", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Text("From the saved MPI summary for ${rules.areaName}. Limits can differ by subarea and change; check MPI before you keep a fish.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { uriHandler.openUri(rules.rulesSourceUrl ?: area.officialUrl) }, contentPadding = PaddingValues(0.dp)) { Text("Open MPI rules") }
                }
            }
        }
    }
}

private fun decodeThumbnail(path: String) = runCatching {
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
}.getOrNull()

private fun shareCatchLog(context: Context, csv: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_SUBJECT, tr("Fishdays - NZ catch log"))
        putExtra(Intent.EXTRA_TEXT, csv)
    }
    try { context.startActivity(Intent.createChooser(send, tr("Export catch log"))) }
    catch (_: ActivityNotFoundException) { android.widget.Toast.makeText(context, tr("No app is available to share with."), android.widget.Toast.LENGTH_LONG).show() }
}
