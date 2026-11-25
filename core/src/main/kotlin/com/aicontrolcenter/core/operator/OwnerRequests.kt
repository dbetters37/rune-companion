package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** What the owner is asked. Stored by name. */
enum class OwnerRequestKind {
    /** May this agent use this capability? Answered with a [GrantScope] or a refusal. */
    PERMISSION,

    /** May this one consequential action run? Answered yes or no; never "always". */
    APPROVAL,

    /**
     * An agent's question to the owner (Phase 6, C1, Tier B; rule 13). Answered with text or
     * "decide yourself" ([OwnerDecision.Answer]); never a permission: it carries no scope, writes
     * no grant, never counts as a refusal and settles nothing else (rule 33). Appended last:
     * stored by name.
     */
    QUESTION,
}

/** Where a request is. Only PENDING can change, and only once. Stored by name. */
enum class OwnerRequestStatus {
    PENDING,
    APPROVED,
    DENIED,

    /** Nobody answered before [OwnerRequest.expiresAt]; the call was refused. */
    EXPIRED,

    /** The waiting call went away (pause, cancel, chat stopped, app restart). */
    CANCELLED,
    ;

    val isPending: Boolean get() = this == PENDING
}

/**
 * A question to the owner raised by the permission broker for one tool call, or by an agent
 * asking for a capability ([CapabilityRequester]). Its text fields are shown as written:
 * [summary] and [details] come from the tool's assessment, with every model-supplied value
 * passed through [PreviewText.exact]; [reason] is the agent's own words and is shown quoted, as
 * data. Previews are never cut short: the owner scrolls.
 *
 * Invariants (checked on construction): only a PERMISSION carries a [decisionScope], and an
 * approved PERMISSION always does; a pending request carries no decision. A QUESTION (Phase 6,
 * K3) and only a QUESTION carries [question]; it is SAFE, has no constraint, holds at most
 * [QuestionRules.MAX_OPTIONS] [options] of at most [QuestionRules.MAX_OPTION_CHARS] characters,
 * none of them using an approval word ([QuestionRules.approvalWordIn]), and carries an [answer]
 * only once APPROVED (answered); an answered QUESTION without an [answer] means "decide yourself".
 */
