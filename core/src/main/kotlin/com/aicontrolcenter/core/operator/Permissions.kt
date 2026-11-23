package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** How long an owner's "yes" to a permission request lasts. Stored by name. */
enum class GrantScope {
    /**
     * Exactly the one call whose request the owner answered. Bound to that request
     * ([PermissionGrant.requestId]): never matched to any other call
     * ([PermissionGrant.covers] is false), used up only by that request's waiter
     * ([GrantLookup.consumeOnceFor]) and void after [PermissionGrant.ONCE_LIFETIME_MS].
     */
    ONCE,

    /** Every call the agent makes while working on one task. Needs a task; revoked when the task finishes. */
    TASK,

    /** Every call, in any task or chat, until the owner revokes it. */
    ALWAYS,
}

/**
 * The owner's permission for one agent to use one capability, possibly narrowed to one folder or
 * app ([constraint]). Grants come from the owner's answers to permission requests; agents never
 * create, extend or decide them. A grant ends when it is revoked, consumed (ONCE) or expires.
 */
@Serializable
data class PermissionGrant(
    val id: GrantId,
    val agentId: AgentId,
    val capability: Capability,
    val constraint: CapabilityConstraint = CapabilityConstraint.None,
    val scope: GrantScope,
    /** TASK: the task it is for (required). ONCE: the task of the call that asked, or null outside tasks. ALWAYS: null. */
    val taskId: TaskId? = null,
    /** The owner request whose approval created it (required for ONCE); null if the owner granted it directly. */
    val requestId: RequestId? = null,
    val grantedAt: Long,
    /** Void from this time on (epoch ms). Required for ONCE. */
    val expiresAt: Long? = null,
    val revokedAt: Long? = null,
    /** ONCE only: when the call it allowed used it up. */
    val consumedAt: Long? = null,
) {
    init {
        require(scope != GrantScope.TASK || taskId != null) { "A TASK grant needs a task." }
        require(scope != GrantScope.ALWAYS || taskId == null) { "An ALWAYS grant is not tied to a task." }
        require(scope != GrantScope.ONCE || expiresAt != null) { "A ONCE grant needs an expiry." }
        require(scope != GrantScope.ONCE || requestId != null) { "A ONCE grant belongs to the request that created it." }
        require(scope == GrantScope.ONCE || consumedAt == null) { "Only a ONCE grant is consumed." }
        require(expiresAt == null || expiresAt > grantedAt) { "A grant must expire after it was granted." }
    }

    /** Neither revoked nor consumed, and not expired at [at]. */
    fun isActiveAt(at: Long): Boolean = revokedAt == null && consumedAt == null && (expiresAt == null || at < expiresAt)

    /**
     * Whether this grant allows the call described by [query]. The single definition of scope
     * matching: every [GrantLookup.findMatching] must return exactly the grants for which this
     * holds. ONCE grants never match: only their own request's waiter uses them.
     */
    fun covers(query: GrantQuery): Boolean =
        isActiveAt(query.at) && agentId == query.agentId && capability == query.capability &&
            constraint == query.constraint &&
            when (scope) {
                GrantScope.ALWAYS -> true
                GrantScope.TASK -> taskId == query.taskId
                GrantScope.ONCE -> false
            }

    companion object {
        /** A ONCE grant nobody used within this time is void, so it cannot be spent on a much later call. */
        const val ONCE_LIFETIME_MS: Long = 10 * 60_000L

        /** The order [GrantLookup.findMatching] returns grants in: ALWAYS, then TASK (ONCE never matches); then oldest first, then id. */
        val PREFERENCE: Comparator<PermissionGrant> =
            compareBy<PermissionGrant>({ scopePreference(it.scope) }, { it.grantedAt }, { it.id.value })

        private fun scopePreference(scope: GrantScope) = when (scope) {
            GrantScope.ALWAYS -> 0
            GrantScope.TASK -> 1
            GrantScope.ONCE -> 2
        }
    }
}

