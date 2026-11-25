package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorGate
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.requestId
import com.aicontrolcenter.core.routines.RoutineLimits
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.WaitClock
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolAuthorizer
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.agents.currentAgentTurn
import com.aicontrolcenter.framework.llm.rethrowIfCancelled
import com.aicontrolcenter.framework.tools.EarlyRefusal
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Decides every tool call, asking the owner when it must and waiting for the answer. It takes
 * the place of [com.aicontrolcenter.framework.tools.DefaultToolAuthorizer] in
 * [com.aicontrolcenter.framework.tools.ToolExecutor], whose rules are its first steps. In order:
 *
 *  1–3. The facts are read fresh ([OperatorFacts]) and [OperatorGate] applied: the agent as
 *       stored is switched on and has the tool switched on (the tools the framework offered the
 *       run count, see [com.aicontrolcenter.framework.agents.AgentTurnInfo.extraToolIds]); the
 *       capability is known and its target of the right kind; the master switches allow it; the
 *       folder exists; the app is authorised and not protected; local-only mode allows it. A
 *       refusal here comes before any request, so nothing the broker would refuse is put to
 *       the owner.
 *  4.   The call passes when its capability is granted by default or a stored grant covers it
 *       ([GrantLookup.findMatching]: agent, capability, constraint, task). Otherwise, unless the
 *       owner refused this agent the same permission and target within
 *       [OwnerRequest.REFUSAL_COOLDOWN_MS] and has not let it ask again since (then the call is
 *       refused at once, the refusal counts the repeat, and the owner is not asked again), a
 *       PERMISSION request is raised and the call waits. Approved for one call (ONCE), the call uses up exactly the grant of its own
 *       request ([GrantLookup.consumeOnceFor]); for the task or always, that grant must be in
 *       force. Refused, expired or cancelled: denied, with the owner's note passed on as quoted
 *       text.
 *  5.   A CONSEQUENTIAL call (the higher of the tool's declared and the call's assessed risk)
 *       raises an APPROVAL request with the call's exact summary, details and destination,
 *       every time, whatever was granted, and waits. It is answered yes or no only.
 *  6.   After every wait, steps 1–3 run again on freshly read facts, and a grant the call
 *       relied on must still be in force.
 *  7.   [Authorization.AllowedByOwner] names the requests the owner answered; otherwise
 *       [Authorization.Allowed].
 *
 * Waiting for the owner:
 *  - Only the owner's UI decides a request ([com.aicontrolcenter.core.operator.OwnerRequests]);
 *    the broker holds [OwnerRequestQueue] and [GrantLookup], which raise, watch, close and use
 *    up the ONCE grant of its own request, and can approve or grant nothing.
 *  - The request is registered in [RequestWaiters] before it is stored and removed once it is
 *    closed, so the owner's port knows a live call waits for it; a request without one (left by
 *    an earlier run of the app) can never be answered.
 *  - The wait runs inside [WaitClock.pausedWhile], so it does not count against the time limits
 *    of the run, the task or the objective around the call. It ends at the request's expiry
 *    (`approvalTimeoutMinutes`, read when it is raised): the request is closed as EXPIRED, unless
 *    the owner answered at the last moment, whose answer is then used.
 *  - While it waits, the call's task shows WAITING_FOR_OWNER (changed only from RUNNING, never
 *    on an objective) and the run is told ([OwnerWaits]). Afterwards the task is RUNNING again
 *    and its stored deadline, and that of every delegating task waiting for it, is moved later
 *    by the time waited, so what is stored agrees with the paused limits (waits a call makes
 *    outside the broker are stored by [com.aicontrolcenter.framework.tools.ToolExecutor], which
 *    is told what the broker stored: [OwnerTimeAccount]). An objective's own
 *    deadline (the Director's calls while planning) is left to the orchestrator, whose running
 *    limit pauses and stores it as the wait goes on, also if the app is killed during the wait.
 *  - When the waiting coroutine is cancelled (the task paused or cancelled, the chat stopped),
 *    the request is closed as CANCELLED; either way an unused ONCE grant of the request is
 *    released ([GrantLookup.releaseOnceFor]).
 *  - The request, the decision and what the call does with it are logged
 *    ([ActivityType.PERMISSION] or [ActivityType.APPROVAL]).
 */
class PermissionBroker(
    private val facts: OperatorFacts,
    private val requests: OwnerRequestQueue,
    private val grants: GrantLookup,
    /** Where the broker says which of its requests a live call waits for. */
    private val waiters: RequestWaiters,
    /** Where a waiting task's status and deadline are kept; null where calls have no tasks. */
    private val tasks: TaskRepository?,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    /**
     * The one lock and the caps every request to the owner is raised under (Phase 6, rules 13 and
     * 14): shared with the capability requester and the question tool. Its own by default.
     */
    attention: OwnerAttentionGate = OwnerAttentionGate(requests, waiters, activity),
    /** Where the time calls waited for the owner is noted, for the bulk rule's window (D12); null: nowhere. */
    ownerWaits: OwnerWaitLedger? = null,
    /**
     * The tasks whose agent got the owner's answer to a question (rule 32): their next call that
     * may send information off the phone asks the owner first. Shared with the question tool.
     */
    private val answeredQuestions: AnsweredQuestions = AnsweredQuestions(),
) : ToolAuthorizer, EarlyRefusal {
    private val waiting = OwnerRequestWait(requests, grants, waiters, tasks, clock, attention, ownerWaits)

    override suspend fun authorize(tool: ToolSpec, assessment: ActionAssessment, context: ToolContext): Authorization {
        val call = Call(tool, assessment, context, currentAgentTurn()?.extraToolIds.orEmpty(), objectiveOptions(context))
        // Phase 6 (security review of wave 1, F12): a routine's objective uses other apps' screens only when the owner ticked it.
        if (!call.options.screenActionsAllowed && tool.capability in SCREEN_CAPABILITIES) {
            return Authorization.Denied(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED)
        }
        gate(call)?.let { return Authorization.Denied(it) }
        // The gate refuses a capability it does not know, so there is one.
        val info = facts.capabilityInfo(tool.capability) ?: return Authorization.Denied("\"${tool.capability.id}\" is not a permission this app knows.")
        val answered = ArrayList<RequestId>()
        var reliesOnGrant = false
        if (!info.grantedByDefault) {
            if (covered(call)) {
                reliesOnGrant = true
            } else {
                refusedRecently(call, info)?.let { return Authorization.Denied(it) }
                when (val asked = ask(call, OwnerRequestKind.PERMISSION, info)) {
                    is Asked.Refused -> return Authorization.Denied(asked.reason)
                    is Asked.Allowed -> {
                        answered += asked.requestId
                        reliesOnGrant = asked.lasting
                    }
                }
                gate(call)?.let { return Authorization.Denied(it) }
            }
        }
        // Phase 6 (D11, rule 15): what the call must hold from the owner's question to its end (the one shell-command
        // slot), taken after the permission is settled, so an agent without it is asked for the permission first and
        // learns nothing of other calls. Given back by the tool executor when the call ends, however it ends.
        call.assessment.prepared?.beforeOwnerAsked()?.let { return Authorization.Denied(it) }
        // Phase 6 (rule 32): the first call of a task that may send information off the phone after the owner answered
        // one of its questions asks the owner, whatever its risk, so nothing the owner wrote leaves unseen.
        val afterAnswer = if (tool.sendsDataOffDevice) answeredQuestions.flagged(context.taskId, tasks) else null
        if (call.risk == RiskLevel.CONSEQUENTIAL || afterAnswer != null) {
            when (val asked = ask(call, OwnerRequestKind.APPROVAL, info, afterAnswer = afterAnswer != null)) {
                is Asked.Refused -> return Authorization.Denied(asked.reason)
                is Asked.Allowed -> answered += asked.requestId
            }
            gate(call)?.let { return Authorization.Denied(it) }
            if (reliesOnGrant && !covered(call)) {
                return Authorization.Denied("The permission \"${info.title}\" was withdrawn while the owner was being asked to approve, so \"${tool.title}\" was not run.")
            }
            // The owner saw this call after their answer: later calls of the task go as usual.
            afterAnswer?.let { answeredQuestions.clear(it) }
        }
        return if (answered.isEmpty()) Authorization.Allowed else Authorization.AllowedByOwner(answered)
    }

    /**
     * A screen tool of an objective that may not use other apps' screens (a routine's without the
     * owner's tick, or one whose record cannot be read, F12) is refused before the tool looks at
     * anything, so not even the window in front is read for it. [authorize] refuses it again.
     */
    override suspend fun refusalBeforeAssess(spec: ToolSpec, context: ToolContext): String? =
        if (spec.capability in SCREEN_CAPABILITIES && !objectiveOptions(context).screenActionsAllowed) ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED else null

    /**
     * The options of the objective [context] belongs to, read once per call: a routine's objective
     * carries its screen choice and approval time-out (F12). Calls outside an objective (a chat)
     * get the defaults of an objective the owner started by hand. A call that names an objective
     * whose record cannot be read fails closed: it may not use other apps' screens.
     */
    private suspend fun objectiveOptions(context: ToolContext): ObjectiveOptions {
        val root = context.rootTaskId ?: return ObjectiveOptions()
        val record = tasks?.get(root) ?: return UNREADABLE_OBJECTIVE
        return record.options ?: ObjectiveOptions()
    }

    /** One call being decided. */
    private class Call(
        val tool: ToolSpec,
        val assessment: ActionAssessment,
        val context: ToolContext,
        val extraToolIds: Set<com.aicontrolcenter.core.ToolId>,
        val options: ObjectiveOptions,
    ) {
        /** An assessment can raise the risk of a call, never lower it below the tool's declared risk. */
        val risk: RiskLevel = maxOf(tool.risk, assessment.risk)
    }

    /** What came of asking the owner. */
    private sealed interface Asked {
        /** The owner said yes; [lasting]: through a TASK or ALWAYS grant, which must still hold after a later wait. */
        class Allowed(val requestId: RequestId, val lasting: Boolean) : Asked

        class Refused(val reason: String) : Asked
    }

    /** Steps 1–3 on facts read now. */
    private suspend fun gate(call: Call): String? =
        OperatorGate.refusal(facts.forCall(call.context.agent.id, call.tool, call.assessment, call.extraToolIds))

    private suspend fun covered(call: Call): Boolean =
        grants.findMatching(GrantQuery(call.context.agent.id, call.tool.capability, call.assessment.constraint, call.context.taskId, clock.now())).isNotEmpty()

    /**
     * Why [call] is refused without asking the owner: the owner refused this agent the same
     * permission and target less than [OwnerRequest.REFUSAL_COOLDOWN_MS] ago. The refusal counts
     * the repeat ([OwnerRequestQueue.countAskedAgain]) and it is logged. Null to go on and ask.
     */
    private suspend fun refusedRecently(call: Call, info: CapabilityInfo): String? {
        val now = clock.now()
        val agent = call.context.agent
        val refusal = requests.lastRefusal(agent.id, call.tool.capability, call.assessment.constraint, now - OwnerRequest.REFUSAL_COOLDOWN_MS) ?: return null
        val counted = safely { requests.countAskedAgain(refusal.id) } ?: refusal
        val reason = refusalCooldownReason(facts.agent(agent.id)?.name ?: agent.name, info.title, refusal, now) + " \"${call.tool.title}\" was not run."
        safely {
            activity.record(
                ActivityType.PERMISSION, "Not asked again: ${call.tool.title}", reason.take(MAX_LOGGED), severity = Severity.WARNING,
                agentId = agent.id, taskId = call.context.taskId, rootTaskId = call.context.rootTaskId,
                data = requestData(counted) { put("askedAgain", counted.askedAgain) },
            )
        }
        return reason
    }

    /**
     * Raises a [kind] request for [call], waits for its answer outside every time limit, and
     * uses it ([OwnerRequestWait]). Always leaves the request closed, an unused ONCE grant of it
     * released, and the task as it was before the wait (deadline moved by the wait), even when
     * cancelled. Refused without asking when too many requests of calls in progress wait for the
     * owner already (rule 14, [OwnerAttentionGate]): nothing is stored and the call does not run.
     */
    private suspend fun ask(call: Call, kind: OwnerRequestKind, info: CapabilityInfo, afterAnswer: Boolean = false): Asked {
        val request = newRequest(call, kind, info, afterAnswer)
        return waiting.ask(
            request, call.context, waitingDetail(request),
            admit = { live -> waiting.attention.callRequestRefusal(call.context, live) },
            raised = { logRaised(request) },
            use = { answer -> use(answer, call, info) },
            finished = { final, outcome -> logOutcome(final, outcome) },
            refused = { reason ->
                val what = if (kind == OwnerRequestKind.APPROVAL) "run \"${call.tool.title}\" (it needs your approval)" else "use \"${call.tool.title}\" (it needs the permission \"${info.title}\")"
                waiting.attention.tellOwnerOnce(call.context, what, reason)
                Asked.Refused(reason)
            },
        )
    }

    private suspend fun newRequest(call: Call, kind: OwnerRequestKind, info: CapabilityInfo, afterAnswer: Boolean = false): OwnerRequest {
        val context = call.context
        val now = clock.now()
        // A routine's objective may set its own time-out (F12); otherwise the owner's usual one.
        val minutes = call.options.approvalTimeoutMinutes?.coerceIn(1, RoutineLimits.MAX_APPROVAL_TIMEOUT_MINUTES)
            ?: facts.settings().approvalTimeoutMinutes.coerceAtLeast(1)
        val details = when (kind) {
            // The exact preview, as the tool assessed it; first, after the owner answered a question, why they are asked (rule 32).
            OwnerRequestKind.APPROVAL -> (if (afterAnswer) listOf(AFTER_ANSWER) else emptyList()) + call.assessment.details
            OwnerRequestKind.PERMISSION -> listOf("Needs the permission \"${info.title}\": ${info.description}") + call.assessment.details
            // Phase 6 (WP1a): the broker never raises a question; questions are raised by the question tool (WP5).
            OwnerRequestKind.QUESTION -> error("The permission broker does not raise questions.")
        }
        return OwnerRequest(
            id = ids.requestId(), kind = kind, agentId = context.agent.id, toolId = call.tool.id,
            capability = call.tool.capability, constraint = call.assessment.constraint, risk = call.risk,
            summary = call.assessment.summary, details = details, dataGoesTo = call.assessment.dataGoesTo,
            taskId = context.taskId, rootId = context.rootTaskId, conversationId = context.conversationId,
            createdAt = now, expiresAt = now + minutes * MINUTE_MS,
            agentName = facts.agent(context.agent.id)?.name ?: context.agent.name, toolTitle = call.tool.title,
            targetLabel = facts.targetLabel(call.assessment.constraint), delegationChain = context.delegationChain,
        )
    }

    /** What the closed request [answer] allows [call] to do. */
    private suspend fun use(answer: OwnerRequest, call: Call, info: CapabilityInfo): Asked {
        val title = call.tool.title
        val now = clock.now()
        return when (answer.status) {
            OwnerRequestStatus.APPROVED -> when {
                answer.kind == OwnerRequestKind.APPROVAL -> Asked.Allowed(answer.id, lasting = false)
                answer.decisionScope == GrantScope.ONCE ->
                    if (grants.consumeOnceFor(answer.id, now) != null) Asked.Allowed(answer.id, lasting = false)
                    else Asked.Refused("The owner's permission for this one call was withdrawn before it could be used, so \"$title\" was not run.")
                grants.findByRequest(answer.id)?.isActiveAt(now) == true -> Asked.Allowed(answer.id, lasting = true)
                else -> Asked.Refused("The permission the owner gave for \"$title\" was withdrawn before it could be used, so it was not run.")
            }
            OwnerRequestStatus.DENIED -> Asked.Refused(
                (if (answer.kind == OwnerRequestKind.APPROVAL) "The owner did not approve this action, so it was not run: ${answer.summary}"
                else "The owner did not give ${answer.agentName ?: "this agent"} the permission \"${info.title}\", so \"$title\" was not run.") +
                    ownersNote(answer.decisionNote),
            )
            OwnerRequestStatus.EXPIRED ->
                Asked.Refused("The owner did not answer within ${minutesOf(answer)} minutes, so \"$title\" was not run. Ask again only if it is still needed.")
            OwnerRequestStatus.CANCELLED, OwnerRequestStatus.PENDING ->
                Asked.Refused("The question to the owner was withdrawn (${answer.decisionNote ?: CANCELLED_NOTE}), so \"$title\" was not run.")
        }
    }

    /** The owner's words, passed to the model as quoted data. */
    private fun ownersNote(note: String?): String =
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { " The owner's note, quoted: \"${it.take(MAX_NOTE)}\"" }.orEmpty()

    private fun waitingDetail(request: OwnerRequest): String {
        val what = if (request.kind == OwnerRequestKind.APPROVAL) "approval" else "permission"
        return "Waiting for the owner's $what: ${request.summary}".let { if (it.length <= MAX_DETAIL) it else it.take(MAX_DETAIL - 1) + "…" }
    }

    // ---- the log ----

    private suspend fun logRaised(request: OwnerRequest) = log(
        request, "${if (request.kind == OwnerRequestKind.APPROVAL) "Approval" else "Permission"} asked: ${request.toolTitle}", request.summary, Severity.INFO,
    ) {
        put("risk", request.risk.name)
        put("expiresAt", request.expiresAt)
        request.dataGoesTo?.let { put("dataGoesTo", it) }
    }

    private suspend fun logOutcome(request: OwnerRequest, outcome: Asked?) {
        val tool = request.toolTitle
        val title = when (request.status) {
            OwnerRequestStatus.APPROVED -> if (request.kind == OwnerRequestKind.APPROVAL) "Approved by the owner: $tool" else "Permission given ${scopeWords(request.decisionScope)}: $tool"
            OwnerRequestStatus.DENIED -> "Refused by the owner: $tool"
            OwnerRequestStatus.EXPIRED -> "No answer in time: $tool"
            OwnerRequestStatus.CANCELLED, OwnerRequestStatus.PENDING -> "Request withdrawn: $tool"
        }
        val allowed = outcome is Asked.Allowed
        val detail = when (outcome) {
            is Asked.Refused -> outcome.reason
            is Asked.Allowed -> "The call goes on: ${request.summary}"
            null -> request.decisionNote ?: CANCELLED_NOTE
        }
        log(request, title, detail, if (allowed) Severity.INFO else Severity.WARNING) {
            put("status", request.status.name)
            request.decisionScope?.let { put("scope", it.name) }
            put("callAllowed", allowed)
        }
    }

    private suspend fun log(
        request: OwnerRequest,
        title: String,
        detail: String,
        severity: Severity,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        safely {
            activity.record(
                if (request.kind == OwnerRequestKind.APPROVAL) ActivityType.APPROVAL else ActivityType.PERMISSION,
                title, detail.take(MAX_LOGGED), severity = severity, agentId = request.agentId, taskId = request.taskId, rootTaskId = request.rootId,
                data = buildJsonObject {
                    put("requestId", request.id.value)
                    put("kind", request.kind.name)
                    request.toolId?.let { put("toolId", it.value) }
                    put("capability", request.capability.id)
                    put("constraint", request.constraint.stored)
                    request.conversationId?.let { put("conversationId", it.value) }
                    extra()
                },
            )
        }
    }

    private fun minutesOf(request: OwnerRequest): Long = ((request.expiresAt - request.createdAt) / MINUTE_MS).coerceAtLeast(1)

    /** Bookkeeping on the way out never changes what happened: a store or log that fails is passed over. */
    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        rethrowIfCancelled(e)
        null
    }

    /** Texts and limits. */
    companion object {
        /** Why a request was withdrawn when its call went away before the owner answered. */
        const val CANCELLED_NOTE = "the call that asked was stopped before the owner answered"

        /**
         * The first line of an approval asked because the owner answered a question of the call's task
         * (rule 32): the call may send information off the phone, and the owner's answer may be in it.
         */
        const val AFTER_ANSWER =
            "You are asked because this is the first action after you answered a question that may send information off " +
                "this phone. Check that nothing you wrote in your answer goes where it should not."

        private const val MINUTE_MS = 60_000L

        /** Reading and acting on other apps' screens: what a routine's objective may do only when the owner ticked it (F12). */
        private val SCREEN_CAPABILITIES = setOf(OperatorCapabilities.SCREEN_READ, OperatorCapabilities.SCREEN_OPERATE)

        /** The options of a call whose objective's record cannot be read: no screens (F12, fail closed). */
        private val UNREADABLE_OBJECTIVE = ObjectiveOptions(screenActionsAllowed = false)
        private const val MAX_NOTE = 500
        private const val MAX_DETAIL = 300
        private const val MAX_LOGGED = 600
    }
}

/**
 * What the model is told when it asks again, too soon, for a permission the owner refused: when
 * it was refused, how long until it may be asked again, and what to do meanwhile. Shared by the
 * broker and the capability requester.
 */
internal fun refusalCooldownReason(agentName: String, permission: String, refusal: OwnerRequest, now: Long): String {
    val decided = refusal.decidedAt ?: now
    val ago = ((now - decided) / 60_000L).coerceAtLeast(0)
    val left = ((decided + OwnerRequest.REFUSAL_COOLDOWN_MS - now + 59_999L) / 60_000L).coerceAtLeast(1)
    val whenRefused = if (ago < 1) "less than a minute ago" else "$ago minute${if (ago == 1L) "" else "s"} ago"
    return "The owner refused $agentName the permission \"$permission\" $whenRefused, so the owner is not asked again for " +
        "$left more minute${if (left == 1L) "" else "s"}. Carry on without it, or say in your answer what you need it for. Only the " +
        "owner can end this wait sooner: in the app under Tools, History, on the refused request, with \"Let it ask again\"."
}