@Serializable
data class OwnerRequest(
    val id: RequestId,
    val kind: OwnerRequestKind,
    val status: OwnerRequestStatus = OwnerRequestStatus.PENDING,
    val agentId: AgentId,
    /** The tool whose call raised it; null when the agent asked for the capability itself. */
    val toolId: ToolId? = null,
    val capability: Capability,
    val constraint: CapabilityConstraint = CapabilityConstraint.None,
    /** The call's risk: the higher of the tool's declared and assessed risk. */
    val risk: RiskLevel,
    /** One sentence naming exactly what would happen, e.g. "Delete Reports/2024.csv in Documents". */
    val summary: String,
    val details: List<String> = emptyList(),
    /** Where information goes if it leaves this app (a web host, another app); null if it stays. */
    val dataGoesTo: String? = null,
    /** The agent's reason, verbatim. Untrusted text: shown quoted, never interpreted. */
    val reason: String? = null,
    val taskId: TaskId? = null,
    val rootId: TaskId? = null,
    val conversationId: ConversationId? = null,
    val createdAt: Long,
    /** After this time (epoch ms) the request can no longer be approved. */
    val expiresAt: Long,
    /** When it left PENDING, whatever the outcome. */
    val decidedAt: Long? = null,
    /** PERMISSION approved: the scope the owner chose. Otherwise null. */
    val decisionScope: GrantScope? = null,
    /** The owner's note, or why it expired or was cancelled. */
    val decisionNote: String? = null,
    /** The agent's name when the request was raised, so history reads right after a rename or deletion. */
    val agentName: String? = null,
    /** The tool's title when the request was raised; null for an agent's own ask. */
    val toolTitle: String? = null,
    /** The folder's name or the app's label when the request was raised; null without a constraint. */
    val targetLabel: String? = null,
    /** Agents that delegated down to the asking agent, outermost first. */
    val delegationChain: List<AgentId> = emptyList(),
    /**
     * DENIED only: how many times the same agent asked again for the same permission within
     * [REFUSAL_COOLDOWN_MS] of this refusal. Those asks were refused without asking the owner;
     * the history shows the count here instead.
     */
    val askedAgain: Int = 0,
    /**
     * A refused PERMISSION request only: when the owner let the agent ask again
     * ([OwnerRequests.letAskAgain]). From then on this refusal holds nothing back: the agent's
     * next call or ask for the permission is put to the owner as usual. Null otherwise.
     */
    val askAgainAt: Long? = null,
    /**
     * QUESTION only: the agent's question, verbatim (untrusted text: shown quoted, never
     * interpreted), 1 to [QuestionRules.MAX_QUESTION_CHARS] characters. Null for every other kind.
     */
    val question: String? = null,
    /**
     * QUESTION only: answers the agent suggests, shown as quoted agent text in a list ("Use this
     * answer"), never as buttons that look like approvals. Empty for every other kind.
     */
    val options: List<String> = emptyList(),
    /**
     * QUESTION only, once answered (APPROVED): the owner's words, at most
     * [QuestionRules.MAX_ANSWER_CHARS] characters; null on an answered question means the owner
     * chose "decide yourself".
     */
    val answer: String? = null,
) {
    init {
        require(expiresAt > createdAt) { "A request must expire after it was created." }
        require(kind == OwnerRequestKind.PERMISSION || decisionScope == null) { "Only a PERMISSION request carries a scope." }
        require(status == OwnerRequestStatus.APPROVED || decisionScope == null) { "Only an approved request carries a scope." }
        require(!(kind == OwnerRequestKind.PERMISSION && status == OwnerRequestStatus.APPROVED) || decisionScope != null) {
            "An approved PERMISSION request carries the scope the owner chose."
        }
        require(status != OwnerRequestStatus.PENDING || (decidedAt == null && decisionNote == null)) { "A pending request has no decision." }
        require(status == OwnerRequestStatus.PENDING || decidedAt != null) { "A closed request records when it was closed." }
        require(askedAgain >= 0) { "askedAgain cannot be negative." }
        require(askedAgain == 0 || status == OwnerRequestStatus.DENIED) { "Only a refused request counts the asks that followed it." }
        require(askAgainAt == null || (status == OwnerRequestStatus.DENIED && kind == OwnerRequestKind.PERMISSION)) {
            "Only a refused permission request can be opened for asking again."
        }
        require((kind == OwnerRequestKind.QUESTION) == (question != null)) { "A QUESTION always carries its question, and no other request does." }
        require(kind == OwnerRequestKind.QUESTION || (options.isEmpty() && answer == null)) { "Only a QUESTION carries options or an answer." }
        if (kind == OwnerRequestKind.QUESTION) {
            QuestionRules.questionProblem(question!!, options)?.let { throw IllegalArgumentException(it) }
            require(risk == RiskLevel.SAFE) { "A question is SAFE: it can do nothing." }
            require(constraint == CapabilityConstraint.None) { "A question has no folder or app." }
            require(answer == null || status == OwnerRequestStatus.APPROVED) { "Only an answered question carries an answer." }
            require(answer == null || (answer.isNotBlank() && answer.length <= QuestionRules.MAX_ANSWER_CHARS)) {
                "An answer is 1 to ${QuestionRules.MAX_ANSWER_CHARS} characters."
            }
            require(status != OwnerRequestStatus.DENIED) { "A question is answered, expires or is cancelled; it is never refused." }
        }
    }

    /** True for an agent's question to the owner (Phase 6). */
    val isQuestion: Boolean get() = kind == OwnerRequestKind.QUESTION

    /**
     * True while this refusal holds the agent back at [now]: a refused PERMISSION request, less
     * than [REFUSAL_COOLDOWN_MS] old, that the owner has not opened for asking again. While it
     * does, the same agent's calls and asks for the same permission and target are refused
     * without a new request ([OwnerRequestQueue.lastRefusal]).
     */
    fun holdsBackAt(now: Long): Boolean =
        kind == OwnerRequestKind.PERMISSION && status == OwnerRequestStatus.DENIED && askAgainAt == null &&
            decidedAt != null && now < decidedAt + REFUSAL_COOLDOWN_MS

    /** The scopes the owner may choose from: none for an APPROVAL or a QUESTION, and TASK only when the call belongs to a task. */
    val offeredScopes: List<GrantScope>
        get() = when (kind) {
            OwnerRequestKind.APPROVAL, OwnerRequestKind.QUESTION -> emptyList()
            OwnerRequestKind.PERMISSION -> listOfNotNull(GrantScope.ONCE, GrantScope.TASK.takeIf { taskId != null }, GrantScope.ALWAYS)
        }

    fun isExpiredAt(at: Long): Boolean = at >= expiresAt

    /**
     * Why [decision] cannot answer this request (wrong kind, a scope that is not offered, an
     * answer that is blank or too long), or null if it can. A QUESTION takes only an
     * [OwnerDecision.Answer], and only a QUESTION takes one.
     */
    fun problemWith(decision: OwnerDecision): String? = when (decision) {
        is OwnerDecision.Deny ->
            if (kind == OwnerRequestKind.QUESTION) QUESTION_ANSWERED_WITH_TEXT else null
        is OwnerDecision.Approve -> when (kind) {
            OwnerRequestKind.APPROVAL -> null
            OwnerRequestKind.PERMISSION -> "A permission request is answered with a scope, not a plain approval."
            OwnerRequestKind.QUESTION -> QUESTION_ANSWERED_WITH_TEXT
        }
        is OwnerDecision.Grant -> when {
            kind == OwnerRequestKind.QUESTION -> QUESTION_ANSWERED_WITH_TEXT
            kind != OwnerRequestKind.PERMISSION -> "An approval request is answered yes or no; it cannot be granted for longer."
            decision.scope !in offeredScopes -> "\"${decision.scope}\" is not offered for this request."
            else -> null
        }
        is OwnerDecision.Answer -> when {
            kind != OwnerRequestKind.QUESTION -> "Only an agent's question is answered with text."
            decision.text != null && decision.text.isBlank() -> "Write an answer, or choose \"Decide yourself\"."
            decision.text != null && decision.text.length > QuestionRules.MAX_ANSWER_CHARS ->
                "An answer can be at most ${QuestionRules.MAX_ANSWER_CHARS} characters long."
            else -> null
        }
    }

    /**
     * The grant an approval of this PERMISSION request with [scope] creates: same agent,
     * capability and constraint; the request's task for TASK and ONCE, none for ALWAYS; ONCE
     * void after [PermissionGrant.ONCE_LIFETIME_MS]. The one place these rules are written.
     */
    fun grantFor(scope: GrantScope, id: GrantId, at: Long): PermissionGrant {
        require(kind == OwnerRequestKind.PERMISSION) { "Only a permission request creates a grant." }
        require(scope in offeredScopes) { "\"$scope\" is not offered for this request." }
        return PermissionGrant(
            id = id, agentId = agentId, capability = capability, constraint = constraint, scope = scope,
            taskId = if (scope == GrantScope.ALWAYS) null else taskId, requestId = this.id, grantedAt = at,
            expiresAt = if (scope == GrantScope.ONCE) at + PermissionGrant.ONCE_LIFETIME_MS else null,
        )
    }

    /** Limits. */
    companion object {
        /**
         * After the owner refuses a PERMISSION request, the same agent asking again for the same
         * capability and target within this time is refused at once, without a new request: the
         * owner is not asked the same question over and over ([OwnerRequestQueue.lastRefusal]).
         * The owner can end it sooner on the refused request ([OwnerRequests.letAskAgain]).
         */
        const val REFUSAL_COOLDOWN_MS: Long = 10 * 60_000L

        private const val QUESTION_ANSWERED_WITH_TEXT =
            "A question is answered with your own words or \"Decide yourself\"; it gives and refuses no permission."
    }
}

