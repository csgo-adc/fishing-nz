package nz.fishingnz.app.model

/**
 * The AI services fish photos go to, as the Fishdays - NZ service reports them (`GET /v1/fish/providers`): names in the
 * order main, backup; whether every one of them says it does not train on the photos; whether any is in China; and a
 * version that changes with any of these, so the app asks for consent again. The iPhone app has the same rules in
 * `PhotoConsentText.swift`.
 */
data class FishProviderInfo(val names: List<String>, val noTraining: Boolean, val china: Boolean, val version: String) {
    companion object {
        /** Until the service has answered once: the setup at release (OpenAI, Cloudflare Workers AI as backup). */
        val DEFAULT = FishProviderInfo(listOf("OpenAI", "Cloudflare's own AI"), noTraining = true, china = false, version = "default")
    }
}

/** The consent dialog and Terms & privacy wording, built from [FishProviderInfo]. English; shown through the translator. */
object PhotoConsentText {
    private fun FishProviderInfo.main() = names.firstOrNull() ?: "OpenAI"
    private fun FishProviderInfo.backup() = names.getOrNull(1)

    /** The consent dialog's first paragraph. */
    fun dialog(info: FishProviderInfo): String = buildList {
        val backup = info.backup()
        add(if (backup != null) "Your selected photo will be sent through the Fishdays - NZ service on Cloudflare to an AI service that suggests a fish species: ${info.main()}, or $backup if ${info.main()} is unavailable."
            else "Your selected photo will be sent through the Fishdays - NZ service on Cloudflare to an AI service that suggests a fish species: ${info.main()}.")
        add("Original photo metadata is removed.")
        add(when {
            !info.noTraining -> "We do not save the photo in your account. See the privacy policy for how each service uses photos."
            backup != null -> "We do not save the photo in your account, and neither service uses it to train AI."
            else -> "We do not save the photo in your account, and the service does not use it to train AI."
        })
        if (info.china) add("Photos may be processed in China, under Chinese law.")
        add("Security records can remain for up to 30 days, or longer where legally required.")
    }.joinToString(" ")

    /** The photo paragraph in More → Settings → Terms & privacy. */
    fun privacyCard(info: FishProviderInfo): String = buildList {
        val backup = info.backup()
        add("A photo is uploaded only after you agree.")
        add(if (backup != null) "It passes through Cloudflare to an AI service for a fish suggestion (currently ${info.main()}, with $backup as a backup; the privacy policy lists the services in use)."
            else "It passes through Cloudflare to an AI service for a fish suggestion (currently ${info.main()}; the privacy policy lists the services in use).")
        add("Original photo metadata is removed.")
        if (info.china) add("Photos may be processed in China, under Chinese law.")
        add(if (info.noTraining) "The AI services don't use it for training; security records can remain for up to 30 days, or longer where legally required."
            else "Security records can remain for up to 30 days, or longer where legally required.")
    }.joinToString(" ")

    /** Under Terms & privacy when "Don't ask me again" is on. */
    const val REMEMBERED = "You chose not to be asked before a photo is sent for AI identification. If the AI services change, the app asks again."

    /** The service's answer, or null when it isn't one. */
    fun parse(json: org.json.JSONObject): FishProviderInfo? {
        val providers = json.optJSONArray("providers") ?: return null
        val names = (0 until providers.length()).mapNotNull { index ->
            providers.optJSONObject(index)?.optString("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 80 }
        }.take(2)
        val version = json.optString("version").takeIf { Regex("[0-9a-f]{8,64}").matches(it) } ?: return null
        if (names.isEmpty()) return null
        return FishProviderInfo(names, json.optBoolean("no_training", false), json.optBoolean("china", false), version)
    }
}
