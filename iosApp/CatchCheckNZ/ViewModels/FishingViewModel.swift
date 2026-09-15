import SwiftUI
import CoreLocation
import UIKit

@MainActor
final class FishingViewModel: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    @Published var selectedTab = 0
    @Published var isBoatFishing = false
    @Published var dateLabel = "This Saturday"
    @Published var location = GeoPoint(latitude: -36.85, longitude: 174.76)
    @Published var hasDeviceLocation = false
    @Published var weather: WeatherState?
    @Published var currentTide: TideState?
    @Published var selectedStation = tideStations[0]
    @Published var tideDate = Date.now
    @Published var stationTide: TideState?
    @Published var error: String?
    @Published var selectedPhoto: UIImage?
    @Published var fishCheck: FishCheck?
    @Published var isCheckingFish = false
    @Published var savedSpotNames = Set<String>()
    @Published var activeTrip: Recommendation?
    @Published var selectedSpot: Recommendation?
    @Published var showingResults = false

    private let repository = FishingRepository()
    private let locationManager = CLLocationManager()

    override init() { super.init(); locationManager.delegate = self; refreshConditions(); refreshStationTide() }
    func requestLocation() { locationManager.requestWhenInUseAuthorization(); locationManager.requestLocation() }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) { guard let point = locations.last else { return }; location = GeoPoint(latitude: point.coordinate.latitude, longitude: point.coordinate.longitude); hasDeviceLocation = true; refreshConditions() }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { self.error = "Location unavailable" }
    func chooseStation(_ station: TideStation) { selectedStation = station; refreshStationTide() }
    func changeTideDate(by days: Int) { tideDate = Calendar.current.date(byAdding: .day, value: days, to: tideDate) ?? tideDate; refreshStationTide() }
    func toggleSaved(_ spot: Recommendation) { if savedSpotNames.contains(spot.name) { savedSpotNames.remove(spot.name) } else { savedSpotNames.insert(spot.name) } }
    func startTrip(_ spot: Recommendation) { activeTrip = spot; selectedSpot = nil }
    func identifyFish() { guard selectedPhoto != nil else { return }; isCheckingFish = true; Task { fishCheck = await repository.identifyFish(); isCheckingFish = false } }
    func refreshConditions() { let point = location; Task { do { let result = try await repository.conditions(at: point); weather = result.0; currentTide = result.1; error = nil } catch { self.error = "Live conditions unavailable" } } }
    func refreshStationTide() { let station = selectedStation, date = tideDate; Task { stationTide = try? await repository.tide(for: station, date: date) } }
}
