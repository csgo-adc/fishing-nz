import SwiftUI
import MapKit
import PhotosUI
import UIKit

enum CatchCheckColor {
    private static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 0xFF) / 255,
                           green: CGFloat((value >> 8) & 0xFF) / 255,
                           blue: CGFloat(value & 0xFF) / 255, alpha: 1)
        })
    }

    static let navy = adaptive(0x17263F, 0xEAF1FC)
    static let seafoam = adaptive(0xEAF1FF, 0x1D2B42)
    static let cream = adaptive(0xF5F7FB, 0x0D1523)
    static let orange = adaptive(0x2867D9, 0x85AEFF)
    static let surface = adaptive(0xFFFFFF, 0x172235)
    static let outline = adaptive(0xDDE5F0, 0x30415C)
    static let hero = adaptive(0x183862, 0x1B3557)
    static let accent = adaptive(0x2867D9, 0x6197F5)
}

private enum MoreDestination: String, Identifiable {
    case trips, rules, account, settings
    var id: String { rawValue }
}

struct CatchCheckRootView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @AppStorage("catchcheckAppearance") private var appearance = "system"
    @State private var moreDestination: MoreDestination?

    private var preferredAppearance: ColorScheme? {
        switch appearance {
        case "light": .light
        case "dark": .dark
        default: nil
        }
    }

    var body: some View {
        TabView(selection: $vm.selectedTab) {
            HomeView(openAccount: { moreDestination = .account }).tabItem { Label("Home", systemImage: "house.fill") }.tag(0)
            FishingMapView().tabItem { Label("Map", systemImage: "map.fill") }.tag(1)
            TideForecastView().tabItem { Label("Tide", systemImage: "water.waves") }.tag(2)
            MoreView(open: { moreDestination = $0 }).tabItem { Label("More", systemImage: "square.grid.2x2.fill") }.tag(3)
        }
        .tint(CatchCheckColor.accent)
        .preferredColorScheme(preferredAppearance)
        .fullScreenCover(isPresented: $vm.showingResults) { ResultsView() }
        .sheet(item: $vm.selectedSpot) { SpotDetailView(spot: $0) }
        .sheet(item: $moreDestination) { destination in
            switch destination {
            case .trips: TripsView()
            case .rules: RulesView()
            case .account: AccountView()
            case .settings: SettingsView()
            }
        }
        .task { vm.requestLocation() }
    }
}

private struct MoreView: View {
    @EnvironmentObject private var vm: FishingViewModel
    let open: (MoreDestination) -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("More").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text("Your account, trips and fishing tools in one place.")
                        .foregroundStyle(.secondary)
                    VStack(spacing: 10) {
                        MoreNavigationRow(title: "Account", subtitle: vm.account?.email ?? "Sign in and manage your profile", icon: "person.crop.circle") { open(.account) }
                    }
                    .padding(.bottom, 6)
                    VStack(spacing: 10) {
                        MoreNavigationRow(title: "Trips", subtitle: "Saved spots and active plans", icon: "calendar") { open(.trips) }
                        MoreNavigationRow(title: "Fishing rules", subtitle: "Sizes, limits and local restrictions", icon: "book.closed") { open(.rules) }
                    }
                    .padding(.bottom, 6)
                    VStack(spacing: 10) {
                        MoreNavigationRow(title: "Settings", subtitle: "Appearance, feedback, terms and privacy", icon: "gearshape") { open(.settings) }
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
        }
    }

}

private struct MoreNavigationRow: View {
    let title: String
    let subtitle: String
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MoreNavigationLabel(title: title, subtitle: subtitle, icon: icon)
        }
        .buttonStyle(.plain)
    }
}

