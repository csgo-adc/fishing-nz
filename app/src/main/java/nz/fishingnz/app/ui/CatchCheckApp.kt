package nz.fishingnz.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.fishingnz.app.model.*
import nz.fishingnz.app.viewmodel.FishingViewModel

@Composable
fun CatchCheckApp(vm: FishingViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Orange, background = Cream)) {
        Scaffold(containerColor = Cream, bottomBar = {
            NavigationBar(containerColor = androidx.compose.ui.graphics.Color.White) {
                listOf(Icons.Default.NearMe to "Home", Icons.Default.Map to "Map", Icons.Default.Waves to "Tide", Icons.Default.CalendarMonth to "Trips", Icons.Default.MenuBook to "Rules").forEachIndexed { index, item ->
                    NavigationBarItem(selected = state.tab == index, onClick = { vm.selectTab(index) }, icon = { Icon(item.first, null) }, label = { Text(item.second) })
                }
            }
        }) { padding ->
            when (state.tab) {
                0 -> HomeScreen(Modifier.padding(padding), state, vm)
                1 -> MapScreen(Modifier.padding(padding), state, vm)
                2 -> TideScreen(Modifier.padding(padding), state, vm)
                3 -> TripsScreen(Modifier.padding(padding), state, vm)
                else -> RulesScreen(Modifier.padding(padding))
            }
        }
        if (state.showResults) ResultsScreen(state, vm)
        state.selectedSpot?.let { SpotDetailScreen(it, state.savedSpots.contains(it.name), vm) }
    }
}

val Navy = androidx.compose.ui.graphics.Color(0xFF123B43)
val Seafoam = androidx.compose.ui.graphics.Color(0xFFDCEFE7)
val Cream = androidx.compose.ui.graphics.Color(0xFFF7F8F4)
val Orange = androidx.compose.ui.graphics.Color(0xFFE4784A)
