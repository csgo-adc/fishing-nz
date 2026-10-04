package nz.fishingnz.app.data

import nz.fishingnz.app.model.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal data class ForecastHour(
    val time: Instant, val wind: Double?, val gust: Double?, val rain: Double?,
    val rainProbability: Double?, val code: Int?, val windDirection: Double? = null
)
internal data class MarineSample(val wave: Double?, val period: Double?, val direction: Double? = null)
internal data class DaylightPeriod(val sunrise: Instant, val sunset: Instant)

/** Planning preferences and weather comfort, not a model of fish activity or local safety. */
internal object WindowEvaluation {
    val zone: ZoneId = ZoneId.of("Pacific/Auckland")
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val datedClock = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.US)

    fun formatWindow(start: Instant, end: Instant): String =
        "${start.atZone(zone).format(datedClock)}–${eventTime(end, start)}"

    private fun eventTime(at: Instant, start: Instant): String = at.atZone(zone).format(
        if (at.atZone(zone).toLocalDate() == start.atZone(zone).toLocalDate()) clock else datedClock
    )

    fun tideFit(start: Instant, end: Instant, tides: List<LinzPrediction>): Double {
        if (!tideCovered(start, end, tides)) return 0.0
        val bands = tides.filter { it.high }.map { it.at.minusSeconds(150 * 60L) to it.at.plusSeconds(30 * 60L) }
        return overlapSeconds(start, end, bands) / Duration.between(start, end).seconds
    }

    fun daylightFraction(start: Instant, end: Instant, solar: Map<LocalDate, DaylightPeriod>): Double? {
        var day = start.atZone(zone).toLocalDate()
        val lastDay = end.minusNanos(1).atZone(zone).toLocalDate()
        while (day <= lastDay) {
            if (solar[day] == null) return null
            day = day.plusDays(1)
        }
        return overlapSeconds(start, end, solar.values.map { it.sunrise to it.sunset }) / Duration.between(start, end).seconds
    }

    private fun overlapSeconds(start: Instant, end: Instant, intervals: List<Pair<Instant, Instant>>): Double {
        var previousEnd = start
        var total = 0L
        intervals.sortedBy { it.first }.forEach { (a, b) ->
            val left = maxOf(start, a, previousEnd)
            val right = minOf(end, b)
            if (right > left) { total += Duration.between(left, right).seconds; previousEnd = right }
        }
        return total.toDouble()
    }

    private fun tideCovered(start: Instant, end: Instant, tides: List<LinzPrediction>): Boolean =
        tides.any { it.at <= start } && tides.any { it.at >= end }

    private fun tideDescription(start: Instant, end: Instant, tides: List<LinzPrediction>, station: TideStation?): String {
        if (station == null || !tideCovered(start, end, tides)) return "Local tide timing is unverified for this session."
        val before = tides.last { it.at <= start }
        val after = tides.first { it.at > start }
        val turns = tides.filter { it.at >= start && it.at <= end }
        val phase = if (turns.isNotEmpty()) {
            turns.joinToString("; ") { "${if (it.high) "High" else "Low"} tide at ${eventTime(it.at, start)} (${"%.2f".format(Locale.US, it.height)} m)${if (it.at < end) if (it.high) ", then falling water" else ", then rising water" else " at the end of the session"}" }
        } else {
            "${if (before.high) "Falling" else "Rising"} water between ${if (before.high) "high" else "low"} at ${eventTime(before.at, start)} and ${if (after.high) "high" else "low"} at ${eventTime(after.at, start)}"
        }
        val nextHigh = tides.firstOrNull { it.high && it.at > start }
        val highNote = if (nextHigh != null && turns.none { it.high } && nextHigh != after) "; next high ${eventTime(nextHigh.at, start)}" else ""
        return "$phase$highNote. LINZ ${station.name}; heights above Chart Datum."
    }

    fun evaluate(
        spot: FishingSpot, distance: Double, samples: List<ForecastHour>,
        marine: Map<Instant, MarineSample>, solar: Map<LocalDate, DaylightPeriod>,
        tides: List<LinzPrediction>, station: TideStation?, now: Instant, sourceNote: String,
        priority: WindowPriority
    ): Recommendation? {
        // Two-hour core: instantaneous values at start, middle AND end.
        if (samples.size != 3 || samples.zipWithNext().any { (a, b) -> Duration.between(a.time, b.time).seconds != 3_600L }) return null
        val start = samples.first().time
        val end = samples.last().time
        val windValues = samples.map { it.wind ?: return null }
        val codes = samples.map { it.code ?: return null }
        // Provider interval labels describe the PRECEDING hour.
        val intervals = samples.drop(1)
        val gusts = intervals.map { it.gust ?: return null }
        val rain = intervals.map { it.rain ?: return null }
        val probabilities = intervals.map { it.rainProbability ?: return null }
        if ((windValues + gusts + rain).any { !it.isFinite() || it < 0 } || probabilities.any { !it.isFinite() || it !in 0.0..100.0 }) return null
        val maxWind = windValues.max()
        val maxGust = gusts.max()
        if (codes.any { it in 95..99 }) return null
        // Existing broad exclusion limits are retained; passing these is not site clearance.
        if (maxWind >= (if (spot.boat) 40 else 55) || maxGust >= (if (spot.boat) 55 else 70)) return null
        val marineSamples = samples.map { marine[it.time] }
        val waves = marineSamples.mapNotNull { it?.wave?.takeIf { value -> value.isFinite() && value >= 0 } }
        val periods = marineSamples.mapNotNull { it?.period?.takeIf { value -> value.isFinite() && value > 0 } }
        val worstWave = waves.maxOrNull()
        val longestPeriod = periods.maxOrNull()
        // Known adverse data survives a missing sample.
        if (worstWave != null && worstWave >= (if (spot.boat) 2 else 3)) return null
        val completeWaves = waves.size == samples.size && periods.size == samples.size
        val completeTides = station != null && tideCovered(start, end, tides)
        val daylight = daylightFraction(start, end, solar) ?: return null
        val meanWind = windValues.zipWithNext { a, b -> (a + b) / 2 }.average()
        val totalRain = rain.sum()
        val meanRain = rain.average()
        val rainProbability = probabilities.max()
        val windComfort = .65 * lowerIsBetter(maxWind, if (spot.boat) 10.0 else 12.0, if (spot.boat) 40.0 else 55.0) +
            .35 * lowerIsBetter(maxGust, if (spot.boat) 18.0 else 20.0, if (spot.boat) 55.0 else 70.0)
        val rainComfort = .5 * (1 - rainProbability / 100) + .5 * lowerIsBetter(meanRain, 0.0, 2.5)
        // Subjective boat comfort: waves dominate, and missing marine data earns no wave credit.
        val waveComfort = if (completeWaves) marineSamples.minOf(::boatWaveComfort) else 0.0
        val comfort = if (spot.boat) 60 * waveComfort + 30 * windComfort + 10 * rainComfort
            else 60 * windComfort + 40 * rainComfort
        val tideFit = tideFit(start, end, tides)
        val tideText = tideDescription(start, end, tides, station)
        val daylightText = if (daylight >= .9999) "The fishing session is entirely in daylight."
            else "About ${((1 - daylight) * 120).roundToInt()} minutes of the fishing session are outside sunrise–sunset."
        val rainText = when {
            totalRain <= .001 && rainProbability <= 30 -> "Little or no rain is forecast (${rainProbability.roundToInt()}% maximum hourly chance)."
            totalRain <= .001 -> "No rain accumulation is forecast, but hourly rain chance reaches ${rainProbability.roundToInt()}%."
            else -> "Around ${"%.1f".format(Locale.US, totalRain)} mm of rain is forecast during the session; hourly chance reaches ${rainProbability.roundToInt()}%."
        }
        val comfortReason = if (spot.boat) "waves as the main comfort factor, alongside wind and rain" else "its balance of wind and rain"
        val selectionReason = if (priority == WindowPriority.LATE_INCOMING)
            "This session fits your preference for late incoming tide around high water." +
                if (spot.boat) " Wave comfort is the main factor when comparing sessions that fit." else ""
        else "Chosen for $comfortReason, with daylight sessions preferred within your selected hours."
        val qualification = if (completeWaves && completeTides) "" else "Some local forecast data is missing; treat this as a time to investigate. "
        val periodText = if (spot.boat && periods.isNotEmpty())
            "; mean wave periods ${"%.1f".format(Locale.US, periods.min())}–${"%.1f".format(Locale.US, periods.max())} s"
            else longestPeriod?.let { "; mean wave period up to ${it.roundToInt()} s" }.orEmpty()
        val waveText = if (worstWave == null) "Offshore wave forecast unavailable." else
            "Offshore significant wave height up to ${"%.1f".format(Locale.US, worstWave)} m$periodText${if (!completeWaves) " in available samples; some data is missing" else ""}. " +
                if (spot.boat) "Actual conditions depend on the boat, route and local sea state." else "Local shore waves may differ."
        val summary = "$qualification$selectionReason ${if (spot.boat) "$waveText " else ""}${tideText.substringBefore(". LINZ").trimEnd('.')}. Wind averages ${meanWind.roundToInt()} km/h, with gusts up to ${maxGust.roundToInt()} km/h. $rainText $daylightText"
        val direction = samples.mapNotNull { it.windDirection }.takeIf { it.size == samples.size }?.let { directions ->
            val labels = directions.map(::compass).distinct()
            " Wind from ${labels.joinToString(" / ")}."
        }.orEmpty()
        val conditions = listOf(
            tideText,
            "Wind: ${meanWind.roundToInt()} km/h average, ${maxWind.roundToInt()} km/h maximum; gusts up to ${maxGust.roundToInt()} km/h.$direction",
            rainText,
            waveText,
            daylightText
        )
        val warnings = buildList {
            if (!completeTides) add("Local tide timing is unverified; check the correct local tide table.")
            if (!completeWaves) add("Wave forecast is incomplete; this is a provisional weather option.")
            if (daylight < .9999) add("Part of this session is outside daylight; check access, lighting and your return route.")
            if (maxGust >= (if (spot.boat) 40 else 55)) add("Strong gusts forecast.")
            if (worstWave != null && worstWave >= (if (spot.boat) 1.2 else 1.5)) add("Elevated offshore waves; local exposure must be checked before using this window.")
            if (spot.boat && marineSamples.any { sample ->
                sample?.wave?.let { it.isFinite() && it >= .5 } == true &&
                    sample?.period?.let { it.isFinite() && it > 0 && it <= 5 } == true
            }) add("Short-period waves may make the boat ride and fishing uncomfortable.")
            if (worstWave != null && longestPeriod != null && worstWave >= (if (spot.boat) 1.0 else .8) && longestPeriod >= (if (spot.boat) 10 else 12))
                add("Long wave periods may increase surf and surge at exposed locations.")
            if (rainProbability >= 60 || meanRain >= 1.5) add("Rain could affect this session.")
            if (codes.any { it == 45 || it == 48 }) add("Fog may reduce visibility.")
            if (Duration.between(now, start).seconds > 7 * 86_400L) add("Long-range forecast; check again closer to the day.")
            add("Access, local wave exposure and official marine warnings have not been verified for this location.")
        }
        return Recommendation(
            name = spot.name, area = spot.area, rating = comfort.roundToInt(),
            time = formatWindow(start, end), distance = "${distance.roundToInt()} km straight line",
            reasons = listOf(selectionReason), boat = spot.boat, warnings = warnings,
            distanceKm = distance, startsAtEpochSeconds = start.epochSecond, durationHours = 2,
            summary = summary, conditions = conditions, sourceNote = sourceNote,
            rankingValue = comfort, dataComplete = completeWaves && completeTides,
            daylightFraction = daylight, tidePreferenceFit = tideFit
        )
    }

    private fun compass(degrees: Double): String = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(degrees / 45).roundToInt().mod(8)]
    private fun lowerIsBetter(value: Double, best: Double, worst: Double): Double = ((worst - value) / (worst - best)).coerceIn(0.0, 1.0)

    /** Default comfort preference, not a vessel motion model or a safe wave-height threshold. */
    private fun boatWaveComfort(sample: MarineSample?): Double {
        val height = sample?.wave?.takeIf { it.isFinite() && it >= 0 } ?: return 0.0
        val period = sample?.period?.takeIf { it.isFinite() && it > 0 } ?: return 0.0
        val heightComfort = lowerIsBetter(height, .3, 2.0)
        val shortPeriodPenalty = .25 * lowerIsBetter(period, 3.0, 8.0) * (height / .75).coerceIn(0.0, 1.0)
        return (heightComfort - shortPeriodPenalty).coerceIn(0.0, 1.0)
    }
}

/** Stable planning order; no rounded score, distance or dawn bonus can change it. */
internal val windowOrder: Comparator<Recommendation> = compareByDescending<Recommendation> { it.dataComplete }
    .thenByDescending { it.daylightFraction }
    .thenByDescending { it.rankingValue }
    .thenBy { it.startsAtEpochSeconds }

internal class WindowSelection(private val onePerDay: Boolean) {
    private val bestByDay = mutableMapOf<LocalDate, Recommendation>()
    fun consider(startDay: LocalDate, candidate: Recommendation) {
        val key = if (onePerDay) startDay else LocalDate.MIN
        val current = bestByDay[key]
        if (current == null || windowOrder.compare(candidate, current) < 0) bestByDay[key] = candidate
    }
    fun windows(): List<Recommendation> = bestByDay.values.sortedBy { it.startsAtEpochSeconds }
}