private struct MoreNavigationLabel: View {
    let title: String
    let subtitle: String
    let icon: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.title3).foregroundStyle(CatchCheckColor.accent)
                .frame(width: 44, height: 44)
                .background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 13))
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline).foregroundStyle(CatchCheckColor.navy)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.caption.bold()).foregroundStyle(.secondary)
        }
        .padding(16)
        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Settings").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                    NavigationLink { TermsPrivacyView() } label: {
                        MoreNavigationLabel(title: "Terms & privacy", subtitle: "How the app works and uses your information", icon: "doc.text")
                    }
                    NavigationLink { FeedbackView() } label: {
                        MoreNavigationLabel(title: "Feedback", subtitle: "Report a problem or share an idea", icon: "bubble.left")
                    }
                    NavigationLink { AppearanceView() } label: {
                        MoreNavigationLabel(title: "Appearance", subtitle: "Light, dark or device setting", icon: "circle.lefthalf.filled")
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

private struct AppearanceView: View {
    @AppStorage("catchcheckAppearance") private var appearance = "system"

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Appearance").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                Card {
                    Picker("Appearance", selection: $appearance) {
                        Text("System").tag("system")
                        Text("Light").tag("light")
                        Text("Dark").tag("dark")
                    }
                    .pickerStyle(.segmented)
                    Text("System follows your device setting.").font(.subheadline).foregroundStyle(.secondary)
                }
            }.padding(20)
        }
        .background(CatchCheckColor.cream)
        .navigationTitle("Appearance")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct TermsPrivacyView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Terms & privacy").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                Text("A short guide to using CatchCheck NZ and understanding the information it uses.")
                    .foregroundStyle(.secondary)
                Card {
                    Text("Terms of use").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text("Fishing windows, tide times, fish identification and rule summaries help you plan. They can be incomplete or out of date. Check the current MPI rules and local access conditions before fishing.")
                    Text("You are responsible for following applicable fishing and access rules.")
                }
                Card {
                    Text("Privacy").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text("If you allow location access, the app uses your position to centre the map and find nearby information. You can change location permission in your phone’s settings.")
                    Text("If you choose a fish photo, it is sent through CatchCheck’s service to an image analysis provider for identification.")
                    Text("If you sign in or send feedback, your account details and feedback are sent to CatchCheck’s service. Signing out removes the saved session from this device.")
                }
            }.padding(20)
        }
        .background(CatchCheckColor.cream)
        .navigationTitle("Terms & privacy")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct FeedbackView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var category = "general"
    @State private var message = ""
    @State private var rating = 5
    @State private var showingAccount = false
    @State private var didSubmit = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Feedback").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                Text("Report a problem or tell us what would improve your fishing trips.")
                    .foregroundStyle(.secondary)
                if vm.account == nil {
                    Card {
                        Text("Sign in to send feedback").font(.title3.bold()).foregroundStyle(CatchCheckColor.navy)
                        Text("Feedback is linked to your CatchCheck account so we can review it.")
                            .foregroundStyle(.secondary)
                        if vm.accountLoading {
                            ProgressView("Checking your account…")
                        } else {
                            Button("Go to account") { showingAccount = true }
                                .buttonStyle(.borderedProminent).tint(CatchCheckColor.accent)
                        }
                    }
                } else {
                    if vm.accountNotice == "Thanks for your feedback." {
                        Label("Thanks for your feedback.", systemImage: "checkmark.circle.fill")
                            .foregroundStyle(CatchCheckColor.navy)
                            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 14))
                    }
                    if didSubmit, let error = vm.accountError {
                        Label(error, systemImage: "exclamationmark.circle.fill")
                            .foregroundStyle(.red)
                            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.red.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
                    }
                    Card {
                        Picker("Type", selection: $category) {
                            Text("General").tag("general")
                            Text("Report a problem").tag("bug")
                            Text("Idea").tag("idea")
                        }.pickerStyle(.menu)
                        TextField("Tell us what you think", text: $message, axis: .vertical)
                            .lineLimit(4...8).textFieldStyle(.roundedBorder)
                            .onChange(of: message) { _, value in message = String(value.prefix(4000)) }
                        Picker("Rating", selection: $rating) {
                            ForEach(1...5, id: \.self) { Text("\($0) out of 5").tag($0) }
                        }.pickerStyle(.segmented)
                        Button {
                            let submittedMessage = message
                            didSubmit = true
                            Task { @MainActor in
                                if await vm.sendFeedback(category: category, message: submittedMessage, rating: rating), message == submittedMessage {
                                    message = ""
                                }
                            }
                        } label: {
                            Label(vm.accountBusy ? "Sending…" : "Send feedback", systemImage: "paperplane.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent).tint(CatchCheckColor.accent)
                        .disabled(message.trimmingCharacters(in: .whitespacesAndNewlines).count < 3 || vm.accountBusy)
                    }
                }
            }.padding(20)
        }
        .background(CatchCheckColor.cream)
        .navigationTitle("Feedback")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingAccount) { AccountView() }
    }
}

