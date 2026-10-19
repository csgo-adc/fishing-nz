import SwiftUI
import MapKit
import PhotosUI
import UIKit
import AuthenticationServices

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
    static let warning = adaptive(0xA7442F, 0xFFB09D)
    // Outlook badges and highlighted numbers on fishing window cards; the same colours as Android.
    static let goodFill = adaptive(0xE3F4E5, 0x1F3A26)
    static let goodText = adaptive(0x1E6B34, 0xA8E0B4)
    static let cautionFill = adaptive(0xFCEFD8, 0x3D2E12)
    static let cautionText = adaptive(0x8A5300, 0xF5C77A)
    static let poorFill = adaptive(0xFBE4E1, 0x3F1D19)
    static let poorText = adaptive(0x9B2C1F, 0xF3A99F)
    static let tile = adaptive(0xEDF1F8, 0x223049)
    static let tileText = adaptive(0x536179, 0xB4C1D5)
}

private enum MoreDestination: String, Identifiable {
    case trips, catchLog, rules, account, settings
    var id: String { rawValue }
}

struct CatchCheckRootView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @AppStorage("catchcheckAppearance") private var appearance = "light"
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
            WeatherView().tabItem { Label("Weather", systemImage: "cloud.sun.fill") }.tag(3)
            MoreView(open: { moreDestination = $0 }).tabItem { Label("More", systemImage: "square.grid.2x2.fill") }.tag(4)
        }
        .tint(CatchCheckColor.accent)
        .preferredColorScheme(preferredAppearance)
        .fullScreenCover(isPresented: $vm.showingResults) { ResultsView() }
        // "Show on map" from a saved window under More closes that sheet too.
        .onChange(of: vm.mapFocus?.id) { _, id in if id != nil { moreDestination = nil } }
        .sheet(item: $vm.selectedSpot) { SpotDetailView(spot: $0) }
        .alert("Shared window", isPresented: Binding(get: { vm.sharedWindowNotice != nil }, set: { if !$0 { vm.sharedWindowNotice = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(vm.sharedWindowNotice ?? "") }
        .sheet(item: $moreDestination) { destination in
            switch destination {
            case .trips: TripsView()
            case .catchLog: CatchLogView()
            case .rules: RulesView()
            case .account: AccountView()
            case .settings: SettingsView()
            }
        }
        .task { vm.requestLocation() }
        // "Log this catch" and a trip's "Log a catch" open the catch log, which starts the new entry.
        .onChange(of: vm.catchPrefill?.id) { _, id in if id != nil { moreDestination = .catchLog } }
        .onChange(of: vm.openTripsRequest) { _, _ in moreDestination = .trips }
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
                    VStack(spacing: 10) {
                        MoreNavigationRow(title: "Account", subtitle: vm.account?.email ?? "Sign in and manage your profile", icon: "person.crop.circle") { open(.account) }
                    }
                    .padding(.bottom, 6)
                    VStack(spacing: 10) {
                        MoreNavigationRow(title: "Trips", subtitle: "Saved spots and active plans", icon: "calendar") { open(.trips) }
                        MoreNavigationRow(title: "Catch log", subtitle: "Your catches, sizes and patterns", icon: "fish") { open(.catchLog) }
                        MoreNavigationRow(title: "Fishing rules", subtitle: "Sizes, limits and local restrictions", icon: "book.closed") { open(.rules) }
                    }
                    .padding(.bottom, 6)
                    VStack(spacing: 10) {
                        MoreNavigationRow(title: "Settings", subtitle: "Appearance, feedback, terms and privacy", icon: "gearshape") { open(.settings) }
                        ShareAppRow()
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

/// What the Share row on the More page sends. Until the app has an App Store page it sends text that tells people to
/// search for it. Once the app record exists, set `appStoreURL` to its page: App Store Connect → App Information shows
/// the Apple ID number, and the link is `https://apps.apple.com/app/id<that number>`.
private enum AppShare {
    static let appStoreURL: URL? = URL(string: "https://apps.apple.com/app/id6820858116")
    static let subject = "Fishdays - NZ"
    static let message = "Fishdays - NZ helps you plan fishing days around New Zealand with tides, weather, fishing windows and MPI fishing rules."
    static let textOnly = message + " Search for “Fishdays - NZ” in the App Store."
}

private struct ShareAppRow: View {
    var body: some View {
        Group {
            if let url = AppShare.appStoreURL {
                ShareLink(item: url, subject: Text(AppShare.subject), message: Text(AppShare.message)) { label }
            } else {
                ShareLink(item: AppShare.textOnly, subject: Text(AppShare.subject)) { label }
            }
        }
        .buttonStyle(.plain)
    }

    private var label: some View {
        MoreNavigationLabel(title: "Share Fishdays - NZ", subtitle: "Tell a friend about the app", icon: "person.2.fill", accessory: "square.and.arrow.up")
    }
}

private struct MoreNavigationLabel: View {
    let title: String
    let subtitle: String
    let icon: String
    var accessory = "chevron.right"

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
            Image(systemName: accessory).font(.caption.bold()).foregroundStyle(.secondary)
        }
        .padding(16)
        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
    }
}

private struct SettingsView: View {
    @EnvironmentObject private var vm: FishingViewModel
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
            .onAppear { vm.trackScreen("settings") }
        }
    }
}

private struct AppearanceView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @AppStorage("catchcheckAppearance") private var appearance = "light"

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
        .onAppear { vm.trackScreen("appearance") }
    }
}

private struct TermsPrivacyView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var analyticsOn = AnalyticsPreferences.isEnabled
    @State private var photoConsentRemembered = PhotoUploadConsent.isRemembered

    private var privacyPolicyURL: URL? {
        let base = (Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String) ?? "https://fishing.fishnz.space"
        return URL(string: base.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/privacy")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("Terms & privacy").font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                Text("A short guide to using Fishdays - NZ and understanding the information it uses.")
                    .foregroundStyle(.secondary)
                Card {
                    Text("Terms of use").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text("Fishing windows, tide times, fish identification and rule summaries help you plan. They can be incomplete or out of date. Check the current MPI rules and local access conditions before fishing.")
                    Text("You are responsible for following applicable fishing and access rules.")
                }
                Card {
                    Text("Privacy").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    Text("If you allow location access, the app uses your position to centre the map and find nearby information. You can change location permission in your phone’s settings.")
                    Text("A photo is uploaded only after you agree. It passes through Cloudflare to OpenAI for an AI fish suggestion. Original photo metadata is removed. OpenAI response storage is disabled; provider security records can remain for up to 30 days, or longer where legally required.")
                    Text("If you sign in or send feedback, your account details and feedback are sent to the Fishdays - NZ service. Signing out removes the saved session from this device. You can delete your account from the Account page.")
                    Text("Weather requests send coordinates to Open-Meteo. Map and tide providers receive connection information and the places you request. You can choose locations manually.")
                    Text("Your catch log, with its photos and positions, stays on this phone unless you turn on sync on the Account page; photos never sync. Saving a catch sends its place and time to Open-Meteo for the weather then; tides come from LINZ.")
                    Text("Publisher: Tristan · tc199558@gmail.com")
                    if let privacyPolicyURL { Link("Read the full privacy policy", destination: privacyPolicyURL).font(.subheadline.weight(.semibold)) }
                    Toggle(isOn: $analyticsOn) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Anonymous usage statistics").fontWeight(.semibold)
                            Text("On by default. Shares which screens you use and how searches and fish photos go, with your device model, iOS version, app version, language and a random identifier made on this phone, whether or not you are signed in. Fishdays - NZ also counts your API requests against that identifier. No location, photos, email or typed text is included. Turning this off erases what we stored for this identifier and resets it.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    .onChange(of: analyticsOn) { _, enabled in AnalyticsPreferences.setEnabled(enabled) }
                    if photoConsentRemembered {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("Fish photos").fontWeight(.semibold)
                            Text("You chose not to be asked before a photo is sent to OpenAI for identification.")
                                .font(.footnote).foregroundStyle(.secondary)
                            Button("Ask me before sending photos again") {
                                PhotoUploadConsent.isRemembered = false
                                photoConsentRemembered = false
                            }.font(.subheadline.weight(.semibold))
                        }
                    }
                }
            }.padding(20)
        }
        .background(CatchCheckColor.cream)
        .navigationTitle("Terms & privacy")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { vm.trackScreen("terms_privacy") }
    }
}

