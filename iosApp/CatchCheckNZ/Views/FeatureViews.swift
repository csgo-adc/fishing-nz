import SwiftUI
import MapKit
import WebKit
import UIKit

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
                        TideDetails(tide: tide, day: vm.tideDate)
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
    @State private var selectedIndex: Int?

    private var isToday: Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar.isDateInToday(day)
    }
    private var selectedPoint: TidePoint? {
        guard let selectedIndex, tide.points.indices.contains(selectedIndex) else { return nil }
        return tide.points[selectedIndex]
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
                Text("Tide height through the day")
                    .bold()
                    .foregroundStyle(CatchCheckColor.navy)
                HStack {
                    Text(selectedPoint.map { "\($0.time) · interpolated" } ?? "Slide across the curve to read a height")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    if let selectedPoint {
                        Text(String(format: "%.2f m", selectedPoint.level))
                            .font(.title3.bold())
                            .foregroundStyle(CatchCheckColor.navy)
                    }
                }
                TideCurve(points: tide.points, selectedIndex: $selectedIndex)
                    .frame(height: 210)
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
    }
}

private struct TideCurve: View {
    let points: [TidePoint]
    @Binding var selectedIndex: Int?

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
                        path.addLine(to: chartPoint(index, width: width, height: height, floor: floor, range: range))
                    }
                    path.addLine(to: CGPoint(x: width, y: height))
                    path.closeSubpath()
                }
                .fill(LinearGradient(colors: [CatchCheckColor.seafoam, CatchCheckColor.seafoam.opacity(0.15)], startPoint: .top, endPoint: .bottom))
                Path { path in
                    guard points.count > 1 else { return }
                    for index in points.indices {
                        let position = chartPoint(index, width: width, height: height, floor: floor, range: range)
                        if index == 0 { path.move(to: position) }
                        else { path.addLine(to: position) }
                    }
                }
                .stroke(CatchCheckColor.navy, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                if let selectedIndex, points.indices.contains(selectedIndex) {
                    let position = chartPoint(selectedIndex, width: width, height: height, floor: floor, range: range)
                    Path { path in
                        path.move(to: CGPoint(x: position.x, y: 0))
                        path.addLine(to: CGPoint(x: position.x, y: height))
                    }
                    .stroke(CatchCheckColor.navy.opacity(0.45), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    Circle()
                        .fill(CatchCheckColor.navy)
                        .frame(width: 13, height: 13)
                        .position(position)
                }
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                guard points.count > 1, width > 0 else { return }
                let fraction = min(max(value.location.x / width, 0), 1)
                selectedIndex = Int((fraction * CGFloat(points.count - 1)).rounded())
            })
        }
        .accessibilityLabel("Tide height curve. Slide to hear interpolated time and height.")
        .accessibilityValue(selectedIndex.flatMap { points.indices.contains($0) ? "\(points[$0].time), \(String(format: "%.2f", points[$0].level)) metres" : nil } ?? "No time selected")
    }

    private func chartPoint(_ index: Int, width: CGFloat, height: CGFloat, floor: Double, range: Double) -> CGPoint {
        let x = width * CGFloat(index) / CGFloat(max(points.count - 1, 1))
        let y = height * (1 - CGFloat((points[index].level - floor) / range))
        return CGPoint(x: x, y: y)
    }
}

struct TripsView: View {
    @EnvironmentObject private var vm: FishingViewModel
    private var saved: [Recommendation] { vm.savedRecommendations.values.sorted { $0.rating > $1.rating } }
    var body: some View { NavigationStack { ScrollView { VStack(alignment: .leading, spacing: 16) { Text("Your trips").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy); Text("Saved spots and active plans").foregroundStyle(.secondary); if let active = vm.activeTrip { Card(background: CatchCheckColor.seafoam) { Text("Active trip").bold().foregroundStyle(CatchCheckColor.navy); Text(active.name).font(.title2).foregroundStyle(CatchCheckColor.navy); Button("End trip") { vm.activeTrip = nil }.buttonStyle(.bordered) } }; Text("Saved spots").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy); if saved.isEmpty { Text("Save a recommended spot to see it here.").foregroundStyle(.secondary) } else { ForEach(saved) { spot in RecommendationCard(spot: spot) { vm.selectedSpot = spot } } } }.padding(20) }.background(CatchCheckColor.cream).navigationTitle("Trips").navigationBarTitleDisplayMode(.inline) } }
}

private struct FishingRulesArea: Identifiable, Hashable { let name: String; let url: URL; var id: String { name } }
private let fishingRulesAreas = [
    FishingRulesArea(name: "Auckland / Kermadec", url: URL(string: "https://fishnz.space/?area=auckland-kermadec")!),
    FishingRulesArea(name: "Central", url: URL(string: "https://fishnz.space/?area=central")!),
    FishingRulesArea(name: "Challenger", url: URL(string: "https://fishnz.space/?area=challenger")!),
    FishingRulesArea(name: "South-East", url: URL(string: "https://fishnz.space/?area=south-east")!),
    FishingRulesArea(name: "Southland", url: URL(string: "https://fishnz.space/?area=southland")!),
    FishingRulesArea(name: "Kaikōura", url: URL(string: "https://fishnz.space/?area=kaikoura")!),
    FishingRulesArea(name: "Chatham Rise", url: URL(string: "https://fishnz.space/?area=chatham-rise")!),
    FishingRulesArea(name: "Fiordland", url: URL(string: "https://fishnz.space/?area=fiordland")!)
]

struct RulesView: View {
    @State private var selectedArea = fishingRulesAreas[0]
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Fishing rules").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text("Choose an area to view rules saved from Fisheries New Zealand (MPI). The rules include legal sizes, catch limits, closures and gear restrictions.").foregroundStyle(.secondary)
                    Picker("Fishing area", selection: $selectedArea) { ForEach(fishingRulesAreas) { area in Text(area.name).tag(area) } }.pickerStyle(.menu)
                    OfficialRulesPage(url: selectedArea.url).frame(height: 720).clipShape(RoundedRectangle(cornerRadius: 12))
                    Text("Official source: mpi.govt.nz · MPI says to check the rules each time you fish.").font(.caption).foregroundStyle(.secondary)
                }.padding(20)
            }.background(CatchCheckColor.cream).navigationTitle("Rules").navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct OfficialRulesPage: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView()
        view.load(URLRequest(url: url))
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {
        guard view.url != url else { return }
        view.load(URLRequest(url: url))
    }
}
private struct RuleCard: View { let title: String; let text: String; var body: some View { Card { Text(title).bold().foregroundStyle(CatchCheckColor.navy); Text(text).foregroundStyle(.secondary) } } }
