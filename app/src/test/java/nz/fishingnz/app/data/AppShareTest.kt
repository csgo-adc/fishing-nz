package nz.fishingnz.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShareTest {
    @Test fun shareTextNamesTheAppAndEndsWithItsPlayStoreLink() {
        val text = AppShare.text()
        assertTrue(text.startsWith("Fishdays - NZ "))
        assertTrue(text.endsWith(AppShare.playStoreUrl))
    }

    @Test fun playStoreLinkUsesTheApplicationId() {
        assertEquals("https://play.google.com/store/apps/details?id=nz.fishingnz.app", AppShare.playStoreUrl)
    }
}
