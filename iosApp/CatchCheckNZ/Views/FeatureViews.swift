import SwiftUI
import MapKit
import UIKit
import EventKit
import EventKitUI

struct FishingMapView: View {
    private enum Layer: String, CaseIterable {
        case map = "Map"
        case satellite = "Satellite"
        case hybrid = "Hybrid"

        var style: MapStyle {
            switch self {
            case .map: .standard(elevation: .realistic)
            case .satellite: .imagery(elevation: .realistic)
            case .hybrid: .hybrid(elevation: .realistic)
            }
        }
    }

    @EnvironmentObject private var vm: FishingViewModel
    @State private var filter = "All"
    @State private var layer: Layer = .map
    @State private var currentCamera: MapCamera?
    @State private var needsFirstLocationCenter = true
    @State private var recenterOnLocationUpdate = false
    @State private var position: MapCameraPosition = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: -41.2, longitude: 174.8), span: MKCoordinateSpan(latitudeDelta: 13, longitudeDelta: 13)))
    private var visibleSpots: [FishingSpot] { fishingSpots.filter { filter == "All" || (filter == "Boat" && $0.boat) || (filter == "Land" && !$0.boat) } }
    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) { Text("Fishing map").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy); Text("Explore named coastal areas · check access and local rules").foregroundStyle(.secondary); Picker("Filter", selection: $filter) { Text("All spots").tag("All"); Text("Land fishing").tag("Land"); Text("Boat fishing").tag("Boat") }.pickerStyle(.segmented) }.padding(20)
            Map(position: $position) {
                UserAnnotation()
                ForEach(visibleSpots, id: \.id) { spot in
                    Annotation(spot.name, coordinate: CLLocationCoordinate2D(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude)) {
                        Button {
                            vm.selectedSpot = vm.recommendations.first(where: { $0.name == spot.name && $0.boat == spot.boat })
                                ?? Recommendation(name: spot.name, area: spot.area, rating: 0, time: "", distance: "Search Home for a live forecast", reasons: [], boat: spot.boat)
                        } label: {
                            Image(systemName: spot.boat ? "ferry.fill" : "mappin.circle.fill")
                                .font(.title).foregroundStyle(CatchCheckColor.orange).background(.white, in: Circle())
                        }
                    }
                }
            }
            .mapStyle(layer.style)
            .onMapCameraChange(frequency: .continuous) { context in
                currentCamera = context.camera
            }
            .overlay(alignment: .topTrailing) {
                VStack(spacing: 12) {
                    Menu {
                        ForEach(Layer.allCases, id: \.self) { choice in
                            Button {
                                layer = choice
                            } label: {
                                if choice == layer {
                                    Label(choice.rawValue, systemImage: "checkmark")
                                } else {
                                    Text(choice.rawValue)
                                }
                            }
                        }
                    } label: {
                        mapControlIcon("square.3.layers.3d")
                    }
                    .accessibilityLabel("Map layers")

                    Button {
                        guard let camera = currentCamera else { return }
                        withAnimation {
                            position = .camera(MapCamera(centerCoordinate: camera.centerCoordinate,
                                                         distance: camera.distance,
                                                         heading: 0,
                                                         pitch: camera.pitch))
                        }
                    } label: {
                        mapControlIcon("location.north.line.fill")
                            .rotationEffect(.degrees(-(currentCamera?.heading ?? 0)))
                    }
                    .accessibilityLabel("Reset map to north")
                }
                .padding(18)
            }
            .overlay(alignment: .bottomTrailing) {
                Button {
                    recenterOnLocationUpdate = true
                    if vm.hasDeviceLocation { centerOnCurrentLocation(); recenterOnLocationUpdate = false }
                    vm.requestLocation()
                } label: {
                    mapControlIcon("location.fill")
                }
                .accessibilityLabel("Center map on my location")
                .padding(18)
            }
        }.background(CatchCheckColor.cream)
            .onAppear {
                if vm.hasDeviceLocation { centerOnCurrentLocation(); needsFirstLocationCenter = false }
                else { needsFirstLocationCenter = true }
                vm.requestLocation()
            }
            .onChange(of: vm.location) { _, _ in
                if vm.hasDeviceLocation && (needsFirstLocationCenter || recenterOnLocationUpdate) {
                    centerOnCurrentLocation()
                    needsFirstLocationCenter = false
                    recenterOnLocationUpdate = false
                }
            }
            .onChange(of: vm.hasDeviceLocation) { _, available in
                if available && (needsFirstLocationCenter || recenterOnLocationUpdate) {
                    centerOnCurrentLocation()
                    needsFirstLocationCenter = false
                    recenterOnLocationUpdate = false
                }
            }
    }

    private func centerOnCurrentLocation() {
        position = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: vm.location.latitude, longitude: vm.location.longitude),
                                              span: MKCoordinateSpan(latitudeDelta: 0.15, longitudeDelta: 0.15)))
    }

    private func mapControlIcon(_ symbol: String) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 20, weight: .semibold))
            .foregroundStyle(CatchCheckColor.navy)
            .frame(width: 48, height: 48)
            .background(.white, in: Circle())
            .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
    }
}

