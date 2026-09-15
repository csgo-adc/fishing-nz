import Foundation
import CoreLocation

struct GeoPoint: Equatable { let latitude: Double; let longitude: Double }
struct WeatherState { let temperature: String; let wind: String; let rain: String }
struct TideEvent: Identifiable { let time: String; let height: String; let type: String; var id: String { "\(time)-\(type)" } }
struct TidePoint: Identifiable { let time: String; let level: Double; var id: String { time } }
struct TideState { let currentLevel: String; let nextEvent: String; let eventTime: String; let events: [TideEvent]; let points: [TidePoint]; let stationName: String }
struct TideStation: Identifiable, Equatable { let id: String; let name: String; let region: String; let latitude: Double; let longitude: Double }
struct Recommendation: Identifiable, Equatable { let name: String; let area: String; let rating: Int; let time: String; let distance: String; let reasons: [String]; let boat: Bool; var id: String { name } }
struct FishCheck { let commonName: String; let scientificName: String; let confidence: Int; let minimumSize: String; let dailyLimit: String; let status: String; let note: String }

let sampleRecommendations = [
    Recommendation(name: "Mission Bay", area: "Auckland", rating: 86, time: "6:10 – 8:40 AM", distance: "18 min away", reasons: ["Incoming tide", "Light SW wind", "17–20°C"], boat: false),
    Recommendation(name: "Rangitoto Channel", area: "Auckland", rating: 82, time: "7:00 – 10:00 AM", distance: "25 min to ramp", reasons: ["Sheltered water", "Gentle swell", "Good current movement"], boat: true),
    Recommendation(name: "Takapuna Beach", area: "Auckland", rating: 74, time: "5:30 – 7:30 PM", distance: "22 min away", reasons: ["Low rain chance", "Outgoing tide", "Good evening light"], boat: false)
]

let tideStations = [
    TideStation(id: "auckland", name: "Auckland Harbour", region: "Auckland", latitude: -36.84, longitude: 174.76),
    TideStation(id: "manukau", name: "Manukau Harbour", region: "Auckland", latitude: -37.05, longitude: 174.65),
    TideStation(id: "whangarei", name: "Whangārei Harbour", region: "Northland", latitude: -35.72, longitude: 174.32),
    TideStation(id: "tauranga", name: "Tauranga Harbour", region: "Bay of Plenty", latitude: -37.64, longitude: 176.18),
    TideStation(id: "wellington", name: "Wellington Harbour", region: "Wellington", latitude: -41.28, longitude: 174.78),
    TideStation(id: "nelson", name: "Nelson Harbour", region: "Nelson", latitude: -41.27, longitude: 173.28)
]

let mapSpots = [
    Recommendation(name: "Whangārei Harbour", area: "Northland", rating: 76, time: "Good now", distance: "Land fishing", reasons: [], boat: false),
    sampleRecommendations[0], sampleRecommendations[1], sampleRecommendations[2],
    Recommendation(name: "Wellington Harbour", area: "Wellington", rating: 78, time: "Good now", distance: "Boat fishing", reasons: [], boat: true),
    Recommendation(name: "Nelson Harbour", area: "Nelson", rating: 77, time: "Good now", distance: "Boat fishing", reasons: [], boat: true)
]

let mapCoordinates: [String: CLLocationCoordinate2D] = [
    "Whangārei Harbour": .init(latitude: -35.72, longitude: 174.32), "Mission Bay": .init(latitude: -36.8485, longitude: 174.7633), "Rangitoto Channel": .init(latitude: -36.78, longitude: 174.93), "Takapuna Beach": .init(latitude: -36.786, longitude: 174.773), "Wellington Harbour": .init(latitude: -41.28, longitude: 174.78), "Nelson Harbour": .init(latitude: -41.27, longitude: 173.28)
]