private struct FeedbackView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var category = "bug"
    @State private var message = ""
    @State private var rating: Int?
    @State private var showingAccount = false
    @State private var didSubmit = false
    @State private var sent = false

    private struct Topic: Identifiable {
        let id: String
        let title: String
        let icon: String
        let hint: String
    }
    private let topics = [
        Topic(id: "bug", title: "Report a problem", icon: "ladybug", hint: "What happened, and what were you doing?"),
        Topic(id: "idea", title: "Suggest an idea", icon: "lightbulb", hint: "What would make the app better for you?"),
        Topic(id: "general", title: "Something else", icon: "bubble.left", hint: "Write your message")
    ]
    private var hint: String { topics.first { $0.id == category }?.hint ?? "" }
    private var canSend: Bool { !vm.accountBusy && message.trimmingCharacters(in: .whitespacesAndNewlines).count >= 3 }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Report a problem or suggest an idea.").foregroundStyle(.secondary)
                if vm.account == nil {
                    Card {
                        Text("Sign in to send feedback").font(.title3.bold()).foregroundStyle(CatchCheckColor.navy)
                        Text("Feedback is linked to your account so we can reply and follow up.")
                            .foregroundStyle(.secondary)
                        if vm.accountLoading {
                            ProgressView("Checking your account…")
                        } else {
                            Button { showingAccount = true } label: { Text("Go to account").frame(maxWidth: .infinity) }
                                .buttonStyle(.borderedProminent).controlSize(.large).tint(CatchCheckColor.accent)
                        }
                    }
                } else {
                    if sent {
                        Label("Thanks, your feedback was sent.", systemImage: "checkmark.circle.fill")
                            .font(.headline).foregroundStyle(CatchCheckColor.navy)
                            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 14))
                    }
                    if didSubmit, !sent, !vm.accountBusy, let error = vm.accountError {
                        Label(error, systemImage: "exclamationmark.circle.fill")
                            .foregroundStyle(.red)
                            .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(Color.red.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("What's it about?").font(.headline).foregroundStyle(CatchCheckColor.navy)
                        ForEach(topics) { topic in
                            let selected = topic.id == category
                            Button { category = topic.id; sent = false } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: topic.icon).font(.title3).frame(width: 26)
                                        .foregroundStyle(selected ? CatchCheckColor.accent : Color.secondary)
                                    Text(topic.title).font(selected ? .body.weight(.semibold) : .body).foregroundStyle(CatchCheckColor.navy)
                                    Spacer()
                                    if selected { Image(systemName: "checkmark").font(.body.bold()).foregroundStyle(CatchCheckColor.accent) }
                                }
                                .padding(.horizontal, 14).padding(.vertical, 14)
                                .background(selected ? CatchCheckColor.seafoam : CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 14))
                                .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? CatchCheckColor.accent : CatchCheckColor.outline, lineWidth: selected ? 2 : 1))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected ? [.isSelected] : [])
                        }
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Your message").font(.headline).foregroundStyle(CatchCheckColor.navy)
                        ZStack(alignment: .topLeading) {
                            TextEditor(text: $message)
                                .scrollContentBackground(.hidden)
                                .frame(minHeight: 160)
                                .padding(8)
                                .onChange(of: message) { _, value in
                                    if value.count > 4000 { message = String(value.prefix(4000)) }
                                    // New typing hides the "sent" note; clearing the box after sending does not.
                                    if !value.isEmpty { sent = false }
                                }
                                .accessibilityLabel("Your message")
                            if message.isEmpty {
                                Text(hint).foregroundStyle(.tertiary).padding(.horizontal, 13).padding(.vertical, 16)
                                    .allowsHitTesting(false).accessibilityHidden(true)
                            }
                        }
                        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 14))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(CatchCheckColor.outline, lineWidth: 1))
                        HStack {
                            if !message.isEmpty && message.trimmingCharacters(in: .whitespacesAndNewlines).count < 3 {
                                Text("Write at least 3 characters.")
                            }
                            Spacer()
                            Text("\(message.count) / 4000")
                        }
                        .font(.caption).foregroundStyle(.secondary)
                    }
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text("How's the app working for you?").font(.headline).foregroundStyle(CatchCheckColor.navy)
                            Text("Optional").font(.caption).foregroundStyle(.secondary)
                        }
                        HStack(spacing: 6) {
                            ForEach(1...5, id: \.self) { value in
                                let filled = (rating ?? 0) >= value
                                Button { rating = rating == value ? nil : value; sent = false } label: {
                                    Image(systemName: filled ? "star.fill" : "star")
                                        .font(.system(size: 30))
                                        .foregroundStyle(filled ? Color(red: 0.88, green: 0.63, blue: 0) : Color.secondary)
                                        .frame(width: 48, height: 48)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("\(value) out of 5")
                                .accessibilityAddTraits(rating == value ? [.isSelected] : [])
                                .accessibilityHint(rating == value ? "Tap again to clear the rating" : "")
                            }
                        }
                    }
                    Button {
                        let submittedMessage = message
                        didSubmit = true
                        sent = false
                        Task { @MainActor in
                            if await vm.sendFeedback(category: category, message: submittedMessage, rating: rating) {
                                if message == submittedMessage { message = ""; rating = nil }
                                sent = true
                            }
                        }
                    } label: {
                        HStack(spacing: 8) {
                            if vm.accountBusy { ProgressView().tint(.white) } else { Image(systemName: "paperplane.fill") }
                            Text(vm.accountBusy ? "Sending…" : "Send feedback")
                        }
                        .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 4)
                    }
                    .buttonStyle(.borderedProminent).controlSize(.large).tint(CatchCheckColor.accent)
                    .disabled(!canSend)
                }
            }.padding(20).padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(CatchCheckColor.cream)
        .navigationTitle("Feedback")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { vm.trackScreen("feedback") }
        .sheet(isPresented: $showingAccount) { AccountView() }
    }
}

struct HomeView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @State private var photoItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var showingPlan = false
    @State private var findAfterPlan = false
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
                                    Text("Your fishing plan").font(.headline).foregroundStyle(CatchCheckColor.navy)
                                    Spacer()
                                    Image(systemName: "chevron.right").font(.caption.bold()).foregroundStyle(.secondary)
                                }
                                Text("\(vm.isBoatFishing ? "Boat" : "Land") · \(vm.dateSummary) · \(vm.timeSummary)\(vm.sessionHours != 2 ? " · \(hoursLabel(vm.sessionHours))" : "") · \(vm.searchesOnePlace ? "this spot only" : "\(vm.radiusKm) km")")
                                    .font(.subheadline.weight(.medium)).foregroundStyle(CatchCheckColor.navy)
                                    .lineLimit(2)
                                Text(vm.whatMattersLabel)
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Edit fishing plan: \(vm.isBoatFishing ? "boat" : "land"), \(vm.dateSummary), \(vm.timeSummary), \(vm.locationSummary)")
                        SearchPlaceMenu(compact: true)
                        Button { vm.showRecommendations() } label: {
                            Text("Find fishing windows")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 5)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(CatchCheckColor.accent)
                    }
                    Text("Quick forecast").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    Card { HStack { Metric(label: "Wind", value: vm.weather?.wind ?? "—"); Spacer(); Metric(label: "Tide", value: vm.currentTide?.nextEvent ?? "—"); Spacer(); Metric(label: "Temp", value: vm.weather?.temperature ?? "—") } }
                    if let conditionsError = vm.conditionsError { Text(conditionsError).font(.caption).foregroundStyle(.secondary) }
                    FishIdentifierView(photoItem: $photoItem, showCamera: $showCamera, openAccount: openAccount)
                    Text("Your next planning window").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                    if let best = vm.bestRecommendation {
                        RecommendationCard(spot: best, showWhy: shouldShowWindowReason(best, among: vm.recommendations)) { vm.showingResults = true }
                    } else {
                        Card { Text("Choose a distance and dates, then search for live fishing windows.").foregroundStyle(.secondary) }
                    }
                }.padding(20)
            }.background(CatchCheckColor.cream)
            // Searching waits for the plan sheet to close, so the results can open in its place.
            .sheet(isPresented: $showingPlan, onDismiss: {
                if findAfterPlan { findAfterPlan = false; vm.showRecommendations() }
            }) {
                PlanningSheet(onFind: { findAfterPlan = true; showingPlan = false })
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
    }
}

/// Chips that wrap onto more lines instead of scrolling sideways, so every choice is visible.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, width: proposal.width ?? .infinity)
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: rows.last.map { $0.y + $0.height } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
        }
    }

    private struct Row { var indices: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0; var y: CGFloat = 0 }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            if !row.indices.isEmpty && row.width + spacing + size.width > width {
                rows.append(row)
                row = Row(y: row.y + row.height + spacing)
            }
            row.width += (row.indices.isEmpty ? 0 : spacing) + size.width
            row.height = max(row.height, size.height)
            row.indices.append(index)
        }
        if !row.indices.isEmpty { rows.append(row) }
        return rows
    }
}

private struct PlanQuestion<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline).foregroundStyle(CatchCheckColor.navy)
            content
        }
    }
}

