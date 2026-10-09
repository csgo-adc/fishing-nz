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
    val rainProbability: Double?, val code: Int?, val windDirection: Double? = null,
    val feelsLike: Double? = null
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
        priority: WindowPriority, land: LandPreferences = LandPreferences(),
        allHours: List<ForecastHour> = samples, retrievedAt: Instant? = null
    ): Recommendation? {
        // Two-hour core: instantaneous values at start, middle AND end.
        if (samples.size != 3 || samples.zipWithNext().any { (a, b) -> Duration.between(a.time, b.time).seconds != 3_600L }) return null
        if (!spot.boat) return LandAssessment.evaluate(spot, distance, samples, allHours, marine, solar,
            tides, station, now, sourceNote, priority, land, retrievedAt)
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
        if (codes.any { it in 95..99 || it !in 0..99 }) return null
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
        val periodText = if (spot.boat && periods.isNotEmpty())
            "; mean wave periods ${"%.1f".format(Locale.US, periods.min())}–${"%.1f".format(Locale.US, periods.max())} s"
            else longestPeriod?.let { "; mean wave period up to ${it.roundToInt()} s" }.orEmpty()
        val waveText = if (worstWave == null) "Offshore wave forecast unavailable." else
            "Offshore significant wave height up to ${"%.1f".format(Locale.US, worstWave)} m$periodText${if (!completeWaves) " in available samples; some data is missing" else ""}. " +
                if (spot.boat) "Actual conditions depend on the boat, route and local sea state." else "Local shore waves may differ."
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
            if (marineSamples.any { (it?.wave ?: 0.0) >= 1.0 && (it?.period ?: 0.0) >= 10 })
                add("Long wave periods may increase surf and surge at exposed locations.")
            if (rainProbability >= 60 || meanRain >= 1.5) add("Rain could affect this session.")
            if (codes.any { it == 45 || it == 48 }) add("Fog may reduce visibility.")
            if (Duration.between(now, start).seconds > 7 * 86_400L) add("Long-range forecast; check again closer to the day.")
            add("Access, local wave exposure and official marine warnings have not been verified for this location.")
        }
        val temperature = samples.mapNotNull { it.feelsLike?.takeIf { value -> value.isFinite() && value in -100.0..80.0 } }
        fun range(values: List<Double>, decimals: Int): String {
            if (values.isEmpty()) return "—"
            val a = String.format(Locale.US, "%.$decimals" + "f", values.min())
            val b = String.format(Locale.US, "%.$decimals" + "f", values.max())
            return if (a == b) a else "$a–$b"
        }
        val windDirections = samples.mapNotNull { it.windDirection?.takeIf { d -> d.isFinite() && d in 0.0..360.0 } }.map(::compass).distinct().joinToString("/")
        val peakRain = rain.max()
        val completeTemperature = temperature.size == samples.size
        val thermalBand = temperature.maxOfOrNull(LandAssessment::temperatureBand) ?: 0
        val comfortBand = maxOf(thermalBand, when { comfort >= 85 -> 0; comfort >= 70 -> 1; comfort >= 50 -> 2; else -> 3 })
        val complete = completeWaves && completeTides && completeTemperature
        val event = tides.firstOrNull { it.at in start..end } ?: tides.firstOrNull { it.high && it.at > start }
        val waveMood = when {
            !completeWaves -> needsDataMood
            marineSamples.any { (it?.wave ?: 0.0) >= .5 && (it?.period ?: 99.0) <= 5 } -> WindowMood("🌊", "Choppy")
            worstWave != null && worstWave >= 1.2 -> WindowMood("🌊", "More motion")
            else -> WindowMood("🌊", "Lower waves")
        }
        val tideValue = if (completeTides && event != null) "${if (event.at > end) "Next " else ""}${if (event.high) "high" else "low"} ${eventTime(event.at, start)} · ${"%.1f".format(Locale.US, event.height)} m CD\n${tideReferenceLabel(spot, station!!)}" else "—"
        val rows = listOf(
            ConditionItem("Offshore waves", worstWave?.let { "${"%.1f".format(Locale.US, it)} m · ${range(periods, 1)} s" } ?: "—", waveMood),
            ConditionItem("Wind", "${maxWind.roundToInt()} km/h · gust ${maxGust.roundToInt()}${if (windDirections.isEmpty()) "" else " · $windDirections"}", comfortMood(LandAssessment.windBand(maxWind, maxGust))),
            ConditionItem("Rain", "${"%.1f".format(Locale.US, totalRain)} mm · peak ${"%.1f".format(Locale.US, peakRain)} mm/h · ${rainProbability.roundToInt()}% hourly", WindowMood(if (peakRain > .2) "🌧️" else if (rainProbability >= 60) "🌦️" else "🌤️", if (peakRain > .8) "Wet" else if (totalRain > 0) "Light rain" else if (rainProbability >= 60) "Rain possible" else "Mostly dry")),
            ConditionItem("Feels like", if (temperature.isEmpty()) "—" else "${range(temperature, 0)}°C",
                if (temperature.size != 3) needsDataMood else if (temperature.min() < 12) WindowMood("🥶", "Cold") else if (temperature.max() > 26) WindowMood("🥵", "Hot") else WindowMood("😌", "Mild")),
            ConditionItem("Tide", tideValue, if (!completeTides) needsDataMood else WindowMood("🕒", "Tide timing")),
            ConditionItem("Daylight", "${(daylight * 100).roundToInt()}% session", WindowMood(if (daylight == 1.0) "🌞" else "🌙", if (daylight == 1.0) "Daylight" else "After dark"))
        )
        val age = Duration.between(now, start).seconds
        val assessment = WindowAssessment(
            if (!complete) needsDataMood else comfortMood(comfortBand),
            rows, when { age > 5 * 86400 -> WindowMood("🔭", "Early outlook"); age > 2 * 86400 -> WindowMood("🗓️", "Planning forecast"); else -> WindowMood("🤔", "Limited confidence") },
            band = comfortBand, comfortComplete = complete,
            details = warnings + buildList {
                add("Waves lead boat ranking. Feels-like temperature limits the outlook and breaks comfort ties. Route, vessel response, model timing, agreement and return conditions unchecked. CD = Chart Datum.")
                add("Rain chance is the highest hourly likelihood, not the chance for the whole session. Offshore waves show significant height and mean period.")
                if (completeTides) add("Tide times and heights are for the named reference station; local timing can differ.")
                if (temperature.size != 3) add("Feels-like temperature coverage incomplete.")
                if (retrievedAt == null || Duration.between(retrievedAt, now).seconds > 3 * 3600) add("Forecast freshness unverified or older than 3 hours.")
            })
        val briefReason = if (priority == WindowPriority.LATE_INCOMING) "Fits late incoming · waves first" else "Waves first"
        return Recommendation(
            name = spot.name, area = spot.area, rating = comfort.roundToInt(),
            time = formatWindow(start, end), distance = "${distance.roundToInt()} km straight line",
            reasons = listOf(briefReason), boat = spot.boat, warnings = warnings,
            distanceKm = distance, startsAtEpochSeconds = start.epochSecond, durationHours = 2,
            summary = briefReason, conditions = conditions, sourceNote = sourceNote,
            rankingValue = comfort, dataComplete = complete,
            daylightFraction = daylight, tidePreferenceFit = tideFit, assessment = assessment,
            latitude = spot.latitude, longitude = spot.longitude
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
internal val windowOrder: Comparator<Recommendation> = Comparator { a, b ->
    val left = a.assessment; val right = b.assessment
    if (!a.boat && !b.boat && left != null && right != null) {
        compareValuesBy(a, b,
            { if (it.assessment!!.comfortComplete) 0 else 1 },
            { if (it.assessment!!.matchesComfort) 0 else 1 },
            { -it.daylightFraction },
            { it.assessment!!.band }, { it.assessment!!.demandingHours }, { it.assessment!!.uncomfortableHours },
            { if (it.assessment!!.priority == LandPriority.DRY) it.assessment.rainTotal else if (it.assessment.priority == LandPriority.CASTING) it.assessment.maxGust else 0.0 },
            { if (it.assessment!!.priority == LandPriority.DRY) it.assessment.maxGust else if (it.assessment.priority == LandPriority.CASTING) it.assessment.maxWind else 0.0 },
            { it.startsAtEpochSeconds })
    } else compareValuesBy(a, b, { !it.dataComplete }, { -it.daylightFraction }, { -it.rankingValue }, { it.assessment?.band ?: 0 }, { it.startsAtEpochSeconds })
}

/** Offer a real comfort trade-off when the balanced choice has a calmer or drier peer. */
internal fun landTradeoff(selected: Recommendation, pool: List<Recommendation>): Pair<String, Recommendation>? {
    val chosen = selected.assessment ?: return null
    if (selected.boat || chosen.priority != LandPriority.BALANCED || !chosen.matchesComfort) return null
    val peers = pool.filter { other ->
        val value = other.assessment
        other.startsAtEpochSeconds != selected.startsAtEpochSeconds && !other.boat && value != null &&
            value.matchesComfort && value.band <= chosen.band && other.daylightFraction >= selected.daylightFraction
    }
    val calmer = peers.filter { it.assessment!!.maxGust < chosen.maxGust && it.assessment.rainTotal > chosen.rainTotal }
        .minWithOrNull(compareBy({ it.assessment!!.maxGust }, { it.assessment!!.rainTotal }, { it.startsAtEpochSeconds }))
    if (calmer != null) return "Calmer alternative" to calmer
    val drier = peers.filter { it.assessment!!.rainTotal < chosen.rainTotal && it.assessment.maxGust > chosen.maxGust }
        .minWithOrNull(compareBy({ it.assessment!!.rainTotal }, { it.assessment!!.maxGust }, { it.startsAtEpochSeconds }))
    return drier?.let { "Drier alternative" to it }
}

internal class WindowSelection(private val onePerDay: Boolean) {
    private val bestByDay = mutableMapOf<LocalDate, Recommendation>()
    fun consider(startDay: LocalDate, candidate: Recommendation) {
        val key = if (onePerDay) startDay else LocalDate.MIN
        val current = bestByDay[key]
        if (current == null || windowOrder.compare(candidate, current) < 0) bestByDay[key] = candidate
    }
    fun windows(): List<Recommendation> = bestByDay.values.sortedBy { it.startsAtEpochSeconds }
}