struct TideForecastView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var showingStationPicker = false
    private var nzCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar
    }
    private var formatter: DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_NZ")
        formatter.timeZone = nzCalendar.timeZone
        formatter.dateFormat = "EEEE, d MMMM yyyy"
        return formatter
    }
    private var canGoBack: Bool {
        nzCalendar.startOfDay(for: vm.tideDate) > nzCalendar.startOfDay(for: .now)
    }
    private var canGoForward: Bool {
        guard let lastPublishedDay = nzCalendar.date(from: DateComponents(year: 2029, month: 12, day: 31)) else { return false }
        return nzCalendar.startOfDay(for: vm.tideDate) < lastPublishedDay
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Tide forecast")
                        .font(.largeTitle.bold())
                        .foregroundStyle(CatchCheckColor.navy)
                    Text("New Zealand tide predictions · heights above Chart Datum")
                        .foregroundStyle(.secondary)

                    Card {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Tide station").font(.caption).foregroundStyle(.secondary)
                                Button { showingStationPicker = true } label: {
                                    HStack {
                                        Text(vm.selectedStation.name).font(.title2.bold())
                                        Image(systemName: "chevron.down").font(.caption.bold())
                                    }.foregroundStyle(CatchCheckColor.navy)
                                }.buttonStyle(.plain)
                            }
                            Spacer()
                            Button {
                                vm.useCurrentLocationForTides()
                            } label: {
                                Label(vm.hasDeviceLocation ? vm.devicePlaceName ?? "My location" : "Use my location", systemImage: "location.fill")
                            }
                            .buttonStyle(.bordered)
                            .accessibilityLabel("Use the tide station nearest my current location")
                        }
                        Text(vm.tideLocationSummary)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        Button("Choose another tide station") { showingStationPicker = true }
                            .font(.subheadline.weight(.semibold))
                    }

                    HStack(spacing: 14) {
                        Button { vm.changeTideDate(by: -1) } label: {
                            Image(systemName: "chevron.left")
                                .frame(width: 36, height: 44)
                        }
                        .disabled(!canGoBack)
                        .accessibilityLabel("Previous day")
                        Spacer(minLength: 0)
                        Text(formatter.string(from: vm.tideDate))
                            .font(.headline)
                            .foregroundStyle(CatchCheckColor.navy)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                        Spacer(minLength: 0)
                        Button { vm.changeTideDate(by: 1) } label: {
                            Image(systemName: "chevron.right")
                                .frame(width: 36, height: 44)
                        }
                        .disabled(!canGoForward)
                        .accessibilityLabel("Next day")
                    }
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 35).onEnded { value in
                        guard abs(value.translation.width) > abs(value.translation.height) * 1.4 else { return }
                        if value.translation.width < 0 {
                            if canGoForward { vm.changeTideDate(by: 1) }
                        } else if canGoBack {
                            vm.changeTideDate(by: -1)
                        }
                    })

                    if let tide = vm.stationTide {
                        TideDetails(tide: tide, day: vm.tideDate, canGoBack: canGoBack, canGoForward: canGoForward) {
                            vm.changeTideDate(by: $0)
                        }
                            .id("\(vm.selectedStation.id)-\(nzCalendar.startOfDay(for: vm.tideDate).timeIntervalSince1970)")
                    } else if let tideError = vm.tideError {
                        Card {
                            Text(tideError).foregroundStyle(CatchCheckColor.navy)
                            Button("Try again") { vm.refreshStationTide() }
                                .buttonStyle(.bordered)
                        }
                    } else {
                        ProgressView("Loading LINZ tide predictions…")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 28)
                    }
                    Text("Source: Toitū Te Whenua Land Information New Zealand (LINZ). Published high and low tide heights are above Chart Datum. The curve and heights between them are interpolated estimates; weather can change actual water levels.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Tide")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear { vm.activateTideTab() }
        .sheet(isPresented: $showingStationPicker) { TideStationPicker() }
    }
}

