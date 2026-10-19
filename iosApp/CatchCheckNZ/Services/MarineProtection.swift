import Foundation

/// What a protected area allows. Wording follows DOC's descriptions of each kind of area.
enum ProtectionKind: String, Decodable, Sendable {
    case reserve
    case highProtection = "high_protection"
    case seafloorProtection = "seafloor_protection"

    var label: String {
        switch self {
        case .reserve: "Marine reserve"
        case .highProtection: "High Protection Area"
        case .seafloorProtection: "Seafloor Protection Area"
        }
    }
    var rule: String {
        switch self {
        case .reserve: "No fishing or taking of any marine life."
        case .highProtection: "No recreational or commercial fishing."
        case .seafloorProtection: "Line fishing is allowed; trawling and dredging are banned, and some areas also restrict nets, pots or longlines."
        }
    }
    var noFishing: Bool { self != .seafloorProtection }
}

/// One area from `marine_protection.json`. Each polygon is an outer ring then its holes, as [longitude, latitude] pairs.
struct ProtectedArea: Decodable, Sendable {
    let name: String
    let kind: ProtectionKind
    let bbox: [Double]
    let polygons: [[[[Double]]]]

    /// Even-odd over every ring, so islands cut out of an area are outside it.
    func contains(_ point: GeoPoint) -> Bool {
        guard bbox.count == 4, (bbox[0]...bbox[2]).contains(point.longitude), (bbox[1]...bbox[3]).contains(point.latitude) else { return false }
        var inside = false
        for rings in polygons {
            for ring in rings {
                for (a, b) in zip(ring, ring.dropFirst()) where (a[1] > point.latitude) != (b[1] > point.latitude) {
                    if point.longitude < (b[0] - a[0]) * (point.latitude - a[1]) / (b[1] - a[1]) + a[0] { inside.toggle() }
                }
            }
        }
        return inside
    }

    /// Kilometres from `point` to the nearest boundary, on a local flat projection (fine within a few kilometres).
    func distanceKm(_ point: GeoPoint) -> Double {
        let kx = 111.32 * cos(point.latitude * .pi / 180), ky = 110.57
        var best = Double.greatestFiniteMagnitude
        for rings in polygons {
            for ring in rings {
                for (a, b) in zip(ring, ring.dropFirst()) {
                    let ax = (a[0] - point.longitude) * kx, ay = (a[1] - point.latitude) * ky
                    let dx = (b[0] - point.longitude) * kx - ax, dy = (b[1] - point.latitude) * ky - ay
                    let length = dx * dx + dy * dy
                    let t = length == 0 ? 0 : min(max(-(ax * dx + ay * dy) / length, 0), 1)
                    best = min(best, hypot(ax + t * dx, ay + t * dy))
                }
            }
        }
        return best
    }

    /// True when `point` is within roughly `km` of the area's bounding box, a cheap test before measuring.
    func isNear(_ point: GeoPoint, km: Double) -> Bool {
        guard bbox.count == 4 else { return false }
        let dLat = km / 110.57, dLon = km / (111.32 * cos(point.latitude * .pi / 180))
        return (bbox[0] - dLon...bbox[2] + dLon).contains(point.longitude) && (bbox[1] - dLat...bbox[3] + dLat).contains(point.latitude)
    }
}

/// A protected area at or near a place. `distanceKm` is 0 inside.
struct ProtectionNotice: Sendable {
    let area: ProtectedArea
    let inside: Bool
    let distanceKm: Double
    var emoji: String { area.kind.noFishing ? "⛔" : "⚠️" }
    var text: String { (inside ? "Inside \(area.name). " : "\(MarineProtection.distanceLabel(distanceKm)) from \(area.name). ") + area.kind.rule }
}

/// DOC marine reserves and Hauraki Gulf protection areas, bundled from `tools/build_marine_protection.py`. Android has
/// the same rules in `MarineProtection.kt`. Boundaries are simplified and can be out of date, so the app always points
/// to DOC and MPI for official boundaries, temporary closures and rāhui.
enum MarineProtection {
    struct Catalogue: Decodable, Sendable {
        let retrievedOn: String
        let attribution: String
        let areas: [ProtectedArea]
        enum CodingKeys: String, CodingKey { case retrievedOn = "retrieved_on", attribution, areas }
    }

    /// Within this distance of a no-fishing area, a place gets a notice.
    static let noFishingNoticeKm = 2.0
    /// Seafloor Protection Areas allow line fishing, so only a place in or right beside one gets a notice.
    static let seafloorNoticeKm = 0.5
    static let docURL = URL(string: "https://www.doc.govt.nz/marinereserves")!
    static let haurakiGulfURL = URL(string: "https://www.doc.govt.nz/haurakigulfmarinepark")!

    /// The bundled boundaries, read once. Nil if the file is missing or unreadable.
    static let bundled: Catalogue? = Bundle.main.url(forResource: "marine_protection", withExtension: "json")
        .flatMap { try? Data(contentsOf: $0) }.flatMap { try? decode($0) }

    static func decode(_ data: Data) throws -> Catalogue { try JSONDecoder().decode(Catalogue.self, from: data) }

    /// Areas a place is inside, then nearby ones, nearest first: at most `limit`.
    static func notices(at point: GeoPoint, in areas: [ProtectedArea], limit: Int = 2) -> [ProtectionNotice] {
        let found: [ProtectionNotice] = areas.compactMap { area in
            let reach = area.kind.noFishing ? noFishingNoticeKm : seafloorNoticeKm
            guard area.isNear(point, km: reach) else { return nil }
            if area.contains(point) { return ProtectionNotice(area: area, inside: true, distanceKm: 0) }
            let distance = area.distanceKm(point)
            return distance <= reach ? ProtectionNotice(area: area, inside: false, distanceKm: distance) : nil
        }
        return Array(found.sorted { a, b in
            if a.inside != b.inside { return a.inside }
            if a.area.kind.noFishing != b.area.kind.noFishing { return a.area.kind.noFishing }
            return a.distanceKm < b.distanceKm
        }.prefix(limit))
    }

    /// "Under 50 m", "200 m" or "1.4 km".
    static func distanceLabel(_ km: Double) -> String {
        if km < 0.05 { return "Under 50 m" }
        if km < 1 { return "\(Int((km * 1000 / 10).rounded()) * 10) m" }
        return String(format: "%.1f km", km)
    }

    static func sourceLine(_ catalogue: Catalogue) -> String {
        let input = DateFormatter(), output = DateFormatter()
        input.locale = Locale(identifier: "en_US_POSIX"); input.dateFormat = "yyyy-MM-dd"
        output.locale = Locale(identifier: "en_US_POSIX"); output.dateFormat = "d MMM yyyy"
        let date = input.date(from: catalogue.retrievedOn).map(output.string(from:)) ?? catalogue.retrievedOn
        return "Approximate DOC boundaries retrieved \(date); they may be out of date and are not for navigation. "
            + "Temporary closures and rāhui are not shown: check DOC and MPI before you fish."
    }
}
