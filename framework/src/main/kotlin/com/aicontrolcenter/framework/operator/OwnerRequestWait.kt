package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.WaitClock
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.tools.OwnerTimeAccount
import com.aicontrolcenter.framework.tools.StoredDeadlines
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one way a live call raises a request to the owner and waits for its answer (Phase 6: the
 * wait of [PermissionBroker], extracted so that [OwnerQuestions] waits exactly the same way):
 *
 *  1. Under the one lock of [OwnerAttentionGate], [ask]'s `admit` sees the live pending requests
 *     and may refuse (a cap, rules 13 and 14): then nothing is stored and nothing waits. Otherwise
 *     the request is registered in [RequestWaiters] and then stored, so the owner never sees it
 *     pending without a live waiter, and `afterRaise` runs, still under the lock.
 *  2. Outside the lock, the call waits inside [WaitClock.pausedWhile] (the wait does not count
 *     against the time limits of the run, the task or the objective) until the owner answers or
 *     the request's expiry, when it is closed as EXPIRED (an answer given at the last moment
 *     stands). Meanwhile the call's task shows WAITING_FOR_OWNER (changed only from RUNNING,
 *     never on an objective) and the run is told ([OwnerWaits]).
 *  3. However it ends, also when the waiting coroutine is cancelled (the request is then closed
 *     as CANCELLED): an unused ONCE grant of the request is released, the waiter unregistered,
 *     the task RUNNING again with its stored deadline (and that of every delegating task waiting
 *     on it) moved later by the time waited ([StoredDeadlines], told to [OwnerTimeAccount]), the
 *     wait written to [OwnerWaitLedger] (D12), `finished` called and the run told.
 *
 * It can approve, grant or answer nothing: it holds the queue and the grant lookup only.
 */