private struct TideStationPicker: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var matchingStations: [TideStation] {
        let sorted = tideStations.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        guard !query.isEmpty else { return sorted }
        return sorted.filter { $0.name.localizedStandardContains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        vm.useCurrentLocationForTides()
                        dismiss()
                    } label: {
                        Label(vm.hasDeviceLocation ? "Near \(vm.devicePlaceName ?? "my location")" : "Use my location", systemImage: "location.fill")
                    }
                } footer: {
                    Text("Only LINZ sites with direct daily predictions are listed. Offset locations need a reference-port calculation.")
                }
                Section("Stations") {
                    ForEach(matchingStations) { station in
                        Button {
                            vm.chooseStation(station)
                            dismiss()
                        } label: {
                            HStack {
                                Text(station.name)
                                Spacer()
                                if vm.selectedStation == station { Image(systemName: "checkmark").foregroundStyle(CatchCheckColor.accent) }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Search tide stations")
            .navigationTitle("Tide locations")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

private struct TideDetails: View {
    let tide: TideState
    let day: Date
    let canGoBack: Bool
    let canGoForward: Bool
    let onDateSwipe: (Int) -> Void
    @State private var selectedMinute: Int?

    private var isToday: Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar.isDateInToday(day)
    }
    private var shownMinute: Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        let now = Date()
        let minute = isToday ? (calendar.dateComponents([.minute], from: calendar.startOfDay(for: now), to: now).minute ?? 720) : 720
        return min(max(selectedMinute ?? minute, tide.points.first?.minuteOfDay ?? 0), tide.points.last?.minuteOfDay ?? 1440)
    }
    private var shownHeight: Double? {
        guard let first = tide.points.first, let last = tide.points.last else { return nil }
        let left = tide.points.last(where: { $0.minuteOfDay <= shownMinute }) ?? first
        let right = tide.points.first(where: { $0.minuteOfDay >= shownMinute }) ?? last
        guard right.minuteOfDay != left.minuteOfDay else { return left.level }
        let fraction = Double(shownMinute - left.minuteOfDay) / Double(right.minuteOfDay - left.minuteOfDay)
        return left.level + (right.level - left.level) * fraction
    }
    private var shownTime: String {
        tideClockLabel(day: day, minute: shownMinute)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Card(background: CatchCheckColor.seafoam) {
                Text(isToday ? "Estimated height now" : "Estimated height around midday")
                    .foregroundStyle(.secondary)
                Text(tide.currentLevel)
                    .font(.largeTitle.bold())
                    .foregroundStyle(CatchCheckColor.navy)
                Text("Next \(tide.nextEvent.lowercased()) tide · \(tide.eventTime)")
                    .font(.subheadline)
            }
            Card {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Tide height through the day").bold().foregroundStyle(.primary)
                    Text("Swipe this heading for another day. Tap or drag the curve to inspect time.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 35).onEnded { value in
                    guard abs(value.translation.width) > abs(value.translation.height) * 1.4 else { return }
                    if value.translation.width < 0 && canGoForward { onDateSwipe(1) }
                    if value.translation.width > 0 && canGoBack { onDateSwipe(-1) }
                })
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(shownTime).font(.title3.bold())
                        Text("Estimated above Chart Datum").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 8)
                    if let shownHeight {
                        Text(String(format: "%.2f m", shownHeight))
                            .font(.title3.bold()).foregroundStyle(.primary)
                    }
                }
                TideCurve(points: tide.points, day: day, selectedMinute: $selectedMinute)
                    .frame(height: 210)
                if let first = tide.points.first, let last = tide.points.last, last.minuteOfDay > first.minuteOfDay {
                    Slider(value: Binding(get: { Double(shownMinute) }, set: { selectedMinute = Int($0.rounded()) }),
                           in: Double(first.minuteOfDay)...Double(last.minuteOfDay), step: 1)
                        .accessibilityLabel("Tide time")
                        .accessibilityValue("\(shownTime), \(shownHeight.map { String(format: "%.2f metres", $0) } ?? "height unavailable")")
                }
                HStack {
                    if let first = tide.points.first { Text(first.time) }
                    Spacer()
                    if tide.points.count > 2 { Text(tide.points[tide.points.count / 2].time) }
                    Spacer()
                    if let last = tide.points.last { Text(last.time) }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            Card {
                Text("Published high and low tides")
                    .bold()
                    .foregroundStyle(CatchCheckColor.navy)
                ForEach(tide.events) { event in
                    HStack {
                        Image(systemName: event.type == "High" ? "arrow.up" : "arrow.down")
                            .foregroundStyle(CatchCheckColor.navy)
                            .frame(width: 18)
                        Text("\(event.type) tide")
                            .bold()
                            .foregroundStyle(CatchCheckColor.navy)
                        Spacer()
                        Text("\(event.time) · \(event.height)")
                    }
                }
            }
        }
        .onAppear { selectedMinute = shownMinute }
    }
}

