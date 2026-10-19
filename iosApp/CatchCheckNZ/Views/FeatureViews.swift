import SwiftUI
import MapKit
import UIKit
import EventKit
import EventKitUI

private enum FishingMapLayer: String, CaseIterable {
    case map = "Map"
    case satellite = "Satellite"
    case hybrid = "Hybrid"
    case linzAerial = "LINZ aerial"
    case linzTopo = "LINZ topo"
    case linzTopoGridded = "LINZ topo with grid"

    var tileURLTemplate: String? {
        let tileSet: String
        switch self {
        case .map, .satellite, .hybrid: return nil
        case .linzAerial: tileSet = "aerial"
        case .linzTopo: tileSet = "topo-raster"
        case .linzTopoGridded: tileSet = "topo-raster-gridded"
        }
        // LINZ permits this public developer key to be bundled in client applications.
        let key = "d01m0gkkx31k5jbq695p35xha3w"
        return "https://basemaps.linz.govt.nz/v1/tiles/\(tileSet)/WebMercatorQuad/{z}/{x}/{y}.jpg?api=\(key)"
    }
}

private enum MapCameraTarget {
    case region(MKCoordinateRegion)
    case camera(MKMapCamera)
    /// Multiplies the camera distance: 0.5 zooms in one step and 2 zooms out one step.
    case zoom(factor: Double)
}

private struct MapCameraCommand {
    let sequence: Int
    let target: MapCameraTarget
}

