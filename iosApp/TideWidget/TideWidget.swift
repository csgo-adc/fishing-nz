import AppIntents
import SwiftUI
import WidgetKit

/// The Tides widget: the next high and low tide for a LINZ station chosen in the widget's settings, with the moon and
/// spring or neap tides. Android's widget follows the Tide tab's station instead (`TideWidget.kt`).
@main
struct TideWidgetBundle: WidgetBundle {
    var body: some Widget { TideWidget() }
}

struct TideWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "TideWidget", intent: TideWidgetIntent.self, provider: TideProvider()) { entry in
            TideWidgetView(entry: entry)
                .containerBackground(for: .widget) { Color(red: 0x18 / 255, green: 0x38 / 255, blue: 0x62 / 255) }
                .widgetURL(URL(string: "nz.fishingnz.catchcheck://tide?station=\(entry.stationID)"))
        }
        .configurationDisplayName("Tides")
        .description("The tide curve with the next high and low for a LINZ station, the moon and the wind.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular])
    }
}

/// A LINZ station in the widget's settings.
struct StationEntity: AppEntity {
    let id: String
    let name: String
    static var typeDisplayRepresentation: TypeDisplayRepresentation { "Tide station" }
    static var defaultQuery: StationQuery { StationQuery() }
    var displayRepresentation: DisplayRepresentation { DisplayRepresentation(title: "\(name)") }
    init(_ station: TideStation) { id = station.id; name = station.name }
}

struct StationQuery: EntityStringQuery {
    private var all: [StationEntity] { tideStations.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }.map(StationEntity.init) }
    func entities(for identifiers: [String]) async throws -> [StationEntity] { all.filter { identifiers.contains($0.id) } }
    func entities(matching string: String) async throws -> [StationEntity] { all.filter { $0.name.localizedStandardContains(string) } }
    func suggestedEntities() async throws -> [StationEntity] { all }
    func defaultResult() async -> StationEntity? { all.first { $0.id == "auckland" } }
}

struct TideWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource { "Tide station" }
    static var description: IntentDescription { IntentDescription("Choose the LINZ station whose tides the widget shows.") }
    @Parameter(title: "Station") var station: StationEntity?
}

struct WidgetTide: Sendable { let time: Date; let height: Double; let high: Bool }

/// The tide between LINZ highs and lows, eased like the app's curve. Android draws the same figure in `TideWidget.kt`.
enum WidgetTideCurve {
    /// The figure runs from 8 hours ago to 16 hours ahead.
    static let before: TimeInterval = 8 * 3600
    static let window: TimeInterval = 24 * 3600

    /// The height at `date`; nil outside the tides.
    static func level(_ tides: [WidgetTide], at date: Date) -> Double? {
        guard let afterIndex = tides.firstIndex(where: { $0.time >= date }) else { return nil }
        let after = tides[afterIndex]
        if after.time == date { return after.height }
        guard afterIndex > 0 else { return nil }
        let before = tides[afterIndex - 1]
        let total = after.time.timeIntervalSince(before.time)
        guard total > 0 else { return nil }
        let fraction = date.timeIntervalSince(before.time) / total
        return before.height + (after.height - before.height) * (1 - cos(.pi * fraction)) / 2
    }
}

struct TideEntry: TimelineEntry {
    let date: Date
    let stationID: String
    let stationName: String
    /// The LINZ highs and lows from a day before to two days after, in time order.
    let tides: [WidgetTide]
    let moon: MoonDay
    /// "12 km/h SW" when the wind loaded.
    let wind: String?

    /// The next tides after `date`, at most two.
    var next: [WidgetTide] { Array(tides.filter { $0.time > date }.prefix(2)) }
    var level: Double? { WidgetTideCurve.level(tides, at: date) }
    var comingIn: Bool? { next.first?.high }
    /// How full the tide is now between the day's lowest low and highest high, 0 to 1.
    var fullness: Double? {
        guard let level else { return nil }
        let nearby = tides.filter { abs($0.time.timeIntervalSince(date)) < 13 * 3600 }.map(\.height)
        guard let low = nearby.min(), let high = nearby.max(), high - low > 0.05 else { return nil }
        return min(max((level - low) / (high - low), 0), 1)
    }
}

