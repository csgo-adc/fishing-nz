package nz.fishingnz.app.data

import nz.fishingnz.app.model.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Trial human-comfort bands. Local exposure and warning checks remain independent. */
internal object LandAssessment {
    fun band(value: Double, limits: List<Double>): Int = limits.indexOfFirst { value <= it }.let { if (it < 0) 3 else it }
    fun windBand(wind: Double, gust: Double) = maxOf(band(wind, listOf(12.0, 20.0, 30.0)), band(gust, listOf(20.0, 30.0, 45.0)))
    fun temperatureBand(value: Double) = when {
        value in 12.0..26.0 -> 0
        value in 8.0..30.0 -> 1
        value in 5.0..33.0 -> 2
        else -> 3
    }
    private fun valid(value: Double?, temperature: Boolean = false) = value?.takeIf {
        it.isFinite() && if (temperature) it in -100.0..80.0 else it >= 0
    }
    private fun at(hours: List<ForecastHour>, time: Instant, temperature: Boolean = false): Double? {
        fun value(hour: ForecastHour) = valid(if (temperature) hour.feelsLike else hour.wind, temperature)
        hours.firstOrNull { it.time == time }?.let { return value(it) }
        val left = hours.lastOrNull { it.time < time } ?: return null
        val right = hours.firstOrNull { it.time > time } ?: return null
        if (Duration.between(left.time, right.time).seconds != 3600L) return null
        val a = value(left) ?: return null
        val b = value(right) ?: return null
        return a + (b - a) * Duration.between(left.time, time).seconds / 3600.0
    }
    private fun waveAt(marine: Map<Instant, MarineSample>, time: Instant): MarineSample? {
        marine[time]?.let { return it }
        val times = marine.keys.sorted()
        val left = times.lastOrNull { it < time } ?: return null
        val right = times.firstOrNull { it > time } ?: return null
        if (Duration.between(left, right).seconds != 3600L) return null
        fun interpolate(a: Double?, b: Double?, period: Boolean = false): Double? {
            val x = valid(a)?.takeIf { !period || it > 0 } ?: return null
            val y = valid(b)?.takeIf { !period || it > 0 } ?: return null
            return x + (y - x) * Duration.between(left, time).seconds / 3600.0
        }
        return MarineSample(interpolate(marine[left]?.wave, marine[right]?.wave), interpolate(marine[left]?.period, marine[right]?.period, true))
    }