struct HomeView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var photoItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var showingPlan = false
    let openAccount: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(vm.account?.displayName.split(separator: " ").first.map { "Kia ora, \($0)" } ?? "Kia ora")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Text("Plan your next catch")
                            .font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                    }
                    Card(background: CatchCheckColor.seafoam) {
                        Button { showingPlan = true } label: {
                            VStack(alignment: .leading, spacing: 12) {
                                HStack(spacing: 12) {
                                    Image(systemName: "slider.horizontal.3")
                                        .font(.title3).foregroundStyle(CatchCheckColor.accent)
                                        .frame(width: 42, height: 42)
                                        .background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 12))
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Your fishing plan").font(.headline).foregroundStyle(CatchCheckColor.navy)
                                        Text("Tap to change your options").font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: "chevron.right").font(.caption.bold()).foregroundStyle(.secondary)
                                }
                                Text("\(vm.isBoatFishing ? "Boat" : "Land") · \(vm.dateSummary) · \(vm.timeSummary)\(vm.selectedSearchStationID == nil ? " · \(vm.radiusKm) km" : "")")
                                    .font(.subheadline.weight(.medium)).foregroundStyle(CatchCheckColor.navy)
                                    .lineLimit(2)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Edit fishing plan: \(vm.isBoatFishing ? "boat" : "land"), \(vm.dateSummary), \(vm.timeSummary), \(vm.locationSummary)")
                        Button { vm.showRecommendations() } label: {
                            Text("Find fishing windows")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 5)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(CatchCheckColor.accent)
                        Divider().padding(.top, 8)
                        SearchPlaceMenu()
                            .padding(.top, 8)
                            .padding(.bottom, 10)
                    }
                    Text("Quick forecast").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    Card { HStack { Metric(label: "Wind", value: vm.weather?.wind ?? "—"); Spacer(); Metric(label: "Tide", value: vm.currentTide?.nextEvent ?? "—"); Spacer(); Metric(label: "Temp", value: vm.weather?.temperature ?? "—") } }
                    if let conditionsError = vm.conditionsError { Text(conditionsError).font(.caption).foregroundStyle(.secondary) }
                    FishIdentifierView(photoItem: $photoItem, showCamera: $showCamera, openAccount: openAccount)
                    Text("Your next best window").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    if let best = vm.recommendations.first {
                        RecommendationCard(spot: best) { vm.showingResults = true }
                    } else {
                        Card { Text("Choose a distance and dates, then search for live fishing windows.").foregroundStyle(.secondary) }
                    }
                    Text("Forecast scores are estimates. Check local hazards and fishing rules before you go.")
                        .font(.caption).foregroundStyle(.secondary)
                }.padding(20)
            }.background(CatchCheckColor.cream)
            .sheet(isPresented: $showingPlan) {
                PlanningSheet()
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
    }
}