/// The plan page: four questions, then More options already set to recommended values.
private struct PlanningSheet: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showMore = false
    let onFind: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Card {
                        PlanQuestion(title: "Where?") {
                            SearchPlaceMenu()
                            FlowLayout {
                                if vm.searchPlace != nil {
                                    Button("Just this spot") { vm.chooseJustThisPlace() }
                                        .buttonStyle(ChoiceButton(selected: vm.searchesOnePlace))
                                }
                                ForEach(searchRadiiKm, id: \.self) { km in
                                    Button("\(km) km") { vm.chooseRadius(km) }
                                        .buttonStyle(ChoiceButton(selected: !vm.searchesOnePlace && vm.radiusKm == km))
                                }
                            }
                        }
                    }
                    Card {
                        PlanQuestion(title: "Fishing from") {
                            Picker("Fishing from", selection: $vm.isBoatFishing) {
                                Text("Land").tag(false)
                                Text("Boat").tag(true)
                            }.pickerStyle(.segmented)
                        }
                    }
                    Card {
                        PlanQuestion(title: "When?") {
                            FlowLayout {
                                ForEach(FishingDatePreset.allCases, id: \.self) { preset in
                                    Button(preset.rawValue) { vm.datePreset = preset }
                                        .buttonStyle(ChoiceButton(selected: vm.datePreset == preset))
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
                            Text(vm.dateRangeNote).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Card {
                        PlanQuestion(title: "What time?") {
                            FlowLayout {
                                ForEach(FishingTimeMode.allCases, id: \.self) { mode in
                                    Button(mode.rawValue) { vm.timeMode = mode }
                                        .buttonStyle(ChoiceButton(selected: vm.timeMode == mode))
                                }
                            }
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
                            }
                            Text(vm.timeNote).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Card {
                        DisclosureGroup(isExpanded: $showMore) {
                            MoreOptions().padding(.top, 12)
                        } label: {
                            HStack {
                                Text("More options").font(.headline).foregroundStyle(CatchCheckColor.navy)
                                Spacer()
                                Text("\(vm.whatMattersLabel) · \(hoursLabel(vm.sessionHours))").font(.caption).foregroundStyle(.secondary)
                            }
                        }.tint(CatchCheckColor.accent)
                    }
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .safeAreaInset(edge: .bottom) {
                Button(action: onFind) {
                    Text("Find fishing windows").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 5)
                }
                .buttonStyle(.borderedProminent).tint(CatchCheckColor.accent)
                .padding(.horizontal, 20).padding(.vertical, 10)
                .background(.bar)
            }
            .navigationTitle("Plan your fishing")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

/// Choices that already have recommended values, so most people never open them.
private struct MoreOptions: View {
    @EnvironmentObject private var vm: FishingViewModel

    private func setWalk(_ minutes: Int) {
        var preferences = vm.landPreferences
        preferences.arrivalMinutes = minutes
        preferences.returnMinutes = minutes
        vm.landPreferences = preferences
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            PlanQuestion(title: "What matters most?") {
                FlowLayout {
                    ForEach(PlanMatter.choices(boat: vm.isBoatFishing)) { matter in
                        Button(matter.label) { vm.setWhatMatters(matter.priority, land: matter.land) }
                            .buttonStyle(ChoiceButton(selected: vm.selectedMatter == matter))
                    }
                }
                if let note = vm.selectedMatter?.note { Text(note).font(.caption).foregroundStyle(.secondary) }
                if vm.preference == .tide {
                    FlowLayout {
                        ForEach(TidePhase.allCases, id: \.self) { phase in
                            Button(phase.label) { vm.tidePhase = phase }
                                .buttonStyle(ChoiceButton(selected: vm.tidePhase == phase))
                        }
                    }
                    Text(vm.tidePhase.note).font(.caption).foregroundStyle(.secondary)
                }
            }
            PlanQuestion(title: "How long will you fish?") {
                FlowLayout {
                    ForEach(sessionLengths, id: \.self) { hours in
                        Button(hoursLabel(hours)) { vm.sessionHours = hours }
                            .buttonStyle(ChoiceButton(selected: vm.sessionHours == hours))
                    }
                }
            }
            PlanQuestion(title: "How rough is OK?") {
                FlowLayout {
                    ForEach(vm.isBoatFishing ? boatRoughnessChoices : roughnessChoices, id: \.band) { choice in
                        Button(choice.label) { vm.landPreferences.maxBand = choice.band }
                            .buttonStyle(ChoiceButton(selected: vm.landPreferences.maxBand == choice.band))
                    }
                }
            }
            if !vm.isBoatFishing {
                PlanQuestion(title: "Where do you fish?") {
                    FlowLayout {
                        ForEach(ShoreSetting.allCases, id: \.self) { setting in
                            Button(setting.rawValue) { vm.landPreferences.setting = setting }
                                .buttonStyle(ChoiceButton(selected: vm.landPreferences.setting == setting))
                        }
                    }
                    Text("Beach and rocks also check the waves.").font(.caption).foregroundStyle(.secondary)
                }
                PlanQuestion(title: "Walk to your spot (each way)") {
                    FlowLayout {
                        ForEach(walkChoices, id: \.minutes) { choice in
                            Button(choice.label) { setWalk(choice.minutes) }
                                .buttonStyle(ChoiceButton(selected: (vm.landPreferences.arrivalMinutes ?? 0) == choice.minutes))
                        }
                    }
                    Text("Daytime then means getting back before dark too.").font(.caption).foregroundStyle(.secondary)
                }
            }
            Button("Reset to recommended") { vm.resetMoreOptions() }.font(.subheadline)
        }
    }
}

/// Explicit permission before a photo leaves the phone (App Review guideline 5.1.2(i)). The box is off by default and is only
/// kept when the person agrees; cancelling never stores it.
private struct PhotoUploadConsentSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var dontAskAgain = false
    let onAgree: (_ dontAskAgain: Bool) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Send this photo for AI identification?").font(.title2.bold()).foregroundStyle(CatchCheckColor.navy)
                Text("Your selected photo will be sent through the Fishdays - NZ Cloudflare service to OpenAI to suggest a fish species. Original photo metadata is removed. We do not save the photo in your account. OpenAI response storage is disabled, but security records can remain for up to 30 days or longer where legally required.")
                Text("Avoid photos containing people or private information. You can cancel and keep using the other tools. Details: More → Settings → Terms & privacy.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Toggle(isOn: $dontAskAgain) {
                    Text("Don’t ask me again before sending a photo")
                }
                .toggleStyle(CheckboxToggleStyle())
            }
            .padding(24)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 4) {
                Button {
                    dismiss()
                    onAgree(dontAskAgain)
                } label: { Text("Agree and upload").frame(maxWidth: .infinity).padding(.vertical, 6) }
                    .buttonStyle(.borderedProminent).tint(CatchCheckColor.accent)
                Button("Cancel", role: .cancel) { dismiss() }
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
            }
            .padding(.horizontal, 24).padding(.top, 12).padding(.bottom, 8)
            .background(.bar)
        }
        .background(CatchCheckColor.cream)
    }
}

/// Flags a wrong, offensive or unsafe identification. It is sent as feedback with the suggested species, never the photo.
private struct AIResultReportSheet: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    let species: String
    @State private var message = ""
    @State private var submitted = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("What went wrong?", text: $message, axis: .vertical)
                        .lineLimit(3...8)
                        .onChange(of: message) { _, value in if value.count > 3000 { message = String(value.prefix(3000)) } }
                } header: {
                    Text("Tell us if this result is incorrect, offensive or unsafe.")
                } footer: {
                    Text("The report includes the suggested species and your message, and is linked to your account. Your photo is not included.")
                }
                if submitted, let error = vm.accountError {
                    Text(error).font(.footnote).foregroundStyle(.red)
                }
            }
            .navigationTitle("Report an AI result")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(vm.accountBusy) }
                ToolbarItem(placement: .confirmationAction) {
                    Button(vm.accountBusy ? "Sending…" : "Send report") {
                        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
                        submitted = true
                        Task { @MainActor in
                            if await vm.sendFeedback(category: "bug", message: "AI result report: \(species)\n\(text)", rating: 1) { dismiss() }
                        }
                    }
                    .disabled(vm.accountBusy || message.trimmingCharacters(in: .whitespacesAndNewlines).count < 3)
                }
            }
        }
        .interactiveDismissDisabled(vm.accountBusy)
    }
}

private struct CheckboxToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: configuration.isOn ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundStyle(configuration.isOn ? CatchCheckColor.accent : Color.secondary)
                configuration.label.font(.subheadline).foregroundStyle(CatchCheckColor.navy).multilineTextAlignment(.leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isToggle)
        .accessibilityValue(configuration.isOn ? "On" : "Off")
    }
}

