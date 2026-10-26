import SwiftUI
import MapKit
import UIKit
import EventKit
import EventKitUI

private enum FishingMapLayer: String, CaseIterable {
    case map = "Map"
    case satellite = "Satellite"
    case hybrid = "Hybrid"
    case linzAerial = "Aerial"
    case linzTopo = "Topographic"
    case linzTopoGridded = "Topographic with grid"

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
    /// The Spots tab opens on the map; the list, nearest first, is one tap away.
    @State private var showMap = true
    @State private var spotQuery = ""
    /// Set after "Show on map" moves the camera, so returning to the tab does not jump back to the current location.
    @State private var keepCameraOnAppear = false
    @AppStorage("mapShowsProtectedAreas") private var showProtection = true
    private var visibleSpots: [FishingSpot] { fishingSpots.filter { filter == "All" || (filter == "Boat" && $0.boat) || (filter == "Land" && !$0.boat) } }
    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("Spots").font(.title.bold()).foregroundStyle(CatchCheckColor.navy)
                    Spacer()
                    Button { showPlaceSearch = true } label: { Image(systemName: "magnifyingglass").font(.title3) }.accessibilityLabel("Search places")
                    Picker("Show", selection: $showMap) { Text("List").tag(false); Text("Map").tag(true) }
                        .pickerStyle(.segmented).frame(width: 120)
                }
                Text(showMap ? "Search a place or tap anywhere on the map" : vm.hasDeviceLocation ? "Nearest fishing spots first" : "Fishing spots by area")
                    .font(.subheadline).foregroundStyle(.secondary)
                Picker("Filter", selection: $filter) { Text("All spots").tag("All"); Text("Land fishing").tag("Land"); Text("Boat fishing").tag("Boat") }.pickerStyle(.segmented)
            }.padding(20)
            FishingMapCanvas(layer: layer, showProtection: showProtection, showRamps: filter == "Boat", showStructures: filter == "Land", spots: visibleSpots, selectedPlace: selectedPlace, cameraCommand: cameraCommand,
                             currentCamera: $currentCamera, onSpotSelected: { selectedPlace = ConditionPlace($0) },
                             onPointSelected: { selectedPlace = ConditionPlace(name: "Dropped pin", point: $0, region: "Selected on map", boat: filter == "Boat") },
                             onRampSelected: { selectedPlace = ConditionPlace(name: $0.label, point: $0.point, region: "Access and tide limits not checked", boat: true) },
                             onStructureSelected: { selectedPlace = ConditionPlace(name: $0.label, point: $0.point, region: $0.mapSubtitle, boat: false) })
            .overlay(alignment: .topLeading) {
                Text("\(visibleSpots.count) spots" + (filter == "Boat" ? " · teal pins are boat ramps" : filter == "Land" ? " · brown pins are wharves and breakwaters" : ""))
                    .font(.caption.weight(.semibold)).foregroundStyle(CatchCheckColor.navy)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(CatchCheckColor.surface.opacity(0.94), in: RoundedRectangle(cornerRadius: 8))
                    .padding(12)
                    .padding(.trailing, 60) // wraps instead of running under the map layer button on narrow phones
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
                if layer.tileURLTemplate != nil || showProtection || filter != "All" {
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
                        if filter == "Boat" {
                            Text("·")
                            Link("Ramps © OpenStreetMap", destination: BoatRamps.creditURL)
                        }
                        if filter == "Land" {
                            Text("·")
                            Link("Wharves © OpenStreetMap", destination: ShoreStructures.creditURL)
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
            // The list covers the map rather than replacing it, so the map keeps its place and loads in the background.
            .overlay {
                if !showMap {
                    SpotListView(spots: visibleSpots, from: vm.hasDeviceLocation ? vm.location : nil, query: $spotQuery) { spot in
                        selectedPlace = ConditionPlace(spot)
                        showMap = true
                        sendCamera(.region(MKCoordinateRegion(center: .init(latitude: spot.coordinate.latitude, longitude: spot.coordinate.longitude),
                                                              span: .init(latitudeDelta: 0.12, longitudeDelta: 0.12))))
                    }
                }
            }
            if showMap { selectedPlaceCard }
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
            .sheet(isPresented: $showPlaceSearch) {
                MapPlaceSearchView { place in
                    selectedPlace = place
                    showMap = true
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
                    Label("Open in Maps", systemImage: "mappin.and.ellipse")
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
        conditionsPlace = nil; showMap = true; showPlaceSearch = false
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
        // Shows the pin; directions are one more tap in Maps.
        destination.openInMaps()
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
    let showRamps: Bool
    let showStructures: Bool
    let spots: [FishingSpot]
    let selectedPlace: ConditionPlace?
    let cameraCommand: MapCameraCommand
    @Binding var currentCamera: MKMapCamera?
    let onSpotSelected: (FishingSpot) -> Void
    let onPointSelected: (GeoPoint) -> Void
    let onRampSelected: (BoatRamp) -> Void
    let onStructureSelected: (ShoreStructure) -> Void

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

        if coordinator.rampsShown != showRamps {
            coordinator.rampsShown = showRamps
            map.removeAnnotations(map.annotations.filter { $0 is BoatRampAnnotation })
            if showRamps { map.addAnnotations(BoatRamps.bundled.map(BoatRampAnnotation.init)) }
        }
        if coordinator.structuresShown != showStructures {
            coordinator.structuresShown = showStructures
            map.removeAnnotations(map.annotations.filter { $0 is ShoreStructureAnnotation })
            if showStructures { map.addAnnotations(ShoreStructures.bundled.map(ShoreStructureAnnotation.init)) }
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
        var rampsShown = false
        var structuresShown = false
        static let structureColor = UIColor(red: 0x63 / 255, green: 0x38 / 255, blue: 0x06 / 255, alpha: 1)
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
            if let ramp = annotation as? BoatRampAnnotation {
                let marker = mapView.dequeueReusableAnnotationView(withIdentifier: "BoatRamp") as? MKMarkerAnnotationView
                    ?? MKMarkerAnnotationView(annotation: ramp, reuseIdentifier: "BoatRamp")
                marker.annotation = ramp
                marker.markerTintColor = UIColor(red: 0x0E / 255, green: 0x7C / 255, blue: 0x86 / 255, alpha: 1)
                marker.glyphImage = UIImage(systemName: "sailboat.fill")
                marker.canShowCallout = false
                marker.clusteringIdentifier = "boat-ramps"
                marker.displayPriority = .defaultLow
                return marker
            }
            if let structure = annotation as? ShoreStructureAnnotation {
                let marker = mapView.dequeueReusableAnnotationView(withIdentifier: "ShoreStructure") as? MKMarkerAnnotationView
                    ?? MKMarkerAnnotationView(annotation: structure, reuseIdentifier: "ShoreStructure")
                marker.annotation = structure
                marker.markerTintColor = Self.structureColor
                marker.glyphImage = UIImage(systemName: "water.waves")
                marker.canShowCallout = false
                marker.clusteringIdentifier = "shore-structures"
                marker.displayPriority = .defaultLow
                return marker
            }
            // Spots close together on screen merge into one marker showing how many there are, as on Android.
            if let cluster = annotation as? MKClusterAnnotation {
                let ramps = cluster.memberAnnotations.allSatisfy { $0 is BoatRampAnnotation }
                let structures = cluster.memberAnnotations.allSatisfy { $0 is ShoreStructureAnnotation }
                let marker = mapView.dequeueReusableAnnotationView(withIdentifier: "SpotCluster") as? MKMarkerAnnotationView
                    ?? MKMarkerAnnotationView(annotation: cluster, reuseIdentifier: "SpotCluster")
                marker.annotation = cluster
                marker.markerTintColor = ramps ? UIColor(red: 0x0E / 255, green: 0x7C / 255, blue: 0x86 / 255, alpha: 1)
                    : structures ? Self.structureColor : UIColor(red: 0x12 / 255, green: 0x3B / 255, blue: 0x43 / 255, alpha: 1)
                marker.glyphImage = nil
                marker.glyphText = "\(cluster.memberAnnotations.count)"
                marker.titleVisibility = .hidden
                marker.subtitleVisibility = .hidden
                marker.canShowCallout = false
                marker.accessibilityLabel = "\(cluster.memberAnnotations.count) \(ramps ? "boat ramps" : structures ? "wharves and breakwaters" : "fishing spots")"
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
            if let ramp = view.annotation as? BoatRampAnnotation {
                parent.onRampSelected(ramp.ramp)
                mapView.deselectAnnotation(ramp, animated: false)
                return
            }
            if let structure = view.annotation as? ShoreStructureAnnotation {
                parent.onStructureSelected(structure.structure)
                mapView.deselectAnnotation(structure, animated: false)
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

/// A fishing spot as the Spots list shows it: how far it is from the phone and what is close by. Android builds the same
/// list in `SpotListing.kt`.
struct SpotListing: Identifiable {
    let spot: FishingSpot
    let distanceKm: Double?
    let nearby: String?
    let warning: String?
    var id: String { "\(spot.id):\(spot.coordinate.latitude):\(spot.coordinate.longitude)" }

    /// Nearest first when the phone's position is known, otherwise by area and name.
    static func build(_ spots: [FishingSpot], from: GeoPoint?, ramps: [BoatRamp], structures: [ShoreStructure], protectedAreas: [ProtectedArea]) -> [SpotListing] {
        let listings = spots.map { spot -> SpotListing in
            let point = spot.coordinate
            let nearby: String?
            if spot.boat {
                nearby = BoatRamps.nearest(point, in: ramps, limit: 1, withinKm: 15).first.map { "Boat ramp \(BoatRamps.distanceLabel($0.km)) away" }
            } else {
                nearby = ShoreStructures.nearest(point, in: structures, limit: 1, withinKm: 3).first.map { "\($0.structure.kindLabel.prefix(1).uppercased() + $0.structure.kindLabel.dropFirst()) \(BoatRamps.distanceLabel($0.km)) away" }
            }
            let warning = MarineProtection.notices(at: point, in: protectedAreas, limit: 1).first.map { notice in
                notice.area.kind.noFishing ? (notice.inside ? "Inside a no-fishing area" : "No-fishing area nearby") : "Seafloor Protection Area nearby"
            }
            return SpotListing(spot: spot, distanceKm: from.map { placeDistanceKm($0, point) }, nearby: nearby, warning: warning)
        }
        if from != nil { return listings.sorted { ($0.distanceKm ?? 0) < ($1.distanceKm ?? 0) } }
        return listings.sorted { ($0.spot.area, $0.spot.name) < ($1.spot.area, $1.spot.name) }
    }

    func matches(_ query: String) -> Bool {
        let text = query.trimmingCharacters(in: .whitespaces)
        return text.isEmpty || spot.name.localizedCaseInsensitiveContains(text) || spot.area.localizedCaseInsensitiveContains(text)
    }

    /// "800 m", "4.2 km" or "120 km".
    static func distanceLabel(_ km: Double) -> String { km < 100 ? BoatRamps.distanceLabel(km) : "\(Int(km)) km" }
}

/// The Spots tab's list: every spot for the filter, nearest first when the phone's position is known.
private struct SpotListView: View {
    let spots: [FishingSpot]
    let from: GeoPoint?
    @Binding var query: String
    let choose: (FishingSpot) -> Void

    var body: some View {
        let listings = SpotListing.build(spots, from: from, ramps: BoatRamps.bundled, structures: ShoreStructures.bundled,
                                         protectedAreas: MarineProtection.bundled?.areas ?? [])
        let matching = listings.filter { $0.matches(query) }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Search spots or areas", text: $query).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if !query.isEmpty {
                        Button { query = "" } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("Clear search")
                    }
                }
                .padding(12)
                .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 12))
                Text(matching.isEmpty ? "No spot matches “\(query.trimmingCharacters(in: .whitespaces))”. Try a town or region, or search a place with the magnifier."
                     : from != nil ? "\(matching.count) spots · nearest first" : "\(matching.count) spots · turn on location to sort by distance")
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(matching) { listing in
                    Button { choose(listing.spot) } label: {
                        HStack(spacing: 12) {
                            Image(systemName: listing.spot.boat ? "sailboat.fill" : "figure.fishing")
                                .font(.title3).foregroundStyle(CatchCheckColor.accent).frame(width: 28)
                                .accessibilityLabel(listing.spot.boat ? "Boat fishing" : "Land fishing")
                            VStack(alignment: .leading, spacing: 2) {
                                Text(listing.spot.name).font(.subheadline.weight(.semibold)).foregroundStyle(CatchCheckColor.navy)
                                Text("\(listing.spot.area) · \(listing.spot.boat ? "Boat" : "Land")").font(.caption).foregroundStyle(.secondary)
                                if let nearby = listing.nearby { Text(nearby).font(.caption).foregroundStyle(.secondary) }
                                if let warning = listing.warning { Text(warning).font(.caption.weight(.semibold)).foregroundStyle(.red) }
                            }
                            Spacer(minLength: 8)
                            if let km = listing.distanceKm {
                                Text(SpotListing.distanceLabel(km)).font(.subheadline.weight(.semibold)).foregroundStyle(CatchCheckColor.navy)
                            }
                        }
                        .padding(.horizontal, 14).padding(.vertical, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 16))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20).padding(.bottom, 20)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(CatchCheckColor.cream)
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
                    Text("New Zealand tide times")
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
                        ProgressView("Loading tide times…")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 28)
                    }
                    Text("The curve between highs and lows is estimated.")
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
            .searchable(text: $query, prompt: tr("Search tide stations"))
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
                    Text("Estimated").font(.caption).foregroundStyle(.secondary)
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

/// The fishing rules page: the MPI area's species with pictures, minimum sizes and daily limits as big figures, a catch
/// size check, and a note on how fresh the copy kept on the phone is. Android has the same page in `RulesScreen.kt`.
struct RulesView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openURL) private var openURL
    @ObservedObject private var sync = RulesSyncState.shared
    @State private var selectedArea: FishingRulesArea?
    @State private var showAreas = false
    @State private var query = ""
    @State private var group: SpeciesGroup = .popular
    @State private var page: SavedRulesPage?
    @State private var source: RulesSource = .saved
    @State private var savedAt: Date?
    @State private var loadError: String?
    @State private var loading = false
    @State private var checking = false
    @State private var reloadToken = 0
    @State private var detail: SavedRuleQuickRow?
    @State private var commonDetail: SpeciesInfo?
    @State private var loadToken = UUID()
    private var catalog: SpeciesCatalog? { _ = sync.revision; return RulesSync.catalog() }
    private var nearRaglanWest: Bool {
        selectedArea?.id == "auckland-kermadec" && vm.isNearRaglan && !vm.rulesAreaSelectionIsManual
    }
    private var allRows: [SavedRuleQuickRow] {
        let rows = page?.allRows() ?? []
        guard nearRaglanWest, rows.contains(where: { $0.species.localizedCaseInsensitiveContains("Auckland West") }) else { return rows }
        return rows.filter { !$0.species.lowercased().hasPrefix("snapper") || $0.species.localizedCaseInsensitiveContains("Auckland West") }
    }
    private var shownRows: [SavedRuleQuickRow] {
        let catalog = catalog
        let text = query.trimmingCharacters(in: .whitespaces)
        if !text.isEmpty {
            return Array(allRows.filter { row in
                row.species.localizedStandardContains(text) || catalog?.forRule(row.species).map {
                    $0.name.localizedStandardContains(text) || ($0.maori?.localizedStandardContains(text) ?? false)
                } == true
            }.prefix(40))
        }
        switch group {
        case .popular: return popularRuleRows(allRows)
        case .all: return allRows
        default: return allRows.filter { catalog?.forRule($0.species)?.group == group.catalogGroup }
        }
    }
    private var officialURL: URL { selectedArea?.officialURL ?? page?.sourceURL ?? URL(string: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules")! }
    private var commonCatches: [SpeciesInfo] { catalog?.commonCatches(allRows.map(\.species)) ?? [] }
    private var combinedLimit: Int? { page?.areaName.localizedCaseInsensitiveContains("Fiordland") == true ? nil : page?.combinedFinfishLimit }
    private var shellfishLimit: Int? { otherShellfishLimit(allRows) }
    /// Species with no rule of their own in this area, listed after the rule rows of their group, like MPI's app.
    private var extras: [SpeciesInfo] {
        let noOwnLimit = commonCatches + (catalog?.otherShellfish(allRows.map(\.species)) ?? [])
        let text = query.trimmingCharacters(in: .whitespaces)
        if !text.isEmpty {
            return noOwnLimit.filter { info in
                info.name.localizedStandardContains(text) || (info.maori?.localizedStandardContains(text) ?? false)
                    || info.match.contains { $0.localizedStandardContains(text) }
            }
        }
        switch group {
        case .popular: return []
        case .all: return noOwnLimit
        default: return noOwnLimit.filter { $0.group == group.catalogGroup }
        }
    }
    private func countsToward(_ info: SpeciesInfo) -> String {
        info.isOtherShellfish ? otherShellfishNote(shellfishLimit) : combinedNote(combinedLimit)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Fishing rules").font(.largeTitle.bold()).foregroundStyle(.primary)
                    Text("Sizes and daily limits for the MPI area where you fish. Kept on your phone for when there's no signal.").foregroundStyle(.secondary)
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
                        Button { vm.useCurrentRulesArea() } label: { Label("Use my current location", systemImage: "location.fill") }
                            .buttonStyle(.bordered)
                        Text("Near an area boundary? Check the MPI map.").font(.caption).foregroundStyle(.secondary)
                    }
                    if loading { ProgressView("Loading MPI rules…").frame(maxWidth: .infinity).padding(24) }
                    if let loadError {
                        Card {
                            Text(loadError).foregroundStyle(.secondary)
                            Button("Try again") { reloadToken += 1 }.buttonStyle(.bordered)
                            Link("Open official MPI page", destination: officialURL)
                        }
                    }
                    if let page {
                        freshness(page)
                        if page.needsReview == true {
                            Card(background: CatchCheckColor.seafoam) {
                                Label("Rules changed at MPI", systemImage: "arrow.triangle.2.circlepath").font(.headline)
                                Text("This area's saved summary is being reviewed. Open the official MPI page for current limits and closures.")
                                    .font(.subheadline)
                                Link("Open current MPI rules", destination: officialURL).buttonStyle(.borderedProminent)
                            }
                        } else {
                            CombinedLimitCard(page: page, common: commonCatches) { commonDetail = $0 }
                            let popular = popularRuleRows(allRows).filter { $0.minimum != nil }
                            let checkable = popular + allRows.filter { row in row.minimum != nil && !popular.contains { $0.id == row.id } }
                            if !checkable.isEmpty { CheckMyCatchCard(rows: checkable, catalog: catalog) }
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
                            if query.isEmpty {
                                FlowLayout {
                                    ForEach(SpeciesGroup.allCases, id: \.self) { option in
                                        Button(option.rawValue) { group = option }.buttonStyle(ChoiceButton(selected: group == option))
                                    }
                                }
                            }
                            let rows = shownRows
                            let extras = extras
                            if rows.isEmpty && extras.isEmpty {
                                Card { Text(query.isEmpty ? "No species in this group have a simple limit for \(page.areaName). Try All or check MPI." : "No reliable simple limit can be shown for “\(query)”. Check MPI for this species and exact subarea.").foregroundStyle(.secondary) }
                            }
                            ForEach(rows) { row in
                                Button { detail = row } label: { SpeciesRuleCard(row: row, info: catalog?.forRule(row.species)) }.buttonStyle(.plain)
                            }
                            if query.isEmpty && !extras.isEmpty {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("No limit of their own").font(.subheadline.bold()).foregroundStyle(CatchCheckColor.navy)
                                    Text("Each counts toward a combined daily limit, shown on its row.").font(.caption).foregroundStyle(.secondary)
                                }
                                .padding(.top, 8)
                            }
                            ForEach(extras) { info in
                                Button { commonDetail = info } label: {
                                    CommonCatchRow(info: info, countsToward: countsToward(info)).padding(.vertical, 6)
                                        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
                                }
                                .buttonStyle(.plain)
                            }
                            Text("Summary only. Closures, gear rules and subareas can change what applies: check MPI for your exact spot. \(catalog?.note ?? "")")
                                .font(.caption).foregroundStyle(.secondary)
                            Link("See all rules on MPI", destination: officialURL).buttonStyle(.borderedProminent)
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
            .sheet(item: $detail) { row in
                SpeciesRuleDetail(row: row, info: catalog?.forRule(row.species), catalog: catalog, officialURL: officialURL)
                    .presentationDetents([.large])
            }
            .sheet(item: $commonDetail) { info in
                Group {
                    if info.isOtherShellfish {
                        OtherShellfishDetail(info: info, limit: shellfishLimit, catalog: catalog, officialURL: officialURL)
                    } else {
                        CommonCatchDetail(info: info, limit: combinedLimit, catalog: catalog, officialURL: officialURL)
                    }
                }
                .presentationDetents([.large])
            }
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
        // Opening the page checks the service for changed rules and pictures; nothing is downloaded when nothing changed.
        .task { await RulesSync.sync() }
        .task(id: "\(selectedArea?.id ?? "")|\(sync.revision)|\(reloadToken)") { await loadRules() }
    }

    @ViewBuilder private func freshness(_ page: SavedRulesPage) -> some View {
        let checked = [savedAt, source == .saved ? RulesSync.lastSynced() : nil].compactMap { $0 }.max()
        let old = checked.map { Date.now.timeIntervalSince($0) > 30 * 86_400 } ?? true
        let text: String = if source == .builtIn {
            "Showing the copy built into the app\(page.reviewedAt.map { " (MPI page reviewed \($0))" } ?? ""). Connect once to get the latest rules and pictures."
        } else if old {
            "Saved on this phone \(checked.map(OfflineCache.savedLabel) ?? ""). Connect to check for MPI changes."
        } else {
            "Saved on this phone for use with no signal · checked \(OfflineCache.savedLabel(checked!))\(page.reviewedAt.map { " · MPI page reviewed \($0)" } ?? "")"
        }
        HStack(alignment: .firstTextBaseline) {
            Text(text).font(.caption).foregroundStyle(source == .builtIn || old ? CatchCheckColor.warning : .secondary)
            Spacer()
            Button(checking ? "Checking…" : "Check now") {
                Task { checking = true; await RulesSync.sync(force: true); checking = false; reloadToken += 1 }
            }
            .font(.caption.weight(.semibold)).disabled(checking)
        }
    }

    /// The copy kept on the phone, else the service's (then kept), else the copy built into the app.
    @MainActor private func loadRules() async {
        // Only the newest load may update the screen. An older one that was
        // cancelled or superseded must not overwrite rules already shown.
        let token = UUID()
        loadToken = token
        guard let selectedArea else { page = nil; loadError = nil; loading = false; return }
        loading = page == nil
        loadError = nil
        defer { if loadToken == token { loading = false } }
        let officialPath = selectedArea.officialURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        func matching(_ data: Data) -> SavedRulesPage? {
            (try? JSONDecoder().decode(SavedRulesEnvelope.self, from: data))?.rules.first(where: {
                $0.areaID == selectedArea.id && $0.sourceURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")) == officialPath
            })
        }
        if let saved = OfflineCache.read("rules_\(selectedArea.id).json"), let savedPage = matching(saved.data) {
            page = savedPage; source = .saved; savedAt = saved.savedAt
            return
        }
        do {
            let data = try await RulesSync.downloadRules(selectedArea.id)
            guard loadToken == token, self.selectedArea?.id == selectedArea.id else { return }
            page = matching(data); source = .saved; savedAt = .now
            if page == nil { loadError = "Saved rules do not match this fishing area. Open the official MPI page instead." }
        } catch {
            // URLSession reports a cancelled task as URLError.cancelled, not CancellationError.
            guard loadToken == token, !Task.isCancelled else { return }
            if let builtIn = RulesSync.builtInRule(selectedArea.id), let builtInPage = matching(builtIn.data) {
                page = builtInPage; source = .builtIn; savedAt = builtIn.made
            } else {
                page = nil
                loadError = "Saved rules are unavailable right now. Try again shortly."
            }
        }
    }
}

/// The rules page's species groups; `catalogGroup` matches the species catalogue's group.
enum SpeciesGroup: String, CaseIterable {
    case popular = "Popular", finfish = "Finfish", sharks = "Sharks and rays", shellfish = "Shellfish", crayfish = "Crayfish", all = "All"
    var catalogGroup: String? {
        switch self {
        case .finfish: "finfish"
        case .sharks: "shark"
        case .shellfish: "shellfish"
        case .crayfish: "crayfish"
        case .popular, .all: nil
        }
    }
}

/// The species most people look up first, in this order. Android: `RuleRows.kt`.
private let popularSpecies = ["snapper", "blue cod", "kingfish", "kahawai", "red gurnard", "gurnard", "tarakihi", "trevally",
                              "red or spiny rock lobster", "pāua", "paua", "scallops", "cockle"]

private func popularRuleRows(_ rows: [SavedRuleQuickRow]) -> [SavedRuleQuickRow] {
    func rank(_ row: SavedRuleQuickRow) -> Int? { popularSpecies.firstIndex { row.species.lowercased().hasPrefix($0) } }
    return Array(rows.filter { rank($0) != nil }.sorted { rank($0)! < rank($1)! }.prefix(10))
}

/// The answer to "Check my catch": whether a length meets the row's minimum, in words.
struct CatchCheckResult: Equatable { let meets: Bool; let text: String }

/// Checks `length`, in the row's own unit, against its single minimum; nil when the row has none.
private func checkCatch(_ row: SavedRuleQuickRow, length: Double) -> CatchCheckResult? {
    guard let (minimum, unit) = row.minimum, length > 0 else { return nil }
    func number(_ value: Double) -> String { value.rounded() == value ? String(Int(value)) : String(format: "%.1f", value) }
    let shown = "\(number(length)) \(unit)"
    let limit = row.dailyLimit.flatMap { Int($0) }.map { " It counts toward the daily limit of \($0)." } ?? ""
    return length >= minimum ? CatchCheckResult(meets: true, text: "\(shown) meets the \(number(minimum)) \(unit) minimum.\(limit)")
        : CatchCheckResult(meets: false, text: "\(shown) is under the \(number(minimum)) \(unit) minimum. Release it carefully.")
}

/// The area's combined daily finfish limit as a big number, then the common catches with no limit of their own, which
/// count toward it: three at first, all on request.
private struct CombinedLimitCard: View {
    let page: SavedRulesPage
    let common: [SpeciesInfo]
    let open: (SpeciesInfo) -> Void
    @State private var showAll = false
    private var limit: Int? { page.areaName.localizedCaseInsensitiveContains("Fiordland") ? nil : page.combinedFinfishLimit }

    var body: some View {
        Card {
            if let limit {
                HStack(spacing: 14) {
                    Text("\(limit)").font(.system(size: 44, weight: .bold, design: .rounded)).foregroundStyle(CatchCheckColor.accent)
                    Text("finfish per person per day, all species combined. Species with their own rule also have their own limit.").foregroundStyle(CatchCheckColor.navy)
                }
            } else {
                Text(page.combinedFinfishSummary ?? "Daily limits vary by species and place. Check the MPI page for your exact spot.").foregroundStyle(CatchCheckColor.navy)
            }
            if !common.isEmpty {
                Divider()
                Text("Fish with no limit of their own").font(.subheadline.bold()).foregroundStyle(CatchCheckColor.navy)
                Text(limit.map { "Each counts toward the \($0) a day above." } ?? "Each counts toward the area's combined finfish limit.")
                    .font(.caption).foregroundStyle(.secondary)
                ForEach(showAll ? common : Array(common.prefix(3))) { info in
                    Button { open(info) } label: { CommonCatchRow(info: info, countsToward: combinedNote(limit)) }.buttonStyle(.plain)
                }
                if common.count > 3 {
                    Button(showAll ? "Show fewer" : "Show all \(common.count)") { withAnimation { showAll.toggle() } }.font(.subheadline.weight(.semibold))
                }
            }
        }
    }
}

/// Which limit a fish with none of its own comes under, such as "in the 20 combined".
private func combinedNote(_ limit: Int?) -> String { limit.map { "in the \($0) combined" } ?? "in the combined limit" }

/// Which limit a shellfish with none of its own comes under, such as "in the 50 other shellfish".
private func otherShellfishNote(_ limit: Int?) -> String { limit.map { "in the \($0) other shellfish" } ?? "in all other shellfish" }

/// The area's "All others (combined)" shellfish limit, such as 50, when every daily limit in that row is the same number.
private func otherShellfishLimit(_ rows: [SavedRuleQuickRow]) -> Int? {
    guard let row = rows.first(where: { $0.species.lowercased().hasPrefix("all others") }) else { return nil }
    let limits = Set(row.facts.filter { $0.label.hasPrefix("Daily limit") }.map(\.value))
    return limits.count == 1 ? limits.first.flatMap { Int($0) } : nil
}

/// A species with no limit of its own as a compact row: picture, names and `countsToward`, the limit it comes under.
private struct CommonCatchRow: View {
    let info: SpeciesInfo
    let countsToward: String
    var body: some View {
        HStack(spacing: 12) {
            SpeciesPictureView(picture: info.pictures.first, description: info.name).frame(width: 84, height: 56)
            VStack(alignment: .leading, spacing: 2) {
                Text(info.name).font(.subheadline.weight(.semibold)).foregroundStyle(CatchCheckColor.navy)
                Text([info.maori, countsToward].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 4)
        .contentShape(Rectangle())
    }
}

/// A species' pictures, large, with a button for each form when there are several.
private struct SpeciesPictures: View {
    let info: SpeciesInfo
    @State private var selected = 0
    var body: some View {
        if !info.pictures.isEmpty {
            let picture = info.pictures[min(selected, info.pictures.count - 1)]
            SpeciesPictureView(picture: picture, description: "\(info.name), \(picture.caption)").aspectRatio(1.5, contentMode: .fit)
            if info.pictures.count > 1 {
                FlowLayout {
                    ForEach(Array(info.pictures.enumerated()), id: \.offset) { index, item in
                        Button(item.caption) { selected = index }.buttonStyle(ChoiceButton(selected: index == selected))
                    }
                }
            }
            Text("Illustration · \(picture.caption)").font(.caption2).foregroundStyle(.secondary)
        }
    }
}

/// A common catch: its pictures, that it has no limit of its own, and the combined limit it counts toward.
private struct CommonCatchDetail: View {
    @Environment(\.dismiss) private var dismiss
    let info: SpeciesInfo
    let limit: Int?
    let catalog: SpeciesCatalog?
    let officialURL: URL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    SpeciesPictures(info: info)
                    Text(info.name).font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text([info.maori, info.scientific.isEmpty ? nil : info.scientific].compactMap { $0 }.joined(separator: " · ")).foregroundStyle(.secondary)
                    HStack(spacing: 16) {
                        RuleFigure(value: "None", unit: "", label: "own minimum")
                        RuleFigure(value: limit.map(String.init) ?? "—", unit: "", label: "combined per day")
                    }
                    Text("MPI sets no size or daily limit of its own for this fish in this area. It counts toward the combined \(limit.map { "limit of \($0) finfish per person per day" } ?? "finfish limit"), together with every other finfish you keep.")
                        .foregroundStyle(CatchCheckColor.navy)
                    Text("Local closures and rules can still apply. Check MPI for your exact spot.").font(.caption).foregroundStyle(.secondary)
                    Link("See this area's rules on MPI", destination: officialURL).buttonStyle(.bordered).frame(maxWidth: .infinity)
                    if let note = catalog?.note { Text(note).font(.caption).foregroundStyle(.secondary) }
                }
                .padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle(info.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

/// A shellfish with no rule of its own: its pictures and MPI's "All others (combined)" limit it counts toward.
private struct OtherShellfishDetail: View {
    @Environment(\.dismiss) private var dismiss
    let info: SpeciesInfo
    let limit: Int?
    let catalog: SpeciesCatalog?
    let officialURL: URL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    SpeciesPictures(info: info)
                    Text(info.name).font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text([info.maori, info.scientific.isEmpty ? nil : info.scientific].compactMap { $0 }.joined(separator: " · ")).foregroundStyle(.secondary)
                    HStack(spacing: 16) {
                        RuleFigure(value: "None", unit: "", label: "own limit")
                        RuleFigure(value: limit.map(String.init) ?? "See MPI", unit: "", label: "all others per day")
                    }
                    Text("MPI sets no limit of its own for this species in this area. It counts toward MPI's “All others (combined)” \(limit.map { "limit of \($0) per person per day" } ?? "shellfish limit"), together with every other shellfish without a limit of its own.")
                        .foregroundStyle(CatchCheckColor.navy)
                    Text("Local closures and rules can still apply. Check MPI for your exact spot.").font(.caption).foregroundStyle(.secondary)
                    Link("See this area's rules on MPI", destination: officialURL).buttonStyle(.bordered).frame(maxWidth: .infinity)
                    if let note = catalog?.note { Text(note).font(.caption).foregroundStyle(.secondary) }
                }
                .padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle(info.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

/// A species' picture, from the phone or downloaded now; a fish outline until it loads or with no signal.
struct SpeciesPictureView: View {
    let picture: SpeciesPicture?
    let description: String
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 12).fill(.white)
            if let image {
                Image(uiImage: image).resizable().scaledToFit().padding(2).accessibilityLabel(description)
            } else {
                Image(systemName: "fish").font(.title2).foregroundStyle(.gray)
            }
        }
        .task(id: picture?.file) {
            guard let picture else { image = nil; return }
            image = await RulesSync.picture(picture).flatMap { UIImage(contentsOfFile: $0.path) }
        }
    }
}

/// A size or limit as a big figure: "30 cm" over "minimum", or "No take".
private struct RuleFigure: View {
    let value: String
    let unit: String
    let label: String
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value).font(value.count <= 4 ? .title2.bold() : .subheadline.bold()).foregroundStyle(CatchCheckColor.navy).lineLimit(1)
                if !unit.isEmpty { Text(unit).font(.subheadline).foregroundStyle(CatchCheckColor.navy) }
            }
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
    }
}

/// A bar from 0 to 100 cm (or 200 mm for shellfish) with the minimum size filled in.
private struct SizeBar: View {
    let minimum: (Double, String)
    var body: some View {
        let scale = minimum.1 == "mm" ? 200.0 : 100.0
        VStack(spacing: 2) {
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(CatchCheckColor.outline)
                    Capsule().fill(CatchCheckColor.accent).frame(width: geometry.size.width * min(max(minimum.0 / scale, 0.03), 1))
                }
            }
            .frame(height: 8)
            HStack {
                Text("0"); Spacer(); Text("\(Int(scale / 2))"); Spacer(); Text("\(Int(scale)) \(minimum.1)")
            }
            .font(.caption2).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Minimum \(Int(minimum.0)) \(minimum.1) on a scale to \(Int(scale)) \(minimum.1)")
    }
}

private func figure(_ value: Double) -> String { value.rounded() == value ? String(Int(value)) : String(value) }

private struct SpeciesRuleCard: View {
    let row: SavedRuleQuickRow
    let info: SpeciesInfo?
    var body: some View {
        Card {
            HStack(spacing: 12) {
                SpeciesPictureView(picture: info?.pictures.first, description: info?.name ?? row.species).frame(width: 108, height: 72)
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.species).font(.headline).foregroundStyle(CatchCheckColor.navy).lineLimit(2).multilineTextAlignment(.leading)
                    if let maori = info?.maori { Text(maori).font(.caption).foregroundStyle(.secondary) }
                    HStack(spacing: 16) {
                        if let minimum = row.minimum {
                            RuleFigure(value: figure(minimum.0), unit: minimum.1, label: "minimum")
                        } else if let fact = row.facts.first(where: { $0.label.hasPrefix("Minimum") }) {
                            RuleFigure(value: fact.value, unit: "", label: fact.label.lowercased())
                        } else {
                            RuleFigure(value: "None", unit: "", label: "minimum")
                        }
                        if let limit = row.dailyLimit { RuleFigure(value: limit, unit: "", label: "per day") }
                    }
                }
                Spacer(minLength: 0)
            }
            if let minimum = row.minimum { SizeBar(minimum: minimum) }
            if let note = row.note { Text(note).font(.caption).foregroundStyle(.secondary).lineLimit(2) }
        }
    }
}