struct FishingMapView: View {
    private static let initialRegion = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: -41.2, longitude: 174.8),
        span: MKCoordinateSpan(latitudeDelta: 13, longitudeDelta: 13)
    )

    @EnvironmentObject private var vm: FishingViewModel
    @State private var filter = "All"
    @State private var layer: FishingMapLayer = .map
    @State private var currentCamera: MKMapCamera?
    @State private var cameraCommand = MapCameraCommand(sequence: 0, target: .region(initialRegion))
    @State private var needsFirstLocationCenter = true
    @State private var recenterOnLocationUpdate = false
    @State private var selectedPlace: ConditionPlace?
    @State private var conditionsPlace: ConditionPlace?
    @State private var showPlaceSearch = false
    @State private var showSpotList = false
    /// Set after "Show on map" moves the camera, so returning to the tab does not jump back to the current location.
    @State private var keepCameraOnAppear = false
    @AppStorage("mapShowsProtectedAreas") private var showProtection = true
    private var visibleSpots: [FishingSpot] { fishingSpots.filter { filter == "All" || (filter == "Boat" && $0.boat) || (filter == "Land" && !$0.boat) } }
    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Explore conditions").font(.title.bold()).foregroundStyle(CatchCheckColor.navy)
                    Spacer()
                    Button { showPlaceSearch = true } label: { Image(systemName: "magnifyingglass").font(.title3) }.accessibilityLabel("Search places")
                    Button { showSpotList = true } label: { Label("List", systemImage: "list.bullet") }
                        .accessibilityLabel("List fishing areas")
                }
                Text("Search a place or tap anywhere on the map").font(.subheadline).foregroundStyle(.secondary)
                Picker("Filter", selection: $filter) { Text("All spots").tag("All"); Text("Land fishing").tag("Land"); Text("Boat fishing").tag("Boat") }.pickerStyle(.segmented)
            }.padding(20)
            FishingMapCanvas(layer: layer, showProtection: showProtection, spots: visibleSpots, selectedPlace: selectedPlace, cameraCommand: cameraCommand,
                             currentCamera: $currentCamera, onSpotSelected: { selectedPlace = ConditionPlace($0) },
                             onPointSelected: { selectedPlace = ConditionPlace(name: "Dropped pin", point: $0, region: "Selected on map", boat: filter == "Boat") })
            .overlay(alignment: .topLeading) {
                Text("\(visibleSpots.count) spots")
                    .font(.caption.weight(.semibold)).foregroundStyle(CatchCheckColor.navy)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(CatchCheckColor.surface.opacity(0.94), in: RoundedRectangle(cornerRadius: 8))
                    .padding(12)
            }
            .overlay(alignment: .topTrailing) {
                VStack(spacing: 12) {
                    Menu {
                        ForEach(FishingMapLayer.allCases, id: \.self) { choice in
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
                        Divider()
                        // Red: marine reserves and High Protection Areas (no fishing). Amber: Seafloor Protection Areas.
                        Button { showProtection.toggle() } label: {
                            if showProtection { Label("Marine protected areas", systemImage: "checkmark") } else { Text("Marine protected areas") }
                        }
                    } label: {
                        mapControlIcon("square.3.layers.3d")
                    }
                    .accessibilityLabel("Map layers")

                    Button {
                        guard let camera = currentCamera else { return }
                        let northFacingCamera = camera.copy() as! MKMapCamera
                        northFacingCamera.heading = 0
                        sendCamera(.camera(northFacingCamera))
                    } label: {
                        mapControlIcon("location.north.line.fill")
                            .rotationEffect(.degrees(-(currentCamera?.heading ?? 0)))
                    }
                    .accessibilityLabel("Reset map to north")
                }
                .padding(18)
            }
            .overlay(alignment: .bottomTrailing) {
                VStack(spacing: 12) {
                    mapZoomControl
                    Button {
                        recenterOnLocationUpdate = true
                        if vm.hasDeviceLocation { centerOnCurrentLocation(); recenterOnLocationUpdate = false }
                        vm.requestLocation()
                    } label: {
                        mapControlIcon("location.fill")
                    }
                    .accessibilityLabel("Center map on my location")
                }
                .padding(18)
            }
            .overlay(alignment: .bottomLeading) {
                if layer.tileURLTemplate != nil || showProtection {
                    HStack(spacing: 4) {
                        if layer.tileURLTemplate != nil {
                            Link("LINZ CC BY 4.0", destination: URL(string: "https://www.linz.govt.nz/copyright")!)
                            Text("·")
                            Link(layer == .linzAerial ? "Imagery Basemap contributors" : "Topographic Basemap contributors",
                                 destination: URL(string: "https://www.linz.govt.nz/products-services/data/licensing-and-using-data/attributing-linz-basemaps-data")!)
                        }
                        if showProtection {
                            if layer.tileURLTemplate != nil { Text("·") }
                            Link("Protected areas © DOC", destination: MarineProtection.docURL)
                        }
                    }
                    .font(.caption2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
                    .padding(.leading, 8)
                    .padding(.bottom, 36)
                }
            }
            selectedPlaceCard
        }.background(CatchCheckColor.cream)
            .onAppear {
                if vm.mapFocus != nil { showMapFocus() }
                else if keepCameraOnAppear { keepCameraOnAppear = false }
                else if vm.hasDeviceLocation { centerOnCurrentLocation(); needsFirstLocationCenter = false }
                else { needsFirstLocationCenter = true }
                vm.requestLocation()
            }
            .onChange(of: vm.mapFocus?.id) { _, id in if id != nil { showMapFocus() } }
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
            .onChange(of: filter) { _, _ in selectedPlace = nil }
            .fullScreenCover(item: $conditionsPlace) { PlaceConditionsView(place: $0) }
            .sheet(isPresented: $showSpotList) {
                FishingSpotList(spots: visibleSpots) { spot in
                    selectedPlace = ConditionPlace(spot)
                    showSpotList = false
                    sendCamera(.region(MKCoordinateRegion(center: .init(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude),
                                                          span: .init(latitudeDelta: 0.12, longitudeDelta: 0.12))))
                }
            }
            .sheet(isPresented: $showPlaceSearch) {
                MapPlaceSearchView { place in
                    selectedPlace = place
                    sendCamera(.region(MKCoordinateRegion(center: .init(latitude: place.point.latitude, longitude: place.point.longitude), span: .init(latitudeDelta: 0.12, longitudeDelta: 0.12))))
                }
            }
    }

    @ViewBuilder private var selectedPlaceCard: some View {
        if let place = selectedPlace {
            let spot = FishingSpot(name: place.name, area: place.region, coordinate: place.point, boat: place.boat)
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(spot.name).font(.headline).foregroundStyle(CatchCheckColor.navy)
                        Text(place.region)
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button { selectedPlace = nil } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3).foregroundStyle(.secondary)
                    }
                    .accessibilityLabel("Close selected spot")
                }
                MarineProtectionNoticeView(point: place.point, compact: true)
                Button { conditionsPlace = place } label: {
                    Label("Check conditions", systemImage: "cloud.sun.fill").frame(maxWidth: .infinity)
                }.buttonStyle(.borderedProminent)
                Button {
                    openSpotInMaps(spot)
                } label: {
                    Label(spot.boat ? "View position in Maps" : "Directions in Maps", systemImage: "map")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                Button("Find a fishing window nearby") {
                    vm.isBoatFishing = spot.boat
                    vm.selectSearchOrigin(SearchOrigin(name: spot.name, point: spot.coordinate))
                    vm.showRecommendations()
                }
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
            }
            .padding(16)
            .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 16))
            .padding(.horizontal, 12)
            .padding(.bottom, 12)
        }
    }


    /// A fishing window sent here with "Show on map": centre on its area and open its card.
    private func showMapFocus() {
        guard let place = vm.mapFocus else { return }
        vm.mapFocus = nil
        selectedPlace = place
        conditionsPlace = nil; showSpotList = false; showPlaceSearch = false
        needsFirstLocationCenter = false; recenterOnLocationUpdate = false; keepCameraOnAppear = true
        sendCamera(.region(MKCoordinateRegion(center: .init(latitude: place.point.latitude, longitude: place.point.longitude),
                                              span: .init(latitudeDelta: 0.12, longitudeDelta: 0.12))))
    }

    private func centerOnCurrentLocation() {
        sendCamera(.region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: vm.location.latitude, longitude: vm.location.longitude),
                                               span: MKCoordinateSpan(latitudeDelta: 0.15, longitudeDelta: 0.15))))
    }

    private func sendCamera(_ target: MapCameraTarget) {
        cameraCommand = MapCameraCommand(sequence: cameraCommand.sequence + 1, target: target)
    }

    private func openSpotInMaps(_ spot: FishingSpot) {
        let coordinate = CLLocationCoordinate2D(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude)
        let destination = MKMapItem(placemark: MKPlacemark(coordinate: coordinate))
        destination.name = spot.name
        if spot.boat {
            destination.openInMaps()
        } else {
            destination.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDriving])
        }
    }

    private func mapControlIcon(_ symbol: String) -> some View {
        Image(systemName: symbol)
            .font(.system(size: 20, weight: .semibold))
            .foregroundStyle(CatchCheckColor.navy)
            .frame(width: 48, height: 48)
            .background(CatchCheckColor.surface, in: Circle())
            .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
    }

    /// Zoom in and out with one thumb, without pinching.
    private var mapZoomControl: some View {
        VStack(spacing: 0) {
            Button { sendCamera(.zoom(factor: 0.5)) } label: {
                Image(systemName: "plus").frame(width: 48, height: 48).contentShape(Rectangle())
            }
            .accessibilityLabel("Zoom in")
            Divider().frame(width: 26)
            Button { sendCamera(.zoom(factor: 2)) } label: {
                Image(systemName: "minus").frame(width: 48, height: 48).contentShape(Rectangle())
            }
            .accessibilityLabel("Zoom out")
        }
        .font(.system(size: 20, weight: .semibold))
        .foregroundStyle(CatchCheckColor.navy)
        .background(CatchCheckColor.surface, in: Capsule())
        .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
    }
}