private struct FishIdentifierView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Binding var photoItem: PhotosPickerItem?
    @Binding var showCamera: Bool
    @State private var showingPhotoConsent = false
    @State private var showingAIReport = false
    let openAccount: () -> Void
    var body: some View {
        let galleryLabel = vm.selectedPhoto == nil ? "Choose photo" : "Gallery"
        Card {
            HStack { Image(systemName: "camera.fill").font(.title2).foregroundStyle(CatchCheckColor.navy).frame(width: 44, height: 44).background(CatchCheckColor.seafoam, in: Circle()); VStack(alignment: .leading) { Text("What fish is this?").font(.title3.bold()).foregroundStyle(CatchCheckColor.navy); Text("AI ID + local rules check").font(.caption).foregroundStyle(.secondary) }; Spacer() }
            if let photo = vm.selectedPhoto {
                RoundedRectangle(cornerRadius: 16)
                    .fill(CatchCheckColor.cream)
                    .frame(height: 200)
                    .overlay {
                        GeometryReader { bounds in
                            Image(uiImage: photo)
                                .resizable()
                                .scaledToFit()
                                .frame(width: bounds.size.width, height: bounds.size.height)
                                .accessibilityLabel("Selected fish photo")
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .overlay(RoundedRectangle(cornerRadius: 16).stroke(CatchCheckColor.outline, lineWidth: 1))
            }
            if let result = vm.fishCheck {
                FishCheckCard(result: result)
                if result.isFish {
                    Button { vm.logIdentifiedCatch() } label: { Label("Log this catch", systemImage: "square.and.pencil").frame(maxWidth: .infinity) }
                        .buttonStyle(.borderedProminent)
                }
                if vm.account != nil {
                    Button("Report this AI result") { showingAIReport = true }
                        .font(.subheadline.weight(.semibold))
                }
            }
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
            if vm.fishCheck?.isFish != false {
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
            }
            Button {
                if vm.fishIdentityAvailable {
                    if PhotoUploadConsent.isRemembered { vm.identifyFish() } else { showingPhotoConsent = true }
                }
                else if vm.hasStoredSession { vm.refreshAccount() }
                else { openAccount() }
            } label: {
                Label(vm.fishIdentityAvailable ? "Identify fish" : vm.accountLoading ? "Checking account…" : vm.hasStoredSession ? "Retry account check" : "Sign in to identify fish", systemImage: "checkmark.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).tint(CatchCheckColor.accent)
            .disabled(vm.isCheckingFish || vm.accountLoading || (vm.fishIdentityAvailable && vm.selectedPhoto == nil))
            if vm.fishIdentityAvailable {
                Text(vm.remainingIdentifications.map { "\($0) of \(vm.fishIdentityQuota?.limit ?? 5) identifications left today · Resets at midnight NZ time" }
                     ?? "5 fish identifications per day · Resets at midnight NZ time")
                    .font(.caption).foregroundStyle(.secondary)
                if vm.remainingIdentifications == 0 {
                    Button("Refresh daily allowance") { vm.refreshAccount() }
                        .font(.subheadline).disabled(vm.accountLoading)
                }
            }
            if !vm.fishIdentityAvailable {
                Text(vm.accountLoading ? "Checking your account access."
                     : vm.hasStoredSession ? "Your saved session is still on this device, but account access could not be checked."
                     : "Sign in to use fish identification.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Text("AI suggestions are a guide. Confirm species, area and current MPI rules before keeping a fish.").font(.caption).foregroundStyle(.secondary)
        }
        .sheet(isPresented: $showingAIReport) {
            AIResultReportSheet(species: vm.fishCheck?.commonName ?? "")
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showingPhotoConsent) {
            PhotoUploadConsentSheet { rememberChoice in
                if rememberChoice { PhotoUploadConsent.isRemembered = true }
                vm.identifyFish()
            }
            .presentationDetents([.large])
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            vm.selectedPhoto = nil
            vm.error = nil
            Task {
                do {
                    let data = try await item.loadTransferable(type: Data.self)
                    guard photoItem == item else { return }
                    guard let data, let image = UIImage(data: data) else {
                        vm.error = "Could not open this photo. Choose another image."
                        return
                    }
                    vm.selectedPhoto = image
                } catch {
                    if photoItem == item { vm.error = "Could not open this photo. Choose another image." }
                }
            }
        }
        .sheet(isPresented: $showCamera) {
            CameraPicker(image: Binding(
                get: { vm.selectedPhoto },
                set: { vm.selectedPhoto = $0 }
            ))
        }
    }
}

struct AccountView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var showChangePassword = false
    @State private var showSignOutEverywhere = false
    @State private var showDeleteConfirmation = false
    @State private var showSyncOff = false
    @State private var createAccount = false
    @State private var email = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var displayName = ""
    @State private var countryCode = "NZ"
    @State private var passwordVisible = false

    private var passwordsMatch: Bool { !confirmPassword.isEmpty && password == confirmPassword }
    private var forgotPasswordURL: URL? {
        let base = (Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String) ?? "https://fishing.fishnz.space"
        return URL(string: base.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/forgot-password")
    }
    private var isEmailValid: Bool {
        email.trimmingCharacters(in: .whitespacesAndNewlines)
            .range(of: #"^[^\s@]+@[^\s@]+\.[^\s@]+$"#, options: .regularExpression) != nil
    }
    private var canSubmit: Bool {
        guard !vm.accountBusy, isEmailValid else { return false }
        if createAccount { return password.utf16.count >= 8 && password.utf16.count <= 128 && passwordsMatch }
        return !password.isEmpty
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 18) {
                        HStack(spacing: 14) {
                            Image(systemName: "person.crop.circle.fill")
                                .font(.system(size: 36, weight: .medium))
                                .foregroundStyle(.white)
                                .frame(width: 60, height: 60)
                                .background(.white.opacity(0.12), in: Circle())
                            VStack(alignment: .leading, spacing: 4) {
                                Text("FISHDAYS · NZ").font(.caption.weight(.bold)).tracking(1.5).foregroundStyle(Color(red: 0.78, green: 0.90, blue: 0.92))
                                // The navigation bar already says Account, so the header names the person, or stays untitled when signed out.
                                if let account = vm.account {
                                    Text(account.displayName.isEmpty ? account.email : account.displayName)
                                        .font(.title2.bold()).foregroundStyle(.white)
                                        .lineLimit(1).truncationMode(.middle)
                                }
                            }
                            Spacer(minLength: 0)
                        }
                        if vm.account == nil {
                            Text("Sign in to identify fish and manage your profile.")
                                .font(.subheadline).foregroundStyle(.white.opacity(0.8))
                        }
                    }
                    .padding(24).frame(maxWidth: .infinity, alignment: .leading)
                    .background(LinearGradient(colors: [CatchCheckColor.hero, Color(red: 0.09, green: 0.29, blue: 0.38)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 28))
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
                        accountSection("Sync trips and catch log", subtitle: "Use them on your other signed-in devices", icon: "arrow.triangle.2.circlepath") {
                            Toggle("Sync on this device", isOn: Binding(get: { vm.syncEnabled }, set: { if $0 { vm.setSyncEnabled(true) } else { showSyncOff = true } }))
                                .font(.subheadline.weight(.semibold))
                            Text("Saved spots, the active trip and your catch log, including each catch's position and notes, are kept with your account. Photos stay on this phone.")
                                .font(.caption).foregroundStyle(.secondary)
                            if vm.syncEnabled {
                                Text(vm.syncing ? "Syncing…" : vm.syncError ?? vm.lastSyncedAt.map { "Last synced \(OfflineCache.savedLabel($0))" } ?? "Not synced yet")
                                    .font(.caption).foregroundStyle(vm.syncError == nil ? Color.secondary : CatchCheckColor.warning)
                                Button("Sync now") { Task { await vm.syncNow() } }.font(.subheadline.weight(.semibold)).disabled(vm.syncing)
                            }
                        }
                        .confirmationDialog("Turn off sync?", isPresented: $showSyncOff, titleVisibility: .visible) {
                            Button("Turn off and delete copy", role: .destructive) { vm.setSyncEnabled(false, deleteSyncedCopy: true) }
                            Button("Turn off, keep copy") { vm.setSyncEnabled(false) }
                        } message: {
                            Text("This phone keeps its trips and catch log. You can also delete the copy kept with your account; your other devices keep theirs.")
                        }
                        if !offeredSignInProviders.isEmpty {
                            accountSection("Sign-in options", subtitle: "Google or Apple", icon: "lock.shield") {
                                socialSignInButtons
                            }
                        }
                        accountSection("Password", subtitle: account.hasPassword ? nil : "Add one to also sign in with your email.", icon: "key") {
                            if account.hasPassword {
                                Button { showChangePassword = true } label: { Label("Change password", systemImage: "key").frame(maxWidth: .infinity) }
                                    .buttonStyle(.bordered).disabled(vm.accountBusy)
                            } else if let forgotPasswordURL {
                                Button {
                                    Analytics.track("password_reset_opened")
                                    openURL(forgotPasswordURL)
                                } label: { Label("Add a password", systemImage: "key").frame(maxWidth: .infinity) }
                                    .buttonStyle(.bordered).disabled(vm.accountBusy)
                            }
                            Button("Sign out of all devices") { showSignOutEverywhere = true }
                                .font(.subheadline.weight(.semibold)).disabled(vm.accountBusy)
                                .alert("Sign out of all devices?", isPresented: $showSignOutEverywhere) {
                                    Button("Sign out everywhere", role: .destructive) { vm.signOutEverywhere() }
                                    Button("Cancel", role: .cancel) {}
                                } message: {
                                    Text("This ends every session for your account, including this phone. You will need to sign in again everywhere.")
                                }
                        }
                        Button("Sign out") { vm.signOut() }.disabled(vm.accountBusy).frame(maxWidth: .infinity).padding(.vertical, 8)
                        Button("Delete account", role: .destructive) { showDeleteConfirmation = true }
                            .disabled(vm.accountBusy).frame(maxWidth: .infinity).padding(.vertical, 4)
                            .alert("Permanently delete your account?", isPresented: $showDeleteConfirmation) {
                                Button("Delete permanently", role: .destructive) { vm.deleteAccount() }
                                Button("Cancel", role: .cancel) {}
                            } message: {
                                Text("This removes your profile, sign-in connections, sessions, feedback, synced trips and catch log, and account-linked usage records from our live database. It also clears this phone’s selected fish photo and trip shortlist and turns off usage statistics. It does not delete your Google or Apple account or photos saved on your phone. It cannot be undone.")
                            }
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
                            if !offeredSignInProviders.isEmpty {
                                socialSignInButtons
                                Text("Or use your email").font(.caption).foregroundStyle(.secondary)
                            }
                            Picker("Account", selection: $createAccount) {
                                Text("Sign in").tag(false)
                                Text("Create account").tag(true)
                            }
                            .pickerStyle(.segmented)
                            .onChange(of: createAccount) { _, _ in password = ""; confirmPassword = ""; passwordVisible = false }
                            VStack(spacing: 12) {
                                if createAccount {
                                    AccountInput(icon: "person") { TextField("Name (optional)", text: $displayName).textContentType(.name) }
                                }
                                AccountInput(icon: "envelope") {
                                    TextField("Email address", text: $email).textContentType(.emailAddress).keyboardType(.emailAddress)
                                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                                }
                                AccountInput(icon: "lock") {
                                    PasswordField(title: createAccount ? "Password · 8+ characters" : "Password", text: $password,
                                                  contentType: createAccount ? .newPassword : .password, revealed: $passwordVisible)
                                }
                                if createAccount {
                                    AccountInput(icon: passwordsMatch ? "checkmark.lock" : "lock.rotation") {
                                        PasswordField(title: "Confirm password", text: $confirmPassword, contentType: .newPassword, revealed: $passwordVisible)
                                    }
                                    if !confirmPassword.isEmpty {
                                        Label(passwordsMatch ? "Passwords match" : "Passwords don’t match",
                                              systemImage: passwordsMatch ? "checkmark.circle.fill" : "xmark.circle.fill")
                                            .font(.caption.weight(.medium))
                                            .foregroundStyle(passwordsMatch ? CatchCheckColor.navy : .red)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                    if !password.isEmpty && password.utf16.count < 8 {
                                        Text("Use at least 8 characters for your password.")
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
                            if !createAccount, let forgotPasswordURL {
                                Button("Forgot password?") {
                                    Analytics.track("password_reset_opened")
                                    openURL(forgotPasswordURL)
                                }
                                .font(.subheadline.weight(.semibold))
                            }
                            if !vm.verificationPending && vm.accountError != nil {
                                Button("Resend confirmation email") { vm.resendVerification(email: email) }
                                    .font(.subheadline.weight(.semibold)).disabled(vm.accountBusy || !isEmailValid)
                            }
                        }
                        .padding(20)
                        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 24))
                        .overlay(RoundedRectangle(cornerRadius: 24).stroke(CatchCheckColor.outline, lineWidth: 1))
                        .shadow(color: .black.opacity(0.06), radius: 18, y: 8)
                    }
                }.padding(20).padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(CatchCheckColor.cream)
            .navigationTitle("Account")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { vm.trackScreen("account") }
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .sheet(isPresented: $showChangePassword) { ChangePasswordSheet().environmentObject(vm) }
            .task {
                if let account = vm.account { displayName = account.displayName; countryCode = account.countryCode; email = account.email }
                vm.refreshAccount()
                await vm.refreshSignInProviders()
            }
            .onChange(of: vm.account) { _, value in if let value { displayName = value.displayName; countryCode = value.countryCode; email = value.email; password = ""; confirmPassword = "" } }
            .onChange(of: vm.verificationPending) { _, pending in if pending { createAccount = false } }
        }
    }

    /// App Review guideline 4.8: Google sign-in may only be offered together with Sign in with Apple.
    private var offeredSignInProviders: [String] {
        guard vm.signInProviders.apple || vm.signInProviders.appleNative else { return [] }
        return vm.signInProviders.google ? ["apple", "google"] : ["apple"]
    }

    private var socialSignInButtons: some View {
        VStack(spacing: 12) {
            ForEach(offeredSignInProviders, id: \.self) { provider in
                let name = provider == "google" ? "Google" : "Apple"
                let connected = vm.account != nil && vm.signInProviders.connected.contains(provider)
                if provider == "apple" && !connected {
                    AppleIDButton { Task { await vm.signInWithProvider("apple") } }
                        .frame(height: 48).disabled(vm.accountBusy)
                        .accessibilityLabel(vm.account == nil ? "Continue with Apple" : "Connect Apple")
                } else {
                    Button {
                        Task { await vm.signInWithProvider(provider) }
                    } label: {
                        HStack(spacing: 10) {
                            if provider == "apple" { Image(systemName: "apple.logo") }
                            Text(connected ? "\(name) connected" : vm.account == nil ? "Continue with \(name)" : "Connect \(name)")
                        }.frame(maxWidth: .infinity).padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered).disabled(vm.accountBusy || connected)
                }
            }
        }
    }

    private func accountSection<Content: View>(_ title: String, subtitle: String?, icon: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: icon).foregroundStyle(CatchCheckColor.navy).frame(width: 38, height: 38).background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline).foregroundStyle(CatchCheckColor.navy)
                    if let subtitle { Text(subtitle).font(.caption).foregroundStyle(.secondary) }
                }
            }
            content()
        }
        .padding(18)
        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(CatchCheckColor.outline, lineWidth: 1))
    }
}

