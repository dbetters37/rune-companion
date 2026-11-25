package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The requests something in this process still waits for: a tool call held by the permission
 * broker (from just before its request is stored until just after it is closed), or an agent's
 * own ask raised in this process (`permissions.request`, whose answer the agent uses later).
 *
 * A PENDING request that is not here has no waiter: it was raised by an earlier run of the app,
 * or its waiter went away without closing it. Such a request can never be answered: the owner's
 * port closes it instead ([DefaultOwnerRequests.closeIfAbandoned]), the pending list leaves it
 * out, and start-up closes every one ([OperatorTaskHooks.startUp]), while requests raised by work
 * that started before start-up finished keep their waiters.
 *
 * Only the broker and the capability requester add requests, each only its own; nothing here can
 * answer, grant or close anything.
 */
class RequestWaiters {
    private val live = MutableStateFlow<Set<RequestId>>(emptySet())

    /** The requests waited for now. */
    val waiting: StateFlow<Set<RequestId>> = live.asStateFlow()

    fun isWaiting(id: RequestId): Boolean = id in live.value

    /** [id] is about to be raised and waited for. Called before it is stored, so it is never seen pending without its waiter. */
    fun register(id: RequestId) = live.update { it + id }

    /** Nothing waits for [id] any more. Called once it is closed (or could not be). */
    fun unregister(id: RequestId) = live.update { it - id }
}

/**
 * Closes pending requests that can no longer be answered usefully, as CANCELLED with a note the
 * owner and the agent read, and logs each one with its request id. Used at start-up, when a task
 * ends, and when the owner withdraws what a request is about (an app, a folder, a permission,
 * screen control). It narrows only: nothing here can approve or grant.
 */
class RequestWithdrawals(
    private val requests: OwnerRequestQueue,
    private val activity: ActivityRecorder,
    private val clock: Clock,
) {
    /**
     * Closes, as CANCELLED with [note], every pending request [which] picks, each with its own
     * compare-and-set (one answered meanwhile is left as answered). Each one closed is logged as
     * "[title]: <what it asked>". Returns those this call closed, oldest first.
     */
    suspend fun withdraw(title: String, note: String, which: suspend (OwnerRequest) -> Boolean): List<OwnerRequest> {
        val at = clock.now()
        val closed = requests.observePending().first().filter { which(it) }.mapNotNull { requests.close(it.id, OwnerRequestStatus.CANCELLED, at, note) }
        closed.forEach { log(it, title, note) }
        return closed
    }

    /** Logs [request], closed by someone other than its waiter, as "[title]: <what it asked>" with [detail]. */
    suspend fun log(request: OwnerRequest, title: String, detail: String, severity: Severity = Severity.WARNING) = logSafely {
        activity.record(
            activityTypeOf(request),
            "$title: ${whatItAsked(request)}", detail.take(MAX_LOGGED), severity = severity,
            agentId = request.agentId, taskId = request.taskId, rootTaskId = request.rootId, data = requestData(request),
        )
    }

    private companion object {
        const val MAX_LOGGED = 600
    }
}

/** What [request] asked, in a few words, for log titles: the tool's title, the agent's own ask, or its question. */
internal fun whatItAsked(request: OwnerRequest): String = when {
    request.kind == OwnerRequestKind.QUESTION -> "question from ${request.agentName ?: "an agent"}"
    else -> request.toolTitle ?: "permission asked by ${request.agentName ?: "an agent"}"
}

/** Where the log files an entry about [request]: approvals, permissions, or (a question, Phase 6) messages. */
internal fun activityTypeOf(request: OwnerRequest): ActivityType = when (request.kind) {
    OwnerRequestKind.APPROVAL -> ActivityType.APPROVAL
    OwnerRequestKind.PERMISSION -> ActivityType.PERMISSION
    OwnerRequestKind.QUESTION -> ActivityType.MESSAGE
}

/**
 * The data of an activity entry about [request], as the broker writes it: the request id (so a
 * request's whole trail can be found in the log), kind, tool, permission, target, status, chat.
 */
fun requestData(request: OwnerRequest, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("requestId", request.id.value)
    put("kind", request.kind.name)
    request.toolId?.let { put("toolId", it.value) }
    put("capability", request.capability.id)
    put("constraint", request.constraint.stored)
    put("status", request.status.name)
    request.conversationId?.let { put("conversationId", it.value) }
    extra()
}

/** Runs [block], turning an exception other than cancellation into null. */
internal suspend fun <T> orNull(block: suspend () -> T): T? = try {
    block()
} catch (e: Exception) {
    rethrowIfCancelled(e)
    null
}