private final class FishingSpotMapAnnotation: NSObject, MKAnnotation {
    let spot: FishingSpot
    let coordinate: CLLocationCoordinate2D
    var title: String? { spot.name }

    init(spot: FishingSpot) {
        self.spot = spot
        coordinate = CLLocationCoordinate2D(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude)
    }
}

private struct FishingMapCanvas: UIViewRepresentable {
    let layer: FishingMapLayer
    let showProtection: Bool
    let spots: [FishingSpot]
    let selectedPlace: ConditionPlace?
    let cameraCommand: MapCameraCommand
    @Binding var currentCamera: MKMapCamera?
    let onSpotSelected: (FishingSpot) -> Void
    let onPointSelected: (GeoPoint) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.showsUserLocation = true
        map.showsCompass = false
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapMap(_:)))
        tap.cancelsTouchesInView = false; tap.delegate = context.coordinator
        map.addGestureRecognizer(tap)
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self

        let layerChanged = coordinator.layer != layer
        if layerChanged {
            map.removeOverlays(map.overlays.filter { $0 is MKTileOverlay })
            coordinator.layer = layer
            switch layer {
            case .map: map.mapType = .standard
            case .satellite: map.mapType = .satellite
            case .hybrid: map.mapType = .hybrid
            case .linzAerial, .linzTopo, .linzTopoGridded:
                map.mapType = .standard
                if let template = layer.tileURLTemplate {
                    let tiles = MKTileOverlay(urlTemplate: template)
                    tiles.canReplaceMapContent = true
                    if layer == .linzAerial { tiles.maximumZ = 22 }
                    map.addOverlay(tiles, level: .aboveLabels)
                }
            }
        }
        // Protected areas go above any LINZ tiles, so they are added again after a layer change.
        if layerChanged || coordinator.protectionShown != showProtection {
            coordinator.protectionShown = showProtection
            map.removeOverlays(map.overlays.filter { $0 is ProtectedAreaPolygon })
            if showProtection { map.addOverlays(MarineProtectionOverlays.all, level: .aboveLabels) }
        }

        let displayed = map.annotations.compactMap { $0 as? FishingSpotMapAnnotation }
        let wantedIDs = Set(spots.map(\.id))
        let displayedIDs = Set(displayed.map { $0.spot.id })
        if wantedIDs != displayedIDs {
            map.removeAnnotations(displayed)
            map.addAnnotations(spots.map(FishingSpotMapAnnotation.init))
        }

        if coordinator.selectedID != selectedPlace?.id {
            if let pin = coordinator.selectedPin { map.removeAnnotation(pin) }
            coordinator.selectedID = selectedPlace?.id; coordinator.selectedPin = nil
            if let place = selectedPlace {
                let pin = MKPointAnnotation(); pin.title = place.name
                pin.coordinate = .init(latitude: place.point.latitude, longitude: place.point.longitude)
                coordinator.selectedPin = pin; map.addAnnotation(pin)
            }
        }
        if coordinator.lastCameraCommand != cameraCommand.sequence {
            coordinator.lastCameraCommand = cameraCommand.sequence
            switch cameraCommand.target {
            case .region(let region): map.setRegion(region, animated: cameraCommand.sequence != 0)
            case .camera(let camera): map.setCamera(camera, animated: true)
            case .zoom(let factor): coordinator.zoom(map, by: factor)
            }
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate, UIGestureRecognizerDelegate {
        var parent: FishingMapCanvas
        var layer: FishingMapLayer?
        var protectionShown: Bool?
        var lastCameraCommand = -1
        var selectedID: String?
        var selectedPin: MKPointAnnotation?
        /// Where the last button zoom is heading. A press made while the map is still animating builds on this instead of
        /// the half-finished camera, so quick presses zoom several steps. Any finished camera change clears it.
        private var zoomTarget: Double?
        func zoom(_ map: MKMapView, by factor: Double) {
            let camera = map.camera.copy() as! MKMapCamera
            let target = min(max((zoomTarget ?? camera.centerCoordinateDistance) * factor, 20), 30_000_000)
            zoomTarget = target
            camera.centerCoordinateDistance = target
            map.setCamera(camera, animated: true)
        }
        @objc func tapMap(_ gesture: UITapGestureRecognizer) {
            guard gesture.state == .ended, let map = gesture.view as? MKMapView else { return }
            let position = map.convert(gesture.location(in: map), toCoordinateFrom: map)
            parent.onPointSelected(.init(latitude: position.latitude, longitude: position.longitude))
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            var view = touch.view
            while let current = view {
                if current is MKAnnotationView || current is UIControl { return false }
                view = current.superview
            }
            return true
        }
        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }

        init(parent: FishingMapCanvas) { self.parent = parent }

        func mapView(_ mapView: MKMapView, rendererFor overlay: any MKOverlay) -> MKOverlayRenderer {
            if let tiles = overlay as? MKTileOverlay { return MKTileOverlayRenderer(tileOverlay: tiles) }
            if let area = overlay as? ProtectedAreaPolygon { return MarineProtectionOverlays.renderer(for: area) }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: any MKAnnotation) -> MKAnnotationView? {
            if let pin = annotation as? MKPointAnnotation, pin === selectedPin {
                let marker = mapView.dequeueReusableAnnotationView(withIdentifier: "SelectedPlace") as? MKMarkerAnnotationView ?? MKMarkerAnnotationView(annotation: pin, reuseIdentifier: "SelectedPlace")
                marker.annotation = pin; marker.markerTintColor = UIColor(CatchCheckColor.accent); marker.glyphImage = UIImage(systemName: "mappin"); marker.canShowCallout = false
                marker.displayPriority = .required
                return marker
            }
            // Spots close together on screen merge into one marker showing how many there are, as on Android.
            if let cluster = annotation as? MKClusterAnnotation {
                let marker = mapView.dequeueReusableAnnotationView(withIdentifier: "SpotCluster") as? MKMarkerAnnotationView
                    ?? MKMarkerAnnotationView(annotation: cluster, reuseIdentifier: "SpotCluster")
                marker.annotation = cluster
                marker.markerTintColor = UIColor(red: 0x12 / 255, green: 0x3B / 255, blue: 0x43 / 255, alpha: 1)
                marker.glyphImage = nil
                marker.glyphText = "\(cluster.memberAnnotations.count)"
                marker.titleVisibility = .hidden
                marker.subtitleVisibility = .hidden
                marker.canShowCallout = false
                marker.accessibilityLabel = "\(cluster.memberAnnotations.count) fishing spots"
                return marker
            }
            guard let spotAnnotation = annotation as? FishingSpotMapAnnotation else { return nil }
            let reuseID = "FishingSpot"
            let marker = mapView.dequeueReusableAnnotationView(withIdentifier: reuseID) as? MKMarkerAnnotationView
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: reuseID)
            marker.annotation = annotation
            marker.markerTintColor = UIColor(CatchCheckColor.orange)
            marker.glyphImage = UIImage(systemName: spotAnnotation.spot.boat ? "ferry.fill" : "mappin.circle.fill")
            marker.canShowCallout = false
            marker.clusteringIdentifier = "fishing-spots"
            return marker
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let cluster = view.annotation as? MKClusterAnnotation {
                mapView.deselectAnnotation(cluster, animated: false)
                mapView.showAnnotations(cluster.memberAnnotations, animated: true)
                return
            }
            guard let spotAnnotation = view.annotation as? FishingSpotMapAnnotation else { return }
            parent.onSpotSelected(spotAnnotation.spot)
            mapView.deselectAnnotation(spotAnnotation, animated: false)
        }

        func mapView(_ mapView: MKMapView, regionDidChangeAnimated animated: Bool) {
            zoomTarget = nil
            parent.currentCamera = mapView.camera.copy() as? MKMapCamera
        }
    }
}

