package nz.fishingnz.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nz.fishingnz.app.model.FishCheck
import nz.fishingnz.app.model.FishRuleMatch
import nz.fishingnz.app.model.isNearRaglan
import nz.fishingnz.app.viewmodel.FishingUiState

@Composable
internal fun FishResultCard(result: FishCheck, state: FishingUiState, retryRules: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val area = fishingRulesAreas.firstOrNull { it.id == state.fishRulesAreaId }
    val nearRaglanWest = state.fishRulesAreaIsSuggested && result.areaId == "auckland-kermadec" && isNearRaglan(state.deviceLocation)
    val westSnapperRows = result.fishRules.filter { it.species.contains("Auckland West", ignoreCase = true) }
    val shownRules = if (nearRaglanWest && result.commonName.equals("Snapper", ignoreCase = true) && westSnapperRows.isNotEmpty())
        westSnapperRows else result.fishRules
    val officialUrl = area?.officialUrl ?: result.rulesSourceUrl
        ?: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules"

    Card(colors = CardDefaults.cardColors(Seafoam), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("IDENTIFICATION", color = Orange, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(cleanIdentificationText(result.commonName), color = Navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (result.scientificName.isNotBlank()) Text(cleanIdentificationText(result.scientificName), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text(if (result.isFish) "${result.confidence}% AI confidence" else "Not a fish",
                color = Orange, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp))

            if (result.visibleClues.isNotBlank()) FishDescription("VISIBLE CLUES", cleanIdentificationText(result.visibleClues))
            if (result.otherPossibilities.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("OTHER POSSIBILITIES", color = Orange, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(result.otherPossibilities.joinToString(" · ") { cleanIdentificationText(it) }, color = Navy, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (result.identificationNote.isNotBlank() && !result.identificationNote.equals("This is not a fish.", ignoreCase = true))
                FishDescription("IDENTIFICATION NOTE", cleanIdentificationText(result.identificationNote), highlighted = true)

            if (result.isFish) {
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("MPI RULES · ${area?.name ?: "Choose an area"}", color = Navy,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    result.rulesReviewedAt?.let { Text("Reviewed $it", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall) }
                }
                if (nearRaglanWest) Text("Near Raglan: check the Auckland West snapper subarea for the exact catch spot.",
                    color = Navy, style = MaterialTheme.typography.bodySmall)
                when {
                    state.fishRulesLoading -> FishRulesMessage("Loading size and catch limits for this area…")
                    state.fishRulesError != null -> {
                        FishRulesMessage(state.fishRulesError)
                        TextButton(onClick = retryRules) { Text("Try loading rules again") }
                    }
                    result.rulesNeedsReview -> FishRulesMessage("MPI has updated this area's rules. Check the official page for current limits.")
                    state.fishRulesAreaId == null -> FishRulesMessage("Choose the MPI area where this fish was caught to see local limits.")
                    shownRules.isEmpty() -> FishRulesMessage("No reliable simple limit can be shown from this saved table. Check MPI for this species and exact subarea before keeping it.")
                    else -> shownRules.forEach { FishRuleRow(it, officialUrl) }
                }
                Text("Check local closures before keeping a fish.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { uriHandler.openUri(officialUrl) }) { Text("See official MPI rules") }
            }
        }
    }
}

@Composable
private fun FishDescription(label: String, detail: String, highlighted: Boolean = false) {
    var expanded by remember(detail) { mutableStateOf(false) }
    val canExpand = detail.length > if (highlighted) 80 else 150
    Column(
        modifier = if (highlighted) Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(10.dp)
            else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, color = Orange, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        Text(detail, color = if (highlighted) MaterialTheme.colorScheme.onSurfaceVariant else Navy,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (canExpand && !expanded) { if (highlighted) 2 else 3 } else Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis)
        if (canExpand) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
    }
}

@Composable
private fun FishRulesMessage(message: String) {
    Text(message, color = Navy, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(12.dp))
}

@Composable
private fun FishRuleRow(rule: FishRuleMatch, officialUrl: String) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(cleanMpiRuleText(rule.species), color = Navy, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (rule.details.any { it.label.contains("daily limit", ignoreCase = true) || it.label.contains("bag limit", ignoreCase = true) }) {
            Text("Limits vary by subarea. Check the exact catch spot on MPI.", color = Navy,
                style = MaterialTheme.typography.bodySmall)
        } else {
            rule.minimumSize?.let { FishRuleValue(cleanMpiRuleText(rule.minimumSizeLabel ?: "Minimum size"), cleanMpiLimitValue(it)) }
            rule.dailyLimit?.let { FishRuleValue("Daily limit", cleanMpiLimitValue(it)) }
        }
        if (hasMpiFootnote(rule)) {
            Text("MPI footnote applies. Read the full condition before keeping this fish.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uriHandler.openUri(officialUrl) }) { Text("Read MPI footnote") }
        }
    }
}

@Composable
private fun FishRuleValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, color = Navy, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
