package nz.fishingnz.app.model

import java.time.LocalTime

data class GeoPoint(val latitude: Double, val longitude: Double)
data class WeatherState(val temperature: String, val wind: String, val rain: String)
data class TideEvent(val time: String, val height: String, val type: String)
data class TidePoint(val time: String, val level: Double, val minuteOfDay: Int)
data class TideState(val currentLevel: String, val nextEvent: String, val eventTime: String, val events: List<TideEvent> = emptyList(), val points: List<TidePoint> = emptyList(), val stationName: String = "")
data class TideStation(val id: String, val name: String, val region: String, val latitude: Double, val longitude: Double, val csvName: String = name)
data class FishingSpot(val name: String, val area: String, val latitude: Double, val longitude: Double, val boat: Boolean)
data class SearchOrigin(val name: String, val point: GeoPoint)
/** A daily interval; an end before the start means it continues into the next day. */
data class PreferredTimeRange(val start: LocalTime, val end: LocalTime)
data class ScoreFactor(val name: String, val score: Int, val weight: Int, val explanation: String)
data class Recommendation(
    val name: String,
    val area: String,
    val rating: Int,
    val time: String,
    val distance: String,
    val reasons: List<String>,
    val boat: Boolean = false,
    val factors: List<ScoreFactor> = emptyList(),
    val coveragePercent: Int = 100,
    val warning: String? = null,
    val warnings: List<String> = emptyList(),
    val distanceKm: Double = Double.NaN,
    val startsAtEpochSeconds: Long = 0,
    val durationHours: Int = 0
)
fun recommendationKey(item: Recommendation): String = "${if (item.boat) "boat" else "land"}:${item.name}"
data class RecommendationSearch(
    val items: List<Recommendation>,
    val nearbySpots: Int,
    val failedSpots: Int,
    val nearestSpot: String? = null,
    val nearestSpotDistanceKm: Int? = null
)
data class FishRuleDetail(val label: String, val value: String)
data class FishRuleMatch(val species: String, val dailyLimit: String?, val minimumSize: String?, val details: List<FishRuleDetail>, val minimumSizeLabel: String? = null)
data class FishCheck(val commonName: String, val scientificName: String, val confidence: Int, val areaName: String, val areaIsEstimated: Boolean, val rulesReviewedAt: String?, val fishRules: List<FishRuleMatch>, val rulesNeedsReview: Boolean = false, val rulesSourceUrl: String? = null, val areaSelectionRequired: Boolean = false)