/// Every fishing area for the current filter, for browsing without the map.
private struct FishingSpotList: View {
    @Environment(\.dismiss) private var dismiss
    let spots: [FishingSpot]
    let choose: (FishingSpot) -> Void
    @State private var query = ""

    private var matching: [FishingSpot] {
        let sorted = spots.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        guard !query.isEmpty else { return sorted }
        return sorted.filter { $0.name.localizedStandardContains(query) || $0.area.localizedStandardContains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(matching, id: \.id) { spot in
                        Button { choose(spot) } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(spot.name).foregroundStyle(CatchCheckColor.navy)
                                Text("\(spot.area) · \(spot.boat ? "Boat" : "Land") fishing").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                } footer: {
                    Text("Tap an area to see it on the map.")
                }
            }
            .searchable(text: $query, prompt: "Search fishing areas")
            .navigationTitle("Fishing areas")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
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
                    Text("Tide predictions: Toitū Te Whenua LINZ. Heights above Chart Datum; the curve between highs and lows is estimated.")
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

    private var isToday: Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Pacific/Auckland")!
        return calendar.isDateInToday(day)
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
            MoonCard(day: day)
            Card {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Tide height through the day").bold().foregroundStyle(.primary)
                    Text("Tap or drag the curve to see a time and height.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 35).onEnded { value in
                    guard abs(value.translation.width) > abs(value.translation.height) * 1.4 else { return }
                    if value.translation.width < 0 && canGoForward { onDateSwipe(1) }
                    if value.translation.width > 0 && canGoBack { onDateSwipe(-1) }
                })
                TideChart(tide: tide, day: day)
            }
            Card {
                Text("Published high and low tides")
                    .bold()
                    .foregroundStyle(CatchCheckColor.navy)
                TideEventRows(events: tide.events)
            }
        }
    }
}