/// Apple's own Sign in with Apple button, as its Human Interface Guidelines require. A tap runs the app's flow.
private struct AppleIDButton: View {
    let action: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        // The button's style is fixed when it is made, so a new one follows a light/dark change.
        Representable(style: colorScheme == .dark ? .white : .black, action: action).id(colorScheme)
    }

    private struct Representable: UIViewRepresentable {
        let style: ASAuthorizationAppleIDButton.Style
        let action: () -> Void

        func makeCoordinator() -> Coordinator { Coordinator(action: action) }

        func makeUIView(context: Context) -> ASAuthorizationAppleIDButton {
            let button = ASAuthorizationAppleIDButton(authorizationButtonType: .continue, authorizationButtonStyle: style)
            button.cornerRadius = 12
            button.addTarget(context.coordinator, action: #selector(Coordinator.tapped), for: .touchUpInside)
            return button
        }

        func updateUIView(_ button: ASAuthorizationAppleIDButton, context: Context) {
            context.coordinator.action = action
            button.isEnabled = context.environment.isEnabled
            button.alpha = context.environment.isEnabled ? 1 : 0.5
        }

        final class Coordinator: NSObject {
            var action: () -> Void
            init(action: @escaping () -> Void) { self.action = action }
            @objc func tapped() { action() }
        }
    }
}

private struct ChangePasswordSheet: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var currentPassword = ""
    @State private var newPassword = ""
    @State private var repeatedPassword = ""
    @State private var passwordsVisible = false

    private var problem: String? {
        if !newPassword.isEmpty && (newPassword.utf16.count < 8 || newPassword.utf16.count > 128) { return "Use 8 to 128 characters for the new password." }
        if !newPassword.isEmpty && newPassword == currentPassword { return "Choose a password you are not already using." }
        if !repeatedPassword.isEmpty && repeatedPassword != newPassword { return "The new passwords don’t match." }
        return nil
    }
    private var canSubmit: Bool {
        !vm.accountBusy && !currentPassword.isEmpty && (8...128).contains(newPassword.utf16.count)
            && newPassword != currentPassword && repeatedPassword == newPassword
    }

    var body: some View {
        NavigationStack {
            Form {
                Section(footer: Text("You stay signed in on this phone. Every other device is signed out.")) {
                    PasswordField(title: "Current password", text: $currentPassword, contentType: .password, revealed: $passwordsVisible)
                    PasswordField(title: "New password · 8+ characters", text: $newPassword, contentType: .newPassword, revealed: $passwordsVisible)
                    PasswordField(title: "Repeat new password", text: $repeatedPassword, contentType: .newPassword, revealed: $passwordsVisible)
                }
                if let problem { Text(problem).font(.footnote).foregroundStyle(.red) }
            }
            .navigationTitle("Change password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Change") {
                        vm.changePassword(current: currentPassword, new: newPassword)
                        dismiss()
                    }.disabled(!canSubmit)
                }
            }
        }
    }
}

/// A password field with a show/hide button, like Android's sign-in fields. Fields that share `revealed` show and hide together.
private struct PasswordField: View {
    let title: String
    @Binding var text: String
    let contentType: UITextContentType
    @Binding var revealed: Bool

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if revealed {
                    TextField(title, text: $text).textInputAutocapitalization(.never).autocorrectionDisabled()
                } else {
                    SecureField(title, text: $text)
                }
            }
            .textContentType(contentType)
            Button { revealed.toggle() } label: {
                Image(systemName: revealed ? "eye.slash" : "eye").foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(revealed ? "Hide password" : "Show password")
        }
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
                        Text(vm.searchesOnePlace
                             ? "\(vm.timeSummary) · this spot only"
                             : "\(vm.timeSummary) · within \(vm.radiusKm) km")
                        Text(vm.locationSummary)
                        Text(vm.whatMattersLabel)
                    }.font(.subheadline).foregroundStyle(.secondary)
                    if vm.searchesOnePlace, vm.hasSearchedRecommendations,
                       !vm.isResolvingRecommendationLocation, !vm.isLoadingRecommendations,
                       vm.recommendationError == nil {
                        Text("Planning windows on \(vm.recommendations.count) of \(vm.recommendationDays().count) selected days. Local site checks are still needed.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                    if vm.isResolvingRecommendationLocation {
                        ProgressView("Getting your current location…").frame(maxWidth: .infinity).padding(.vertical, 28)
                    } else if let locationError = vm.recommendationLocationError {
                        VStack(alignment: .leading, spacing: 12) {
                            Text(locationError).foregroundStyle(.red)
                            Text("Choose a town or tide location in your fishing plan on Home, then search again.")
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
                                 : vm.preference == .tide
                                 ? "No \(hoursLabel(vm.sessionHours)) window falls \(vm.tidePhase.words) with the available tide and weather data. Try another tide choice or date."
                                 : vm.preference == .sun
                                 ? "No \(hoursLabel(vm.sessionHours)) window falls around sunrise or sunset within your hours and the available forecasts. Try another time or date."
                                 : "No \(hoursLabel(vm.sessionHours)) planning window matches these dates, hours and the available forecasts.")
                                .foregroundStyle(.secondary)
                            if vm.nearbySpotCount == 0, let hint = vm.nearestSpotHint {
                                Text(hint).font(.subheadline).foregroundStyle(.secondary)
                            }
                            if vm.nearbySpotCount > 0, vm.datePreset == .today {
                                Text("Today may have too little time left for a \(hoursLabel(vm.sessionHours)) session. Try another date in your fishing plan.")
                                    .font(.subheadline).foregroundStyle(.secondary)
                            }
                            if !vm.nearbyPlacesWithoutWindows.isEmpty {
                                Text("Nearby areas checked: \(vm.nearbyPlacesWithoutWindows.prefix(5).joined(separator: ", ")).")
                                    .font(.subheadline).foregroundStyle(.secondary)
                            }
                        }.padding(.vertical, 14)
                    } else {
                        if !vm.recommendations.contains(where: { $0.assessment?.matchesComfort == true }) {
                            Text(vm.recommendations.allSatisfy { $0.assessment?.comfortComplete == false }
                                 ? "More data needed · partial options below"
                                 : "No window meets your comfort preference · alternatives below")
                                .font(.subheadline).foregroundStyle(CatchCheckColor.orange)
                        }
                        // A plan over several days lists each day in date order, best first within the day; the best overall is marked.
                        let groups = vm.recommendationsByDay
                        let best = vm.recommendations.count > 1 ? vm.bestRecommendation?.windowID : nil
                        ForEach(groups, id: \.label) { group in
                            if groups.count > 1 {
                                Text(group.label).font(.headline).foregroundStyle(CatchCheckColor.navy).padding(.top, 6)
                            }
                            ForEach(group.windows, id: \.windowID) { spot in
                                VStack(alignment: .leading, spacing: 6) {
                                    if spot.windowID == best {
                                        Label("Best pick", systemImage: "star.fill")
                                            .font(.subheadline.bold()).foregroundStyle(CatchCheckColor.accent).padding(.leading, 16).padding(.top, 4)
                                    }
                                    RecommendationCard(spot: spot, showWhy: shouldShowWindowReason(spot, among: vm.recommendations)) {
                                        vm.trackSpotOpened(spot)
                                        vm.showingResults = false; vm.selectedSpot = spot
                                    }
                                }
                                .listRowInsets(EdgeInsets())
                            }
                        }
                        if !vm.nearbyPlacesWithoutWindows.isEmpty {
                            Text("No planning window available at: \(vm.nearbyPlacesWithoutWindows.prefix(5).joined(separator: ", ")).")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    if !vm.failedSpotNames.isEmpty && !vm.isLoadingRecommendations && vm.recommendationError == nil {
                        Text("Forecasts failed for \(vm.failedSpotNames.count) nearby spot(s); no planning windows are shown for those spots.")
                            .font(.footnote).foregroundStyle(CatchCheckColor.orange)
                    }
                } footer: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Planning windows compare forecast conditions. Local access, wave exposure, marine warnings and current MPI rules still need checking.")
                        Link("Weather and offshore waves: Open-Meteo", destination: URL(string: "https://open-meteo.com/")!)
                        Link("Tide predictions, where verified: LINZ", destination: URL(string: "https://www.linz.govt.nz/products-services/tides-and-tidal-streams/tide-predictions")!)
                        Text("See each window for its sources and limitations.")
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
    let compact: Bool

    init(compact: Bool = false) { self.compact = compact }

    var body: some View {
        Button { showingPicker = true } label: {
            if compact {
                HStack(spacing: 6) {
                    Text(vm.locationSummary)
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    Image(systemName: "chevron.right").font(.caption.bold())
                }
                .foregroundStyle(CatchCheckColor.accent)
                .padding(.vertical, 2)
                .contentShape(Rectangle())
            } else {
                HStack(spacing: 10) {
                    Image(systemName: "mappin.and.ellipse").foregroundStyle(CatchCheckColor.accent)
                    Text(vm.locationSummary).font(.headline).foregroundStyle(CatchCheckColor.navy)
                    Spacer()
                    Text("Change").font(.subheadline.weight(.semibold)).foregroundStyle(CatchCheckColor.accent)
                }
                .padding(12)
                .background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(vm.locationSummary). Change place")
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

    private var matchingSpots: [FishingSpot] {
        let sorted = fishingSpots.filter { $0.boat == vm.isBoatFishing }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        guard !query.isEmpty else { return sorted }
        return sorted.filter { $0.name.localizedStandardContains(query) || $0.area.localizedStandardContains(query) }
    }

    private var matchingTowns: [SearchOrigin] {
        let sorted = searchOrigins.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        guard !query.isEmpty else { return sorted }
        return sorted.filter { $0.name.localizedStandardContains(query) }
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
                            if vm.searchesFromDeviceLocation { Image(systemName: "checkmark") }
                        }
                    }
                } footer: {
                    Text("Search near you or a town, or pick one fishing spot or tide location. You can change it any time.")
                }
                if !matchingTowns.isEmpty {
                    Section {
                        ForEach(matchingTowns) { town in
                            Button {
                                vm.selectSearchOrigin(town)
                                dismiss()
                            } label: {
                                HStack {
                                    Text(town.name)
                                    Spacer()
                                    if vm.selectedSearchOrigin == town { Image(systemName: "checkmark") }
                                }
                            }
                        }
                    } header: {
                        Text("Towns")
                    } footer: {
                        Text("Search within a distance of the town.")
                    }
                }
                if !matchingSpots.isEmpty {
                    Section {
                        ForEach(matchingSpots, id: \.id) { spot in
                            Button {
                                vm.selectSearchSpot(spot)
                                dismiss()
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(spot.name)
                                        Text(spot.area).font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if vm.selectedSearchSpotName == spot.name { Image(systemName: "checkmark") }
                                }
                            }
                        }
                    } header: {
                        Text(vm.isBoatFishing ? "Boat fishing areas" : "Fishing spots")
                    } footer: {
                        Text("Search just this spot, or around it.")
                    }
                }
                if !matchingStations.isEmpty { Section("Tide locations") {
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
                } }
            }
            .searchable(text: $query, prompt: "Search towns, spots or tide locations")
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Where are you fishing?")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
        }
    }
}