// Named coastal search points. Wharf/pier names are recorded by LINZ tide predictions
// (https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions/tide-predictions-list-view)
// or local councils. Coordinates are approximate; a name does not guarantee fishing access.
val fishingSpots = listOf(
    FishingSpot("Whangārei Harbour", "Northland", -35.72, 174.32, false),
    FishingSpot("Mission Bay", "Auckland", -36.8485, 174.7633, false),
    FishingSpot("Rangitoto Channel", "Auckland", -36.78, 174.93, true),
    FishingSpot("Takapuna Beach", "Auckland", -36.786, 174.773, false),
    FishingSpot("Wellington Harbour", "Wellington", -41.28, 174.78, true),
    FishingSpot("Nelson Harbour", "Nelson", -41.27, 173.28, true),
    FishingSpot("Paihia Wharf", "Bay of Islands", -35.283, 174.091, false),
    FishingSpot("Bay of Islands", "Northland", -35.235, 174.18, true),
    FishingSpot("Mangōnui Harbour", "Far North", -34.99, 173.535, false),
    FishingSpot("Tutukākā Coast", "Northland", -35.602, 174.537, true),
    FishingSpot("Orewa Beach", "Auckland", -36.587, 174.696, false),
    FishingSpot("Muriwai Beach", "Auckland", -36.821, 174.427, false),
    FishingSpot("Manukau Harbour", "Auckland", -37.05, 174.65, true),
    FishingSpot("Coromandel Harbour", "Coromandel", -36.745, 175.5, false),
    FishingSpot("Whitianga Harbour", "Coromandel", -36.83, 175.705, true),
    FishingSpot("Raglan", "Waikato", -37.799, 174.87, false),
    FishingSpot("Raglan", "Waikato", -37.78, 174.82, true),
    FishingSpot("Thames", "Coromandel", -37.136, 175.526, false),
    FishingSpot("Thames", "Firth of Thames", -37.10, 175.42, true),
    FishingSpot("Tauranga", "Bay of Plenty", -37.64, 176.18, false),
    FishingSpot("Tauranga", "Bay of Plenty", -37.59, 176.20, true),
    FishingSpot("Kāwhia", "Waikato", -38.063, 174.82, false),
    FishingSpot("Kāwhia", "Waikato", -38.03, 174.79, true),
    FishingSpot("Waihī Beach", "Bay of Plenty", -37.4, 175.943, false),
    FishingSpot("Waihī Beach", "Bay of Plenty", -37.39, 175.98, true),
    FishingSpot("Whangamatā", "Coromandel", -37.21, 175.875, false),
    FishingSpot("Whangamatā", "Coromandel", -37.19, 175.92, true),
    FishingSpot("Mount Maunganui", "Bay of Plenty", -37.632, 176.185, false),
    FishingSpot("Ōhope Beach", "Bay of Plenty", -37.966, 177.058, false),
    FishingSpot("Gisborne Harbour", "Gisborne", -38.672, 178.02, true),
    FishingSpot("Napier Breakwater", "Hawke's Bay", -39.48, 176.92, false),
    FishingSpot("New Plymouth Coast", "Taranaki", -39.055, 174.075, false),
    FishingSpot("Whanganui River Mouth", "Whanganui", -39.946, 174.98, false),
    FishingSpot("Kāpiti Coast", "Wellington", -40.92, 174.98, false),
    FishingSpot("Eastbourne", "Wellington", -41.29, 174.9, false),
    FishingSpot("Marlborough Sounds", "Marlborough", -41.1, 174.2, true),
    FishingSpot("Picton Foreshore", "Marlborough", -41.288, 174.008, false),
    FishingSpot("Kaikōura Coast", "Canterbury", -42.404, 173.684, false),
    FishingSpot("Lyttelton Harbour", "Canterbury", -43.62, 172.75, true),
    FishingSpot("New Brighton Pier", "Canterbury", -43.506, 172.729, false),
    FishingSpot("Akaroa Harbour", "Canterbury", -43.81, 172.965, true),
    FishingSpot("Timaru Coast", "Canterbury", -44.395, 171.256, false),
    FishingSpot("Moeraki Coast", "Otago", -45.36, 170.86, false),
    FishingSpot("Otago Harbour", "Otago", -45.84, 170.64, true),
    FishingSpot("St Clair Beach", "Dunedin", -45.91, 170.49, false),
    FishingSpot("Bluff Harbour", "Southland", -46.60, 168.34, true),
    FishingSpot("Riverton Coast", "Southland", -46.35, 168.02, false),
    FishingSpot("Stewart Island / Rakiura", "Southland", -46.895, 168.13, true),
    FishingSpot("Greymouth Coast", "West Coast", -42.45, 171.2, false),
    FishingSpot("Hokitika Coast", "West Coast", -42.715, 170.96, false),
    FishingSpot("Westport Coast", "West Coast", -41.75, 171.60, false),
    FishingSpot("Cornwallis Wharf", "Auckland", -37.005, 174.608, false),
    FishingSpot("Greenhithe Wharf", "Auckland", -36.778, 174.676, false),
    FishingSpot("Murrays Bay Wharf", "Auckland", -36.731, 174.75, false),
    FishingSpot("Waitawa Wharf", "Auckland", -36.914, 175.143, false),
    FishingSpot("Opua Wharf", "Bay of Islands", -35.316, 174.119, false),
    FishingSpot("Marsden Point", "Northland", -35.835, 174.493, false),
    FishingSpot("Opononi Foreshore", "Hokianga", -35.51, 173.39, false),
    FishingSpot("Whangaroa Harbour", "Northland", -35.052, 173.744, false),
    FishingSpot("Huruhi Harbour", "Hauraki Gulf", -36.6, 175.766667, false),
    FishingSpot("Mātiatia Bay", "Waiheke Island", -36.783333, 174.983333, false),
    FishingSpot("Port Ōhope Wharf", "Bay of Plenty", -37.983333, 177.1, false),
    FishingSpot("Ōpōtiki Wharf", "Bay of Plenty", -38.033333, 177.233333, false),
    FishingSpot("Whakatāne River Mouth", "Bay of Plenty", -37.936, 177.001, false),
    FishingSpot("Whanganui River Entrance", "Whanganui", -39.953, 174.986, false),
    FishingSpot("Castlepoint Coast", "Wairarapa", -40.902, 176.232, false),
    FishingSpot("Picton Harbour", "Marlborough", -41.288, 174.008, true),
    FishingSpot("Kaiteriteri Beach", "Tasman", -41.035, 173.019, false),
    FishingSpot("Tarakohe Harbour", "Golden Bay", -40.824, 172.899, false),
    FishingSpot("Westport Harbour", "West Coast", -41.748, 171.596, true),
    FishingSpot("Oamaru Harbour", "Otago", -45.099, 170.97, false),
    FishingSpot("Timaru Harbour", "Canterbury", -44.393, 171.255, true),
    FishingSpot("Halfmoon Bay", "Stewart Island / Rakiura", -46.897, 168.131, false)
)

