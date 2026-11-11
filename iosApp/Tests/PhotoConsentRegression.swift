import Foundation

/// The photo consent wording matches Android's `PhotoConsentTextTest`. Run with `tools/test_photo_consent_swift.sh`.
@main struct PhotoConsentRegression {
    enum Failure: Error { case assertion(String) }
    static var checks = 0
    static func expect(_ value: @autoclosure () -> Bool, _ message: String) throws { checks += 1; if !value() { throw Failure.assertion(message) } }

    static func main() throws {
        let dialog = PhotoConsentText.dialog(.default)
        try expect(dialog == "Your selected photo will be sent through the Fishdays - NZ service on Cloudflare to an AI service that suggests a fish species: OpenAI, or Cloudflare's own AI if OpenAI is unavailable. Original photo metadata is removed. We do not save the photo in your account, and neither service uses it to train AI. Security records can remain for up to 30 days, or longer where legally required.", "Dialog: \(dialog)")
        let card = PhotoConsentText.privacyCard(.default)
        try expect(card == "A photo is uploaded only after you agree. It passes through Cloudflare to an AI service for a fish suggestion (currently OpenAI, with Cloudflare's own AI as a backup; the privacy policy lists the services in use). Original photo metadata is removed. The AI services don't use it for training; security records can remain for up to 30 days, or longer where legally required.", "Card: \(card)")

        let one = FishProviderInfo(names: ["OpenAI"], noTraining: true, china: false, version: "a1b2c3d4")
        try expect(PhotoConsentText.dialog(one).contains("fish species: OpenAI. "), "One service")
        try expect(PhotoConsentText.dialog(one).contains("and the service does not use it to train AI."), "One service, no training")
        let china = FishProviderInfo(names: ["OpenAI", "Alibaba Cloud (Qwen)"], noTraining: false, china: true, version: "a1b2c3d4")
        try expect(PhotoConsentText.dialog(china).contains("See the privacy policy for how each service uses photos."), "May train")
        try expect(PhotoConsentText.dialog(china).contains("Photos may be processed in China, under Chinese law."), "China")
        try expect(PhotoConsentText.privacyCard(china).hasSuffix("Security records can remain for up to 30 days, or longer where legally required."), "China card")

        let answer = Data(#"{"providers":[{"id":"openai","name":"OpenAI","role":"main"},{"id":"workers_ai","name":"Cloudflare's own AI","role":"backup"}],"no_training":true,"china":false,"version":"0123456789abcdef"}"#.utf8)
        try expect(PhotoConsentText.parse(answer) == FishProviderInfo(names: ["OpenAI", "Cloudflare's own AI"], noTraining: true, china: false, version: "0123456789abcdef"), "Parse")
        try expect(PhotoConsentText.parse(Data(#"{"providers":[],"version":"0123456789abcdef"}"#.utf8)) == nil, "No providers")
        try expect(PhotoConsentText.parse(Data(#"{"providers":[{"name":"OpenAI"}],"version":"not a version"}"#.utf8)) == nil, "Bad version")
        print("Photo consent regression passed (\(checks) checks)")
    }
}
