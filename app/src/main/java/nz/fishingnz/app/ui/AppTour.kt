package nz.fishingnz.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * The first-launch tour: after the two setup questions, Home dims and lights up one part at a time with a short note
 * and Skip / Back / Next, ending on "You're all set". It runs once; More → Settings → App tour shows it again. The
 * iPhone app has the same steps and words in `AppTour.swift`.
 */
enum class TourStep(val title: String, val body: String) {
    PLACE("Where you fish", "Tap here to change your town or spot, or use your location."),
    TODAY("Today", "The tide, wind, moon and sunset for this place today."),
    BEST("Your best fishing window", "Home finds the best two hours for your plan by itself. Tap the card to see why."),
    PLAN("Your plan", "Switch land or boat and the days here. Edit plan has distance and hours."),
    SHORTCUTS("Shortcuts", "Rules, fish ID, your catch log and trips, one tap away."),
    TABS("Tabs", "Spots has the map. Tide and Weather have the forecasts. More has your account and settings."),
    DONE("You're all set", "Replay this tour any time in More → Settings → App tour.");

    /** The steps that light something up (all but the last). */
    val hasTarget get() = this != DONE
}

object AppTour {
    private const val PREFS = "app_tour"
    private var preferences: SharedPreferences? = null

    /** The step on screen, or null when the tour isn't showing. */
    var step by mutableStateOf<TourStep?>(null)
        private set
    /** Where each lit-up part of Home is, in root coordinates; filled by [tourTarget]. */
    val targets = mutableStateMapOf<TourStep, Rect>()

    fun initialize(context: Context) { preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** The first-launch setup has just finished: the tour shows once Home is on screen. */
    fun markPending() { preferences?.edit()?.putBoolean("pending", true)?.apply() }

    /** Starts the tour if the setup asked for it and it hasn't been shown or skipped yet. */
    fun startIfPending() {
        val prefs = preferences ?: return
        if (step == null && prefs.getBoolean("pending", false) && !prefs.getBoolean("done", false)) step = TourStep.PLACE
    }

    /** Settings → App tour. */
    fun start() { step = TourStep.PLACE }

    fun next() { step = TourStep.entries.getOrNull((step?.ordinal ?: -1) + 1) ?: run { finish(); null } }
    fun back() { step?.let { current -> if (current.ordinal > 0) step = TourStep.entries[current.ordinal - 1] } }

    /** Finish or Skip: the tour doesn't show by itself again. */
    fun finish() {
        step = null
        preferences?.edit()?.putBoolean("pending", false)?.putBoolean("done", true)?.apply()
    }
}

/** Marks the part of Home that [step] lights up. */
fun Modifier.tourTarget(step: TourStep): Modifier = onGloballyPositioned { AppTour.targets[step] = it.boundsInRoot() }

/** The dimmed screen with a lit-up target and the step's note. Drawn above everything while the tour runs. */
@Composable fun TourOverlay() {
    val step = AppTour.step ?: return
    BackHandler { AppTour.finish() }
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val margin = with(density) { 8.dp.toPx() }
    val corner = with(density) { 18.dp.toPx() }
    val hole = AppTour.targets[step]?.takeIf { step.hasTarget }?.translate(-origin)?.inflate(margin)
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot(); size = it.size }
            // Taps on the dimmed part do nothing; only the note's buttons work while the tour shows.
            .pointerInput(Unit) { detectTapGestures { } }
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawBehind {
                drawRect(Color.Black.copy(alpha = 0.58f))
                hole?.let { drawRoundRect(Color.Transparent, topLeft = it.topLeft, size = it.size, cornerRadius = CornerRadius(corner), blendMode = BlendMode.Clear) }
            }
    ) {
        val gap = 14.dp
        // Below the lit-up part when it is in the top half, above it otherwise. Until the overlay has been measured
        // (the first frame) the note waits in the middle.
        val height = size.height.toFloat()
        val placement = when {
            hole == null || height <= 0f -> Modifier.align(Alignment.Center)
            hole.center.y < height / 2f -> Modifier.align(Alignment.TopCenter)
                .padding(top = with(density) { hole.bottom.coerceIn(0f, height).toDp() } + gap)
            else -> Modifier.align(Alignment.BottomCenter)
                .padding(bottom = with(density) { (height - hole.top).coerceIn(0f, height).toDp() } + gap)
        }
        TourCard(step, placement.padding(horizontal = 20.dp))
    }
}

@Composable private fun TourCard(step: TourStep, modifier: Modifier) {
    val shown = TourStep.entries.size
    Card(modifier.widthIn(max = 460.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(6.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${step.ordinal + 1} / $shown", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(step.title, color = Navy, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(step.body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step != TourStep.DONE) TextButton(onClick = AppTour::finish) { Text("Skip") }
                Spacer(Modifier.weight(1f))
                if (step.ordinal > 0) OutlinedButton(onClick = AppTour::back) { Text("Back") }
                Spacer(Modifier.padding(start = 8.dp))
                Button(onClick = AppTour::next) { Text(if (step == TourStep.DONE) "Finish" else "Next") }
            }
        }
    }
}