// Manual origins keep recommendations usable when device location is unavailable.
val searchOrigins = listOf(
    SearchOrigin("Whangārei", GeoPoint(-35.725, 174.324)),
    SearchOrigin("Kerikeri", GeoPoint(-35.227, 173.95)),
    SearchOrigin("Kaitaia", GeoPoint(-35.112, 173.263)),
    SearchOrigin("Whitianga", GeoPoint(-36.835, 175.705)),
    SearchOrigin("Hamilton", GeoPoint(-37.787, 175.279)),
    SearchOrigin("Auckland", GeoPoint(-36.8485, 174.7633)),
    SearchOrigin("Tauranga", GeoPoint(-37.686, 176.166)),
    SearchOrigin("Thames", GeoPoint(-37.138, 175.54)),
    SearchOrigin("Whakatāne", GeoPoint(-37.953, 176.991)),
    SearchOrigin("Gisborne", GeoPoint(-38.662, 178.018)),
    SearchOrigin("Napier", GeoPoint(-39.493, 176.912)),
    SearchOrigin("New Plymouth", GeoPoint(-39.057, 174.074)),
    SearchOrigin("Whanganui", GeoPoint(-39.932, 175.052)),
    SearchOrigin("Wellington", GeoPoint(-41.286, 174.776)),
    SearchOrigin("Nelson", GeoPoint(-41.271, 173.284)),
    SearchOrigin("Blenheim", GeoPoint(-41.513, 173.961)),
    SearchOrigin("Westport", GeoPoint(-41.755, 171.603)),
    SearchOrigin("Greymouth", GeoPoint(-42.449, 171.207)),
    SearchOrigin("Hokitika", GeoPoint(-42.717, 170.965)),
    SearchOrigin("Kaikōura", GeoPoint(-42.404, 173.681)),
    SearchOrigin("Christchurch", GeoPoint(-43.532, 172.636)),
    SearchOrigin("Timaru", GeoPoint(-44.397, 171.254)),
    SearchOrigin("Oamaru", GeoPoint(-45.097, 170.969)),
    SearchOrigin("Dunedin", GeoPoint(-45.878, 170.503)),
    SearchOrigin("Invercargill", GeoPoint(-46.413, 168.354)),
    SearchOrigin("Bluff", GeoPoint(-46.598, 168.33))
)

fun nearestCityName(point: GeoPoint): String {
    fun separation(origin: SearchOrigin): Double {
        val lat = Math.toRadians(point.latitude)
        val otherLat = Math.toRadians(origin.point.latitude)
        val dLat = otherLat - lat
        val dLon = Math.toRadians(origin.point.longitude - point.longitude)
        return kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(lat) * kotlin.math.cos(otherLat) *
            kotlin.math.sin(dLon / 2).let { it * it }
    }
    val nearest = searchOrigins.minBy(::separation)
    val distanceKm = 6_371.0 * 2 * kotlin.math.asin(kotlin.math.sqrt(separation(nearest).coerceIn(0.0, 1.0)))
    return if (distanceKm < 50) nearest.name else "Current location"
}

