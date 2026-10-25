import Combine
import CryptoKit
import Foundation

/// One illustration of a species, such as "Large male". `file` is its name under the service's /species/ folder.
struct SpeciesPicture: Codable, Hashable, Sendable {
    let id: String
    let caption: String
    let file: String
    let sha256: String
}

/// A species on the rules page: its names, group (finfish, shark, shellfish or crayfish) and pictures.
struct SpeciesInfo: Codable, Hashable, Sendable {
    let id: String
    let name: String
    let maori: String?
    let scientific: String
    let group: String
    let match: [String]
    let pictures: [SpeciesPicture]
}

/// The species pictures and the MPI rule names each covers, from the service's rules manifest or the copy built into the
/// app (`tools/build_offline_rules.py`). Android reads the same catalogue in `SpeciesCatalog.kt`.
struct SpeciesCatalog: Codable, Sendable {
    let version: String
    let note: String
    let species: [SpeciesInfo]

    /// The species an MPI rule row is about: the longest listed name the row starts with as a whole word (a plural "s"
    /// allowed), ignoring case and macrons.
    func forRule(_ ruleSpecies: String) -> SpeciesInfo? {
        let name = Array(Self.normalize(ruleSpecies))
        func wordEnds(_ index: Int) -> Bool { index >= name.count || !name[index].isLetter }
        let candidates = species.flatMap { info in info.match.map { (Array(Self.normalize($0)), info) } }.sorted { $0.0.count > $1.0.count }
        return candidates.first { prefix, _ in
            name.starts(with: prefix) && (wordEnds(prefix.count) || (prefix.count < name.count && name[prefix.count] == "s" && wordEnds(prefix.count + 1)))
        }?.1
    }

    var pictures: [SpeciesPicture] {
        var seen = Set<String>()
        return species.flatMap(\.pictures).filter { seen.insert($0.file).inserted }
    }

    /// Lower case without macrons or other accents, dashes as spaces.
    static func normalize(_ text: String) -> String {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: Locale(identifier: "en_NZ")).lowercased()
            .replacingOccurrences(of: "–", with: " ").replacingOccurrences(of: "—", with: " ")
            .trimmingCharacters(in: .whitespaces)
    }
}

/// Where the rules on screen came from: kept on the phone (from the service) or the copy built into the app.
enum RulesSource: Sendable { case saved, builtIn }

/// Tells an open rules page that a sync saved new rules or pictures.
@MainActor final class RulesSyncState: ObservableObject {
    static let shared = RulesSyncState()
    @Published var revision = 0
}

/// Keeps every MPI area's rules and the species pictures on the phone, so the rules page works on a boat with no
/// signal. The service's /v1/rules/manifest gives a version for each area and a hash for each picture; only what changed
/// since the last sync is downloaded. It runs when the app opens (at most every 12 hours) and when the rules page opens.
/// Android does the same in `RulesSync.kt`.
enum RulesSync {
    private static let stateKey = "rules_sync.json"
    private static let catalogKey = "species_catalog.json"
    private static let gap: TimeInterval = 12 * 3600
    private static let gate = SyncGate()

