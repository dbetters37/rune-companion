package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.tasks.TaskRecord
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What the orchestrator and the app do for permissions at start-up, when a task ends, and as time
 * passes, so that nothing outlives what it was for:
 *  - [startUp], once, before any task is recovered: every PENDING request that no call of this
 *    process waits for ([RequestWaiters]) is closed as CANCELLED, since no waiter survives the app
 *    stopping. A request raised by work the owner restarted before start-up finished (Resume,
 *    Run again, Retry) has a live waiter and is left alone;
 *  - [taskEnded], when a task reaches a finished status: the agents' own pending asks for it
 *    are withdrawn ([OwnerRequestQueue.cancelAsksForTask]) and its TASK and ONCE grants revoked
 *    ([GrantLookup.revokeAllForTask]);
 *  - [closeOverdue], from the app every minute: requests past their expiry are closed as EXPIRED
 *    (an agent's own ask has no waiter whose timer would close it);
 *  - [screenStopped], when the owner presses Stop on screen control: pending requests to see or
 *    use a screen are withdrawn, since every screen call is refused until the owner resumes.
 * Every request closed here is logged on its own, with its request id. It narrows only: nothing
 * here can approve or grant.
 */
class OperatorTaskHooks(
    private val requests: OwnerRequestQueue,
    private val grants: GrantLookup,
    private val waiters: RequestWaiters,
    private val activity: ActivityRecorder,
    private val clock: Clock,
) {
    private val withdrawals = RequestWithdrawals(requests, activity, clock)

    /** Closes every pending request nothing in this process waits for, as CANCELLED. Returns those it closed. */
    suspend fun startUp(): List<OwnerRequest> =
        withdrawals.withdraw("Request withdrawn at start-up", STOPPED_NOTE) { !waiters.isWaiting(it.id) }

    /** Withdraws [task]'s pending asks and revokes its TASK and ONCE grants. Safe to call more than once. */
    suspend fun taskEnded(task: TaskRecord) {
        val now = clock.now()
        val note = "The task ended (${task.status.name.lowercase().replace('_', ' ')})."
        val withdrawn = requests.cancelAsksForTask(task.id, now, note)
        withdrawn.forEach { withdrawals.log(it, "Request withdrawn, its task ended", note) }
        val revoked = grants.revokeAllForTask(task.id, now)
        if (withdrawn.isNotEmpty() || revoked > 0) {
            logSafely {
                activity.record(
                    ActivityType.PERMISSION, "Task permissions ended: ${task.title}",
                    "${revoked} permission(s) for the task revoked, ${withdrawn.size} open question(s) withdrawn.",
                    agentId = task.assignee, taskId = task.id, rootTaskId = task.rootId,
                    data = buildJsonObject {
                        put("revoked", revoked)
                        put("withdrawn", withdrawn.size)
                        putJsonArray("requestIds") { withdrawn.forEach { add(it.id.value) } }
                    },
                )
            }
        }
    }

    /** Closes, as EXPIRED, the requests still pending after their expiry, and logs each. Returns those it closed. */
    suspend fun closeOverdue(): List<OwnerRequest> {
        val closed = requests.closeOverdue(clock.now(), EXPIRED_NOTE)
        closed.forEach { withdrawals.log(it, "No answer in time", it.summary) }
        return closed
    }

    /** Withdraws the pending requests to see or use a screen: the owner pressed Stop. Returns those it closed. */
    suspend fun screenStopped(): List<OwnerRequest> =
        withdrawals.withdraw("Request withdrawn, screen control stopped", SCREEN_STOPPED_NOTE) { it.capability in SCREEN_CAPABILITIES }

    /** Texts. */
    companion object {
        const val STOPPED_NOTE = "The app stopped while waiting for your answer."

        /** Why a request no one answered in time was closed. */
        const val EXPIRED_NOTE = "No answer in time."

        const val SCREEN_STOPPED_NOTE = "You pressed Stop on screen control, so this was not done."

        private val SCREEN_CAPABILITIES = setOf(OperatorCapabilities.SCREEN_READ, OperatorCapabilities.SCREEN_OPERATE)
    }
}