/// A species' pictures, every rule fact, the catch check for it and the MPI link.
private struct SpeciesRuleDetail: View {
    @Environment(\.dismiss) private var dismiss
    let row: SavedRuleQuickRow
    let info: SpeciesInfo?
    let catalog: SpeciesCatalog?
    let officialURL: URL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let info { SpeciesPictures(info: info) }
                    Text(row.species).font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    if let info { Text([info.maori, info.scientific.isEmpty ? nil : info.scientific].compactMap { $0 }.joined(separator: " · ")).foregroundStyle(.secondary) }
                    ForEach(Array(row.facts.enumerated()), id: \.offset) { item in
                        HStack {
                            Text(item.element.label).foregroundStyle(.secondary)
                            Spacer()
                            Text(item.element.value).font(.headline).foregroundStyle(CatchCheckColor.navy)
                        }
                    }
                    if let minimum = row.minimum { SizeBar(minimum: minimum) }
                    if let note = row.note { Text(note).font(.caption).foregroundStyle(.secondary) }
                    if row.minimum != nil { CheckMyCatchCard(rows: [row], catalog: catalog) }
                    Link(row.hasFootnote ? "Read the MPI footnote" : "Check the official MPI rule", destination: officialURL)
                        .buttonStyle(.bordered).frame(maxWidth: .infinity)
                    if let note = catalog?.note { Text(note).font(.caption).foregroundStyle(.secondary) }
                }
                .padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle(info?.name ?? "Species")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

