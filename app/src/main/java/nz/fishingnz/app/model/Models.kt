package nz.fishingnz.app.model

data class GeoPoint(val latitude: Double, val longitude: Double)
data class WeatherState(val temperature: String, val wind: String, val rain: String)
data class TideEvent(val time: String, val height: String, val type: String)
data class TidePoint(val time: String, val level: Double)
data class TideState(val currentLevel: String, val nextEvent: String, val eventTime: String, val events: List<TideEvent> = emptyList(), val points: List<TidePoint> = emptyList(), val stationName: String = "")
data class TideStation(val id: String, val name: String, val region: String, val latitude: Double, val longitude: Double)
data class Recommendation(val name: String, val area: String, val rating: Int, val time: String, val distance: String, val reasons: List<String>, val boat: Boolean = false)
data class FishCheck(val commonName: String, val scientificName: String, val confidence: Int, val minimumSize: String, val dailyLimit: String, val status: String, val note: String)

val sampleRecommendations = listOf(
    Recommendation("Mission Bay", "Auckland", 86, "6:10 – 8:40 AM", "18 min away", listOf("Incoming tide", "Light SW wind", "17–20°C")),
    Recommendation("Rangitoto Channel", "Auckland", 82, "7:00 – 10:00 AM", "25 min to ramp", listOf("Sheltered water", "Gentle swell", "Good current movement"), true),
    Recommendation("Takapuna Beach", "Auckland", 74, "5:30 – 7:30 PM", "22 min away", listOf("Low rain chance", "Outgoing tide", "Good evening light"))
)

val tideStations = listOf(
    TideStation("auckland", "Auckland Harbour", "Auckland", -36.84, 174.76), TideStation("manukau", "Manukau Harbour", "Auckland", -37.05, 174.65),
    TideStation("whangarei", "Whangārei Harbour", "Northland", -35.72, 174.32), TideStation("tauranga", "Tauranga Harbour", "Bay of Plenty", -37.64, 176.18),
    TideStation("coromandel", "Coromandel Harbour", "Waikato", -36.83, 175.50), TideStation("gisborne", "Gisborne Harbour", "Gisborne", -38.66, 178.02),
    TideStation("wellington", "Wellington Harbour", "Wellington", -41.28, 174.78), TideStation("nelson", "Nelson Harbour", "Nelson", -41.27, 173.28),
    TideStation("lyttelton", "Lyttelton Harbour", "Canterbury", -43.61, 172.72), TideStation("otago", "Otago Harbour", "Otago", -45.88, 170.51),
    TideStation("bluff", "Bluff Harbour", "Southland", -46.60, 168.33)
)
