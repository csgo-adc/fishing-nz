package nz.fishingnz.app.model

import java.time.LocalTime

data class GeoPoint(val latitude: Double, val longitude: Double)
data class WeatherState(val temperature: String, val wind: String, val rain: String)
data class TideEvent(val time: String, val height: String, val type: String)
data class TidePoint(val time: String, val level: Double, val minuteOfDay: Int)
data class TideState(val currentLevel: String, val nextEvent: String, val eventTime: String, val events: List<TideEvent> = emptyList(), val points: List<TidePoint> = emptyList(), val stationName: String = "")
data class TideStation(val id: String, val name: String, val region: String, val latitude: Double, val longitude: Double)
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
data class FishRuleMatch(val species: String, val dailyLimit: String?, val minimumSize: String?, val details: List<FishRuleDetail>)
data class FishCheck(val commonName: String, val scientificName: String, val confidence: Int, val areaName: String, val areaIsEstimated: Boolean, val rulesReviewedAt: String?, val fishRules: List<FishRuleMatch>)

// Known named fishing areas. Coordinates are approximate search points; users must check access and local rules.
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
    FishingSpot("Westport Coast", "West Coast", -41.75, 171.60, false)
)

// Manual origins keep recommendations usable when device location is unavailable.
val searchOrigins = listOf(
    SearchOrigin("Hamilton", GeoPoint(-37.787, 175.279)),
    SearchOrigin("Auckland", GeoPoint(-36.8485, 174.7633)),
    SearchOrigin("Tauranga", GeoPoint(-37.686, 176.166)),
    SearchOrigin("Thames", GeoPoint(-37.138, 175.54)),
    SearchOrigin("Wellington", GeoPoint(-41.286, 174.776)),
    SearchOrigin("Christchurch", GeoPoint(-43.532, 172.636)),
    SearchOrigin("Dunedin", GeoPoint(-45.878, 170.503))
)

val tideStations = listOf(
    TideStation("auckland", "Auckland", "Auckland", -36.85, 174.77),
    TideStation("onehunga", "Onehunga", "Auckland", -36.93, 174.78),
    TideStation("whangarei", "Whangārei", "Northland", -35.72, 174.32),
    TideStation("tauranga", "Tauranga", "Bay of Plenty", -37.64, 176.18),
    TideStation("raglan", "Raglan", "Waikato", -37.80, 174.883),
    TideStation("thames", "Thames", "Coromandel", -37.14, 175.54),
    TideStation("kawhia", "Kawhia", "Waikato", -38.06, 174.82),
    TideStation("whitianga", "Whitianga", "Coromandel", -36.83, 175.70),
    TideStation("gisborne", "Gisborne", "Gisborne", -38.66, 178.02),
    TideStation("wellington", "Wellington", "Wellington", -41.28, 174.78),
    TideStation("nelson", "Nelson", "Nelson", -41.27, 173.28),
    TideStation("lyttelton", "Lyttelton", "Canterbury", -43.61, 172.72),
    TideStation("akaroa", "Akaroa", "Canterbury", -43.81, 172.965),
    TideStation("port_chalmers", "Port Chalmers", "Otago", -45.81, 170.62),
    TideStation("dunedin", "Dunedin", "Otago", -45.88, 170.51),
    TideStation("bluff", "Bluff", "Southland", -46.60, 168.33)
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
