import Foundation

struct GeoPoint: Equatable, Sendable { let latitude: Double; let longitude: Double }
struct WeatherState { let temperature: String; let wind: String; let rain: String }
struct TideEvent: Identifiable { let time: String; let height: String; let type: String; var id: String { "\(time)-\(type)" } }
struct TidePoint: Identifiable { let time: String; let level: Double; let minuteOfDay: Int; var id: Int { minuteOfDay } }
struct TideState { let currentLevel: String; let nextEvent: String; let eventTime: String; let events: [TideEvent]; let points: [TidePoint]; let stationName: String }
struct TideStation: Identifiable, Equatable, Sendable { let id: String; let name: String; let csvName: String; let region: String; let latitude: Double; let longitude: Double }
struct Recommendation: Identifiable, Equatable { let name: String; let area: String; let rating: Int; let time: String; let distance: String; let reasons: [String]; let boat: Bool; var warnings: [String] = []; var startsAt: Date? = nil; var endsAt: Date? = nil; var id: String { "\(boat ? "boat" : "land"):\(name)" }; var windowID: String { "\(id):\(startsAt?.timeIntervalSince1970 ?? 0)" } }
struct FishRuleDetail: Decodable, Identifiable { let label: String; let value: String; var id: String { "\(label)-\(value)" } }
struct FishRuleMatch: Decodable, Identifiable { let species: String; let dailyLimit: String?; let minimumSize: String?; let minimumSizeLabel: String?; let details: [FishRuleDetail]; var id: String { species + (dailyLimit ?? "") + (minimumSize ?? "") } }
struct FishCheck { let commonName: String; let scientificName: String; let confidence: Int; let areaName: String; let areaIsEstimated: Bool; let rulesNeedsReview: Bool; let rulesReviewedAt: String?; let rulesSourceURL: URL?; let fishRules: [FishRuleMatch] }

struct AccountProfile: Decodable, Equatable {
    let id: String
    let email: String
    let displayName: String
    let countryCode: String
    let plan: String
    enum CodingKeys: String, CodingKey { case id, email, plan; case displayName = "display_name"; case countryCode = "country_code" }
}
struct AccountFeatures: Decodable { let fishIdentity: Bool; enum CodingKeys: String, CodingKey { case fishIdentity = "fish_identity" } }
struct AccountPermissions: Decodable { let plan: String; let features: AccountFeatures }
struct AccountSnapshot: Decodable { let user: AccountProfile; let permissions: AccountPermissions? }
struct AccountProfileEnvelope: Decodable { let user: AccountProfile }
struct AccountPermissionsEnvelope: Decodable { let plan: String; let features: AccountFeatures }
struct AccountAuthResponse: Decodable { let token: String; let user: AccountProfile; let permissions: AccountPermissions }