private struct PlanningSheet: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    private let radii = [10, 30, 50, 100, 200, 300, 400, 500]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Card {
                        Text("Fishing from").font(.headline).foregroundStyle(CatchCheckColor.navy)
                        Picker("Fishing type", selection: $vm.isBoatFishing) {
                            Text("Land").tag(false)
                            Text("Boat").tag(true)
                        }.pickerStyle(.segmented)
                    }
                    Card {
                        Text("When").font(.headline).foregroundStyle(CatchCheckColor.navy)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(FishingDatePreset.allCases, id: \.self) { preset in
                                    Button(preset.rawValue) { vm.datePreset = preset }
                                        .buttonStyle(ChoiceButton(selected: vm.datePreset == preset))
                                }
                            }
                        }
                        if vm.datePreset == .custom {
                            DatePicker("From", selection: $vm.customStartDate,
                                       in: vm.firstSelectableDate...vm.latestSelectableDate,
                                       displayedComponents: .date)
                                .environment(\.timeZone, TimeZone(identifier: "Pacific/Auckland")!)
                                .onChange(of: vm.customStartDate) { _, start in
                                    if vm.customEndDate < start { vm.customEndDate = start }
                                }
                            DatePicker("To", selection: $vm.customEndDate,
                                       in: vm.customStartDate...vm.latestSelectableDate,
                                       displayedComponents: .date)
                                .environment(\.timeZone, TimeZone(identifier: "Pacific/Auckland")!)
                        }
                        Text("Choose dates within the next 16 days.").font(.caption).foregroundStyle(.secondary)
                    }
                    Card {
                        Text("Preferred time").font(.headline).foregroundStyle(CatchCheckColor.navy)
                        Picker("Preferred time", selection: $vm.timeMode) {
                            ForEach(FishingTimeMode.allCases, id: \.self) { mode in
                                Text(mode.rawValue).tag(mode)
                            }
                        }.pickerStyle(.segmented)
                        if vm.timeMode == .custom {
                            Picker("From", selection: $vm.preferredStartMinute) {
                                ForEach(0..<48, id: \.self) { step in
                                    Text(FishingViewModel.clockLabel(minutes: step * 30)).tag(step * 30)
                                }
                            }.pickerStyle(.menu)
                            Picker("Until", selection: $vm.preferredEndMinute) {
                                ForEach(0..<48, id: \.self) { step in
                                    Text(FishingViewModel.clockLabel(minutes: step * 30)).tag(step * 30)
                                }
                            }.pickerStyle(.menu)
                            Text(vm.preferredStartMinute > vm.preferredEndMinute
                                 ? "This range continues after midnight."
                                 : "Only complete 2–3 hour windows within these hours appear.")
                                .font(.caption).foregroundStyle(.secondary)
                        } else if vm.timeMode == .comfortable {
                            Text("Suggestions fit between 7 AM and 9 PM.")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Card {
                        Text("Search from").font(.headline).foregroundStyle(CatchCheckColor.navy)
                        SearchPlaceMenu()
                        Text(vm.selectedSearchStationID == nil
                             ? "Choose your current location to search for fishing windows within a radius, or select one tide location."
                             : "Fishing windows are calculated at this tide location. The search radius does not apply.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    if vm.selectedSearchStationID == nil {
                        Card {
                            Text("Search radius").font(.headline).foregroundStyle(CatchCheckColor.navy)
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(radii, id: \.self) { radius in
                                        Button("\(radius) km") { vm.radiusKm = radius }
                                            .buttonStyle(ChoiceButton(selected: vm.radiusKm == radius))
                                    }
                                }
                            }
                        }
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Fishing plan")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

private struct FishIdentifierView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Binding var photoItem: PhotosPickerItem?
    @Binding var showCamera: Bool
    let openAccount: () -> Void
    var body: some View {
        let galleryLabel = vm.selectedPhoto == nil ? "Choose photo" : "Gallery"
        Card {
            HStack { Image(systemName: "camera.fill").font(.title2).foregroundStyle(CatchCheckColor.navy).frame(width: 44, height: 44).background(CatchCheckColor.seafoam, in: Circle()); VStack(alignment: .leading) { Text("What fish is this?").font(.title3.bold()).foregroundStyle(CatchCheckColor.navy); Text("AI ID + local rules check").font(.caption).foregroundStyle(.secondary) }; Spacer() }
            if let photo = vm.selectedPhoto { Image(uiImage: photo).resizable().scaledToFill().frame(height: 160).clipShape(RoundedRectangle(cornerRadius: 12)) }
            if let result = vm.fishCheck { FishCheckCard(result: result) }
            if vm.isCheckingFish { ProgressView("Checking the photo…").tint(CatchCheckColor.orange) }
            if let error = vm.error { Text(error).font(.caption).foregroundStyle(.red) }
            HStack(spacing: 10) {
                Button { showCamera = true } label: {
                    Label("Take photo", systemImage: "camera")
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Label(galleryLabel, systemImage: "photo")
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
            }
            Menu {
                Button {
                    vm.useCurrentRulesArea()
                } label: {
                    Label("Use current location", systemImage: "location.fill")
                }
                Divider()
                ForEach(fishingRulesAreas) { area in
                    Button(area.name) { vm.selectRulesArea(area.id) }
                }
            } label: {
                HStack {
                    Label("MPI rules area", systemImage: "map")
                    Spacer()
                    Text(fishingRulesAreas.first(where: { $0.id == vm.selectedRulesAreaID })?.name ?? "Choose for local limits")
                    Image(systemName: "chevron.down")
                }.font(.subheadline).foregroundStyle(CatchCheckColor.navy)
            }
            Text(vm.hasDeviceLocation
                 ? "Suggested from your current location. Choose the area where you caught the fish for local size and daily limits."
                 : "Choose the area where you caught the fish for local size and daily limits.")
                .font(.caption).foregroundStyle(.secondary)
            Button {
                if vm.fishIdentityAvailable { vm.identifyFish() }
                else if vm.hasStoredSession { vm.refreshAccount() }
                else { openAccount() }
            } label: {
                Label(vm.fishIdentityAvailable ? "Identify fish" : vm.accountLoading ? "Checking account…" : vm.hasStoredSession ? "Retry account check" : "Sign in to identify fish", systemImage: "checkmark.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).tint(CatchCheckColor.accent)
            .disabled(vm.isCheckingFish || vm.accountLoading || (vm.fishIdentityAvailable && vm.selectedPhoto == nil))
            if !vm.fishIdentityAvailable {
                Text(vm.accountLoading ? "Checking your account access."
                     : vm.hasStoredSession ? "Your saved session is still on this device, but account access could not be checked."
                     : "Sign in to use fish identification.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Text("AI suggestions are a guide. Confirm species, area and current MPI rules before keeping a fish.").font(.caption).foregroundStyle(.secondary)
        }
        .onChange(of: photoItem) { _, item in Task { guard let data = try? await item?.loadTransferable(type: Data.self), let image = UIImage(data: data) else { return }; vm.selectedPhoto = image; vm.fishCheck = nil } }
        .sheet(isPresented: $showCamera) { CameraPicker(image: $vm.selectedPhoto) }
    }
}

struct AccountView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var createAccount = false
    @State private var email = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var displayName = ""
    @State private var countryCode = "NZ"

    private var passwordsMatch: Bool { !confirmPassword.isEmpty && password == confirmPassword }
    private var isEmailValid: Bool {
        email.trimmingCharacters(in: .whitespacesAndNewlines)
            .range(of: #"^[^\s@]+@[^\s@]+\.[^\s@]+$"#, options: .regularExpression) != nil
    }
    private var canSubmit: Bool {
        guard !vm.accountBusy, isEmailValid else { return false }
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
                            .background(CatchCheckColor.hero, in: RoundedRectangle(cornerRadius: 20))
                        VStack(alignment: .leading, spacing: 4) {
                            Text("CATCHCHECK NZ").font(.caption.weight(.bold)).tracking(1.5).foregroundStyle(CatchCheckColor.orange)
                            Text(vm.account == nil ? "Your fishing account" : "Welcome back")
                                .font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                        }
                        Spacer(minLength: 0)
                    }
                    if vm.account == nil {
                        Text("Save your profile and manage your CatchCheck account in one place.")
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
                    if vm.accountLoadFailed {
                        Button("Check account again") { vm.refreshAccount() }
                            .buttonStyle(.bordered)
                    }
                    if vm.accountLoading {
                        Card { ProgressView("Checking your account…") }
                    } else if let account = vm.account {
                        Card(background: CatchCheckColor.seafoam) {
                            Label("Signed in", systemImage: "checkmark.seal.fill").font(.title3.bold()).foregroundStyle(CatchCheckColor.navy)
                            Text("Fish identification and your fishing tools are ready.").font(.subheadline).foregroundStyle(.secondary)
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
                            }.buttonStyle(.borderedProminent).tint(CatchCheckColor.accent).disabled(vm.accountBusy)
                        }
                        Button("Sign out", role: .destructive) { vm.signOut() }.disabled(vm.accountBusy).frame(maxWidth: .infinity).padding(.vertical, 8)
                    } else if vm.hasStoredSession {
                        Card {
                            Text("Session saved").font(.headline).foregroundStyle(CatchCheckColor.navy)
                            Text("Your sign-in is saved, but we could not check account access. Check your connection and try again.")
                                .font(.subheadline).foregroundStyle(.secondary)
                            Button("Retry account check") { vm.refreshAccount() }.buttonStyle(.borderedProminent)
                        }
                    } else {
                        if vm.verificationPending {
                            Card(background: CatchCheckColor.seafoam) {
                                Label("Check your inbox", systemImage: "envelope.badge")
                                    .font(.headline).foregroundStyle(CatchCheckColor.navy)
                                Text("Open the confirmation email before signing in. The link expires after 24 hours.").font(.subheadline).foregroundStyle(.secondary)
                                Button("Resend confirmation email") { vm.resendVerification(email: email) }.disabled(vm.accountBusy || !isEmailValid)
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
                                Task { @MainActor in
                                    if await vm.signIn(email: email, password: password, displayName: displayName, createAccount: createAccount) {
                                        password = ""
                                        confirmPassword = ""
                                    }
                                }
                            } label: {
                                HStack {
                                    Spacer()
                                    if vm.accountBusy { ProgressView().tint(.white) }
                                    Text(vm.accountBusy ? "Please wait…" : createAccount ? "Create my account" : "Sign in")
                                    Image(systemName: "arrow.right")
                                    Spacer()
                                }.font(.headline).padding(.vertical, 5)
                            }
                            .buttonStyle(.borderedProminent).tint(CatchCheckColor.accent)
                            .disabled(!canSubmit)
                            if !vm.verificationPending && vm.accountError != nil {
                                Button("Resend confirmation email") { vm.resendVerification(email: email) }
                                    .font(.subheadline.weight(.semibold)).disabled(vm.accountBusy || !isEmailValid)
                            }
                        }
                        .padding(20)
                        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 24))
                        .overlay(RoundedRectangle(cornerRadius: 24).stroke(CatchCheckColor.outline, lineWidth: 1))
                        .shadow(color: .black.opacity(0.06), radius: 18, y: 8)
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
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .onChange(of: vm.account) { _, value in if let value { displayName = value.displayName; countryCode = value.countryCode; email = value.email } }
            .onChange(of: vm.verificationPending) { _, pending in if pending { createAccount = false } }
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
        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(CatchCheckColor.outline, lineWidth: 1))
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
    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("\(vm.isBoatFishing ? "Boat" : "Land") fishing · \(vm.dateSummary)").font(.headline)
                        Text(vm.selectedSearchStationID == nil
                             ? "\(vm.timeSummary) · within \(vm.radiusKm) km"
                             : vm.timeSummary)
                        Text(vm.locationSummary)
                    }.font(.subheadline).foregroundStyle(.secondary)
                    if vm.isResolvingRecommendationLocation {
                        ProgressView("Getting your current location…").frame(maxWidth: .infinity).padding(.vertical, 28)
                    } else if let locationError = vm.recommendationLocationError {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(locationError).foregroundStyle(.red)
                            Text("Choose a tide location in your fishing plan on Home, then search again.")
                                .foregroundStyle(.secondary)
                        }.padding(.vertical, 14)
                    } else if vm.isLoadingRecommendations {
                        ProgressView("Comparing hourly forecasts…").frame(maxWidth: .infinity).padding(.vertical, 28)
                    } else if let error = vm.recommendationError {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(error).foregroundStyle(.red)
                            Button("Try again") { vm.searchRecommendations() }.buttonStyle(.bordered)
                        }.padding(.vertical, 14)
                    } else if vm.recommendations.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(vm.nearbySpotCount == 0
                                 ? "No catalogued \(vm.isBoatFishing ? "boat" : "land") areas are within this radius."
                                 : "No suitable 2–3 hour forecast window fits these dates, hours and conditions.")
                                .foregroundStyle(.secondary)
                            if vm.nearbySpotCount == 0, let hint = vm.nearestSpotHint {
                                Text(hint).font(.subheadline).foregroundStyle(.secondary)
                            }
                            if vm.nearbySpotCount > 0, vm.datePreset == .today {
                                Text("Today may have too little time left for a 2–3 hour window. Try another date in your fishing plan.")
                                    .font(.subheadline).foregroundStyle(.secondary)
                            }
                            if !vm.nearbyPlacesWithoutWindows.isEmpty {
                                Text("Nearby areas checked: \(vm.nearbyPlacesWithoutWindows.prefix(5).joined(separator: ", ")).")
                                    .font(.subheadline).foregroundStyle(.secondary)
                            }
                        }.padding(.vertical, 14)
                    } else {
                        ForEach(vm.recommendations) { spot in
                            RecommendationCard(spot: spot) { vm.showingResults = false; vm.selectedSpot = spot }
                                .listRowInsets(EdgeInsets())
                        }
                        if !vm.nearbyPlacesWithoutWindows.isEmpty {
                            Text("No suitable scored window at: \(vm.nearbyPlacesWithoutWindows.prefix(5).joined(separator: ", ")).")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Scores compare forecast conditions, not fish abundance. Check local hazards, marine forecasts and current fishing rules before you go.")
                        Link("Forecast data: Open-Meteo", destination: URL(string: "https://open-meteo.com/")!)
                    }
                }
            }
            .listStyle(.plain)
            .navigationTitle("Fishing windows")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { vm.showingResults = false } } }
        }
    }
}

