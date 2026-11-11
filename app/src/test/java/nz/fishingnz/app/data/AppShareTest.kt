package nz.fishingnz.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppShareTest {
    @Test fun shareTextNamesTheAppAndGivesBothStoreLinks() {
        assertEquals(
            "Fishdays - NZ helps you plan fishing days around New Zealand with tides, weather, fishing windows and MPI fishing rules.\n" +
                "iPhone: https://apps.apple.com/app/id6820858116\n" +
                "Android: https://play.google.com/store/apps/details?id=nz.fishingnz.app",
            AppShare.text())
    }

    @Test fun playStoreLinkUsesTheApplicationId() {
        assertEquals("https://play.google.com/store/apps/details?id=nz.fishingnz.app", AppShare.playStoreUrl)
    }
}
