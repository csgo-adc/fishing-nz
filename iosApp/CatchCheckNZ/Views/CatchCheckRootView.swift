import SwiftUI
import MapKit
import PhotosUI
import UIKit

enum CatchCheckColor {
    static let navy = Color(red: 18 / 255, green: 59 / 255, blue: 67 / 255)
    static let seafoam = Color(red: 220 / 255, green: 239 / 255, blue: 231 / 255)
    static let cream = Color(red: 247 / 255, green: 248 / 255, blue: 244 / 255)
    static let orange = Color(red: 228 / 255, green: 120 / 255, blue: 74 / 255)
}

struct CatchCheckRootView: View {
    @EnvironmentObject private var vm: FishingViewModel
    var body: some View {
        TabView(selection: $vm.selectedTab) {
            HomeView().tabItem { Label("Home", systemImage: "location.north.fill") }.tag(0)
            FishingMapView().tabItem { Label("Map", systemImage: "map.fill") }.tag(1)
            TideForecastView().tabItem { Label("Tide", systemImage: "water.waves") }.tag(2)
            TripsView().tabItem { Label("Trips", systemImage: "calendar") }.tag(3)
            RulesView().tabItem { Label("Rules", systemImage: "book.closed.fill") }.tag(4)
        }
        .tint(CatchCheckColor.navy)
        .sheet(isPresented: $vm.showingResults) { ResultsView() }
        .sheet(item: $vm.selectedSpot) { SpotDetailView(spot: $0) }
    }
}

struct HomeView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var photoItem: PhotosPickerItem?
    @State private var showCamera = false
    private let dates = ["Today", "Tomorrow", "Friday", "Saturday", "Sunday", "Next 7 days"]
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 3) { Text("Kia ora, Alex").foregroundStyle(.secondary); Text("Plan your next catch").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy) }
                    Card(background: CatchCheckColor.navy) {
                        Text("What are you fishing for?").font(.title3.bold()).foregroundStyle(.white)
                        Picker("Fishing type", selection: $vm.isBoatFishing) { Text("Land fishing").tag(false); Text("Boat fishing").tag(true) }.pickerStyle(.segmented).padding(.top, 6)
                        Text("Auckland demo · \(vm.dateLabel)").foregroundStyle(.white.opacity(0.8)).padding(.top, 8)
                    }
                    Text("When are you going?").font(.headline).foregroundStyle(CatchCheckColor.navy)
                    ScrollView(.horizontal, showsIndicators: false) { HStack { ForEach(dates, id: \.self) { day in Button(day) { vm.dateLabel = day }.buttonStyle(ChoiceButton(selected: vm.dateLabel == day)) } } }
                    ActionCard(title: "Find the best time", detail: "See the best fishing window near you") { vm.showingResults = true }
                    ActionCard(title: "Find the best location", detail: "Rank spots by conditions and distance", outlined: true) { vm.showingResults = true }
                    FishIdentifierView(photoItem: $photoItem, showCamera: $showCamera)
                    Text("Quick forecast").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    Card { HStack { Metric(label: "Wind", value: vm.weather?.wind ?? "—"); Spacer(); Metric(label: "Tide", value: vm.currentTide?.nextEvent ?? "—"); Spacer(); Metric(label: "Temp", value: vm.weather?.temperature ?? "—") } }
                    Text("Your next best window").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    RecommendationCard(spot: sampleRecommendations[0]) { vm.showingResults = true }
                }.padding(20)
            }.background(CatchCheckColor.cream)
        }
    }
}

private struct FishIdentifierView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Binding var photoItem: PhotosPickerItem?
    @Binding var showCamera: Bool
    var body: some View {
        let galleryLabel = vm.selectedPhoto == nil ? "Choose photo" : "Gallery"
        Card {
            HStack { Image(systemName: "camera.fill").font(.title2).foregroundStyle(CatchCheckColor.navy).frame(width: 44, height: 44).background(CatchCheckColor.seafoam, in: Circle()); VStack(alignment: .leading) { Text("What fish is this?").font(.title3.bold()).foregroundStyle(CatchCheckColor.navy); Text("AI ID + local rules check").font(.caption).foregroundStyle(.secondary) }; Spacer() }
            if let photo = vm.selectedPhoto { Image(uiImage: photo).resizable().scaledToFill().frame(height: 160).clipShape(RoundedRectangle(cornerRadius: 12)) }
            if let result = vm.fishCheck { FishCheckCard(result: result) }
            if vm.isCheckingFish { ProgressView("Checking the photo…").tint(CatchCheckColor.orange) }
            if let error = vm.error { Text(error).font(.caption).foregroundStyle(.red) }
            HStack { Button { showCamera = true } label: { Label("Take photo", systemImage: "camera") }.buttonStyle(.bordered).frame(maxWidth: .infinity); PhotosPicker(selection: $photoItem, matching: .images) { Label(galleryLabel, systemImage: "photo") }.buttonStyle(.bordered).frame(maxWidth: .infinity) }
            Button { vm.identifyFish() } label: { Label("Identify fish", systemImage: "checkmark.circle.fill").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent).tint(CatchCheckColor.navy).disabled(vm.selectedPhoto == nil || vm.isCheckingFish)
            Text("AI suggestions are a guide. Confirm species, area and current MPI rules before keeping a fish.").font(.caption).foregroundStyle(.secondary)
        }
        .onChange(of: photoItem) { _, item in Task { guard let data = try? await item?.loadTransferable(type: Data.self), let image = UIImage(data: data) else { return }; vm.selectedPhoto = image; vm.fishCheck = nil } }
        .sheet(isPresented: $showCamera) { CameraPicker(image: $vm.selectedPhoto) }
    }
}

struct ResultsView: View {
    @EnvironmentObject private var vm: FishingViewModel
    var body: some View { NavigationStack { List { Section { ForEach(sampleRecommendations.filter { !vm.isBoatFishing || $0.boat }) { spot in RecommendationCard(spot: spot) { vm.showingResults = false; vm.selectedSpot = spot }.listRowInsets(EdgeInsets()) } } footer: { Text("Safety and fishing rules always override the score.") } }.listStyle(.plain).navigationTitle("Best options").toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { vm.showingResults = false } } } } }
}

struct SpotDetailView: View {
    @EnvironmentObject private var vm: FishingViewModel
    let spot: Recommendation
    var body: some View { NavigationStack { ScrollView { VStack(alignment: .leading, spacing: 16) { Text(spot.name).font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy); Text(spot.area).foregroundStyle(.secondary); Text("\(spot.rating)/100 · \(spot.time)").font(.title3.bold()).foregroundStyle(CatchCheckColor.orange); Card(background: CatchCheckColor.seafoam) { Text("Why this spot?").bold().foregroundStyle(CatchCheckColor.navy); ForEach(spot.reasons, id: \.self) { Text("✓  \($0)") } }; Button(vm.savedSpotNames.contains(spot.name) ? "Remove saved spot" : "Save spot") { vm.toggleSaved(spot) }.buttonStyle(.bordered).frame(maxWidth: .infinity); Button("Start fishing trip") { vm.startTrip(spot) }.buttonStyle(.borderedProminent).tint(CatchCheckColor.navy).frame(maxWidth: .infinity) }.padding(20) }.background(CatchCheckColor.cream).navigationTitle("Spot details").navigationBarTitleDisplayMode(.inline).toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { vm.selectedSpot = nil } } } } }
}

