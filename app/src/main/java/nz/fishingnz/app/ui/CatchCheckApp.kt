package nz.fishingnz.app.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.fishingnz.app.model.recommendationKey
import nz.fishingnz.app.viewmodel.FishingViewModel

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
fun CatchCheckApp(vm: FishingViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val preferences = remember(context) { context.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE) }
    var appearance by remember { mutableStateOf(runCatching { Appearance.valueOf(preferences.getString("mode", "SYSTEM") ?: "SYSTEM") }.getOrDefault(Appearance.SYSTEM)) }
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val palette = if (dark) darkPalette else lightPalette
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
        BackHandler(enabled = vm.canGoBack()) { vm.goBack() }
        Scaffold(containerColor = palette.background, bottomBar = {
            NavigationBar(containerColor = palette.surface) {
                val destinations = listOf(
                    Triple(Icons.Default.Home, "Home", 0),
                    Triple(Icons.Default.Map, "Map", 1),
                    Triple(Icons.Default.Waves, "Tide", 2),
                    Triple(Icons.Default.MoreHoriz, "More", 6)
                )
                destinations.forEach { (icon, label, tab) ->
                    NavigationBarItem(
                        selected = if (tab == 6) state.tab >= 3 else state.tab == tab,
                        onClick = { vm.selectTab(tab) },
                        icon = { Icon(icon, contentDescription = null) },
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
                4 -> MoreDetail("Rules", { vm.selectTab(6) }, modifier) { RulesScreen(Modifier) }
                5 -> MoreDetail("Account", { vm.selectTab(6) }, modifier) { AccountScreen(Modifier, state, vm) }
                else -> MoreScreen(modifier, state.account?.user?.displayName, appearance, vm::selectTab) { choice ->
                    appearance = choice
                    preferences.edit().putString("mode", choice.name).apply()
                }
            }
        }
        if (state.showResults) ResultsScreen(state, vm)
        state.selectedSpot?.let { SpotDetailScreen(it, state.savedSpots.contains(recommendationKey(it)), vm) }
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
private fun MoreScreen(modifier: Modifier, name: String?, selectedAppearance: Appearance, onNavigate: (Int) -> Unit, onAppearance: (Appearance) -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(2.dp))
        Text("More", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(name?.let { "Signed in as $it" } ?: "Your fishing tools and preferences", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            MoreItem(Icons.Default.CalendarMonth, "Trips & saved spots", "Keep your plans together") { onNavigate(3) }
            HorizontalDivider(Modifier.padding(start = 64.dp))
            MoreItem(Icons.Default.MenuBook, "Fishing rules", "Sizes, limits and local restrictions") { onNavigate(4) }
            HorizontalDivider(Modifier.padding(start = 64.dp))
            MoreItem(Icons.Default.AccountCircle, "Account", "Profile, access and feedback") { onNavigate(5) }
        }
        Text("APPEARANCE", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Appearance.entries.forEach { option ->
                val icon = when (option) { Appearance.SYSTEM -> Icons.Default.SettingsBrightness; Appearance.LIGHT -> Icons.Default.LightMode; Appearance.DARK -> Icons.Default.DarkMode }
                ListItem(
                    headlineContent = { Text(option.label) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    trailingContent = { RadioButton(selected = selectedAppearance == option, onClick = null) },
                    modifier = Modifier.fillMaxWidth().clickable { onAppearance(option) }
                )
            }
        }
    }
}

@Composable
private fun MoreItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(0.dp)) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(detail) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) }
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
