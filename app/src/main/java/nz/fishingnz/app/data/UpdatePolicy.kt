package nz.fishingnz.app.data

/** How often one version has been offered: the build, how many pop-ups so far and the day (epoch day) of the last one. */
data class UpdatePromptRecord(val build: Int = 0, val count: Int = 0, val lastShownDay: Long = Long.MIN_VALUE)

/**
 * When the "Update available" pop-up may appear (docs/in-app-updates.md). At most three pop-ups per version: the first
 * time the store has it, 3 days after the first and 7 days after the second (day 10). After that only the More row and
 * a dot on More remain. A newer version starts the count again. Never on the first day the app is used, never twice in
 * one day and never on a day another notice was shown. The iPhone app follows the same rules in `AppUpdates.swift`.
 */
object UpdatePolicy {
    const val MAX_PROMPTS = 3
    private val waitDays = longArrayOf(0, 3, 7)

    /** The record for [availableBuild]: a newer build than the one on record starts again from no pop-ups. */
    fun recordFor(record: UpdatePromptRecord, availableBuild: Int): UpdatePromptRecord =
        if (availableBuild > record.build) UpdatePromptRecord(build = availableBuild) else record

    fun shouldPrompt(
        record: UpdatePromptRecord, availableBuild: Int, installedBuild: Int,
        today: Long, firstUseDay: Long, lastNoticeDay: Long?,
    ): Boolean {
        if (availableBuild <= installedBuild) return false
        if (today <= firstUseDay || lastNoticeDay == today) return false
        val current = recordFor(record, availableBuild)
        if (current.count >= MAX_PROMPTS) return false
        if (current.count == 0) return true
        return today - current.lastShownDay >= waitDays[current.count]
    }

    /** The record after a pop-up for [availableBuild] was shown on [today]. */
    fun shown(record: UpdatePromptRecord, availableBuild: Int, today: Long): UpdatePromptRecord {
        val current = recordFor(record, availableBuild)
        return current.copy(count = current.count + 1, lastShownDay = today)
    }

    /** All pop-ups for this version are used up, so More shows a dot instead. */
    fun remindersDone(record: UpdatePromptRecord, availableBuild: Int, installedBuild: Int): Boolean =
        availableBuild > installedBuild && recordFor(record, availableBuild).count >= MAX_PROMPTS

    /** The installed build is older than the oldest one the service still supports. */
    fun required(installedBuild: Int, minimumBuild: Int): Boolean = installedBuild < minimumBuild
}
