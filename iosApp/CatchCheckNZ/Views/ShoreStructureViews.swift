import SwiftUI
import MapKit

/// "Wharves and breakwaters nearby" for a land window: the three nearest within 10 km. Android shows the same card.
struct NearbyStructuresCard: View {
    let point: GeoPoint

    var body: some View {
        let nearby = ShoreStructures.nearest(point, in: ShoreStructures.bundled)
        Card {
            Text("Wharves and breakwaters nearby").bold().foregroundStyle(CatchCheckColor.navy)
            if nearby.isEmpty { Text("No wharf or breakwater is mapped within 10 km.").font(.subheadline) }
            ForEach(Array(nearby.enumerated()), id: \.offset) { _, item in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.structure.label).font(.subheadline.weight(.semibold)).foregroundStyle(CatchCheckColor.navy)
                        Text(BoatRamps.distanceLabel(item.km) + " " + BoatRamps.direction(from: point, to: item.structure.point) + " · " + item.structure.kindLabel)
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Open in Maps") {
                        let destination = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: item.structure.lat, longitude: item.structure.lon)))
                        destination.name = item.structure.label
                        destination.openInMaps()
                    }.font(.subheadline.weight(.semibold))
                }
            }
            Text("\(ShoreStructures.caution) \(ShoreStructures.credit).").font(.caption2).foregroundStyle(.secondary)
            Link("OpenStreetMap licence", destination: ShoreStructures.creditURL).font(.caption)
        }
    }
}

/// A wharf, breakwater or groyne on the map, shown in Land mode.
final class ShoreStructureAnnotation: NSObject, MKAnnotation {
    let structure: ShoreStructure
    let coordinate: CLLocationCoordinate2D
    var title: String? { structure.label }
    init(_ structure: ShoreStructure) { self.structure = structure; coordinate = CLLocationCoordinate2D(latitude: structure.lat, longitude: structure.lon) }
}
