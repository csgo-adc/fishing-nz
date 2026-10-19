import SwiftUI
import CoreLocation
import UIKit
import AuthenticationServices

enum FishingDatePreset: String, CaseIterable, Hashable {
    case today = "Today"
    case tomorrow = "Tomorrow"
    case thisWeekend = "This weekend"
    case nextThreeDays = "Next 3 days"
    case inSevenDays = "Next 7 days"
    case custom = "Pick dates"

    /// A saved value, including the choices before 1.0.32 ("In 3 days" was one day, three days ahead).
    static func saved(_ raw: String?) -> FishingDatePreset? {
        switch raw {
        case "In 3 days": .nextThreeDays
        case "Choose dates": .custom
        default: raw.flatMap(FishingDatePreset.init(rawValue:))
        }
    }
}

/// A "What matters most?" choice: one list that sets the window preference and, on land, the tie-break.
struct PlanMatter: Identifiable, Equatable {
    let label: String
    let priority: WindowPriority
    let land: LandPriority
    let note: String
    var id: String { label }

    private static let tideNote = "Windows that mostly fall in the part of the tide you choose. Times are for the nearest LINZ station."
    private static let sunNote = "The hour either side of sunrise or sunset. Part of the window can be dark."

    static func choices(boat: Bool) -> [PlanMatter] {
        boat ? [
            PlanMatter(label: "Calm water", priority: .weather, land: .balanced, note: "The smallest, smoothest waves first, then wind and rain."),
            PlanMatter(label: "Tide", priority: .tide, land: .balanced, note: tideNote + " Waves still come first."),
            PlanMatter(label: "Sunrise or sunset", priority: .sun, land: .balanced, note: sunNote + " Waves still come first.")
        ] : [
            PlanMatter(label: "Comfort", priority: .weather, land: .balanced, note: "The least wind, rain and cold."),
            PlanMatter(label: "Less wind", priority: .weather, land: .casting, note: "Comfortable first, then the least wind for easier casting."),
            PlanMatter(label: "Less rain", priority: .weather, land: .dry, note: "Comfortable first, then the least rain."),
            PlanMatter(label: "Tide", priority: .tide, land: .balanced, note: tideNote),
            PlanMatter(label: "Sunrise or sunset", priority: .sun, land: .balanced, note: sunNote)
        ]
    }
}

extension TidePhase {
    /// What each tide choice covers, timed from the official highs and lows.
    var note: String {
        switch self {
        case .incoming: "From low tide up to high tide."
        case .aroundHigh: "From 2 hours before high tide to 1 hour after."
        case .outgoing: "From high tide down to low tide."
        case .aroundLow: "From 1.5 hours before low tide to 1.5 hours after."
        }
    }
    /// "around high tide", for sentences.
    var words: String {
        switch self {
        case .incoming: "on the incoming tide"
        case .aroundHigh: "around high tide"
        case .outgoing: "on the outgoing tide"
        case .aroundLow: "around low tide"
        }
    }
}

/// "2 hours", or "1 hour".
func hoursLabel(_ hours: Int) -> String { hours == 1 ? "1 hour" : "\(hours) hours" }

/// When in the day someone can fish. Daytime follows each day's sunrise and sunset; the others are fixed hours.
enum FishingTimeMode: String, CaseIterable, Hashable {
    case daytime = "Daytime"
    case morning = "Morning"
    case evening = "Evening"
    case anytime = "Any time"
    case custom = "Pick hours"

    /// A saved value, including the choices before 1.0.32 (7 AM–9 PM became Daytime).
    static func saved(_ raw: String?) -> FishingTimeMode? {
        switch raw {
        case "7 AM–9 PM": .daytime
        case "Anytime": .anytime
        case "Choose hours": .custom
        default: raw.flatMap(FishingTimeMode.init(rawValue:))
        }
    }
}