private struct TideCurve: View {
    let points: [TidePoint]
    let day: Date
    @Binding var selectedMinute: Int?

    var body: some View {
        GeometryReader { geometry in
            let levels = points.map(\.level)
            let low = levels.min() ?? 0
            let high = levels.max() ?? 1
            let padding = max((high - low) * 0.12, 0.1)
            let floor = low - padding
            let range = max(high - low + 2 * padding, 0.2)
            let width = geometry.size.width
            let height = geometry.size.height
            let firstMinute = points.first?.minuteOfDay ?? 0
            let lastMinute = points.last?.minuteOfDay ?? 1440
            let span = max(lastMinute - firstMinute, 1)

            ZStack {
                ForEach(0..<3, id: \.self) { line in
                    Path { path in
                        let y = height * CGFloat(line + 1) / 4
                        path.move(to: CGPoint(x: 0, y: y))
                        path.addLine(to: CGPoint(x: width, y: y))
                    }
                    .stroke(CatchCheckColor.navy.opacity(0.12), lineWidth: 1)
                }
                Path { path in
                    guard points.count > 1 else { return }
                    path.move(to: CGPoint(x: 0, y: height))
                    for index in points.indices {
                        path.addLine(to: chartPoint(index, width: width, height: height, floor: floor, range: range, firstMinute: firstMinute, span: span))
                    }
                    path.addLine(to: CGPoint(x: width, y: height))
                    path.closeSubpath()
                }
                .fill(LinearGradient(colors: [CatchCheckColor.seafoam, CatchCheckColor.seafoam.opacity(0.15)], startPoint: .top, endPoint: .bottom))
                Path { path in
                    guard points.count > 1 else { return }
                    for index in points.indices {
                        let position = chartPoint(index, width: width, height: height, floor: floor, range: range, firstMinute: firstMinute, span: span)
                        if index == 0 { path.move(to: position) }
                        else { path.addLine(to: position) }
                    }
                }
                .stroke(CatchCheckColor.navy, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                if let selectedMinute, let marker = interpolatedPoint(minute: selectedMinute, width: width, height: height, floor: floor, range: range, firstMinute: firstMinute, span: span) {
                    Path { path in
                        path.move(to: CGPoint(x: marker.x, y: 0))
                        path.addLine(to: CGPoint(x: marker.x, y: height))
                    }
                    .stroke(CatchCheckColor.navy.opacity(0.45), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    Circle()
                        .fill(CatchCheckColor.navy)
                        .frame(width: 13, height: 13)
                        .position(marker)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                guard points.count > 1, width > 0 else { return }
                let fraction = min(max(value.location.x / width, 0), 1)
                selectedMinute = firstMinute + Int((fraction * CGFloat(span)).rounded())
            })
        }
        .accessibilityLabel("Tide height curve. Slide to hear interpolated time and height.")
        .accessibilityValue(selectedMinute.map { tideClockLabel(day: day, minute: $0) } ?? "No time selected")
    }

    private func chartPoint(_ index: Int, width: CGFloat, height: CGFloat, floor: Double, range: Double, firstMinute: Int, span: Int) -> CGPoint {
        let x = width * CGFloat(points[index].minuteOfDay - firstMinute) / CGFloat(span)
        let y = height * (1 - CGFloat((points[index].level - floor) / range))
        return CGPoint(x: x, y: y)
    }
    private func interpolatedPoint(minute: Int, width: CGFloat, height: CGFloat, floor: Double, range: Double, firstMinute: Int, span: Int) -> CGPoint? {
        guard let first = points.first, let last = points.last else { return nil }
        let left = points.last(where: { $0.minuteOfDay <= minute }) ?? first
        let right = points.first(where: { $0.minuteOfDay >= minute }) ?? last
        let fraction = right.minuteOfDay == left.minuteOfDay ? 0 : Double(minute - left.minuteOfDay) / Double(right.minuteOfDay - left.minuteOfDay)
        let level = left.level + (right.level - left.level) * fraction
        return CGPoint(x: width * CGFloat(minute - firstMinute) / CGFloat(span), y: height * (1 - CGFloat((level - floor) / range)))
    }
}

private func tideClockLabel(day: Date, minute: Int) -> String {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
    let time = calendar.date(byAdding: .minute, value: minute, to: calendar.startOfDay(for: day)) ?? day
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_NZ")
    formatter.timeZone = calendar.timeZone
    formatter.dateFormat = "h:mm a"
    return formatter.string(from: time)
}

struct TripsView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedSavedSpot: Recommendation?
    @State private var calendarTrip: Recommendation?
    private var saved: [Recommendation] { vm.savedRecommendations.values.sorted { $0.rating > $1.rating } }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Your trips").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text("Saved spots and active plans").foregroundStyle(.secondary)
                    if let active = vm.activeTrip {
                        Card(background: CatchCheckColor.seafoam) {
                            Text("Active trip").bold().foregroundStyle(CatchCheckColor.navy)
                            Text(active.name).font(.title2).foregroundStyle(CatchCheckColor.navy)
                            if !active.time.isEmpty { Text(active.time).foregroundStyle(.secondary) }
                            if active.startsAt == nil {
                                Text("Choose the date and time in your calendar.").font(.caption).foregroundStyle(.secondary)
                            }
                            Button("Add to calendar") { calendarTrip = active }.buttonStyle(.bordered)
                            Button("End trip") { vm.activeTrip = nil }.buttonStyle(.bordered)
                        }
                    }
                    Text("Saved spots").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    if saved.isEmpty {
                        Text("Save a recommended spot to see it here.").foregroundStyle(.secondary)
                    } else {
                        ForEach(saved) { spot in RecommendationCard(spot: spot) { selectedSavedSpot = spot } }
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Trips")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .sheet(item: $selectedSavedSpot) { SpotDetailView(spot: $0) }
        .sheet(item: $calendarTrip) { TripCalendarEditor(trip: $0) }
    }
}

struct TripCalendarEditor: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss
    let trip: Recommendation