struct TideProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> TideEntry {
        let now = Date.now
        let tides = [WidgetTide(time: now.addingTimeInterval(-15_000), height: 0.6, high: false), WidgetTide(time: now.addingTimeInterval(7_200), height: 3.1, high: true),
                     WidgetTide(time: now.addingTimeInterval(29_000), height: 0.5, high: false), WidgetTide(time: now.addingTimeInterval(51_000), height: 3.0, high: true)]
        return TideEntry(date: now, stationID: "auckland", stationName: "Auckland", tides: tides, moon: MoonPhase.forDay(now), wind: "12 km/h SW")
    }

    func snapshot(for configuration: TideWidgetIntent, in context: Context) async -> TideEntry {
        context.isPreview ? placeholder(in: context) : await entries(for: configuration).first ?? placeholder(in: context)
    }

    func timeline(for configuration: TideWidgetIntent, in context: Context) async -> Timeline<TideEntry> {
        let entries = await entries(for: configuration)
        // An entry every half hour moves the dot along the curve; ask again after the last one.
        let refresh = entries.last.map { $0.date.addingTimeInterval(1_800) } ?? .now.addingTimeInterval(1_800)
        return Timeline(entries: entries, policy: .after(refresh))
    }

    private func entries(for configuration: TideWidgetIntent) async -> [TideEntry] {
        let station = tideStations.first { $0.id == configuration.station?.id } ?? tideStations.first { $0.id == "auckland" }!
        let now = Date.now
        async let wind = Self.wind(latitude: station.latitude, longitude: station.longitude)
        guard let predictions = try? await LINZTideStore.shared.predictions(stationName: station.csvName,
                                                                            start: now.addingTimeInterval(-86_400), end: now.addingTimeInterval(2 * 86_400)) else {
            return [TideEntry(date: now, stationID: station.id, stationName: station.name, tides: [], moon: MoonPhase.forDay(now), wind: await wind)]
        }
        let sorted = predictions.sorted { $0.time < $1.time }
        let tides = sorted.indices.map { index -> WidgetTide in
            let neighbours = [index - 1, index + 1].filter { sorted.indices.contains($0) }.map { sorted[$0].height }
            return WidgetTide(time: sorted[index].time, height: sorted[index].height, high: neighbours.allSatisfy { sorted[index].height > $0 })
        }
        let windText = await wind
        return (0...24).map { step in
            let moment = now.addingTimeInterval(Double(step) * 1_800)
            return TideEntry(date: moment, stationID: station.id, stationName: station.name, tides: tides, moon: MoonPhase.forDay(moment), wind: windText)
        }
    }

    /// The current wind at the station from Open-Meteo, such as "12 km/h SW"; nil when it can't load.
    private static func wind(latitude: Double, longitude: Double) async -> String? {
        struct Response: Decodable {
            struct Current: Decodable { let speed: Double; let direction: Double?; enum CodingKeys: String, CodingKey { case speed = "wind_speed_10m", direction = "wind_direction_10m" } }
            let current: Current
        }
        guard let url = URL(string: "https://api.open-meteo.com/v1/forecast?latitude=\(latitude)&longitude=\(longitude)&current=wind_speed_10m,wind_direction_10m&timezone=auto"),
              let (data, _) = try? await URLSession.shared.data(from: url),
              let current = try? JSONDecoder().decode(Response.self, from: data).current else { return nil }
        let compass = current.direction.map { " " + ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int(($0 / 45).rounded()) % 8] } ?? ""
        return "\(Int(current.speed)) km/h\(compass)"
    }
}

