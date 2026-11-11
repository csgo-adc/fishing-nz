package nz.fishingnz.app.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoConsentTextTest {
    @Test fun `the dialog names the main service and its backup, as approved`() {
        assertEquals("Your selected photo will be sent through the Fishdays - NZ service on Cloudflare to an AI service that suggests a fish " +
            "species: OpenAI, or Cloudflare's own AI if OpenAI is unavailable. Original photo metadata is removed. We do not save the photo " +
            "in your account, and neither service uses it to train AI. Security records can remain for up to 30 days, or longer where " +
            "legally required.", PhotoConsentText.dialog(FishProviderInfo.DEFAULT))
        assertEquals("A photo is uploaded only after you agree. It passes through Cloudflare to an AI service for a fish suggestion " +
            "(currently OpenAI, with Cloudflare's own AI as a backup; the privacy policy lists the services in use). Original photo metadata " +
            "is removed. The AI services don't use it for training; security records can remain for up to 30 days, or longer where legally " +
            "required.", PhotoConsentText.privacyCard(FishProviderInfo.DEFAULT))
    }

    @Test fun `one service, a service that may train, and a service in China read differently`() {
        val one = FishProviderInfo(listOf("OpenAI"), noTraining = true, china = false, version = "a1b2c3d4")
        assertTrue(PhotoConsentText.dialog(one).contains("fish species: OpenAI. "))
        assertTrue(PhotoConsentText.dialog(one).contains("and the service does not use it to train AI."))
        val china = FishProviderInfo(listOf("OpenAI", "Alibaba Cloud (Qwen)"), noTraining = false, china = true, version = "a1b2c3d4")
        val dialog = PhotoConsentText.dialog(china)
        assertTrue(dialog.contains("See the privacy policy for how each service uses photos."))
        assertTrue(dialog.contains("Photos may be processed in China, under Chinese law."))
        assertTrue(PhotoConsentText.privacyCard(china).endsWith("Security records can remain for up to 30 days, or longer where legally required."))
    }

    @Test fun `the service's answer is read, and anything else is ignored`() {
        val info = PhotoConsentText.parse(JSONObject("""{"providers":[{"id":"openai","name":"OpenAI","role":"main"},{"id":"workers_ai","name":"Cloudflare's own AI","role":"backup"}],"no_training":true,"china":false,"version":"0123456789abcdef"}"""))
        assertEquals(FishProviderInfo(listOf("OpenAI", "Cloudflare's own AI"), true, false, "0123456789abcdef"), info)
        assertNull(PhotoConsentText.parse(JSONObject("""{"providers":[],"version":"0123456789abcdef"}""")))
        assertNull(PhotoConsentText.parse(JSONObject("""{"providers":[{"name":"OpenAI"}],"version":"not a version"}""")))
    }
}