    static var baseURL: String {
        ((Bundle.main.object(forInfoDictionaryKey: "FishIdentificationAPIBaseURL") as? String) ?? "https://fishing.fishnz.space")
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    private struct Bundled: Decodable {
        let generatedAt: String
        let species: SpeciesCatalog
        enum CodingKeys: String, CodingKey { case generatedAt = "generated_at", species }
    }

    private static let bundledData: Data? = Bundle.main.url(forResource: "offline_rules", withExtension: "json").flatMap { try? Data(contentsOf: $0) }

    /// An area's rules from the copy built into the app, as a `{"rules": [...]}` answer, with the day the copy was made.
    static func builtInRule(_ areaID: String) -> (data: Data, made: Date?)? {
        guard let bundledData, let root = try? JSONSerialization.jsonObject(with: bundledData) as? [String: Any],
              let rules = root["rules"] as? [[String: Any]], let item = rules.first(where: { $0["area_id"] as? String == areaID }),
              let data = try? JSONSerialization.data(withJSONObject: ["rules": [item]]) else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Pacific/Auckland")
        formatter.dateFormat = "yyyy-MM-dd"
        return (data, (root["generated_at"] as? String).flatMap(formatter.date(from:)))
    }

    /// The species catalogue: the last one synced, else the one built into the app.
    static func catalog() -> SpeciesCatalog? {
        if let saved = OfflineCache.read(catalogKey), let catalog = try? JSONDecoder().decode(SpeciesCatalog.self, from: saved.data) { return catalog }
        return bundledData.flatMap { try? JSONDecoder().decode(Bundled.self, from: $0).species }
    }

    /// The picture kept on the phone, or nil before it has been downloaded.
    static func pictureURL(_ picture: SpeciesPicture) -> URL? { OfflineCache.fileURL("species/\(picture.file)") }

    /// The picture, downloading it now if the phone doesn't have it yet; nil with no signal.
    static func picture(_ picture: SpeciesPicture) async -> URL? {
        if let url = pictureURL(picture) { return url }
        return try? await save(picture)
    }

    /// When the last complete sync finished, or nil before the first.
    static func lastSynced() -> Date? { readState().syncedAt }

    /// Downloads an area's rules from the service and keeps them on the phone.
    @discardableResult
    static func downloadRules(_ areaID: String) async throws -> Data {
        guard let url = URL(string: "\(baseURL)/v1/rules?area=\(areaID)") else { throw URLError(.badURL) }
        let data = try await get(url, timeout: 15)
        struct Envelope: Decodable { struct Rule: Decodable { let area_id: String }; let rules: [Rule] }
        guard (try JSONDecoder().decode(Envelope.self, from: data)).rules.first?.area_id == areaID else { throw URLError(.cannotParseResponse) }
        OfflineCache.write("rules_\(areaID).json", data)
        return data
    }

    /// Downloads changed areas and pictures. Without `force` it does nothing within 12 hours of the last full sync.
    /// Returns true when something new was saved.
    @discardableResult
    static func sync(force: Bool = false) async -> Bool {
        let changed = await gate.run {
            var state = readState()
            if !force, let synced = state.syncedAt, Date.now.timeIntervalSince(synced) < gap { return false }
            struct Manifest: Decodable {
                struct Rule: Decodable { let area_id: String; let version: String }
                let rules: [Rule]
                let species: SpeciesCatalog?
            }
            guard let url = URL(string: "\(baseURL)/v1/rules/manifest"), let data = try? await get(url, timeout: 15),
                  let manifest = try? JSONDecoder().decode(Manifest.self, from: data) else { return false }
            var changed = false, complete = true
            for rule in manifest.rules where state.areas[rule.area_id] != rule.version || OfflineCache.fileURL("rules_\(rule.area_id).json") == nil {
                if (try? await downloadRules(rule.area_id)) != nil { state.areas[rule.area_id] = rule.version; changed = true } else { complete = false }
            }
            if let catalog = manifest.species {
                if catalog.version != Self.catalog()?.version, let encoded = try? JSONEncoder().encode(catalog) {
                    OfflineCache.write(catalogKey, encoded)
                    changed = true
                }
                for picture in catalog.pictures where state.pictures[picture.file] != picture.sha256 || pictureURL(picture) == nil {
                    if (try? await save(picture)) != nil { state.pictures[picture.file] = picture.sha256; changed = true } else { complete = false }
                }
            }
            // A sync cut short by a lost signal is tried again next time instead of waiting 12 hours.
            if complete { state.syncedAt = .now }
            writeState(state)
            return changed
        }
        if changed { await MainActor.run { RulesSyncState.shared.revision += 1 } }
        return changed
    }

    private static func save(_ picture: SpeciesPicture) async throws -> URL? {
        guard let url = URL(string: "\(baseURL)/species/\(picture.file)") else { return nil }
        let data = try await get(url, timeout: 20)
        guard sha256(data) == picture.sha256 else { return nil }
        OfflineCache.write("species/\(picture.file)", data)
        return pictureURL(picture)
    }

    private static func get(_ url: URL, timeout: TimeInterval) async throws -> Data {
        var request = URLRequest(url: url)
        request.timeoutInterval = timeout
        request.setValue("CatchCheckNZ-iOS/1.0", forHTTPHeaderField: "User-Agent")
        if let deviceID = AnalyticsPreferences.deviceID() { request.setValue(deviceID, forHTTPHeaderField: "X-Device-Id") }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard 200...299 ~= ((response as? HTTPURLResponse)?.statusCode ?? 0) else { throw URLError(.badServerResponse) }
        return data
    }

    static func sha256(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }

    private struct State: Codable {
        var syncedAt: Date?
        var areas: [String: String] = [:]
        var pictures: [String: String] = [:]
    }

    private static func readState() -> State {
        OfflineCache.read(stateKey).flatMap { try? JSONDecoder().decode(State.self, from: $0.data) } ?? State()
    }

    private static func writeState(_ state: State) {
        if let data = try? JSONEncoder().encode(state) { OfflineCache.write(stateKey, data) }
    }
}

/// Runs one sync at a time.
private actor SyncGate {
    private var running: Task<Bool, Never>?
    func run(_ work: @escaping @Sendable () async -> Bool) async -> Bool {
        if let running { return await running.value }
        let task = Task { await work() }
        running = task
        let result = await task.value
        running = nil
        return result
    }
}
