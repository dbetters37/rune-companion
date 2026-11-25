package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.operator.OwnerAttention
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.tools.ToolContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How much of the owner's attention agents may ask for at once (Phase 6, rules 13 and 14, C3,
 * D10), with the one lock under which every request to the owner is checked and raised: the
 * permission broker's (a tool call waiting for a PERMISSION or an APPROVAL), the capability
 * requester's (an agent's own `permissions.request`) and the question tool's
 * ([OwnerQuestions]). So two concurrent asks can never pass a cap together.
 *
 * The caps of [OwnerAttention], counted over the requests still pending that a live call of this
 * process waits for ([RequestWaiters]; a request nothing waits for can never be answered and is
 * closed at start-up):
 *  - requests raised by calls in progress: [OwnerAttention.MAX_PENDING_CALL_REQUESTS] in all and
 *    [OwnerAttention.MAX_PENDING_CALL_REQUESTS_PER_OBJECTIVE] per objective (rule 14);
 *  - agent-initiated requests (its own asks and its questions):
 *    [OwnerAttention.MAX_OPEN_AGENT_REQUESTS] in all and
 *    [OwnerAttention.MAX_OPEN_AGENT_REQUESTS_PER_OBJECTIVE] per objective (rule 13, C3).
 * Over a cap nothing is asked and nothing runs; the model is told to assume, say so and go on, and
 * the owner is told once per task (or chat) in the activity log ([tellOwnerOnce]).
 *
 * It narrows only: nothing here raises, answers or closes a request.
 */
class OwnerAttentionGate(
    private val requests: OwnerRequestQueue,
    private val waiters: RequestWaiters,
    private val activity: ActivityRecorder,
) {
    private val lock = Mutex()

    /** The tasks (or chats, or agents outside both) the owner was already told about, bounded. */
    private val told = object : LinkedHashMap<String, Unit>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > MAX_TOLD
    }

    /** Runs [block] holding the one lock: checks and the raising of a request are one step. Never wait for the owner inside it. */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    /** The requests pending now that a live call waits for, not yet past their expiry. Read it inside [exclusive]. */
    suspend fun live(now: Long): List<OwnerRequest> =
        requests.observePending().first().filter { !it.isExpiredAt(now) && waiters.isWaiting(it.id) }

    /**
     * Why a tool call of [context] may not raise one more request (rule 14), given [live]: too many
     * requests of calls in progress wait already, in all or under its objective. Null to go on.
     */
    fun callRequestRefusal(context: ToolContext, live: List<OwnerRequest>): String? {
        val calls = live.filter { !OwnerAttention.isAgentInitiated(it) }
        if (calls.size >= OwnerAttention.MAX_PENDING_CALL_REQUESTS) return manyWaiting(calls.size, null)
        val root = context.rootTaskId ?: return null
        val underObjective = calls.count { it.rootId == root }
        if (underObjective >= OwnerAttention.MAX_PENDING_CALL_REQUESTS_PER_OBJECTIVE) return manyWaiting(underObjective, "for this objective")
        return null
    }

    /**
     * Why an agent of [context] may not raise one more request of its own (an ask or a question,
     * rule 13, C3), given [live]: too many agent-initiated requests are open, in all or under its
     * objective. Null to go on.
     */
    fun agentRequestRefusal(context: ToolContext, live: List<OwnerRequest>): String? {
        val own = live.filter { OwnerAttention.isAgentInitiated(it) }
        if (own.size >= OwnerAttention.MAX_OPEN_AGENT_REQUESTS) return manyOwn(own.size, null)
        val root = context.rootTaskId ?: return null
        val underObjective = own.count { it.rootId == root }
        if (underObjective >= OwnerAttention.MAX_OPEN_AGENT_REQUESTS_PER_OBJECTIVE) return manyOwn(underObjective, "for this objective")
        return null
    }

    /**
     * Tells the owner, in the activity log, that [context]'s agent was refused by a cap ([reason]),
     * once per task (or chat, or agent outside both): a model that keeps asking does not fill the
     * log. Never fails.
     */
    suspend fun tellOwnerOnce(context: ToolContext, what: String, reason: String) {
        val key = context.taskId?.let { "task:${it.value}" } ?: context.conversationId?.let { "chat:${it.value}" } ?: "agent:${context.agent.id.value}"
        val first = synchronized(told) { told.put(key, Unit) == null }
        if (!first) return
        logSafely {
            activity.record(
                ActivityType.PERMISSION, "Not asked: too many requests wait for you",
                "${context.agent.name} wanted to $what. $reason".take(MAX_LOGGED), severity = Severity.WARNING,
                agentId = context.agent.id, taskId = context.taskId, rootTaskId = context.rootTaskId,
                data = buildJsonObject {
                    put("capReached", true)
                    context.conversationId?.let { put("conversationId", it.value) }
                },
            )
        }
    }

    /** Texts and bounds. */
    companion object {
        /** What the model reads after a refusal by a cap: assume, say so, go on. */
        const val ASSUME_AND_GO_ON =
            "Nothing was asked and nothing was run. Do not ask again now: make the most reasonable assumption, say in your result " +
                "what you assumed and what still needs the owner, and go on."

        private const val MAX_TOLD = 500
        private const val MAX_LOGGED = 600

        private fun manyWaiting(count: Int, where: String?): String =
            "Many requests already wait for the owner's answer ($count${where?.let { " $it" }.orEmpty()}). $ASSUME_AND_GO_ON"

        private fun manyOwn(count: Int, where: String?): String =
            "Agents already have many questions and requests open for the owner ($count${where?.let { " $it" }.orEmpty()}). $ASSUME_AND_GO_ON"
    }
}
