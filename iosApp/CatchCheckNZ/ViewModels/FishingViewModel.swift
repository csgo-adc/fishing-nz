import SwiftUI
import CoreLocation
import UIKit

enum FishingDatePreset: String, CaseIterable, Hashable {
    case today = "Today"
    case inThreeDays = "In 3 days"
    case inSevenDays = "Next 7 days"
    case thisWeekend = "This weekend"
    case custom = "Choose dates"
}

enum FishingTimeMode: String, CaseIterable, Hashable {
    case comfortable = "7 AM–9 PM"
    case custom = "Choose hours"
    case anytime = "Anytime"
}

@MainActor
final class FishingViewModel: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    @Published var selectedTab = 0 {
        didSet {
            guard oldValue != selectedTab else { return }
            let features = ["home", "map", "tide", "more"]
            guard features.indices.contains(selectedTab) else { return }
            Task { await repository.trackEvent("feature_used", feature: features[selectedTab], platform: "ios") }
        }
    }
    @Published var isBoatFishing = false { didSet { if oldValue != isBoatFishing { invalidateRecommendations() } } }
    @Published var datePreset: FishingDatePreset = .inThreeDays { didSet { if oldValue != datePreset { invalidateRecommendations() } } }
    @Published var customStartDate = Date.now { didSet { if oldValue != customStartDate { invalidateRecommendations() } } }
    @Published var customEndDate = Date.now { didSet { if oldValue != customEndDate { invalidateRecommendations() } } }
    @Published var timeMode: FishingTimeMode = .comfortable { didSet { if oldValue != timeMode { invalidateRecommendations() } } }
    @Published var preferredStartMinute = 8 * 60 { didSet { if oldValue != preferredStartMinute { invalidateRecommendations() } } }
    @Published var preferredEndMinute = 18 * 60 { didSet { if oldValue != preferredEndMinute { invalidateRecommendations() } } }
    @Published var radiusKm = 100 {
        didSet {
            guard oldValue != radiusKm else { return }
            UserDefaults.standard.set(radiusKm, forKey: Self.searchRadiusKey)
            invalidateRecommendations()
        }
    }
    @Published var location = GeoPoint(latitude: -36.85, longitude: 174.76)
    @Published var hasDeviceLocation = false
    @Published private(set) var tideLocationIssue: String?
    @Published var devicePlaceName: String?
    @Published private(set) var selectedSearchStationID: String?
    @Published var isResolvingRecommendationLocation = false
    @Published var recommendationLocationError: String?
    @Published var weather: WeatherState?
    @Published var currentTide: TideState?
    @Published var conditionsError: String?
    @Published var selectedStation = tideStations.first(where: { $0.id == "auckland" })!
    @Published var tideDate = Date.now
    @Published var stationTide: TideState?
    @Published var tideError: String?
    @Published private(set) var usesNearbyTideStation = true
    @Published var error: String?
    @Published var selectedPhoto: UIImage?
    @Published var fishCheck: FishCheck?
    @Published private(set) var selectedRulesAreaID: String? { didSet { if oldValue != selectedRulesAreaID { fishCheck = nil } } }
    @Published var isCheckingFish = false
    @Published var savedSpotNames = Set<String>()
    @Published var savedRecommendations: [String: Recommendation] = [:]
    @Published var activeTrip: Recommendation?
    @Published var selectedSpot: Recommendation?
    @Published var showingResults = false
    @Published var scoredWindows: [ScoredFishingWindow] = []
    @Published var isLoadingRecommendations = false
    @Published var hasSearchedRecommendations = false
    @Published var recommendationError: String?
    @Published var account: AccountProfile?
    var fishIdentityAvailable: Bool { account != nil }
    @Published var hasStoredSession = FishingRepository().hasStoredSession()
    @Published var accountBusy = false
    @Published var accountLoading = true
    @Published var accountLoadFailed = false
    @Published var accountError: String?
    @Published var accountNotice: String?
    @Published var verificationPending = false

    private let repository = FishingRepository()
    private let scoringService = FishingScoringService()
    private let locationManager = CLLocationManager()
    private var pendingFishPhoto: UIImage?
    private var recommendationTask: Task<Void, Never>?
    private var recommendationSearchID = UUID()
    private var locationRequestID = UUID()
    private var tideTask: Task<Void, Never>?
    private var tideRequestID = UUID()
    private var accountRequestVersion = 0
    private var rulesAreaSelectionIsManual = false
    private static let searchStationKey = "fishingSearchStationID"
    private static let searchRadiusKey = "fishingSearchRadiusKm"

    private var nzCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar
    }

    var firstSelectableDate: Date { nzCalendar.startOfDay(for: .now) }

    var latestSelectableDate: Date {
        nzCalendar.date(byAdding: .day, value: 15, to: firstSelectableDate) ?? .now
    }

    var dateSummary: String {
        if datePreset != .custom { return datePreset.rawValue }
        let start = customStartDate.formatted(.dateTime.day().month(.abbreviated))
        let end = customEndDate.formatted(.dateTime.day().month(.abbreviated))
        return start == end ? start : "\(start) – \(end)"
    }

    static func clockLabel(minutes: Int) -> String {
        let hour = minutes / 60
        let minute = minutes % 60
        return String(format: "%d:%02d %@", hour % 12 == 0 ? 12 : hour % 12, minute, hour < 12 ? "AM" : "PM")
    }

    var timeSummary: String {
        switch timeMode {
        case .comfortable: "7:00 AM–9:00 PM"
        case .custom: "\(Self.clockLabel(minutes: preferredStartMinute))–\(Self.clockLabel(minutes: preferredEndMinute))"
        case .anytime: "Anytime"
        }
    }

    private var preferredHours: PreferredFishingHours? {
        switch timeMode {
        case .comfortable: PreferredFishingHours(startMinute: 7 * 60, endMinute: 21 * 60)
        case .custom: PreferredFishingHours(startMinute: preferredStartMinute, endMinute: preferredEndMinute)
        case .anytime: nil
        }
    }

    var locationSummary: String {
        if let selectedSearchStation { return "From \(selectedSearchStation.name)" }
        if hasDeviceLocation { return "From \(devicePlaceName ?? "your current location")" }
        if tideLocationIssue != nil { return "Choose a search location" }
        return "Finding your current location…"
    }

    var homeCityLabel: String {
        selectedSearchStation?.name ?? (hasDeviceLocation ? devicePlaceName ?? "Current location" : tideLocationIssue == nil ? "Finding location…" : "Choose location")
    }

    var selectedSearchStation: TideStation? {
        tideStations.first(where: { $0.id == selectedSearchStationID })
    }

    var tideLocationSummary: String {
        if !usesNearbyTideStation { return "Chosen LINZ tide station" }
        if hasDeviceLocation { return "Nearest station to \(devicePlaceName ?? "your current location")" }
        if let tideLocationIssue { return tideLocationIssue }
        return "Finding a station near your current location…"
    }

    private var recommendationOrigin: GeoPoint? {
        if let selectedSearchStation {
            return GeoPoint(latitude: selectedSearchStation.latitude, longitude: selectedSearchStation.longitude)
        }
        return hasDeviceLocation ? location : nil
    }

    var recommendations: [Recommendation] {
        scoredWindows.map { window in
            let day = Self.nzDayLabel(window.start)
            let startTime = Self.nzClockLabel(window.start)
            let endTime = Self.nzClockLabel(window.end)
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
            let endLabel = calendar.isDate(window.start, inSameDayAs: window.end)
                ? endTime : "\(Self.nzDayLabel(window.end)) \(endTime)"
            return Recommendation(
                name: window.spotName,
                area: window.area,
                rating: window.score,
                time: "\(day) · \(startTime)–\(endLabel)",
                distance: selectedSearchStationID == nil
                    ? String(format: "%.0f km straight-line", window.distanceKm)
                    : "At selected tide location",
                reasons: window.reasons,
                boat: window.boat,
                warnings: window.warnings,
                startsAt: window.start,
                endsAt: window.end
            )
        }
    }

    private static func nzDayLabel(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_NZ")
        formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
        formatter.dateFormat = "EEE d MMM"
        return formatter.string(from: date)
    }

    private static func nzClockLabel(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
        formatter.dateFormat = "h:mm a"
        return formatter.string(from: date)
    }

    var nearbySpotCount: Int {
        if selectedSearchStationID != nil { return 1 }
        guard let recommendationOrigin else { return 0 }
        let origin = CLLocation(latitude: recommendationOrigin.latitude, longitude: recommendationOrigin.longitude)
        return fishingSpots.filter { spot in
            spot.boat == isBoatFishing && origin.distance(from: CLLocation(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude)) <= Double(radiusKm) * 1_000
        }.count
    }

    var nearestSpotHint: String? {
        if selectedSearchStationID != nil { return nil }
        guard let recommendationOrigin else { return nil }
        let origin = CLLocation(latitude: recommendationOrigin.latitude, longitude: recommendationOrigin.longitude)
        guard let nearest = fishingSpots.filter({ $0.boat == isBoatFishing }).map({ spot in
            (spot.name, origin.distance(from: CLLocation(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude)) / 1_000)
        }).min(by: { $0.1 < $1.1 }) else { return nil }
        let nextRadius = [10, 30, 50, 100, 200, 300, 400, 500].first(where: { Double($0) >= nearest.1 })
        if let nextRadius { return "Nearest area: \(nearest.0), about \(Int(nearest.1.rounded())) km away. Try \(nextRadius) km." }
        return "Nearest area: \(nearest.0), about \(Int(nearest.1.rounded())) km away."
    }

    var suggestedRadiusKm: Int? {
        if selectedSearchStationID != nil { return nil }
        guard let recommendationOrigin else { return nil }
        let origin = CLLocation(latitude: recommendationOrigin.latitude, longitude: recommendationOrigin.longitude)
        let nearestDistance = fishingSpots.filter { $0.boat == isBoatFishing }.map {
            origin.distance(from: CLLocation(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude)) / 1_000
        }.min() ?? .infinity
        return [10, 30, 50, 100, 200, 300, 400, 500].first { $0 > radiusKm && Double($0) >= nearestDistance }
    }

    var nearbyPlacesWithoutWindows: [String] {
        if selectedSearchStationID != nil { return [] }
        guard let recommendationOrigin else { return [] }
        let origin = CLLocation(latitude: recommendationOrigin.latitude, longitude: recommendationOrigin.longitude)
        let scored = Set(recommendations.map(\.name))
        var seen = Set<String>()
        return fishingSpots.filter { $0.boat == isBoatFishing }
            .map { spot in (spot.name, origin.distance(from: CLLocation(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude)) / 1_000) }
            .filter { $0.1 <= Double(radiusKm) }
            .sorted { $0.1 < $1.1 }
            .compactMap { name, _ in
                guard !scored.contains(name), seen.insert(name).inserted else { return nil }
                return name
            }
    }

    func recommendationDays(now: Date = .now) -> [Date] {
        let calendar = nzCalendar
        let today = calendar.startOfDay(for: now)
        func days(from start: Date, count: Int) -> [Date] {
            (0..<count).compactMap { calendar.date(byAdding: .day, value: $0, to: start) }
        }
        switch datePreset {
        case .today:
            return [today]
        case .inThreeDays:
            return [calendar.date(byAdding: .day, value: 3, to: today)].compactMap { $0 }
        case .inSevenDays:
            return days(from: today, count: 7)
        case .thisWeekend:
            let weekday = calendar.component(.weekday, from: today)
            if weekday == 1 { return [today] }
            let saturday = calendar.date(byAdding: .day, value: 7 - weekday, to: today) ?? today
            return days(from: saturday, count: 2)
        case .custom:
            let start = max(today, calendar.startOfDay(for: customStartDate))
            let end = min(latestSelectableDate, max(start, calendar.startOfDay(for: customEndDate)))
            let count = (calendar.dateComponents([.day], from: start, to: end).day ?? 0) + 1
            return days(from: start, count: count)
        }
    }

    func showRecommendations() {
        showingResults = true
        searchRecommendations()
        if selectedSearchStationID == nil && hasDeviceLocation { requestLocation() }
    }

    func selectSearchStation(_ station: TideStation?) {
        selectedSearchStationID = station?.id
        if let station {
            UserDefaults.standard.set(station.id, forKey: Self.searchStationKey)
        } else {
            UserDefaults.standard.removeObject(forKey: Self.searchStationKey)
        }
        isResolvingRecommendationLocation = false
        recommendationLocationError = nil
        locationRequestID = UUID()
        if showingResults { searchRecommendations() }
        else { invalidateRecommendations() }
        refreshConditions()
        if station == nil { requestLocation() }
    }

    func selectRulesArea(_ areaID: String) {
        guard fishingRulesAreas.contains(where: { $0.id == areaID }) else { return }
        rulesAreaSelectionIsManual = true
        selectedRulesAreaID = areaID
    }

    func useCurrentRulesArea() {
        rulesAreaSelectionIsManual = false
        selectedRulesAreaID = hasDeviceLocation ? suggestedRulesAreaID(for: location) : nil
        requestLocation()
    }

    private func suggestedRulesAreaID(for point: GeoPoint) -> String? {
        guard (-48 ... -34).contains(point.latitude) else { return nil }
        if point.longitude < -170 { return "chatham-rise" }
        guard (165 ... 180).contains(point.longitude) else { return nil }

        let origin = CLLocation(latitude: point.latitude, longitude: point.longitude)
        let kaikoura = CLLocation(latitude: -42.4167, longitude: 173.7)
        if origin.distance(from: kaikoura) < 35_000 { return "kaikoura" }
        if point.latitude < -43.8 && point.latitude > -46.5 && point.longitude < 168.5 {
            return "fiordland"
        }

        // Inland locations use the closest catalogued coast as a starting point.
        // MPI coast boundaries and special areas still need checking at the fishing spot.
        guard let nearest = fishingSpots.min(by: {
            origin.distance(from: CLLocation(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude)) <
            origin.distance(from: CLLocation(latitude: $1.coordinate.latitude, longitude: $1.coordinate.longitude))
        }) else { return nil }
        switch nearest.area {
        case "Northland", "Bay of Islands", "Far North", "Hokianga", "Auckland", "Auckland West Coast",
             "Waiheke Island", "Coromandel", "Firth of Thames", "Waikato", "Bay of Plenty":
            return "auckland-kermadec"
        case "Gisborne", "Hawke's Bay", "Taranaki", "Whanganui", "Wellington", "Wairarapa":
            return "central"
        case "Marlborough", "Marlborough Sounds", "Tasman", "Golden Bay", "Nelson", "West Coast":
            return "challenger"
        case "Canterbury", "Otago", "Dunedin":
            return "south-east"
        case "Southland":
            return "southland"
        default:
            return nil
        }
    }

    private func invalidateRecommendations() {
        recommendationTask?.cancel()
        recommendationSearchID = UUID()
        scoredWindows = []
        recommendationError = nil
        hasSearchedRecommendations = false
        isLoadingRecommendations = false
    }

    func searchRecommendations() {
        recommendationTask?.cancel()
        let searchID = UUID()
        recommendationSearchID = searchID
        scoredWindows = []
        recommendationError = nil
        guard let origin = recommendationOrigin else {
            hasSearchedRecommendations = false
            isLoadingRecommendations = false
            isResolvingRecommendationLocation = true
            recommendationLocationError = nil
            let requestID = UUID()
            locationRequestID = requestID
            requestLocation()
            Task {
                try? await Task.sleep(for: .seconds(10))
                guard locationRequestID == requestID, isResolvingRecommendationLocation else { return }
                isResolvingRecommendationLocation = false
                recommendationLocationError = "Couldn’t get your current location. Choose a tide location on Home or enable Location Services, then try again."
            }
            return
        }
        isResolvingRecommendationLocation = false
        recommendationLocationError = nil
        locationRequestID = UUID()
        let radius = Double(radiusKm)
        let station = selectedSearchStation
        let days = recommendationDays()
        let boat = isBoatFishing
        let hours = preferredHours
        scoredWindows = []
        recommendationError = nil
        hasSearchedRecommendations = true
        isLoadingRecommendations = true
        recommendationTask = Task {
            do {
                let windows = try await scoringService.rank(origin: origin, radiusKm: radius, selectedStation: station,
                                                            days: days, boat: boat, preferredHours: hours)
                guard !Task.isCancelled, recommendationSearchID == searchID else { return }
                scoredWindows = windows
            } catch is CancellationError {
                return
            } catch {
                guard recommendationSearchID == searchID else { return }
                recommendationError = error.localizedDescription
            }
            guard recommendationSearchID == searchID else { return }
            isLoadingRecommendations = false
        }
    }

    override init() {
        super.init()
        let storedRadius = UserDefaults.standard.integer(forKey: Self.searchRadiusKey)
        if [10, 30, 50, 100, 200, 300, 400, 500].contains(storedRadius) { radiusKm = storedRadius }
        if let stationID = UserDefaults.standard.string(forKey: Self.searchStationKey),
           tideStations.contains(where: { $0.id == stationID }) {
            selectedSearchStationID = stationID
        }
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        refreshStationTide()
        if selectedSearchStationID != nil { refreshConditions() }
        refreshAccount()
    }
    func requestLocation() {
        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            tideLocationIssue = nil
            locationManager.requestLocation()
        case .denied, .restricted:
            tideLocationIssue = "Location access is off. Choose a station or enable it in Settings."
            hasDeviceLocation = false
            if isResolvingRecommendationLocation {
                isResolvingRecommendationLocation = false
                recommendationLocationError = "Location access is off. Choose a tide location on Home or enable location access in Settings."
            }
        @unknown default:
            break
        }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse {
            tideLocationIssue = nil
            manager.requestLocation()
        } else if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted {
            tideLocationIssue = "Location access is off. Choose a station or enable it in Settings."
            hasDeviceLocation = false
            if isResolvingRecommendationLocation {
                isResolvingRecommendationLocation = false
                recommendationLocationError = "Location access is off. Choose a tide location on Home or enable location access in Settings."
            }
        }
        if pendingFishPhoto != nil {
            if manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted { identifyPendingFish(at: location) }
        }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let point = locations.last, CLLocationCoordinate2DIsValid(point.coordinate) else { return }
        location = GeoPoint(latitude: point.coordinate.latitude, longitude: point.coordinate.longitude)
        hasDeviceLocation = true
        tideLocationIssue = nil
        let nearestStation = tideStations.min {
            point.distance(from: CLLocation(latitude: $0.latitude, longitude: $0.longitude)) <
            point.distance(from: CLLocation(latitude: $1.latitude, longitude: $1.longitude))
        }
        devicePlaceName = nearestStation.flatMap {
            point.distance(from: CLLocation(latitude: $0.latitude, longitude: $0.longitude)) < 15_000 ? $0.name : nil
        }
        if !rulesAreaSelectionIsManual { selectedRulesAreaID = suggestedRulesAreaID(for: location) }
        if usesNearbyTideStation { selectNearestTideStation() }
        let coordinate = point.coordinate
        Task {
            if let place = try? await CLGeocoder().reverseGeocodeLocation(point).first,
               location.latitude == coordinate.latitude, location.longitude == coordinate.longitude {
                devicePlaceName = place.locality ?? place.subAdministrativeArea ?? devicePlaceName
            }
        }
        refreshConditions()
        if showingResults && selectedSearchStationID == nil { searchRecommendations() }
        else if !showingResults { invalidateRecommendations() }
        if pendingFishPhoto != nil { identifyPendingFish(at: location) }
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if !hasDeviceLocation {
            tideLocationIssue = "Couldn’t get your current location. Choose a station or tap Nearby to try again."
        }
        if isResolvingRecommendationLocation {
            isResolvingRecommendationLocation = false
            recommendationLocationError = "Couldn’t get your current location. Choose a tide location on Home or try again."
        }
        if pendingFishPhoto != nil { identifyPendingFish(at: location) }
    }
    func activateTideTab() {
        if usesNearbyTideStation && hasDeviceLocation { selectNearestTideStation() }
        requestLocation()
    }
    func useCurrentLocationForTides() {
        usesNearbyTideStation = true
        if hasDeviceLocation { selectNearestTideStation() }
        requestLocation()
    }
    private func selectNearestTideStation() {
        guard hasDeviceLocation else { return }
        let origin = CLLocation(latitude: location.latitude, longitude: location.longitude)
        guard let nearest = tideStations.min(by: {
            origin.distance(from: CLLocation(latitude: $0.latitude, longitude: $0.longitude)) <
            origin.distance(from: CLLocation(latitude: $1.latitude, longitude: $1.longitude))
        }) else { return }
        if selectedStation != nearest { selectedStation = nearest; refreshStationTide() }
    }
    func chooseStation(_ station: TideStation) {
        usesNearbyTideStation = false
        selectedStation = station
        refreshStationTide()
    }
    func changeTideDate(by days: Int) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        let currentDay = calendar.startOfDay(for: tideDate)
        guard let targetDay = calendar.date(byAdding: .day, value: days, to: currentDay),
              let lastPublishedDay = calendar.date(from: DateComponents(year: 2029, month: 12, day: 31)),
              targetDay >= calendar.startOfDay(for: .now), targetDay <= lastPublishedDay
        else { return }
        tideDate = targetDay
        refreshStationTide()
    }
    func toggleSaved(_ spot: Recommendation) {
        if savedSpotNames.contains(spot.id) { savedSpotNames.remove(spot.id); savedRecommendations.removeValue(forKey: spot.id) }
        else { savedSpotNames.insert(spot.id); savedRecommendations[spot.id] = spot }
    }
    func startTrip(_ spot: Recommendation) { activeTrip = spot; selectedSpot = nil }
    func refreshAccount() {
        let version = accountRequestVersion
        accountLoading = true
        accountLoadFailed = false
        accountError = nil
        Task {
            do {
                let snapshot = try await repository.currentAccount()
                guard version == accountRequestVersion else { return }
                account = snapshot?.user
                hasStoredSession = repository.hasStoredSession()
                accountLoading = false
                if snapshot != nil { await repository.trackEvent("app_opened", feature: nil, platform: "ios") }
            } catch {
                guard version == accountRequestVersion else { return }
                accountLoading = false
                accountLoadFailed = true
                hasStoredSession = repository.hasStoredSession()
                accountError = "Could not check your account. Check your connection and try again."
            }
        }
    }
    func signIn(email: String, password: String, displayName: String, createAccount: Bool) async -> Bool {
        accountRequestVersion += 1
        accountLoading = false
        accountLoadFailed = false
        accountBusy = true; accountError = nil; accountNotice = nil
        do {
            if createAccount {
                accountNotice = try await repository.createAccount(email: email, password: password, displayName: displayName)
                verificationPending = true
            } else {
                let snapshot = try await repository.registerOrLogin(email: email, password: password)
                account = snapshot.user; hasStoredSession = true; accountNotice = "You’re signed in."; verificationPending = false
            }
            accountBusy = false
            return true
        } catch {
            accountError = error.localizedDescription
            if let apiError = error as? AccountAPIError,
               apiError.code == "email_not_verified" || apiError.code == "email_delivery_failed" {
                verificationPending = true
            }
            accountBusy = false
            return false
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
            do { let snapshot = try await repository.saveProfile(displayName: displayName, countryCode: countryCode); account = snapshot.user; accountNotice = "Profile saved." }
            catch { accountError = error.localizedDescription }
            accountBusy = false
        }
    }
    func sendFeedback(category: String, message: String, rating: Int) async -> Bool {
        accountBusy = true; accountError = nil; accountNotice = nil
        do {
            try await repository.sendFeedback(category: category, message: message, rating: rating)
            accountNotice = "Thanks for your feedback."
            accountBusy = false
            return true
        } catch {
            accountError = error.localizedDescription
            accountBusy = false
            return false
        }
    }
    func signOut() {
        accountRequestVersion += 1
        accountLoadFailed = false
        accountLoading = false
        accountBusy = true; accountError = nil; accountNotice = nil
        Task { await repository.signOut(); account = nil; hasStoredSession = false; verificationPending = false; accountBusy = false; accountNotice = "You’re signed out." }
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
                fishCheck = try await repository.identifyFish(image: photo, at: point, hasDeviceLocation: hasDeviceLocation, selectedRulesAreaID: selectedRulesAreaID)
            } catch { self.error = error.localizedDescription }
            isCheckingFish = false
        }
    }
    func refreshConditions() {
        guard let point = recommendationOrigin else { weather = nil; currentTide = nil; conditionsError = nil; return }
        Task {
            do {
                let result = try await repository.conditions(at: point)
                guard recommendationOrigin == point else { return }
                weather = result.0
                currentTide = result.1
                conditionsError = nil
            } catch { if recommendationOrigin == point { conditionsError = "Live weather unavailable" } }
        }
    }
    func refreshStationTide() {
        tideTask?.cancel()
        let requestID = UUID()
        tideRequestID = requestID
        let station = selectedStation, date = tideDate
        stationTide = nil
        tideError = nil
        tideTask = Task {
            do {
                let forecast = try await repository.tide(for: station, date: date)
                guard !Task.isCancelled, tideRequestID == requestID else { return }
                stationTide = forecast
            } catch {
                guard !Task.isCancelled, tideRequestID == requestID else { return }
                tideError = "Tide predictions are unavailable for \(station.name) on this date. Try another date or station."
            }
        }
    }
}