internal class OwnerRequestWait(
    private val requests: OwnerRequestQueue,
    /** Releases an unused ONCE grant of the request; null where requests carry none (questions). */
    private val grants: GrantLookup?,
    private val waiters: RequestWaiters,
    private val tasks: TaskRepository?,
    private val clock: Clock,
    val attention: OwnerAttentionGate,
    private val ledger: OwnerWaitLedger?,
) {
    /**
     * Raises [request] for the call of [context] and waits for its answer; see the class comment.
     *
     * @param waitingDetail the task's status line while it waits.
     * @param admit under the lock, with the live pending requests: why not to ask ([OwnerAttentionGate]), or null.
     * @param afterRaise under the lock, once the request is stored.
     * @param raised outside the lock, once stored, before the wait (logging).
     * @param use what the closed request means for the caller; its result is returned.
     * @param finished on the way out, never cancelled, with the request as it ended and what `use` made of it (null if it did not run).
     * @param refused what to return when `admit` refused (nothing was stored and nothing waited), given its reason.
     */
    suspend fun <T> ask(
        request: OwnerRequest,
        context: ToolContext,
        waitingDetail: String,
        admit: suspend (live: List<OwnerRequest>) -> String?,
        afterRaise: suspend () -> Unit = {},
        raised: suspend () -> Unit = {},
        use: suspend (OwnerRequest) -> T,
        finished: suspend (ended: OwnerRequest, outcome: T?) -> Unit,
        refused: suspend (reason: String) -> T,
    ): T {
        val refusal = attention.exclusive {
            admit(attention.live(clock.now()))?.let { return@exclusive it }
            waiters.register(request.id)
            try {
                requests.create(request)
            } catch (e: Exception) {
                waiters.unregister(request.id)
                throw e
            }
            try {
                afterRaise()
            } catch (e: Exception) {
                // The request is stored and waited for below; whatever afterRaise could not record is bookkeeping.
                rethrowIfCancelled(e)
            }
            null
        }
        if (refusal != null) return refused(refusal)
        return wait(request, context.taskId, ledgerKey(context), waitingDetail, raised, use, finished)
    }

    private suspend fun <T> wait(
        request: OwnerRequest,
        taskId: TaskId?,
        key: OwnerWaitLedger.Key,
        waitingDetail: String,
        raised: suspend () -> Unit,
        use: suspend (OwnerRequest) -> T,
        finished: suspend (OwnerRequest, T?) -> Unit,
    ): T {
        val waits = OwnerWaits.current()
        val startedAt = clock.now()
        // The wait as the paused limits count it, for whoever accounts for this call's paused time (the tool executor).
        val waitClock = WaitClock.current()
        val pausedBefore = waitClock?.pausedMs() ?: 0L
        var waitingTask: WaitingTask? = null
        var ended: OwnerRequest? = null
        var outcome: T? = null
        // D12: the bulk rule's window stands still for the agent, its task and its objective while it waits.
        val ledgerHandle = ledger?.start(key, startedAt)
        try {
            safely { raised() }
            waitingTask = markWaiting(taskId, waitingDetail)
            waits?.waiting(request)
            val answer = WaitClock.pausedWhile { answerOf(request) }
            ended = answer
            return use(answer).also { outcome = it }
        } finally {
            withContext(NonCancellable) {
                val at = clock.now()
                val final = ended ?: safely { requests.close(request.id, OwnerRequestStatus.CANCELLED, at, PermissionBroker.CANCELLED_NOTE) }
                    ?: safely { requests.get(request.id) } ?: request
                grants?.let { safely { it.releaseOnceFor(request.id, at) } }
                waiters.unregister(request.id)
                ledgerHandle?.let { ledger?.finish(it, at) }
                val pausedMs = ((waitClock?.pausedMs() ?: 0L) - pausedBefore).coerceAtLeast(0)
                waitingTask?.let { safely { resume(it, (at - startedAt).coerceAtLeast(0), pausedMs, at) } }
                safely { finished(final, outcome) }
                waits?.let { safely { it.answered(final) } }
            }
        }
    }

    /**
     * The request once it is no longer pending: as the owner answered it, or closed as EXPIRED
     * at its expiry. When the owner answered at the last moment, so that closing it fails, the
     * answer stands.
     */
    private suspend fun answerOf(request: OwnerRequest): OwnerRequest {
        val waitMs = (request.expiresAt - clock.now()).coerceAtLeast(0)
        val seen = withTimeoutOrNull(waitMs) { Seen(requests.observe(request.id).first { it == null || !it.status.isPending }) }
        if (seen != null) return seen.request ?: request.closed(OwnerRequestStatus.CANCELLED, "The request no longer exists.")
        val note = "No answer within ${minutesOf(request)} minutes."
        val at = clock.now()
        return requests.close(request.id, OwnerRequestStatus.EXPIRED, at, note)
            ?: requests.get(request.id)?.takeIf { !it.status.isPending }
            ?: request.closed(OwnerRequestStatus.EXPIRED, note)
    }

    private class Seen(val request: OwnerRequest?)

    // ---- the waiting task ----

    private class WaitingTask(val id: TaskId, val flipped: Boolean, val detailBefore: String?)

    /** Shows the call's task as WAITING_FOR_OWNER when it is RUNNING (never an objective, whose status the orchestrator keeps). */
    private suspend fun markWaiting(taskId: TaskId?, detail: String): WaitingTask? {
        val store = tasks ?: return null
        taskId ?: return null
        var flipped = false
        var before: String? = null
        return try {
            store.update(taskId) { task ->
                flipped = task.kind != TaskKind.OBJECTIVE && task.status == TaskStatus.RUNNING
                before = task.statusDetail
                if (!flipped) task else task.copy(status = TaskStatus.WAITING_FOR_OWNER, statusDetail = detail, updatedAt = clock.now())
            } ?: return null
            WaitingTask(taskId, flipped, before)
        } catch (e: Exception) {
            rethrowIfCancelled(e)
            // A store that fails here costs the task its status line, never the call.
            WaitingTask(taskId, false, null)
        }
    }

    /**
     * After the wait: the task is RUNNING again (unless the owner changed its status meanwhile)
     * and its deadline, with that of every delegating task waiting on it, is [waitedMs] later
     * ([StoredDeadlines], the rule the tool executor keeps for every other wait of a call).
     * The executor around this call is told that the wait ([pausedMs], as the paused limits
     * counted it) is in the store, so it does not write it again ([OwnerTimeAccount]).
     */
    private suspend fun resume(waiting: WaitingTask, waitedMs: Long, pausedMs: Long, at: Long) {
        val store = tasks ?: return
        val task = store.update(waiting.id) { task ->
            var next = task
            if (waiting.flipped && task.status == TaskStatus.WAITING_FOR_OWNER) next = next.copy(status = TaskStatus.RUNNING, statusDetail = waiting.detailBefore)
            // An objective's deadline is the orchestrator's: its running limit keeps it ([ObjectiveDriver]).
            next = StoredDeadlines.later(next, waitedMs)
            if (next == task) task else next.copy(updatedAt = at)
        } ?: return
        val delegating = StoredDeadlines.moveDelegating(store, task, waitedMs, at)
        OwnerTimeAccount.stored(delegating + waiting.id, pausedMs)
    }

    private fun minutesOf(request: OwnerRequest): Long = ((request.expiresAt - request.createdAt) / MINUTE_MS).coerceAtLeast(1)

    private fun OwnerRequest.closed(status: OwnerRequestStatus, note: String) = copy(status = status, decidedAt = clock.now(), decisionNote = note)

    /** Bookkeeping on the way out never changes what happened: a store or log that fails is passed over. */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        null
    }

    /** Helpers. */
    companion object {
        private const val MINUTE_MS = 60_000L

        /** Who waits, for [OwnerWaitLedger]: the agent, its task and its objective. */
        fun ledgerKey(context: ToolContext) = OwnerWaitLedger.Key(context.agent.id, context.taskId, context.rootTaskId ?: context.taskId)

        /** A line no longer than [max] characters, cut with "…". */
        fun line(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"
    }
}
