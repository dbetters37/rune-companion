package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.CapabilityRequester
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorGate
import com.aicontrolcenter.core.operator.OperatorTools
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.requestId
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.ToolContext
import kotlinx.coroutines.flow.first
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
 *  - refused at once, without a request, when the owner refused this agent the same permission
 *    and target less than [OwnerRequest.REFUSAL_COOLDOWN_MS] ago; the refusal counts the repeat
 *    ([OwnerRequestQueue.countAskedAgain]);
 *  - the ask's risk is that of the capability's riskiest tool ([OperatorTools.riskOf]) and it
 *    expires after `approvalTimeoutMinutes`, or the objective's own time-out (a routine's, F12).
 *    Asks of a task are withdrawn when it ends ([OperatorTaskHooks]). An ask raised here is
 *    registered in [RequestWaiters]: the agent of this run of the app is the one that uses the answer;
 *  - (Phase 6, F12) an ask to see or use other apps' screens in an objective that may not use them
 *    (a routine's without the owner's tick, or one whose record cannot be read) is refused with
 *    [ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED] before anything else;
 *  - (Phase 6, C3, D10) refused, without a request, when too many agent-initiated requests are
 *    open, in the app or under the objective ([OwnerAttentionGate.agentRequestRefusal]); the owner
 *    is told once per task.
 * Checks and creation run under the one lock of [OwnerAttentionGate], shared with the permission
 * broker and the question tool, so concurrent asks cannot pass the limits together.
 */
class DefaultCapabilityRequester(
    private val facts: OperatorFacts,
    private val requests: OwnerRequestQueue,
    private val grants: GrantLookup,
    private val waiters: RequestWaiters,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    /** Where the objective's options are read (a routine's screen choice and time-out); null where asks have no tasks. */
    private val tasks: TaskRepository? = null,
    /** The one lock and caps shared with the permission broker and the question tool; its own by default. */
    private val attention: OwnerAttentionGate = OwnerAttentionGate(requests, waiters, activity),
) : CapabilityRequester {

    override suspend fun ask(context: ToolContext, capability: Capability, constraint: CapabilityConstraint, reason: String): AskResult = attention.exclusive {
        askLocked(context, capability, constraint, reason)
    }

    private suspend fun askLocked(context: ToolContext, capability: Capability, constraint: CapabilityConstraint, reason: String): AskResult {
        val now = clock.now()
        val options = objectiveOptions(context)
        // F12, second review: a routine's objective sees and uses other apps' screens only when the owner ticked it.
        if (!options.screenActionsAllowed && capability in SCREEN_CAPABILITIES) return AskResult.Refused(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED)
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
        requests.lastRefusal(agent.id, capability, constraint, now - OwnerRequest.REFUSAL_COOLDOWN_MS)?.let { refusal ->
            val counted = orNull { requests.countAskedAgain(refusal.id) } ?: refusal
            val reason = refusalCooldownReason(agent.name, info.title, refusal, now)
            logSafely {
                activity.record(
                    ActivityType.PERMISSION, "Not asked again: ${info.title}", reason, severity = Severity.WARNING,
                    agentId = agent.id, taskId = context.taskId, rootTaskId = context.rootTaskId,
                    data = requestData(counted) { put("askedAgain", counted.askedAgain) },
                )
            }
            return AskResult.Refused(reason)
        }
        if (waiting.size >= CapabilityRequester.MAX_PENDING_ASKS_PER_AGENT) {
            return AskResult.Refused("You already have ${waiting.size} questions waiting for the owner; wait for an answer first.")
        }
        attention.agentRequestRefusal(context, attention.live(now))?.let { refusal ->
            attention.tellOwnerOnce(context, "ask for \"${info.title}\"", refusal)
            return AskResult.Refused(refusal)
        }
        val target = facts.targetLabel(constraint)
        // A routine's objective may set its own time-out (F12); otherwise the owner's usual one.
        val minutes = options.approvalTimeoutMinutes?.coerceIn(1, RoutineLimits.MAX_APPROVAL_TIMEOUT_MINUTES)
            ?: facts.settings().approvalTimeoutMinutes.coerceAtLeast(1)
        val request = OwnerRequest(
            id = ids.requestId(), kind = OwnerRequestKind.PERMISSION, agentId = agent.id, toolId = null, capability = capability,
            constraint = constraint, risk = OperatorTools.riskOf(capability),
            summary = "${agent.name} asks for \"${info.title}\"" + (target?.let { ": $it" } ?: ""), details = listOf(info.description),
            reason = reason, taskId = context.taskId, rootId = context.rootTaskId, conversationId = context.conversationId,
            createdAt = now, expiresAt = now + minutes * 60_000L,
            agentName = agent.name, targetLabel = target, delegationChain = context.delegationChain,
        )
        waiters.register(request.id)
        try {
            requests.create(request)
        } catch (e: Exception) {
            waiters.unregister(request.id)
            throw e
        }
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
        return AskResult.Raised(request.id)
    }

    /** The options of the objective of [context], as the broker reads them: none outside one, no screens when unreadable (F12). */
    private suspend fun objectiveOptions(context: ToolContext): ObjectiveOptions {
        val root = context.rootTaskId ?: return ObjectiveOptions()
        val store = tasks ?: return ObjectiveOptions()
        val record = store.get(root) ?: return ObjectiveOptions(screenActionsAllowed = false)
        return record.options ?: ObjectiveOptions()
    }

    private companion object {
        val SCREEN_CAPABILITIES = setOf(OperatorCapabilities.SCREEN_READ, OperatorCapabilities.SCREEN_OPERATE)
    }
}