/// What the share sheet sends for a fishing window.
struct ShareContent: Identifiable {
    let id = UUID()
    let subject: String
    let text: String
}

/// The system share sheet, shown once the link is ready. `ShareLink` cannot wait for a network call.
struct ActivityView: UIViewControllerRepresentable {
    let content: ShareContent

    private final class Item: NSObject, UIActivityItemSource, Sendable {
        let content: ShareContent
        init(_ content: ShareContent) { self.content = content }
        func activityViewControllerPlaceholderItem(_ controller: UIActivityViewController) -> Any { content.text }
        func activityViewController(_ controller: UIActivityViewController, itemForActivityType type: UIActivity.ActivityType?) -> Any? { content.text }
        func activityViewController(_ controller: UIActivityViewController, subjectForActivityType type: UIActivity.ActivityType?) -> String { content.subject }
    }

    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: [Item(content)], applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct SpotDetailView: View {
    @EnvironmentObject private var vm: FishingViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var addToCalendar = false
    @State private var showingCalendarEditor = false
    @State private var preparingShare = false
    @State private var shareContent: ShareContent?
    let spot: Recommendation
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(spot.name).font(.largeTitle.bold()).foregroundStyle(CatchCheckColor.navy)
                    if spot.hasHighlights {
                        WindowPlace(spot: spot)
                    } else {
                        Text(spot.area).foregroundStyle(.secondary)
                    }
                    if !spot.time.isEmpty {
                        if spot.hasHighlights {
                            WindowTimeHeader(spot: spot)
                            WindowKeyTiles(spot: spot)
                            WindowWatchOut(spot: spot)
                            if let note = spot.forecastNote { Text(note).font(.caption).foregroundStyle(.secondary) }
                            DisclosureGroup("Details & sources") { WindowSources(spot: spot).padding(.top, 6) }.font(.caption)
                        } else {
                            WindowOutlook(outlook: spot.windowOutlook)
                            WindowChecks(spot: spot)
                            Text(spot.time).font(.title3.bold()).foregroundStyle(CatchCheckColor.orange)
                            Text(spot.distance).font(.subheadline).foregroundStyle(.secondary)
                        }
                        MarineProtectionNoticeView(point: spot.coordinate)
                        if spot.boat, let point = spot.coordinate { NearbyRampsCard(point: point, start: spot.startsAt, end: spot.endsAt) }
                        if shouldShowWindowReason(spot, among: vm.recommendations) {
                            Card(background: CatchCheckColor.seafoam) {
                                Text("Why this window").bold().foregroundStyle(CatchCheckColor.navy)
                                Text(windowReason(spot) ?? "")
                                if let alternative = spot.alternative, !alternative.isEmpty {
                                    Text("Another option").font(.subheadline.bold()).foregroundStyle(CatchCheckColor.navy)
                                    Text(alternative).foregroundStyle(.secondary)
                                }
                            }
                        } else if let alternative = spot.alternative, !alternative.isEmpty {
                            Card(background: CatchCheckColor.seafoam) {
                                Text("Another option").font(.subheadline.bold()).foregroundStyle(CatchCheckColor.navy)
                                Text(alternative).foregroundStyle(.secondary)
                            }
                        }
                        if !spot.conditions.isEmpty {
                            Card {
                                Text("Conditions").bold().foregroundStyle(CatchCheckColor.navy)
                                WindowConditions(spot: spot)
                            }
                        }
                        if !spot.warnings.isEmpty && spot.assessment == nil {
                            Card {
                                Text("Check before you go").bold().foregroundStyle(CatchCheckColor.navy)
                                WindowWarnings(warnings: spot.warnings)
                            }
                        }
                        if !spot.sourceNote.isEmpty && spot.assessment == nil {
                            Text(spot.sourceNote).font(.caption).foregroundStyle(.secondary)
                        }
                    } else {
                        Card { Text("Search from Home to compare forecast conditions and find a planning window for this spot.").foregroundStyle(.secondary) }
                    }
                    if let place = spot.mapPlace {
                        Button { vm.showOnMap(place); dismiss() } label: { Label("Show on map", systemImage: "map") }
                            .buttonStyle(.bordered).frame(maxWidth: .infinity)
                    }
                    if SharedWindowLink.canShare(spot) {
                        Button {
                            preparingShare = true
                            Task {
                                // A short link when the service answers, the long link otherwise, so sharing works offline.
                                let link = await ShareLinkService.live.link(for: spot)
                                preparingShare = false
                                if let link { shareContent = ShareContent(subject: "Fishing window · \(spot.name)", text: SharedWindowLink.text(for: spot, url: link)) }
                            }
                        } label: {
                            Label(preparingShare ? "Creating link…" : "Share this window", systemImage: "square.and.arrow.up")
                        }.buttonStyle(.bordered).frame(maxWidth: .infinity).disabled(preparingShare)
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
                    Text("Starting a trip saves its forecast and tides for offline use and checks the forecast again before you go.")
                        .font(.caption).foregroundStyle(.secondary)
                }.padding(20)
            }
            .background(CatchCheckColor.cream)
            .navigationTitle("Spot details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { vm.selectedSpot = nil; dismiss() } } }
            .sheet(item: $shareContent) { ActivityView(content: $0).presentationDetents([.medium, .large]) }
            .sheet(isPresented: $showingCalendarEditor, onDismiss: {
                vm.startTrip(spot)
                dismiss()
            }) { TripCalendarEditor(trip: spot) }
        }
    }
}

struct RecommendationCard: View {
    @EnvironmentObject private var vm: FishingViewModel
    let spot: Recommendation
    var showWhy = true
    let action: () -> Void
    @State private var showAllConditions = false

    var body: some View {
        if spot.hasHighlights { highlighted } else { classic }
    }

    /// Time, outlook, three key numbers and any watch-outs first; the full conditions fold away.
    private var highlighted: some View {
        VStack(alignment: .leading, spacing: 12) {
            WindowTimeHeader(spot: spot)
            WindowPlace(spot: spot, compact: true)
            WindowKeyTiles(spot: spot)
            WindowWatchOut(spot: spot)
            if let note = spot.forecastNote { Text(note).font(.caption).foregroundStyle(.secondary) }
            // Tap targets rather than Buttons: a List fires a row's first Button when the row itself is tapped.
            HStack {
                HStack(spacing: 4) {
                    Text(showAllConditions ? "Hide conditions" : "All conditions")
                    Image(systemName: showAllConditions ? "chevron.up" : "chevron.down").font(.caption.bold())
                }
                .padding(.vertical, 4).contentShape(Rectangle())
                .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { showAllConditions.toggle() } }
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { showAllConditions.toggle() }
                Spacer()
                if let place = spot.mapPlace {
                    Label("Show on map", systemImage: "map")
                        .padding(.vertical, 4).contentShape(Rectangle())
                        .onTapGesture { vm.showOnMap(place) }
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(.isButton)
                        .accessibilityAction { vm.showOnMap(place) }
                }
            }
            .font(.subheadline.weight(.semibold)).foregroundStyle(CatchCheckColor.accent)
            if showAllConditions {
                WindowConditions(spot: spot)
                WindowSources(spot: spot).font(.caption)
            }
        }
        .foregroundStyle(CatchCheckColor.navy)
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
        .contentShape(Rectangle())
        .onTapGesture(perform: action)
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Open window", action)
    }

    private var classic: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(spot.name).font(.title3.bold())
                    Text("\(spot.area) · \(spot.boat ? "Boat" : "Land")").font(.subheadline).foregroundStyle(.secondary)
                    if !spot.time.isEmpty {
                        WindowOutlook(outlook: spot.windowOutlook)
                        WindowChecks(spot: spot, showDetails: false)
                    }
                    if !spot.time.isEmpty { Text(spot.time).font(.headline) }
                    Text(spot.distance).font(.caption).foregroundStyle(.secondary)
                }
                if !spot.time.isEmpty, showWhy {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("WHY THIS WINDOW").font(.caption2.bold()).foregroundStyle(CatchCheckColor.accent)
                        Text(windowReason(spot) ?? "").font(.subheadline)
                    }
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 12))
                }
                if !spot.time.isEmpty {
                    if !spot.conditions.isEmpty {
                        Text("Conditions").font(.subheadline.bold())
                        WindowConditions(spot: spot)
                    }
                }
                if !spot.warnings.isEmpty && spot.assessment == nil {
                    WindowWarnings(warnings: spot.warnings)
                }
            }
            .foregroundStyle(CatchCheckColor.navy)
            .padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
        }
    }
}

