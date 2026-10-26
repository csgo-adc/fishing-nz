package nz.fishingnz.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AccountModelsTest {
    private fun profile(createdAt: String) = AccountProfile("u1", "a@b.nz", "", "NZ", "free", createdAt = createdAt)

    @Test fun memberSinceUsesNewZealandMonth() {
        assertEquals("Member since October 2026", profile("2026-10-01T10:00:00.000Z").memberSince)
        // 30 September 11:30 UTC is already 1 October in New Zealand.
        assertEquals("Member since October 2026", profile("2026-09-30T11:30:00Z").memberSince)
        assertNull(profile("").memberSince)
        assertNull(profile("not a date").memberSince)
    }

    @Test fun countriesStartWithNewZealandThenSortByName() {
        val countries = Countries.all(Locale.ENGLISH)
        assertEquals(Country("NZ", "New Zealand"), countries.first())
        assertEquals(1, countries.count { it.code == "NZ" })
        assertTrue(countries.any { it.code == "AU" && it.name == "Australia" })
        val rest = countries.drop(1).map { it.name }
        assertEquals(rest.sortedWith(compareBy(java.text.Collator.getInstance(Locale.ENGLISH)) { it }), rest)
        assertTrue(countries.all { it.code.matches(Regex("[A-Z]{2}")) })
    }

    @Test fun flagsAndNamesForTwoLetterCodes() {
        assertEquals("🇳🇿", Countries.flag("NZ"))
        assertEquals("", Countries.flag("nz"))
        assertEquals("", Countries.flag(""))
        assertEquals("Australia", Countries.name("AU", Locale.ENGLISH))
    }
}
