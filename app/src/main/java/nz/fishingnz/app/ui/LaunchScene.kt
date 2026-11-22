package nz.fishingnz.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nz.fishingnz.app.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * The animated scene that opens the app on every cold start: dawn over the sea, the app's fish in its tile, waves
 * and the name. On the first launch it settles into the welcome page, whose Get started leads to the two setup
 * questions; otherwise it fades away into the app. The iPhone app plays the same scene (`LaunchScene.swift`).
 * See docs/launch-scene.md.
 */
object LaunchScene {
    /** True from process start until the scene has gone; a rotation or language change doesn't play it again. */
    var playing by mutableStateOf(true)
        private set

    fun finish() { playing = false }

    /** The night sea behind the system splash, the window and the first frame of the scene. */
    val Night = Color(0xFF0A1A3F)
    /** The deep water at the bottom of the scene, used for the navigation bar while it shows. */
    val Deep = Color(0xFF061A7A)
}

private val SkyMid = Color(0xFF163E80)
private val Dawn = Color(0xFFF0A67E)
private val WaveBack = Color(0xFF3E7BD6)
private val WaveMid = Color(0xFF1F4FC4)
private val Sea = Color(0xFF0A2BB0)
private val Foam = Color(0xFFBFE7F5)

// The fish fills this share of the tile's width, as on the iPhone launch screen (tools/export_launch_art.cjs).
private const val FISH_WIDTH = 0.78f
// The settled tile's corner radius as a share of its side.
private const val TILE_CORNER = 32f / 120f

// Fixed star positions (share of width, share of the sky's height, radius in dp, twinkle offset).
private val stars = listOf(
    floatArrayOf(0.12f, 0.10f, 1.4f, 0.0f), floatArrayOf(0.28f, 0.22f, 1.0f, 0.7f), floatArrayOf(0.46f, 0.07f, 1.2f, 1.9f),
    floatArrayOf(0.66f, 0.16f, 1.6f, 2.6f), floatArrayOf(0.84f, 0.09f, 1.1f, 3.4f), floatArrayOf(0.91f, 0.30f, 1.3f, 4.1f),
    floatArrayOf(0.07f, 0.34f, 1.0f, 4.8f), floatArrayOf(0.36f, 0.40f, 0.9f, 5.5f), floatArrayOf(0.74f, 0.42f, 1.0f, 1.2f),
    floatArrayOf(0.55f, 0.28f, 0.8f, 2.2f), floatArrayOf(0.20f, 0.52f, 0.8f, 3.0f), floatArrayOf(0.80f, 0.55f, 0.9f, 5.0f),
)

// Bubbles rising through the water (share of width, start offset, size in dp).
private val bubbles = listOf(
    floatArrayOf(0.16f, 0.0f, 3.0f), floatArrayOf(0.78f, 0.35f, 2.2f), floatArrayOf(0.48f, 0.62f, 2.6f),
    floatArrayOf(0.30f, 0.81f, 1.8f), floatArrayOf(0.90f, 0.15f, 2.0f),
)

/**
 * Covers the whole app while it plays. [welcome] says whether the first-launch welcome page should follow; it is read
 * when the opening finishes, so a shared window that opened meanwhile goes straight to the app.
 */
