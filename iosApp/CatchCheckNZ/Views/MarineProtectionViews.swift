import SwiftUI
import MapKit

/// A warning when `point` is inside or near a marine reserve or Hauraki Gulf protection area. Shows nothing elsewhere.
/// `compact` drops the source note and link, for the map's place card. The Android app shows the same card.
struct MarineProtectionNoticeView: View {
    let point: GeoPoint?
    var compact = false

    var body: some View {
        if let point, point.latitude.isFinite, point.longitude.isFinite, let catalogue = MarineProtection.bundled {
            let notices = MarineProtection.notices(at: point, in: catalogue.areas)
            if !notices.isEmpty {
                let noFishing = notices.contains { $0.area.kind.noFishing }
                let gulf = notices.contains { $0.area.kind != .reserve }
                VStack(alignment: .leading, spacing: 4) {
                    Text(notices.contains { $0.inside } ? "Inside a marine protected area" : "Marine protected area nearby").font(.subheadline.bold())
                    ForEach(notices, id: \.area.name) { Text("\($0.emoji) \($0.text)").font(.caption).fixedSize(horizontal: false, vertical: true) }
                    if !compact {
                        Text(MarineProtection.sourceLine(catalogue)).font(.caption2).fixedSize(horizontal: false, vertical: true)
                        Link(gulf ? "DOC Hauraki Gulf protection maps" : "DOC marine reserves",
                             destination: gulf ? MarineProtection.haurakiGulfURL : MarineProtection.docURL)
                            .font(.caption.weight(.semibold))
                    }
                }
                .foregroundStyle(noFishing ? CatchCheckColor.poorText : CatchCheckColor.cautionText)
                .tint(noFishing ? CatchCheckColor.poorText : CatchCheckColor.cautionText)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(noFishing ? CatchCheckColor.poorFill : CatchCheckColor.cautionFill, in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }
}

/// One polygon of a protected area on the map; `kind` picks its colour.
final class ProtectedAreaPolygon: MKPolygon {
    var kind: ProtectionKind = .reserve
}

enum MarineProtectionOverlays {
    /// Every protected area as map polygons, outer rings with their island holes. Built once.
    @MainActor static let all: [ProtectedAreaPolygon] = (MarineProtection.bundled?.areas ?? []).flatMap { area in
        area.polygons.compactMap { rings -> ProtectedAreaPolygon? in
            func coordinates(_ ring: [[Double]]) -> [CLLocationCoordinate2D] { ring.map { CLLocationCoordinate2D(latitude: $0[1], longitude: $0[0]) } }
            guard let outer = rings.first else { return nil }
            let holes = rings.dropFirst().map { ring in coordinates(ring).withUnsafeBufferPointer { MKPolygon(coordinates: $0.baseAddress!, count: $0.count) } }
            let polygon = coordinates(outer).withUnsafeBufferPointer {
                ProtectedAreaPolygon(coordinates: $0.baseAddress!, count: $0.count, interiorPolygons: holes)
            }
            polygon.kind = area.kind
            polygon.title = area.name
            return polygon
        }
    }

    /// Red where fishing is banned, amber for Seafloor Protection Areas; the same colours as Android.
    static func renderer(for polygon: ProtectedAreaPolygon) -> MKPolygonRenderer {
        let renderer = MKPolygonRenderer(polygon: polygon)
        let colour = polygon.kind.noFishing ? UIColor(red: 0xD6 / 255, green: 0x45 / 255, blue: 0x41 / 255, alpha: 1)
            : UIColor(red: 0xD9 / 255, green: 0x82 / 255, blue: 0x2B / 255, alpha: 1)
        renderer.fillColor = colour.withAlphaComponent(0.22)
        renderer.strokeColor = colour.withAlphaComponent(0.85)
        renderer.lineWidth = 1.4
        return renderer
    }
}