    func makeUIViewController(context: Context) -> EKEventEditViewController {
        let store = EKEventStore()
        let controller = EKEventEditViewController()
        controller.eventStore = store
        controller.editViewDelegate = context.coordinator

        let event = EKEvent(eventStore: store)
        event.title = "Fishing at \(trip.name)"
        event.location = "\(trip.name), \(trip.area), New Zealand"
        event.notes = "Fishing plan from CatchCheck NZ. Check the latest forecast, local access and fishing rules before you go."
        let start = trip.startsAt ?? Date().addingTimeInterval(3_600)
        event.startDate = start
        event.endDate = trip.endsAt.flatMap { $0 > start ? $0 : nil } ?? start.addingTimeInterval(7_200)
        controller.event = event
        return controller
    }

    func updateUIViewController(_ controller: EKEventEditViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onDone: { dismiss() }) }

    final class Coordinator: NSObject, EKEventEditViewDelegate {
        private let onDone: () -> Void
        init(onDone: @escaping () -> Void) { self.onDone = onDone }
        func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
            onDone()
        }
    }
}

struct FishingRulesArea: Identifiable, Hashable {
    let id: String
    let name: String
    let slug: String
    let scope: String
    var officialURL: URL { URL(string: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/\(slug)")! }
}
let fishingRulesAreas = [
    FishingRulesArea(id: "auckland-kermadec", name: "Auckland / Kermadec", slug: "auckland-kermadec-fishing-rules", scope: "Northland, Auckland, Waikato and Bay of Plenty coasts; east and west subareas can have different limits."),
    FishingRulesArea(id: "central", name: "Central", slug: "central-fishing-rules", scope: "North Island coast from Cape Runaway around the south to Tirua Point."),
    FishingRulesArea(id: "challenger", name: "Challenger", slug: "challenger-fishing-rules", scope: "South Island coast from Awarua Point up the west and north to Clarence Point."),
    FishingRulesArea(id: "south-east", name: "South-East", slug: "south-east-fishing-rules", scope: "South Island east coast from Clarence Point to Slope Point, except the Kaikōura special area."),
    FishingRulesArea(id: "southland", name: "Southland", slug: "southland-fishing-rules", scope: "South and west from Awarua Point to Slope Point, including Rakiura; Fiordland has separate rules."),
    FishingRulesArea(id: "kaikoura", name: "Kaikōura", slug: "kaikoura-fishing-rules", scope: "Clarence Point to Conway River mouth, extending 12 nautical miles offshore."),
    FishingRulesArea(id: "chatham-rise", name: "Chatham Rise", slug: "chatham-rise-area-recreational-fishing-rules", scope: "Chatham Islands and Chatham Rise; check the MPI map for your exact position."),
    FishingRulesArea(id: "fiordland", name: "Fiordland", slug: "fiordland-marine-area-fishing-rules", scope: "Fiordland coast from Awarua Point to Sand Hill Point, extending 12 nautical miles offshore.")
]

struct RulesView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedArea: FishingRulesArea?
    @State private var showAreas = false
    @State private var query = ""
    @State private var page: SavedRulesPage?
    @State private var loadError: String?
    @State private var loading = false
    private var quickRows: [SavedRuleQuickRow] { page?.quickRows(matching: query) ?? [] }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Fishing rules").font(.largeTitle.bold()).foregroundStyle(.primary)
                    Text("Choose the MPI area where you will fish, then look up a species.").foregroundStyle(.secondary)
                    Card {
                        Text("MPI fishing area").font(.caption).foregroundStyle(.secondary)
                        Button { showAreas = true } label: {
                            HStack { Text(selectedArea?.name ?? "Choose an area").font(.headline); Spacer(); Image(systemName: "chevron.right") }
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.borderedProminent)
                        if let selectedArea { Text(selectedArea.scope).font(.subheadline).foregroundStyle(.secondary) }
                        Button {
                            vm.useCurrentRulesArea()
                        } label: {
                            Label("Use my current location", systemImage: "location.fill")
                        }
                        .buttonStyle(.bordered)
                        if vm.hasDeviceLocation {
                            Label("Near \(vm.devicePlaceName ?? "your current location")", systemImage: "location.fill")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Text("The suggested area uses your current position and the nearest catalogued coast. MPI areas have local exceptions; confirm the exact fishing spot on the official map.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if selectedArea != nil {
                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("Search a fish or shellfish", text: $query)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        if !query.isEmpty {
                            Button { query = "" } label: { Image(systemName: "xmark.circle.fill") }
                                .accessibilityLabel("Clear search")
                        }
                    }
                    .padding(14)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                    if loading { ProgressView("Loading MPI rules…").frame(maxWidth: .infinity).padding(24) }
                    if let loadError {
                        Card {
                            Text(loadError).foregroundStyle(.secondary)
                            Button("Try again") { Task { await loadRules() } }.buttonStyle(.bordered)
                            if let selectedArea { Link("Open official MPI page", destination: selectedArea.officialURL) }
                        }
                    }
                    if let page {
                        if page.needsReview == true {
                            Card(background: CatchCheckColor.seafoam) {
                                Label("Rules changed at MPI", systemImage: "arrow.triangle.2.circlepath").font(.headline)
                                Text("This area's saved summary is being reviewed. Open the official MPI page for current limits and closures.")
                                    .font(.subheadline)
                                Link("Open current MPI rules", destination: page.sourceURL).buttonStyle(.borderedProminent)
                            }
                        } else {
                        if let summary = page.combinedFinfishSummary {
                            Card(background: CatchCheckColor.seafoam) {
                                Label("General finfish limit", systemImage: "checkmark.shield").font(.headline)
                                Text(summary).font(.subheadline)
                            }
                        }
                        Text(query.isEmpty ? "Common species" : "Matching species")
                            .font(.title2.bold())
                        if quickRows.isEmpty {
                            Card { Text(query.isEmpty ? "Search for a species to see its saved size and daily limits." : "No saved species limits match “\(query)”. Check the MPI page for other rules.").foregroundStyle(.secondary) }
                        } else {
                            ForEach(quickRows) { row in SavedRuleQuickCard(row: row) }
                        }
                        Card {
                            Label("Check local restrictions", systemImage: "exclamationmark.triangle").font(.headline)
                            Text("Closures, subareas, methods and recent changes can change what applies at a particular spot. Check the official page before keeping a catch.")
                                .font(.subheadline).foregroundStyle(.secondary)
                            Link("See full official MPI rules", destination: page.sourceURL).buttonStyle(.borderedProminent)
                            if let reviewedAt = page.reviewedAt { Text("Saved page reviewed by MPI: \(reviewedAt)").font(.caption).foregroundStyle(.secondary) }
                        }
                        }
                    }
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Rules")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .sheet(isPresented: $showAreas) {
                NavigationStack {
                    List {
                        Section {
                            Link("View MPI fishing area maps", destination: URL(string: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules")!)
                        } footer: {
                            Text("Areas follow the coast, and Kaikōura and Fiordland have special boundaries. Select where you will fish.")
                        }
                        ForEach(fishingRulesAreas) { area in
                            Button {
                                selectedArea = area
                                vm.selectRulesArea(area.id)
                                query = ""
                                showAreas = false
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(area.name).font(.headline)
                                        Text(area.scope).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if area == selectedArea { Image(systemName: "checkmark") }
                                }
                            }
                        }
                    }
                    .navigationTitle("Choose fishing area")
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { showAreas = false } } }
                }
            }
        }
        .onAppear {
            selectedArea = fishingRulesAreas.first(where: { $0.id == vm.selectedRulesAreaID })
            vm.requestLocation()
        }
        .onChange(of: vm.selectedRulesAreaID) { _, areaID in
            selectedArea = fishingRulesAreas.first(where: { $0.id == areaID })
        }
        .task(id: selectedArea?.id) { await loadRules() }
    }

    @MainActor private func loadRules() async {
        guard let selectedArea else { page = nil; loadError = nil; return }
        loading = true
        loadError = nil
        page = nil
        defer { loading = false }
        let baseURL = ((Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String) ?? "https://fishing.fishnz.space")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: "\(baseURL)/v1/rules?area=\(selectedArea.id)") else { loadError = "Rules service URL is unavailable."; return }
        do {
            var request = URLRequest(url: url)
            request.timeoutInterval = 15
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue("CatchCheckNZ-iOS/1.0", forHTTPHeaderField: "User-Agent")
            let (data, response) = try await URLSession.shared.data(for: request)
            guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0) else { throw URLError(.badServerResponse) }
            let envelope = try JSONDecoder().decode(SavedRulesEnvelope.self, from: data)
            page = envelope.rules.first
            if page == nil { loadError = "No saved rules are available for this area." }
        } catch is CancellationError { return }
        catch { loadError = "Saved rules are unavailable right now. Try again shortly." }
    }
}

