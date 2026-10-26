package nz.fishingnz.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.fishingnz.app.model.recommendationKey
import nz.fishingnz.app.viewmodel.FishingViewModel
import nz.fishingnz.app.data.Analytics
import nz.fishingnz.app.data.AppShare
import nz.fishingnz.app.data.PrivacyLinks
import nz.fishingnz.app.data.PrivacyPreferences
import nz.fishingnz.app.data.AppUpdates
import kotlinx.coroutines.delay

private enum class Appearance(val label: String) { SYSTEM("Use device setting"), LIGHT("Light"), DARK("Dark") }

private val lightPalette = lightColorScheme(
    primary = Color(0xFF315DE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5ECFF), onPrimaryContainer = Color(0xFF17367F),
    secondary = Color(0xFF6055A5), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEAFB), onSecondaryContainer = Color(0xFF33256D),
    tertiary = Color(0xFFBE583F), onTertiary = Color.White,
    background = Color(0xFFF6F8FC), onBackground = Color(0xFF172235),
    surface = Color.White, onSurface = Color(0xFF172235),
    surfaceVariant = Color(0xFFEDF1F8), onSurfaceVariant = Color(0xFF536179),
    outline = Color(0xFF8490A4)
)

private val darkPalette = darkColorScheme(
    primary = Color(0xFFAFC5FF), onPrimary = Color(0xFF122D73),
    primaryContainer = Color(0xFF263C69), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFC8BFFF), onSecondary = Color(0xFF30275F),
    secondaryContainer = Color(0xFF38305E), onSecondaryContainer = Color(0xFFE8E2FF),
    tertiary = Color(0xFFFFAF99), onTertiary = Color(0xFF67291C),
    background = Color(0xFF0F1726), onBackground = Color(0xFFE9EFFB),
    surface = Color(0xFF192337), onSurface = Color(0xFFE9EFFB),
    surfaceVariant = Color(0xFF28364C), onSurfaceVariant = Color(0xFFB4C1D5),
    outline = Color(0xFF8693A9)
)

