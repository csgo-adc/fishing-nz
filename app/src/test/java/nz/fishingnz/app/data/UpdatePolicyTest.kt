package nz.fishingnz.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val installed = 60
    private val available = 62
    private val firstUse = 100L

    private fun prompt(record: UpdatePromptRecord, today: Long, lastNotice: Long? = null, build: Int = available) =
        UpdatePolicy.shouldPrompt(record, build, installed, today, firstUse, lastNotice)

    @Test fun `asks on day 0, day 3 and day 10, then stops`() {
        var record = UpdatePromptRecord()
        assertTrue(prompt(record, 200))
        record = UpdatePolicy.shown(record, available, 200)
        assertFalse(prompt(record, 201))
        assertFalse(prompt(record, 202))
        assertTrue(prompt(record, 203))
        record = UpdatePolicy.shown(record, available, 203)
        assertFalse(prompt(record, 209))
        assertTrue(prompt(record, 210))
        record = UpdatePolicy.shown(record, available, 210)
        assertEquals(3, record.count)
        assertFalse(prompt(record, 400))
        assertTrue(UpdatePolicy.remindersDone(record, available, installed))
    }

    @Test fun `a newer version starts the count again`() {
        var record = UpdatePromptRecord()
        repeat(3) { record = UpdatePolicy.shown(record, available, 200L + it * 10) }
        assertFalse(prompt(record, 300))
        assertTrue(prompt(record, 300, build = available + 1))
        val next = UpdatePolicy.shown(record, available + 1, 300)
        assertEquals(UpdatePromptRecord(build = available + 1, count = 1, lastShownDay = 300), next)
        assertFalse(UpdatePolicy.remindersDone(next, available + 1, installed))
    }

    @Test fun `never on the first day, never on a day with another notice, never when up to date`() {
        assertFalse(prompt(UpdatePromptRecord(), firstUse))
        assertFalse(prompt(UpdatePromptRecord(), 200, lastNotice = 200))
        assertTrue(prompt(UpdatePromptRecord(), 200, lastNotice = 199))
        assertFalse(UpdatePolicy.shouldPrompt(UpdatePromptRecord(), installed, installed, 200, firstUse, null))
        assertFalse(UpdatePolicy.remindersDone(UpdatePromptRecord(build = installed, count = 3), installed, installed))
    }

    @Test fun `an update is required only below the minimum build`() {
        assertTrue(UpdatePolicy.required(installedBuild = 58, minimumBuild = 59))
        assertFalse(UpdatePolicy.required(installedBuild = 59, minimumBuild = 59))
        assertFalse(UpdatePolicy.required(installedBuild = 60, minimumBuild = 1))
    }
}
