package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.ToolContext
import kotlinx.coroutines.flow.Flow

/*
 * Owner-only ports. How the separation from agents is enforced:
 * - A tool sees only its JSON arguments and a ToolContext, which holds no services. Its
 *   dependencies are chosen in the composition root. Nothing reachable from ToolExecutor, the
 *   permission broker or any tool may depend on OwnerRequests, OwnerGrants, OwnerFolders,
 *   OwnerOperatorApps, ScreenControl, OwnerRequestDecisions, GrantStore or the full
 *   repositories; an :app architecture test checks it. The broker holds OwnerRequestQueue and
 *   GrantLookup, which can raise, wait, use up the ONCE grant of its own request and narrow,
 *   never approve or grant. permissions.request holds only CapabilityRequester.
 * - Answers are never read from model output, tool results or messages; only these ports
 *   decide, and only a person tapping in the app calls them. The "Approval needed"
 *   notification only opens the app.
 * - The screen operator refuses to act on this app's windows (ProtectedPackages), so an agent
 *   cannot tap "Approve" either.
 */

/** The outcome of an owner action that can be refused. */
sealed interface OwnerResult<out T> {
    /** Done; [value] is what changed. */
    data class Done<T>(val value: T) : OwnerResult<T>

    /** Not done; [reason] is a sentence for the owner. */
    data class Refused(val reason: String) : OwnerResult<Nothing>
}

/**
 * How the UI sees and answers requests. Implemented in `:framework` over
 * [OwnerRequestRepository] (answering logs the decision); `:app` hands it to view models only.
 */
interface OwnerRequests {
    /**
     * Requests waiting for an answer, oldest first, without those already past their expiry and
     * those nothing waits for any more (see [closeIfAbandoned]).
     */
    val pending: Flow<List<OwnerRequest>>

    /** The newest [limit] requests of any status. */
    fun observeRecent(limit: Int): Flow<List<OwnerRequest>>

    /** Requests raised in one chat, oldest first, to show inline there. */
    fun observeForConversation(conversationId: ConversationId): Flow<List<OwnerRequest>>

    suspend fun get(id: RequestId): OwnerRequest?

    /**
     * The request as it is now, closed first if nothing waits for its answer any more: a
     * PENDING request whose waiting call is gone (it stopped with an earlier run of the app, or
     * went away without closing it) can never be answered, so it is closed as CANCELLED and
     * returned closed. Screens call this before offering the answers; answering such a request
     * closes it the same way. Null when no request has this id.
     */
    suspend fun closeIfAbandoned(id: RequestId): OwnerRequest? = get(id)

    /** Yes to a PERMISSION request for [scope]; the grant is stored in the same transaction ([OwnerRequestDecisions.decide]). */
    suspend fun grant(id: RequestId, scope: GrantScope, note: String? = null): DecideResult

    /** Yes to an APPROVAL request: this action, once. There is no scope to give. */
    suspend fun approve(id: RequestId, note: String? = null): DecideResult

    /** No, to either kind. */
    suspend fun deny(id: RequestId, note: String? = null): DecideResult

    /**
     * The owner changes their mind after refusing the PERMISSION request [id]: ends the cool-down
     * ([OwnerRequest.REFUSAL_COOLDOWN_MS]) for that agent, permission and target at once, so the
     * agent's next call or `permissions.request` raises a normal request instead of being refused
     * unasked. It gives no permission and answers nothing. Refused, with the reason, when the
     * request is missing or is not a permission request the owner refused. Logged. Like every
     * method here it is the owner's alone: nothing an agent can reach calls it.
     */
    suspend fun letAskAgain(id: RequestId): OwnerResult<OwnerRequest>