private struct SavedRulesEnvelope: Decodable { let rules: [SavedRulesPage] }
private struct SavedRulesPage: Decodable {
    let areaName: String
    let sourceURL: URL
    let reviewedAt: String?
    let needsReview: Bool?
    let sections: [SavedRulesSection]
    let tables: [[[String]]]
    enum CodingKeys: String, CodingKey {
        case areaName = "area_name", sourceURL = "source_url", reviewedAt = "reviewed_at", needsReview, sections, tables
    }
}
private struct SavedRulesSection: Decodable { let heading: String; let text: String }

private struct SavedRuleQuickRow: Identifiable {
    let id: String
    let species: String
    let facts: [(label: String, value: String)]
    let note: String?
}

private extension SavedRulesPage {
    var combinedFinfishSummary: String? {
        if areaName.localizedCaseInsensitiveContains("Fiordland") {
            return "Daily limits differ between the outer Fiordland Marine Area and the inner fiords. Check the exact subarea on MPI."
        }
        let pattern = "combined daily bag limit of\\s+\\d+\\s+finfish[^.]*\\."
        for section in sections {
            if let range = section.text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) {
                return String(section.text[range]).replacingOccurrences(of: "*", with: "")
            }
        }
        return nil
    }

    func quickRows(matching query: String) -> [SavedRuleQuickRow] {
        let preferred = ["snapper", "blue cod", "kingfish", "kahawai", "rock lobster", "pāua", "pauā", "cockle"]
        let rows: [SavedRuleQuickRow] = tables.enumerated().flatMap { tableIndex, table in
            guard let headers = table.first, headers.count > 1,
                  headers[0].localizedCaseInsensitiveContains("species") else { return [SavedRuleQuickRow]() }
            let columns = headers.indices.dropFirst().compactMap { index -> (Int, String)? in
                guard let label = ruleFactLabel(headers[index]) else { return nil }
                return (index, label)
            }
            guard !columns.isEmpty else { return [SavedRuleQuickRow]() }
            let hasMultipleDailyColumns = columns.filter { $0.1.hasPrefix("Daily limit") }.count > 1
            return Array(table.dropFirst()).enumerated().compactMap { rowIndex, row in
                guard let originalSpecies = row.first?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !originalSpecies.isEmpty, !originalSpecies.hasPrefix("*"),
                      originalSpecies.count <= 180 else { return nil }
                let species = cleanRuleText(originalSpecies)
                guard !species.isEmpty else { return nil }
                var notes: [String] = []
                if row.contains(where: { $0.contains("*") }) { notes.append("Additional MPI conditions apply.") }
                if originalSpecies.localizedCaseInsensitiveContains("refer to map") {
                    notes.append("Check the exact area boundary on MPI.")
                }
                var facts: [(label: String, value: String)] = []
                if hasMultipleDailyColumns && row.count != headers.count {
                    facts = [("Area-specific rule", "See MPI")]
                    notes.append("The MPI table has merged cells; check its area-specific limit.")
                } else {
                    for (index, label) in columns where row.indices.contains(index) {
                        let rawValue = row[index].trimmingCharacters(in: .whitespacesAndNewlines)
                        if rawValue.isEmpty || ["—", "–", "-", "none"].contains(rawValue) { continue }
                        let clean = cleanRuleText(rawValue)
                        let numbers = clean.components(separatedBy: CharacterSet.decimalDigits.inverted).filter { !$0.isEmpty }
                        let value: String
                        if clean.localizedCaseInsensitiveContains("No take allowed") {
                            value = "No take"
                        } else if clean.localizedCaseInsensitiveContains("See below") {
                            value = "See MPI"
                            notes.append("Check the area-specific rule on MPI.")
                        } else if label == "Minimum tail width" {
                            value = clean
                        } else if numbers.count > 1 {
                            value = "Varies — see MPI"
                            notes.append("This value covers multiple species or subareas; check MPI.")
                        } else if label.hasPrefix("Minimum"), let range = clean.range(of: "^\\d+", options: .regularExpression) {
                            let remainder = clean[range.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
                            if !remainder.isEmpty { notes.append(remainder) }
                            value = "\(clean[range]) \(headers[index].localizedCaseInsensitiveContains("(cm)") ? "cm" : "mm")"
                        } else {
                            value = clean
                        }
                        facts.append((label, value))
                    }
                }
                guard !facts.isEmpty else { return nil }
                return SavedRuleQuickRow(id: "\(tableIndex)-\(rowIndex)", species: species,
                                         facts: facts, note: notes.isEmpty ? nil : Array(Set(notes)).sorted().joined(separator: " "))
            }
        }
        if !query.isEmpty {
            return Array(rows.filter { $0.species.localizedStandardContains(query) }.prefix(24))
        }
        return Array(rows.filter { row in preferred.contains { row.species.lowercased().hasPrefix($0) } }
            .sorted { first, second in
                let firstIndex = preferred.firstIndex { first.species.lowercased().hasPrefix($0) } ?? preferred.count
                let secondIndex = preferred.firstIndex { second.species.lowercased().hasPrefix($0) } ?? preferred.count
                return firstIndex < secondIndex
            }.prefix(8))
    }
}