/// The day's moon and what it means for the tide range. The Android Tide tab shows the same card.
private struct MoonCard: View {
    let day: Date

    var body: some View {
        let moon = MoonPhase.forDay(day)
        Card {
            Text("Moon").bold().foregroundStyle(.primary)
            Text(moon.summary).font(.headline).foregroundStyle(CatchCheckColor.navy)
            Text("\(moon.tideRange.label): \(moon.tideRange.explanation.prefix(1).lowercased() + moon.tideRange.explanation.dropFirst())")
            Text(MoonPhase.nextPhasesLine(after: day)).font(.caption).foregroundStyle(.secondary)
            Text("Spring tides follow new and full moons; neap tides follow the quarter moons.").font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// The tide height curve with its time and height readout, slider and time labels. The Tide tab and a place's
/// conditions page both show this, so the two always look and behave the same. Give it a new `.id` when the
/// station or day changes so the marker starts again at "now" (today) or midday.
struct TideChart: View {
    let tide: TideState
    let day: Date
    var curveHeight: CGFloat = 210
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
        VStack(alignment: .leading, spacing: 10) {
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
                .frame(height: curveHeight)
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
        .onAppear { selectedMinute = shownMinute }
    }
}

/// The day's published high and low tides, one row each.
struct TideEventRows: View {
    let events: [TideEvent]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(events) { event in
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
    @State private var conditionsPlace: ConditionPlace?
    @State private var notificationsAllowed = true
    @State private var tellSomeone = false
    private var saved: [Recommendation] {
        vm.savedRecommendations.values.sorted {
            ($0.startsAt ?? .distantFuture, $0.name) < ($1.startsAt ?? .distantFuture, $1.name)
        }
    }
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
                            if !active.summary.isEmpty { Text(active.summary).foregroundStyle(CatchCheckColor.navy) }
                            ForEach(active.warnings, id: \.self) { warning in
                                HStack(alignment: .top, spacing: 6) {
                                    Text(warningMood(warning).emoji)
                                    Text(warning)
                                }
                                .font(.caption).foregroundStyle(CatchCheckColor.orange)
                            }
                            if active.startsAt == nil {
                                Text("Choose the date and time in your calendar.").font(.caption).foregroundStyle(.secondary)
                            }
                            if let checkAt = vm.tripCheckAt {
                                Text(notificationsAllowed ? "Forecast check \(OfflineCache.savedLabel(checkAt)): you'll get a notification saying whether it changed."
                                     : "Allow notifications for Fishdays - NZ in Settings to get a forecast check \(OfflineCache.savedLabel(checkAt)).")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Button("Add to calendar") { calendarTrip = active }.buttonStyle(.bordered)
                            // Opens straight from here, so the forecast saved for the trip shows even without a map signal.
                            if let place = active.mapPlace { Button("Check conditions") { conditionsPlace = place }.buttonStyle(.borderedProminent) }
                            Button("Tell someone") { tellSomeone = true }.buttonStyle(.bordered)
                            if let backBy = vm.tripBackBy {
                                Text("Plan shared · back by \(OfflineCache.savedLabel(backBy)). You'll get a reminder to check in then.")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Button("Log a catch") { vm.logCatchOnTrip() }.buttonStyle(.bordered)
                            Button("End trip") { vm.activeTrip = nil }.buttonStyle(.bordered)
                        }
                    }
                    Text("Saved spots").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    if saved.isEmpty {
                        Text("Save a recommended spot to see it here.").foregroundStyle(.secondary)
                    } else {
                        ForEach(saved) { spot in RecommendationCard(spot: spot) { vm.trackSpotOpened(spot); selectedSavedSpot = spot } }
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Trips")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { vm.trackScreen("trip_planning") }
            .task(id: vm.tripCheckAt) { notificationsAllowed = await TripReminders.allowed() }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
        .sheet(item: $selectedSavedSpot) { SpotDetailView(spot: $0) }
        .sheet(item: $calendarTrip) { TripCalendarEditor(trip: $0) }
        .fullScreenCover(item: $conditionsPlace) { PlaceConditionsView(place: $0) }
        .sheet(isPresented: $tellSomeone) { if let trip = vm.activeTrip { TellSomeoneView(trip: trip) } }
    }
}

/// "Tell someone": who to tell, when you'll be back, and the plan text to send them. Android has the same dialog.
private struct TellSomeoneView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    let trip: Recommendation
    @State private var backBy = Date.now
    @State private var who = ""
    @State private var details = ""
    @State private var preparing = false
    @State private var shareContent: ShareContent?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Send your plan to a friend or family member who isn't going. If you're not back when you said, they'll know where to look and who to call.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    DatePicker("Back by", selection: $backBy, in: Date.now...)
                    TextField("Who's going (optional)", text: $who)
                    TextField("Details (car, boat, gear)", text: $details, axis: .vertical).lineLimit(2...5)
                }
                Section {
                    Button(preparing ? "Preparing…" : "Send plan") {
                        preparing = true
                        Task {
                            let link = await ShareLinkService.live.link(for: trip)
                            preparing = false
                            shareContent = ShareContent(subject: "Fishing trip plan · \(trip.name)",
                                                        text: TripPlan.text(trip: trip, backBy: backBy, who: who, details: details, forecastLink: link?.absoluteString))
                        }
                    }.disabled(preparing)
                }
            }
            .tint(CatchCheckColor.accent)
            .navigationTitle("Tell someone")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .onAppear { backBy = vm.tripBackBy ?? TripPlan.defaultBackBy(trip) ?? Date.now.addingTimeInterval(4 * 3_600) }
            .sheet(item: $shareContent, onDismiss: {
                vm.tripPlanShared(backBy: backBy)
                dismiss()
            }) { ActivityView(content: $0).presentationDetents([.medium, .large]) }
        }
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
        event.notes = "Fishing plan from Fishdays - NZ. Check the latest forecast, local access and fishing rules before you go."
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
    @Environment(\.scenePhase) private var scenePhase
    @State private var selectedArea: FishingRulesArea?
    @State private var showAreas = false
    @State private var query = ""
    @State private var page: SavedRulesPage?
    @State private var loadError: String?
    /// When the rules shown were saved on the phone, if the service could not be reached.
    @State private var offlineSavedAt: Date?
    @State private var loading = false
    @State private var loadToken = UUID()
    private var nearRaglanWest: Bool {
        selectedArea?.id == "auckland-kermadec" && vm.isNearRaglan && !vm.rulesAreaSelectionIsManual
    }
    private var quickRows: [SavedRuleQuickRow] {
        let rows = page?.quickRows(matching: query) ?? []
        guard nearRaglanWest, rows.contains(where: { $0.species.localizedCaseInsensitiveContains("Auckland West") }) else { return rows }
        return rows.filter { !$0.species.lowercased().hasPrefix("snapper") || $0.species.localizedCaseInsensitiveContains("Auckland West") }
    }

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
                        if nearRaglanWest {
                            Text("Near Raglan: snapper is in the Auckland West subarea. Check the exact catch spot on MPI.")
                                .font(.subheadline).foregroundStyle(CatchCheckColor.accent)
                        }
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
                        Text("Near an area boundary? Check the MPI map.")
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
                                Link("Open current MPI rules", destination: selectedArea?.officialURL ?? page.sourceURL).buttonStyle(.borderedProminent)
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
                            Card { Text(query.isEmpty ? "Search for a species to see its saved size and daily limits." : "No reliable simple limit can be shown for “\(query)”. Check MPI for this species and exact subarea.").foregroundStyle(.secondary) }
                        } else {
                            ForEach(quickRows) { row in SavedRuleQuickCard(row: row, officialURL: selectedArea?.officialURL ?? page.sourceURL) }
                        }
                        Card {
                            Label("Check local restrictions", systemImage: "exclamationmark.triangle").font(.headline)
                            Text("Closures, subareas, methods and recent changes can change what applies at a particular spot. Check the official page before keeping a catch.")
                                .font(.subheadline).foregroundStyle(.secondary)
                            Link("See full official MPI rules", destination: selectedArea?.officialURL ?? page.sourceURL).buttonStyle(.borderedProminent)
                            if let reviewedAt = page.reviewedAt { Text("Saved page reviewed by MPI: \(reviewedAt)").font(.caption).foregroundStyle(.secondary) }
                            if let offlineSavedAt {
                                Text("No connection · showing the rules saved on this phone \(OfflineCache.savedLabel(offlineSavedAt)). Check MPI when you can.")
                                    .font(.caption).foregroundStyle(CatchCheckColor.warning)
                            }
                        }
                        }
                    }
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Rules")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { vm.trackScreen("fishing_rules") }
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
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { vm.requestLocation() }
        }
        .task(id: selectedArea?.id) { await loadRules() }
    }

    @MainActor private func loadRules() async {
        // Only the newest load may update the screen. An older one that was
        // cancelled or superseded must not overwrite rules already shown.
        let token = UUID()
        loadToken = token
        guard let selectedArea else { page = nil; loadError = nil; loading = false; return }
        loading = true
        loadError = nil
        page = nil
        offlineSavedAt = nil
        defer { if loadToken == token { loading = false } }
        let offlineKey = "rules_\(selectedArea.id).json"
        let officialPath = selectedArea.officialURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        func matching(_ data: Data) -> SavedRulesPage? {
            (try? JSONDecoder().decode(SavedRulesEnvelope.self, from: data))?.rules.first(where: {
                $0.areaID == selectedArea.id && $0.sourceURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) == officialPath
            })
        }
        let baseURL = ((Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String) ?? "https://fishing.fishnz.space")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: "\(baseURL)/v1/rules?area=\(selectedArea.id)") else { loadError = "Rules service URL is unavailable."; return }
        do {
            var request = URLRequest(url: url)
            request.timeoutInterval = 15
            request.setValue("application/json", forHTTPHeaderField: "Accept")
            request.setValue("CatchCheckNZ-iOS/1.0", forHTTPHeaderField: "User-Agent")
            if let deviceID = AnalyticsPreferences.deviceID() { request.setValue(deviceID, forHTTPHeaderField: "X-Device-Id") }
            let (data, response) = try await URLSession.shared.data(for: request)
            guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0) else { throw URLError(.badServerResponse) }
            _ = try JSONDecoder().decode(SavedRulesEnvelope.self, from: data)
            guard loadToken == token, self.selectedArea?.id == selectedArea.id else { return }
            page = matching(data)
            if page == nil { loadError = "Saved rules do not match this fishing area. Open the official MPI page instead." }
            else { OfflineCache.write(offlineKey, data) }
        } catch {
            // URLSession reports a cancelled task as URLError.cancelled, not CancellationError.
            guard loadToken == token, !Task.isCancelled else { return }
            if let saved = OfflineCache.read(offlineKey), let savedPage = matching(saved.data) {
                page = savedPage; offlineSavedAt = saved.savedAt
            } else {
                loadError = "Saved rules are unavailable right now. Try again shortly."
            }
        }
    }
}