@Composable
fun CatchCheckApp(vm: FishingViewModel = viewModel(), onUpdate: () -> Unit = {}) {
    val state by vm.state.collectAsState()
    val update by AppUpdates.state.collectAsState()
    var updatePrompt by remember { mutableStateOf<Int?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val imeOpen = WindowInsets.ime.getBottom(density) > 0
    val preferences = remember(context) { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var appearance by remember { mutableStateOf(runCatching { Appearance.valueOf(preferences.getString("mode", "LIGHT") ?: "LIGHT") }.getOrDefault(Appearance.LIGHT)) }
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val palette = if (dark) darkPalette else lightPalette
    // The first launch asks land or boat and where, once. People who used the app before 1.0.48 already chose.
    val firstRun = remember(context) { context.getSharedPreferences("first_run", Context.MODE_PRIVATE) }
    val locationOnboarding = remember(context) { context.getSharedPreferences("location_onboarding", Context.MODE_PRIVATE) }
    var setupDone by remember { mutableStateOf(firstRun.getBoolean("done", false) || locationOnboarding.getBoolean("requested", false)) }
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor = palette.background.toArgb()
            window.navigationBarColor = palette.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = palette) {
        if (update.required) { RequiredUpdateScreen(update, onUpdate); return@MaterialTheme }
        // The update pop-up waits until Home has settled, and never shows over setup, a window, a spot, another
        // notice or while a trip is under way (docs/in-app-updates.md).
        val tripUnderWay = state.tripBackBy?.isAfter(java.time.Instant.now()) == true
        LaunchedEffect(update.availableBuild, state.tab, setupDone, state.showResults, state.selectedSpot, state.sharedWindowNotice, tripUnderWay) {
            if (update.availableBuild == null || state.tab != 0 || !setupDone || state.showResults || state.selectedSpot != null
                || state.sharedWindowNotice != null || tripUnderWay) return@LaunchedEffect
            delay(2_500)
            updatePrompt = AppUpdates.claimPrompt()
        }
        LaunchedEffect(update.downloaded) {
            if (update.downloaded && snackbars.showSnackbar("Update downloaded", actionLabel = "Restart", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                AppUpdates.restartNow()
            }
        }
        updatePrompt?.let { number ->
            UpdatePromptSheet(update, number, onUpdate = { updatePrompt = null; onUpdate() }, onLater = { updatePrompt = null; AppUpdates.later() })
        }
        state.sharedWindowNotice?.let { notice ->
            LaunchedEffect(notice) { AppUpdates.noteOtherNotice() }
            AlertDialog(onDismissRequest = vm::dismissSharedWindowNotice,
                confirmButton = { TextButton(onClick = vm::dismissSharedWindowNotice) { Text("OK") } },
                title = { Text("Shared window") }, text = { Text(notice) })
        }
        if (!setupDone && !state.showResults && state.selectedSpot == null) FirstRunSetup(state, vm) {
            firstRun.edit().putBoolean("done", true).apply()
            // Home asks for location by itself only when it was never asked; the setup has covered that.
            locationOnboarding.edit().putBoolean("requested", true).apply()
            setupDone = true
        } else {
        BackHandler(enabled = vm.canGoBack()) { vm.goBack() }
        Scaffold(containerColor = palette.background, snackbarHost = { SnackbarHost(snackbars) }, bottomBar = {
            if (!state.showResults && state.selectedSpot == null && !imeOpen) NavigationBar(containerColor = palette.surface) {
                val destinations = listOf(
                    Triple(Icons.Default.Home, "Home", 0),
                    Triple(Icons.Default.Place, "Spots", 1),
                    Triple(Icons.Default.Waves, "Tide", 2),
                    Triple(Icons.Default.WbSunny, "Weather", 11),
                    Triple(Icons.Default.MoreHoriz, "More", 6)
                )
                destinations.forEach { (icon, label, tab) ->
                    NavigationBarItem(
                        selected = if (tab == 6) state.tab in 3..10 || state.tab == 12 else state.tab == tab,
                        onClick = { vm.selectTab(tab) },
                        icon = {
                            if (tab == 6 && (update.remindersDone || update.downloaded)) BadgedBox(badge = { Badge() }) { Icon(icon, contentDescription = "More, update available") }
                            else Icon(icon, contentDescription = null)
                        },
                        label = { Text(label) }
                    )
                }
            }
        }) { padding ->
            val modifier = Modifier.padding(padding)
            when (state.tab) {
                0 -> HomeScreen(modifier, state, vm)
                1 -> MapScreen(modifier, state, vm)
                2 -> TideScreen(modifier, state, vm)
                3 -> MoreDetail("Trips", { vm.selectTab(6) }, modifier) { TripsScreen(Modifier, state, vm) }
                4 -> MoreDetail("Rules", { vm.selectTab(6) }, modifier) { RulesScreen(Modifier, state, vm) }
                5 -> MoreDetail("Account", { vm.selectTab(6) }, modifier) { AccountScreen(Modifier, state, vm) }
                7 -> MoreDetail("Settings", { vm.selectTab(6) }, modifier) { SettingsScreen(Modifier, vm::selectTab) }
                8 -> MoreDetail("Feedback", { vm.selectTab(7) }, modifier) { FeedbackScreen(Modifier, state, vm) }
                9 -> MoreDetail("Terms & privacy", { vm.selectTab(7) }, modifier) { TermsPrivacyScreen(Modifier) }
                10 -> MoreDetail("Appearance", { vm.selectTab(7) }, modifier) {
                    AppearanceScreen(Modifier, appearance) { choice ->
                        appearance = choice
                        preferences.edit().putString("mode", choice.name).apply()
                    }
                }
                11 -> WeatherScreen(modifier)
                12 -> MoreDetail("Catch log", { vm.selectTab(6) }, modifier) { CatchLogScreen(Modifier, state, vm) }
                else -> MoreScreen(modifier, state.account?.user?.displayName, update, { if (update.downloaded) AppUpdates.restartNow() else onUpdate() }, vm::selectTab)
            }
        }
        if (state.showResults) ResultsScreen(state, vm)
        state.selectedSpot?.let { SpotDetailScreen(it, state.savedSpots.contains(recommendationKey(it)), vm) }
        }
    }
}

@Composable
private fun MoreDetail(title: String, back: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to More") }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun MoreScreen(modifier: Modifier, name: String?, update: AppUpdates.State, onUpdate: () -> Unit, onNavigate: (Int) -> Unit) {
    val context = LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(2.dp))
        Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        name?.let { Text("Signed in as $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (update.available) Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            MoreItem(Icons.Default.SystemUpdate, "Update available", updateRowDetail(update), onClick = onUpdate)
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            MoreItem(Icons.Default.AccountCircle, "Account", "Profile and sign in") { onNavigate(5) }
        }
        Spacer(Modifier.height(2.dp))
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            MoreItem(Icons.Default.CalendarMonth, "Trips", "Saved spots and active plans") { onNavigate(3) }
            HorizontalDivider(Modifier.padding(start = 64.dp))
            MoreItem(Icons.Default.Phishing, "Catch log", "Your catches, sizes and patterns") { onNavigate(12) }
            HorizontalDivider(Modifier.padding(start = 64.dp))
            MoreItem(Icons.Default.MenuBook, "Fishing rules", "Sizes, limits and local restrictions") { onNavigate(4) }
        }
        Spacer(Modifier.height(2.dp))
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            MoreItem(Icons.Default.Settings, "Settings", "Appearance, feedback, terms and privacy") { onNavigate(7) }
            HorizontalDivider(Modifier.padding(start = 64.dp))
            MoreItem(Icons.Default.Group, "Share Fishdays - NZ", "Tell a friend about the app", trailingIcon = Icons.Default.Share) { shareApp(context) }
        }
    }
}