private func cleanRuleText(_ text: String) -> String {
    text.replacingOccurrences(of: "\\(Fisheries Management Area\\s*(\\d+)[^)]*\\)", with: "(FMA $1)", options: .regularExpression)
        .replacingOccurrences(of: "\\[PDF[^]]*]", with: "", options: .regularExpression)
        .replacingOccurrences(of: "\\*+", with: "", options: .regularExpression)
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        .trimmingCharacters(in: CharacterSet(charactersIn: " –-:"))
}

private func ruleFactLabel(_ heading: String) -> String? {
    let text = heading.lowercased()
    if text.contains("daily limit") && text.contains("outside fiord") { return "Daily limit · outer area" }
    if text.contains("daily limit") && text.contains("the fiords") { return "Daily limit · inner fiords" }
    if text.contains("daily limit") && text.contains("auckland coromandel") { return "Daily limit · Auckland/Coromandel" }
    if text.contains("daily limit") { return "Daily limit" }
    if text.contains("fish length") { return "Minimum length" }
    if text.contains("min size") || text.contains("minimum size") { return "Minimum size" }
    if text.contains("tail width") { return "Minimum tail width" }
    return nil
}

private struct SavedRuleQuickCard: View {
    let row: SavedRuleQuickRow
    var body: some View {
        Card {
            Text(row.species).font(.headline).foregroundStyle(CatchCheckColor.navy)
            ForEach(Array(row.facts.enumerated()), id: \.offset) { item in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(item.element.label).font(.caption).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(item.element.value).font(.subheadline.bold())
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if let note = row.note { Text(note).font(.caption).foregroundStyle(.secondary) }
        }
    }
}
