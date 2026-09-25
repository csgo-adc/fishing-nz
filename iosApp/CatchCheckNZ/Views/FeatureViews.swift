import SwiftUI
import MapKit
import UIKit
import EventKit
import EventKitUI

struct FishingMapView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var filter = "All"
    @State private var position: MapCameraPosition = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: -41.2, longitude: 174.8), span: MKCoordinateSpan(latitudeDelta: 13, longitudeDelta: 13)))
    private var visibleSpots: [FishingSpot] { fishingSpots.filter { filter == "All" || (filter == "Boat" && $0.boat) || (filter == "Land" && !$0.boat) } }
    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) { Text("Fishing map").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy); Text("New Zealand spots · tap a marker to explore").foregroundStyle(.secondary); Picker("Filter", selection: $filter) { Text("All spots").tag("All"); Text("Land fishing").tag("Land"); Text("Boat fishing").tag("Boat") }.pickerStyle(.segmented) }.padding(20)
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
            }.mapStyle(.standard(elevation: .realistic)).overlay(alignment: .bottomTrailing) { Button { vm.requestLocation(); position = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: vm.location.latitude, longitude: vm.location.longitude), span: MKCoordinateSpan(latitudeDelta: 0.12, longitudeDelta: 0.12))) } label: { Image(systemName: "location.fill").padding().background(.white, in: Circle()).shadow(radius: 3) }.padding(18) }
        }.background(CatchCheckColor.cream)
    }
}

struct TideForecastView: View {
    @EnvironmentObject private var vm: FishingViewModel
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
                                Text(vm.selectedStation.name)
                                    .font(.title2.bold())
                                    .foregroundStyle(CatchCheckColor.navy)
                            }
                            Spacer()
                            Button {
                                vm.useCurrentLocationForTides()
                            } label: {
                                Label("Nearby", systemImage: "location.fill")
                            }
                            .buttonStyle(.bordered)
                            .accessibilityLabel("Use the tide station nearest my current location")
                        }
                        Text(vm.tideLocationSummary)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(tideStations) { station in
                                    Button(station.name) { vm.chooseStation(station) }
                                        .buttonStyle(ChoiceButton(selected: vm.selectedStation == station))
                                }
                            }
                        }
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

