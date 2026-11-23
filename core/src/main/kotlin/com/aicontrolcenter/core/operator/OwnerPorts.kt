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
    /** Requests waiting for an answer, oldest first, without those already past their expiry. */
    val pending: Flow<List<OwnerRequest>>

    /** The newest [limit] requests of any status. */
    fun observeRecent(limit: Int): Flow<List<OwnerRequest>>

    /** Requests raised in one chat, oldest first, to show inline there. */
    fun observeForConversation(conversationId: ConversationId): Flow<List<OwnerRequest>>

    suspend fun get(id: RequestId): OwnerRequest?

    /** Yes to a PERMISSION request for [scope]; the grant is stored in the same transaction ([OwnerRequestDecisions.decide]). */
    suspend fun grant(id: RequestId, scope: GrantScope, note: String? = null): DecideResult

    /** Yes to an APPROVAL request: this action, once. There is no scope to give. */
    suspend fun approve(id: RequestId, note: String? = null): DecideResult

    /** No, to either kind. */
    suspend fun deny(id: RequestId, note: String? = null): DecideResult
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
     * packages ([ProtectedPackages]) and invalid package names.
     */
    suspend fun authorise(packageName: String, label: String, signingCertSha256: String?): OwnerResult<OperatorApp>

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
