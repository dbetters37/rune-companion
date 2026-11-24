package com.aicontrolcenter.framework.tools

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/*
 * Time a tool call spends waiting for the owner does not count against the limits of the run,
 * the task or the objective around it: every such wait runs inside WaitClock.pausedWhile, which
 * pauses those limits in memory. What is stored must agree, because other things read it: a
 * teammate asked for help gets the time the task's stored deadline has left, a delegated task
 * stops at its delegating task's stored deadline, and the task screen shows it.
 *
 * Two writers keep the stored deadlines in step, by one rule ([StoredDeadlines]):
 *  - the permission broker, at the moment the owner's answer to its request comes (it also sets
 *    the task back to RUNNING);
 *  - the tool executor, around every call, for whatever else the call waited: Android's own
 *    confirmation of an install or a removal, and the wait for an operated app to come back in
 *    front after an approval.
 * [OwnerTimeAccount] keeps them from writing the same wait twice.
 */

/**
 * How much of the time one tool call waited for the owner is already in its task's stored
 * deadline. A coroutine-context element the executor puts around every call; whoever writes a
 * wait to stored deadlines says so with [stored], which also tells the calls around this one
 * (a delegating task's call runs the delegated task's calls inside it, and their waits pause it
 * too), each for its own task.
 */
internal class OwnerTimeAccount(val taskId: TaskId?, private val enclosing: OwnerTimeAccount?) : AbstractCoroutineContextElement(Key) {
    private val stored = AtomicLong()

    /** Paused time of this call, in ms, that is in its task's stored deadline (or that the rule left out of it). */
    val storedMs: Long get() = stored.get()

    /** The key, and how a writer reports. */
    companion object Key : CoroutineContext.Key<OwnerTimeAccount> {
        /**
         * A wait that paused the limits for [pausedMs] is now accounted for in the stored
         * deadline of each of [tasks]: told to the call this runs under and to every call around
         * it that belongs to one of them. Outside any call it does nothing.
         */
        suspend fun stored(tasks: Collection<TaskId>, pausedMs: Long) {
            if (pausedMs <= 0 || tasks.isEmpty()) return
            var account = currentCoroutineContext()[Key]
            while (account != null) {
                if (account.taskId in tasks) account.stored.addAndGet(pausedMs)
                account = account.enclosing
            }
        }
    }
}

/**
 * The one rule by which time waited for the owner moves stored task deadlines later: a task
 * that has a deadline, has not finished and is not an objective (an objective's deadline is the
 * orchestrator's, whose running limit stores it as it pauses), and with it every delegating
 * task still waiting on it, up the chain.
 */
internal object StoredDeadlines {
    private const val MAX_DELEGATION_WALK = 16

    /** [task] with its deadline [ms] later where the rule applies, else [task] itself. */
    fun later(task: TaskRecord, ms: Long): TaskRecord {
        val deadline = task.deadlineAt
        return if (ms > 0 && deadline != null && !task.status.isFinished && task.kind != TaskKind.OBJECTIVE) task.copy(deadlineAt = deadline + ms) else task
    }

    /**
     * After [task] waited [ms] for the owner: the deadline of every delegating task waiting on
     * it (WAITING_FOR_AGENT, up the chain) moves as much. Returns those it moved.
     */
    suspend fun moveDelegating(store: TaskRepository, task: TaskRecord, ms: Long, at: Long): List<TaskId> {
        if (ms <= 0 || task.kind != TaskKind.WORK) return emptyList()
        val moved = ArrayList<TaskId>()
        var parentId = task.parentId
        var depth = 0
        while (parentId != null && depth++ < MAX_DELEGATION_WALK) {
            val parent = store.get(parentId) ?: break
            if (parent.kind != TaskKind.WORK || parent.status != TaskStatus.WAITING_FOR_AGENT) break
            var changed = false
            store.update(parentId) {
                val deadline = it.deadlineAt
                if (it.status == TaskStatus.WAITING_FOR_AGENT && deadline != null) {
                    changed = true
                    it.copy(deadlineAt = deadline + ms, updatedAt = at)
                } else {
                    it
                }
            }
            if (changed) moved += parentId
            parentId = parent.parentId
        }
        return moved
    }

    /**
     * Moves the stored deadline of [taskId], and of the delegating tasks waiting on it, [ms]
     * later. Returns the tasks whose deadline moved; empty when the task is gone.
     */
    suspend fun moveLater(store: TaskRepository, taskId: TaskId, ms: Long, at: Long): List<TaskId> {
        if (ms <= 0) return emptyList()
        var own = false
        val task = store.update(taskId) { task ->
            val moved = later(task, ms)
            own = moved !== task
            if (own) moved.copy(updatedAt = at) else task
        } ?: return emptyList()
        return (if (own) listOf(taskId) else emptyList()) + moveDelegating(store, task, ms, at)
    }
}