/// "Waikato · Land · 41 km", plus the distance note when it is not a plain distance (a selected tide location or a shared window).
private struct WindowPlace: View {
    let spot: Recommendation
    var compact = false
    private var kilometres: String? {
        guard spot.distance.hasSuffix("km straight-line") else { return nil }
        return spot.distance.replacingOccurrences(of: " straight-line", with: "")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if compact { Text(spot.name).font(.headline).foregroundStyle(CatchCheckColor.navy) }
            Text("\(spot.area) · \(spot.boat ? "Boat" : "Land")\(kilometres.map { " · \($0)" } ?? "")")
                .font(compact ? .subheadline : .body).foregroundStyle(.secondary)
            if kilometres == nil && !spot.distance.isEmpty {
                Text(spot.distance).font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

private func toneColors(_ tone: OutlookTone) -> (fill: Color, text: Color) {
    switch tone {
    case .good: (CatchCheckColor.goodFill, CatchCheckColor.goodText)
    case .caution: (CatchCheckColor.cautionFill, CatchCheckColor.cautionText)
    case .poor: (CatchCheckColor.poorFill, CatchCheckColor.poorText)
    case .unknown: (CatchCheckColor.tile, CatchCheckColor.tileText)
    }
}

/// The day, the two-hour window in large type, and the coloured outlook badge.
private struct WindowTimeHeader: View {
    let spot: Recommendation
    var body: some View {
        let when = spot.windowDayAndHours
        let mood = spot.windowMood
        let colors = toneColors(WindowHighlights.tone(mood.label))
        HStack(alignment: .top, spacing: 8) {
            // The date and time are what a person plans around, so they take the accent colour.
            VStack(alignment: .leading, spacing: 1) {
                Label(when.day, systemImage: "calendar").font(.subheadline.weight(.semibold))
                Text(when.hours).font(.title3.bold()).lineLimit(1).minimumScaleFactor(0.8)
            }
            .foregroundStyle(CatchCheckColor.accent)
            Spacer(minLength: 0)
            Text("\(mood.emoji) \(mood.label)")
                .font(.subheadline.weight(.semibold)).foregroundStyle(colors.text).lineLimit(1)
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(colors.fill, in: Capsule())
                .accessibilityLabel("Window outlook: \(mood.label)")
        }
    }
}

/// Three key numbers; one that needs attention turns amber.
private struct WindowKeyTiles: View {
    let spot: Recommendation
    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            ForEach(Array(spot.keyTiles.enumerated()), id: \.offset) { _, tile in
                VStack(alignment: .leading, spacing: 2) {
                    Text(tile.label).font(.caption)
                        .foregroundStyle(tile.concern ? CatchCheckColor.cautionText : CatchCheckColor.tileText)
                    Text(tile.value).font(.headline).lineLimit(1).minimumScaleFactor(0.75)
                        .foregroundStyle(tile.concern ? CatchCheckColor.cautionText : CatchCheckColor.navy)
                    if !tile.detail.isEmpty {
                        Text(tile.detail).font(.caption2).lineLimit(3)
                            .foregroundStyle(tile.concern ? CatchCheckColor.cautionText : CatchCheckColor.tileText)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding(10)
                .background(tile.concern ? CatchCheckColor.cautionFill : CatchCheckColor.tile, in: RoundedRectangle(cornerRadius: 12))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(tile.label) \(tile.value) \(tile.detail)\(tile.concern ? ", needs attention" : "")")
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

/// The amber box: only shown when something needs attention.
private struct WindowWatchOut: View {
    let spot: Recommendation
    var body: some View {
        let lines = spot.watchOuts
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(lines, id: \.self) { line in
                    Label(line, systemImage: "exclamationmark.triangle.fill").font(.subheadline)
                }
            }
            .foregroundStyle(CatchCheckColor.cautionText)
            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(CatchCheckColor.cautionFill, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}

private struct WindowSources: View {
    let spot: Recommendation
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(spot.assessment?.details ?? [], id: \.self) { Text($0) }
            if !spot.sourceNote.isEmpty { Text(spot.sourceNote) }
        }
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private func windowReason(_ spot: Recommendation) -> String? {
    let reason = (spot.reasons.first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                  ?? spot.summary).trimmingCharacters(in: .whitespacesAndNewlines)
    return reason.isEmpty ? nil : reason
}

private func comparableWindowReason(_ reason: String) -> String {
    let qualification = "Some essential local data is missing; treat this as a time to investigate. "
    return (reason.hasPrefix(qualification) ? String(reason.dropFirst(qualification.count)) : reason)
        .trimmingCharacters(in: .whitespacesAndNewlines)
}

private func shouldShowWindowReason(_ spot: Recommendation, among results: [Recommendation]) -> Bool {
    guard let reason = windowReason(spot) else { return false }
    guard results.count > 1 else { return true }
    return results.contains { comparableWindowReason(windowReason($0) ?? "") != comparableWindowReason(reason) }
}

private struct WindowOutlook: View {
    let outlook: String

    var body: some View {
        let parts = outlook.split(separator: " ", maxSplits: 1).map(String.init)
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("WINDOW OUTLOOK").font(.caption2.bold()).foregroundStyle(.secondary)
                Text(parts.count > 1 ? parts[1] : outlook)
                    .font(.subheadline.bold()).foregroundStyle(CatchCheckColor.navy)
            }
            Spacer(minLength: 0)
            if let emoji = parts.first, parts.count > 1 {
                Text(emoji).font(.title).accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Window outlook: \(outlook)")
    }
}

private struct WindowConditions: View {
    let spot: Recommendation
    private let labels = ["Wind", "Rain", "Tide", "Daylight", "Waves"]
    private var indices: [Int] {
        let order = spot.boat ? [4] + spot.conditions.indices.filter { $0 != 4 } : Array(spot.conditions.indices)
        return order.filter { spot.conditions.indices.contains($0) }
    }

    var body: some View {
        if let assessment = spot.assessment {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(assessment.conditions.enumerated()), id: \.offset) { position, condition in
                    if position > 0 { Divider().padding(.vertical, 6) }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(condition.title).font(.caption.bold()).foregroundStyle(CatchCheckColor.accent)
                        Text(PlainConditions.windowValue(condition)).font(.subheadline)
                            .foregroundStyle(CatchCheckColor.navy).fixedSize(horizontal: false, vertical: true)
                        Text("\(condition.mood.emoji) \(condition.mood.label)").font(.caption).foregroundStyle(.secondary)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        } else {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(indices.enumerated()), id: \.element) { position, index in
                if position > 0 { Divider().padding(.vertical, 8) }
                let label = index < labels.count ? labels[index] : "Other"
                let mood = spot.conditionMood(index)
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 8) {
                        Text(label).font(.caption.bold()).foregroundStyle(CatchCheckColor.accent)
                        Text("\(mood.emoji) \(mood.label)").font(.caption)
                    }
                    Text(spot.conditions[index]).font(.subheadline).foregroundStyle(CatchCheckColor.navy)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        }
    }
}

private struct WindowChecks: View {
    let spot: Recommendation
    var showDetails = true
    var body: some View {
        if let assessment = spot.assessment {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(assessment.checks.emoji) \(assessment.checks.label)")
                Text("\(assessment.confidence.emoji) \(assessment.confidence.label)")
                if showDetails {
                    DisclosureGroup("Details & sources") {
                        VStack(alignment: .leading, spacing: 6) {
                            ForEach(assessment.details, id: \.self) { Text($0) }
                            if !spot.sourceNote.isEmpty { Text(spot.sourceNote) }
                        }.padding(.top, 6).foregroundStyle(.secondary)
                    }
                }
            }.font(.caption)
        }
    }
}

private struct WindowWarnings: View {
    let warnings: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            ForEach(warnings, id: \.self) { warning in
                HStack(alignment: .top, spacing: 8) {
                    Text(warningMood(warning).emoji).font(.subheadline).padding(.top, 2)
                    Text(warning).font(.subheadline).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .foregroundStyle(CatchCheckColor.warning)
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(CatchCheckColor.warning.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
    }
}
private struct FishCheckCard: View {
    @EnvironmentObject private var vm: FishingViewModel
    let result: FishCheck
    private var areaName: String {
        fishingRulesAreas.first(where: { $0.id == vm.selectedRulesAreaID })?.name ?? "Choose an area"
    }
    private var officialURL: URL {
        fishingRulesAreas.first(where: { $0.id == vm.selectedRulesAreaID })?.officialURL
            ?? result.rulesSourceURL
            ?? URL(string: "https://www.mpi.govt.nz/fishing-aquaculture/recreational-fishing/fishing-rules")!
    }
    private var nearRaglanWest: Bool {
        result.areaID == "auckland-kermadec" && vm.isNearRaglan && !vm.rulesAreaSelectionIsManual
    }
    private var shownRules: [FishRuleMatch] {
        let westRows = result.fishRules.filter { $0.species.localizedCaseInsensitiveContains("Auckland West") }
        return nearRaglanWest && result.commonName.localizedCaseInsensitiveCompare("Snapper") == .orderedSame && !westRows.isEmpty
            ? westRows : result.fishRules
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text("IDENTIFICATION").font(.caption2.bold()).foregroundStyle(CatchCheckColor.accent)
                Text(cleanIdentificationText(result.commonName)).font(.title3.bold()).foregroundStyle(CatchCheckColor.navy)
                    .fixedSize(horizontal: false, vertical: true)
                if !result.scientificName.isEmpty {
                    Text(cleanIdentificationText(result.scientificName)).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            Text(result.isFish ? "\(result.confidence)% AI confidence" : "Not a fish")
                .font(.caption.bold()).foregroundStyle(CatchCheckColor.accent)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(CatchCheckColor.surface, in: Capsule())
            if !result.visibleClues.isEmpty {
                FishDescriptionSection(title: "VISIBLE CLUES", detail: cleanIdentificationText(result.visibleClues))
                    .id(result.visibleClues)
            }
            if !result.otherPossibilities.isEmpty {
                VStack(alignment: .leading, spacing: 3) {
                    Text("OTHER POSSIBILITIES").font(.caption2.bold()).foregroundStyle(CatchCheckColor.accent)
                    Text(result.otherPossibilities.map(cleanIdentificationText).joined(separator: " · ")).font(.subheadline).foregroundStyle(CatchCheckColor.navy)
                }
            }
            if !result.identificationNote.isEmpty && result.identificationNote.localizedCaseInsensitiveCompare("This is not a fish.") != .orderedSame {
                FishDescriptionSection(title: "IDENTIFICATION NOTE", detail: cleanIdentificationText(result.identificationNote), highlighted: true)
                    .id(result.identificationNote)
            }
            if result.isFish {
                Divider()
                VStack(alignment: .leading, spacing: 3) {
                    Text("MPI RULES · \(areaName)").font(.subheadline.bold()).foregroundStyle(CatchCheckColor.navy)
                    if let reviewed = result.rulesReviewedAt {
                        Text("Reviewed \(reviewed)").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                if nearRaglanWest {
                    Text("Near Raglan: check the Auckland West snapper subarea for the exact catch spot.")
                        .font(.caption).foregroundStyle(CatchCheckColor.accent)
                }
                rulesContent
                Text("Check local closures before keeping a fish.").font(.caption).foregroundStyle(.secondary)
                Link("See official MPI rules", destination: officialURL).font(.subheadline.weight(.semibold))
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(CatchCheckColor.seafoam, in: RoundedRectangle(cornerRadius: 14))
    }

    @ViewBuilder private var rulesContent: some View {
        if vm.isLoadingFishRules {
            message("Loading size and catch limits for this area…")
        } else if let error = vm.fishRulesError {
            message(error)
            Button("Try loading rules again") { vm.retryFishRules() }
                .font(.subheadline.weight(.semibold))
        } else if result.rulesNeedsReview {
            message("MPI has updated this area's rules. Check the official page for current limits.")
        } else if vm.selectedRulesAreaID == nil {
            message("Choose the MPI area where this fish was caught to see local limits.")
        } else if shownRules.isEmpty {
            message("No reliable simple limit can be shown from this saved table. Check MPI for this species and exact subarea before keeping it.")
        } else {
            ForEach(shownRules) { rule in
                VStack(alignment: .leading, spacing: 8) {
                    Text(cleanMPIRuleText(rule.species)).font(.subheadline.bold()).foregroundStyle(CatchCheckColor.navy)
                    if rule.details.contains(where: { $0.label.localizedCaseInsensitiveContains("daily limit") || $0.label.localizedCaseInsensitiveContains("bag limit") }) {
                        Text("Limits vary by subarea. Check the exact catch spot on MPI.")
                            .font(.caption).foregroundStyle(CatchCheckColor.navy)
                    } else {
                        if let minimumSize = rule.minimumSize { FishRuleValueRow(label: cleanMPIRuleText(rule.minimumSizeLabel ?? "Minimum size"), value: cleanMPILimitValue(minimumSize)) }
                        if let dailyLimit = rule.dailyLimit { FishRuleValueRow(label: "Daily limit", value: cleanMPILimitValue(dailyLimit)) }
                    }
                    if hasMPIFootnote(rule) {
                        Text("MPI footnote applies. Read the full condition before keeping this fish.")
                            .font(.caption).foregroundStyle(.secondary)
                        Link("Read MPI footnote", destination: officialURL).font(.subheadline.weight(.semibold))
                    }
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 10))
            }
        }
    }

    private func message(_ text: String) -> some View {
        Text(text).font(.subheadline).foregroundStyle(CatchCheckColor.navy)
            .padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct FishDescriptionSection: View {
    let title: String
    let detail: String
    var highlighted = false
    @State private var expanded = false
    private var canExpand: Bool { detail.count > (highlighted ? 80 : 150) }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption2.bold()).foregroundStyle(CatchCheckColor.accent)
            Text(detail).font(.subheadline).foregroundStyle(highlighted ? Color.secondary : CatchCheckColor.navy)
                .lineLimit(canExpand && !expanded ? (highlighted ? 2 : 3) : nil)
                .fixedSize(horizontal: false, vertical: true)
            if canExpand {
                Button(expanded ? "Show less" : "Show more") { expanded.toggle() }
                    .font(.caption.weight(.semibold))
            }
        }
        .padding(highlighted ? 10 : 0)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(highlighted ? CatchCheckColor.surface : Color.clear, in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct FishRuleValueRow: View {
    let label: String
    let value: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Spacer(minLength: 0)
            Text(value).font(.subheadline.bold()).foregroundStyle(CatchCheckColor.navy)
                .multilineTextAlignment(.trailing)
        }
    }
}
struct Card<Content: View>: View { var background: Color = CatchCheckColor.surface; @ViewBuilder let content: Content; var body: some View { VStack(alignment: .leading, spacing: 10, content: { content }).padding(18).frame(maxWidth: .infinity, alignment: .leading).background(background, in: RoundedRectangle(cornerRadius: 18)) } }
private struct Metric: View { let label: String; let value: String; var body: some View { VStack(alignment: .leading) { Text(label).font(.caption).foregroundStyle(.secondary); Text(value).bold().foregroundStyle(CatchCheckColor.navy) } } }
struct ChoiceButton: ButtonStyle { let selected: Bool; func makeBody(configuration: Configuration) -> some View { configuration.label.padding(.horizontal, 12).padding(.vertical, 8).background(selected ? CatchCheckColor.accent : CatchCheckColor.surface, in: Capsule()).foregroundStyle(selected ? .white : CatchCheckColor.navy).overlay(Capsule().stroke(selected ? CatchCheckColor.accent : CatchCheckColor.outline, lineWidth: 1)).opacity(configuration.isPressed ? 0.7 : 1) } }

struct CameraPicker: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    @Environment(\.dismiss) private var dismiss
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController(); picker.sourceType = .camera; picker.delegate = context.coordinator; picker.cameraCaptureMode = .photo
        picker.cameraOverlayView = FishCameraGuideView()
        return picker
    }
    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}
    final class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate { let parent: CameraPicker; init(parent: CameraPicker) { self.parent = parent }; func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) { parent.image = info[.originalImage] as? UIImage; parent.dismiss() }; func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() } }
}

/// The fish outline and "head up" hint over the camera, the same guide Android's camera shows. It ignores touches, so the
/// system shutter and controls underneath keep working.
private final class FishCameraGuideView: UIView {
    private let hint = UILabel()

    init() {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        isOpaque = false
        backgroundColor = .clear
        contentMode = .redraw
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        hint.text = "Hold the fish vertically — head up"
        hint.textColor = .white
        hint.font = .preferredFont(forTextStyle: .headline)
        hint.adjustsFontForContentSizeCategory = true
        hint.textAlignment = .center
        hint.numberOfLines = 0
        hint.layer.shadowColor = UIColor.black.cgColor
        hint.layer.shadowOpacity = 0.8
        hint.layer.shadowRadius = 3
        hint.layer.shadowOffset = .zero
        addSubview(hint)
    }

    required init?(coder: NSCoder) { nil }

    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        if let superview { frame = superview.bounds }
    }

    private var guideTop: CGFloat { bounds.height * 0.2 }
    private var guideBottom: CGFloat { bounds.height * 0.72 }

    override func layoutSubviews() {
        super.layoutSubviews()
        let width = bounds.width - 48
        let height = hint.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        hint.frame = CGRect(x: 24, y: max(safeAreaInsets.top + 52, guideTop - height - 12), width: width, height: height)
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        let w = bounds.width, top = guideTop, bottom = guideBottom, h = bottom - top, centerX = w / 2
        UIColor.white.withAlphaComponent(0.9).setStroke()
        let body = UIBezierPath()
        body.move(to: CGPoint(x: centerX, y: top + h * 0.12))
        body.addCurve(to: CGPoint(x: centerX, y: bottom - h * 0.12),
                      controlPoint1: CGPoint(x: w * 0.28, y: top + h * 0.2), controlPoint2: CGPoint(x: w * 0.28, y: bottom - h * 0.2))
        body.addCurve(to: CGPoint(x: centerX, y: top + h * 0.12),
                      controlPoint1: CGPoint(x: w * 0.72, y: bottom - h * 0.2), controlPoint2: CGPoint(x: w * 0.72, y: top + h * 0.2))
        body.close()
        body.lineWidth = 4
        body.stroke()
        let tail = UIBezierPath()
        tail.move(to: CGPoint(x: centerX, y: bottom - h * 0.09))
        tail.addLine(to: CGPoint(x: w * 0.36, y: bottom))
        tail.addLine(to: CGPoint(x: w * 0.64, y: bottom))
        tail.close()
        tail.lineWidth = 4
        tail.stroke()
        UIColor.white.withAlphaComponent(0.9).setFill()
        UIBezierPath(ovalIn: CGRect(x: centerX - 5, y: top + h * 0.2 - 5, width: 10, height: 10)).fill()
    }
}
