package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolContext

/**
 * How many existing files were replaced, edited, trashed or deleted over the last [windowMs]:
 * the count behind the bulk rule. Each change is counted three ways, and the largest count
 * decides: by the agent in the task (or, for calls outside a task, outside any task), by the
 * agent across all its tasks, and by all agents working on the same root objective. So many
 * small calls add up, and neither a new task nor a helper agent starts again at zero. Kept in
 * memory: when the app restarts, the counts start again from zero.
 *
 * Time spent waiting for the owner does not age a change (Phase 6, D12): with [ownerWaits], a
 * change's age is the time since it was made less the time its agent, its task or its objective
 * waited for the owner since then ([OwnerWaitLedger.waitedMs]). So an approval that took 11
 * minutes does not empty the window.
 */
class RecentFileChanges(
    private val windowMs: Long = ConsequentialControls.BULK_WINDOW_MS,
    private val limit: Int = ConsequentialControls.BULK_FILE_LIMIT,
    /** The owner waits of agents, tasks and objectives (D12); null: the window runs on the wall clock alone. */
    private val ownerWaits: OwnerWaitLedger? = null,
) {
    /**
     * Whose changes a call adds to: the [agent], its [task] (null outside any task) and the
     * [root] objective the task belongs to (null outside any objective).
     */
    data class Key(val agent: AgentId, val task: TaskId?, val root: TaskId? = task)

    /** The groups a change is counted in, the narrowest first. */
    enum class Scope { TASK, AGENT, OBJECTIVE }

    private class Entry(val at: Long, val files: Int, val key: Key)

    private val entries = ArrayList<Entry>()

    /** Files changed within the window ending at [now], in the [scope] that [key] belongs to. */
    @Synchronized
    fun count(key: Key, now: Long, scope: Scope): Int {
        entries.removeAll { age(it, now) >= windowMs }
        // A wait that ended before the oldest change kept can no longer pause any of them.
        ownerWaits?.forgetBefore(entries.firstOrNull()?.at ?: now)
        return entries.sumOf { e ->
            val counted = when (scope) {
                Scope.TASK -> e.key.agent == key.agent && e.key.task == key.task
                Scope.AGENT -> e.key.agent == key.agent
                Scope.OBJECTIVE -> key.root != null && e.key.root == key.root
            }
            if (counted) e.files else 0
        }
    }

    /** The largest of the three counts for [key] within the window ending at [now]. */
    @Synchronized
    fun count(key: Key, now: Long): Int = Scope.entries.maxOf { count(key, now, it) }

    /** The narrowest scope in which [files] more, at [now], would go over the limit; null when none would. */
    @Synchronized
    fun exceededScope(key: Key, files: Int, now: Long): Scope? =
        if (files <= 0) null else Scope.entries.firstOrNull { count(key, now, it) + files > limit }

    /** True when [files] more, at [now], would go over the limit in any scope. */
    @Synchronized
    fun wouldExceed(key: Key, files: Int, now: Long): Boolean = exceededScope(key, files, now) != null

    /**
     * Counts [files] at [now] and returns true, unless that goes over the limit and the call was
     * not [approved] by the owner: then nothing is counted and the call must not run.
     */
    @Synchronized
    fun take(key: Key, files: Int, now: Long, approved: Boolean): Boolean {
        if (files <= 0) return true
        if (!approved && wouldExceed(key, files, now)) return false
        if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
        entries += Entry(now, files, key)
        return true
    }

    /** How long ago [e] was made, at [now], not counting the time its agent, task or objective waited for the owner since. */
    private fun age(e: Entry, now: Long): Long {
        val waited = ownerWaits?.waitedMs(e.key.agent, e.key.task, e.key.root, e.at, now) ?: 0L
        return now - e.at - waited
    }

    private companion object {
        /** Far more calls than fit in one window; a bound, so the list cannot grow without end if the clock stands still. */
        const val MAX_ENTRIES = 20_000
    }
}

/** The bulk rule as the file tools apply it, over [OperatorToolDependencies.recentChanges]. */
internal object BulkRule {
    private const val LIMIT = ConsequentialControls.BULK_FILE_LIMIT
    private val MINUTES = ConsequentialControls.BULK_WINDOW_MS / 60_000

    /** A task without a known root objective is its own. */
    fun key(context: ToolContext) = RecentFileChanges.Key(context.agent.id, context.taskId, context.rootTaskId ?: context.taskId)

    /** CONSEQUENTIAL when [files] more existing files would make more than the limit within the window, in any of the counts; else MODIFYING. */
    fun risk(d: OperatorToolDependencies, context: ToolContext, files: Int): RiskLevel =
        if (d.recentChanges.wouldExceed(key(context), files, d.clock.now())) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING

    /** The details line that says why the owner is asked, or null when the rule does not apply. */
    fun detail(d: OperatorToolDependencies, context: ToolContext, files: Int): String? {
        val now = d.clock.now()
        val scope = d.recentChanges.exceededScope(key(context), files, now) ?: return null
        val before = d.recentChanges.count(key(context), now, scope)
        val who = when (scope) {
            RecentFileChanges.Scope.TASK -> "by ${context.agent.name} within $MINUTES minutes${if (context.taskId != null) " in this task" else ""}"
            RecentFileChanges.Scope.AGENT -> "by ${context.agent.name} within $MINUTES minutes, counted across its tasks"
            RecentFileChanges.Scope.OBJECTIVE -> "within $MINUTES minutes by the agents working on this objective"
        }
        return "You are asked because this makes more than $LIMIT existing files replaced, edited or removed $who " +
            "(${before} before this call, ${OperatorText.count(files, "file")} in it)."
    }

    /**
     * Counts the call's [files] right before it acts. Null to go on; otherwise why it must not
     * run: other calls filled the window since it was assessed, and the owner did not approve it.
     */
    fun take(d: OperatorToolDependencies, context: ToolContext, files: Int, assessed: RiskLevel): String? =
        if (d.recentChanges.take(key(context), files, d.clock.now(), approved = assessed == RiskLevel.CONSEQUENTIAL)) null
        else "Nothing was done: since this call was checked, enough other files were changed that it now makes more than " +
            "$LIMIT within $MINUTES minutes, which needs the owner's approval. Call it again to ask."
}