    /**
     * The owner answers an agent's QUESTION (Phase 6, K3, Tier B): [text] in their own words, or
     * null for "decide yourself". Refused, with a sentence for the owner, when the request is
     * missing, not a pending question, no longer waited for ([closeIfAbandoned]), expired, or the
     * text is blank or too long ([QuestionRules.MAX_ANSWER_CHARS]); when [text] looks like a key
     * ([AnswerCheck], [AnswerRisk.KEY]) or matches a stored provider key, always; when it looks
     * like a code ([AnswerRisk.CODE]), unless [sendAnyway] (the owner's second tap). An answer
     * gives no permission, approves nothing and leaves every other request as it is (rules 2,
     * 33); it is logged without its text. The default refuses: questions are switched off.
     */
    suspend fun answer(id: RequestId, text: String?, sendAnyway: Boolean = false): OwnerResult<Unit> =
        OwnerResult.Refused(QUESTIONS_UNAVAILABLE)

    /** Texts. */
    companion object {
        const val QUESTIONS_UNAVAILABLE: String = "Answering agents' questions is not available in this version."
    }
}

/**
 * How much of the owner's attention agents may ask for at once (Phase 6, rules 13 and 14, C3,
 * D10). The framework checks these under one lock shared by the capability requester, the
 * permission broker and the question tool (WP5), so two concurrent asks cannot pass a cap
 * together. An "agent-initiated" request is one the agent raised on its own: an ask for a
 * permission (toolId null) or a QUESTION ([isAgentInitiated]); every other request was raised by
 * a tool call in progress.
 */
object OwnerAttention {
    /** Agent-initiated requests open at once, in the whole app. */
    const val MAX_OPEN_AGENT_REQUESTS: Int = 5

    /** Agent-initiated requests open at once under one objective. */
    const val MAX_OPEN_AGENT_REQUESTS_PER_OBJECTIVE: Int = 3

    /** Pending requests raised by calls in progress, in the whole app; over it the call is refused and nothing runs (rule 14). */
    const val MAX_PENDING_CALL_REQUESTS: Int = 12

    /** Pending requests raised by calls in progress under one objective. */
    const val MAX_PENDING_CALL_REQUESTS_PER_OBJECTIVE: Int = 6

    /** Open questions of one task at a time. */
    const val MAX_OPEN_QUESTIONS_PER_TASK: Int = 1

    /** Questions one task may ask in all. */
    const val MAX_QUESTIONS_PER_TASK: Int = 2

    /** Questions one objective may ask in all. */
    const val MAX_QUESTIONS_PER_OBJECTIVE: Int = 4

    /** Minutes a question waits for the owner by default (`FrameworkSettings.ownerQuestionTimeoutMinutes`). */
    const val DEFAULT_QUESTION_TIMEOUT_MINUTES: Int = 20

    /** Raised by the agent on its own (a permission ask or a question), not by a tool call in progress. */
    fun isAgentInitiated(request: OwnerRequest): Boolean = request.kind == OwnerRequestKind.QUESTION || request.toolId == null
}

/**
 * The owner's view of what each agent has been granted, with direct grants and revocation.
 * Implemented in `:framework` over [GrantStore]; every change is logged.
 */
interface OwnerGrants {
    /** Grants in force or waiting to be used (not revoked, not consumed), oldest first. */
    fun observeForAgent(agentId: AgentId): Flow<List<PermissionGrant>>

    fun observeAll(): Flow<List<PermissionGrant>>

    /**
     * Grants [capability] to [agentId] directly, ALWAYS (the Folders and Screen control screens'
     * "which agents may use it"). Refused when the capability is unknown or granted by default,
     * the constraint's kind is not the capability's, the folder does not exist, or the app is not
     * authorised or is protected. An identical grant in force is returned instead of a second one.
     */
    suspend fun grant(agentId: AgentId, capability: Capability, constraint: CapabilityConstraint = CapabilityConstraint.None): OwnerResult<PermissionGrant>

    /** Returns false when the grant had already ended. */
    suspend fun revoke(id: GrantId): Boolean

    /** Returns how many grants were revoked. */
    suspend fun revokeAll(agentId: AgentId): Int
}

/**
 * The Folders screen. Implemented over [SharedFolders], [SharedFolderRepository] and
 * [GrantStore]; every change is logged.
 */