/**
 * The owner's answer. Shaped so an APPROVAL cannot be answered with a scope: [Approve] has none,
 * and [Grant], the only answer that carries one, answers PERMISSION requests only
 * ([OwnerRequest.problemWith]; [OwnerRequest] refuses to hold a scope on an APPROVAL).
 */
sealed interface OwnerDecision {
    /** The owner's optional words, passed to the agent as data. */
    val note: String?

    /** An answer to a PERMISSION request. */
    sealed interface ForPermission : OwnerDecision

    /** An answer to an APPROVAL request. */
    sealed interface ForApproval : OwnerDecision

    /** An answer to a QUESTION (Phase 6). */
    sealed interface ForQuestion : OwnerDecision

    /** Yes to a PERMISSION request, for [scope]. */
    data class Grant(val scope: GrantScope, override val note: String? = null) : ForPermission

    /** Yes to an APPROVAL request: this one action, this one time. */
    data class Approve(override val note: String? = null) : ForApproval

    /** No, to a PERMISSION or an APPROVAL request. A QUESTION is never refused: it is answered. */
    data class Deny(override val note: String? = null) : ForPermission, ForApproval

    /**
     * The owner's answer to a QUESTION (Phase 6, K3): [text] in their own words, or null for
     * "decide yourself". It closes the question as APPROVED with [OwnerRequest.answer] = [text];
     * it writes no grant and answers no other request (rule 33). Its [note] is always null: the
     * answer is the text.
     */
    data class Answer(val text: String?) : ForQuestion {
        override val note: String? get() = null
    }
}

