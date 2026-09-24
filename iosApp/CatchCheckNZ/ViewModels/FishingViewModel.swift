import SwiftUI
import CoreLocation
import UIKit

@MainActor
final class FishingViewModel: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    @Published var selectedTab = 0 {
        didSet {
            guard oldValue != selectedTab else { return }
            let features = ["home", "map", "tide", "trip_planning", "fishing_rules", "account"]
            guard features.indices.contains(selectedTab) else { return }
            Task { await repository.trackEvent("feature_used", feature: features[selectedTab], platform: "ios") }
        }
    }
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
    @Published var account: AccountProfile?
    @Published var fishIdentityAvailable = false
    @Published var accountBusy = false
    @Published var accountError: String?
    @Published var accountNotice: String?
    @Published var verificationPending = false

    private let repository = FishingRepository()
    private let locationManager = CLLocationManager()
    private var pendingFishPhoto: UIImage?

    override init() { super.init(); locationManager.delegate = self; refreshConditions(); refreshStationTide(); refreshAccount() }
    func requestLocation() {
        if locationManager.authorizationStatus == .notDetermined { locationManager.requestWhenInUseAuthorization() }
        else if locationManager.authorizationStatus == .authorizedAlways || locationManager.authorizationStatus == .authorizedWhenInUse { locationManager.requestLocation() }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if pendingFishPhoto != nil {
            if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse { manager.requestLocation() }
            else if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted { identifyPendingFish(at: location) }
        }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let point = locations.last else { return }
        location = GeoPoint(latitude: point.coordinate.latitude, longitude: point.coordinate.longitude); hasDeviceLocation = true; refreshConditions()
        if pendingFishPhoto != nil { identifyPendingFish(at: location) }
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if pendingFishPhoto != nil { identifyPendingFish(at: location) } else { self.error = "Location unavailable" }
    }
    func chooseStation(_ station: TideStation) { selectedStation = station; refreshStationTide() }
    func changeTideDate(by days: Int) { tideDate = Calendar.current.date(byAdding: .day, value: days, to: tideDate) ?? tideDate; refreshStationTide() }
    func toggleSaved(_ spot: Recommendation) { if savedSpotNames.contains(spot.name) { savedSpotNames.remove(spot.name) } else { savedSpotNames.insert(spot.name) } }
    func startTrip(_ spot: Recommendation) { activeTrip = spot; selectedSpot = nil }
    func refreshAccount() {
        Task {
            do {
                let snapshot = try await repository.currentAccount()
                account = snapshot?.user
                fishIdentityAvailable = snapshot?.permissions?.features.fishIdentity ?? false
                if snapshot != nil { await repository.trackEvent("app_opened", feature: nil, platform: "ios") }
            } catch { account = nil; fishIdentityAvailable = false }
        }
    }
    func signIn(email: String, password: String, displayName: String, createAccount: Bool) {
        accountBusy = true; accountError = nil; accountNotice = nil
        Task {
            do {
                if createAccount {
                    accountNotice = try await repository.createAccount(email: email, password: password, displayName: displayName)
                    verificationPending = true
                } else {
                    let snapshot = try await repository.registerOrLogin(email: email, password: password)
                    account = snapshot.user; fishIdentityAvailable = snapshot.permissions?.features.fishIdentity ?? false; accountNotice = "You’re signed in."; verificationPending = false
                }
            } catch { accountError = error.localizedDescription }
            accountBusy = false
        }
    }
    func resendVerification(email: String) {
        accountBusy = true; accountError = nil; accountNotice = nil
        Task {
            do { accountNotice = try await repository.resendVerification(email: email); verificationPending = true }
            catch { accountError = error.localizedDescription }
            accountBusy = false
        }
    }
    func saveAccountProfile(displayName: String, countryCode: String) {
        accountBusy = true; accountError = nil; accountNotice = nil
        Task {
            do { let snapshot = try await repository.saveProfile(displayName: displayName, countryCode: countryCode); account = snapshot.user; fishIdentityAvailable = snapshot.permissions?.features.fishIdentity ?? false; accountNotice = "Profile saved." }
            catch { accountError = error.localizedDescription }
            accountBusy = false
        }
    }
    func sendFeedback(category: String, message: String, rating: Int) {
        accountBusy = true; accountError = nil; accountNotice = nil
        Task {
            do { try await repository.sendFeedback(category: category, message: message, rating: rating); accountNotice = "Thanks for your feedback." }
            catch { accountError = error.localizedDescription }
            accountBusy = false
        }
    }
    func signOut() {
        accountBusy = true; accountError = nil; accountNotice = nil
        Task { await repository.signOut(); account = nil; fishIdentityAvailable = false; verificationPending = false; accountBusy = false; accountNotice = "You’re signed out." }
    }
    func identifyFish() {
        guard let photo = selectedPhoto else { return }
        error = nil
        if hasDeviceLocation { identify(photo, at: location) }
        else if locationManager.authorizationStatus == .denied || locationManager.authorizationStatus == .restricted { identify(photo, at: location) }
        else { pendingFishPhoto = photo; isCheckingFish = true; requestLocation() }
    }
    private func identifyPendingFish(at point: GeoPoint) { guard let photo = pendingFishPhoto else { return }; pendingFishPhoto = nil; identify(photo, at: point) }
    private func identify(_ photo: UIImage, at point: GeoPoint) {
        isCheckingFish = true
        Task {
            do {
                fishCheck = try await repository.identifyFish(image: photo, at: point, hasDeviceLocation: hasDeviceLocation)
            } catch { self.error = error.localizedDescription }
            isCheckingFish = false
        }
    }
    func refreshConditions() { let point = location; Task { do { let result = try await repository.conditions(at: point); weather = result.0; currentTide = result.1; error = nil } catch { self.error = "Live conditions unavailable" } } }
    func refreshStationTide() { let station = selectedStation, date = tideDate; Task { stationTide = try? await repository.tide(for: station, date: date) } }
}