private struct SavedRulesEnvelope: Decodable { let rules: [SavedRulesPage] }
private struct SavedRulesPage: Decodable {
    let areaID: String
    let areaName: String
    let sourceURL: URL
    let reviewedAt: String?
    let needsReview: Bool?
    let sections: [SavedRulesSection]
    let tables: [[[String]]]
    enum CodingKeys: String, CodingKey {
        case areaID = "area_id", areaName = "area_name", sourceURL = "source_url", reviewedAt = "reviewed_at", needsReview, sections, tables
    }
}
private struct SavedRulesSection: Decodable { let heading: String; let text: String }

private struct SavedRuleQuickRow: Identifiable {
    let id: String
    let species: String
    let facts: [(label: String, value: String)]
    let note: String?
    let hasFootnote: Bool
}

private extension SavedRulesPage {
    var combinedFinfishSummary: String? {
        if areaName.localizedCaseInsensitiveContains("Fiordland") {
            return "Daily limits differ between the outer Fiordland Marine Area and the inner fiords. Check the exact subarea on MPI."
        }
        let pattern = "combined daily bag limit of\\s+\\d+\\s+finfish[^.]*\\."
        for section in sections {
            if let range = section.text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) {
                let source = String(section.text[range])
                return cleanMPIRuleText(source) + (hasMPIFootnoteMarker(source) ? " See MPI's definition of finfish." : "")
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
            return Array(table.dropFirst()).enumerated().compactMap { rowIndex, row in
                guard let originalSpecies = row.first?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !originalSpecies.isEmpty, !originalSpecies.hasPrefix("*"),
                      originalSpecies.count <= 180 else { return nil }
                let qualifier = row.count == headers.count && headers[1].localizedCaseInsensitiveCompare(headers[0]) == .orderedSame
                    ? row[1].trimmingCharacters(in: .whitespacesAndNewlines) : ""
                let name = cleanMPIRuleText(originalSpecies)
                let species = !qualifier.isEmpty && qualifier.localizedCaseInsensitiveCompare(originalSpecies) != .orderedSame
                    ? "\(name) — \(cleanMPIRuleText(qualifier))" : name
                guard !species.isEmpty else { return nil }
                var notes: [String] = []
                let hasFootnote = row.contains(where: hasMPIFootnoteMarker)
                if hasFootnote { notes.append("MPI footnote applies. Read the full condition on the official page.") }
                if originalSpecies.localizedCaseInsensitiveContains("refer to map") {
                    notes.append("Check the exact area boundary on MPI.")
                }
                var facts: [(label: String, value: String)] = []
                if row.count != headers.count {
                    facts = [("Area-specific rule", "See MPI")]
                    notes.append("This saved row cannot be matched safely to its columns. Check the official MPI limit.")
                } else {
                    for (index, label) in columns where row.indices.contains(index) {
                        let rawValue = row[index].trimmingCharacters(in: .whitespacesAndNewlines)
                        if rawValue.isEmpty || ["—", "–", "-", "none"].contains(rawValue) { continue }
                        let clean = cleanMPIRuleText(rawValue)
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
                                         facts: facts, note: notes.isEmpty ? nil : Array(Set(notes)).sorted().joined(separator: " "),
                                         hasFootnote: hasFootnote)
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

func cleanMPIRuleText(_ text: String) -> String {
    // MPI's shellfish tables also use a literal "^+" footnote marker.
    text.replacingOccurrences(of: "\\(Fisheries Management Area\\s*(\\d+)[^)]*\\)", with: "(FMA $1)", options: .regularExpression)
        .replacingOccurrences(of: "\\[PDF[^]]*]", with: "", options: .regularExpression)
        .replacingOccurrences(of: "\\*+", with: "", options: .regularExpression)
        .replacingOccurrences(of: "^+", with: "")
        .replacingOccurrences(of: "(?<=[\\p{L}\\p{N}])\\+(?![\\p{L}\\p{N}])", with: "", options: .regularExpression)
        .replacingOccurrences(of: "(?<=[\\p{L}\\p{N}])#(?![\\p{L}\\p{N}])", with: "", options: .regularExpression)
        .replacingOccurrences(of: "†", with: "")
        .replacingOccurrences(of: "‡", with: "")
        .replacingOccurrences(of: "\\s*•\\s*$", with: "", options: .regularExpression)
        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
        .trimmingCharacters(in: CharacterSet(charactersIn: " –-:"))
}

func cleanMPILimitValue(_ text: String) -> String {
    let clean = cleanMPIRuleText(text)
    let numbers = clean.components(separatedBy: CharacterSet.decimalDigits.inverted).filter { !$0.isEmpty }
    return numbers.count > 1 ? "Varies — see MPI" : clean
}

func hasMPIFootnoteMarker(_ text: String) -> Bool {
    text.contains("*") || text.contains("†") || text.contains("‡") || text.contains("^+") ||
        text.range(of: "\\s*•\\s*$", options: .regularExpression) != nil ||
        text.range(of: "(?<=[\\p{L}\\p{N}])\\+(?![\\p{L}\\p{N}])", options: .regularExpression) != nil ||
        text.range(of: "(?<=[\\p{L}\\p{N}])#(?![\\p{L}\\p{N}])", options: .regularExpression) != nil
}

func hasMPIFootnote(_ rule: FishRuleMatch) -> Bool {
    hasMPIFootnoteMarker(rule.species) || rule.dailyLimit.map(hasMPIFootnoteMarker) == true ||
        rule.minimumSize.map(hasMPIFootnoteMarker) == true || rule.minimumSizeLabel.map(hasMPIFootnoteMarker) == true ||
        rule.details.contains(where: { hasMPIFootnoteMarker($0.label) || hasMPIFootnoteMarker($0.value) })
}

func cleanIdentificationText(_ text: String) -> String {
    text.replacingOccurrences(of: "(?m)^[ \\t]*#{1,6}[ \\t]+", with: "", options: .regularExpression)
        .replacingOccurrences(of: "(?m)^[ \\t]*[-*][ \\t]+", with: "• ", options: .regularExpression)
        .replacingOccurrences(of: "\\*{1,2}", with: "", options: .regularExpression)
        .replacingOccurrences(of: "__(.*?)__", with: "$1", options: .regularExpression)
        .replacingOccurrences(of: "`", with: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
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
    let officialURL: URL
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
            if row.hasFootnote || row.facts.contains(where: { $0.value.contains("MPI") }) {
                Link(row.hasFootnote ? "Read MPI footnote" : "Check official MPI rule", destination: officialURL)
                    .font(.subheadline.weight(.semibold))
            }
        }
    }
}