// Each added station was checked against LINZ's Daily Predictions list and the
// corresponding annual CSV. Offset-only sites cannot use this direct-CSV parser.
val tideStations = listOf(
    TideStation("akaroa", "Akaroa", "Canterbury", -43.8, 172.966667),
    TideStation("anakakata_bay", "Anakakata Bay", "New Zealand", -41.05, 174.283333),
    TideStation("anawhata", "Anawhata", "New Zealand", -36.933333, 174.45),
    TideStation("auckland", "Auckland", "Auckland", -36.85, 174.766667),
    TideStation("ben_gunn_wharf", "Ben Gunn Wharf", "New Zealand", -35.0, 173.266667),
    TideStation("bluff", "Bluff", "Southland", -46.6, 168.35),
    TideStation("castlepoint", "Castlepoint", "New Zealand", -40.916667, 176.216667),
    TideStation("charleston", "Charleston", "New Zealand", -41.908333, 171.433333),
    TideStation("dargaville", "Dargaville", "New Zealand", -35.933333, 173.866667),
    TideStation("deep_cove", "Deep Cove", "New Zealand", -45.466667, 167.15),
    TideStation("dog_island", "Dog Island", "New Zealand", -46.65, 168.416667),
    TideStation("dunedin", "Dunedin", "Otago", -45.883333, 170.5),
    TideStation("elaine_bay", "Elaine Bay", "New Zealand", -41.05, 173.766667),
    TideStation("elie_bay", "Elie Bay", "New Zealand", -41.131667, 173.991667),
    TideStation("flour_cask_bay", "Flour Cask Bay", "New Zealand", -47.283333, 167.483333),
    TideStation("fresh_water_basin", "Fresh Water Basin", "New Zealand", -44.666667, 167.933333),
    TideStation("gisborne", "Gisborne", "Gisborne", -38.666667, 178.033333),
    TideStation("green_island", "Green Island", "New Zealand", -45.95, 170.383333),
    TideStation("halfmoon_bay_oban", "Halfmoon Bay / Oban", "New Zealand", -46.9, 168.133333, "Halfmoon Bay - Oban"),
    TideStation("havelock", "Havelock", "New Zealand", -41.283333, 173.766667),
    TideStation("helensville", "Helensville", "New Zealand", -36.666667, 174.45),
    TideStation("huruhi_harbour", "Huruhi Harbour", "New Zealand", -36.6, 175.766667),
    TideStation("jackson_bay", "Jackson Bay", "New Zealand", -43.983333, 168.633333),
    TideStation("kaikoura", "Kaikōura", "New Zealand", -42.416667, 173.7),
    TideStation("kaiteriteri", "Kaiteriteri", "New Zealand", -41.05, 173.016667),
    TideStation("kaituna_river_entrance", "Kaituna River Entrance", "New Zealand", -37.75, 176.416667),
    TideStation("kawhia", "Kawhia", "Waikato", -38.066667, 174.816667),
    TideStation("korotiti_bay", "Korotiti Bay", "New Zealand", -36.183333, 175.483333),
    TideStation("leigh", "Leigh", "New Zealand", -36.283333, 174.8),
    TideStation("long_island", "Long Island", "New Zealand", -41.116667, 174.283333),
    TideStation("lottin_point_wakatiri", "Lottin Point / Wakatiri", "New Zealand", -37.55, 178.166667, "Lottin Point - Wakatiri"),
    TideStation("lyttelton", "Lyttelton", "Canterbury", -43.6, 172.716667),
    TideStation("man_owar_bay", "Man o‘War Bay", "New Zealand", -36.783333, 175.15),
    TideStation("mana_marina", "Mana Marina", "New Zealand", -41.1, 174.866667),
    TideStation("manu_bay", "Manu Bay", "New Zealand", -37.816667, 174.816667),
    TideStation("marsden_point", "Marsden Point", "New Zealand", -35.833333, 174.5),
    TideStation("motuara_island", "Motuara Island", "New Zealand", -41.093333, 174.271667),
    TideStation("moturiki_island", "Moturiki Island", "New Zealand", -37.633333, 176.183333),
    TideStation("mapua", "Māpua", "New Zealand", -41.25, 173.1),
    TideStation("matiatia_bay", "Mātiatia Bay", "New Zealand", -36.783333, 174.983333),
    TideStation("napier", "Napier", "New Zealand", -39.483333, 176.916667),
    TideStation("nelson", "Nelson", "Nelson", -41.266667, 173.266667),
    TideStation("new_brighton_pier", "New Brighton Pier", "New Zealand", -43.506667, 172.735),
    TideStation("north_cape_otou", "North Cape / Otou", "New Zealand", -34.416667, 173.033333, "North Cape - Otou"),
    TideStation("oamaru", "Oamaru", "New Zealand", -45.1, 170.983333),
    TideStation("omaha_bridge", "Omaha Bridge", "New Zealand", -36.341667, 174.765),
    TideStation("onehunga", "Onehunga", "Auckland", -36.933333, 174.783333),
    TideStation("opononi", "Opononi", "New Zealand", -35.5, 173.4),
    TideStation("opua", "Opua", "New Zealand", -35.316667, 174.116667),
    TideStation("paratutae_island", "Paratutae Island", "New Zealand", -37.05, 174.516667),
    TideStation("picton", "Picton", "New Zealand", -41.283333, 174.0),
    TideStation("port_chalmers", "Port Chalmers", "Otago", -45.816667, 170.65),
    TideStation("port_taranaki", "Port Taranaki", "New Zealand", -39.05, 174.033333),
    TideStation("port_ohope_wharf", "Port Ōhope Wharf", "New Zealand", -37.983333, 177.1),
    TideStation("pouto_point", "Pouto Point", "New Zealand", -36.366667, 174.183333),
    TideStation("raglan", "Raglan", "Waikato", -37.8, 174.883333),
    TideStation("rangatira_point", "Rangatira Point", "New Zealand", -40.85, 174.933333),
    TideStation("rangitaiki_river_entrance", "Rangitaiki River Entrance", "New Zealand", -37.916667, 176.866667),
    TideStation("richmond_bay", "Richmond Bay", "New Zealand", -41.015, 173.988333),
    TideStation("riverton_aparima", "Riverton / Aparima", "New Zealand", -46.366667, 168.016667, "Riverton - Aparima"),
    TideStation("spit_wharf", "Spit Wharf", "New Zealand", -45.783333, 170.716667),
    TideStation("sumner_head", "Sumner Head", "New Zealand", -43.566667, 172.766667),
    TideStation("tarakohe", "Tarakohe", "New Zealand", -40.816667, 172.9),
    TideStation("tauranga", "Tauranga", "Bay of Plenty", -37.65, 176.183333),
    TideStation("thames", "Thames", "Coromandel", -37.133333, 175.516667),
    TideStation("timaru", "Timaru", "New Zealand", -44.383333, 171.25),
    TideStation("town_basin", "Town Basin", "New Zealand", -35.716667, 174.333333),
    TideStation("tamaki_river", "Tāmaki River", "New Zealand", -36.911667, 174.861667),
    TideStation("waihopai_river_entrance", "Waihopai River Entrance", "New Zealand", -46.416667, 168.333333),
    TideStation("weiti_river_entrance", "Weiti River Entrance", "New Zealand", -36.65, 174.733333),
    TideStation("welcombe_bay", "Welcombe Bay", "New Zealand", -46.083333, 166.583333),
    TideStation("wellington", "Wellington", "Wellington", -41.283333, 174.783333),
    TideStation("westport", "Westport", "New Zealand", -41.75, 171.6),
    TideStation("whakatane", "Whakatāne", "New Zealand", -37.95, 177.0),
    TideStation("whanganui_river_entrance", "Whanganui River Entrance", "New Zealand", -39.95, 174.983333),
    TideStation("whangaroa", "Whangaroa", "New Zealand", -35.05, 173.75),
    TideStation("whangarei", "Whangārei", "Northland", -35.766667, 174.35),
    TideStation("whitianga", "Whitianga", "Coromandel", -36.833333, 175.7),
    TideStation("wilson_bay", "Wilson Bay", "New Zealand", -41.083333, 173.9),
    TideStation("okukari_bay", "Ōkukari Bay", "New Zealand", -41.2, 174.316667),
    TideStation("omokoroa", "Ōmokoroa", "New Zealand", -37.666667, 176.05),
    TideStation("opotiki_wharf", "Ōpōtiki Wharf", "New Zealand", -38.033333, 177.233333)
)

fun nearestTideStation(point: GeoPoint): TideStation {
    val latitude = Math.toRadians(point.latitude)
    val longitude = Math.toRadians(point.longitude)
    return tideStations.minBy { station ->
        val stationLatitude = Math.toRadians(station.latitude)
        val latitudeDelta = stationLatitude - latitude
        val longitudeDelta = Math.toRadians(station.longitude) - longitude
        val arc = kotlin.math.sin(latitudeDelta / 2).let { it * it } +
            kotlin.math.cos(latitude) * kotlin.math.cos(stationLatitude) *
            kotlin.math.sin(longitudeDelta / 2).let { it * it }
        arc
    }
}
