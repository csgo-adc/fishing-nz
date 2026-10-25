import SwiftUI
import MapKit

/// "Boat ramps nearby" for a boat window: the three nearest public ramps within 25 km, and a note when a low tide falls
/// within an hour of the trip. Android shows the same card.
struct NearbyRampsCard: View {
    let point: GeoPoint
    let start: Date?
    let end: Date?
    @State private var lowTide: String?

    var body: some View {
        let nearby = BoatRamps.nearest(point, in: BoatRamps.bundled)
        Card {
            Text("Boat ramps nearby").bold().foregroundStyle(CatchCheckColor.navy)
            if nearby.isEmpty { Text("No public boat ramp is mapped within 25 km.").font(.subheadline) }
            ForEach(Array(nearby.enumerated()), id: \.offset) { _, item in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.ramp.label).font(.subheadline.weight(.semibold)).foregroundStyle(CatchCheckColor.navy)
                        Text(BoatRamps.distanceLabel(item.km) + " " + BoatRamps.direction(from: point, to: item.ramp.point) + (item.ramp.fee == "yes" ? " · fee" : item.ramp.fee == "no" ? " · free" : ""))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Open in Maps") {
                        let destination = MKMapItem(placemark: MKPlacemark(coordinate: CLLocationCoordinate2D(latitude: item.ramp.lat, longitude: item.ramp.lon)))
                        destination.name = item.ramp.label
                        destination.openInMaps()
                    }.font(.subheadline.weight(.semibold))
                }
            }
            if let lowTide { Text(lowTide).font(.caption).foregroundStyle(CatchCheckColor.orange) }
            Text("\(BoatRamps.caution) \(BoatRamps.credit).").font(.caption2).foregroundStyle(.secondary)
            Link("OpenStreetMap licence", destination: BoatRamps.creditURL).font(.caption)
        }
        .task(id: "\(point.latitude),\(point.longitude),\(start?.timeIntervalSince1970 ?? 0)") {
            guard let start, let end, let station = nearbyTideStations(point: point).first,
                  let predictions = try? await LINZTideStore.shared.predictions(stationName: station.csvName, start: start.addingTimeInterval(-86_400), end: end.addingTimeInterval(86_400))
            else { return }
            let sorted = predictions.sorted { $0.time < $1.time }
            let lows = sorted.indices.filter { index in
                [index - 1, index + 1].filter { sorted.indices.contains($0) }.allSatisfy { sorted[index].height < sorted[$0].height }
            }.map { sorted[$0].time }
            lowTide = BoatRamps.lowTideNote(lows: lows, start: start, end: end)
        }
    }
}

/// A boat ramp on the map, shown in Boat mode.
final class BoatRampAnnotation: NSObject, MKAnnotation {
    let ramp: BoatRamp
    let coordinate: CLLocationCoordinate2D
    var title: String? { ramp.label }
    init(_ ramp: BoatRamp) { self.ramp = ramp; coordinate = CLLocationCoordinate2D(latitude: ramp.lat, longitude: ramp.lon) }
}
