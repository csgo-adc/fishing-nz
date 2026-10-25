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
import nz.fishingnz.app.data.BoatRamps
import nz.fishingnz.app.data.ShoreStructure
import nz.fishingnz.app.data.ShoreStructures
import nz.fishingnz.app.model.GeoPoint
import java.util.Locale

/** The bundled wharves, breakwaters and groynes, read off the main thread; null until loaded. */
@Composable
internal fun rememberShoreStructures(): List<ShoreStructure>? {
    val context = LocalContext.current
    val structures by produceState(ShoreStructures.loadedOrNull()) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { ShoreStructures.load(context.applicationContext) }.getOrNull() }
    }
    return structures
}

/** "Wharves and breakwaters nearby" for a land window: the three nearest within 10 km. The iPhone app shows the same card. */
@Composable
internal fun NearbyStructuresCard(point: GeoPoint) {
    val structures = rememberShoreStructures() ?: return
    val nearby = ShoreStructures.nearest(point, structures)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Wharves and breakwaters nearby", fontWeight = FontWeight.Bold, color = Navy)
            if (nearby.isEmpty()) Text("No wharf or breakwater is mapped within 10 km.", style = MaterialTheme.typography.bodySmall)
            nearby.forEach { (structure, km) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(structure.label, color = Navy, fontWeight = FontWeight.SemiBold)
                        Text(BoatRamps.distanceLabel(km) + " " + BoatRamps.direction(point, structure.point) + " · " + structure.kindLabel,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        val destination = String.format(Locale.US, "%.5f,%.5f", structure.point.latitude, structure.point.longitude)
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$destination")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        catch (_: android.content.ActivityNotFoundException) { }
                    }) { Text("Directions") }
                }
            }
            Text("${ShoreStructures.CAUTION} ${ShoreStructures.CREDIT}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { uriHandler.openUri(ShoreStructures.CREDIT_URL) }, contentPadding = PaddingValues(0.dp)) { Text("OpenStreetMap licence") }
        }
    }
}
