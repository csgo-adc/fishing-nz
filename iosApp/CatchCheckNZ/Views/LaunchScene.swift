import SwiftUI

/// The animated scene that opens the app on every cold start: dawn over the sea, the app's fish in its tile, waves and
/// the name. On the first launch it settles into the welcome page, whose Get started leads to the two setup questions;
/// otherwise it fades away into the app. Android plays the same scene (`LaunchScene.kt`). See docs/launch-scene.md.
@MainActor
final class LaunchScene: ObservableObject {
    static let shared = LaunchScene()

    /// True from launch until the scene has gone.
    @Published private(set) var playing = true
    /// True once the scene has started to fade away, so the setup questions or a shared window can come up over it.
    @Published private(set) var leaving = false

    /// The scene still fills the screen: sheets, alerts and the setup questions wait.
    var covering: Bool { playing && !leaving }

    func beginLeaving() { leaving = true }
    func finish() { playing = false }
}

private extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }
}

/// The same colours as Android's scene; `night` is also the launch screen's `LaunchBackground`.
private enum Sea {
    static let night = Color(hex: 0x0A1A3F)
    static let skyMid = Color(hex: 0x163E80)
    static let dawn = Color(hex: 0xF0A67E)
    static let waveBack = Color(hex: 0x3E7BD6)
    static let waveMid = Color(hex: 0x1F4FC4)
    static let water = Color(hex: 0x0A2BB0)
    static let deep = Color(hex: 0x061A7A)
    static let foam = Color(hex: 0xBFE7F5)
    static let glint = Color(hex: 0xFFD9A8)
}

/// The fish fills this share of the tile's width, as on the launch screen (tools/export_launch_art.cjs).
private let fishWidth: CGFloat = 0.78
/// The tile's corner radius as a share of its side.
private let tileCorner: CGFloat = 32.0 / 120.0

/// Fixed stars: share of width, share of the sky's height, radius, twinkle offset.
private let stars: [(x: CGFloat, y: CGFloat, r: CGFloat, offset: Double)] = [
    (0.12, 0.10, 1.4, 0.0), (0.28, 0.22, 1.0, 0.7), (0.46, 0.07, 1.2, 1.9), (0.66, 0.16, 1.6, 2.6),
    (0.84, 0.09, 1.1, 3.4), (0.91, 0.30, 1.3, 4.1), (0.07, 0.34, 1.0, 4.8), (0.36, 0.40, 0.9, 5.5),
    (0.74, 0.42, 1.0, 1.2), (0.55, 0.28, 0.8, 2.2), (0.20, 0.52, 0.8, 3.0), (0.80, 0.55, 0.9, 5.0),
]

/// Bubbles rising through the water: share of width, start offset, radius.
private let bubbles: [(x: CGFloat, offset: Double, r: CGFloat)] = [
    (0.16, 0.0, 3.0), (0.78, 0.35, 2.2), (0.48, 0.62, 2.6), (0.30, 0.81, 1.8), (0.90, 0.15, 2.0),
]

private func clamp(_ value: Double) -> Double { min(max(value, 0), 1) }
private func easeOut(_ value: Double) -> Double { 1 - pow(1 - clamp(value), 3) }
private func easeIn(_ value: Double) -> Double { clamp(value) * clamp(value) }
/// A gently damped settle, like Android's spring.
private func settle(_ value: Double) -> Double { value >= 1 ? 1 : 1 - exp(-6 * max(value, 0)) * cos(4 * max(value, 0)) }
private func mix(_ from: CGFloat, _ to: CGFloat, _ progress: Double) -> CGFloat { from + (to - from) * CGFloat(progress) }