// Verified daily-prediction locations and coordinates from the LINZ tide prediction list and 2026 CSV headers.
// https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions/tide-predictions-list-view
// Offset/secondary ports are excluded because they require reference-port corrections.
let tideStations: [TideStation] = [
    .init(id: "akaroa", name: "Akaroa", csvName: "Akaroa", region: "Canterbury & West Coast", latitude: -43.8, longitude: 172.966667),
    .init(id: "anakakata-bay", name: "Anakakata Bay", csvName: "Anakakata Bay", region: "Upper South Island & Wellington", latitude: -41.05, longitude: 174.283333),
    .init(id: "anawhata", name: "Anawhata", csvName: "Anawhata", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.933333, longitude: 174.45),
    .init(id: "auckland", name: "Auckland", csvName: "Auckland", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.85, longitude: 174.766667),
    .init(id: "ben-gunn-wharf", name: "Ben Gunn Wharf", csvName: "Ben Gunn Wharf", region: "Northland & Hauraki Gulf", latitude: -35.0, longitude: 173.266667),
    .init(id: "bluff", name: "Bluff", csvName: "Bluff", region: "Southland & Rakiura", latitude: -46.6, longitude: 168.35),
    .init(id: "castlepoint", name: "Castlepoint", csvName: "Castlepoint", region: "Upper South Island & Wellington", latitude: -40.916667, longitude: 176.216667),
    .init(id: "charleston", name: "Charleston", csvName: "Charleston", region: "Upper South Island & Wellington", latitude: -41.908333, longitude: 171.433333),
    .init(id: "dargaville", name: "Dargaville", csvName: "Dargaville", region: "Northland & Hauraki Gulf", latitude: -35.933333, longitude: 173.866667),
    .init(id: "deep-cove", name: "Deep Cove", csvName: "Deep Cove", region: "Fiordland & Westland", latitude: -45.466667, longitude: 167.15),
    .init(id: "dog-island", name: "Dog Island", csvName: "Dog Island", region: "Southland & Rakiura", latitude: -46.65, longitude: 168.416667),
    .init(id: "dunedin", name: "Dunedin", csvName: "Dunedin", region: "Otago & Southland", latitude: -45.883333, longitude: 170.5),
    .init(id: "elaine-bay", name: "Elaine Bay", csvName: "Elaine Bay", region: "Upper South Island & Wellington", latitude: -41.05, longitude: 173.766667),
    .init(id: "elie-bay", name: "Elie Bay", csvName: "Elie Bay", region: "Upper South Island & Wellington", latitude: -41.131667, longitude: 173.991667),
    .init(id: "flour-cask-bay", name: "Flour Cask Bay", csvName: "Flour Cask Bay", region: "Southland & Rakiura", latitude: -47.283333, longitude: 167.483333),
    .init(id: "fresh-water-basin", name: "Fresh Water Basin", csvName: "Fresh Water Basin", region: "Fiordland & Westland", latitude: -44.666667, longitude: 167.933333),
    .init(id: "gisborne", name: "Gisborne", csvName: "Gisborne", region: "Lower North Island", latitude: -38.666667, longitude: 178.033333),
    .init(id: "green-island", name: "Green Island", csvName: "Green Island", region: "Otago & Southland", latitude: -45.95, longitude: 170.383333),
    .init(id: "halfmoon-bay-oban", name: "Halfmoon Bay / Oban", csvName: "Halfmoon Bay - Oban", region: "Southland & Rakiura", latitude: -46.9, longitude: 168.133333),
    .init(id: "havelock", name: "Havelock", csvName: "Havelock", region: "Upper South Island & Wellington", latitude: -41.283333, longitude: 173.766667),
    .init(id: "helensville", name: "Helensville", csvName: "Helensville", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.666667, longitude: 174.45),
    .init(id: "huruhi-harbour", name: "Huruhi Harbour", csvName: "Huruhi Harbour", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.6, longitude: 175.766667),
    .init(id: "jackson-bay", name: "Jackson Bay", csvName: "Jackson Bay", region: "Fiordland & Westland", latitude: -43.983333, longitude: 168.633333),
    .init(id: "kaikoura", name: "Kaikōura", csvName: "Kaikōura", region: "Canterbury & West Coast", latitude: -42.416667, longitude: 173.7),
    .init(id: "kaiteriteri", name: "Kaiteriteri", csvName: "Kaiteriteri", region: "Upper South Island & Wellington", latitude: -41.05, longitude: 173.016667),
    .init(id: "kaituna-river-entrance", name: "Kaituna River Entrance", csvName: "Kaituna River Entrance", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.75, longitude: 176.416667),
    .init(id: "kawhia", name: "Kawhia", csvName: "Kawhia", region: "Lower North Island", latitude: -38.066667, longitude: 174.816667),
    .init(id: "korotiti-bay", name: "Korotiti Bay", csvName: "Korotiti Bay", region: "Northland & Hauraki Gulf", latitude: -36.183333, longitude: 175.483333),
    .init(id: "leigh", name: "Leigh", csvName: "Leigh", region: "Northland & Hauraki Gulf", latitude: -36.283333, longitude: 174.8),
    .init(id: "long-island", name: "Long Island", csvName: "Long Island", region: "Upper South Island & Wellington", latitude: -41.116667, longitude: 174.283333),
    .init(id: "lottin-point-wakatiri", name: "Lottin Point / Wakatiri", csvName: "Lottin Point - Wakatiri", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.55, longitude: 178.166667),
    .init(id: "lyttelton", name: "Lyttelton", csvName: "Lyttelton", region: "Canterbury & West Coast", latitude: -43.6, longitude: 172.716667),
    .init(id: "man-o-war-bay", name: "Man o‘War Bay", csvName: "Man o‘War Bay", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.783333, longitude: 175.15),
    .init(id: "mana-marina", name: "Mana Marina", csvName: "Mana Marina", region: "Upper South Island & Wellington", latitude: -41.1, longitude: 174.866667),
    .init(id: "manu-bay", name: "Manu Bay", csvName: "Manu Bay", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.816667, longitude: 174.816667),
    .init(id: "mapua", name: "Māpua", csvName: "Māpua", region: "Upper South Island & Wellington", latitude: -41.25, longitude: 173.1),
    .init(id: "marsden-point", name: "Marsden Point", csvName: "Marsden Point", region: "Northland & Hauraki Gulf", latitude: -35.833333, longitude: 174.5),
    .init(id: "matiatia-bay", name: "Mātiatia Bay", csvName: "Mātiatia Bay", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.783333, longitude: 174.983333),
    .init(id: "motuara-island", name: "Motuara Island", csvName: "Motuara Island", region: "Upper South Island & Wellington", latitude: -41.093333, longitude: 174.271667),
    .init(id: "moturiki-island", name: "Moturiki Island", csvName: "Moturiki Island", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.633333, longitude: 176.183333),
    .init(id: "napier", name: "Napier", csvName: "Napier", region: "Lower North Island", latitude: -39.483333, longitude: 176.916667),
    .init(id: "nelson", name: "Nelson", csvName: "Nelson", region: "Upper South Island & Wellington", latitude: -41.266667, longitude: 173.266667),
    .init(id: "new-brighton-pier", name: "New Brighton Pier", csvName: "New Brighton Pier", region: "Canterbury & West Coast", latitude: -43.506667, longitude: 172.735),
    .init(id: "north-cape-otou", name: "North Cape / Otou", csvName: "North Cape - Otou", region: "Northland & Hauraki Gulf", latitude: -34.416667, longitude: 173.033333),
    .init(id: "oamaru", name: "Oamaru", csvName: "Oamaru", region: "Otago & Southland", latitude: -45.1, longitude: 170.983333),
    .init(id: "okukari-bay", name: "Ōkukari Bay", csvName: "Ōkukari Bay", region: "Upper South Island & Wellington", latitude: -41.2, longitude: 174.316667),
    .init(id: "omaha-bridge", name: "Omaha Bridge", csvName: "Omaha Bridge", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.341667, longitude: 174.765),
    .init(id: "omokoroa", name: "Ōmokoroa", csvName: "Ōmokoroa", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.666667, longitude: 176.05),
    .init(id: "onehunga", name: "Onehunga", csvName: "Onehunga", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.933333, longitude: 174.783333),
    .init(id: "opononi", name: "Opononi", csvName: "Opononi", region: "Northland & Hauraki Gulf", latitude: -35.5, longitude: 173.4),
    .init(id: "opotiki-wharf", name: "Ōpōtiki Wharf", csvName: "Ōpōtiki Wharf", region: "Lower North Island", latitude: -38.033333, longitude: 177.233333),
    .init(id: "opua", name: "Opua", csvName: "Opua", region: "Northland & Hauraki Gulf", latitude: -35.316667, longitude: 174.116667),
    .init(id: "paratutae-island", name: "Paratutae Island", csvName: "Paratutae Island", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.05, longitude: 174.516667),
    .init(id: "picton", name: "Picton", csvName: "Picton", region: "Upper South Island & Wellington", latitude: -41.283333, longitude: 174.0),
    .init(id: "port-chalmers", name: "Port Chalmers", csvName: "Port Chalmers", region: "Otago & Southland", latitude: -45.816667, longitude: 170.65),
    .init(id: "port-ohope-wharf", name: "Port Ōhope Wharf", csvName: "Port Ōhope Wharf", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.983333, longitude: 177.1),
    .init(id: "port-taranaki", name: "Port Taranaki", csvName: "Port Taranaki", region: "Lower North Island", latitude: -39.05, longitude: 174.033333),
    .init(id: "pouto-point", name: "Pouto Point", csvName: "Pouto Point", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.366667, longitude: 174.183333),
    .init(id: "raglan", name: "Raglan", csvName: "Raglan", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.8, longitude: 174.883333),
    .init(id: "rangatira-point", name: "Rangatira Point", csvName: "Rangatira Point", region: "Upper South Island & Wellington", latitude: -40.85, longitude: 174.933333),
    .init(id: "rangitaiki-river-entrance", name: "Rangitaiki River Entrance", csvName: "Rangitaiki River Entrance", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.916667, longitude: 176.866667),
    .init(id: "richmond-bay", name: "Richmond Bay", csvName: "Richmond Bay", region: "Upper South Island & Wellington", latitude: -41.015, longitude: 173.988333),
    .init(id: "riverton-aparima", name: "Riverton / Aparima", csvName: "Riverton - Aparima", region: "Southland & Rakiura", latitude: -46.366667, longitude: 168.016667),
    .init(id: "spit-wharf", name: "Spit Wharf", csvName: "Spit Wharf", region: "Otago & Southland", latitude: -45.783333, longitude: 170.716667),
    .init(id: "sumner-head", name: "Sumner Head", csvName: "Sumner Head", region: "Canterbury & West Coast", latitude: -43.566667, longitude: 172.766667),
    .init(id: "tamaki-river", name: "Tāmaki River", csvName: "Tāmaki River", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.911667, longitude: 174.861667),
    .init(id: "tarakohe", name: "Tarakohe", csvName: "Tarakohe", region: "Upper South Island & Wellington", latitude: -40.816667, longitude: 172.9),
    .init(id: "tauranga", name: "Tauranga", csvName: "Tauranga", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.65, longitude: 176.183333),
    .init(id: "thames", name: "Thames", csvName: "Thames", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.133333, longitude: 175.516667),
    .init(id: "timaru", name: "Timaru", csvName: "Timaru", region: "Canterbury & West Coast", latitude: -44.383333, longitude: 171.25),
    .init(id: "town-basin", name: "Town Basin", csvName: "Town Basin", region: "Northland & Hauraki Gulf", latitude: -35.716667, longitude: 174.333333),
    .init(id: "waihopai-river-entrance", name: "Waihopai River Entrance", csvName: "Waihopai River Entrance", region: "Southland & Rakiura", latitude: -46.416667, longitude: 168.333333),
    .init(id: "weiti-river-entrance", name: "Weiti River Entrance", csvName: "Weiti River Entrance", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.65, longitude: 174.733333),
    .init(id: "welcombe-bay", name: "Welcombe Bay", csvName: "Welcombe Bay", region: "Fiordland & Westland", latitude: -46.083333, longitude: 166.583333),
    .init(id: "wellington", name: "Wellington", csvName: "Wellington", region: "Upper South Island & Wellington", latitude: -41.283333, longitude: 174.783333),
    .init(id: "westport", name: "Westport", csvName: "Westport", region: "Upper South Island & Wellington", latitude: -41.75, longitude: 171.6),
    .init(id: "whakatane", name: "Whakatāne", csvName: "Whakatāne", region: "Auckland, Waikato & Bay of Plenty", latitude: -37.95, longitude: 177.0),
    .init(id: "whanganui-river-entrance", name: "Whanganui River Entrance", csvName: "Whanganui River Entrance", region: "Lower North Island", latitude: -39.95, longitude: 174.983333),
    .init(id: "whangarei", name: "Whangārei", csvName: "Whangārei", region: "Northland & Hauraki Gulf", latitude: -35.766667, longitude: 174.35),
    .init(id: "whangaroa", name: "Whangaroa", csvName: "Whangaroa", region: "Northland & Hauraki Gulf", latitude: -35.05, longitude: 173.75),
    .init(id: "whitianga", name: "Whitianga", csvName: "Whitianga", region: "Auckland, Waikato & Bay of Plenty", latitude: -36.833333, longitude: 175.7),
    .init(id: "wilson-bay", name: "Wilson Bay", csvName: "Wilson Bay", region: "Upper South Island & Wellington", latitude: -41.083333, longitude: 173.9)
]
