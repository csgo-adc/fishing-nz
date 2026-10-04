package nz.fishingnz.app.data

import nz.fishingnz.app.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LandAssessmentTest {
    private val date = LocalDate.of(2026, 10, 4)
    private fun at(hour: Int) = date.atTime(hour, 0).atZone(WindowEvaluation.zone).toInstant()
    private val spot = FishingSpot("Shore reference", "Test", -37.1, 175.5, false)
    private val solar = mapOf(date to DaylightPeriod(at(7), at(19)))
    private fun hours(wind: Double = 10.0, gust: Double = 18.0, rain: Double = 0.0, temp: Double? = 16.0) =
        (8..10).map { ForecastHour(at(it), wind, gust, rain, 80.0, 0, feelsLike = temp) }
    private fun evaluate(core: List<ForecastHour> = hours(), all: List<ForecastHour> = core,
                         prefs: LandPreferences = LandPreferences(arrivalMinutes = 0, returnMinutes = 0),
                         waves: Map<java.time.Instant, MarineSample> = all.associate { it.time to MarineSample(.5, 8.0) },
                         sourceTime: java.time.Instant = at(7)) = WindowEvaluation.evaluate(spot, 0.0, core, waves, solar,
        emptyList(), null, at(7), "Test", WindowPriority.WEATHER, prefs, all, sourceTime)

    @Test fun calmLightRainBeatsDryGustyCastingConditions() {
        val gusty = checkNotNull(evaluate(hours(20.0, 32.0)))
        val wet = checkNotNull(evaluate(hours(rain = .3)))
        assertEquals("Demanding", gusty.windowMood.label)
        assertEquals("Okay", wet.windowMood.label)
        assertFalse(gusty.assessment!!.matchesComfort)
        assertTrue(windowOrder.compare(wet, gusty) < 0)
    }
    @Test fun coldCannotEarnPerfectComfortAndMissingTemperatureCannotImproveOrder() {
        val cold = checkNotNull(evaluate(hours(temp = 4.0)))
        val mild = checkNotNull(evaluate(hours(14.0, 25.0)))
        val unknown = checkNotNull(evaluate(hours(temp = null)))
        assertEquals("Uncomfortable", cold.windowMood.label)
        assertTrue(windowOrder.compare(mild, cold) < 0)
        assertEquals("Needs more data", unknown.windowMood.label)
        assertFalse(unknown.assessment!!.matchesComfort)
        assertTrue(windowOrder.compare(cold, unknown) < 0)
    }
    @Test fun wettestHourAndFinalTemperatureAreNotAveragedAway() {
        val burst = hours().mapIndexed { i, h -> h.copy(rain = if (i == 2) 2.0 else 0.0) }
        assertEquals("Uncomfortable", checkNotNull(evaluate(burst)).windowMood.label)
        val endpointCold = hours().mapIndexed { i, h -> if (i == 2) h.copy(feelsLike = 4.0) else h }
        assertEquals("Uncomfortable", checkNotNull(evaluate(endpointCold)).windowMood.label)
    }
    @Test fun rainLikelihoodChangesDescriptionWithoutChangingPhysicalComfort() {
        val likely = checkNotNull(evaluate(hours(rain = .15)))
        val unlikely = checkNotNull(evaluate(hours(rain = .15).map { it.copy(rainProbability = 20.0) }))
        val absent = checkNotNull(evaluate(hours(rain = .15).map { it.copy(rainProbability = null) }))
        assertEquals(likely.rankingValue, unlikely.rankingValue, 0.0)
        assertEquals(likely.rankingValue, absent.rankingValue, 0.0)
        assertTrue(absent.assessment!!.details.any { it.contains("Rain likelihood incomplete") })
    }
    @Test fun returnWeatherAndInterpolatedReturnWavesCanRuleOutAVisit() {
        val all = hours() + ForecastHour(at(11), 60.0, 18.0, 0.0, 0.0, 0, feelsLike = 16.0)
        assertNull(evaluate(all.take(3), all, LandPreferences(arrivalMinutes = 0, returnMinutes = 60)))
        val ordinary = all.map { it.copy(wind = 10.0) }
        val waves = ordinary.associate { it.time to MarineSample(if (it.time == at(10)) 2.0 else if (it.time == at(11)) 4.0 else .5, 8.0) }
        assertNull(evaluate(ordinary.take(3), ordinary, LandPreferences(arrivalMinutes = 0, returnMinutes = 30), waves))
        val sparse = ordinary.take(3) + ordinary.last().copy(time = at(12))
        assertNull(evaluate(sparse.take(3), sparse, LandPreferences(arrivalMinutes = 0, returnMinutes = 120), waves))
    }
    @Test fun daylightOnlyChecksReturnAndRequiresKnownBuffers() {
        val late = (17..20).map { ForecastHour(at(it), 10.0, 18.0, 0.0, 0.0, 0, feelsLike = 16.0) }
        assertNull(evaluate(late.take(3), late, LandPreferences(arrivalMinutes = 0, returnMinutes = 30, daylightOnly = true)))
        assertNull(evaluate(prefs = LandPreferences(daylightOnly = true)))
        assertNotNull(evaluate(prefs = LandPreferences(arrivalMinutes = 0, returnMinutes = 0, daylightOnly = true)))
        assertNull(evaluate(prefs = LandPreferences(arrivalMinutes = 120)))
    }
    @Test fun unknownSiteAndOldForecastNeverClaimSupportedConfidence() {
        val rocks = checkNotNull(evaluate(prefs = LandPreferences(setting = ShoreSetting.ROCKS), sourceTime = at(7).minusSeconds(14400)))
        assertEquals("Local checks needed", rocks.assessment!!.checks.label)
        assertEquals("Limited confidence", rocks.assessment.confidence.label)
        assertTrue(rocks.assessment.details.any { it.contains("Rock footing") })
        assertTrue(rocks.assessment.details.any { it.contains("older than 3 hours") })
    }
    @Test fun longWaveWarningKeepsHeightAndPeriodPaired() {
        val core = hours()
        val unpaired = mapOf(at(8) to MarineSample(1.0, 8.0), at(9) to MarineSample(.4, 14.0), at(10) to MarineSample(.4, 8.0))
        assertFalse(checkNotNull(evaluate(waves = unpaired)).warnings.any { it.startsWith("Long-period waves") })
        assertNull(evaluate(waves = unpaired + (at(10) to MarineSample(3.4, null))))
    }
    @Test fun completeTideOrWaveDataCannotOverrideWeatherComfort() {
        val normal = checkNotNull(evaluate())
        val missingWaves = checkNotNull(evaluate(waves = emptyMap()))
        assertEquals(0, windowOrder.compare(normal, missingWaves))
        assertEquals(normal.windowMood, missingWaves.windowMood)
        assertTrue(missingWaves.assessment!!.details.any { it.contains("wave coverage incomplete") })
    }
    @Test fun balancedModeOffersRealCalmerOrDrierPeersWithinComfortLimit() {
        val dry = checkNotNull(evaluate(hours(14.0, 25.0, .2)))
        val wetHours = hours(10.0, 18.0, .3).map { it.copy(time = it.time.plusSeconds(3600)) }
        val calmer = checkNotNull(evaluate(wetHours))
        assertEquals("Calmer alternative", landTradeoff(dry, listOf(dry, calmer))?.first)
        assertEquals("Drier alternative", landTradeoff(calmer, listOf(dry, calmer))?.first)
        val outside = checkNotNull(evaluate(hours(20.0, 32.0)))
        assertNull(landTradeoff(outside, listOf(outside, calmer)))
        val casting = checkNotNull(evaluate(prefs = LandPreferences(priority = LandPriority.CASTING)))
        assertNull(landTradeoff(casting, listOf(casting, dry, calmer)))
    }
    @Test fun oneKnownBufferIsIncludedWithoutClaimingTheWholeVisitIsKnown() {
        val all = hours() + ForecastHour(at(11), 10.0, 18.0, 1.0, 80.0, 0, feelsLike = 16.0)
        val result = checkNotNull(evaluate(all.take(3), all, LandPreferences(returnMinutes = 30)))
        assertEquals(.5, result.assessment!!.rainTotal, .0001)
        assertEquals("100% known time", result.assessment.conditions.first { it.title == "Daylight" }.value)
        assertEquals("Visit times needed", result.assessment.conditions.first { it.title == "Daylight" }.mood.label)
    }
}