/// The tide from 8 hours ago to 16 hours ahead with each high and low labelled and a dot at now.
struct WidgetTideCurveView: View {
    let entry: TideEntry

    var body: some View {
        Canvas { context, size in
            let start = entry.date.addingTimeInterval(-WidgetTideCurve.before)
            let samples: [(Date, Double)] = (0...96).compactMap { step in
                let moment = start.addingTimeInterval(WidgetTideCurve.window * Double(step) / 96)
                return WidgetTideCurve.level(entry.tides, at: moment).map { (moment, $0) }
            }
            guard samples.count > 1, let low = samples.map(\.1).min(), let high = samples.map(\.1).max() else { return }
            let span = high - low > 0.05 ? high - low : 1
            let labelRoom: CGFloat = 15
            func x(_ date: Date) -> CGFloat { size.width * CGFloat(date.timeIntervalSince(start) / WidgetTideCurve.window) }
            func y(_ level: Double) -> CGFloat { labelRoom + (size.height - 2 * labelRoom) * CGFloat(1 - (level - low) / span) }
            var curve = Path()
            for (index, sample) in samples.enumerated() {
                let point = CGPoint(x: x(sample.0), y: y(sample.1))
                if index == 0 { curve.move(to: point) } else { curve.addLine(to: point) }
            }
            var fill = curve
            fill.addLine(to: CGPoint(x: x(samples.last!.0), y: size.height))
            fill.addLine(to: CGPoint(x: x(samples.first!.0), y: size.height))
            fill.closeSubpath()
            let line = Color(red: 0x85 / 255, green: 0xB7 / 255, blue: 0xEB / 255)
            context.fill(fill, with: .color(line.opacity(0.25)))
            context.stroke(curve, with: .color(line), style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            let end = start.addingTimeInterval(WidgetTideCurve.window)
            for tide in entry.tides where tide.time >= start && tide.time <= end {
                let label = context.resolve(Text("\(tide.high ? "H" : "L") \(TideWidgetView.clock(tide.time, relativeTo: entry.date))").font(.system(size: 10, weight: .semibold)).foregroundStyle(.white.opacity(0.9)))
                let half = label.measure(in: size).width / 2
                let px = min(max(x(tide.time), half), size.width - half)
                context.draw(label, at: CGPoint(x: px, y: tide.high ? y(tide.height) - 8 : y(tide.height) + 8))
            }
            if let level = entry.level {
                var guide = Path()
                guide.move(to: CGPoint(x: x(entry.date), y: 0))
                guide.addLine(to: CGPoint(x: x(entry.date), y: size.height))
                context.stroke(guide, with: .color(.white.opacity(0.5)), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
                context.fill(Path(ellipseIn: CGRect(x: x(entry.date) - 4.5, y: y(level) - 4.5, width: 9, height: 9)),
                             with: .color(Color(red: 0xFA / 255, green: 0xC7 / 255, blue: 0x75 / 255)))
            }
        }
        .accessibilityLabel("Tide curve with the time now marked")
    }
}

struct TideWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TideEntry

    /// "2:10 PM", or "Mon 2:10 PM" on another day than `relativeTo`, in New Zealand time.
    static func clock(_ date: Date, relativeTo day: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = formatter.timeZone
        formatter.dateFormat = calendar.isDate(date, inSameDayAs: day) ? "h:mm a" : "EEE h:mm a"
        return formatter.string(from: date)
    }
    private func time(_ date: Date) -> String { Self.clock(date, relativeTo: entry.date) }
    private func line(_ event: WidgetTide) -> String { "\(event.high ? "High" : "Low") \(time(event.time)) · \(String(format: "%.1f", event.height)) m" }
    private var moonShort: String {
        switch entry.moon.tideRange { case .spring: "Spring tides"; case .neap: "Neap tides"; case .between: "Medium tides" }
    }
    private var trend: String? {
        guard let comingIn = entry.comingIn, let level = entry.level else { return nil }
        return "\(comingIn ? "Coming in ↑" : "Going out ↓") · \(String(format: "%.1f", level)) m"
    }

    var body: some View {
        switch family {
        case .accessoryCircular:
            if let fullness = entry.fullness, let next = entry.next.first {
                Gauge(value: fullness) {
                    Image(systemName: next.high ? "arrow.up" : "arrow.down")
                } currentValueLabel: {
                    Text(time(next.time).replacingOccurrences(of: " AM", with: "").replacingOccurrences(of: " PM", with: ""))
                }
                .gaugeStyle(.accessoryCircular)
            } else {
                Image(systemName: "water.waves")
            }
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Text(entry.stationName + (entry.comingIn.map { $0 ? " ↑" : " ↓" } ?? "")).font(.caption.bold())
                ForEach(Array(entry.next.enumerated()), id: \.offset) { Text(line($0.element)).font(.caption) }
                if entry.next.isEmpty { Text("Tides unavailable").font(.caption) }
            }
        case .systemSmall:
            HStack(spacing: 10) {
                if let fullness = entry.fullness {
                    GeometryReader { geometry in
                        ZStack(alignment: .bottom) {
                            Capsule().fill(.white.opacity(0.15))
                            Capsule().fill(Color(red: 0x37 / 255, green: 0x8A / 255, blue: 0xDD / 255))
                                .frame(height: max(geometry.size.height * fullness, 10))
                        }
                    }
                    .frame(width: 12)
                    .accessibilityLabel("Tide \(Int(fullness * 100)) percent of the way from low to high")
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(entry.stationName).font(.caption.bold()).foregroundStyle(.white.opacity(0.85)).lineLimit(1)
                    if let comingIn = entry.comingIn { Text(comingIn ? "Coming in ↑" : "Going out ↓").font(.caption2).foregroundStyle(.white.opacity(0.8)) }
                    if let next = entry.next.first {
                        Spacer(minLength: 0)
                        Text("Next \(next.high ? "high" : "low")").font(.caption2).foregroundStyle(.white.opacity(0.8))
                        Text(time(next.time)).font(.title3.bold()).foregroundStyle(.white).minimumScaleFactor(0.7).lineLimit(1)
                        Text(String(format: "%.1f m", next.height)).font(.caption).foregroundStyle(.white.opacity(0.9))
                        if let then = entry.next.dropFirst().first {
                            Text("Then \(then.high ? "high" : "low") \(time(then.time))").font(.caption2).foregroundStyle(.white.opacity(0.8)).lineLimit(1).minimumScaleFactor(0.8)
                        }
                    } else {
                        Text("Tides unavailable").font(.headline).foregroundStyle(.white)
                        Text("Open the app to try again.").font(.caption).foregroundStyle(.white.opacity(0.8))
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        default:
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("\(entry.stationName) tides").font(.caption.bold()).foregroundStyle(.white.opacity(0.9)).lineLimit(1)
                    Spacer()
                    if let trend { Text(trend).font(.caption2).foregroundStyle(Color(red: 0xB5 / 255, green: 0xD4 / 255, blue: 0xF4 / 255)) }
                }
                if entry.tides.isEmpty {
                    Spacer()
                    Text("Tides unavailable").font(.headline).foregroundStyle(.white)
                    Text("Open the app to try again.").font(.caption).foregroundStyle(.white.opacity(0.8))
                    Spacer()
                } else {
                    WidgetTideCurveView(entry: entry)
                }
                HStack(spacing: 10) {
                    if let next = entry.next.first { Text(line(next)).font(.caption.bold()).foregroundStyle(.white).lineLimit(1).minimumScaleFactor(0.8) }
                    Spacer(minLength: 0)
                    Text("\(entry.moon.emoji) \(moonShort)").font(.caption2).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                    if let wind = entry.wind { Text(wind).font(.caption2).foregroundStyle(.white.opacity(0.8)).lineLimit(1) }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }
}
