package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.tasks.TaskRecord
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the orchestrator does for permissions at start-up and when a task ends, so that nothing
 * outlives what it was for:
 *  - [startUp], once, before any task is recovered: every PENDING request is closed as
 *    CANCELLED, since no waiter survives the app stopping;
 *  - [taskEnded], when a task reaches a finished status: the agents' own pending asks for it
 *    are withdrawn ([OwnerRequestQueue.cancelAsksForTask]) and its TASK and ONCE grants revoked
 *    ([GrantLookup.revokeAllForTask]).
 * It narrows only: nothing here can approve or grant.
 */
class OperatorTaskHooks(
    private val requests: OwnerRequestQueue,
    private val grants: GrantLookup,
    private val activity: ActivityRecorder,
    private val clock: Clock,
) {
    /** Closes every pending request as CANCELLED. Returns those it closed. */
    suspend fun startUp(): List<OwnerRequest> {
        val closed = requests.cancelAllPending(clock.now(), STOPPED_NOTE)
        if (closed.isNotEmpty()) {
            logSafely {
                activity.record(
                    ActivityType.PERMISSION, "Requests withdrawn at start-up", "${closed.size} question(s) to the owner were still open when the app stopped.",
                    severity = Severity.WARNING, data = buildJsonObject { put("count", closed.size) },
                )
            }
        }
        return closed
    }

    /** Withdraws [task]'s pending asks and revokes its TASK and ONCE grants. Safe to call more than once. */
    suspend fun taskEnded(task: TaskRecord) {
        val now = clock.now()
        val withdrawn = requests.cancelAsksForTask(task.id, now, "The task ended (${task.status.name.lowercase().replace('_', ' ')}).")
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
                    },
                )
            }
        }
    }

    /** Texts. */
    companion object {
        const val STOPPED_NOTE = "The app stopped while waiting for your answer."
    }
}
