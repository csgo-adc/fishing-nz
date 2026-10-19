package nz.fishingnz.app.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Spring tides follow new and full moons (a bigger range); neap tides follow the quarter moons (a smaller range). */
enum class TideRange(val label: String, val explanation: String) {
    SPRING("Spring tides", "A bigger range than usual: higher highs and lower lows."),
    NEAP("Neap tides", "A smaller range than usual: lower highs and higher lows."),
    BETWEEN("Between spring and neap", "A middling tide range.")
}

/** The moon on one New Zealand day. [emoji] shows it as seen from New Zealand, lit on the left while it waxes. */
data class MoonDay(val name: String, val emoji: String, val illuminationPercent: Int, val tideRange: TideRange) {
    val summary: String get() = "$emoji $name · $illuminationPercent% lit"
}

/**
 * Moon phase from Meeus's low-precision lunar elongation (Astronomical Algorithms, ch. 47–48), within about half an hour
 * of the published phase times. The iPhone app has the same rules in `MoonPhase.swift`. Planning information only:
 * it describes the tide range, never how many fish to expect.
 */
object MoonPhase {
    private val zone = ZoneId.of("Pacific/Auckland")
    private const val SYNODIC_DAYS = 29.530588853
    private val principal = listOf(0.0 to "New moon", 0.25 to "First quarter", 0.5 to "Full moon", 0.75 to "Last quarter")

    /** 0 at new moon, 0.25 first quarter, 0.5 full and 0.75 last quarter. */
    fun position(at: Instant): Double {
        val t = (at.epochSecond / 86_400.0 + 2_440_587.5 - 2_451_545.0) / 36_525.0
        val d = 297.8501921 + 445_267.1114034 * t - 0.0018819 * t * t + t * t * t / 545_868 - t * t * t * t / 113_065_000
        val m = 357.5291092 + 35_999.0502909 * t - 0.0001536 * t * t + t * t * t / 24_490_000
        val mp = 134.9633964 + 477_198.8675055 * t + 0.0087414 * t * t + t * t * t / 69_699 - t * t * t * t / 14_712_000
        fun s(degrees: Double) = sin(Math.toRadians(degrees))
        val elongation = d + 6.289 * s(mp) - 2.100 * s(m) + 1.274 * s(2 * d - mp) + 0.658 * s(2 * d) + 0.214 * s(2 * mp) + 0.110 * s(d)
        return ((elongation % 360 + 360) % 360) / 360
    }

    fun illumination(at: Instant): Double = (1 - cos(2 * Math.PI * position(at))) / 2

    fun forDay(date: LocalDate): MoonDay {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val noon = date.atTime(12, 0).atZone(zone).toInstant()
        val from = position(start)
        val until = position(end)
        // A principal phase is named on the day it happens; between them, the noon position decides.
        val name = principal.firstOrNull { (target, _) -> if (target == 0.0) until < from else from < target && target <= until }?.second
            ?: when (position(noon)) {
                in 0.0..<0.25 -> "Waxing crescent"
                in 0.25..<0.5 -> "Waxing gibbous"
                in 0.5..<0.75 -> "Waning gibbous"
                else -> "Waning crescent"
            }
        val p = position(noon)
        return MoonDay(name, emoji(name), (illumination(noon) * 100).roundToInt(), tideRange(p))
    }

    /** The next day after [after] (within about a month) whose moon is [phase], such as "Full moon". */
    fun nextDay(phase: String, after: LocalDate): LocalDate? =
        (1L..31L).map(after::plusDays).firstOrNull { forDay(it).name == phase }

    /** "Next full moon Mon 26 Oct · Next new moon Tue 10 Nov", soonest first. */
    fun nextPhasesLine(after: LocalDate): String {
        val format = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
        return listOf("Full moon", "New moon").mapNotNull { phase -> nextDay(phase, after)?.let { phase to it } }
            .sortedBy { it.second }
            .joinToString(" · ") { (phase, day) -> "Next ${phase.lowercase()} ${day.format(format)}" }
    }

    internal fun tideRange(position: Double): TideRange {
        // Days after the latest new or full moon, and after the latest quarter. Ranges peak a day or two after each.
        val afterSyzygy = (position % 0.5) * SYNODIC_DAYS
        val afterQuarter = ((position + 0.25) % 0.5) * SYNODIC_DAYS
        val halfCycle = SYNODIC_DAYS / 2
        return when {
            afterSyzygy <= 3 || afterSyzygy >= halfCycle - 0.5 -> TideRange.SPRING
            afterQuarter <= 3 || afterQuarter >= halfCycle - 0.5 -> TideRange.NEAP
            else -> TideRange.BETWEEN
        }
    }

    private fun emoji(name: String) = when (name) {
        "New moon" -> "🌑"
        "Waxing crescent" -> "🌘"
        "First quarter" -> "🌗"
        "Waxing gibbous" -> "🌖"
        "Full moon" -> "🌕"
        "Waning gibbous" -> "🌔"
        "Last quarter" -> "🌓"
        else -> "🌒"
    }
}
