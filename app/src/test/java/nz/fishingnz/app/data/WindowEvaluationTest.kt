package nz.fishingnz.app.data

import kotlinx.coroutines.runBlocking
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
    private fun samples(start: Int = 7) = (start..start + 2).map { ForecastHour(at(it), 5.0, 10.0, 0.0, 0.0, 0, feelsLike = 16.0) }
    private fun marine(hours: List<ForecastHour>) = hours.associate { it.time to MarineSample(1.0, 8.0) }
    private fun evaluate(hours: List<ForecastHour> = samples(), waves: Map<Instant, MarineSample> = marine(hours)) =
        WindowEvaluation.evaluate(spot, 0.0, hours, waves, solar, tides, station, now, "Frozen test", WindowPriority.WEATHER)
    private fun evaluateBoat(hours: List<ForecastHour> = samples(), waves: Map<Instant, MarineSample> = marine(hours),
                             priority: WindowPriority = WindowPriority.WEATHER, land: LandPreferences = LandPreferences()) =
        WindowEvaluation.evaluate(spot.copy(boat = true), 0.0, hours, waves, solar, tides, station, now, "Frozen test", priority, land)
    private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }

    @Test fun waitawaWindowsUseNearbyReferenceInsteadOfUnverifiedTides() = runBlocking {
        val waitawa = fishingSpots.first { it.name == "Waitawa Wharf" }
        val day = LocalDate.of(2026, 10, 5)
        val reference = loadTideReference(GeoPoint(waitawa.latitude, waitawa.longitude), recommendationTideStation(waitawa, null)) {
            LinzTideSource.parse(resource("/tides/man-owar-bay-2026.csv"), it, 2026, day, day)
        }
        assertEquals("man_owar_bay", reference.station.id)
        fun at(hour: Int) = day.atTime(hour, 0).atZone(WindowEvaluation.zone).toInstant()
        val hours = (13..15).map { ForecastHour(at(it), 5.0, 10.0, 0.0, 0.0, 0, feelsLike = 16.0) }
        val result = checkNotNull(WindowEvaluation.evaluate(waitawa, 0.0, hours, marine(hours),
            mapOf(day to DaylightPeriod(at(7), at(19))), reference.value, reference.station,
            at(0), "Test", WindowPriority.TIDE))
        val row = result.assessment!!.conditions.first { it.title == "Tide" }
        assertEquals("Around high", row.mood.label)
        assertTrue(row.value.contains("2:53 PM") && row.value.contains("3.0 m CD"))
        assertTrue(row.value.contains("Man o‘War Bay · 14.5 km away"))
        assertFalse(result.assessment.details.contains("Local tide coverage unverified."))
        assertTrue(result.tidePreferenceFit >= .8)
    }

    @Test fun precedingHourRainAndGustUseEightAndNineNotSeven() {
        val hours = samples().mapIndexed { i, hour -> hour.copy(gust = if (i == 0) 90.0 else 14.0, rain = if (i == 0) 20.0 else .25) }
        val result = checkNotNull(evaluate(hours))
        assertTrue(result.assessment!!.conditions.first { it.title == "Wind" }.value.contains("gust 14"))
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
        assertTrue(partial.dataComplete) // Weather comfort is separate from local wave confidence.
        assertEquals(0, windowOrder.compare(complete, partial))
        assertEquals("Local checks needed", partial.assessment!!.checks.label)
        assertTrue(partial.warnings.any { it.contains("Some wave data is missing") })
    }
    @Test fun boatLowerWavesOutrankDryCalmWindWithRoughWater() {
        val calmSeaHours = samples().map { it.copy(wind = 20.0, gust = 25.0, rain = .3, rainProbability = 50.0) }
        val calmSea = checkNotNull(evaluateBoat(calmSeaHours, calmSeaHours.associate { it.time to MarineSample(.5, 8.0) }))
        val roughSea = checkNotNull(evaluateBoat(waves = samples().associate { it.time to MarineSample(1.5, 4.0) }))
        assertTrue(windowOrder.compare(calmSea, roughSea) < 0)
        assertTrue(calmSea.rankingValue > roughSea.rankingValue)
        assertEquals(listOf("Waves first"), calmSea.reasons)
        assertTrue(roughSea.assessment!!.conditions.first().value.contains("1.5 m"))
    }
    @Test fun boatShortPeriodsLowerComfortAndShowConcernDespiteLowWind() {
        val short = checkNotNull(evaluateBoat(waves = samples().associate { it.time to MarineSample(1.0, 4.0) }))
        val spaced = checkNotNull(evaluateBoat(waves = samples().associate { it.time to MarineSample(1.0, 8.0) }))
        assertTrue(windowOrder.compare(spaced, short) < 0)
        assertTrue(short.warnings.any { it.startsWith("Short-period waves") })
        assertEquals("Demanding", short.windowMood.label)
        assertEquals("Choppy", short.assessment!!.conditions.first().mood.label)
        assertFalse(spaced.warnings.any { it.startsWith("Short-period waves") })
    }
    @Test fun boatWorstPairedSampleIncludesTheFinalEndpoint() {
        val hours = samples()
        val steady = hours.associate { it.time to MarineSample(.6, 8.0) }
        val ending = steady.toMutableMap().apply { put(at(9), MarineSample(.6, 4.0)) }
        val steadyResult = checkNotNull(evaluateBoat(hours, steady))
        val endingResult = checkNotNull(evaluateBoat(hours, ending))
        assertTrue(endingResult.rankingValue < steadyResult.rankingValue)
        assertTrue(endingResult.conditions[3].contains("4.0–8.0 s"))
        assertTrue(endingResult.warnings.any { it.startsWith("Short-period waves") })
    }
    @Test fun boatMissingPeriodCannotEarnWaveComfortCreditOrHideAdverseHeight() {
        val hours = samples()
        val complete = hours.associate { it.time to MarineSample(.6, 4.0) }
        val incomplete = complete.toMutableMap().apply { put(at(8), MarineSample(.6, null)) }
        val known = checkNotNull(evaluateBoat(hours, complete))
        val missing = checkNotNull(evaluateBoat(hours, incomplete))
        assertFalse(missing.dataComplete)
        assertTrue(missing.rankingValue < known.rankingValue)
        assertTrue(missing.rating <= 40)
        assertNull(evaluateBoat(hours, incomplete.toMutableMap().apply { put(at(9), MarineSample(2.0, null)) }))
    }
    @Test fun boatLateIncomingStillExplainsWaveComfortAndUsesTheSameRanking() {
        val hours = samples(11)
        val result = checkNotNull(evaluateBoat(hours, marine(hours), WindowPriority.TIDE))
        assertTrue(result.tidePreferenceFit >= .8)
        assertEquals(listOf("Matches your tide choice · waves first"), result.reasons)
        assertEquals(checkNotNull(evaluateBoat(hours, marine(hours))).rankingValue, result.rankingValue, .0001)
    }
    @Test fun boatColdAndHeatLimitOutlookWhileWavesStillLeadRanking() {
        val calm = samples().associate { it.time to MarineSample(.3, 8.0) }
        val mild = checkNotNull(evaluateBoat(waves = calm))
        val cold = checkNotNull(evaluateBoat(samples().map { it.copy(feelsLike = 4.0) }, calm))
        val hot = checkNotNull(evaluateBoat(samples().map { it.copy(feelsLike = 34.0) }, calm))
        val unknown = checkNotNull(evaluateBoat(samples().map { it.copy(feelsLike = null) }, calm))
        assertEquals("Demanding", cold.windowMood.label)
        assertEquals("Uncomfortable", hot.windowMood.label)
        assertEquals("Needs more data", unknown.windowMood.label)
        assertFalse(unknown.dataComplete)
        assertEquals(mild.rankingValue, cold.rankingValue, .0001)
        assertTrue(windowOrder.compare(mild, cold) < 0)
        // Within the same roughness limit, calmer waves still lead even when it is cold...
        val roughOk = LandPreferences(maxBand = 2)
        val coldRoughOk = checkNotNull(evaluateBoat(samples().map { it.copy(feelsLike = 4.0) }, calm, land = roughOk))
        assertTrue(windowOrder.compare(coldRoughOk, checkNotNull(evaluateBoat(land = roughOk))) < 0)
        // ...but with the default "A little chop" limit, a window inside the limit comes first.
        assertTrue(windowOrder.compare(checkNotNull(evaluateBoat()), cold) < 0)
    }
    @Test fun landComfortDoesNotChangeWithTheNewBoatWavePreference() {
        val hours = samples()
        val lower = checkNotNull(evaluate(hours, hours.associate { it.time to MarineSample(.5, 8.0) }))
        val higher = checkNotNull(evaluate(hours, hours.associate { it.time to MarineSample(1.0, 4.0) }))
        assertEquals(lower.rankingValue, higher.rankingValue, .0001)
        assertFalse(higher.warnings.any { it.startsWith("Short-period waves") })
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
    @Test fun tidePhasesUseOfficialEventsAndNeverHeightDerivative() {
        // Raglan, 30 September: low 7:02 AM, high 1:23 PM. Around high is 11:23 AM–2:23 PM.
        assertEquals(0.0, WindowEvaluation.tideFit(at(7), at(9), tides), .0001)
        assertEquals(1.0, WindowEvaluation.tideFit(at(12), at(14), tides), .0001)
        assertEquals(97.0 / 120, WindowEvaluation.tideFit(at(11), at(13), tides), .0001)
        // A four-hour session fits when it covers the whole three-hour band.
        assertEquals(1.0, WindowEvaluation.tideFit(at(11), at(15), tides), .0001)
        assertEquals(1.0, WindowEvaluation.tideFit(at(9), at(11), tides, TidePhase.INCOMING), .0001)
        assertEquals(58.0 / 120, WindowEvaluation.tideFit(at(6), at(8), tides, TidePhase.INCOMING), .0001)
        assertEquals(1.0, WindowEvaluation.tideFit(at(14), at(16), tides, TidePhase.OUTGOING), .0001)
        assertEquals(0.0, WindowEvaluation.tideFit(at(9), at(11), tides, TidePhase.OUTGOING), .0001)
        assertEquals(1.0, WindowEvaluation.tideFit(at(6), at(8), tides, TidePhase.AROUND_LOW), .0001)
        assertEquals(0.0, WindowEvaluation.tideFit(at(11), at(13), emptyList()), .0001)
        val result = checkNotNull(evaluate(samples(11)))
        assertTrue(result.assessment!!.conditions.first { it.title == "Tide" }.value.contains("1:23 PM"))
        assertFalse(result.conditions.any { it.contains("m/hour") })
    }
    @Test fun sunriseOrSunsetFitsTheHourEitherSide() {
        // The test day has sunrise at 7 AM and sunset at 7 PM.
        assertEquals(1.0, WindowEvaluation.sunFit(at(6), at(8), solar), .0001)
        assertEquals(1.0, WindowEvaluation.sunFit(at(18), at(20), solar), .0001)
        assertEquals(0.0, WindowEvaluation.sunFit(at(12), at(14), solar), .0001)
        val dawn = checkNotNull(WindowEvaluation.evaluate(spot, 0.0, samples(6), marine(samples(6)), solar, tides, station, now, "Test", WindowPriority.SUN))
        assertEquals("Around sunrise", dawn.assessment!!.conditions.first { it.title == "Daylight" }.mood.label)
        assertEquals(WindowPriority.SUN, dawn.assessment.preference)
        // Sunrise or sunset does not rank by daylight, so the partly dark dawn window is not pushed down.
        val noon = checkNotNull(WindowEvaluation.evaluate(spot, 0.0, samples(12), marine(samples(12)), solar, tides, station, now, "Test", WindowPriority.SUN))
        assertEquals(0, windowQuality.compare(dawn.copy(rankingValue = noon.rankingValue), noon))
    }
    @Test fun equallyGoodWindowsGoToTheNearerSpotBeforeTheEarlierStart() {
        val early = checkNotNull(evaluate(samples(9))).copy(name = "Far", distanceKm = 90.0)
        val later = checkNotNull(evaluate(samples(10))).copy(name = "Near", distanceKm = 10.0, startsAtEpochSeconds = early.startsAtEpochSeconds + 3600)
        assertEquals(listOf("Near", "Far"), listOf(early, later).sortedWith(searchOrder).map { it.name })
        assertEquals(listOf("Far", "Near"), listOf(later, early).sortedWith(windowOrder).map { it.name })
    }
    @Test fun boatRoughnessLimitMarksWindowsOutsideIt() {
        val choppy = samples().associate { it.time to MarineSample(1.4, 5.0) }
        val calmOnly = checkNotNull(evaluateBoat(waves = choppy, land = LandPreferences(maxBand = 0)))
        val rough = checkNotNull(evaluateBoat(waves = choppy, land = LandPreferences(maxBand = 2)))
        assertFalse(calmOnly.assessment!!.matchesComfort)
        assertTrue(rough.assessment!!.band > 0)
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
        val wrongTemperature = JSONObject(resource("/forecasts/raglan-weather.json"))
        val count = wrongTemperature.getJSONObject("hourly").getJSONArray("time").length()
        wrongTemperature.getJSONObject("hourly").put("apparent_temperature", org.json.JSONArray(List(count) { 16.0 }))
        wrongTemperature.getJSONObject("hourly_units").put("apparent_temperature", "°F")
        assertThrows(IllegalArgumentException::class.java) { engine.decodeWeather(wrongTemperature, now) }
        wrongTemperature.getJSONObject("hourly_units").put("apparent_temperature", "°C")
        assertEquals(16.0, engine.decodeWeather(wrongTemperature, now).hours.first().feelsLike!!, .0001)
        wrongTemperature.getJSONObject("hourly").put("apparent_temperature", org.json.JSONArray(listOf(16.0)))
        assertThrows(IllegalArgumentException::class.java) { engine.decodeWeather(wrongTemperature, now) }
        val missingChance = JSONObject(resource("/forecasts/raglan-weather.json"))
        missingChance.getJSONObject("hourly").remove("precipitation_probability")
        assertNull(engine.decodeWeather(missingChance, now).hours.first().rainProbability)
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
        assertTrue(weatherChoice.conditions.any { it.contains("gust 14") })
        assertTrue(tideChoice.conditions.any { it.contains("gust 22") })
        assertTrue(tideChoice.conditions.any { it.contains("0.5 mm") })
        assertTrue(tideChoice.conditions.any { it.contains("1:23 PM") })
    }
}