/// Pick a species, type a length and see whether it meets the minimum; with one row, the species is fixed.
private struct CheckMyCatchCard: View {
    let rows: [SavedRuleQuickRow]
    let catalog: SpeciesCatalog?
    @State private var selectedID: String?
    @State private var lengthText = ""
    @FocusState private var typing: Bool
    var body: some View {
        let row = rows.first { $0.id == selectedID } ?? rows[0]
        let unit = row.minimum?.1 ?? "cm"
        let result = Double(lengthText.replacingOccurrences(of: ",", with: ".")).flatMap { checkCatch(row, length: $0) }
        Card(background: CatchCheckColor.seafoam) {
            Text("Check my catch").font(.headline).foregroundStyle(CatchCheckColor.navy)
            if rows.count > 1 {
                Picker("Species", selection: Binding(get: { row.id }, set: { selectedID = $0; lengthText = "" })) {
                    ForEach(rows) { option in
                        Text(option.species + (catalog?.forRule(option.species)?.maori.map { " · \($0)" } ?? "")).tag(option.id)
                    }
                }
                .pickerStyle(.menu).tint(CatchCheckColor.accent)
            }
            // The answer sits above the field so the keyboard never hides it.
            if let result {
                Label(result.text, systemImage: result.meets ? "checkmark.circle.fill" : "xmark.circle.fill")
                    .font(.subheadline.weight(.semibold)).foregroundStyle(result.meets ? .green : .red)
            }
            HStack {
                TextField("Length", text: $lengthText).keyboardType(.decimalPad).focused($typing)
                    .padding(10).background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 10))
                Text(unit).foregroundStyle(CatchCheckColor.navy)
            }
            .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Done") { typing = false } } }
            Text("Measure the way MPI describes for this species. Works with no signal.").font(.caption).foregroundStyle(.secondary)
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

