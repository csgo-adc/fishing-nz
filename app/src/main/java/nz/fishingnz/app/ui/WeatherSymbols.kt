package nz.fishingnz.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import nz.fishingnz.app.model.WeatherLabels

/** Familiar cloud + rain/fog/snow symbols, rather than a grain or a lone water drop. */
private fun cloudSymbol(name: String, detail: Int, drops: Int = 3, drizzle: Boolean = false): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(19.3f, 9.8f)
        curveTo(18.6f, 6.5f, 15.8f, 4f, 12.4f, 4f)
        curveTo(9.7f, 4f, 7.3f, 5.5f, 6.1f, 7.7f)
        curveTo(2.6f, 8.1f, 1f, 10.2f, 1f, 12.7f)
        curveTo(1f, 15.3f, 3.1f, 17f, 5.7f, 17f)
        lineTo(18.5f, 17f)
        curveTo(21.1f, 17f, 23f, 15.1f, 23f, 12.8f)
        curveTo(23f, 10.7f, 21.3f, 9.8f, 19.3f, 9.8f)
        close()
    }
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round) {
        when (detail) {
            1 -> { moveTo(4f, 19f); lineTo(20f, 19f); moveTo(6f, 22f); lineTo(18f, 22f) }
            2 -> for (x in listOf(6f, 12f, 18f)) { moveTo(x, 19f); lineTo(x, 23f); moveTo(x - 1.6f, 20f); lineTo(x + 1.6f, 22f); moveTo(x - 1.6f, 22f); lineTo(x + 1.6f, 20f) }
        }
    }
    if (detail == 0) {
        val positions = when (drops) { 1 -> listOf(12f); 2 -> listOf(8f, 16f); else -> listOf(6f, 12f, 18f) }
        val width = if (drizzle) 1f else 1.6f
        val bottom = if (drizzle) 22f else 23f
        positions.forEach { x -> path(fill = SolidColor(Color.Black)) {
            moveTo(x, 18.5f)
            curveTo(x - width, 20f, x - width, bottom - .6f, x, bottom)
            curveTo(x + width, bottom - .6f, x + width, 20f, x, 18.5f)
            close()
        } }
    }
}.build()
private val rainClouds = (1..3).map { cloudSymbol("Rain $it drops", 0, it) }
private val drizzleClouds = (1..3).map { cloudSymbol("Drizzle $it drops", 0, it, true) }
private val fogCloud = cloudSymbol("Fog", 1)
private val snowCloud = cloudSymbol("Snow cloud", 2)
private val partlyCloudy = ImageVector.Builder("Partly cloudy", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(10f, 1f); lineTo(10f, 3f); lineTo(12f, 3f); lineTo(12f, 1f); close()
        moveTo(3f, 7f); lineTo(1f, 7f); lineTo(1f, 9f); lineTo(3f, 9f); close()
        moveTo(5f, 3f); lineTo(3.5f, 4.5f); lineTo(5f, 6f); lineTo(6.5f, 4.5f); close()
        moveTo(16f, 3f); lineTo(14.5f, 4.5f); lineTo(16f, 6f); lineTo(17.5f, 4.5f); close()
        moveTo(15f, 8f); curveTo(15f, 5.8f, 13.2f, 4f, 11f, 4f); curveTo(8.8f, 4f, 7f, 5.8f, 7f, 8f); curveTo(7f, 10.2f, 8.8f, 12f, 11f, 12f); curveTo(13.2f, 12f, 15f, 10.2f, 15f, 8f); close()
        moveTo(19.4f, 12f); curveTo(19f, 9.5f, 17f, 8f, 14.5f, 8f); curveTo(12.4f, 8f, 10.7f, 9.2f, 10f, 11f); curveTo(7f, 11f, 5f, 13f, 5f, 15.5f); curveTo(5f, 18f, 7f, 20f, 9.5f, 20f); lineTo(19f, 20f); curveTo(21.3f, 20f, 23f, 18.3f, 23f, 16f); curveTo(23f, 13.8f, 21.5f, 12.2f, 19.4f, 12f); close()
    }
}.build()

private val partlyCloudyNight = ImageVector.Builder("Partly cloudy night", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(11f, 1f); curveTo(7f, 1f, 4f, 4f, 4f, 8f); curveTo(4f, 12f, 7f, 15f, 11f, 15f)
        curveTo(13.5f, 15f, 15.5f, 13.7f, 16.8f, 11.9f); curveTo(11.7f, 12.8f, 7.5f, 8.5f, 8.2f, 3.5f)
        curveTo(8.6f, 2.4f, 9.5f, 1.6f, 11f, 1f); close()
        moveTo(19.4f, 13f); curveTo(19f, 10.5f, 17f, 9f, 14.5f, 9f); curveTo(12.4f, 9f, 10.7f, 10.2f, 10f, 12f)
        curveTo(7f, 12f, 5f, 14f, 5f, 16.5f); curveTo(5f, 19f, 7f, 21f, 9.5f, 21f); lineTo(19f, 21f)
        curveTo(21.3f, 21f, 23f, 19.3f, 23f, 17f); curveTo(23f, 14.8f, 21.5f, 13.2f, 19.4f, 13f); close()
    }
}.build()

internal fun knownWeatherIcon(code: Int?, day: Boolean = true): ImageVector {
    val drops = WeatherLabels.rainDrops(code)
    if (drops > 0) return (if (WeatherLabels.isDrizzle(code)) drizzleClouds else rainClouds)[drops - 1]
    return when (code) {
        0, 1 -> if (day) Icons.Default.WbSunny else Icons.Default.NightsStay
        2 -> if (day) partlyCloudy else partlyCloudyNight
        3 -> Icons.Default.Cloud
        45, 48 -> fogCloud
        71, 73, 75, 77, 85, 86 -> snowCloud
        95, 96, 97, 99 -> Icons.Default.Thunderstorm
        else -> Icons.Default.HelpOutline
    }
}
