package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainConditionsTest {
    private fun plain(title: String, value: String) = PlainConditions.windowValue(ConditionItem(title, value, WindowMood("🙂", "Okay")))

    @Test fun windSaysHowStrongAndWhereFrom() {
        // The "S" is the direction, not seconds.
        assertEquals("Breezy: 22 km/h, gusts to 28 km/h, from the south", plain("Wind", "22 km/h · gust 28 · S"))
        assertEquals("Light wind: 8 km/h, gusts to 15 km/h, from SW, then W", plain("Wind", "8 km/h · gust 15 · SW/W"))
        assertEquals("Gentle breeze: 12 km/h, gusts to 20 km/h", plain("Wind", "12 km/h · gust 20"))
        assertEquals(listOf("Light wind", "Gentle breeze", "Breezy", "Windy", "Very windy"),
            listOf(11, 12, 20, 30, 40).map(PlainConditions::windStrength))
        assertEquals("Windy: up to 35 km/h, gusts to 50 km/h, from the north-east", PlainConditions.wind(34.6, 50.0, listOf("NE"), upTo = true))
        assertEquals("—", PlainConditions.wind(null, 20.0, listOf("N")))
    }

    @Test fun wavesSayHowFarApart() {
        assertEquals("Up to 1.2 m, 7 seconds apart", plain("Offshore waves", "1.2 m · 6.5–7.0 s"))
        assertEquals("Up to 1.4 m, 5–6 seconds apart", plain("Offshore waves", "1.4 m · 5.0–5.5 s"))
        assertEquals("Up to 0.8 m", PlainConditions.waves(0.8, "—"))
        assertEquals("—", plain("Offshore waves", "—"))
    }

    @Test fun rainSaysTotalAndHourlyChance() {
        assertEquals("0.4 mm in total, up to 40% chance in any hour", plain("Rain", "0.4 mm · peak 0.3 mm/h · 40% hourly"))
        assertEquals("No rain expected, up to 10% chance in any hour", plain("Rain", "0.0 mm · peak 0.0 mm/h · 10% hourly"))
        assertEquals("1.2 mm in total", plain("Rain", "1.2 mm · peak 0.8 mm/h · chance —"))
        assertEquals("No rain expected", plain("Rain", "0.0 mm · peak 0.0 mm/h · 0% hourly"))
        assertEquals("3.5 mm all day, up to 80% chance in any hour", PlainConditions.rain(3.5, 80.0, wholeDay = true))
        assertEquals("Up to 30% chance in any hour", PlainConditions.rain(null, 30.0, wholeDay = true))
    }

    @Test fun tideNamesTheStationWithoutChartDatumShorthand() {
        assertEquals("Next high tide 7:12 PM, 2.4 m\nTide station: Auckland (Waitematā)", plain("Tide", "Next high 7:12 PM · 2.4 m CD\nAuckland (Waitematā)"))
        assertEquals("Low tide Thu 15 Oct, 1:20 AM, 0.4 m\nTide station: Auckland, 3.0 km away", plain("Tide", "low Thu 15 Oct, 1:20 AM · 0.4 m CD\nLINZ Auckland · 3.0 km away"))
    }

    @Test fun daylightAndShorePlanReadAsSentences() {
        assertEquals("All in daylight", plain("Daylight", "100% session"))
        assertEquals("75% in daylight", plain("Daylight", "75% session"))
        assertEquals("Whole visit in daylight", plain("Daylight", "100% visit"))
        assertEquals("All after dark", plain("Daylight", "0% known time"))
        assertEquals("Beach · 20 min walk in · 15 min back", plain("Shore plan", "Beach · before 20 min · return 15 min"))
        assertEquals("Shore type not set · walk-in time not set · return time not set", plain("Shore plan", "Not set · before — · return —"))
    }

    @Test fun windTileNamesWhereTheWindStartsAndEnds() {
        assertEquals("5 km/h" to "Light wind · gusts 17 · from SW to E", PlainConditions.windTile("5 km/h · gust 17 · SW/SE/E"))
        assertEquals("22 km/h" to "Breezy · gusts 28 · from S", PlainConditions.windTile("22 km/h · gust 28 · S"))
    }

    @Test fun unknownTextIsLeftAlone() {
        assertEquals("14 km/h · gust 22 · SW and a few more…", plain("Wind", "14 km/h · gust 22 · SW and a few more…"))
        assertEquals("14–16°C", plain("Feels like", "14–16°C"))
    }
}
