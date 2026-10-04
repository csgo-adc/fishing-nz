package nz.fishingnz.app.data

import nz.fishingnz.app.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PlaceConditionsRepositoryTest {
    private val repository = PlaceConditionsRepository()
    private val now = Instant.parse("2026-09-27T03:00:00Z")
    private val date = LocalDate.parse("2026-09-27")
    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("conditions/$name.json")!!.readText())
    private fun data() = PlaceConditions(repository.decodeWeather(fixture("weather"), now), repository.decodeMarine(fixture("marine"), now), null, null)
    private fun rejects(work: () -> Unit) { try { work() } catch (_: Exception) { return }; fail("Invalid provider data accepted") }

    @Test fun mapPlacesAutomaticallyUseNearbyTidesWhenNoStationIsLinked() {
        val pin = ConditionPlace("Dropped pin", GeoPoint(-37.799, 174.87))
        assertEquals("raglan", pin.initialTideStation.id)
        val searchResult = ConditionPlace("Thames waterfront", GeoPoint(-37.133, 175.533))
        assertEquals("thames", searchResult.initialTideStation.id)
        assertEquals("thames", pin.copy(point = searchResult.point).initialTideStation.id)
    }
    @Test fun mapPlacesKeepTheirLinkedTideStation() {
        val linked = tideStations.first { it.id == "thames" }
        val place = ConditionPlace("Named fishing area", GeoPoint(-37.799, 174.87), station = linked)
        assertEquals(linked, place.initialTideStation)
    }

    @Test fun usesSeparateProviderHorizonsAndHistoryCap() {
        val point = GeoPoint(-37.1, 175.5)
        assertTrue(repository.weatherURL(point, 92).contains("forecast_days=16&past_days=92"))
        assertTrue(repository.marineURL(point, 92).contains("forecast_days=8&past_days=92"))
        assertTrue(repository.marineURL(point, 3).contains("cell_selection=sea"))
        assertEquals(92, PlaceConditionsRepository.MAX_PAST_DAYS)
    }
    @Test fun preservesLocalDatesAndActualTwentyThreeHourDay() {
        val data = data()
        assertEquals(17, data.dates.size)
        assertEquals(LocalDate.parse("2026-09-26"), data.dates.first())
        assertEquals(23, data.weatherHours(date).size)
        assertEquals(23, data.marineHours(date).size)
        assertEquals("More motion", data.rows(date).first().mood.label)
        assertEquals("6:30 AM–7:30 PM", data.rows(date).first { it.title == "Daylight" }.value)
    }
    @Test fun neverTurnsMissingChanceIntoZeroOrExtendsWaves() {
        val data = data()
        assertNull(data.weather!!.hours.last().chance)
        assertNull(data.weather.days.last().chance)
        assertTrue(data.marineHours(date.plusDays(10)).isEmpty())
        assertEquals("Needs more data", data.rows(date.plusDays(10)).first().mood.label)
        assertEquals("—", data.rows(date.plusDays(10)).first().value)
    }
    @Test fun rejectsWrongUnitsAndUnalignedArrays() {
        val marine = fixture("marine"); marine.getJSONObject("hourly_units").put("wave_height", "ft")
        rejects { repository.decodeMarine(marine, now) }
        val weather = fixture("weather"); weather.getJSONObject("hourly").getJSONArray("temperature_2m").remove(0)
        rejects { repository.decodeWeather(weather, now) }
        val scalar = fixture("marine"); scalar.getJSONObject("hourly").put("wave_period", 8)
        rejects { repository.decodeMarine(scalar, now) }
    }
    @Test fun independentSourcesKeepUsefulDays() {
        val full = data()
        val weatherOnly = full.copy(marine = null, marineIssue = "Unavailable")
        assertEquals(17, weatherOnly.dates.size)
        assertEquals("Needs more data", weatherOnly.rows(date).first().mood.label)
        val seaOnly = full.copy(weather = null, weatherIssue = "Unavailable")
        assertEquals(2, seaOnly.dates.size)
        assertEquals(23, seaOnly.hourDates(date).size)
        assertEquals("More motion", seaOnly.rows(date).first().mood.label)
        assertEquals("Needs more data", seaOnly.rows(date).first { it.title == "Wind" }.mood.label)
    }
    @Test fun knownHighWaveSurvivesIncompletePeriodData() {
        val full = data(); val at = full.marine!!.hours.keys.last()
        val partial = full.copy(marine = full.marine.copy(hours = mapOf(at to full.marine.hours.getValue(at).copy(height = 2.5, period = null))))
        assertEquals("High waves", partial.rows(date).first().mood.label)
        assertTrue(partial.rows(date).first().value.contains("2.5 m"))
    }
    @Test fun choppyFeelingRequiresHeightAndPeriodTogether() {
        val full = data(); val at = full.marine!!.hours.keys.last()
        val lowShort = full.marine.hours.mapValues { (_, hour) -> hour.copy(height = .7, period = 9.0) }.toMutableMap()
        lowShort[at] = lowShort.getValue(at).copy(height = .1, period = 4.0)
        assertEquals("More motion", full.copy(marine = full.marine.copy(hours = lowShort)).rows(date).first().mood.label)
        lowShort[at] = lowShort.getValue(at).copy(height = .7, period = 4.0)
        assertEquals("Choppy", full.copy(marine = full.marine.copy(hours = lowShort)).rows(date).first().mood.label)
    }
    @Test fun labelsDistinguishRainIntensityAndUnknownData() {
        assertEquals("Light rain", WeatherLabels.describe(61))
        assertEquals("Heavy rain", WeatherLabels.describe(65))
        assertEquals("Heavy drizzle", WeatherLabels.describe(55))
        assertEquals("Heavy freezing rain", WeatherLabels.describe(67))
        assertEquals("Thunderstorms with hail", WeatherLabels.describe(99))
        assertEquals("Weather unavailable", WeatherLabels.describe(null))
        assertEquals("Weather unavailable", WeatherLabels.describe(64))
    }
}