/** Opens the system share sheet with a short invitation and the app's Google Play link. */
private fun shareApp(context: Context) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, AppShare.SUBJECT)
        putExtra(Intent.EXTRA_TEXT, AppShare.text())
    }
    try { context.startActivity(Intent.createChooser(send, AppShare.SUBJECT)) }
    catch (_: ActivityNotFoundException) { /* No app can share text on this device. */ }
}

@Composable
private fun SettingsScreen(modifier: Modifier, onNavigate: (Int) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            MoreItem(Icons.Default.MenuBook, "Terms & privacy", "How the app works and uses your information") { onNavigate(9) }
            HorizontalDivider(Modifier.padding(start = 64.dp))
            MoreItem(Icons.Default.Email, "Feedback", "Report a problem or share an idea") { onNavigate(8) }
            HorizontalDivider(Modifier.padding(start = 64.dp))
            MoreItem(Icons.Default.SettingsBrightness, "Appearance", "Light, dark or device setting") { onNavigate(10) }
        }
    }
}

@Composable
private fun AppearanceScreen(modifier: Modifier, selectedAppearance: Appearance, onAppearance: (Appearance) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Appearance", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Appearance.entries.forEachIndexed { index, option ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 64.dp))
                val icon = when (option) { Appearance.SYSTEM -> Icons.Default.SettingsBrightness; Appearance.LIGHT -> Icons.Default.LightMode; Appearance.DARK -> Icons.Default.DarkMode }
                ListItem(
                    headlineContent = { Text(option.label) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    trailingContent = { RadioButton(selected = selectedAppearance == option, onClick = null) },
                    modifier = Modifier.fillMaxWidth().clickable { onAppearance(option) }
                )
            }
        }
        Text("Use device setting follows your phone’s appearance.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TermsPrivacyScreen(modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    var analyticsEnabled by remember { mutableStateOf(PrivacyPreferences.analyticsEnabled()) }
    var firebaseEnabled by remember { mutableStateOf(PrivacyPreferences.firebaseEnabled()) }
    var photoConsentRemembered by remember { mutableStateOf(PrivacyPreferences.photoConsentRemembered()) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Terms & privacy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("A short guide to using Fishdays - NZ and understanding the information it uses.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Terms of use", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Fishing windows, tide times, fish identification and rule summaries help you plan. They can be incomplete or out of date. Check the current MPI rules and local access conditions before fishing.")
                Text("You are responsible for following applicable fishing and access rules.")
            }
        }
        // The one place the app credits its data; the screens themselves stay free of source names. The iPhone app matches.
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Data sources", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                listOf("Weather and waves: Open-Meteo.com (CC BY 4.0)", "Tide times: Toitū Te Whenua LINZ (CC BY 4.0)",
                    "Maps: OpenFreeMap, © OpenStreetMap contributors and LINZ", "Boat ramps and wharves: © OpenStreetMap contributors",
                    "Marine protected areas: Department of Conservation", "Fishing rules: summarised from MPI")
                    .forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Privacy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("If you allow location access, the app uses your position to centre the map and find nearby information. You can change location permission in your phone’s settings.")
                Text("A photo is uploaded only after you agree. It passes through Cloudflare to OpenAI for an AI fish suggestion. Original photo metadata is removed. OpenAI response storage is disabled; provider security records can remain for up to 30 days, or longer where legally required.")
                Text("If you sign in or send feedback, your account details and feedback are sent to the Fishdays - NZ service. Signing out removes the saved session from this device.")
                Text("Weather requests send coordinates to Open-Meteo. Map and tide providers receive connection information and the places you request. You can choose locations manually.")
                Text("Your catch log, with its photos and positions, stays on this phone unless you turn on sync on the Account page; photos never sync. Saving a catch sends its place and time to Open-Meteo for the weather then; tides come from LINZ.")
                Text("Publisher: Tristan · tc199558@gmail.com")
                TextButton(onClick = { uriHandler.openUri(PrivacyLinks.policy) }) { Text("Read full privacy policy") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Anonymous usage statistics", fontWeight = FontWeight.SemiBold)
                        Text("On by default. Shares which screens you use and how searches and fish photos go, with your device model, Android version, app version, language and a random identifier made on this phone, whether or not you are signed in. Fishdays - NZ also counts your API requests against that identifier. No location, photos, email or typed text is included. Turning this off erases what we stored for this identifier and resets it.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = analyticsEnabled, onCheckedChange = {
                        analyticsEnabled = it
                        PrivacyPreferences.setAnalyticsEnabled(it)
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Google Analytics (Firebase)", fontWeight = FontWeight.SemiBold)
                        Text("Also share app interactions, device information, app-instance identifiers and approximate location from your IP address with Google. Off unless you turn it on.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = firebaseEnabled, onCheckedChange = {
                        firebaseEnabled = it
                        PrivacyPreferences.setFirebaseEnabled(it)
                    })
                }
                if (photoConsentRemembered) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Fish photos", fontWeight = FontWeight.SemiBold)
                    Text("You chose not to be asked before a photo is sent to OpenAI for identification.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        PrivacyPreferences.setPhotoConsentRemembered(false)
                        photoConsentRemembered = false
                    }, contentPadding = PaddingValues(0.dp)) { Text("Ask me before sending photos again") }
                }
                Text("Fishdays - NZ is independent and is not affiliated with or endorsed by the New Zealand Government, MPI or LINZ.")
            }
        }
    }
}

@Composable
private fun MoreItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String,
                     trailingIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.ChevronRight, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(0.dp)) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(detail) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(trailingIcon, contentDescription = null) }
        )
    }
}

val Navy: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
val Seafoam: Color
    @Composable get() = MaterialTheme.colorScheme.primaryContainer
val Cream: Color
    @Composable get() = MaterialTheme.colorScheme.background
val Orange: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary
