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
            AccountView().tabItem { Label("Account", systemImage: "person.crop.circle") }.tag(5)
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
            Button { if vm.fishIdentityAvailable { vm.identifyFish() } else { vm.selectedTab = 5 } } label: { Label(vm.fishIdentityAvailable ? "Identify fish" : "Sign in for fish ID", systemImage: "checkmark.circle.fill").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent).tint(CatchCheckColor.navy).disabled(vm.selectedPhoto == nil || vm.isCheckingFish)
            if !vm.fishIdentityAvailable { Text(vm.account == nil ? "Create an account or sign in, then choose the paid plan for fish identification." : "Fish identification is included with the paid plan.").font(.caption).foregroundStyle(.secondary) }
            Text("AI suggestions are a guide. Confirm species, area and current MPI rules before keeping a fish.").font(.caption).foregroundStyle(.secondary)
        }
        .onChange(of: photoItem) { _, item in Task { guard let data = try? await item?.loadTransferable(type: Data.self), let image = UIImage(data: data) else { return }; vm.selectedPhoto = image; vm.fishCheck = nil } }
        .sheet(isPresented: $showCamera) { CameraPicker(image: $vm.selectedPhoto) }
    }
}

struct AccountView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var createAccount = true
    @State private var email = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var displayName = ""
    @State private var countryCode = "NZ"
    @State private var category = "general"
    @State private var message = ""
    @State private var rating = 5

    private var passwordsMatch: Bool { !confirmPassword.isEmpty && password == confirmPassword }
    private var canSubmit: Bool {
        guard !vm.accountBusy, !email.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        if createAccount { return password.count >= 10 && passwordsMatch }
        return !password.isEmpty
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    HStack(spacing: 14) {
                        Image(systemName: "person.crop.circle.fill.badge.checkmark")
                            .font(.system(size: 34, weight: .medium))
                            .foregroundStyle(.white)
                            .frame(width: 62, height: 62)
                            .background(CatchCheckColor.navy, in: RoundedRectangle(cornerRadius: 20))
                        VStack(alignment: .leading, spacing: 4) {
                            Text("CATCHCHECK NZ").font(.caption.weight(.bold)).tracking(1.5).foregroundStyle(CatchCheckColor.orange)
                            Text(vm.account == nil ? "Your fishing account" : "Welcome back")
                                .font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                        }
                        Spacer(minLength: 0)
                    }
                    if vm.account == nil {
                        Text("Save your profile, share feedback, and manage your CatchCheck features in one place.")
                            .font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                    if let notice = vm.accountNotice {
                        Label(notice, systemImage: "checkmark.circle.fill")
                            .font(.subheadline.weight(.medium)).foregroundStyle(CatchCheckColor.navy)
                            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 14))
                    }
                    if let error = vm.accountError {
                        Label(error, systemImage: "exclamationmark.circle.fill")
                            .font(.subheadline).foregroundStyle(.red)
                            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.red.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
                    }
                    if let account = vm.account {
                        Card(background: CatchCheckColor.seafoam) {
                            Label("\(account.plan.capitalized) plan", systemImage: "checkmark.seal.fill").font(.title3.bold()).foregroundStyle(CatchCheckColor.navy)
                            Text(vm.fishIdentityAvailable ? "Fish identification is included with your plan." : "Your core fishing tools are ready to use.").font(.subheadline).foregroundStyle(.secondary)
                        }
                        accountSection("Profile", subtitle: account.email, icon: "person.text.rectangle") {
                            AccountInput(icon: "person") { TextField("Name", text: $displayName).textContentType(.name) }
                            AccountInput(icon: "globe") {
                                TextField("Country code", text: $countryCode).textInputAutocapitalization(.characters)
                                    .onChange(of: countryCode) { _, value in countryCode = String(value.uppercased().prefix(2)) }
                            }
                            Button { vm.saveAccountProfile(displayName: displayName, countryCode: countryCode) } label: {
                                Label(vm.accountBusy ? "Saving…" : "Save profile", systemImage: "checkmark")
                                    .frame(maxWidth: .infinity)
                            }.buttonStyle(.borderedProminent).tint(CatchCheckColor.navy).disabled(vm.accountBusy)
                        }
                        accountSection("Send feedback", subtitle: "Help us make fishing trips better.", icon: "bubble.left.and.bubble.right") {
                            Picker("Type", selection: $category) { Text("General").tag("general"); Text("Report a problem").tag("bug"); Text("Idea").tag("idea") }.pickerStyle(.menu)
                            TextField("Tell us what you think", text: $message, axis: .vertical).lineLimit(4...8).textFieldStyle(.roundedBorder)
                            Picker("Rating", selection: $rating) { ForEach(1...5, id: \.self) { Text("\($0) out of 5").tag($0) } }.pickerStyle(.segmented)
                            Button { vm.sendFeedback(category: category, message: message, rating: rating); message = "" } label: {
                                Label(vm.accountBusy ? "Sending…" : "Send feedback", systemImage: "paperplane.fill")
                                    .frame(maxWidth: .infinity)
                            }.buttonStyle(.borderedProminent).tint(CatchCheckColor.navy).disabled(message.trimmingCharacters(in: .whitespacesAndNewlines).count < 3 || vm.accountBusy)
                        }
                        Button("Sign out", role: .destructive) { vm.signOut() }.disabled(vm.accountBusy).frame(maxWidth: .infinity).padding(.vertical, 8)
                    } else {
                        if vm.verificationPending {
                            Card(background: CatchCheckColor.seafoam) {
                                Label("Check your inbox", systemImage: "envelope.badge")
                                    .font(.headline).foregroundStyle(CatchCheckColor.navy)
                                Text("Open the confirmation email before signing in. The link expires after 24 hours.").font(.subheadline).foregroundStyle(.secondary)
                                Button("Resend confirmation email") { vm.resendVerification(email: email) }.disabled(vm.accountBusy)
                            }
                        }
                        VStack(alignment: .leading, spacing: 18) {
                            HStack(spacing: 8) {
                                Image(systemName: "lock.shield.fill").foregroundStyle(CatchCheckColor.orange)
                                Text("Secure access").font(.headline).foregroundStyle(CatchCheckColor.navy)
                            }
                            Text("Create an account or sign in to manage your CatchCheck profile.")
                                .font(.subheadline).foregroundStyle(.secondary)
                            Picker("Account", selection: $createAccount) {
                                Text("Create account").tag(true)
                                Text("Sign in").tag(false)
                            }
                            .pickerStyle(.segmented)
                            .onChange(of: createAccount) { _, _ in password = ""; confirmPassword = "" }
                            VStack(spacing: 12) {
                                if createAccount {
                                    AccountInput(icon: "person") { TextField("Name (optional)", text: $displayName).textContentType(.name) }
                                }
                                AccountInput(icon: "envelope") {
                                    TextField("Email address", text: $email).textContentType(.emailAddress).keyboardType(.emailAddress)
                                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                                }
                                AccountInput(icon: "lock") {
                                    SecureField(createAccount ? "Password · 10+ characters" : "Password", text: $password)
                                        .textContentType(createAccount ? .newPassword : .password)
                                }
                                if createAccount {
                                    AccountInput(icon: passwordsMatch ? "checkmark.lock" : "lock.rotation") {
                                        SecureField("Confirm password", text: $confirmPassword).textContentType(.newPassword)
                                    }
                                    if !confirmPassword.isEmpty {
                                        Label(passwordsMatch ? "Passwords match" : "Passwords don’t match",
                                              systemImage: passwordsMatch ? "checkmark.circle.fill" : "xmark.circle.fill")
                                            .font(.caption.weight(.medium))
                                            .foregroundStyle(passwordsMatch ? CatchCheckColor.navy : .red)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                    if !password.isEmpty && password.count < 10 {
                                        Text("Use at least 10 characters for your password.")
                                            .font(.caption).foregroundStyle(.secondary)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                }
                            }
                            Button {
                                vm.signIn(email: email, password: password, displayName: displayName, createAccount: createAccount)
                                password = ""
                                confirmPassword = ""
                            } label: {
                                HStack {
                                    Spacer()
                                    if vm.accountBusy { ProgressView().tint(.white) }
                                    Text(vm.accountBusy ? "Please wait…" : createAccount ? "Create my account" : "Sign in")
                                    Image(systemName: "arrow.right")
                                    Spacer()
                                }.font(.headline).padding(.vertical, 5)
                            }
                            .buttonStyle(.borderedProminent).tint(CatchCheckColor.navy)
                            .disabled(!canSubmit)
                            if !createAccount || vm.verificationPending || vm.accountError != nil {
                                Button("Resend confirmation email") { vm.resendVerification(email: email) }
                                    .font(.subheadline.weight(.semibold)).disabled(vm.accountBusy || email.isEmpty)
                            }
                        }
                        .padding(20)
                        .background(.white, in: RoundedRectangle(cornerRadius: 24))
                        .overlay(RoundedRectangle(cornerRadius: 24).stroke(CatchCheckColor.navy.opacity(0.07), lineWidth: 1))
                        .shadow(color: CatchCheckColor.navy.opacity(0.06), radius: 18, y: 8)
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: "lock.fill").foregroundStyle(CatchCheckColor.navy)
                            Text("Your password is protected. We never store it as plain text.")
                                .font(.caption).foregroundStyle(.secondary)
                        }.padding(.horizontal, 4)
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Account")
            .navigationBarTitleDisplayMode(.inline)
            .onChange(of: vm.account) { _, value in if let value { displayName = value.displayName; countryCode = value.countryCode; email = value.email } }
        }
    }

    private func accountSection<Content: View>(_ title: String, subtitle: String, icon: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: icon).foregroundStyle(CatchCheckColor.navy).frame(width: 38, height: 38).background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(CatchCheckColor.navy)
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
            }
            content()
        }
        .padding(18)
        .background(.white, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(CatchCheckColor.navy.opacity(0.07), lineWidth: 1))
    }
}

private struct AccountInput<Content: View>: View {
    let icon: String
    @ViewBuilder let content: Content

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon).foregroundStyle(CatchCheckColor.navy.opacity(0.65)).frame(width: 22)
            content
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
        .background(CatchCheckColor.cream, in: RoundedRectangle(cornerRadius: 13))
        .overlay(RoundedRectangle(cornerRadius: 13).stroke(CatchCheckColor.navy.opacity(0.09), lineWidth: 1))
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
