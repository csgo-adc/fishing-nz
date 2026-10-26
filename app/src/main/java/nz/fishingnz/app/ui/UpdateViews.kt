package nz.fishingnz.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nz.fishingnz.app.data.AppUpdates
import kotlin.math.roundToInt

/** "Version 1.0.61 · 4.9 MB", or as much of it as is known. */
internal fun updateSummary(state: AppUpdates.State): String = listOfNotNull(
    state.availableVersion?.let { "Version $it" },
    state.downloadBytes.takeIf { it > 0 }?.let { "%.1f MB".format(it / 1_048_576.0) },
).joinToString(" · ")

/** The update pop-up on Home: what's new, Update and Later. The third and last one says it won't ask again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdatePromptSheet(state: AppUpdates.State, promptNumber: Int, onUpdate: () -> Unit, onLater: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onLater, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text("Update available", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            updateSummary(state).takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.notes.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.notes.take(4).forEach { Text("• $it") }
            } else Text("A newer version of Fishdays - NZ is ready.")
            Text(
                if (state.inApp) "It downloads while you keep using the app and installs when you leave it."
                else "Opens Google Play to update.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (promptNumber >= nz.fishingnz.app.data.UpdatePolicy.MAX_PROMPTS) Text(
                "We won't remind you again. You can update any time from More.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) { Text("Update") }
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) { Text("Later") }
        }
    }
}

/** Shown instead of the app when this version is older than the service supports. There is no Later. */
@Composable
internal fun RequiredUpdateScreen(state: AppUpdates.State, onUpdate: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text("Update required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text("This version of Fishdays - NZ no longer works with our service. Update to keep using the app.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            updateSummary(state).takeIf { it.isNotEmpty() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.downloading) Text("Downloading… ${(state.progress * 100).roundToInt()}%")
            Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) { Text(if (state.downloaded) "Restart to update" else "Update") }
        }
    }
}

/** The More row's second line: where the update is up to. */
internal fun updateRowDetail(state: AppUpdates.State): String = when {
    state.downloaded -> "Downloaded · tap to restart and install"
    state.downloading -> "Downloading… ${(state.progress * 100).roundToInt()}%"
    else -> listOfNotNull(state.availableVersion?.let { "Version $it" }, "tap to update").joinToString(" · ")
}
