package nz.fishingnz.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import nz.fishingnz.app.data.DailyWeather
import nz.fishingnz.app.data.HourlyWeather
import nz.fishingnz.app.data.LocalWeatherForecast
import nz.fishingnz.app.data.WeatherRepository
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.nearestCityName
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val hourFormatter = DateTimeFormatter.ofPattern("ha", Locale.ENGLISH)
private val dayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
private val refreshedFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

@Composable
fun WeatherScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { WeatherRepository() }
    var point by remember { mutableStateOf<GeoPoint?>(null) }
    var forecast by remember { mutableStateOf<LocalWeatherForecast?>(null) }
    var locationLoading by remember { mutableStateOf(false) }
    var weatherLoading by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var weatherMessage by remember { mutableStateOf<String?>(null) }
    var refreshIndex by remember { mutableIntStateOf(0) }

    fun locate() {
        locationLoading = true
        locationMessage = null
        requestCurrentLocation(context,
            onLocation = { found ->
                locationLoading = false
                point = found
                refreshIndex++
            },
            onUnavailable = {
                locationLoading = false
                point = null
                forecast = null
                locationMessage = "Your current location is unavailable. Check location services and try again."
            })
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true || results[Manifest.permission.ACCESS_COARSE_LOCATION] == true) locate()
        else {
            point = null
            forecast = null
            locationMessage = "Allow location access to see the weather where you are."
        }
    }
    fun requestWeatherLocation() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) locate()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    LaunchedEffect(Unit) { requestWeatherLocation() }
    LaunchedEffect(point, refreshIndex) {
        val selectedPoint = point ?: return@LaunchedEffect
        weatherLoading = true
        weatherMessage = null
        try {
            forecast = repository.forecast(selectedPoint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            weatherMessage = "The forecast could not be loaded. Please try again."
        } finally {
            weatherLoading = false
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Weather", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(point?.let { name ->
                        val city = nearestCityName(name)
                        if (city == "Current location") "At your current location" else "Near $city · current location"
                    } ?: "At your current location", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = ::requestWeatherLocation, enabled = !locationLoading && !weatherLoading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh current location and weather")
                }
            }
        }
        if (locationLoading || (weatherLoading && forecast == null)) item {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        locationMessage?.let { message -> item { WeatherNotice(message, ::requestWeatherLocation) } }
        weatherMessage?.let { message -> item { WeatherNotice(message) { refreshIndex++ } } }
        forecast?.let { data ->
            item { WeatherNowCard(data, weatherLoading) }
            item { WeatherHourlyCard(data.hourly) }
            item { WeatherWeekCard(data.daily) }
            item {
                Text(
                    "Forecast: Open-Meteo, refreshed ${data.fetchedAt.atZone(data.timeZone).format(refreshedFormatter)}. Conditions can differ at exposed fishing spots; check official marine warnings before heading out.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WeatherNotice(message: String, retry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onSecondaryContainer)
            OutlinedButton(onClick = retry) { Text("Try again") }
        }
    }
}

@Composable
private fun WeatherNowCard(forecast: LocalWeatherForecast, updating: Boolean) {
    val now = forecast.current
    val heroBrush = Brush.linearGradient(listOf(Color(0xFF1B579B), Color(0xFF397FC6)))
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().background(heroBrush).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Current forecast", color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
                Text(now.observedAt.format(refreshedFormatter), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(weatherIcon(now.code, now.isDay), null, tint = Color.White, modifier = Modifier.size(52.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("${now.temperatureC.roundToInt()}°", color = Color.White, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                    Text(weatherDescription(now.code), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (updating) Text("Updating…", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WeatherMetric("Feels like", "${now.feelsLikeC.roundToInt()}°C", Modifier.weight(1f))
                WeatherMetric("Wind", "${now.windKmh.roundToInt()} km/h", Modifier.weight(1f))
                WeatherMetric("Rain", "${"%.1f".format(Locale.US, now.precipitationMm)} mm", Modifier.weight(1f))
            }
            Text("Wind from ${windDirection(now.windDirectionDegrees)} · gusts ${now.windGustKmh.roundToInt()} km/h · humidity ${now.humidityPercent}%",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WeatherMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WeatherHourlyCard(hours: List<HourlyWeather>) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Next 24 hours", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp))
            if (hours.isEmpty()) Text("Hourly forecast is unavailable.", modifier = Modifier.padding(horizontal = 18.dp))
            else Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hours.forEachIndexed { index, hour ->
                    Column(
                        Modifier.width(76.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).padding(vertical = 12.dp, horizontal = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(if (index == 0) "Now" else hour.at.format(hourFormatter), style = MaterialTheme.typography.labelMedium)
                        Icon(weatherIcon(hour.code, hour.at.hour in 7..18), contentDescription = weatherDescription(hour.code), tint = MaterialTheme.colorScheme.primary)
                        Text("${hour.temperatureC.roundToInt()}°", fontWeight = FontWeight.Bold)
                        Text("Rain ${hour.rainChancePercent}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${hour.windKmh.roundToInt()} km/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherWeekCard(days: List<DailyWeather>) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("7-day forecast", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            days.forEachIndexed { index, day ->
                if (index > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (index == 0) "Today" else day.date.format(dayFormatter), modifier = Modifier.width(50.dp), fontWeight = FontWeight.SemiBold)
                    Icon(weatherIcon(day.code, true), contentDescription = weatherDescription(day.code), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(weatherDescription(day.code), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text("Rain ${day.rainChancePercent}% · wind to ${day.windMaxKmh.roundToInt()} km/h",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${day.highC.roundToInt()}°", fontWeight = FontWeight.Bold)
                    Text("${day.lowC.roundToInt()}°", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

internal fun weatherDescription(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Mainly clear"
    2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    in 51..57 -> "Drizzle"
    in 61..67 -> "Rain"
    in 71..77 -> "Snow"
    in 80..82 -> "Showers"
    85, 86 -> "Snow showers"
    95, 96, 99 -> "Thunderstorms"
    else -> "Variable conditions"
}

private fun weatherIcon(code: Int, day: Boolean): ImageVector = when (code) {
    0, 1 -> if (day) Icons.Default.WbSunny else Icons.Default.NightsStay
    2, 3, 45, 48 -> Icons.Default.Cloud
    in 51..57 -> Icons.Default.Grain
    in 61..67, in 80..82 -> Icons.Default.WaterDrop
    in 71..77, 85, 86 -> Icons.Default.AcUnit
    95, 96, 99 -> Icons.Default.Thunderstorm
    else -> Icons.Default.Cloud
}

internal fun windDirection(degrees: Int): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return directions[((degrees % 360 + 360) % 360 + 22) / 45 % 8]
}
