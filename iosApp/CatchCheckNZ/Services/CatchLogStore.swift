import Foundation
#if canImport(UIKit)
import UIKit
#endif

/// The catch log on this phone: `catch_log.json` and photos in `catch_photos/`, in Application Support, which is
/// private to the app. Android keeps the same JSON shape in `CatchLogStore.kt`.
enum CatchLogStore {
    private struct Envelope: Codable { var version = 1; var entries: [CatchEntry] }
    private static var folder: URL? {
        try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
    }
    private static var photosFolder: URL? { folder?.appendingPathComponent("catch_photos", isDirectory: true) }

    static func load() -> [CatchEntry] {
        guard let url = folder?.appendingPathComponent("catch_log.json"), let data = try? Data(contentsOf: url) else { return [] }
        return (try? decode(data)) ?? []
    }

    static func save(_ entries: [CatchEntry]) {
        guard let url = folder?.appendingPathComponent("catch_log.json"), let data = try? encode(entries) else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func encode(_ entries: [CatchEntry]) throws -> Data { try JSONEncoder().encode(Envelope(entries: entries)) }

    /// Reads every entry it can; one damaged entry does not lose the rest.
    static func decode(_ data: Data) throws -> [CatchEntry] {
        struct Lenient: Decodable {
            let entries: [CatchEntry]
            private struct Maybe: Decodable { let entry: CatchEntry?; init(from decoder: Decoder) throws { entry = try? CatchEntry(from: decoder) } }
            enum CodingKeys: String, CodingKey { case entries }
            init(from decoder: Decoder) throws {
                entries = (try decoder.container(keyedBy: CodingKeys.self).decodeIfPresent([Maybe].self, forKey: .entries) ?? []).compactMap(\.entry)
            }
        }
        return try JSONDecoder().decode(Lenient.self, from: data).entries
    }

    #if canImport(UIKit)
    /// Saves a photo with the entry and returns its file name.
    static func keepPhoto(_ image: UIImage, id: String) -> String? {
        guard let folder = photosFolder, let data = image.jpegData(compressionQuality: 0.8) else { return nil }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let name = "\(id).jpg"
        return (try? data.write(to: folder.appendingPathComponent(name), options: .atomic)) != nil ? name : nil
    }

    static func photo(_ name: String?) -> UIImage? {
        guard let name, let url = photosFolder?.appendingPathComponent(name) else { return nil }
        return UIImage(contentsOfFile: url.path)
    }
    #endif

    static func deletePhoto(_ name: String?) {
        guard let name, let url = photosFolder?.appendingPathComponent(name) else { return }
        try? FileManager.default.removeItem(at: url)
    }
}

/// Looks up the tide, wind, waves and water temperature for a catch's time and place. Missing parts stay nil.
enum CatchConditionsLookup {
    static func lookup(point: GeoPoint?, at date: Date) async -> CatchConditions {
        let moon = MoonPhase.forDay(date)
        var result = CatchConditions(moon: "\(moon.emoji) \(moon.name)", tideRange: moon.tideRange.label)
        guard let point else { return result }
        let station = nearbyTideStations(point: point).first
        if let station, let predictions = try? await LINZTideStore.shared.predictions(stationName: station.csvName,
            start: date.addingTimeInterval(-86_400), end: date.addingTimeInterval(86_400)),
           let tide = tideAt(predictions, date: date, stationName: station.name) {
            result.tidePhase = tide.phase; result.tide = tide.text
        }
        let pastDays = min(max(Int(Date.now.timeIntervalSince(date) / 86_400) + 1, 1), PlaceConditionsService.maxPastDays)
        if let place = try? await PlaceConditionsService().load(at: point, pastDays: pastDays) {
            let hour = Date(timeIntervalSince1970: (date.timeIntervalSince1970 / 3600).rounded() * 3600)
            if let weather = place.weather?.hours.min(by: { abs($0.at.timeIntervalSince(hour)) < abs($1.at.timeIntervalSince(hour)) }),
               abs(weather.at.timeIntervalSince(hour)) <= 5_400, let wind = weather.wind {
                result.wind = PlainConditions.wind(speed: wind, gust: weather.gust, directions: [PlaceConditionRows.direction(weather.direction)])
            }
            if let marine = place.marine?.hours.min(by: { abs($0.key.timeIntervalSince(hour)) < abs($1.key.timeIntervalSince(hour)) }),
               abs(marine.key.timeIntervalSince(hour)) <= 5_400 {
                if let height = marine.value.height {
                    result.waves = PlainConditions.waves(height: height, periods: marine.value.period.map { String(format: "%.1f", $0) }) + " offshore"
                }
                result.waterTemperature = marine.value.waterTemperature.map { "\(Int($0.rounded()))°C" }
            }
        }
        return result
    }

    /// The part of the tide at `date`, timed from the LINZ highs and lows with the same windows as the fishing plan:
    /// around high is 2 hours before to 1 hour after high, around low 1.5 hours either side. Nil outside the predictions.
    static func tideAt(_ predictions: [LINZTidePrediction], date: Date, stationName: String) -> (phase: String, text: String)? {
        let sorted = predictions.sorted { $0.time < $1.time }
        guard let afterIndex = sorted.firstIndex(where: { $0.time > date }), afterIndex > 0 else { return nil }
        let before = sorted[afterIndex - 1], after = sorted[afterIndex]
        let rising = after.height > before.height
        let since = date.timeIntervalSince(before.time) / 60, until = after.time.timeIntervalSince(date) / 60
        let phase: String
        if rising { phase = until <= 120 ? "Around high" : since <= 90 ? "Around low" : "Incoming" }
        else { phase = since <= 60 ? "Around high" : until <= 90 ? "Around low" : "Outgoing" }
        let relative = since <= until ? "\(span(since)) after \(rising ? "low" : "high")" : "\(span(until)) before \(rising ? "high" : "low")"
        return (phase, "\(rising ? "Incoming" : "Outgoing") · \(relative) (LINZ \(stationName))")
    }

    private static func span(_ minutes: Double) -> String {
        if minutes < 15 { return "just" }
        if minutes < 60 { return "\(Int(minutes)) min" }
        let half = (minutes / 30).rounded() / 2
        return half == half.rounded() ? "\(Int(half)) h" : "\(half) h"
    }
}
