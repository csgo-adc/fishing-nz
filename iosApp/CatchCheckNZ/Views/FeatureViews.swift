import SwiftUI
import MapKit
import WebKit
import UIKit

struct FishingMapView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var filter = "All"
    @State private var position: MapCameraPosition = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: -41.2, longitude: 174.8), span: MKCoordinateSpan(latitudeDelta: 13, longitudeDelta: 13)))
    private var visibleSpots: [Recommendation] { mapSpots.filter { filter == "All" || (filter == "Boat" && $0.boat) || (filter == "Land" && !$0.boat) } }
    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) { Text("Fishing map").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy); Text("New Zealand spots · tap a marker to explore").foregroundStyle(.secondary); Picker("Filter", selection: $filter) { Text("All spots").tag("All"); Text("Land fishing").tag("Land"); Text("Boat fishing").tag("Boat") }.pickerStyle(.segmented) }.padding(20)
            Map(position: $position) {
                UserAnnotation()
                ForEach(visibleSpots) { spot in if let coordinate = mapCoordinates[spot.name] { Annotation(spot.name, coordinate: coordinate) { Button { vm.selectedSpot = spot } label: { Image(systemName: spot.boat ? "ferry.fill" : "mappin.circle.fill").font(.title).foregroundStyle(CatchCheckColor.orange).background(.white, in: Circle()) } } } }
            }.mapStyle(.standard(elevation: .realistic)).overlay(alignment: .bottomTrailing) { Button { vm.requestLocation(); position = .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: vm.location.latitude, longitude: vm.location.longitude), span: MKCoordinateSpan(latitudeDelta: 0.12, longitudeDelta: 0.12))) } label: { Image(systemName: "location.fill").padding().background(.white, in: Circle()).shadow(radius: 3) }.padding(18) }
        }.background(CatchCheckColor.cream)
    }
}

struct TideForecastView: View {
    @EnvironmentObject private var vm: FishingViewModel
    private var formatter: DateFormatter { let f = DateFormatter(); f.dateFormat = "EEE, d MMM"; return f }
    var body: some View { NavigationStack { ScrollView { VStack(alignment: .leading, spacing: 16) { Text("Tide forecast").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy); Text("Live marine forecast · \(vm.selectedStation.name)").foregroundStyle(.secondary); Card { Text("Choose harbour").bold().foregroundStyle(CatchCheckColor.navy); ScrollView(.horizontal, showsIndicators: false) { HStack { ForEach(tideStations) { station in Button(station.name.replacingOccurrences(of: " Harbour", with: "")) { vm.chooseStation(station) }.buttonStyle(ChoiceButton(selected: vm.selectedStation == station)) } } } }; HStack { Text(formatter.string(from: vm.tideDate)).bold().foregroundStyle(CatchCheckColor.navy); Spacer(); Button("‹") { vm.changeTideDate(by: -1) }.disabled(Calendar.current.isDateInToday(vm.tideDate)); Button("›") { vm.changeTideDate(by: 1) } }; if let tide = vm.stationTide { TideDetails(tide: tide) } else { ProgressView("Loading live tide data…").frame(maxWidth: .infinity) }; Text("Source: Open-Meteo marine model. Confirm official LINZ predictions for safety-critical decisions.").font(.caption).foregroundStyle(.secondary) }.padding(20) }.background(CatchCheckColor.cream).navigationTitle("Tide").navigationBarTitleDisplayMode(.inline) } }
}

private struct TideDetails: View { let tide: TideState; var body: some View { VStack(alignment: .leading, spacing: 16) { Card(background: CatchCheckColor.seafoam) { Text("Current level").foregroundStyle(.secondary); Text(tide.currentLevel).font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy); Text("Next \(tide.nextEvent) · \(tide.eventTime)") }; Card { Text("Tide curve").bold().foregroundStyle(CatchCheckColor.navy); TideCurve(points: tide.points).frame(height: 150) }; Card { ForEach(tide.events) { event in HStack { Text(event.type).bold().foregroundStyle(CatchCheckColor.navy); Spacer(); Text("\(event.time) · \(event.height)") } } } } } }
private struct TideCurve: View { let points: [TidePoint]; var body: some View { GeometryReader { geometry in Path { path in guard points.count > 1 else { return }; let low = points.map(\.level).min() ?? 0; let high = points.map(\.level).max() ?? 1; let range = max(high - low, 0.1); for (index, point) in points.enumerated() { let x = geometry.size.width * CGFloat(index) / CGFloat(points.count - 1); let y = geometry.size.height * (1 - CGFloat((point.level - low) / range)); if index == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) } } }.stroke(CatchCheckColor.navy, style: StrokeStyle(lineWidth: 4, lineCap: .round)) } } }

struct TripsView: View {
    @EnvironmentObject private var vm: FishingViewModel
    private var saved: [Recommendation] { sampleRecommendations.filter { vm.savedSpotNames.contains($0.name) } }
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