private struct FishingRulesArea: Identifiable, Hashable {
    let id: String
    let name: String
    let slug: String
    var officialURL: URL { URL(string: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules/\(slug)")! }
}
private let fishingRulesAreas = [
    FishingRulesArea(id: "auckland-kermadec", name: "Auckland / Kermadec", slug: "auckland-kermadec-fishing-rules"),
    FishingRulesArea(id: "central", name: "Central", slug: "central-fishing-rules"),
    FishingRulesArea(id: "challenger", name: "Challenger", slug: "challenger-fishing-rules"),
    FishingRulesArea(id: "south-east", name: "South-East", slug: "south-east-fishing-rules"),
    FishingRulesArea(id: "southland", name: "Southland", slug: "southland-fishing-rules"),
    FishingRulesArea(id: "kaikoura", name: "Kaikōura", slug: "kaikoura-fishing-rules"),
    FishingRulesArea(id: "chatham-rise", name: "Chatham Rise", slug: "chatham-rise-area-recreational-fishing-rules"),
    FishingRulesArea(id: "fiordland", name: "Fiordland", slug: "fiordland-marine-area-fishing-rules")
]

struct RulesView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedArea = fishingRulesAreas[0]
    @State private var manuallyChosen = false
    @State private var showAreas = false
    @State private var query = ""
    @State private var page: SavedRulesPage?
    @State private var loadError: String?
    @State private var loading = false
    @State private var locationRequested = false

    private var matchingSections: [SavedRulesSection] {
        guard let page else { return [] }
        guard !query.isEmpty else { return page.sections }
        return page.sections.filter { $0.heading.localizedStandardContains(query) || $0.text.localizedStandardContains(query) }
    }
    private var matchingTables: [[[String]]] {
        guard let page else { return [] }
        guard !query.isEmpty else { return page.tables }
        return page.tables.filter { table in table.contains { row in row.contains { $0.localizedStandardContains(query) } } }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Fishing rules").font(.largeTitle.bold()).foregroundStyle(.primary)
                    Text("Sizes, limits and restrictions from Fisheries New Zealand").foregroundStyle(.secondary)
                    Card {
                        Text("Fishing area").font(.caption).foregroundStyle(.secondary)
                        Button { showAreas = true } label: {
                            HStack { Text(selectedArea.name).font(.headline); Spacer(); Image(systemName: "chevron.down") }
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }.buttonStyle(.borderedProminent)
                        Button { manuallyChosen = false; vm.requestLocation() } label: {
                            Label("Use my location", systemImage: "location.fill")
                        }.buttonStyle(.bordered)
                        Text(vm.hasDeviceLocation && !manuallyChosen
                             ? "Area estimated from your current location. Confirm the exact fishing spot."
                             : "Choose the area where you plan to fish."
                        ).font(.caption).foregroundStyle(.secondary)
                    }
                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("Search species or rules", text: $query)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        if !query.isEmpty {
                            Button { query = "" } label: { Image(systemName: "xmark.circle.fill") }
                                .accessibilityLabel("Clear search")
                        }
                    }
                    .padding(14)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                    if loading { ProgressView("Loading saved rules…").frame(maxWidth: .infinity).padding(24) }
                    if let loadError {
                        Card {
                            Text(loadError).foregroundStyle(.secondary)
                            Button("Try again") { Task { await loadRules() } }.buttonStyle(.bordered)
                            Link("Open official MPI page", destination: selectedArea.officialURL)
                        }
                    }
                    if let page {
                        Text("\(matchingSections.count + matchingTables.count) \(query.isEmpty ? "rule topics" : "matching topics")")
                            .font(.headline)
                        if let reviewedAt = page.reviewedAt {
                            Text("MPI last reviewed: \(reviewedAt)").font(.caption).foregroundStyle(.secondary)
                        }
                        ForEach(Array(matchingSections.enumerated()), id: \.offset) { _, section in
                            SavedRuleSectionCard(section: section, searching: !query.isEmpty)
                        }
                        if !matchingTables.isEmpty { Text("Species and limits").font(.title2.bold()) }
                        ForEach(Array(matchingTables.enumerated()), id: \.offset) { _, table in
                            SavedRuleTableCard(table: table, query: query)
                        }
                        if !query.isEmpty && matchingSections.isEmpty && matchingTables.isEmpty {
                            Text("No saved rules match “\(query)” in \(page.areaName). Try a species or another term.")
                                .foregroundStyle(.secondary)
                        }
                        Link("Open official MPI rules", destination: page.sourceURL).buttonStyle(.bordered)
                    }
                    Text("Confirm the exact location and latest official rules each time you fish.")
                        .font(.caption).foregroundStyle(.secondary)
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Rules")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .sheet(isPresented: $showAreas) {
                NavigationStack {
                    List(fishingRulesAreas) { area in
                        Button {
                            selectedArea = area
                            manuallyChosen = true
                            showAreas = false
                        } label: {
                            HStack { Text(area.name); Spacer(); if area == selectedArea { Image(systemName: "checkmark") } }
                        }
                    }
                    .navigationTitle("Choose fishing area")
                    .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { showAreas = false } } }
                }
            }
        }
        .onAppear {
            if vm.hasDeviceLocation { selectArea(for: vm.location) }
            else if !locationRequested { locationRequested = true; vm.requestLocation() }
        }
        .onChange(of: vm.location) { _, point in if vm.hasDeviceLocation && !manuallyChosen { selectArea(for: point) } }
        .onChange(of: vm.hasDeviceLocation) { _, available in if available && !manuallyChosen { selectArea(for: vm.location) } }
        .task(id: selectedArea.id) { await loadRules() }
    }

    private func selectArea(for point: GeoPoint) {
        let id: String
        switch (point.latitude, point.longitude) {
        case let (latitude, longitude) where longitude < -175 && latitude < -40 && latitude > -49: id = "chatham-rise"
        case let (latitude, longitude) where latitude <= -44.5 && (166...168.8).contains(longitude): id = "fiordland"
        case let (latitude, _) where latitude <= -46.3: id = "southland"
        case let (latitude, longitude) where latitude < -42.4 && latitude > -44 && (172.2...174.4).contains(longitude): id = "kaikoura"
        case let (latitude, longitude) where latitude <= -40 && latitude >= -46.3 && longitude < 171: id = "challenger"
        case let (latitude, longitude) where latitude <= -42.5 && latitude > -46.3 && longitude >= 171: id = "south-east"
        case let (latitude, _) where latitude > -37.7: id = "auckland-kermadec"
        case let (latitude, _) where latitude > -41.6: id = "central"
        default: id = "challenger"
        }
        if let area = fishingRulesAreas.first(where: { $0.id == id }) { selectedArea = area }
    }

    @MainActor private func loadRules() async {
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
    let sections: [SavedRulesSection]
    let tables: [[[String]]]
    enum CodingKeys: String, CodingKey {
        case areaName = "area_name", sourceURL = "source_url", reviewedAt = "reviewed_at", sections, tables
    }
}
private struct SavedRulesSection: Decodable { let heading: String; let text: String }

private struct SavedRuleSectionCard: View {
    let section: SavedRulesSection
    let searching: Bool
    @State private var expanded = false
    var body: some View {
        Card {
            DisclosureGroup(isExpanded: $expanded) {
                Text(section.text).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 6)
            } label: {
                Text(section.heading).font(.headline).foregroundStyle(.primary)
            }
        }
        .onAppear { if searching { expanded = true } }
        .onChange(of: searching) { _, active in if active { expanded = true } }
    }
}

private struct SavedRuleTableCard: View {
    let table: [[String]]
    let query: String
    @State private var expanded = false
    private var headers: [String] { table.first ?? [] }
    private var rows: [[String]] {
        let all = Array(table.dropFirst())
        guard !query.isEmpty && !headers.contains(where: { $0.localizedStandardContains(query) }) else { return all }
        return all.filter { $0.contains { $0.localizedStandardContains(query) } }
    }
    var body: some View {
        Card {
            Text(headers.first ?? "Rules table").font(.headline)
            ForEach(Array((expanded || !query.isEmpty ? rows : Array(rows.prefix(6))).enumerated()), id: \.offset) { _, row in
                Divider()
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.first ?? "").font(.subheadline.bold())
                    ForEach(Array(row.dropFirst().enumerated()), id: \.offset) { index, value in
                        if !value.isEmpty && value != "—" {
                            Text("\(headers.indices.contains(index + 1) ? headers[index + 1] : "Detail"): \(value)")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }.frame(maxWidth: .infinity, alignment: .leading)
            }
            if rows.count > 6 && query.isEmpty {
                Button(expanded ? "Show fewer" : "Show all \(rows.count) entries") { expanded.toggle() }
                    .buttonStyle(.bordered)
            }
        }
    }
}
