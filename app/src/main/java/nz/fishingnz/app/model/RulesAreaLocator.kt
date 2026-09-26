package nz.fishingnz.app.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Suggests an MPI rules area from a device position. These are representative coastal
 * places, not legal area boundaries: a position on land cannot establish which marine
 * area a later fishing spot falls in. The UI must still ask fishers to check the
 * official MPI map for the exact spot and allow them to change the suggestion.
 *
 * Area descriptions: https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/
 */
internal fun suggestedRulesAreaId(point: GeoPoint): String? {
    if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
        point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return null

    // Special marine areas should only be suggested near their own coastlines.
    val special = specialRulesAreaAnchors.minByOrNull { distanceKm(point, it.point) }
    if (special != null && distanceKm(point, special.point) <= special.maxDistanceKm) return special.areaId

    val nearest = generalRulesAreaAnchors.minByOrNull { distanceKm(point, it.point) } ?: return null
    return nearest.areaId.takeIf { distanceKm(point, nearest.point) <= 180.0 }
}

private data class RulesAreaAnchor(val areaId: String, val point: GeoPoint, val maxDistanceKm: Double = 0.0)

private val specialRulesAreaAnchors = listOf(
    RulesAreaAnchor("kaikoura", GeoPoint(-42.40, 173.68), 45.0),
    RulesAreaAnchor("fiordland", GeoPoint(-44.67, 167.93), 35.0), // Milford Sound
    RulesAreaAnchor("fiordland", GeoPoint(-45.28, 166.87), 35.0), // Doubtful Sound
    RulesAreaAnchor("fiordland", GeoPoint(-45.74, 166.79), 35.0), // Dusky Sound
    RulesAreaAnchor("chatham-rise", GeoPoint(-43.95, -176.56), 150.0)
)

private val generalRulesAreaAnchors = listOf(
    // Auckland / Kermadec: North Cape to Cape Runaway (east) and Tirua Point (west).
    RulesAreaAnchor("auckland-kermadec", GeoPoint(-35.11, 173.26)), // Kaitaia
    RulesAreaAnchor("auckland-kermadec", GeoPoint(-35.73, 174.32)), // Whangārei
    RulesAreaAnchor("auckland-kermadec", GeoPoint(-36.85, 174.76)), // Auckland
    RulesAreaAnchor("auckland-kermadec", GeoPoint(-37.79, 175.28)), // Hamilton
    RulesAreaAnchor("auckland-kermadec", GeoPoint(-37.80, 174.87)), // Raglan
    RulesAreaAnchor("auckland-kermadec", GeoPoint(-36.84, 175.71)), // Whitianga
    RulesAreaAnchor("auckland-kermadec", GeoPoint(-37.69, 176.17)), // Tauranga
    RulesAreaAnchor("auckland-kermadec", GeoPoint(-37.95, 177.00)), // Whakatāne
    RulesAreaAnchor("auckland-kermadec", GeoPoint(-38.06, 174.83)), // Kāwhia

    // Central: Cape Runaway to Tirua Point around the North Island's south coast.
    RulesAreaAnchor("central", GeoPoint(-38.66, 178.02)), // Gisborne
    RulesAreaAnchor("central", GeoPoint(-39.49, 176.91)), // Napier
    RulesAreaAnchor("central", GeoPoint(-39.06, 174.07)), // New Plymouth
    RulesAreaAnchor("central", GeoPoint(-39.95, 174.98)), // Whanganui
    RulesAreaAnchor("central", GeoPoint(-41.29, 174.78)), // Wellington

    // Challenger: northern and western South Island, including the Sounds.
    RulesAreaAnchor("challenger", GeoPoint(-40.82, 172.90)), // Golden Bay
    RulesAreaAnchor("challenger", GeoPoint(-41.27, 173.28)), // Nelson
    RulesAreaAnchor("challenger", GeoPoint(-41.29, 174.01)), // Picton
    RulesAreaAnchor("challenger", GeoPoint(-41.75, 171.60)), // Westport
    RulesAreaAnchor("challenger", GeoPoint(-42.45, 171.20)), // Greymouth
    RulesAreaAnchor("challenger", GeoPoint(-42.72, 170.97)), // Hokitika

    // South-East: the east coast from Clarence Point to Slope Point.
    RulesAreaAnchor("south-east", GeoPoint(-43.53, 172.64)), // Christchurch
    RulesAreaAnchor("south-east", GeoPoint(-44.40, 171.25)), // Timaru
    RulesAreaAnchor("south-east", GeoPoint(-45.10, 170.97)), // Oamaru
    RulesAreaAnchor("south-east", GeoPoint(-45.88, 170.50)), // Dunedin

    // Southland: the southern coast and Rakiura, outside the Fiordland special area.
    RulesAreaAnchor("southland", GeoPoint(-46.41, 168.35)), // Invercargill
    RulesAreaAnchor("southland", GeoPoint(-46.60, 168.33)), // Bluff
    RulesAreaAnchor("southland", GeoPoint(-46.90, 168.13)) // Rakiura
)

private fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val deltaLat = lat2 - lat1
    val longitudeGap = ((b.longitude - a.longitude + 540.0) % 360.0) - 180.0
    val deltaLon = Math.toRadians(longitudeGap)
    val halfChord = sin(deltaLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
    return 12_742.0 * asin(sqrt(halfChord.coerceIn(0.0, 1.0)))
}
