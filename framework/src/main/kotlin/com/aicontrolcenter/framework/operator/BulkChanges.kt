package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolContext

/**
 * How many existing files each agent replaced, edited, trashed or deleted in each task (or, for
 * calls outside a task, outside any task) over the last [windowMs]: the count behind the bulk
 * rule. Many small calls add up, so an agent cannot get around the rule by changing one file per
 * call. Kept in memory: when the app restarts, the count starts again from zero.
 */
class RecentFileChanges(
    private val windowMs: Long = ConsequentialControls.BULK_WINDOW_MS,
    private val limit: Int = ConsequentialControls.BULK_FILE_LIMIT,
) {
    /** Whose changes are counted together. */
    data class Key(val agent: AgentId, val task: TaskId?)

    private class Entry(val at: Long, val files: Int)

    private val entries = HashMap<Key, ArrayDeque<Entry>>()

    /** Files changed under [key] within the window ending at [now]. */
    @Synchronized
    fun count(key: Key, now: Long): Int = live(key, now).sumOf { it.files }

    /** True when [files] more, at [now], would go over the limit. */
    @Synchronized
    fun wouldExceed(key: Key, files: Int, now: Long): Boolean = files > 0 && count(key, now) + files > limit

    /**
     * Counts [files] at [now] and returns true, unless that goes over the limit and the call was
     * not [approved] by the owner: then nothing is counted and the call must not run.
     */
    @Synchronized
    fun take(key: Key, files: Int, now: Long, approved: Boolean): Boolean {
        if (files <= 0) return true
        if (!approved && count(key, now) + files > limit) return false
        entries.getOrPut(key) { ArrayDeque() }.addLast(Entry(now, files))
        return true
    }

    private fun live(key: Key, now: Long): ArrayDeque<Entry> {
        val list = entries[key] ?: return ArrayDeque()
        while (list.isNotEmpty() && list.first().at <= now - windowMs) list.removeFirst()
        if (list.isEmpty()) entries.remove(key)
        return list
    }
}

/** The bulk rule as the file tools apply it, over [OperatorToolDependencies.recentChanges]. */
internal object BulkRule {
    private const val LIMIT = ConsequentialControls.BULK_FILE_LIMIT
    private val MINUTES = ConsequentialControls.BULK_WINDOW_MS / 60_000

    fun key(context: ToolContext) = RecentFileChanges.Key(context.agent.id, context.taskId)

    /** CONSEQUENTIAL when [files] more existing files would make more than the limit within the window; else MODIFYING. */
    fun risk(d: OperatorToolDependencies, context: ToolContext, files: Int): RiskLevel =
        if (d.recentChanges.wouldExceed(key(context), files, d.clock.now())) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING

    /** The details line that says why the owner is asked, or null when the rule does not apply. */
    fun detail(d: OperatorToolDependencies, context: ToolContext, files: Int): String? {
        if (!d.recentChanges.wouldExceed(key(context), files, d.clock.now())) return null
        val before = d.recentChanges.count(key(context), d.clock.now())
        return "You are asked because this makes more than $LIMIT existing files replaced, edited or removed by ${context.agent.name} " +
            "within $MINUTES minutes${if (context.taskId != null) " in this task" else ""} (${before} before this call, " +
            "${OperatorText.count(files, "file")} in it)."
    }

    /**
     * Counts the call's [files] right before it acts. Null to go on; otherwise why it must not
     * run: other calls filled the window since it was assessed, and the owner did not approve it.
     */
    fun take(d: OperatorToolDependencies, context: ToolContext, files: Int, assessed: RiskLevel): String? =
        if (d.recentChanges.take(key(context), files, d.clock.now(), approved = assessed == RiskLevel.CONSEQUENTIAL)) null
        else "Nothing was done: since this call was checked, ${context.agent.name} changed enough other files that it now makes more than " +
            "$LIMIT within $MINUTES minutes, which needs the owner's approval. Call it again to ask."
}