    fun evaluate(spot: FishingSpot, distance: Double, core: List<ForecastHour>, hours: List<ForecastHour>,
                 marine: Map<Instant, MarineSample>, solar: Map<LocalDate, DaylightPeriod>,
                 tides: List<LinzPrediction>, station: TideStation?, now: Instant, sourceNote: String,
                 priority: WindowPriority, preferences: LandPreferences, retrievedAt: Instant?): Recommendation? {
        val start = core.first().time; val end = core.last().time
        val arrival = preferences.arrivalMinutes; val returning = preferences.returnMinutes
        require(preferences.maxBand in 0..3 && listOfNotNull(arrival, returning).all { it in 0..180 })
        val visitKnown = arrival != null && returning != null
        if (preferences.daylightOnly && !visitKnown) return null
        val visitStart = start.minusSeconds((arrival ?: 0) * 60L)
        val visitEnd = end.plusSeconds((returning ?: 0) * 60L)
        if (arrival != null && visitStart < now) return null
        val points = (listOf(visitStart, visitEnd) + hours.map { it.time }.filter { it > visitStart && it < visitEnd }).distinct().sorted()
        val winds = points.map { at(hours, it) }
        val temperatures = points.map { at(hours, it, true) }
        val intervals = hours.filter { it.time > visitStart && it.time.minusSeconds(3600) < visitEnd }
        val gusts = intervals.map { valid(it.gust) }
        val rain = intervals.map { valid(it.rain) }
        val probabilities = intervals.mapNotNull { it.rainProbability?.takeIf { p -> p.isFinite() && p in 0.0..100.0 } }
        val codes = hours.filter { it.time in visitStart..visitEnd }.map { it.code }
        val maxWind = winds.filterNotNull().maxOrNull() ?: return null
        val maxGust = gusts.filterNotNull().maxOrNull() ?: return null
        val marineSamples = points.map { waveAt(marine, it) }
        val completeMarine = marineSamples.all { valid(it?.wave) != null && valid(it?.period)?.let { p -> p > 0 } == true }
        val marineHours = marineSamples.filterNotNull() + marine.filterKeys { it in visitStart..visitEnd && it !in points }.values
        val waves = marineHours.mapNotNull { valid(it.wave) }
        val periods = marineHours.mapNotNull { valid(it.period)?.takeIf { p -> p > 0 } }
        // Known adverse conditions are examined before coverage, including the return.
        if (maxWind >= 55 || maxGust >= 70 || waves.any { it >= 3 } || codes.any { it in 95..99 }) return null
        if (core.any { valid(it.wind) == null || it.code == null || it.code !in 0..99 } ||
            core.drop(1).any { valid(it.gust) == null || valid(it.rain) == null }) return null
        val duration = Duration.between(visitStart, visitEnd).seconds.toDouble()
        fun overlap(hour: ForecastHour) = Duration.between(maxOf(visitStart, hour.time.minusSeconds(3600)), minOf(visitEnd, hour.time)).seconds.toDouble()
        val covered = intervals.sumOf(::overlap)
        val completeWeather = covered == duration && winds.all { it != null } && gusts.all { it != null } &&
            rain.all { it != null } && codes.all { it != null && it in 0..99 }
        val completeTemperature = temperatures.all { it != null }
        val complete = completeWeather && completeTemperature
        val totalRain = intervals.sumOf { (valid(it.rain) ?: 0.0) * overlap(it) / 3600 }
        val peakRain = rain.filterNotNull().maxOrNull() ?: return null
        val windBand = windBand(maxWind, maxGust)
        val rainBand = band(peakRain, listOf(.2, .8, 1.5))
        val tempBand = temperatures.filterNotNull().maxOfOrNull(::temperatureBand) ?: 0
        val grade = maxOf(windBand, rainBand, tempBand)
        var demanding = 0.0; var uncomfortable = 0.0
        for (hour in intervals) {
            val left = maxOf(visitStart, hour.time.minusSeconds(3600)); val right = minOf(visitEnd, hour.time)
            val w = listOfNotNull(at(hours, left), at(hours, right)).maxOrNull() ?: continue
            val g = valid(hour.gust) ?: continue; val r = valid(hour.rain) ?: continue
            val t = listOfNotNull(at(hours, left, true), at(hours, right, true)).maxOfOrNull(::temperatureBand) ?: 0
            val b = maxOf(windBand(w, g), band(r, listOf(.2, .8, 1.5)), t)
            if (b >= 2) demanding += overlap(hour) / 3600
            if (b >= 1) uncomfortable += overlap(hour) / 3600
        }
        val daylight = WindowEvaluation.daylightFraction(visitStart, visitEnd, solar)
        if (preferences.daylightOnly && daylight != 1.0) return null
        val fit = WindowEvaluation.tideFit(start, end, tides)
        val tideComplete = station != null && tides.any { it.at <= start } && tides.any { it.at >= end }
        val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        fun time(at: Instant) = at.atZone(WindowEvaluation.zone).format(
            if (at.atZone(WindowEvaluation.zone).toLocalDate() == start.atZone(WindowEvaluation.zone).toLocalDate()) formatter
            else DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US))
        val turn = tides.firstOrNull { it.at in start..end }
        val event = turn ?: tides.firstOrNull { it.high && it.at > start }
        val rising = tides.lastOrNull { it.at <= start }?.high == false
        val tideMood = when {
            !tideComplete -> WindowMood("❓", "Unverified")
            priority == WindowPriority.LATE_INCOMING && fit >= .8 -> WindowMood("🎯", "Late incoming")
            turn != null -> WindowMood("🔄", "Tide turns")
            rising -> WindowMood("↗️", "Rising")
            else -> WindowMood("↘️", "Falling")
        }
        val probability = probabilities.maxOrNull()
        val directions = hours.filter { it.time in visitStart..visitEnd }.mapNotNull { it.windDirection?.takeIf { d -> d.isFinite() && d in 0.0..360.0 } }
            .map { listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(it / 45).roundToInt().mod(8)] }.distinct().joinToString("/")
        val temps = temperatures.filterNotNull()
        fun range(values: List<Double>, decimals: Int = 0): String {
            if (values.isEmpty()) return "—"
            val min = values.min(); val max = values.max()
            fun f(value: Double) = String.format(Locale.US, "%.$decimals" + "f", value)
            return if (f(min) == f(max)) f(min) else "${f(min)}–${f(max)}"
        }
        val tideValue = if (tideComplete && event != null) "${if (turn == null) "Next " else ""}${if (event.high) "high" else "low"} ${time(event.at)} · ${"%.1f".format(Locale.US, event.height)} m CD\n${tideReferenceLabel(spot, station!!)}" else "—"
        val rows = listOf(
            ConditionItem("Wind", "${maxWind.roundToInt()} km/h · gust ${maxGust.roundToInt()}${if (directions.isNotEmpty()) " · $directions" else ""}", if (winds.any { it == null } || gusts.any { it == null }) needsDataMood else comfortMood(windBand)),
            ConditionItem("Rain", "${"%.1f".format(Locale.US, totalRain)} mm · peak ${"%.1f".format(Locale.US, peakRain)} mm/h · ${probability?.let { "${it.roundToInt()}% hourly" } ?: "chance —"}",
                if (covered != duration || rain.any { it == null }) needsDataMood else WindowMood(if (peakRain > .2) "🌧️" else if ((probability ?: 0.0) >= 60) "🌦️" else "🌤️", if (peakRain > .8) "Wet" else if (peakRain > 0) "Light rain" else if ((probability ?: 0.0) >= 60) "Rain possible" else "Mostly dry")),
            ConditionItem("Feels like", if (temps.isEmpty()) "—" else "${range(temps)}°C", if (!completeTemperature) needsDataMood else if (temps.min() < 12) WindowMood("🥶", "Cold") else if (temps.max() > 26) WindowMood("🥵", "Hot") else WindowMood("😌", "Mild")),
            ConditionItem("Tide", tideValue, tideMood),
            ConditionItem("Offshore waves", if (waves.isEmpty()) "—" else "${"%.1f".format(Locale.US, waves.max())} m · ${range(periods, 1)} s", if (!completeMarine) needsDataMood else WindowMood("🌊", when (preferences.setting) { ShoreSetting.ROCKS -> "Check surge"; ShoreSetting.BEACH -> "Check surf"; else -> "Check exposure" })),
            ConditionItem("Daylight", daylight?.let { "${(it * 100).roundToInt()}% ${if (visitKnown) "visit" else if (arrival != null || returning != null) "known time" else "session"}" } ?: "—", when {
                daylight == null -> needsDataMood
                daylight < 1 -> WindowMood("🌙", "After dark")
                !visitKnown -> WindowMood("🔎", "Visit times needed")
                else -> WindowMood("🌞", "Daylight")
            }),
            ConditionItem("Shore plan", "${preferences.setting.label} · before ${arrival?.let { "$it min" } ?: "—"} · return ${returning?.let { "$it min" } ?: "—"}", WindowMood("🔎", "Access unchecked"))
        )
        val details = buildList {
            add(when (preferences.setting) { ShoreSetting.ROCKS -> "Rock footing, surge and escape route unchecked."; ShoreSetting.BEACH -> "Surf, wading and beach access unchecked."; ShoreSetting.WHARF -> "Wharf access and exposure unchecked."; ShoreSetting.BANK -> "Bank footing and tide access unchecked."; else -> "Shore type and access unchecked." })
            add("Official warnings unchecked. Single forecast; model timing and agreement unverified.")
            if (!visitKnown) add("Access/setup or return time not set; only the session and known extra time are assessed.")
            if (!completeWeather) add("Visit weather coverage incomplete; displayed amounts use available samples.")
            if (!completeTemperature) add("Feels-like temperature coverage incomplete.")
            if (!tideComplete) add("Local tide coverage unverified.")
            else add("Tide times and heights are for the named reference station; local timing can differ.")
            if (!completeMarine) add("Offshore wave coverage incomplete.")
            if (probabilities.size != intervals.size) add("Rain likelihood incomplete.")
            if (retrievedAt == null || Duration.between(retrievedAt, now).seconds > 3 * 3600) add("Forecast freshness unverified or older than 3 hours.")
            if (waves.any { it >= 1.5 }) add("Elevated offshore waves; local exposure unchecked.")
            if (marineHours.any { (valid(it.wave) ?: 0.0) >= .8 && (valid(it.period) ?: 0.0) >= 12 }) add("Long-period waves; local surge unchecked.")
            if (codes.any { it == 45 || it == 48 }) add("Fog forecast; visibility needs checking.")
            if (temps.any { it < 12 } && peakRain > .2) add("Cold and wet conditions.")
            add("Rain chance is the highest hourly likelihood, not the chance for the whole visit. Offshore waves show significant height and mean period.")
            add("Trial comfort bands; not a catch forecast. CD = Chart Datum. Hourly weather timing is approximate.")
        }
        val age = Duration.between(now, start).seconds
        val confidence = when { age > 5 * 86400 -> WindowMood("🔭", "Early outlook"); age > 2 * 86400 -> WindowMood("🗓️", "Planning forecast"); else -> WindowMood("🤔", "Limited confidence") }
        val assessment = WindowAssessment(if (complete) comfortMood(grade) else needsDataMood, rows, confidence,
            details = details, band = grade, demandingHours = demanding, uncomfortableHours = uncomfortable,
            matchesComfort = complete && grade <= preferences.maxBand, comfortComplete = complete,
            maxWind = maxWind, maxGust = maxGust, rainTotal = totalRain, priority = preferences.priority)
        val reason = if (!complete) "Comfort assessment incomplete" else if (grade > preferences.maxBand) "Outside your comfort preference" else if (priority == WindowPriority.LATE_INCOMING) "Fits late incoming" else "Lower discomfort"
        return Recommendation(spot.name, spot.area, (100 - grade * 25), WindowEvaluation.formatWindow(start, end),
            "${distance.roundToInt()} km straight line", listOf(reason), boat = false,
            warnings = details, distanceKm = distance, startsAtEpochSeconds = start.epochSecond, durationHours = 2,
            summary = reason, conditions = rows.map { "${it.title}: ${it.value} · ${it.mood.emoji} ${it.mood.label}" },
            sourceNote = sourceNote, rankingValue = (3 - grade).toDouble(), dataComplete = complete,
            daylightFraction = daylight ?: 0.0, tidePreferenceFit = fit, assessment = assessment)
    }
}