private struct ActionCard: View { let title: String; let detail: String; var outlined = false; let action: () -> Void; var body: some View { Button(action: action) { HStack { Image(systemName: "location.fill").foregroundStyle(CatchCheckColor.navy).frame(width: 44, height: 44).background(outlined ? CatchCheckColor.seafoam : .white, in: Circle()); VStack(alignment: .leading) { Text(title).bold(); Text(detail).font(.caption).foregroundStyle(.secondary) }; Spacer() }.foregroundStyle(CatchCheckColor.navy).padding(16).frame(maxWidth: .infinity, alignment: .leading).background(outlined ? .white : CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 18)) } } }
struct RecommendationCard: View { let spot: Recommendation; let action: () -> Void; var body: some View { Button(action: action) { VStack(alignment: .leading, spacing: 6) { HStack { VStack(alignment: .leading) { Text(spot.name).font(.headline); Text(spot.area).foregroundStyle(.secondary) }; Spacer(); Text("\(spot.rating)/100").bold().foregroundStyle(CatchCheckColor.orange) }; Text(spot.time).bold(); Text(spot.distance).font(.caption).foregroundStyle(.secondary); HStack { ForEach(spot.reasons, id: \.self) { Text($0).font(.caption2).padding(7).background(CatchCheckColor.seafoam, in: Capsule()) } } }.foregroundStyle(CatchCheckColor.navy).padding(16).frame(maxWidth: .infinity, alignment: .leading).background(.white, in: RoundedRectangle(cornerRadius: 18)) } } }
private struct FishCheckCard: View {
    let result: FishCheck
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text(result.commonName).bold(); Spacer(); Text("\(result.confidence)% match").bold().foregroundStyle(CatchCheckColor.orange) }
            Text(result.scientificName).foregroundStyle(.secondary)
            HStack {
                Text("MPI rules · \(result.areaName)").font(.subheadline.bold())
                Spacer()
                if let reviewed = result.rulesReviewedAt { Text("Reviewed \(reviewed)").font(.caption).foregroundStyle(.secondary) }
            }
            if result.fishRules.isEmpty {
                Text("No species-specific size or catch-limit entry was found in the saved rules for this area. Check local closures and restrictions before keeping this fish.")
                    .font(.subheadline).foregroundStyle(.secondary).padding(12).frame(maxWidth: .infinity, alignment: .leading).background(.white, in: RoundedRectangle(cornerRadius: 10))
            } else {
                ForEach(result.fishRules) { rule in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(rule.species).font(.subheadline.bold())
                        if let minimumSize = rule.minimumSize { Text("Minimum size: \(minimumSize)") }
                        if let dailyLimit = rule.dailyLimit { Text("Daily limit: \(dailyLimit)") }
                        ForEach(rule.details) { detail in Text("\(detail.label): \(detail.value)") }
                    }
                    .font(.subheadline).foregroundStyle(CatchCheckColor.navy).padding(12).frame(maxWidth: .infinity, alignment: .leading).background(.white, in: RoundedRectangle(cornerRadius: 10))
                }
            }
            Text(result.areaIsEstimated ? "Fishing area is estimated because device location was unavailable. Confirm the area where you are fishing." : "Area selected from current device location. Confirm the exact fishing location.")
                .font(.caption).foregroundStyle(.secondary)
            Text("Check local closures and current MPI rules before keeping a fish.").font(.caption).foregroundStyle(.secondary)
        }.foregroundStyle(CatchCheckColor.navy).padding(14).background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 14))
    }
}
struct Card<Content: View>: View { var background: Color = .white; @ViewBuilder let content: Content; var body: some View { VStack(alignment: .leading, spacing: 10, content: { content }).padding(18).frame(maxWidth: .infinity, alignment: .leading).background(background, in: RoundedRectangle(cornerRadius: 18)) } }
private struct Metric: View { let label: String; let value: String; var body: some View { VStack(alignment: .leading) { Text(label).font(.caption).foregroundStyle(.secondary); Text(value).bold().foregroundStyle(CatchCheckColor.navy) } } }
struct ChoiceButton: ButtonStyle { let selected: Bool; func makeBody(configuration: Configuration) -> some View { configuration.label.padding(.horizontal, 12).padding(.vertical, 8).background(selected ? CatchCheckColor.navy : .white, in: Capsule()).foregroundStyle(selected ? .white : CatchCheckColor.navy).opacity(configuration.isPressed ? 0.7 : 1) } }

struct CameraPicker: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    @Environment(\.dismiss) private var dismiss
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIViewController(context: Context) -> UIImagePickerController { let picker = UIImagePickerController(); picker.sourceType = .camera; picker.delegate = context.coordinator; picker.cameraCaptureMode = .photo; return picker }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate { let parent: CameraPicker; init(parent: CameraPicker) { self.parent = parent }; func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { parent.image = info[.originalImage] as? UIImage; parent.dismiss() }; func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() } }
}