@Composable
internal fun LaunchSceneOverlay(welcome: Boolean) {
    val context = LocalContext.current
    // "Remove animations" in the phone's accessibility settings: the scene stays still and is shorter.
    val still = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val wantsWelcome by rememberUpdatedState(welcome)
    val intro = remember { Animatable(if (still) 1f else 0f) }
    val name = remember { Animatable(if (still) 1f else 0f) }
    val toWelcome = remember { Animatable(0f) }
    val items = remember { List(5) { Animatable(0f) } }
    val exit = remember { Animatable(0f) }
    var tapped by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // The app's first frames are heavy and the system splash freezes while they draw. The scene holds its first
        // frame, which matches the splash, until three frames in a row come within 50 ms (at most 0.9 s), so the
        // sunrise isn't half over before it is seen.
        withTimeoutOrNull(900L) {
            var last = withFrameNanos { it }
            var smooth = 0
            while (smooth < 3) {
                val now = withFrameNanos { it }
                smooth = if (now - last < 50_000_000L) smooth + 1 else 0
                last = now
            }
        }
        if (!still) {
            launch { intro.animateTo(1f, tween(950, easing = FastOutSlowInEasing)) }
            launch { delay(420); name.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        }
        // The opening lasts about 1.6 s; a tap cuts it short.
        withTimeoutOrNull(if (still) 700L else 1_650L) { snapshotFlow { tapped }.first { it } }
        if (wantsWelcome) {
            if (still) { toWelcome.snapTo(1f); items.forEach { it.snapTo(1f) } }
            else {
                launch { toWelcome.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 140f)) }
                items.forEachIndexed { index, item ->
                    launch { delay(260L + index * 110L); item.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
                }
            }
        } else {
            leaving = true
            exit.animateTo(1f, tween(if (still) 200 else 480, easing = FastOutLinearInEasing))
            LaunchScene.finish()
        }
    }

    fun getStarted() {
        if (leaving) return
        leaving = true
        scope.launch { exit.animateTo(1f, tween(if (still) 150 else 380, easing = FastOutLinearInEasing)); LaunchScene.finish() }
    }

    val motion = rememberInfiniteTransition(label = "launch")
    val wave by motion.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "wave")
    val bob by motion.animateFloat(-1f, 1f, infiniteRepeatable(tween(1_800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val glow by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(2_600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val rise by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "rise")
    val ring by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(3_200, easing = FastOutSlowInEasing)), label = "ring")
    val t = if (still) 0f else 1f

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value; val scale = 1f + 0.06f * exit.value; scaleX = scale; scaleY = scale }
            .background(LaunchScene.Night)
            .pointerInput(Unit) { detectTapGestures { tapped = true } }
    ) {
        val height = maxHeight
        val compact = height < 700.dp
        // The tide comes in under the welcome page, so its highlights sit in the water below the sunrise.
        val horizon = height * (0.63f - 0.09f * toWelcome.value)
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

        SeaAndSky(intro.value, toWelcome.value, horizon, wave * t, glow * t, rise * t, still)

        // The tile starts where the system splash leaves its round icon, then rises and becomes the app's tile.
        val settled = if (compact) 100.dp else 120.dp
        val tile = lerp(160.dp, settled, intro.value)
        val opening = lerp(height / 2, height * 0.38f, intro.value)
        val top = topInset + (if (compact) 28.dp else 52.dp) + settled / 2
        val centre = lerp(opening, top, toWelcome.value)
        Column(Modifier.fillMaxWidth().offset(y = centre - tile / 2).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            FishTile(tile, lerp(tile / 2, tile * TILE_CORNER, intro.value), bob * t, ring * t * intro.value)
            Spacer(Modifier.height(18.dp))
            val nameLift = (1f - name.value) * 14f
            Column(Modifier.graphicsLayer { alpha = name.value; translationY = nameLift * density },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Kia ora", color = Foam, fontSize = 14.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                Text("Fishdays - NZ", color = Color.White, fontSize = if (compact) 30.sp else 34.sp, fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = Shadow(LaunchScene.Night.copy(alpha = 0.45f), Offset(0f, 2f), 14f)))
            }
            Text("Find the best time and place to fish, anywhere in Aotearoa.", color = Color(0xFFDCE8FF), fontSize = 15.sp,
                textAlign = TextAlign.Center, lineHeight = 21.sp,
                modifier = Modifier.padding(top = 10.dp).appear(items[0].value))
        }

        if (toWelcome.value > 0f) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Highlight(Icons.Default.Schedule, "Your best fishing window, found for you", items[1].value)
            Highlight(Icons.Default.Waves, "Tides, wind, waves and moon", items[2].value)
            Highlight(Icons.Default.Straighten, "Size and bag limits, even offline", items[3].value)
            Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
            Column(Modifier.appear(items[4].value), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(::getStarted, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = LaunchScene.Night)) {
                    Text("Get started", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                Text("Two quick questions, then you're fishing", color = Color(0xFFBFD3FF), fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** Fades in and rises into place as [progress] goes from 0 to 1. */
private fun Modifier.appear(progress: Float) = graphicsLayer { alpha = progress; translationY = (1f - progress) * 16f * density }

@Composable private fun Highlight(icon: ImageVector, text: String, progress: Float) {
    Row(Modifier.fillMaxWidth().appear(progress)
        .background(LaunchScene.Night.copy(alpha = 0.38f), RoundedCornerShape(16.dp))
        .border(BorderStroke(0.6.dp, Color.White.copy(alpha = 0.24f)), RoundedCornerShape(16.dp))
        .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = Foam, modifier = Modifier.size(20.dp))
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** The pale-sea tile with the fish bobbing in it, and a ripple spreading out from it now and then. */
@Composable private fun FishTile(size: Dp, corner: Dp, bob: Float, ring: Float) {
    Box(Modifier.size(size).drawBehind {
        if (ring > 0f) {
            val radius = this.size.minDimension / 2 * (1f + ring * 0.9f)
            drawCircle(Color.White.copy(alpha = (1f - ring) * 0.45f), radius, style = Stroke(1.6.dp.toPx()))
        }
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.launch_sea), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Image(painterResource(R.drawable.launch_fish), contentDescription = "Fishdays - NZ", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(FISH_WIDTH).graphicsLayer {
                    translationY = bob * 3.dp.toPx(); rotationZ = bob * 3f
                })
        }
    }
}

/** The sky brightening into dawn, the sun rising behind the sea, and three layers of waves rolling in. */
@Composable private fun SeaAndSky(intro: Float, welcome: Float, horizon: Dp, wave: Float, glow: Float, rise: Float, still: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val horizonY = horizon.toPx()
        drawRect(Brush.verticalGradient(0f to LaunchScene.Night, 0.62f to SkyMid, 1f to Dawn, startY = 0f, endY = horizonY + 2f),
            size = size.copy(height = horizonY + 2f), alpha = intro)
        stars.forEach { (fx, fy, r, offset) ->
            val twinkle = if (still) 0.7f else 0.45f + 0.4f * sin(glow * PI.toFloat() + offset).let { it * it }
            drawCircle(Color.White.copy(alpha = twinkle * (1f - 0.5f * fy) * intro), r.dp.toPx(), Offset(fx * w, fy * horizonY * 0.9f))
        }
        // The sun comes up from below the horizon as the scene opens; the water drawn next hides its lower half.
        val sunY = horizonY + (1f - intro) * 60.dp.toPx() - 4.dp.toPx()
        val sunRadius = 74.dp.toPx() * (1f + 0.06f * glow)
        drawCircle(Brush.radialGradient(0f to Color(0xFFFFE9C9), 0.32f to Color(0xFFF9B783), 0.7f to Dawn.copy(alpha = 0.35f), 1f to Color.Transparent,
            center = Offset(w / 2, sunY), radius = sunRadius), sunRadius, Offset(w / 2, sunY), alpha = intro)

        // The sea rises into place from the bottom as the scene opens.
        val lift = (1f - intro) * (h - horizonY + 40.dp.toPx())
        wave(horizonY - 4.dp.toPx() + lift, 5.dp.toPx(), w / 1.4f, wave, SolidBrush(WaveBack), 0.6f)
        wave(horizonY + 10.dp.toPx() + lift, 7.dp.toPx(), w / 1.1f, -wave * 1.3f + 1.2f, SolidBrush(WaveMid), 0.9f)
        wave(horizonY + 26.dp.toPx() + lift, 5.dp.toPx(), w / 0.9f, wave * 0.8f + 2.4f,
            Brush.verticalGradient(listOf(Sea, LaunchScene.Deep), startY = horizonY + lift, endY = h), 1f)

        // The sun's reflection glinting on the water; it fades under the welcome page's highlights.
        if (welcome < 1f) for (i in 0 until 6) {
            val y = horizonY + 36.dp.toPx() + i * 11.dp.toPx() + lift
            val shimmer = 0.7f + 0.3f * sin(wave * 2 + i * 1.3f)
            val half = (46.dp.toPx() - i * 6.dp.toPx()) * shimmer
            val sway = sin(wave + i) * 4.dp.toPx()
            drawLine(Color(0xFFFFD9A8).copy(alpha = 0.42f * (1f - i / 7f) * intro * (1f - welcome)), Offset(w / 2 - half + sway, y), Offset(w / 2 + half + sway, y),
                strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
        }
        if (!still) bubbles.forEach { (fx, offset, r) ->
            val progress = (rise + offset) % 1f
            val y = h - 40.dp.toPx() - progress * 260.dp.toPx()
            val fade = if (progress < 0.15f) progress / 0.15f else 1f - (progress - 0.15f) / 0.85f
            drawCircle(Color.White.copy(alpha = 0.55f * fade * intro), r.dp.toPx(), Offset(fx * w + sin(progress * 9f + offset * 6f) * 6.dp.toPx(), y),
                style = Stroke(1.2.dp.toPx()))
        }
    }
}

private fun SolidBrush(color: Color) = androidx.compose.ui.graphics.SolidColor(color)

private fun DrawScope.wave(baseline: Float, amplitude: Float, wavelength: Float, phase: Float, brush: Brush, alpha: Float) {
    val path = Path().apply {
        moveTo(0f, size.height)
        var x = 0f
        val step = 8f
        while (x <= size.width + step) {
            lineTo(x, baseline + amplitude * sin(x / wavelength * 2f * PI.toFloat() + phase))
            x += step
        }
        lineTo(size.width, size.height)
        close()
    }
    drawPath(path, brush, alpha)
}
