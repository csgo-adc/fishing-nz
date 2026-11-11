import SwiftUI
import UIKit

/// The first-launch tour: after the two setup questions, Home dims and lights up one part at a time with a short note
/// and Skip / Back / Next, ending on "You're all set". It runs once; More → Settings → App tour shows it again.
/// Android has the same steps and words in `AppTour.kt`.
enum TourStep: Int, CaseIterable {
    case place, today, best, plan, shortcuts, tabs, done

    var title: String {
        switch self {
        case .place: "Where you fish"
        case .today: "Today"
        case .best: "Your best fishing window"
        case .plan: "Your plan"
        case .shortcuts: "Shortcuts"
        case .tabs: "Tabs"
        case .done: "You're all set"
        }
    }

    var body: String {
        switch self {
        case .place: "Tap here to change your town or spot, or use your location."
        case .today: "The tide, wind, moon and sunset for this place today."
        case .best: "Home finds the best two hours for your plan by itself. Tap the card to see why."
        case .plan: "Switch land or boat and the days here. Edit plan has distance and hours."
        case .shortcuts: "Rules, fish ID, your catch log and trips, one tap away."
        case .tabs: "Spots has the map. Tide and Weather have the forecasts. More has your account and settings."
        case .done: "Replay this tour any time in More → Settings → App tour."
        }
    }

    /// The Home view Home scrolls to for this step, if any.
    var scrollID: String? {
        switch self {
        case .place, .today: "tour.top"
        case .best: "tour.best"
        case .plan: "tour.plan"
        case .shortcuts: "tour.shortcuts"
        case .tabs, .done: nil
        }
    }
}

@MainActor final class AppTour: ObservableObject {
    static let shared = AppTour()
    /// The step on screen, or nil when the tour isn't showing.
    @Published private(set) var step: TourStep?
    /// Where each lit-up part of Home is on screen; filled by `tourTarget(_:)`.
    @Published private(set) var frames: [TourStep: CGRect] = [:]
    private let defaults = UserDefaults.standard

    /// The first-launch setup has just finished: the tour shows once Home is on screen.
    func markPending() { defaults.set(true, forKey: "appTourPending") }

    /// Starts the tour if the setup asked for it and it hasn't been shown or skipped yet.
    func startIfPending() {
        guard step == nil, defaults.bool(forKey: "appTourPending"), !defaults.bool(forKey: "appTourDone") else { return }
        step = .place
    }

    /// Settings → App tour.
    func start() { step = .place }

    func next() {
        guard let step else { return }
        if let following = TourStep(rawValue: step.rawValue + 1) { self.step = following } else { finish() }
    }

    func back() {
        guard let step, step.rawValue > 0 else { return }
        self.step = TourStep(rawValue: step.rawValue - 1)
    }

    /// Finish or Skip: the tour doesn't show by itself again.
    func finish() {
        step = nil
        defaults.set(false, forKey: "appTourPending")
        defaults.set(true, forKey: "appTourDone")
    }

    func report(_ step: TourStep, _ frame: CGRect) { if frames[step] != frame { frames[step] = frame } }
}

extension View {
    /// Marks the part of Home that `step` lights up.
    func tourTarget(_ step: TourStep) -> some View {
        background(GeometryReader { proxy in
            Color.clear
                .onAppear { AppTour.shared.report(step, proxy.frame(in: .global)) }
                .onChange(of: proxy.frame(in: .global)) { _, frame in AppTour.shared.report(step, frame) }
        })
    }
}

/// The dimmed screen with a lit-up target and the step's note. Drawn above the tab bar while the tour runs.
struct TourOverlay: View {
    @ObservedObject private var tour = AppTour.shared

    var body: some View {
        if let step = tour.step {
            GeometryReader { proxy in
                let size = proxy.size
                let origin = proxy.frame(in: .global).origin
                let hole = holeRect(step, size: size, origin: origin)
                ZStack {
                    Path { path in
                        path.addRect(CGRect(origin: .zero, size: size))
                        if let hole { path.addRoundedRect(in: hole, cornerSize: CGSize(width: 18, height: 18)) }
                    }
                    .fill(Color.black.opacity(0.58), style: FillStyle(eoFill: true))
                    // Taps on the dimmed part do nothing; only the note's buttons work while the tour shows.
                    .contentShape(Rectangle())
                    .onTapGesture {}
                    VStack(spacing: 0) {
                        if let hole, hole.midY < size.height / 2 {
                            Color.clear.frame(height: min(max(hole.maxY + 14, 0), size.height))
                            card(step)
                            Spacer(minLength: 0)
                        } else if let hole {
                            Spacer(minLength: 0)
                            card(step)
                            Color.clear.frame(height: min(max(size.height - hole.minY + 14, 0), size.height))
                        } else {
                            Spacer(minLength: 0)
                            card(step)
                            Spacer(minLength: 0)
                        }
                    }
                    .padding(.horizontal, 20)
                }
            }
            .ignoresSafeArea()
            .animation(.easeInOut(duration: 0.25), value: step)
            .transition(.opacity)
        }
    }

    /// The lit-up area: the target's frame with a margin, or the tab bar along the bottom of the screen.
    private func holeRect(_ step: TourStep, size: CGSize, origin: CGPoint) -> CGRect? {
        switch step {
        case .done: return nil
        case .tabs:
            let window = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.keyWindow
            let bottom = window?.safeAreaInsets.bottom ?? 0
            if #available(iOS 26, *) {
                // The floating tab bar of iOS 26.
                return CGRect(x: 10, y: size.height - bottom - 66, width: size.width - 20, height: 74)
            }
            return CGRect(x: 4, y: size.height - bottom - 53, width: size.width - 8, height: 53 + bottom)
        default:
            guard let frame = tour.frames[step] else { return nil }
            return frame.offsetBy(dx: -origin.x, dy: -origin.y).insetBy(dx: -8, dy: -8)
        }
    }

    private func card(_ step: TourStep) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: "\(step.rawValue + 1) / \(TourStep.allCases.count)").font(.caption).foregroundStyle(.secondary)
            Text(step.title).font(.headline).foregroundStyle(CatchCheckColor.navy)
            Text(step.body).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 10) {
                if step != .done { Button("Skip") { tour.finish() } }
                Spacer()
                if step.rawValue > 0 { Button("Back") { tour.back() }.buttonStyle(.bordered) }
                Button(step == .done ? "Finish" : "Next") { tour.next() }.buttonStyle(.borderedProminent)
            }
            .padding(.top, 6)
        }
        .padding(18)
        .frame(maxWidth: 460, alignment: .leading)
        .background(CatchCheckColor.surface, in: RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
    }
}
