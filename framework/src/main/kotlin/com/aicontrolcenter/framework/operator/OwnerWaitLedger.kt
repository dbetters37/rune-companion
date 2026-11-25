package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.TaskId

/**
 * The time agents spend waiting for the owner (Phase 6, D12): one entry per wait, with who waited
 * (the agent, its task and its objective) and from when to when, open while the wait goes on. The
 * permission broker and the question tool open an entry when a wait starts ([start]) and close it
 * when it ends ([finish]); [RecentFileChanges] reads it, so the bulk rule's
 * window does not run on while an agent waits for the owner: a change's age is the time since it
 * was made, less the time the same agent, task or objective waited for the owner since then.
 *
 * In memory only, like the counts it serves: when the app restarts, both start again from zero.
 * At most [maxEntries] waits are kept (the oldest go first).
 */
class OwnerWaitLedger(private val maxEntries: Int = 2_000) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /** Who waited: the [agent], in [task] (null outside a task) of objective [root] (null outside an objective). */
    data class Key(val agent: AgentId, val task: TaskId?, val root: TaskId?)

    /** One wait; [until] is null while it goes on. */
    data class Wait(val key: Key, val from: Long, val until: Long?)

    private class Entry(val id: Long, val key: Key, val from: Long, var until: Long?)

    private val waits = ArrayDeque<Entry>()
    private var nextId = 1L

    /** [key] starts waiting for the owner at [from]. Returns the wait's handle for [finish]. */
    @Synchronized
    fun start(key: Key, from: Long): Long {
        if (waits.size >= maxEntries) waits.removeFirst()
        val id = nextId++
        waits.addLast(Entry(id, key, from, null))
        return id
    }

    /** The wait [handle] ended at [until] (one of no length is dropped). Unknown handles are ignored. */
    @Synchronized
    fun finish(handle: Long, until: Long) {
        val entry = waits.firstOrNull { it.id == handle } ?: return
        if (until <= entry.from) waits.remove(entry) else entry.until = until
    }

    /** Notes that [key] waited for the owner from [from] until [until]. A wait of no length is not kept. */
    @Synchronized
    fun record(key: Key, from: Long, until: Long) {
        if (until <= from) return
        finish(start(key, from), until)
    }

    /** Every wait kept, in the order they started. */
    @Synchronized
    fun all(): List<Wait> = waits.map { Wait(it.key, it.from, it.until) }

    /**
     * How long, between [since] and [now], the owner was waited for by [agent], by [task] or by
     * [root]'s objective (a null task or root matches nothing), a wait that goes on counted up to
     * [now]: overlapping waits are counted once.
     */
    @Synchronized
    fun waitedMs(agent: AgentId, task: TaskId?, root: TaskId?, since: Long, now: Long): Long {
        if (now <= since) return 0
        val spans = waits.asSequence()
            .filter { w -> w.key.agent == agent || (task != null && w.key.task == task) || (root != null && w.key.root == root) }
            .map { maxOf(it.from, since) to minOf(it.until ?: now, now) }
            .filter { (from, until) -> until > from }
            .sortedBy { it.first }
            .toList()
        var total = 0L
        var end = Long.MIN_VALUE
        for ((from, until) in spans) {
            if (until <= end) continue
            total += until - maxOf(from, end)
            end = until
        }
        return total
    }

    /** Forgets every wait that ended at or before [before]; waits that go on are kept. */
    @Synchronized
    fun forgetBefore(before: Long) {
        waits.removeAll { it.until.let { until -> until != null && until <= before } }
    }
}