struct LaunchSceneView: View {
    /// Whether the first-launch welcome page follows; read when the opening finishes, so a shared window that opened
    /// meanwhile goes straight to the app.
    let welcome: () -> Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// When the opening began; until then the scene holds its first frame, which matches the launch screen.
    @State private var start: Date?
    @State private var welcomeStart: Date?
    @State private var exitStart: Date?
    @State private var tapped = false

    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation(minimumInterval: 1.0 / 60, paused: reduceMotion)) { timeline in
                scene(at: timeline.date, geo: geo)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { tapped = true }
        .task { await run() }
    }

    private func run() async {
        // The app's first frames are heavy; starting a moment later keeps the sunrise from being half over before
        // it is seen.
        try? await Task.sleep(for: .milliseconds(150))
        start = Date()
        // The opening lasts about 1.6 s; a tap cuts it short.
        let end = Date().addingTimeInterval(reduceMotion ? 0.7 : 1.65)
        while Date() < end && !tapped { try? await Task.sleep(for: .milliseconds(40)) }
        if welcome() { welcomeStart = Date() } else { await leave() }
    }

    private func leave() async {
        guard exitStart == nil else { return }
        exitStart = Date()
        LaunchScene.shared.beginLeaving()
        if reduceMotion {
            withAnimation(.easeOut(duration: 0.2)) { LaunchScene.shared.finish() }
        } else {
            try? await Task.sleep(for: .seconds(0.48))
            LaunchScene.shared.finish()
        }
    }

    @ViewBuilder
    private func scene(at date: Date, geo: GeometryProxy) -> some View {
        let insets = geo.safeAreaInsets
        let fullHeight = geo.size.height + insets.top + insets.bottom
        let time = reduceMotion ? 0 : start.map { max(date.timeIntervalSince($0), 0) } ?? 0
        let intro = reduceMotion ? 1 : easeOut(time / 0.95)
        let name = reduceMotion ? 1 : easeOut((time - 0.42) / 0.65)
        let sinceWelcome = welcomeStart.map { date.timeIntervalSince($0) }
        let toWelcome = sinceWelcome.map { reduceMotion ? 1 : settle($0 / 1.0) } ?? 0
        let items = (0..<5).map { index in sinceWelcome.map { reduceMotion ? 1 : easeOut(($0 - 0.26 - Double(index) * 0.11) / 0.52) } ?? 0 }
        let exit = reduceMotion ? 0 : exitStart.map { easeIn(date.timeIntervalSince($0) / 0.48) } ?? 0
        let compact = fullHeight < 700
        // The tile starts where the launch screen leaves it, in the middle, then rises.
        let settled: CGFloat = compact ? 100 : 120
        let tile = mix(120, settled, intro)
        let opening = mix(fullHeight / 2, fullHeight * 0.38, intro)
        let top = insets.top + (compact ? 28 : 52) + settled / 2
        let centre = mix(opening, top, toWelcome)
        let bob = sin(time * .pi / 1.8)
        let ring = reduceMotion ? 0 : easeOut(time.truncatingRemainder(dividingBy: 3.2) / 3.2) * intro

        ZStack(alignment: .top) {
            Canvas { context, size in
                drawSea(&context, size: size, time: time, intro: intro, welcome: toWelcome)
            }
            .ignoresSafeArea()
            .accessibilityHidden(true)

            VStack(spacing: 0) {
                FishTile(size: tile, corner: tile * tileCorner, bob: bob, ring: ring)
                VStack(spacing: 2) {
                    Text("Kia ora").font(.subheadline.weight(.medium)).tracking(2).foregroundStyle(Sea.foam)
                    Text("Fishdays - NZ").font(.system(size: compact ? 30 : 34, weight: .bold)).foregroundStyle(.white)
                        .shadow(color: Sea.night.opacity(0.45), radius: 7, y: 1)
                }
                .padding(.top, 18)
                .opacity(name).offset(y: (1 - name) * 14)
                Text("Find the best time and place to fish, anywhere in Aotearoa.")
                    .font(.callout).foregroundStyle(Color(hex: 0xDCE8FF)).multilineTextAlignment(.center)
                    .padding(.top, 10)
                    .appear(items[0])
            }
            .padding(.horizontal, 28)
            .frame(maxWidth: .infinity)
            .offset(y: centre - tile / 2 - insets.top)

            if toWelcome > 0 {
                VStack(spacing: 8) {
                    Highlight(symbol: "clock", text: "Your best fishing window, found for you").appear(items[1])
                    Highlight(symbol: "water.waves", text: "Tides, wind, waves and moon").appear(items[2])
                    Highlight(symbol: "ruler", text: "Size and bag limits, even offline").appear(items[3])
                    VStack(spacing: 10) {
                        Button { Task { await leave() } } label: {
                            HStack(spacing: 8) {
                                Text("Get started").font(.headline)
                                Image(systemName: "arrow.right").font(.headline)
                            }
                            .frame(maxWidth: .infinity).frame(height: 56)
                            .background(.white, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                            .foregroundStyle(Sea.night)
                        }
                        .buttonStyle(.plain)
                        Text("Two quick questions, then you're fishing").font(.footnote).foregroundStyle(Color(hex: 0xBFD3FF))
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, compact ? 6 : 12)
                    .appear(items[4])
                }
                .padding(.horizontal, 20).padding(.bottom, 20)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            }
        }
        .opacity(1 - exit)
        .scaleEffect(1 + 0.06 * exit)
    }

    /// The sky brightening into dawn, the sun rising behind the sea, and three layers of waves rolling in.
    private func drawSea(_ context: inout GraphicsContext, size: CGSize, time: Double, intro: Double, welcome: Double) {
        let w = size.width, h = size.height
        // The tide comes in under the welcome page, so its highlights sit in the water below the sunrise.
        let horizon = h * (0.63 - 0.09 * welcome)
        let wave = time * 2 * .pi / 7
        let glow = 0.5 - 0.5 * cos(time * .pi / 2.6)
        context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Sea.night))

        var sky = context
        sky.opacity = intro
        sky.fill(Path(CGRect(x: 0, y: 0, width: w, height: horizon + 2)), with: .linearGradient(
            Gradient(stops: [.init(color: Sea.night, location: 0), .init(color: Sea.skyMid, location: 0.62), .init(color: Sea.dawn, location: 1)]),
            startPoint: .zero, endPoint: CGPoint(x: 0, y: horizon + 2)))
        for star in stars {
            let s = sin(glow * .pi + star.offset)
            let twinkle = reduceMotion ? 0.7 : 0.45 + 0.4 * s * s
            let centre = CGPoint(x: star.x * w, y: star.y * horizon * 0.9)
            context.fill(Path(ellipseIn: CGRect(x: centre.x - star.r, y: centre.y - star.r, width: star.r * 2, height: star.r * 2)),
                         with: .color(.white.opacity(twinkle * (1 - 0.5 * star.y) * intro)))
        }

        // The sun comes up from below the horizon as the scene opens; the water drawn next hides its lower half.
        let sunCentre = CGPoint(x: w / 2, y: horizon + (1 - intro) * 60 - 4)
        let sunRadius = 74 * (1 + 0.06 * glow)
        sky.fill(Path(ellipseIn: CGRect(x: sunCentre.x - sunRadius, y: sunCentre.y - sunRadius, width: sunRadius * 2, height: sunRadius * 2)),
                 with: .radialGradient(Gradient(stops: [
                    .init(color: Color(hex: 0xFFE9C9), location: 0), .init(color: Color(hex: 0xF9B783), location: 0.32),
                    .init(color: Sea.dawn.opacity(0.35), location: 0.7), .init(color: Sea.dawn.opacity(0), location: 1),
                 ]), center: sunCentre, startRadius: 0, endRadius: sunRadius))

        // The sea rises into place from the bottom as the scene opens.
        let lift = (1 - intro) * (h - horizon + 40)
        fillWave(&context, size: size, baseline: horizon - 4 + lift, amplitude: 5, wavelength: w / 1.4, phase: wave,
                 shading: .color(Sea.waveBack.opacity(0.6)))
        fillWave(&context, size: size, baseline: horizon + 10 + lift, amplitude: 7, wavelength: w / 1.1, phase: -wave * 1.3 + 1.2,
                 shading: .color(Sea.waveMid.opacity(0.9)))
        fillWave(&context, size: size, baseline: horizon + 26 + lift, amplitude: 5, wavelength: w / 0.9, phase: wave * 0.8 + 2.4,
                 shading: .linearGradient(Gradient(colors: [Sea.water, Sea.deep]), startPoint: CGPoint(x: 0, y: horizon + lift), endPoint: CGPoint(x: 0, y: h)))

        // The sun's reflection glinting on the water; it fades under the welcome page's highlights.
        for index in 0..<6 where welcome < 1 {
            let i = Double(index)
            let y = horizon + 36 + i * 11 + lift
            let half = (46 - i * 6) * (0.7 + 0.3 * sin(wave * 2 + i * 1.3))
            let sway = sin(wave + i) * 4
            var line = Path()
            line.move(to: CGPoint(x: w / 2 - half + sway, y: y))
            line.addLine(to: CGPoint(x: w / 2 + half + sway, y: y))
            context.stroke(line, with: .color(Sea.glint.opacity(0.42 * (1 - i / 7) * intro * (1 - welcome))), style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
        }
        guard !reduceMotion else { return }
        for bubble in bubbles {
            let progress = (time / 6 + bubble.offset).truncatingRemainder(dividingBy: 1)
            let y = h - 40 - progress * 260
            let fade = progress < 0.15 ? progress / 0.15 : 1 - (progress - 0.15) / 0.85
            let x = bubble.x * w + sin(progress * 9 + bubble.offset * 6) * 6
            context.stroke(Path(ellipseIn: CGRect(x: x - bubble.r, y: y - bubble.r, width: bubble.r * 2, height: bubble.r * 2)),
                           with: .color(.white.opacity(0.55 * fade * intro)), lineWidth: 1.2)
        }
    }

    private func fillWave(_ context: inout GraphicsContext, size: CGSize, baseline: CGFloat, amplitude: CGFloat, wavelength: CGFloat,
                          phase: Double, shading: GraphicsContext.Shading) {
        var path = Path()
        path.move(to: CGPoint(x: 0, y: size.height))
        var x: CGFloat = 0
        while x <= size.width + 4 {
            path.addLine(to: CGPoint(x: x, y: baseline + amplitude * sin(x / wavelength * 2 * .pi + phase)))
            x += 4
        }
        path.addLine(to: CGPoint(x: size.width, y: size.height))
        path.closeSubpath()
        context.fill(path, with: shading)
    }
}

