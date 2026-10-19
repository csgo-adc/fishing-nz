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
        .description("The next high and low tide for a LINZ station, with the moon.")
        .supportedFamilies([.systemSmall, .systemMedium, .accessoryRectangular])
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

struct TideEntry: TimelineEntry {
    let date: Date
    let stationID: String
    let stationName: String
    /// The next tides after `date`, at most two.
    let next: [WidgetTide]
    let moon: MoonDay
}

struct TideProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> TideEntry {
        let now = Date.now
        return TideEntry(date: now, stationID: "auckland", stationName: "Auckland", next: [
            WidgetTide(time: now.addingTimeInterval(7_200), height: 3.1, high: true), WidgetTide(time: now.addingTimeInterval(29_000), height: 0.5, high: false)],
            moon: MoonPhase.forDay(now))
    }

    func snapshot(for configuration: TideWidgetIntent, in context: Context) async -> TideEntry {
        context.isPreview ? placeholder(in: context) : await entries(for: configuration).first ?? placeholder(in: context)
    }

    func timeline(for configuration: TideWidgetIntent, in context: Context) async -> Timeline<TideEntry> {
        let entries = await entries(for: configuration)
        // A new entry at each tide moves "Next" along; ask again a little after the last one, or in 6 hours.
        let refresh = min(entries.last.map { $0.date.addingTimeInterval(600) } ?? .now.addingTimeInterval(1_800), .now.addingTimeInterval(21_600))
        return Timeline(entries: entries, policy: .after(refresh))
    }

    private func entries(for configuration: TideWidgetIntent) async -> [TideEntry] {
        let station = tideStations.first { $0.id == configuration.station?.id } ?? tideStations.first { $0.id == "auckland" }!
        let now = Date.now
        guard let predictions = try? await LINZTideStore.shared.predictions(stationName: station.csvName,
                                                                            start: now.addingTimeInterval(-86_400), end: now.addingTimeInterval(2 * 86_400)) else {
            return [TideEntry(date: now, stationID: station.id, stationName: station.name, next: [], moon: MoonPhase.forDay(now))]
        }
        let sorted = predictions.sorted { $0.time < $1.time }
        let events = sorted.indices.map { index -> WidgetTide in
            let neighbours = [index - 1, index + 1].filter { sorted.indices.contains($0) }.map { sorted[$0].height }
            return WidgetTide(time: sorted[index].time, height: sorted[index].height, high: neighbours.allSatisfy { sorted[index].height > $0 })
        }
        let upcoming = events.filter { $0.time > now }
        return ([now] + upcoming.prefix(4).map(\.time)).map { moment in
            TideEntry(date: moment, stationID: station.id, stationName: station.name, next: Array(events.filter { $0.time > moment }.prefix(2)), moon: MoonPhase.forDay(moment))
        }
    }
}

struct TideWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TideEntry

    private func time(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = formatter.timeZone
        formatter.dateFormat = calendar.isDate(date, inSameDayAs: entry.date) ? "h:mm a" : "EEE h:mm a"
        return formatter.string(from: date)
    }
    private func line(_ event: WidgetTide) -> String { "\(event.high ? "High" : "Low") \(time(event.time)) · \(String(format: "%.1f", event.height)) m" }

    var body: some View {
        if family == .accessoryRectangular {
            VStack(alignment: .leading, spacing: 1) {
                Text(entry.stationName).font(.caption.bold())
                ForEach(Array(entry.next.enumerated()), id: \.offset) { Text(line($0.element)).font(.caption) }
                if entry.next.isEmpty { Text("Tides unavailable").font(.caption) }
            }
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(entry.stationName) tides").font(.caption.bold()).foregroundStyle(.white.opacity(0.85)).lineLimit(1)
                if let next = entry.next.first {
                    Text("Next \(next.high ? "high" : "low")").font(.caption).foregroundStyle(.white.opacity(0.8))
                    Text(time(next.time)).font(.title2.bold()).foregroundStyle(.white).minimumScaleFactor(0.7).lineLimit(1)
                    Text(String(format: "%.1f m", next.height)).font(.subheadline).foregroundStyle(.white.opacity(0.9))
                    if let then = entry.next.dropFirst().first {
                        Text("Then \(line(then).prefix(1).lowercased() + line(then).dropFirst())").font(.caption).foregroundStyle(.white.opacity(0.8)).lineLimit(1).minimumScaleFactor(0.8)
                    }
                } else {
                    Text("Tides unavailable").font(.headline).foregroundStyle(.white)
                    Text("Open the app to try again.").font(.caption).foregroundStyle(.white.opacity(0.8))
                }
                if family == .systemMedium {
                    Spacer(minLength: 0)
                    Text("\(entry.moon.emoji) \(entry.moon.name) · \(entry.moon.tideRange.label)").font(.caption).foregroundStyle(.white.opacity(0.8))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }
}
