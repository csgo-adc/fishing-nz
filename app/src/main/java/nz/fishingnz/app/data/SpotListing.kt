package nz.fishingnz.app.data

import nz.fishingnz.app.model.FishingSpot
import nz.fishingnz.app.model.GeoPoint
import java.util.Locale

/** A fishing spot as the Spots list shows it: how far it is from the phone and what is close by. */
data class SpotListing(val spot: FishingSpot, val distanceKm: Double?, val nearby: String?, val warning: String?)

/**
 * Builds the Spots tab's list: nearest first when the phone's position is known, otherwise by area and name. The
 * iPhone app has the same rules in `FeatureViews.swift`.
 */
object SpotListings {
    fun build(spots: List<FishingSpot>, from: GeoPoint?, ramps: List<BoatRamp>?, structures: List<ShoreStructure>?,
              protectedAreas: List<ProtectedArea>?): List<SpotListing> = spots.map { spot ->
        val point = GeoPoint(spot.latitude, spot.longitude)
        val nearby = if (spot.boat) ramps?.let { BoatRamps.nearest(point, it, limit = 1, withinKm = 15.0).firstOrNull() }
            ?.let { "Boat ramp ${BoatRamps.distanceLabel(it.distanceKm)} away" }
        else structures?.let { ShoreStructures.nearest(point, it, limit = 1, withinKm = 3.0).firstOrNull() }
            ?.let { "${it.structure.kindLabel.replaceFirstChar(Char::uppercase)} ${BoatRamps.distanceLabel(it.distanceKm)} away" }
        val warning = protectedAreas?.let { MarineProtection.notices(point, it, limit = 1).firstOrNull() }?.let { notice ->
            when {
                notice.area.kind.noFishing && notice.inside -> "Inside a no-fishing area"
                notice.area.kind.noFishing -> "No-fishing area nearby"
                else -> "Seafloor Protection Area nearby"
            }
        }
        SpotListing(spot, from?.let { placeDistanceKm(it, point) }, nearby, warning)
    }.sortedWith(if (from != null) compareBy { it.distanceKm } else compareBy({ it.spot.area }, { it.spot.name }))

    /** True when [query] is empty or appears in the spot's name or area. */
    fun matches(listing: SpotListing, query: String): Boolean = query.isBlank() ||
        listing.spot.name.contains(query.trim(), ignoreCase = true) || listing.spot.area.contains(query.trim(), ignoreCase = true)

    /** "800 m", "4.2 km" or "120 km". */
    fun distanceLabel(km: Double): String = if (km < 100) BoatRamps.distanceLabel(km) else String.format(Locale.US, "%d km", km.toInt())
}