@MainActor
final class FishingViewModel: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    @Published var selectedTab = 0 {
        didSet {
            guard oldValue != selectedTab else { return }
            let features = ["home", "map", "tide", "weather", "more"]
            guard features.indices.contains(selectedTab) else { return }
            trackScreen(features[selectedTab])
        }
    }
    @Published var isBoatFishing = false {
        didSet { if oldValue != isBoatFishing { UserDefaults.standard.set(isBoatFishing, forKey: Self.planBoatKey); invalidateRecommendations() } }
    }
    @Published var datePreset: FishingDatePreset = .nextThreeDays {
        didSet { if oldValue != datePreset { UserDefaults.standard.set(datePreset.rawValue, forKey: Self.planDatePresetKey); invalidateRecommendations() } }
    }
    @Published var customStartDate = Date.now {
        didSet { if oldValue != customStartDate { UserDefaults.standard.set(customStartDate, forKey: Self.planStartDateKey); invalidateRecommendations() } }
    }
    @Published var customEndDate = Date.now {
        didSet { if oldValue != customEndDate { UserDefaults.standard.set(customEndDate, forKey: Self.planEndDateKey); invalidateRecommendations() } }
    }
    @Published var timeMode: FishingTimeMode = .daytime {
        didSet { if oldValue != timeMode { UserDefaults.standard.set(timeMode.rawValue, forKey: Self.planTimeModeKey); invalidateRecommendations() } }
    }
    @Published var preference: WindowPriority = .weather {
        didSet { if oldValue != preference { UserDefaults.standard.set(preference.rawValue, forKey: Self.planPriorityKey); invalidateRecommendations() } }
    }
    @Published var tidePhase: TidePhase = .aroundHigh {
        didSet { if oldValue != tidePhase { UserDefaults.standard.set(tidePhase.rawValue, forKey: "fishingPlanTidePhase"); invalidateRecommendations() } }
    }
    /// How long the person fishes, 1 to 4 hours.
    @Published var sessionHours = 2 {
        didSet { if oldValue != sessionHours { UserDefaults.standard.set(sessionHours, forKey: "fishingPlanSessionHours"); invalidateRecommendations() } }
    }
    @Published var landPreferences = LandPreferences() {
        didSet {
            guard oldValue != landPreferences else { return }
            UserDefaults.standard.set([
                "setting": landPreferences.setting.rawValue, "priority": landPreferences.priority.rawValue,
                "maxBand": landPreferences.maxBand, "arrival": landPreferences.arrivalMinutes ?? -1,
                "return": landPreferences.returnMinutes ?? -1, "daylight": landPreferences.daylightOnly
            ] as [String: Any], forKey: "land_preferences")
            invalidateRecommendations()
        }
    }
    @Published var preferredStartMinute = 8 * 60 {
        didSet { if oldValue != preferredStartMinute { UserDefaults.standard.set(preferredStartMinute, forKey: Self.planStartMinuteKey); invalidateRecommendations() } }
    }
    @Published var preferredEndMinute = 18 * 60 {
        didSet { if oldValue != preferredEndMinute { UserDefaults.standard.set(preferredEndMinute, forKey: Self.planEndMinuteKey); invalidateRecommendations() } }
    }
    @Published var radiusKm = 50 {
        didSet {
            guard oldValue != radiusKm else { return }
            UserDefaults.standard.set(radiusKm, forKey: Self.searchRadiusKey)
            invalidateRecommendations()
        }
    }
    /// With a picked spot or tide station: true searches only that place, false searches the radius around it.
    @Published private(set) var searchJustThisPlace = true
    @Published var location = GeoPoint(latitude: -36.85, longitude: 174.76)
    @Published var hasDeviceLocation = false
    @Published private(set) var deviceLocationUpdatedAt: Date?
    @Published private(set) var tideLocationIssue: String?
    @Published var devicePlaceName: String?
    @Published private(set) var selectedSearchStationID: String?
    /// A fishing spot picked as the search place, by name.
    @Published private(set) var selectedSearchSpotName: String?
    /// A town, or a spot from the map, to search around within the radius. Nil with no station means the device location.
    @Published private(set) var selectedSearchOrigin: SearchOrigin?
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
    @Published var selectedPhoto: UIImage? {
        didSet {
            if selectedPhoto !== oldValue {
                fishIdentifyRequestID = UUID()
                fishRulesTask?.cancel()
                fishRulesRequestID = UUID()
                isLoadingFishRules = false
                fishRulesError = nil
                fishCheck = nil
                error = nil
                pendingFishPhoto = nil
                isCheckingFish = false
            }
        }
    }
    @Published var fishCheck: FishCheck?
    @Published private(set) var selectedRulesAreaID: String? {
        didSet { if selectedRulesAreaID != oldValue { refreshFishRulesForSelection() } }
    }
    @Published var isCheckingFish = false
    @Published private(set) var isLoadingFishRules = false
    @Published private(set) var fishRulesError: String?
    @Published var savedSpotNames = Set<String>()
    @Published var savedRecommendations: [String: Recommendation] = [:] { didSet { saveTrips() } }
    @Published var activeTrip: Recommendation? {
        didSet {
            saveTrips()
            guard !restoringTrips, activeTrip?.windowID != oldValue?.windowID else { return }
            tripBackBy = nil
            if let activeTrip {
                prefetchForOffline([activeTrip])
                Task { tripCheckAt = await TripReminders.schedule(activeTrip) }
            } else {
                TripReminders.cancel()
                tripCheckAt = nil
            }
        }
    }
    /// When the active trip's forecast is checked again (see `TripCheck`), or nil when no check fits.
    @Published private(set) var tripCheckAt: Date?
    /// Bumped when a trip notification is tapped; the root view opens Trips.
    @Published var openTripsRequest = 0
    /// The back-by time in the trip plan shared with someone, if one was shared.
    @Published private(set) var tripBackBy: Date? {
        didSet { if !restoringTrips { UserDefaults.standard.set(tripBackBy, forKey: "trip_back_by") } }
    }

    /// After the trip plan is shared: remember the back-by time and remind the person then to check in.
    func tripPlanShared(backBy: Date) {
        guard let trip = activeTrip else { return }
        tripBackBy = backBy
        Task { await TripReminders.scheduleCheckIn(trip, at: backBy) }
        Analytics.track("trip_plan_shared")
    }
    @Published var selectedSpot: Recommendation?
    @Published var sharedWindowNotice: String?
    /// Every catch log entry on this phone, including deleted ones kept for sync; see `CatchLog.visible`.
    @Published private(set) var catches: [CatchEntry] = CatchLogStore.load()
    /// Set by "Log this catch" or a trip's "Log a catch"; the catch log opens a new entry with it.
    @Published var catchPrefill: CatchPrefill?
    @Published var showingResults = false
    /// A place the Map tab should centre on and open, set by "Show on map" on a fishing window.
    @Published var mapFocus: ConditionPlace?
    @Published var scoredWindows: [ScoredFishingWindow] = []
    @Published var isLoadingRecommendations = false
    @Published var hasSearchedRecommendations = false
    @Published var recommendationError: String?
    /// Nearby spots from the last search whose forecast failed, nearest first.
    @Published private(set) var failedSpotNames: [String] = []
    /// Nearby spots from the last search that loaded but had no window that fits, nearest first.
    @Published private(set) var nearbyPlacesWithoutWindows: [String] = []
    @Published var account: AccountProfile?
    @Published var fishIdentityQuota: FishIdentityQuota?
    var fishIdentityAvailable: Bool { account != nil }
    var remainingIdentifications: Int? { fishIdentityQuota?.remainingToday }
    @Published var hasStoredSession = FishingRepository().hasStoredSession()
    @Published var accountBusy = false
    @Published var accountLoading = true
    @Published var accountLoadFailed = false
    @Published var accountError: String?
    @Published var accountNotice: String?
    @Published var verificationPending = false
    @Published var signInProviders = SignInProviders()

    private let repository = FishingRepository()
    private let socialSignInBrowser = SocialSignInBrowser()
    private let appleSignInSheet = AppleSignInSheet()
    private let scoringService = FishingScoringService()
    private let locationManager = CLLocationManager()
    private var pendingFishPhoto: UIImage?
    private var fishIdentifyRequestID = UUID()
    private var fishRulesTask: Task<Void, Never>?
    private var fishRulesRequestID = UUID()
    private var recommendationTask: Task<Void, Never>?
    private var recommendationSearchID = UUID()
    private var locationRequestID = UUID()
    private var tideTask: Task<Void, Never>?
    private var tideRequestID = UUID()
    private var accountRequestVersion = 0
    private var catchRulesCache: [String: FishRulesResult] = [:]
    private var restoringTrips = false
    @Published private(set) var rulesAreaSelectionIsManual = false
    private static let searchStationKey = "fishingSearchStationID"
    private static let searchOriginKey = "fishingSearchOrigin"
    private static let searchSpotKey = "fishingSearchSpot"
    private static let searchJustThisPlaceKey = "fishingSearchJustThisPlace"
    private static let searchRadiusKey = "fishingSearchRadiusKm"
    private static let planBoatKey = "fishingPlanBoat"
    private static let planDatePresetKey = "fishingPlanDatePreset"
    private static let planStartDateKey = "fishingPlanStartDate"
    private static let planEndDateKey = "fishingPlanEndDate"
    private static let planTimeModeKey = "fishingPlanTimeMode"
    private static let planPriorityKey = "fishingPlanPriority"
    private static let planStartMinuteKey = "fishingPlanStartMinute"
    private static let planEndMinuteKey = "fishingPlanEndMinute"

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
        timeMode == .custom ? "\(Self.clockLabel(minutes: preferredStartMinute))–\(Self.clockLabel(minutes: preferredEndMinute))" : timeMode.rawValue
    }

    /// "Fri 16 – Sun 18 Oct", or one day: the dates the plan covers.
    var dateRangeNote: String {
        let days = recommendationDays()
        guard let first = days.first, let last = days.last else { return "" }
        func text(_ date: Date, _ pattern: String) -> String {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
            formatter.dateFormat = pattern
            return formatter.string(from: date)
        }
        if first == last { return text(first, "EEE d MMM") }
        let sameMonth = nzCalendar.component(.month, from: first) == nzCalendar.component(.month, from: last)
        return "\(text(first, sameMonth ? "EEE d" : "EEE d MMM")) – \(text(last, "EEE d MMM"))"
    }

    var selectedMatter: PlanMatter? {
        PlanMatter.choices(boat: isBoatFishing).first {
            $0.priority == preference && (isBoatFishing || $0.priority != .weather || $0.land == landPreferences.priority)
        }
    }

    var whatMattersLabel: String { preference == .tide ? "\(tidePhase.label) tide" : selectedMatter?.label ?? preference.title(boat: isBoatFishing) }

    /// The line under the time choices on the plan page.
    var timeNote: String {
        switch timeMode {
        case .daytime: isBoatFishing ? "Sunrise to sunset." : "Sunrise to sunset, including your walk."
        case .morning: "5 AM – 12 PM."
        case .evening: "3 PM – 10 PM."
        case .anytime: "Day or night. Daylight still comes first."
        case .custom: "\(Self.clockLabel(minutes: preferredStartMinute)) – \(Self.clockLabel(minutes: preferredEndMinute))" + (preferredStartMinute > preferredEndMinute ? " (ends next day)." : ".")
        }
    }

    private var preferredHours: PreferredFishingHours? {
        switch timeMode {
        case .morning: PreferredFishingHours(startMinute: 5 * 60, endMinute: 12 * 60)
        case .evening: PreferredFishingHours(startMinute: 15 * 60, endMinute: 22 * 60)
        case .custom: PreferredFishingHours(startMinute: preferredStartMinute, endMinute: preferredEndMinute)
        case .daytime, .anytime: nil
        }
    }

    var locationSummary: String {
        if searchesOnePlace, let searchPlace { return "At \(searchPlace.name)" }
        if let searchPlace { return "Near \(searchPlace.name)" }
        if let selectedSearchOrigin { return "Near \(selectedSearchOrigin.name)" }
        if hasDeviceLocation { return "Near \(devicePlaceName ?? "you")" }
        if tideLocationIssue != nil { return "Choose a place" }
        return "Finding your location…"
    }

    var homeCityLabel: String {
        searchPlace?.name ?? selectedSearchOrigin?.name ?? (hasDeviceLocation ? devicePlaceName ?? "Current location" : tideLocationIssue == nil ? "Finding location…" : "Choose location")
    }

    var selectedSearchStation: TideStation? {
        tideStations.first(where: { $0.id == selectedSearchStationID })
    }

    var selectedSearchSpot: FishingSpot? {
        selectedSearchSpotName.flatMap { name in fishingSpots.first { $0.name == name } }
    }

    /// The picked fishing spot or tide station, searched alone or as the centre of a radius.
    var searchPlace: SearchOrigin? {
        if let spot = selectedSearchSpot { return SearchOrigin(name: spot.name, point: spot.coordinate) }
        if let station = selectedSearchStation { return SearchOrigin(name: station.name, point: GeoPoint(latitude: station.latitude, longitude: station.longitude)) }
        return nil
    }

    /// True when the plan searches just one place.
    var searchesOnePlace: Bool { searchPlace != nil && searchJustThisPlace }

    var tideLocationSummary: String {
        if !usesNearbyTideStation { return "Chosen LINZ tide station" }
        if hasDeviceLocation { return "Nearest station to \(devicePlaceName ?? "your current location")" }
        if let tideLocationIssue { return tideLocationIssue }
        return "Finding a station near your current location…"
    }

    /// True when searches start from the phone's position rather than a chosen town, spot or tide location.
    var searchesFromDeviceLocation: Bool { searchPlace == nil && selectedSearchOrigin == nil }

    private var recommendationOrigin: GeoPoint? {
        if let searchPlace { return searchPlace.point }
        if let selectedSearchOrigin { return selectedSearchOrigin.point }
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
                distance: !searchesOnePlace
                    ? String(format: "%.0f km straight-line", window.distanceKm)
                    : selectedSearchSpot != nil ? "Selected spot" : "At selected tide location",
                reasons: window.reasons,
                boat: window.boat,
                warnings: window.warnings,
                startsAt: window.start,
                endsAt: window.end,
                summary: window.summary,
                conditions: window.conditions,
                sourceNote: window.sourceNote,
                alternative: window.alternative,
                dataComplete: window.dataComplete,
                tidePreferenceFit: window.tidePreferenceFit,
                assessment: window.assessment,
                coordinate: window.coordinate
            )
        }
    }

    /// The best window overall: first in planning order, the nearer spot first when two are equally good.
    var bestRecommendation: Recommendation? {
        guard let best = scoredWindows.min(by: FishingScoringService.searchOrder) else { return nil }
        return recommendations.first { $0.name == best.spotName && $0.startsAt == best.start }
    }

    /// The results by day in date order, keeping the planning order within each day.
    var recommendationsByDay: [(label: String, windows: [Recommendation])] {
        let grouped = Dictionary(grouping: recommendations) { nzCalendar.startOfDay(for: $0.startsAt ?? .distantPast) }
        return grouped.keys.sorted().map { day in
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
            formatter.dateFormat = "EEEE d MMMM"
            return (formatter.string(from: day), grouped[day]!)
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
        if searchesOnePlace { return 1 }
        guard let recommendationOrigin else { return 0 }
        let origin = CLLocation(latitude: recommendationOrigin.latitude, longitude: recommendationOrigin.longitude)
        return fishingSpots.filter { spot in
            spot.boat == isBoatFishing && origin.distance(from: CLLocation(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude)) <= Double(radiusKm) * 1_000
        }.count
    }

    var nearestSpotHint: String? {
        if searchesOnePlace { return nil }
        guard let recommendationOrigin else { return nil }
        let origin = CLLocation(latitude: recommendationOrigin.latitude, longitude: recommendationOrigin.longitude)
        guard let nearest = fishingSpots.filter({ $0.boat == isBoatFishing }).map({ spot in
            (spot.name, origin.distance(from: CLLocation(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude)) / 1_000)
        }).min(by: { $0.1 < $1.1 }) else { return nil }
        let nextRadius = searchRadiiKm.first(where: { Double($0) >= nearest.1 })
        if let nextRadius { return "Nearest area: \(nearest.0), about \(Int(nearest.1.rounded())) km away. Try \(nextRadius) km." }
        return "Nearest area: \(nearest.0), about \(Int(nearest.1.rounded())) km away."
    }

    var suggestedRadiusKm: Int? {
        if searchesOnePlace { return nil }
        guard let recommendationOrigin else { return nil }
        let origin = CLLocation(latitude: recommendationOrigin.latitude, longitude: recommendationOrigin.longitude)
        let nearestDistance = fishingSpots.filter { $0.boat == isBoatFishing }.map {
            origin.distance(from: CLLocation(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude)) / 1_000
        }.min() ?? .infinity
        return searchRadiiKm.first { $0 > radiusKm && Double($0) >= nearestDistance }
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
        case .tomorrow:
            return [calendar.date(byAdding: .day, value: 1, to: today)].compactMap { $0 }
        case .nextThreeDays:
            return days(from: today, count: 3)
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
        if searchesFromDeviceLocation && hasDeviceLocation { requestLocation() }
    }

    /// One tide location, or nil to search around the device location.
    func selectSearchStation(_ station: TideStation?) {
        selectedSearchStationID = station?.id
        selectedSearchSpotName = nil
        selectedSearchOrigin = nil
        searchJustThisPlace = true
        UserDefaults.standard.removeObject(forKey: Self.searchOriginKey)
        UserDefaults.standard.removeObject(forKey: Self.searchSpotKey)
        UserDefaults.standard.set(true, forKey: Self.searchJustThisPlaceKey)
        if let station {
            UserDefaults.standard.set(station.id, forKey: Self.searchStationKey)
        } else {
            UserDefaults.standard.removeObject(forKey: Self.searchStationKey)
        }
        searchOriginChanged()
        if station == nil { requestLocation() }
    }

    /// A picked fishing spot is searched alone until a distance is chosen.
    func selectSearchSpot(_ spot: FishingSpot) {
        selectedSearchSpotName = spot.name
        selectedSearchStationID = nil
        selectedSearchOrigin = nil
        searchJustThisPlace = true
        UserDefaults.standard.removeObject(forKey: Self.searchOriginKey)
        UserDefaults.standard.removeObject(forKey: Self.searchStationKey)
        UserDefaults.standard.set(spot.name, forKey: Self.searchSpotKey)
        UserDefaults.standard.set(true, forKey: Self.searchJustThisPlaceKey)
        searchOriginChanged()
    }

    /// A distance searches around the origin; with a picked spot or station, around that place.
    func chooseRadius(_ km: Int) {
        guard searchRadiiKm.contains(km) else { return }
        let changedScope = searchJustThisPlace && searchPlace != nil
        searchJustThisPlace = false
        UserDefaults.standard.set(false, forKey: Self.searchJustThisPlaceKey)
        if radiusKm != km { radiusKm = km } else if changedScope { searchOriginChanged() }
    }

    /// "Just this spot": search only the picked fishing spot or tide station.
    func chooseJustThisPlace() {
        guard searchPlace != nil, !searchJustThisPlace else { return }
        searchJustThisPlace = true
        UserDefaults.standard.set(true, forKey: Self.searchJustThisPlaceKey)
        searchOriginChanged()
    }

    /// "What matters most?": one choice that sets the window preference and, on land, the tie-break.
    func setWhatMatters(_ priority: WindowPriority, land: LandPriority) {
        if landPreferences.priority != land { landPreferences.priority = land }
        if preference != priority { preference = priority }
    }

    /// "Reset to recommended" for everything under More options.
    func resetMoreOptions() {
        landPreferences = LandPreferences(arrivalMinutes: 0, returnMinutes: 0)
        preference = .weather
        tidePhase = .aroundHigh
        sessionHours = 2
    }

    /// Search within the radius around a town, or around a spot chosen on the map.
    func selectSearchOrigin(_ origin: SearchOrigin) {
        selectedSearchOrigin = origin
        selectedSearchStationID = nil
        selectedSearchSpotName = nil
        UserDefaults.standard.removeObject(forKey: Self.searchStationKey)
        UserDefaults.standard.removeObject(forKey: Self.searchSpotKey)
        UserDefaults.standard.set(["name": origin.name, "latitude": origin.point.latitude, "longitude": origin.point.longitude] as [String: Any],
                                  forKey: Self.searchOriginKey)
        searchOriginChanged()
    }

    private func searchOriginChanged() {
        isResolvingRecommendationLocation = false
        recommendationLocationError = nil
        locationRequestID = UUID()
        if showingResults { searchRecommendations() }
        else { invalidateRecommendations() }
        refreshConditions()
    }

    func trackScreen(_ screen: String) {
        Analytics.track("screen_view", ["screen": .text(screen)])
        Task { await repository.trackEvent("feature_used", feature: screen, platform: "ios") }
    }

    func trackSpotOpened(_ spot: Recommendation) {
        Analytics.track("spot_opened", ["spot_id": .text(spot.id), "mode": .text(spot.boat ? "boat" : "land")])
    }

    func selectRulesArea(_ areaID: String) {
        guard fishingRulesAreas.contains(where: { $0.id == areaID }) else { return }
        Analytics.track("rules_area_selected", ["area_id": .text(areaID)])
        rulesAreaSelectionIsManual = true
        selectedRulesAreaID = areaID
    }

    func useCurrentRulesArea() {
        rulesAreaSelectionIsManual = false
        selectedRulesAreaID = hasDeviceLocation ? suggestedRulesAreaID(for: location) : nil
        requestLocation()
    }

    var isNearRaglan: Bool {
        hasDeviceLocation && CLLocation(latitude: location.latitude, longitude: location.longitude)
            .distance(from: CLLocation(latitude: -37.799, longitude: 174.870)) <= 30_000
    }

    private func suggestedRulesAreaID(for point: GeoPoint) -> String? {
        guard point.latitude.isFinite, point.longitude.isFinite,
              (-90 ... 90).contains(point.latitude), (-180 ... 180).contains(point.longitude) else { return nil }
        let origin = CLLocation(latitude: point.latitude, longitude: point.longitude)
        let specialCoasts: [(area: String, latitude: Double, longitude: Double, radius: Double)] = [
            ("auckland-kermadec", -29.25, -177.92, 80_000), // Raoul Island
            ("auckland-kermadec", -30.23, -178.50, 80_000), // Macauley Island
            ("auckland-kermadec", -31.35, -178.83, 80_000), // L'Esperance Rock
            ("chatham-rise", -43.95, -176.56, 150_000),
            ("kaikoura", -42.40, 173.68, 45_000),
            ("fiordland", -44.67, 167.93, 35_000), // Milford Sound
            ("fiordland", -45.28, 166.87, 35_000), // Doubtful Sound
            ("fiordland", -45.74, 166.79, 35_000) // Dusky Sound
        ]
        if let nearest = specialCoasts.min(by: {
            origin.distance(from: CLLocation(latitude: $0.latitude, longitude: $0.longitude)) <
            origin.distance(from: CLLocation(latitude: $1.latitude, longitude: $1.longitude))
        }), origin.distance(from: CLLocation(latitude: nearest.latitude, longitude: nearest.longitude)) <= nearest.radius {
            return nearest.area
        }
        guard (-48 ... -34).contains(point.latitude) else { return nil }
        guard (165 ... 180).contains(point.longitude) else { return nil }

        // Inland locations use the closest catalogued coast as a starting point.
        // MPI coast boundaries and special areas still need checking at the fishing spot.
        guard let nearest = fishingSpots.min(by: {
            origin.distance(from: CLLocation(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude)) <
            origin.distance(from: CLLocation(latitude: $1.coordinate.latitude, longitude: $1.coordinate.longitude))
        }), origin.distance(from: CLLocation(latitude: nearest.coordinate.latitude, longitude: nearest.coordinate.longitude)) <= 180_000 else { return nil }
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
        failedSpotNames = []
        nearbyPlacesWithoutWindows = []
        recommendationError = nil
        hasSearchedRecommendations = false
        isLoadingRecommendations = false
    }

    func searchRecommendations() {
        recommendationTask?.cancel()
        let searchID = UUID()
        recommendationSearchID = searchID
        scoredWindows = []
        failedSpotNames = []
        nearbyPlacesWithoutWindows = []
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
                recommendationLocationError = "Couldn’t get your current location. Choose a town or tide location on Home or enable Location Services, then try again."
            }
            return
        }
        isResolvingRecommendationLocation = false
        recommendationLocationError = nil
        locationRequestID = UUID()
        let radius = Double(radiusKm)
        let station = searchesOnePlace ? selectedSearchStation : nil
        let spot = searchesOnePlace ? selectedSearchSpot : nil
        let daylightOnly = timeMode == .daytime
        let phase = tidePhase, session = sessionHours
        let days = recommendationDays()
        let boat = isBoatFishing
        let hours = preferredHours
        let priority = preference
        let land = landPreferences
        scoredWindows = []
        recommendationError = nil
        hasSearchedRecommendations = true
        isLoadingRecommendations = true
        recommendationTask = Task {
            do {
                let result = try await scoringService.search(origin: origin, radiusKm: radius, selectedStation: station, selectedSpot: spot,
                                                             days: days, boat: boat, preferredHours: hours, priority: priority, land: land,
                                                             daylightOnly: daylightOnly, tidePhase: phase, sessionHours: session)
                guard !Task.isCancelled, recommendationSearchID == searchID else { return }
                let windows = result.windows
                scoredWindows = windows
                failedSpotNames = result.failedSpots
                nearbyPlacesWithoutWindows = result.spotsWithoutWindows
                Analytics.track("search_run", ["mode": .text(boat ? "boat" : "land"), "radius_km": .number(radius),
                                               "date_preset": .text(datePreset.rawValue), "result_count": .number(Double(windows.count))])
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

    /// The saved "More options" for land fishing; the pre-trip check reads them too.
    nonisolated static func storedLandPreferences(_ defaults: UserDefaults = .standard) -> LandPreferences {
        guard let stored = defaults.dictionary(forKey: "land_preferences") else { return LandPreferences(arrivalMinutes: 0, returnMinutes: 0) }
        // 1.0.32 has one walk time for both ways (none when never set) and three roughness choices.
        let arrival = (stored["arrival"] as? Int).flatMap { (0...180).contains($0) ? $0 : nil }
        let returning = (stored["return"] as? Int).flatMap { (0...180).contains($0) ? $0 : nil }
        let walk = arrival ?? returning ?? 0
        let snapped = walkChoices.map(\.minutes).min { abs($0 - walk) < abs($1 - walk) } ?? 0
        return LandPreferences(
            setting: ShoreSetting.saved(stored["setting"] as? String),
            priority: LandPriority.saved(stored["priority"] as? String),
            maxBand: min(2, max(0, stored["maxBand"] as? Int ?? 1)),
            arrivalMinutes: snapped, returnMinutes: snapped)
    }
    nonisolated static func storedPriority(_ defaults: UserDefaults = .standard) -> WindowPriority { WindowPriority.saved(defaults.string(forKey: "fishingPlanPriority")) }
    nonisolated static func storedTidePhase(_ defaults: UserDefaults = .standard) -> TidePhase {
        defaults.string(forKey: "fishingPlanTidePhase").flatMap(TidePhase.init(rawValue:)) ?? .aroundHigh
    }

    override init() {
        super.init()
        restoreTrips()
        Analytics.track("app_open")
        let defaults = UserDefaults.standard
        landPreferences = Self.storedLandPreferences(defaults)
        isBoatFishing = defaults.bool(forKey: Self.planBoatKey)
        if let preset = FishingDatePreset.saved(defaults.string(forKey: Self.planDatePresetKey)) {
            datePreset = preset
        }
        let earliest = firstSelectableDate
        let latest = latestSelectableDate
        if let stored = defaults.object(forKey: Self.planStartDateKey) as? Date {
            customStartDate = min(max(stored, earliest), latest)
        }
        if let stored = defaults.object(forKey: Self.planEndDateKey) as? Date {
            customEndDate = min(max(stored, customStartDate), latest)
        } else {
            customEndDate = max(customEndDate, customStartDate)
        }
        if let mode = FishingTimeMode.saved(defaults.string(forKey: Self.planTimeModeKey)) {
            timeMode = mode
        }
        preference = WindowPriority.saved(defaults.string(forKey: Self.planPriorityKey))
        if let phase = defaults.string(forKey: "fishingPlanTidePhase").flatMap(TidePhase.init(rawValue:)) { tidePhase = phase }
        if sessionLengths.contains(defaults.integer(forKey: "fishingPlanSessionHours")) { sessionHours = defaults.integer(forKey: "fishingPlanSessionHours") }
        let storedStartMinute = defaults.integer(forKey: Self.planStartMinuteKey)
        if defaults.object(forKey: Self.planStartMinuteKey) != nil, (0..<1440).contains(storedStartMinute), storedStartMinute % 30 == 0 {
            preferredStartMinute = storedStartMinute
        }
        let storedEndMinute = defaults.integer(forKey: Self.planEndMinuteKey)
        if defaults.object(forKey: Self.planEndMinuteKey) != nil, (0..<1440).contains(storedEndMinute), storedEndMinute % 30 == 0 {
            preferredEndMinute = storedEndMinute
        }
        let storedRadius = UserDefaults.standard.integer(forKey: Self.searchRadiusKey)
        if storedRadius > 0 { radiusKm = nearestSearchRadius(storedRadius) }
        if defaults.object(forKey: Self.searchJustThisPlaceKey) != nil { searchJustThisPlace = defaults.bool(forKey: Self.searchJustThisPlaceKey) }
        if let name = defaults.string(forKey: Self.searchSpotKey), fishingSpots.contains(where: { $0.name == name }) {
            selectedSearchSpotName = name
        }
        if selectedSearchSpotName != nil {
            // A picked spot is the place; it wins over an older saved tide station or town.
        } else if let stationID = UserDefaults.standard.string(forKey: Self.searchStationKey),
           tideStations.contains(where: { $0.id == stationID }) {
            selectedSearchStationID = stationID
        } else if let stored = defaults.dictionary(forKey: Self.searchOriginKey),
                  let name = stored["name"] as? String, !name.isEmpty,
                  let latitude = stored["latitude"] as? Double, let longitude = stored["longitude"] as? Double,
                  (-90...90).contains(latitude), (-180...180).contains(longitude) {
            selectedSearchOrigin = SearchOrigin(name: name, point: GeoPoint(latitude: latitude, longitude: longitude))
        }
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        refreshStationTide()
        if !searchesFromDeviceLocation { refreshConditions() }
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
                recommendationLocationError = "Location access is off. Choose a town or tide location on Home or enable location access in Settings."
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
                recommendationLocationError = "Location access is off. Choose a town or tide location on Home or enable location access in Settings."
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
        // When the fix arrived, not when it was measured: on a real iPhone
        // requestLocation() often answers with a recent cached fix whose
        // timestamp predates the request.
        deviceLocationUpdatedAt = .now
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
        if searchesFromDeviceLocation {
            if showingResults { searchRecommendations() } else { invalidateRecommendations() }
        }
        if pendingFishPhoto != nil { identifyPendingFish(at: location) }
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if !hasDeviceLocation {
            tideLocationIssue = "Couldn’t get your current location. Choose a station or tap Nearby to try again."
        }
        if isResolvingRecommendationLocation {
            isResolvingRecommendationLocation = false
            recommendationLocationError = "Couldn’t get your current location. Choose a town or tide location on Home or try again."
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
        Analytics.track("tide_station_selected", ["station_id": .text(station.id)])
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
        else { savedSpotNames.insert(spot.id); savedRecommendations[spot.id] = spot; prefetchForOffline([spot]) }
    }

    /// Restores saved windows and the active trip from the last launch.
    private func restoreTrips() {
        let stored = TripStore.load()
        restoringTrips = true
        savedRecommendations = Dictionary(stored.saved.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        savedSpotNames = Set(savedRecommendations.keys)
        activeTrip = stored.active
        tripCheckAt = stored.active?.startsAt.flatMap { TripCheck.checkTime(start: $0) }
        tripBackBy = stored.active == nil ? nil : UserDefaults.standard.object(forKey: "trip_back_by") as? Date
        restoringTrips = false
        prefetchForOffline((stored.active.map { [$0] } ?? []) + stored.saved)
    }
    private func saveTrips() {
        guard !restoringTrips else { return }
        TripStore.save(saved: savedRecommendations.values.sorted { ($0.startsAt ?? .distantPast) < ($1.startsAt ?? .distantPast) }, active: activeTrip)
    }
    /// Fetches the forecast, a year of LINZ tides and the MPI rules for each window's place while there is a signal,
    /// so the active trip and saved spots still show conditions at the water. Failures are ignored.
    private func prefetchForOffline(_ windows: [Recommendation]) {
        guard !restoringTrips else { return }
        var seen = Set<String>()
        let points = windows.compactMap(\.coordinate).filter { seen.insert(OfflineCache.placeKey("p", $0)).inserted }.prefix(6)
        guard !points.isEmpty else { return }
        let repository = repository
        Task.detached(priority: .utility) {
            for point in points {
                _ = try? await PlaceConditionsService().load(at: point, pastDays: 1)
                if let station = nearbyTideStations(point: point).first { _ = try? await repository.tide(for: station, date: .now) }
            }
        }
    }
    /// Shows the window in a shared link the app was opened with. Other addresses are ignored.
    func openSharedWindow(_ url: URL) {
        guard let token = SharedWindowLink.token(from: url) else { return }
        Task {
            if let window = await ShareLinkService.live.window(forToken: token) {
                showingResults = false
                selectedSpot = window
            } else {
                sharedWindowNotice = "This shared window couldn't be opened. The link may have expired or be damaged, or you may be offline."
            }
        }
    }

    /// Closes the window list and details and opens the Map tab on `place` (see Recommendation.mapPlace).
    func showOnMap(_ place: ConditionPlace) {
        selectedSpot = nil
        showingResults = false
        mapFocus = place
        selectedTab = 1
    }

    func startTrip(_ spot: Recommendation) { activeTrip = spot; selectedSpot = nil }

    /// Opens the catch log with a new entry for the fish just identified.
    func logIdentifiedCatch() {
        guard let check = fishCheck, check.isFish else { return }
        catchPrefill = CatchPrefill(species: check.commonName, photo: selectedPhoto, rulesAreaID: selectedRulesAreaID)
        trackScreen("catch_log")
    }
    /// "Log a catch" on the active trip: a new entry at the trip's spot.
    func logCatchOnTrip() { catchPrefill = CatchPrefill(species: "", photo: nil, rulesAreaID: selectedRulesAreaID) }

    /// A new entry at the current time: the active trip's spot, else the phone's position and nearest listed spot.
    func newCatch(species: String = "", rulesAreaID: String? = nil) -> CatchEntry {
        let device = hasDeviceLocation ? location : nil
        let tripPoint = activeTrip?.coordinate
        let atTrip = tripPoint != nil && (device == nil || placeDistanceKm(device!, tripPoint!) <= 5)
        let point = atTrip ? tripPoint : device
        var place = ""
        if atTrip, let trip = activeTrip { place = trip.name }
        else if let device {
            place = fishingSpots.filter { placeDistanceKm(device, $0.coordinate) <= 3 }
                .min { placeDistanceKm(device, $0.coordinate) < placeDistanceKm(device, $1.coordinate) }?.name
                ?? devicePlaceName.map { "Near \($0)" } ?? ""
        }
        return CatchEntry(caughtAt: .now, species: species, placeName: place, latitude: point?.latitude, longitude: point?.longitude,
                          rulesAreaID: rulesAreaID ?? selectedRulesAreaID)
    }

    /// Adds or replaces `entry`, keeps `photo` with it, then looks up the conditions at that time and place.
    func saveCatch(_ entry: CatchEntry, photo: UIImage?) {
        let previous = catches.first { $0.id == entry.id }
        var saved = entry
        if let photo { saved.photoFile = CatchLogStore.keepPhoto(photo, id: entry.id) ?? entry.photoFile }
        // A changed time or place needs fresh conditions; otherwise the recorded ones stay.
        let moved = previous == nil || previous?.caughtAt != entry.caughtAt || previous?.latitude != entry.latitude || previous?.longitude != entry.longitude
        if moved { saved.conditions = CatchConditions() }
        saved.updatedAt = Date(timeIntervalSince1970: Date.now.timeIntervalSince1970.rounded())
        updateCatches { $0.filter { $0.id != saved.id } + [saved] }
        Analytics.track(previous == nil ? "catch_logged" : "catch_edited", ["kept": .flag(saved.kept), "has_length": .flag(saved.lengthCm != nil)])
        guard moved else { return }
        let stamp = saved.updatedAt
        Task {
            let conditions = await CatchConditionsLookup.lookup(point: saved.point, at: saved.caughtAt)
            updateCatches { list in list.map { $0.id == saved.id && $0.updatedAt == stamp ? { var e = $0; e.conditions = conditions; return e }($0) : $0 } }
        }
    }
    /// Hides the entry and deletes its photo; the entry stays as a tombstone so account sync removes it everywhere.
    func deleteCatch(_ id: String) {
        guard let entry = catches.first(where: { $0.id == id }) else { return }
        CatchLogStore.deletePhoto(entry.photoFile)
        let now = Date(timeIntervalSince1970: Date.now.timeIntervalSince1970.rounded())
        updateCatches { $0.map { $0.id == id ? { var e = $0; e.deleted = true; e.photoFile = nil; e.updatedAt = now; return e }($0) : $0 } }
    }
    func deleteAllCatches() {
        let now = Date(timeIntervalSince1970: Date.now.timeIntervalSince1970.rounded())
        catches.forEach { CatchLogStore.deletePhoto($0.photoFile) }
        updateCatches { $0.map { var e = $0; e.deleted = true; e.photoFile = nil; e.updatedAt = now; return e } }
    }
    private func updateCatches(_ change: ([CatchEntry]) -> [CatchEntry]) {
        catches = change(catches)
        let snapshot = catches
        Task.detached(priority: .utility) { CatchLogStore.save(snapshot) }
    }
    /// MPI rows for `species` in an area, for the catch log's size check. Remembered for this session.
    func rulesFor(species: String, areaID: String) async throws -> FishRulesResult {
        let key = species.trimmingCharacters(in: .whitespaces).lowercased() + "|" + areaID
        if let cached = catchRulesCache[key] { return cached }
        let result = try await repository.fishRules(species: species.trimmingCharacters(in: .whitespaces), areaID: areaID)
        catchRulesCache[key] = result
        return result
    }
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
                fishIdentityQuota = snapshot?.permissions?.fishIdentityQuota
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
                Analytics.track("sign_up_succeeded")
                verificationPending = true
            } else {
                let snapshot = try await repository.registerOrLogin(email: email, password: password)
                Analytics.track("sign_in_succeeded", ["method": .text("password")])
                account = snapshot.user; fishIdentityQuota = snapshot.permissions?.fishIdentityQuota; hasStoredSession = true; accountNotice = "You’re signed in."; verificationPending = false
                await refreshSignInProviders()
            }
            accountBusy = false
            return true
        } catch {
            Analytics.track("sign_in_failed", ["method": .text(createAccount ? "sign_up" : "password"), "error_code": .text(Analytics.errorCode(error))])
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

    func refreshSignInProviders() async {
        if let providers = try? await repository.signInProviders() { signInProviders = providers }
    }

    func signInWithProvider(_ provider: String) async {
        guard !accountBusy else { return }
        Analytics.track("social_sign_in_started", ["method": .text(provider)])
        accountRequestVersion += 1
        let version = accountRequestVersion
        let linking = account != nil
        accountLoading = false; accountLoadFailed = false
        accountBusy = true; accountError = nil; accountNotice = nil
        defer { if version == accountRequestVersion { accountBusy = false } }
        do {
            let snapshot: AccountSnapshot
            if provider == "apple" && signInProviders.appleNative {
                let flow = try await repository.startAppleSignIn(link: linking)
                let credential = try await appleSignInSheet.authorize(nonce: flow.nonce)
                snapshot = try await repository.completeAppleSignIn(state: flow.state, credential: credential)
            } else {
                snapshot = try await browserSignIn(provider: provider, linking: linking)
            }
            guard version == accountRequestVersion else { return }
            Analytics.track("sign_in_succeeded", ["method": .text("social")])
            account = snapshot.user; fishIdentityQuota = snapshot.permissions?.fishIdentityQuota; hasStoredSession = true; verificationPending = false
            accountNotice = linking ? "Account connected. You can use it to sign in next time." : "You’re signed in."
            await refreshSignInProviders()
        } catch {
            guard version == accountRequestVersion else { return }
            if (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin || (error as? ASAuthorizationError)?.code == .canceled {
                accountNotice = "Sign-in was cancelled. You can try again."
            } else if error is ASAuthorizationError {
                accountError = "Could not finish Apple sign-in. Please try again."
            } else { accountError = error.localizedDescription }
        }
    }

    /// Google, or Apple on a service without the native flow: the system browser and the service's callback.
    private func browserSignIn(provider: String, linking: Bool) async throws -> AccountSnapshot {
        let flow = try await repository.startSocialSignIn(provider: provider, link: linking)
        let callback = try await socialSignInBrowser.authenticate(url: flow.url)
        guard callback.scheme == "nz.fishingnz.catchcheck", callback.host == "auth", callback.path == "/callback",
              let components = URLComponents(url: callback, resolvingAgainstBaseURL: false) else {
            throw AccountAPIError(message: "Invalid sign-in response. Please try again.", status: 400, code: nil)
        }
        let items = components.queryItems ?? []
        if let error = items.first(where: { $0.name == "oauth_error" })?.value {
            let message = error == "cancelled" ? "Sign-in was cancelled. You can try again." : error == "account_exists"
                ? "This account already exists or is connected elsewhere. Sign in with your usual method, then connect Google or Apple from your profile."
                : "Could not finish sign-in. Please try again."
            throw AccountAPIError(message: message, status: 400, code: nil)
        }
        guard let code = items.first(where: { $0.name == "oauth_code" })?.value else {
            throw AccountAPIError(message: "Could not finish sign-in. Please try again.", status: 400, code: nil)
        }
        return try await repository.completeSocialSignIn(code: code, secret: flow.secret)
    }
    func saveAccountProfile(displayName: String, countryCode: String) {
        accountBusy = true; accountError = nil; accountNotice = nil
        Task {
            do { let snapshot = try await repository.saveProfile(displayName: displayName, countryCode: countryCode); account = snapshot.user; fishIdentityQuota = snapshot.permissions?.fishIdentityQuota; accountNotice = "Profile saved." }
            catch { accountError = error.localizedDescription }
            accountBusy = false
        }
    }
    func sendFeedback(category: String, message: String, rating: Int?) async -> Bool {
        accountBusy = true; accountError = nil; accountNotice = nil
        do {
            try await repository.sendFeedback(category: category, message: message, rating: rating)
            Analytics.track("feedback_sent")
            accountNotice = "Thanks for your feedback."
            accountBusy = false
            return true
        } catch {
            accountError = error.localizedDescription
            accountBusy = false
            return false
        }
    }
    func changePassword(current: String, new: String) {
        accountBusy = true; accountError = nil; accountNotice = nil
        Task {
            do {
                try await repository.changePassword(current: current, new: new)
                Analytics.track("password_changed")
                accountNotice = "Password changed. Your other devices were signed out."
            } catch { accountError = error.localizedDescription }
            accountBusy = false
        }
    }
    func signOutEverywhere() {
        accountRequestVersion += 1
        accountLoadFailed = false
        accountLoading = false
        accountBusy = true; accountError = nil; accountNotice = nil
        Task {
            do {
                try await repository.logoutEverywhere()
                Analytics.track("signed_out_everywhere")
                PhotoUploadConsent.isRemembered = false
                account = nil; fishIdentityQuota = nil; hasStoredSession = false; verificationPending = false
                accountNotice = "You’re signed out of every device."
            } catch { accountError = error.localizedDescription }
            accountBusy = false
        }
    }
    func deleteAccount() {
        guard !accountBusy else { return }
        accountRequestVersion += 1
        accountLoadFailed = false
        accountLoading = false
        accountBusy = true; accountError = nil; accountNotice = nil
        Task {
            do {
                try await repository.deleteAccount()
                // Forget everything tied to the account on this phone, and stop any fish check still in flight.
                fishIdentifyRequestID = UUID()
                fishRulesTask?.cancel()
                fishRulesRequestID = UUID()
                pendingFishPhoto = nil
                selectedPhoto = nil; fishCheck = nil; isCheckingFish = false; self.error = nil
                isLoadingFishRules = false; fishRulesError = nil
                savedSpotNames = []; savedRecommendations = [:]; activeTrip = nil
                account = nil; fishIdentityQuota = nil; hasStoredSession = false; verificationPending = false
                signInProviders = SignInProviders()
                AnalyticsPreferences.setEnabled(false)
                PhotoUploadConsent.isRemembered = false
                accountNotice = "Your account and associated data have been deleted."
            } catch { accountError = error.localizedDescription }
            accountBusy = false
        }
    }
    func signOut() {
        Analytics.track("sign_out")
        accountRequestVersion += 1
        accountLoadFailed = false
        accountLoading = false
        accountBusy = true; accountError = nil; accountNotice = nil
        PhotoUploadConsent.isRemembered = false
        Task { await repository.signOut(); account = nil; fishIdentityQuota = nil; hasStoredSession = false; verificationPending = false; accountBusy = false; accountNotice = "You’re signed out." }
    }
    func identifyFish() {
        guard !isCheckingFish else { return }
        guard let photo = selectedPhoto else { return }
        error = nil
        if hasDeviceLocation { identify(photo, at: location) }
        else if locationManager.authorizationStatus == .denied || locationManager.authorizationStatus == .restricted { identify(photo, at: location) }
        else { pendingFishPhoto = photo; isCheckingFish = true; requestLocation() }
    }
    private func identifyPendingFish(at point: GeoPoint) { guard let photo = pendingFishPhoto else { return }; pendingFishPhoto = nil; identify(photo, at: point) }
    private func identify(_ photo: UIImage, at point: GeoPoint) {
        Analytics.track("fish_identify_started")
        isCheckingFish = true
        fishIdentifyRequestID = UUID()
        let requestID = fishIdentifyRequestID
        let requestedAreaID = selectedRulesAreaID
        let accountVersion = accountRequestVersion
        Task {
            do {
                let result = try await repository.identifyFish(image: photo, at: point, hasDeviceLocation: hasDeviceLocation, selectedRulesAreaID: requestedAreaID)
                Analytics.track("fish_identify_succeeded", ["is_fish": .flag(result.isFish),
                                                            "confidence_level": .text(AnalyticsPayload.confidenceLevel(percent: result.confidence))])
                if requestID == fishIdentifyRequestID && selectedPhoto === photo {
                    fishRulesTask?.cancel()
                    fishRulesRequestID = UUID()
                    fishCheck = result
                    if let quota = result.fishIdentityQuota { fishIdentityQuota = quota }
                    isLoadingFishRules = false
                    fishRulesError = nil
                    if result.isFish && result.areaID != selectedRulesAreaID { refreshFishRulesForSelection() }
                }
            } catch {
                Analytics.track("fish_identify_failed", ["error_code": .text(Analytics.errorCode(error))])
                if requestID == fishIdentifyRequestID && selectedPhoto === photo {
                    if let quota = (error as? FishIdentificationError)?.quota { fishIdentityQuota = quota }
                    self.error = error.localizedDescription
                }
            }
            if let snapshot = try? await repository.currentAccount(), accountVersion == accountRequestVersion {
                if let quota = snapshot.permissions?.fishIdentityQuota { fishIdentityQuota = quota }
            }
            if requestID == fishIdentifyRequestID && selectedPhoto === photo { isCheckingFish = false }
        }
    }
    func retryFishRules() { refreshFishRulesForSelection(force: true) }

    private func refreshFishRulesForSelection(force: Bool = false) {
        fishRulesTask?.cancel()
        fishRulesRequestID = UUID()
        guard let check = fishCheck, check.isFish else { return }
        let areaID = selectedRulesAreaID
        if !force && check.areaID == areaID && !isLoadingFishRules { return }
        let areaName = fishingRulesAreas.first(where: { $0.id == areaID })?.name ?? "Choose an MPI fishing area"
        fishCheck = check.awaitingRules(for: areaID, name: areaName)
        fishRulesError = nil
        guard let areaID else { isLoadingFishRules = false; return }
        isLoadingFishRules = true
        let requestID = fishRulesRequestID
        let photo = selectedPhoto
        fishRulesTask = Task {
            do {
                let rules = try await repository.fishRules(species: check.commonName, areaID: areaID)
                guard requestID == fishRulesRequestID, selectedRulesAreaID == areaID,
                      selectedPhoto === photo, fishCheck?.commonName == check.commonName else { return }
                fishCheck = fishCheck?.withRules(rules)
                isLoadingFishRules = false
            } catch is CancellationError {
                return
            } catch {
                guard requestID == fishRulesRequestID, selectedRulesAreaID == areaID,
                      selectedPhoto === photo, fishCheck?.commonName == check.commonName else { return }
                fishRulesError = "Could not load this area's saved limits. Check the official MPI rules."
                isLoadingFishRules = false
            }
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
