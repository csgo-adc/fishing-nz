package nz.fishingnz.app.data

import nz.fishingnz.app.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WindowEvaluationTest {
    private val station = tideStations.first { it.id == "raglan" }
    private val spot = FishingSpot("Raglan", "Waikato", -37.8, 174.883333, false)
    private val date = LocalDate.of(2026, 9, 30)
    private val now = date.minusDays(3).atStartOfDay(WindowEvaluation.zone).toInstant()
    private fun at(hour: Int) = date.atTime(hour, 0).atZone(WindowEvaluation.zone).toInstant()
    private val tides by lazy { LinzTideSource.parse(resource("/tides/raglan-2026.csv"), station, 2026) }
    private val solar = mapOf(date to DaylightPeriod(at(7), at(19)))
    private fun samples(start: Int = 7) = (start..start + 2).map { ForecastHour(at(it), 5.0, 10.0, 0.0, 0.0, 0) }
    private fun marine(hours: List<ForecastHour>) = hours.associate { it.time to MarineSample(1.0, 8.0) }
    private fun evaluate(hours: List<ForecastHour> = samples(), waves: Map<Instant, MarineSample> = marine(hours)) =
        WindowEvaluation.evaluate(spot, 0.0, hours, waves, solar, tides, station, now, "Frozen test", WindowPriority.WEATHER)
    private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }

    @Test fun precedingHourRainAndGustUseEightAndNineNotSeven() {
        val hours = samples().mapIndexed { i, hour -> hour.copy(gust = if (i == 0) 90.0 else 14.0, rain = if (i == 0) 20.0 else .25) }
        val result = checkNotNull(evaluate(hours))
        assertTrue(result.conditions.any { it.contains("gusts up to 14") })
        assertTrue(result.conditions.any { it.contains("0.5 mm") })
        assertFalse(result.conditions.any { it.contains("20.0 mm") })
    }
    @Test fun missingStartIntervalDoesNotDiscardValidInstantaneousStart() {
        val hours = samples().toMutableList().apply { this[0] = first().copy(gust = null, rain = null, rainProbability = null) }
        assertNotNull(evaluate(hours))
    }
    @Test fun windAndWavesAtSessionEndAreIncluded() {
        val hours = samples().toMutableList().apply { this[2] = last().copy(wind = 60.0) }
        assertNull(evaluate(hours))
        val ordinary = samples()
        assertNull(evaluate(ordinary, marine(ordinary).toMutableMap().apply { put(at(9), MarineSample(3.5, 12.0)) }))
    }
    @Test fun knownAdverseWaveCannotBeErasedByAnotherMissingHour() {
        val hours = samples()
        val waves = marine(hours).toMutableMap().apply {
            put(at(7), MarineSample(3.5, 12.0)); put(at(8), MarineSample(null, null))
        }
        assertNull(evaluate(hours, waves))
    }
    @Test fun missingMarineCannotImprovePlanningOrder() {
        val complete = checkNotNull(evaluate())
        val partial = checkNotNull(evaluate(waves = emptyMap()))
        assertFalse(partial.dataComplete)
        assertTrue(windowOrder.compare(complete, partial) < 0)
        assertTrue(partial.summary.contains("missing"))
        assertTrue(partial.warnings.any { it.contains("incomplete") })
    }
    @Test fun partialDaylightCannotReceiveFullSessionDaylight() {
        assertEquals(.5, checkNotNull(evaluate(samples(6))).daylightFraction, .0001)
    }
    @Test fun overnightRequiresSolarCoverageForBothDays() {
        val first = at(23); val end = date.plusDays(1).atTime(1, 0).atZone(WindowEvaluation.zone).toInstant()
        assertNull(WindowEvaluation.daylightFraction(first, end, solar))
        val next = date.plusDays(1)
        val both = solar + (next to DaylightPeriod(next.atTime(7, 0).atZone(WindowEvaluation.zone).toInstant(), next.atTime(19, 0).atZone(WindowEvaluation.zone).toInstant()))
        assertEquals(0.0, WindowEvaluation.daylightFraction(first, end, both)!!, .0001)
    }
    @Test fun lateIncomingFitUsesOfficialHighAndNeverHeightDerivative() {
        assertEquals(0.0, WindowEvaluation.tideFit(at(7), at(9), tides), .0001)
        assertEquals(1.0, WindowEvaluation.tideFit(at(11), at(13), tides), .0001)
        assertEquals(0.0, WindowEvaluation.tideFit(at(11), at(13), emptyList()), .0001)
        val result = checkNotNull(evaluate(samples(11)))
        assertTrue(result.conditions.first().contains("1:23 PM"))
        assertFalse(result.conditions.any { it.contains("m/hour") })
    }
    @Test fun gapsAndMissingEndWeatherCannotProduceCompleteSession() {
        assertNull(evaluate(listOf(samples()[0], samples()[1], samples()[2].copy(time = at(10)))))
        assertNull(evaluate(samples().mapIndexed { i, h -> if (i == 2) h.copy(gust = null) else h }))
    }
    @Test fun providerUnitsTimestampOrderAndProbabilityAreValidated() {
        val engine = RecommendationEngine()
        val wrong = JSONObject(resource("/forecasts/raglan-weather.json"))
        wrong.getJSONObject("hourly_units").put("wind_speed_10m", "kn")
        assertThrows(IllegalArgumentException::class.java) { engine.decodeWeather(wrong, now) }
        val duplicated = JSONObject(resource("/forecasts/raglan-weather.json"))
        val times = duplicated.getJSONObject("hourly").getJSONArray("time")
        times.put(1, times.getLong(0))
        assertThrows(IllegalArgumentException::class.java) { engine.decodeWeather(duplicated, now) }
        val invalidChance = JSONObject(resource("/forecasts/raglan-weather.json"))
        invalidChance.getJSONObject("hourly").getJSONArray("precipitation_probability").put(0, 101)
        assertNull(engine.decodeWeather(invalidChance, now).hours.first().rainProbability)
    }
    @Test fun frozenRaglanSnapshotExplainsEarlyWeatherVersusLaterTide() {
        val engine = RecommendationEngine()
        val weather = engine.decodeWeather(JSONObject(resource("/forecasts/raglan-weather.json")), now)
        val marine = engine.decodeMarine(JSONObject(resource("/forecasts/raglan-marine.json")), now)
        val options = weather.hours.windowed(3).filter { it.first().time.atZone(WindowEvaluation.zone).toLocalDate() == date }.mapNotNull {
            WindowEvaluation.evaluate(spot, 0.0, it, marine.hours, weather.solar, tides, station, now, "Frozen 27 September 2026", WindowPriority.WEATHER)
        }
        val weatherChoice = options.minWith(windowOrder)
        val tideChoice = options.filter { it.tidePreferenceFit >= .8 }.minWith(windowOrder)
        assertEquals(at(7).epochSecond, weatherChoice.startsAtEpochSeconds)
        assertEquals(at(11).epochSecond, tideChoice.startsAtEpochSeconds)
        assertTrue(weatherChoice.conditions.any { it.contains("gusts up to 14") })
        assertTrue(tideChoice.conditions.any { it.contains("gusts up to 22") })
        assertTrue(tideChoice.conditions.any { it.contains("0.5 mm") })
        assertTrue(tideChoice.conditions.any { it.contains("1:23 PM") })
    }
}