/// The pale-sea tile with the fish bobbing in it, and a ripple spreading out from it now and then.
private struct FishTile: View {
    let size: CGFloat
    let corner: CGFloat
    let bob: Double
    let ring: Double

    var body: some View {
        ZStack {
            if ring > 0 {
                Circle().stroke(.white.opacity((1 - ring) * 0.45), lineWidth: 1.6)
                    .frame(width: size * (1 + ring * 0.9), height: size * (1 + ring * 0.9))
            }
            ZStack {
                Image("LaunchSea").resizable().scaledToFill()
                Image("LaunchFish").resizable().scaledToFit().frame(width: size * fishWidth)
                    .rotationEffect(.degrees(bob * 3)).offset(y: bob * 3)
            }
            .frame(width: size, height: size)
            .clipShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
            .accessibilityElement()
            .accessibilityLabel(tr("Fishdays - NZ"))
        }
        .frame(width: size, height: size)
    }
}

private struct Highlight: View {
    let symbol: String
    let text: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).font(.body.weight(.semibold)).foregroundStyle(Sea.foam).frame(width: 22)
            Text(text).font(.subheadline.weight(.medium)).foregroundStyle(.white)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background(Sea.night.opacity(0.38), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(.white.opacity(0.24), lineWidth: 0.6))
    }
}

private extension View {
    /// Fades in and rises into place as `progress` goes from 0 to 1.
    func appear(_ progress: Double) -> some View { opacity(progress).offset(y: (1 - progress) * 16) }
}