/** What happened to one attempt to answer a request. */
sealed interface DecideResult {
    /**
     * This answer won: the request is now APPROVED or DENIED as [request] shows. [grant] is the
     * grant written in the same transaction when a PERMISSION request was granted, else null.
     */
    data class Decided(val request: OwnerRequest, val grant: PermissionGrant? = null) : DecideResult

    /** Someone answered, it expired or it was cancelled first; [request] is its current state. */
    data class AlreadyClosed(val request: OwnerRequest) : DecideResult

    /** The answer came after [OwnerRequest.expiresAt]; this call closed the request as EXPIRED. */
    data class Expired(val request: OwnerRequest) : DecideResult

    /** [decision] does not fit the request ([OwnerRequest.problemWith]); nothing changed. */
    data class NotAccepted(val request: OwnerRequest, val problem: String) : DecideResult

    /** No request has that id. */
    data object NotFound : DecideResult
}

/**
 * The permission broker's side of owner requests: raise one, watch it, and close it when its
 * waiter goes away or it runs out of time. Nothing here can approve. Handed to the broker and
 * the [CapabilityRequester] instead of [OwnerRequestRepository].
 */
interface OwnerRequestQueue {
    /** Stores a new PENDING request. @throws IllegalArgumentException if it is not pending or its id is taken. */
    suspend fun create(request: OwnerRequest)

    suspend fun get(id: RequestId): OwnerRequest?

    /** The request as it changes; null while it does not exist. */
    fun observe(id: RequestId): Flow<OwnerRequest?>

    /** Every PENDING request, expired or not, oldest first (created time, then id). */
    fun observePending(): Flow<List<OwnerRequest>>

    /**
     * Closes a PENDING request as [status] (EXPIRED or CANCELLED) with [note]. Returns the closed
     * request if this call closed it, null if it was missing or already closed.
     * @throws IllegalArgumentException for any other [status] (checked first).
     */
    suspend fun close(id: RequestId, status: OwnerRequestStatus, at: Long, note: String?): OwnerRequest?

    /** Closes every PENDING request with expiresAt ≤ [at] as EXPIRED. Returns those it closed, oldest first. */
    suspend fun closeOverdue(at: Long, note: String?): List<OwnerRequest>

    /**
     * Closes every PENDING request as CANCELLED. Returns those it closed, oldest first. The app's
     * start-up does not use it (since review 5 it closes one by one only the requests no live
     * call waits for, so work restarted before start-up finished keeps its question); it is for
     * a store known to have no waiters at all.
     */
    suspend fun cancelAllPending(at: Long, note: String?): List<OwnerRequest>

    /**
     * Closes as CANCELLED every PENDING agent ask (toolId null) of a task that reached a finished
     * status, since nothing will use the answer. Returns those it closed, oldest first.
     */
    suspend fun cancelAsksForTask(taskId: TaskId, at: Long, note: String?): List<OwnerRequest>

