package nz.fishingnz.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.fishingnz.app.data.MarineProtection
import nz.fishingnz.app.data.ProtectionKind
import nz.fishingnz.app.data.ProtectionNotice
import nz.fishingnz.app.model.GeoPoint

/** The bundled DOC boundaries, read off the main thread; null until loaded or when the file cannot be read. */
@Composable
internal fun rememberMarineProtection(): MarineProtection.Catalogue? {
    val context = LocalContext.current
    val catalogue by produceState(MarineProtection.loadedOrNull()) {
        if (value == null) value = withContext(Dispatchers.IO) { runCatching { MarineProtection.load(context.applicationContext) }.getOrNull() }
    }
    return catalogue
}

/**
 * A warning when [point] is inside or near a marine reserve or Hauraki Gulf protection area. Shows nothing elsewhere.
 * [compact] drops the source note and links, for the map's place card. The iPhone app shows the same card.
 */
@Composable
internal fun MarineProtectionNotice(point: GeoPoint?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val catalogue = rememberMarineProtection() ?: return
    if (point == null || !point.latitude.isFinite() || !point.longitude.isFinite()) return
    val notices: List<ProtectionNotice> = MarineProtection.notices(point, catalogue.areas)
    if (notices.isEmpty()) return
    val uriHandler = LocalUriHandler.current
    val noFishing = notices.any { it.area.kind.noFishing }
    // The window cards' "poor" and "caution" colours, readable in light and dark.
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val container = when { noFishing && dark -> Color(0xFF3F1D19); noFishing -> Color(0xFFFBE4E1); dark -> Color(0xFF3D2E12); else -> Color(0xFFFCEFD8) }
    val content = when { noFishing && dark -> Color(0xFFF3A99F); noFishing -> Color(0xFF9B2C1F); dark -> Color(0xFFF5C77A); else -> Color(0xFF8A5300) }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(container, content)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (notices.any { it.inside }) "Inside a marine protected area" else "Marine protected area nearby", fontWeight = FontWeight.Bold)
            notices.forEach { Text("${it.emoji} ${it.text}", style = MaterialTheme.typography.bodySmall) }
            if (!compact) {
                Text(MarineProtection.sourceLine(catalogue), style = MaterialTheme.typography.labelSmall)
                val gulf = notices.any { it.area.kind != ProtectionKind.RESERVE }
                TextButton(onClick = { uriHandler.openUri(if (gulf) MarineProtection.HAURAKI_GULF_URL else MarineProtection.DOC_URL) },
                    contentPadding = PaddingValues(0.dp)) {
                    Text(if (gulf) "DOC Hauraki Gulf protection maps" else "DOC marine reserves", color = content, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
