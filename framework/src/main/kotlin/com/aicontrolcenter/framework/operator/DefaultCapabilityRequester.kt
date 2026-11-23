package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.CapabilityRequester
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.OperatorGate
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.requestId
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.ToolContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How an agent asks the owner for a capability ([CapabilityRequester]): the only dependency of
 * the `permissions.request` tool. It raises a PERMISSION request and returns; it never waits and
 * can grant nothing. In order:
 *  - [OperatorGate] on facts read now ([OperatorFacts.forAsk]), so nothing the broker would
 *    refuse is put to the owner;
 *  - the reason must be 1 to [CapabilityRequester.MAX_REASON_CHARS] characters, and is stored
 *    verbatim (the owner sees it quoted);
 *  - [AskResult.AlreadyGranted] when the capability is granted by default or a TASK or ALWAYS
 *    grant covers it;
 *  - at most one pending ask per agent, capability and constraint ([AskResult.AlreadyPending])
 *    and [CapabilityRequester.MAX_PENDING_ASKS_PER_AGENT] per agent;
 *  - the ask's risk is that of the capability's riskiest tool ([OperatorTools.riskOf]) and it
 *    expires after `approvalTimeoutMinutes`. Asks of a task are withdrawn when it ends
 *    ([OperatorTaskHooks]).
 * Checks and creation run under one lock, so concurrent asks cannot pass the limits together.
 */
class DefaultCapabilityRequester(
    private val facts: OperatorFacts,
    private val requests: OwnerRequestQueue,
    private val grants: GrantLookup,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
) : CapabilityRequester {
    private val lock = Mutex()

    override suspend fun ask(context: ToolContext, capability: Capability, constraint: CapabilityConstraint, reason: String): AskResult = lock.withLock {
        val now = clock.now()
        OperatorGate.refusal(facts.forAsk(context.agent.id, capability, constraint))?.let { return AskResult.Refused(it) }
        if (reason.isBlank()) return AskResult.Refused("Say in a sentence why you need it; the owner reads your reason.")
        if (reason.length > CapabilityRequester.MAX_REASON_CHARS) {
            return AskResult.Refused("The reason is longer than ${CapabilityRequester.MAX_REASON_CHARS} characters; shorten it.")
        }
        // The gate refuses a missing agent or an unknown capability.
        val agent = facts.agent(context.agent.id) ?: return AskResult.Refused("This agent no longer exists.")
        val info = facts.capabilityInfo(capability) ?: return AskResult.Refused("\"${capability.id}\" is not a permission this app knows.")
        if (info.grantedByDefault || grants.findMatching(GrantQuery(agent.id, capability, constraint, context.taskId, now)).isNotEmpty()) {
            return AskResult.AlreadyGranted
        }
        val waiting = requests.observePending().first().filter { it.agentId == agent.id && it.toolId == null && !it.isExpiredAt(now) }
        waiting.firstOrNull { it.capability == capability && it.constraint == constraint }?.let { return AskResult.AlreadyPending(it.id) }
        if (waiting.size >= CapabilityRequester.MAX_PENDING_ASKS_PER_AGENT) {
            return AskResult.Refused("You already have ${waiting.size} questions waiting for the owner; wait for an answer first.")
        }
        val target = facts.targetLabel(constraint)
        val minutes = facts.settings().approvalTimeoutMinutes.coerceAtLeast(1)
        val request = OwnerRequest(
            id = ids.requestId(), kind = OwnerRequestKind.PERMISSION, agentId = agent.id, toolId = null, capability = capability,
            constraint = constraint, risk = OperatorTools.riskOf(capability),
            summary = "${agent.name} asks for \"${info.title}\"" + (target?.let { ": $it" } ?: ""), details = listOf(info.description),
            reason = reason, taskId = context.taskId, rootId = context.rootTaskId, conversationId = context.conversationId,
            createdAt = now, expiresAt = now + minutes * 60_000L,
            agentName = agent.name, targetLabel = target, delegationChain = context.delegationChain,
        )
        requests.create(request)
        logSafely {
            activity.record(
                ActivityType.PERMISSION, "Permission asked for by the agent: ${info.title}", request.summary,
                agentId = agent.id, taskId = context.taskId, rootTaskId = context.rootTaskId,
                data = buildJsonObject {
                    put("requestId", request.id.value)
                    put("capability", capability.id)
                    put("constraint", constraint.stored)
                    put("reasonChars", reason.length)
                },
            )
        }
        AskResult.Raised(request.id)
    }
}