/** One call to be checked against stored grants: who, what, which folder or app, in which task, when. */
data class GrantQuery(
    val agentId: AgentId,
    val capability: Capability,
    val constraint: CapabilityConstraint,
    /** The call's task; null for a call outside any task (direct chat). */
    val taskId: TaskId?,
    /** The time of the check (epoch ms); grants expired by then do not match. */
    val at: Long,
)

/**
 * The permission broker's view of grants: checking, and using up the ONCE grant its own request
 * created. Nothing here creates, extends or restores a grant; revoking only narrows. Handed to
 * the broker and the orchestrator instead of [PermissionRepository].
 */
interface GrantLookup {
    /** Every grant that [PermissionGrant.covers] the query (so never a ONCE grant), in [PermissionGrant.PREFERENCE] order. */
    suspend fun findMatching(query: GrantQuery): List<PermissionGrant>

    /** The grant created by answering [requestId], in any state, or null. */
    suspend fun findByRequest(requestId: RequestId): PermissionGrant?

    /**
     * Uses up the ONCE grant created by answering [requestId], atomically: of any number of
     * concurrent callers, at most one gets it back (with [PermissionGrant.consumedAt] = [at]).
     * Null when there is none, or it is revoked, consumed or expired at [at]. Only the waiter of
     * that request calls this, for the one call the owner saw.
     */
    suspend fun consumeOnceFor(requestId: RequestId, at: Long): PermissionGrant?

    /** Revokes the unused ONCE grant of [requestId] (its waiter went away). Returns it, or null if there was none to revoke. */
    suspend fun releaseOnceFor(requestId: RequestId, at: Long): PermissionGrant?

    /** Revokes every TASK and ONCE grant of a task that reached a finished status. Returns how many. */
    suspend fun revokeAllForTask(taskId: TaskId, at: Long): Int
}

/**
 * The owner's side of grant storage: listing, adding directly, revoking. Insert-only: a grant
 * that exists is never replaced, so a revoked or consumed grant cannot come back. Grants that
 * answer a request are written by [OwnerRequestDecisions.decide], in the same transaction.
 * Handed only to the owner ports ([OwnerGrants], [OwnerFolders], [OwnerOperatorApps]).
 */
interface GrantStore {
    /** Grants neither revoked nor consumed, oldest first (granted time, then id); expiry is not applied. */
    fun observeForAgent(agentId: AgentId): Flow<List<PermissionGrant>>

    /** All grants neither revoked nor consumed, in the same order. */
    fun observeAll(): Flow<List<PermissionGrant>>

    suspend fun get(id: GrantId): PermissionGrant?

    /** Stores a new grant. @throws IllegalArgumentException if its id exists, revoked or not. */
    suspend fun insert(grant: PermissionGrant)

    /** Ends a grant that is neither revoked nor consumed. Returns the updated grant, or null if nothing changed. */
    suspend fun revoke(id: GrantId, at: Long): PermissionGrant?

    /** Revokes every grant of the agent that is neither revoked nor consumed. Returns how many. */
    suspend fun revokeAllFor(agentId: AgentId, at: Long): Int

    /** Revokes every unrevoked, unconsumed grant narrowed to [constraint] (a folder or app the owner removed). Returns how many. */
    suspend fun revokeAllWithConstraint(constraint: CapabilityConstraint, at: Long): Int
}

/**
 * Storage of permission grants: both sides. Implementations: Room (`:data`) and in-memory
 * (`:testkit`); both pass `PermissionRepositoryContract`. Observe flows emit at once and after
 * every change. Dependency injection hands out only [GrantLookup] or [GrantStore], never this.
 * Room: every state change is one conditional `UPDATE … WHERE <still active>` with a row-count
 * check, never a read followed by a write.
 */
interface PermissionRepository : GrantLookup, GrantStore
