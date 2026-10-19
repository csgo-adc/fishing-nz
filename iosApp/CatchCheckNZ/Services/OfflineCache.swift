import Foundation

/// Last good provider responses kept in Application Support, so tides, forecasts and rules still show without a
/// signal. Keys are file names; the file's modification date is when the response was saved. Android has the same
/// rules in `OfflineCache.kt`.
enum OfflineCache {
    nonisolated(unsafe) static var folderOverride: URL?
    private static var folder: URL? {
        if let folderOverride { return folderOverride }
        guard let support = try? FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true) else { return nil }
        let folder = support.appendingPathComponent("offline", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }

    static func write(_ key: String, _ data: Data) {
        guard let url = folder?.appendingPathComponent(key) else { return }
        try? data.write(to: url, options: .atomic)
    }

    /// The saved data and when it was saved, or nil.
    static func read(_ key: String) -> (data: Data, savedAt: Date)? {
        guard let url = folder?.appendingPathComponent(key), let data = try? Data(contentsOf: url) else { return nil }
        let savedAt = (try? FileManager.default.attributesOfItem(atPath: url.path)[.modificationDate] as? Date) ?? .distantPast
        return (data, savedAt)
    }

    /// A place's key: coordinates to about 100 m, so nearby requests share one saved forecast.
    static func placeKey(_ kind: String, _ point: GeoPoint) -> String {
        String(format: "%@_%.3f_%.3f.json", kind, point.latitude, point.longitude)
    }

    /// "Sun 18 Oct, 12:00 PM", in New Zealand time.
    static func savedLabel(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
        formatter.dateFormat = "EEE d MMM, h:mm a"
        return formatter.string(from: date)
    }
}