    /**
     * The newest PERMISSION request of [agentId] for [capability] and [constraint] that the owner
     * DENIED at or after [since] (its decidedAt) and has not opened for asking again
     * ([OwnerRequest.askAgainAt] is null), or null. Used to refuse asking the owner the same
     * question again within [OwnerRequest.REFUSAL_COOLDOWN_MS] of a refusal.
     */
    suspend fun lastRefusal(agentId: AgentId, capability: Capability, constraint: CapabilityConstraint, since: Long): OwnerRequest?

    /**
     * Adds one to [OwnerRequest.askedAgain] of a DENIED request: the agent asked again within the
     * cool-down and was refused without asking the owner. Returns the updated request, or null
     * when it is missing or not DENIED. Room does it as one conditional `UPDATE`.
     */
    suspend fun countAskedAgain(id: RequestId): OwnerRequest?
}

/** The owner's answer, written. Handed only to the [OwnerRequests] implementation. */
interface OwnerRequestDecisions {
    /**
     * Answers a PENDING request, atomically. In order: missing → [DecideResult.NotFound]; not
     * pending → [DecideResult.AlreadyClosed]; [decision] does not fit → [DecideResult.NotAccepted]
     * (nothing changes); [at] ≥ expiresAt → closed as EXPIRED (decidedAt = [at], no note),
     * [DecideResult.Expired]; otherwise APPROVED (with the grant's scope for PERMISSION) or
     * DENIED, with decidedAt = [at] and the decision's note, [DecideResult.Decided].
     *
     * A [OwnerDecision.Grant] also stores `request.grantFor(scope, grantId, at)` in the same
     * transaction, so whoever sees the request APPROVED finds its grant
     * ([GrantLookup.findByRequest]). [grantId] is used for nothing else.
     *
     * An [OwnerDecision.Answer] (QUESTION only, Phase 6) closes the question as APPROVED with
     * [OwnerRequest.answer] = its text (null: "decide yourself"), decidedAt = [at] and no note,
     * through the same compare-and-set; it writes no grant, so [DecideResult.Decided.grant] is null.
     */
    suspend fun decide(id: RequestId, decision: OwnerDecision, at: Long, grantId: GrantId): DecideResult

    /**
     * The owner lets the agent ask again after refusing the PERMISSION request [id]: sets
     * [OwnerRequest.askAgainAt] to [at] on it and on every other refused PERMISSION request of
     * the same agent, capability and target that carries none yet, atomically, so that
     * [OwnerRequestQueue.lastRefusal] finds none of them and the cool-down is over for that
     * agent, permission and target. A request already opened keeps its first time. Returns the
     * request as it is now; null, changing nothing, when it is missing or is not a refused
     * PERMISSION request. It gives no permission: the next call or ask raises a normal request.
     */
    suspend fun letAskAgain(id: RequestId, at: Long): OwnerRequest?
}

/**
 * Storage of owner requests: both sides, plus the lists the owner's screens show. Every state
 * change is a compare-and-set from PENDING, so a request is decided, expired or cancelled at
 * most once however many callers race; Room does each as one conditional
 * `UPDATE … WHERE id=? AND status='PENDING'` with a row-count check. Implementations: Room
 * (`:data`, which writes the grant of [decide] in the same database transaction) and in-memory
 * (`:testkit`); both pass `OwnerRequestRepositoryContract`.
 *
 * Orders: pending and per-chat/per-objective lists oldest first (created time, then id);
 * [observeRecent] newest first (created time, then id, both descending). Flows emit at once and
 * after every change.
 *
 * Dependency injection hands the broker [OwnerRequestQueue] and the [OwnerRequests]
 * implementation this interface; tools and agents get neither.
 */
interface OwnerRequestRepository : OwnerRequestQueue, OwnerRequestDecisions {
    /** The newest [limit] requests of any status, for the history. */
    fun observeRecent(limit: Int): Flow<List<OwnerRequest>>

    /** Requests raised in one chat, any status, to show inline there. */
    fun observeForConversation(conversationId: ConversationId): Flow<List<OwnerRequest>>

    /** Requests raised under one objective, any status. */
    fun observeForObjective(rootId: TaskId): Flow<List<OwnerRequest>>
}