    /// The single minimum length or size with its unit, such as (30, "cm"); nil when there is none or it varies.
    var minimum: (Double, String)? {
        guard let value = facts.first(where: { $0.label == "Minimum length" || $0.label == "Minimum size" })?.value,
              let match = value.wholeMatch(of: /(\d+(?:\.\d+)?) (cm|mm)/), let number = Double(match.1) else { return nil }
        return (number, String(match.2))
    }
    /// The daily limit as shown, such as "7" or "No take"; nil when the row has none.
    var dailyLimit: String? { facts.first { $0.label.hasPrefix("Daily limit") }?.value }
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

    /// The area's combined daily finfish limit, such as 20, when its text states one.
    var combinedFinfishLimit: Int? {
        for section in sections {
            if let match = section.text.firstMatch(of: /(?i)combined daily bag limit of\s+(\d+)\s+finfish/) { return Int(match.1) }
        }
        return nil
    }

    func allRows() -> [SavedRuleQuickRow] {
        tables.enumerated().flatMap { tableIndex, saved in
            // One of MPI's tables (Southland) has its header row last; read it as the header.
            let headerLast = saved.first?.first?.localizedCaseInsensitiveContains("species") != true
                && saved.last?.first?.trimmingCharacters(in: .whitespaces).lowercased().hasSuffix("species") == true
            let table = headerLast ? [saved[saved.count - 1]] + saved.dropLast() : saved
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
