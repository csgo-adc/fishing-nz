package nz.fishingnz.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.fishingnz.app.data.BoatRamp
import nz.fishingnz.app.data.BoatRamps
import nz.fishingnz.app.data.LinzTideSource
import nz.fishingnz.app.model.GeoPoint
import nz.fishingnz.app.model.nearestTideStation
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The bundled ramps, read off the main thread; null until loaded. */
@Composable
internal fun rememberBoatRamps(): List<BoatRamp>? {
    val context = LocalContext.current
    val ramps by produceState(BoatRamps.loadedOrNull()) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { BoatRamps.load(context.applicationContext) }.getOrNull() }
    }
    return ramps
}

/**
 * "Boat ramps nearby" for a boat window: the three nearest public ramps within 25 km, and a note when a low tide falls
 * within an hour of the trip. The iPhone app shows the same card.
 */
@Composable
internal fun NearbyRampsCard(point: GeoPoint, start: Instant?, end: Instant?) {
    val ramps = rememberBoatRamps() ?: return
    val nearby = BoatRamps.nearest(point, ramps)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lowTide by produceState<String?>(null, point, start, end) {
        if (start == null || end == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val zone = ZoneId.of("Pacific/Auckland")
                val day = start.atZone(zone).toLocalDate()
                val lows = LinzTideSource.predictions(nearestTideStation(point), day, end.atZone(zone).toLocalDate()).filter { !it.high }.map { it.at }
                BoatRamps.lowTideNote(lows, start, end)
            }.getOrNull()
        }
    }
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Boat ramps nearby", fontWeight = FontWeight.Bold, color = Navy)
            if (nearby.isEmpty()) Text("No public boat ramp is mapped within 25 km.", style = MaterialTheme.typography.bodySmall)
            nearby.forEach { (ramp, km) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ramp.label, color = Navy, fontWeight = FontWeight.SemiBold)
                        Text(BoatRamps.distanceLabel(km) + " " + BoatRamps.direction(point, ramp.point) + when (ramp.fee) { true -> " · fee"; false -> " · free"; null -> "" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        val destination = String.format(Locale.US, "%.5f,%.5f", ramp.point.latitude, ramp.point.longitude)
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$destination")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: android.content.ActivityNotFoundException) { }
                    }) { Text("Directions") }
                }
            }
            lowTide?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            Text("${BoatRamps.CAUTION} ${BoatRamps.CREDIT}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { uriHandler.openUri(BoatRamps.CREDIT_URL) }, contentPadding = PaddingValues(0.dp)) { Text("OpenStreetMap licence") }
        }
    }
}
