package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.usage.UsageHousekeeping
import com.aicontrolcenter.core.usage.UsageRange
import java.time.ZoneId

/**
 * Keeps the usage history as long as the owner chose (Phase 6, WP4 item 9; security review of wave 1, F4): the hourly
 * rows older than `usageRetentionDays` local days (90, 365, 730; 0 keeps them for ever), and the per-work rows of
 * tasks, objectives and chats that no longer exist.
 *
 * Run by the upkeep job (WP2's `UpkeepGraph`, with `UpkeepPorts.usageHousekeeping`), never by anything an agent
 * reaches: [UsageHousekeeping] is owner-only in both architecture tests. Today's rows can never go: the cut is at the
 * start of a local day at least one day back, and the store clamps anything inside today anyway, because today's
 * rows are what the daily limits count after a restart.
 */
class UsageRetention(
    private val housekeeping: UsageHousekeeping,
    private val settings: SettingsView,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** What one run deleted. */
    data class Pruned(val hourRows: Int, val workRows: Int)

    /** Deletes what the owner's retention no longer keeps and returns how much went. */
    suspend fun prune(): Pruned {
        val days = settings.get().usageRetentionDays
        val hours = cutFor(days, clock.now(), zone())?.let { housekeeping.pruneHours(it) } ?: 0
        val work = housekeeping.pruneOrphanedWork()
        return Pruned(hours, work)
    }

    /** Pure rules. */
    companion object {
        /**
         * The time before which hourly rows go when [days] local days are kept (today included), or null when
         * everything is kept (0, or a value that is not a number of days).
         */
        fun cutFor(days: Int, now: Long, zone: ZoneId): Long? =
            if (days <= 0) null else UsageRange.lastDays(days, now, zone).fromMs
    }
}