private struct SearchPlaceMenu: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var showingPicker = false

    var body: some View {
        Button { showingPicker = true } label: {
            HStack(spacing: 10) {
                Image(systemName: "location.fill")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Search from").font(.caption).foregroundStyle(.secondary)
                    Text(vm.homeCityLabel).font(.headline).foregroundStyle(CatchCheckColor.navy)
                }
                Spacer()
                Image(systemName: "chevron.down").font(.caption.bold())
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Search location: \(vm.homeCityLabel). Change location")
        .sheet(isPresented: $showingPicker) { SearchStationPicker() }
    }
}

private struct SearchStationPicker: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var matchingStations: [TideStation] {
        let sorted = tideStations.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        guard !query.isEmpty else { return sorted }
        return sorted.filter { $0.name.localizedStandardContains(query) || $0.region.localizedStandardContains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        vm.selectSearchStation(nil)
                        dismiss()
                    } label: {
                        HStack {
                            Label(vm.hasDeviceLocation ? "Current location · \(vm.devicePlaceName ?? "near me")" : "Use current location", systemImage: "location.fill")
                            Spacer()
                            if vm.selectedSearchStationID == nil { Image(systemName: "checkmark") }
                        }
                    }
                } footer: {
                    Text("A tide station provides the centre for your fishing window search. You can change it any time.")
                }
                Section("Tide locations") {
                    ForEach(matchingStations) { station in
                        Button {
                            vm.selectSearchStation(station)
                            dismiss()
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(station.name)
                                    Text(station.region).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if vm.selectedSearchStationID == station.id { Image(systemName: "checkmark") }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Search tide locations")
            .navigationTitle("Search location")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

struct SpotDetailView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var addToCalendar = false
    @State private var showingCalendarEditor = false
    let spot: Recommendation
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(spot.name).font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text(spot.area).foregroundStyle(.secondary)
                    Text("Area marker only. Check public access, safe conditions and any local fishing closures before going.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    if spot.rating > 0 {
                        Text("\(spot.rating)/100 · \(spot.time)").font(.title3.bold()).foregroundStyle(CatchCheckColor.orange)
                        Text(spot.distance).font(.subheadline).foregroundStyle(.secondary)
                        Card(background: CatchCheckColor.seafoam) {
                            Text("Why this window?").bold().foregroundStyle(CatchCheckColor.navy)
                            ForEach(spot.reasons, id: \.self) { Text("✓  \($0)") }
                        }
                        if !spot.warnings.isEmpty {
                            Card { ForEach(spot.warnings, id: \.self) { warning in Label(warning, systemImage: "exclamationmark.triangle").font(.subheadline) } }
                        }
                    } else {
                        Card { Text("Search from Home to see a live forecast score for this spot.").foregroundStyle(.secondary) }
                    }
                    Button(vm.savedSpotNames.contains(spot.id) ? "Remove saved spot" : "Save spot") { vm.toggleSaved(spot) }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                    Toggle("Add this trip to my calendar", isOn: $addToCalendar)
                    if addToCalendar && spot.startsAt == nil {
                        Text("Choose the date and time in your calendar.").font(.caption).foregroundStyle(.secondary)
                    }
                    Button("Start fishing trip") {
                        if addToCalendar { showingCalendarEditor = true }
                        else { vm.startTrip(spot); dismiss() }
                    }.buttonStyle(.borderedProminent).tint(CatchCheckColor.accent).frame(maxWidth: .infinity)
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Spot details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { vm.selectedSpot = nil; dismiss() } } }
            .sheet(isPresented: $showingCalendarEditor, onDismiss: {
                vm.startTrip(spot)
                dismiss()
            }) { TripCalendarEditor(trip: spot) }
        }
    }
}