interface OwnerFolders {
    fun observeAll(): Flow<List<SharedFolder>>

    /**
     * Keeps access to a tree the owner just picked and stores it. Picking a tree that is already
     * stored returns the stored folder. Refused when Android does not let this app keep access.
     */
    suspend fun add(treeUri: String): OwnerResult<SharedFolder>

    /**
     * Removes a folder in one step, narrowing first: revokes every grant constrained to it, then
     * deletes it, then releases Android's access. Returns false if it did not exist.
     */
    suspend fun remove(id: FolderId): Boolean

    /** The owner's read-only switch. Returns the updated folder, or null if it does not exist. */
    suspend fun setReadOnly(id: FolderId, readOnly: Boolean): SharedFolder?
}

/**
 * The authorised-apps list of the Screen control screen. Implemented over
 * [OperatorAppRepository] and [GrantStore]; every change is logged.
 */
interface OwnerOperatorApps {
    fun observeAll(): Flow<List<OperatorApp>>

    /**
     * Authorises an installed app for screen operation, recording its signing certificate so a
     * different app installed later under the same name is not trusted. Refused for protected
     * packages ([ProtectedPackages]) and invalid package names. A newly authorised app asks
     * before every action ([ScreenActionPolicy.ASK_EVERY_ACTION]); authorising the same app
     * again keeps the policy the owner chose, and a different certificate resets it.
     */
    suspend fun authorise(packageName: String, label: String, signingCertSha256: String?): OwnerResult<OperatorApp>

    /** Sets how often the app's screen actions ask the owner. Null when the app is not authorised. */
    suspend fun setPolicy(packageName: String, policy: ScreenActionPolicy): OperatorApp?

    /** Revokes every grant constrained to the app, then withdraws it. Returns false if it was not authorised. */
    suspend fun withdraw(packageName: String): Boolean
}

/** The outcome of [CapabilityRequester.ask]. */
sealed interface AskResult {
    /** A PERMISSION request was raised; the owner answers it in the app. */
    data class Raised(val requestId: RequestId) : AskResult

    /** The same agent already asked for the same capability and target, and the owner has not answered yet. */
    data class AlreadyPending(val requestId: RequestId) : AskResult

    /** Already allowed: granted by default, or a TASK or ALWAYS grant covers it. */
    data object AlreadyGranted : AskResult

    /** Not asked; [reason] is a sentence the model can act on. */
    data class Refused(val reason: String) : AskResult
}

/**
 * How an agent asks the owner for a capability (the `permissions.request` tool's only
 * dependency). It never waits for the answer and can never grant anything. Implementations:
 * - apply [OperatorGate] first (agent on, capability known, constraint kind right, folder
 *   exists, app authorised and not protected, master switches, local-only mode), so nothing the
 *   broker would refuse is put to the owner;
 * - refuse a [reason] longer than [MAX_REASON_CHARS] or blank, and store it verbatim otherwise
 *   (shown quoted);
 * - answer [AskResult.AlreadyGranted] when no request is needed;
 * - keep at most one PENDING ask per agent, capability and constraint ([AskResult.AlreadyPending])
 *   and at most [MAX_PENDING_ASKS_PER_AGENT] per agent;
 * - raise asks with toolId null, the risk of the capability's riskiest tool or MODIFYING, and
 *   the expiry from `approvalTimeoutMinutes`. Asks of a task are cancelled when the task finishes
 *   ([OwnerRequestQueue.cancelAsksForTask]).
 * Reference implementation and contract: `:testkit` (`CapabilityRequesterContract`).
 */
interface CapabilityRequester {
    suspend fun ask(context: ToolContext, capability: Capability, constraint: CapabilityConstraint, reason: String): AskResult

    /** Limits on asks. */
    companion object {
        const val MAX_REASON_CHARS: Int = 500
        const val MAX_PENDING_ASKS_PER_AGENT: Int = 3
    }
}
