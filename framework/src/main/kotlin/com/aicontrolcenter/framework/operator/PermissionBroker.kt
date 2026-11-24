package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.operator.GrantLookup
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorGate
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.requestId
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRepository
import com.aicontrolcenter.core.tasks.TaskStatus
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
import com.aicontrolcenter.framework.tools.OwnerTimeAccount
import com.aicontrolcenter.framework.tools.StoredDeadlines
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
) : ToolAuthorizer {

    override suspend fun authorize(tool: ToolSpec, assessment: ActionAssessment, context: ToolContext): Authorization {
        val call = Call(tool, assessment, context, currentAgentTurn()?.extraToolIds.orEmpty())
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
        if (call.risk == RiskLevel.CONSEQUENTIAL) {
            when (val asked = ask(call, OwnerRequestKind.APPROVAL, info)) {
                is Asked.Refused -> return Authorization.Denied(asked.reason)
                is Asked.Allowed -> answered += asked.requestId
            }
            gate(call)?.let { return Authorization.Denied(it) }
            if (reliesOnGrant && !covered(call)) {
                return Authorization.Denied("The permission \"${info.title}\" was withdrawn while the owner was being asked to approve, so \"${tool.title}\" was not run.")
            }
        }
        return if (answered.isEmpty()) Authorization.Allowed else Authorization.AllowedByOwner(answered)
    }

    /** One call being decided. */
    private class Call(val tool: ToolSpec, val assessment: ActionAssessment, val context: ToolContext, val extraToolIds: Set<com.aicontrolcenter.core.ToolId>) {
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
     * uses it. Always leaves the request closed, an unused ONCE grant of it released, and the
     * task as it was before the wait (deadline moved by the wait), even when cancelled.
     */
    private suspend fun ask(call: Call, kind: OwnerRequestKind, info: CapabilityInfo): Asked {
        val request = newRequest(call, kind, info)
        val waits = OwnerWaits.current()
        // Registered first, so the owner never sees this request pending without a live waiter.
        waiters.register(request.id)
        try {
            requests.create(request)
        } catch (e: Exception) {
            waiters.unregister(request.id)
            throw e
        }
        val startedAt = clock.now()
        // The wait as the paused limits count it, for whoever accounts for this call's paused time (the tool executor).
        val waitClock = WaitClock.current()
        val pausedBefore = waitClock?.pausedMs() ?: 0L
        var waitingTask: WaitingTask? = null
        var ended: OwnerRequest? = null
        var outcome: Asked? = null
        try {
            logRaised(request)
            waitingTask = markWaiting(call.context.taskId, request)
            waits?.waiting(request)
            val answer = WaitClock.pausedWhile { answerOf(request) }
            ended = answer
            return use(answer, call, info).also { outcome = it }
        } finally {
            withContext(NonCancellable) {
                val at = clock.now()
                val final = ended ?: safely { requests.close(request.id, OwnerRequestStatus.CANCELLED, at, CANCELLED_NOTE) }
                    ?: safely { requests.get(request.id) } ?: request
                safely { grants.releaseOnceFor(request.id, at) }
                waiters.unregister(request.id)
                val pausedMs = ((waitClock?.pausedMs() ?: 0L) - pausedBefore).coerceAtLeast(0)
                waitingTask?.let { safely { resume(it, (at - startedAt).coerceAtLeast(0), pausedMs, at) } }
                logOutcome(final, outcome)
                waits?.let { safely { it.answered(final) } }
            }
        }
    }

    private suspend fun newRequest(call: Call, kind: OwnerRequestKind, info: CapabilityInfo): OwnerRequest {
        val context = call.context
        val now = clock.now()
        val minutes = facts.settings().approvalTimeoutMinutes.coerceAtLeast(1)
        val details = when (kind) {
            // The exact preview, as the tool assessed it.
            OwnerRequestKind.APPROVAL -> call.assessment.details
            OwnerRequestKind.PERMISSION -> listOf("Needs the permission \"${info.title}\": ${info.description}") + call.assessment.details
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

    // ---- the waiting task ----

    private class WaitingTask(val id: TaskId, val flipped: Boolean, val detailBefore: String?)

    /** Shows the call's task as WAITING_FOR_OWNER when it is RUNNING (never an objective, whose status the orchestrator keeps). */
    private suspend fun markWaiting(taskId: TaskId?, request: OwnerRequest): WaitingTask? {
        val store = tasks ?: return null
        taskId ?: return null
        var flipped = false
        var before: String? = null
        return try {
            store.update(taskId) { task ->
                flipped = task.kind != TaskKind.OBJECTIVE && task.status == TaskStatus.RUNNING
                before = task.statusDetail
                if (!flipped) task else task.copy(status = TaskStatus.WAITING_FOR_OWNER, statusDetail = waitingDetail(request), updatedAt = clock.now())
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

    private fun OwnerRequest.closed(status: OwnerRequestStatus, note: String) = copy(status = status, decidedAt = clock.now(), decisionNote = note)

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

        private const val MINUTE_MS = 60_000L
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
