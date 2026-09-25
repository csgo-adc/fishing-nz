import Foundation

struct GeoPoint: Equatable, Sendable { let latitude: Double; let longitude: Double }
struct WeatherState { let temperature: String; let wind: String; let rain: String }
struct TideEvent: Identifiable { let time: String; let height: String; let type: String; var id: String { "\(time)-\(type)" } }
struct TidePoint: Identifiable { let time: String; let level: Double; let minuteOfDay: Int; var id: Int { minuteOfDay } }
struct TideState { let currentLevel: String; let nextEvent: String; let eventTime: String; let events: [TideEvent]; let points: [TidePoint]; let stationName: String }
struct TideStation: Identifiable, Equatable { let id: String; let name: String; let region: String; let latitude: Double; let longitude: Double }
struct Recommendation: Identifiable, Equatable { let name: String; let area: String; let rating: Int; let time: String; let distance: String; let reasons: [String]; let boat: Bool; var warnings: [String] = []; var startsAt: Date? = nil; var endsAt: Date? = nil; var id: String { "\(boat ? "boat" : "land"):\(name)" } }
struct FishRuleDetail: Decodable, Identifiable { let label: String; let value: String; var id: String { "\(label)-\(value)" } }
struct FishRuleMatch: Decodable, Identifiable { let species: String; let dailyLimit: String?; let minimumSize: String?; let details: [FishRuleDetail]; var id: String { species + (dailyLimit ?? "") + (minimumSize ?? "") } }
struct FishCheck { let commonName: String; let scientificName: String; let confidence: Int; let areaName: String; let areaIsEstimated: Bool; let rulesReviewedAt: String?; let rulesSourceURL: URL?; let fishRules: [FishRuleMatch] }

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

let tideStations = [
    TideStation(id: "auckland", name: "Auckland", region: "Auckland", latitude: -36.843, longitude: 174.768),
    TideStation(id: "onehunga", name: "Onehunga", region: "Auckland", latitude: -36.924, longitude: 174.786),
    TideStation(id: "whangarei", name: "Whangārei", region: "Northland", latitude: -35.729, longitude: 174.325),
    TideStation(id: "tauranga", name: "Tauranga", region: "Bay of Plenty", latitude: -37.673, longitude: 176.169),
    TideStation(id: "raglan", name: "Raglan", region: "Waikato", latitude: -37.800, longitude: 174.883),
    TideStation(id: "thames", name: "Thames", region: "Waikato", latitude: -37.138, longitude: 175.540),
    TideStation(id: "kawhia", name: "Kawhia", region: "Waikato", latitude: -38.069, longitude: 174.820),
    TideStation(id: "whitianga", name: "Whitianga", region: "Coromandel", latitude: -36.837, longitude: 175.706),
    TideStation(id: "gisborne", name: "Gisborne", region: "Tairāwhiti", latitude: -38.661, longitude: 178.017),
    TideStation(id: "wellington", name: "Wellington", region: "Wellington", latitude: -41.285, longitude: 174.783),
    TideStation(id: "nelson", name: "Nelson", region: "Nelson", latitude: -41.263, longitude: 173.283),
    TideStation(id: "lyttelton", name: "Lyttelton", region: "Canterbury", latitude: -43.605, longitude: 172.720),
    TideStation(id: "akaroa", name: "Akaroa", region: "Canterbury", latitude: -43.804, longitude: 172.968),
    TideStation(id: "port-chalmers", name: "Port Chalmers", region: "Otago", latitude: -45.816, longitude: 170.621),
    TideStation(id: "dunedin", name: "Dunedin", region: "Otago", latitude: -45.874, longitude: 170.504),
    TideStation(id: "bluff", name: "Bluff", region: "Southland", latitude: -46.600, longitude: 168.333)
]
