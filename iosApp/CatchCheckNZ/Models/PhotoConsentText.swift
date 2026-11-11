import Foundation

/// The AI services fish photos go to, as the Fishdays - NZ service reports them (`GET /v1/fish/providers`): names in the
/// order main, backup; whether every one says it does not train on the photos; whether any is in China; and a version
/// that changes with any of these, so the app asks for consent again. Android has the same rules in `PhotoConsentText.kt`.
struct FishProviderInfo: Equatable, Codable, Sendable {
    let names: [String]
    let noTraining: Bool
    let china: Bool
    let version: String

    /// Until the service has answered once: the setup at release (OpenAI, Cloudflare Workers AI as backup).
    static let `default` = FishProviderInfo(names: ["OpenAI", "Cloudflare's own AI"], noTraining: true, china: false, version: "default")
}

/// The consent sheet and Terms & privacy wording, built from `FishProviderInfo`. English; shown through the translator.
enum PhotoConsentText {
    static func dialog(_ info: FishProviderInfo) -> String {
        let main = info.names.first ?? "OpenAI"
        let backup = info.names.count > 1 ? info.names[1] : nil
        var sentences = [backup.map { "Your selected photo will be sent through the Fishdays - NZ service on Cloudflare to an AI service that suggests a fish species: \(main), or \($0) if \(main) is unavailable." }
            ?? "Your selected photo will be sent through the Fishdays - NZ service on Cloudflare to an AI service that suggests a fish species: \(main)."]
        sentences.append("Original photo metadata is removed.")
        if !info.noTraining {
            sentences.append("We do not save the photo in your account. See the privacy policy for how each service uses photos.")
        } else if backup != nil {
            sentences.append("We do not save the photo in your account, and neither service uses it to train AI.")
        } else {
            sentences.append("We do not save the photo in your account, and the service does not use it to train AI.")
        }
        if info.china { sentences.append("Photos may be processed in China, under Chinese law.") }
        sentences.append("Security records can remain for up to 30 days, or longer where legally required.")
        return sentences.joined(separator: " ")
    }

    static func privacyCard(_ info: FishProviderInfo) -> String {
        let main = info.names.first ?? "OpenAI"
        let backup = info.names.count > 1 ? info.names[1] : nil
        var sentences = ["A photo is uploaded only after you agree."]
        sentences.append(backup.map { "It passes through Cloudflare to an AI service for a fish suggestion (currently \(main), with \($0) as a backup; the privacy policy lists the services in use)." }
            ?? "It passes through Cloudflare to an AI service for a fish suggestion (currently \(main); the privacy policy lists the services in use).")
        sentences.append("Original photo metadata is removed.")
        if info.china { sentences.append("Photos may be processed in China, under Chinese law.") }
        sentences.append(info.noTraining
            ? "The AI services don't use it for training; security records can remain for up to 30 days, or longer where legally required."
            : "Security records can remain for up to 30 days, or longer where legally required.")
        return sentences.joined(separator: " ")
    }

    /// Under Terms & privacy when "Don't ask me again" is on.
    static let remembered = "You chose not to be asked before a photo is sent for AI identification. If the AI services change, the app asks again."

    /// The service's answer, or nil when it isn't one.
    static func parse(_ data: Data) -> FishProviderInfo? {
        struct Answer: Decodable {
            struct Provider: Decodable { let name: String? }
            let providers: [Provider]?
            let no_training: Bool?
            let china: Bool?
            let version: String?
        }
        guard let answer = try? JSONDecoder().decode(Answer.self, from: data) else { return nil }
        let names = (answer.providers ?? []).compactMap { $0.name?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && $0.count <= 80 }.prefix(2)
        guard let version = answer.version, version.range(of: "^[0-9a-f]{8,64}$", options: .regularExpression) != nil, !names.isEmpty else { return nil }
        return FishProviderInfo(names: Array(names), noTraining: answer.no_training ?? false, china: answer.china ?? false, version: version)
    }
}