struct RecommendationCard: View {
    let spot: Recommendation
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 7) {
                HStack {
                    VStack(alignment: .leading) {
                        Text(spot.name).font(.headline)
                        Text(spot.area).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if spot.rating > 0 { Text("\(spot.rating)/100").bold().foregroundStyle(CatchCheckColor.orange) }
                }
                if spot.rating > 0 { Text(spot.time).bold() }
                Text(spot.distance).font(.caption).foregroundStyle(.secondary)
                if !spot.reasons.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack { ForEach(spot.reasons.prefix(3), id: \.self) { reason in Text(reason).font(.caption2).padding(7).background(CatchCheckColor.seafoam, in: Capsule()) } }
                    }
                }
                if !spot.warnings.isEmpty {
                    Label(spot.warnings[0], systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(CatchCheckColor.orange)
                }
            }
            .foregroundStyle(CatchCheckColor.navy)
            .padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
        }
    }
}
private struct FishCheckCard: View {
    @EnvironmentObject private var vm: FishingViewModel
    let result: FishCheck
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack { Text(result.commonName).bold(); Spacer(); Text("\(result.confidence)% match").bold().foregroundStyle(CatchCheckColor.orange) }
            Text(result.scientificName).foregroundStyle(.secondary)
            Text("MPI rules · \(result.areaName)").font(.subheadline.bold())
            if result.fishRules.isEmpty {
                Text(result.rulesNeedsReview
                     ? "MPI has changed this area's rules. Open the official page for current limits."
                     : vm.selectedRulesAreaID == nil
                       ? "Choose the MPI rules area above, then identify again to check local limits."
                       : "No saved size or daily limit matched this species here. Check the official rules before keeping it.")
                    .font(.subheadline).foregroundStyle(.secondary).padding(12).frame(maxWidth: .infinity, alignment: .leading).background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 10))
                if vm.selectedRulesAreaID == nil {
                    Menu("Choose MPI fishing area") {
                        ForEach(fishingRulesAreas) { area in
                            Button(area.name) {
                                vm.selectRulesArea(area.id)
                                vm.identifyFish()
                            }
                        }
                    }.buttonStyle(.borderedProminent)
                }
            } else {
                ForEach(result.fishRules) { rule in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(rule.species).font(.subheadline.bold())
                        if rule.details.contains(where: { $0.label.localizedCaseInsensitiveContains("daily limit") || $0.label.localizedCaseInsensitiveContains("bag limit") }) {
                            Text("Limits differ within this area. Check the exact subarea on MPI.")
                        } else {
                            if let minimumSize = rule.minimumSize { Text("\(rule.minimumSizeLabel ?? "Minimum size"): \(minimumSize)") }
                            if let dailyLimit = rule.dailyLimit { Text("Daily limit: \(dailyLimit)") }
                        }
                    }
                    .font(.subheadline).foregroundStyle(CatchCheckColor.navy).padding(12).frame(maxWidth: .infinity, alignment: .leading).background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 10))
                }
            }
            if let url = result.rulesSourceURL ?? fishingRulesAreas.first(where: { $0.id == vm.selectedRulesAreaID })?.officialURL {
                Link("See full official MPI rules", destination: url).font(.subheadline.weight(.semibold))
            }
            Text("Confirm the species, exact location, closures and current MPI rules before keeping a fish.")
                .font(.caption).foregroundStyle(.secondary)
            if let reviewed = result.rulesReviewedAt { Text("MPI page reviewed: \(reviewed)").font(.caption).foregroundStyle(.secondary) }
        }.foregroundStyle(CatchCheckColor.navy).padding(14).background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 14))
    }
}
struct Card<Content: View>: View { var background: Color = CatchCheckColor.surface; @ViewBuilder let content: Content; var body: some View { VStack(alignment: .leading, spacing: 10, content: { content }).padding(18).frame(maxWidth: .infinity, alignment: .leading).background(background, in: RoundedRectangle(cornerRadius: 18)) } }
private struct Metric: View { let label: String; let value: String; var body: some View { VStack(alignment: .leading) { Text(label).font(.caption).foregroundStyle(.secondary); Text(value).bold().foregroundStyle(CatchCheckColor.navy) } } }
struct ChoiceButton: ButtonStyle { let selected: Bool; func makeBody(configuration: Configuration) -> some View { configuration.label.padding(.horizontal, 12).padding(.vertical, 8).background(selected ? CatchCheckColor.accent : CatchCheckColor.surface, in: Capsule()).foregroundStyle(selected ? .white : CatchCheckColor.navy).overlay(Capsule().stroke(selected ? CatchCheckColor.accent : CatchCheckColor.outline, lineWidth: 1)).opacity(configuration.isPressed ? 0.7 : 1) } }

struct CameraPicker: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    @Environment(\.dismiss) private var dismiss
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIViewController(context: Context) -> UIImagePickerController { let picker = UIImagePickerController(); picker.sourceType = .camera; picker.delegate = context.coordinator; picker.cameraCaptureMode = .photo; return picker }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate { let parent: CameraPicker; init(parent: CameraPicker) { self.parent = parent }; func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { parent.image = info[.originalImage] as? UIImage; parent.dismiss() }; func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() } }
}
