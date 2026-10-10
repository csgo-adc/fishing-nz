package nz.fishingnz.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nz.fishingnz.app.model.OutlookTone
import nz.fishingnz.app.model.Recommendation
import nz.fishingnz.app.model.keyTiles
import nz.fishingnz.app.model.outlookTone
import nz.fishingnz.app.model.watchOuts
import nz.fishingnz.app.model.windowDayAndHours
import nz.fishingnz.app.model.windowMood

/** Background and text colours for an outlook or a key number that needs attention, readable in light and dark. */
private data class Tone(val background: Color, val content: Color)

@Composable private fun tone(value: OutlookTone): Tone {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    return when (value) {
        OutlookTone.GOOD -> if (dark) Tone(Color(0xFF1F3A26), Color(0xFFA8E0B4)) else Tone(Color(0xFFE3F4E5), Color(0xFF1E6B34))
        OutlookTone.CAUTION -> if (dark) Tone(Color(0xFF3D2E12), Color(0xFFF5C77A)) else Tone(Color(0xFFFCEFD8), Color(0xFF8A5300))
        OutlookTone.POOR -> if (dark) Tone(Color(0xFF3F1D19), Color(0xFFF3A99F)) else Tone(Color(0xFFFBE4E1), Color(0xFF9B2C1F))
        OutlookTone.UNKNOWN -> Tone(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The day, the two-hour window in large type, and the coloured outlook badge. */
@Composable internal fun WindowTimeHeader(item: Recommendation) {
    val (day, hours) = item.windowDayAndHours()
    val mood = item.windowMood
    val badge = tone(outlookTone(mood.label))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(day, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Text(hours, color = Navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Text("${mood.emoji} ${mood.label}", color = badge.content, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, maxLines = 1,
            modifier = Modifier.background(badge.background, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp)
                .clearAndSetSemantics { contentDescription = "Window outlook: ${mood.label}" })
    }
}

/** Three key numbers; one that needs attention turns amber. */
@Composable internal fun WindowKeyTiles(item: Recommendation) {
    val caution = tone(OutlookTone.CAUTION)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item.keyTiles().forEach { tile ->
            val background = if (tile.concern) caution.background else MaterialTheme.colorScheme.surfaceVariant
            val strong = if (tile.concern) caution.content else Navy
            val soft = if (tile.concern) caution.content else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.weight(1f).fillMaxHeight().background(background, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 8.dp)
                .clearAndSetSemantics { contentDescription = "${tile.label} ${tile.value} ${tile.detail}" + if (tile.concern) ", needs attention" else "" }) {
                Text(tile.label, color = soft, style = MaterialTheme.typography.labelMedium)
                Text(tile.value, color = strong, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (tile.detail.isNotEmpty()) Text(tile.detail, color = soft, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            }
        }
    }
}

/** The amber box: only shown when something needs attention. */
@Composable internal fun WindowWatchOut(item: Recommendation) {
    val lines = item.watchOuts()
    if (lines.isEmpty()) return
    val caution = tone(OutlookTone.CAUTION)
    Column(Modifier.fillMaxWidth().background(caution.background, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = caution.content, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(line, color = caution.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
