package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OwnerDecision
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Reference implementation of [PermissionRepository]: every change is one compare-and-set on the whole map. */
class InMemoryPermissionRepository : PermissionRepository {
    private val state = MutableStateFlow<Map<GrantId, PermissionGrant>>(emptyMap())

    private val grantOrder = compareBy<PermissionGrant>({ it.grantedAt }, { it.id.value })

    private fun Map<GrantId, PermissionGrant>.listed(filter: (PermissionGrant) -> Boolean) =
        values.filter { it.revokedAt == null && it.consumedAt == null && filter(it) }.sortedWith(grantOrder)

    override fun observeForAgent(agentId: AgentId): Flow<List<PermissionGrant>> =
        state.map { it.listed { g -> g.agentId == agentId } }.distinctUntilChanged()

    override fun observeAll(): Flow<List<PermissionGrant>> = state.map { it.listed { true } }.distinctUntilChanged()

    override suspend fun get(id: GrantId): PermissionGrant? = state.value[id]

    override suspend fun insert(grant: PermissionGrant) = add(grant)

    /** [insert] without suspending, for [InMemoryOwnerRequestRepository.decide]. */
    internal fun add(grant: PermissionGrant) {
        var taken = false
        state.update { all ->
            taken = grant.id in all
            if (taken) all else all + (grant.id to grant)
        }
        require(!taken) { "Grant ${grant.id} already exists." }
    }

    override suspend fun revoke(id: GrantId, at: Long): PermissionGrant? = change({ it.id == id }) { g ->
        if (g.revokedAt == null && g.consumedAt == null) g.copy(revokedAt = at) else null
    }

    override suspend fun revokeAllFor(agentId: AgentId, at: Long): Int = revokeWhere(at) { it.agentId == agentId }

    override suspend fun revokeAllWithConstraint(constraint: CapabilityConstraint, at: Long): Int =
        revokeWhere(at) { it.constraint == constraint }

    override suspend fun revokeAllForTask(taskId: TaskId, at: Long): Int =
        revokeWhere(at) { it.taskId == taskId && it.scope != GrantScope.ALWAYS }

    override suspend fun findMatching(query: GrantQuery): List<PermissionGrant> =
        state.value.values.filter { it.covers(query) }.sortedWith(PermissionGrant.PREFERENCE)

    override suspend fun findByRequest(requestId: RequestId): PermissionGrant? =
        state.value.values.filter { it.requestId == requestId }.minWithOrNull(grantOrder)

    override suspend fun consumeOnceFor(requestId: RequestId, at: Long): PermissionGrant? =
        change({ it.requestId == requestId && it.scope == GrantScope.ONCE }) { g -> if (g.isActiveAt(at)) g.copy(consumedAt = at) else null }

    override suspend fun releaseOnceFor(requestId: RequestId, at: Long): PermissionGrant? =
        change({ it.requestId == requestId && it.scope == GrantScope.ONCE }) { g ->
            if (g.revokedAt == null && g.consumedAt == null) g.copy(revokedAt = at) else null
        }

    /** Applies [transform] atomically to the grant [which] selects; a null result leaves it unchanged and is returned. */
    private fun change(which: (PermissionGrant) -> Boolean, transform: (PermissionGrant) -> PermissionGrant?): PermissionGrant? {
        var result: PermissionGrant? = null
        state.update { all ->
            result = all.values.firstOrNull(which)?.let(transform)
            result?.let { all + (it.id to it) } ?: all
        }
        return result
    }

    private fun revokeWhere(at: Long, predicate: (PermissionGrant) -> Boolean): Int {
        var count = 0
        state.update { all ->
            val revoked = all.values.filter { it.revokedAt == null && it.consumedAt == null && predicate(it) }.map { it.copy(revokedAt = at) }
            count = revoked.size
            all + revoked.associateBy { it.id }
        }
        return count
    }
}

/**
 * Reference implementation of [OwnerRequestRepository]. Every change happens under one lock;
 * a granted PERMISSION request's grant is added to [grants] before the request is published as
 * APPROVED, so whoever sees the approval finds the grant.
 */
class InMemoryOwnerRequestRepository(
    /** Where [decide] writes grants; share it with the broker's [com.aicontrolcenter.core.operator.GrantLookup]. */
    val grants: InMemoryPermissionRepository = InMemoryPermissionRepository(),
) : OwnerRequestRepository {
    private val state = MutableStateFlow<Map<RequestId, OwnerRequest>>(emptyMap())
    private val lock = Any()

    private val oldestFirst = compareBy<OwnerRequest>({ it.createdAt }, { it.id.value })

    private fun Map<RequestId, OwnerRequest>.oldestFirst(filter: (OwnerRequest) -> Boolean) = values.filter(filter).sortedWith(oldestFirst)

    override suspend fun create(request: OwnerRequest) {
        require(request.status == OwnerRequestStatus.PENDING) { "A new request must be pending." }
        synchronized(lock) {
            require(request.id !in state.value) { "Request ${request.id} already exists." }
            state.value += request.id to request
        }
    }

    override suspend fun get(id: RequestId): OwnerRequest? = state.value[id]

    override fun observe(id: RequestId): Flow<OwnerRequest?> = state.map { it[id] }.distinctUntilChanged()

    override fun observePending(): Flow<List<OwnerRequest>> =
        state.map { it.oldestFirst { r -> r.status.isPending } }.distinctUntilChanged()

    override fun observeRecent(limit: Int): Flow<List<OwnerRequest>> =
        state.map { all -> all.values.sortedWith(oldestFirst).asReversed().take(limit) }.distinctUntilChanged()

    override fun observeForConversation(conversationId: ConversationId): Flow<List<OwnerRequest>> =
        state.map { it.oldestFirst { r -> r.conversationId == conversationId } }.distinctUntilChanged()

    override fun observeForObjective(rootId: TaskId): Flow<List<OwnerRequest>> =
        state.map { it.oldestFirst { r -> r.rootId == rootId } }.distinctUntilChanged()

    override suspend fun decide(id: RequestId, decision: OwnerDecision, at: Long, grantId: GrantId): DecideResult {
        synchronized(lock) { return decideLocked(id, decision, at, grantId) }
    }

    private fun decideLocked(id: RequestId, decision: OwnerDecision, at: Long, grantId: GrantId): DecideResult {
        val current = state.value[id] ?: return DecideResult.NotFound
        if (!current.status.isPending) return DecideResult.AlreadyClosed(current)
        current.problemWith(decision)?.let { return DecideResult.NotAccepted(current, it) }
        if (current.isExpiredAt(at)) {
            val expired = current.copy(status = OwnerRequestStatus.EXPIRED, decidedAt = at)
            state.value += id to expired
            return DecideResult.Expired(expired)
        }
        val grant = (decision as? OwnerDecision.Grant)?.let { current.grantFor(it.scope, grantId, at) }
        grant?.let(grants::add)
        val decided = answered(current, decision, at)
        state.value += id to decided
        return DecideResult.Decided(decided, grant)
    }

    private fun answered(request: OwnerRequest, decision: OwnerDecision, at: Long): OwnerRequest = when (decision) {
        is OwnerDecision.Grant -> request.copy(status = OwnerRequestStatus.APPROVED, decidedAt = at, decisionScope = decision.scope, decisionNote = decision.note)
        is OwnerDecision.Approve -> request.copy(status = OwnerRequestStatus.APPROVED, decidedAt = at, decisionNote = decision.note)
        is OwnerDecision.Deny -> request.copy(status = OwnerRequestStatus.DENIED, decidedAt = at, decisionNote = decision.note)
    }

    override suspend fun close(id: RequestId, status: OwnerRequestStatus, at: Long, note: String?): OwnerRequest? {
        require(status == OwnerRequestStatus.EXPIRED || status == OwnerRequestStatus.CANCELLED) { "close() only expires or cancels." }
        return synchronized(lock) {
            state.value[id]?.takeIf { it.status.isPending }?.copy(status = status, decidedAt = at, decisionNote = note)
                ?.also { state.value += id to it }
        }
    }

    override suspend fun closeOverdue(at: Long, note: String?): List<OwnerRequest> =
        closeWhere(OwnerRequestStatus.EXPIRED, at, note) { it.isExpiredAt(at) }

    override suspend fun cancelAllPending(at: Long, note: String?): List<OwnerRequest> =
        closeWhere(OwnerRequestStatus.CANCELLED, at, note) { true }

    override suspend fun cancelAsksForTask(taskId: TaskId, at: Long, note: String?): List<OwnerRequest> =
        closeWhere(OwnerRequestStatus.CANCELLED, at, note) { it.toolId == null && it.taskId == taskId }

    override suspend fun lastRefusal(agentId: AgentId, capability: Capability, constraint: CapabilityConstraint, since: Long): OwnerRequest? =
        state.value.values
            .filter {
                it.kind == OwnerRequestKind.PERMISSION && it.status == OwnerRequestStatus.DENIED && it.agentId == agentId &&
                    it.capability == capability && it.constraint == constraint && (it.decidedAt ?: Long.MIN_VALUE) >= since &&
                    it.askAgainAt == null
            }
            .maxWithOrNull(compareBy<OwnerRequest>({ it.decidedAt }, { it.id.value }))

    override suspend fun countAskedAgain(id: RequestId): OwnerRequest? = synchronized(lock) {
        state.value[id]?.takeIf { it.status == OwnerRequestStatus.DENIED }?.let { it.copy(askedAgain = it.askedAgain + 1) }
            ?.also { state.value += id to it }
    }

    override suspend fun letAskAgain(id: RequestId, at: Long): OwnerRequest? = synchronized(lock) {
        val refusal = state.value[id]?.takeIf { it.kind == OwnerRequestKind.PERMISSION && it.status == OwnerRequestStatus.DENIED } ?: return null
        val opened = state.value.values.filter {
            it.kind == OwnerRequestKind.PERMISSION && it.status == OwnerRequestStatus.DENIED && it.askAgainAt == null &&
                it.agentId == refusal.agentId && it.capability == refusal.capability && it.constraint == refusal.constraint
        }.map { it.copy(askAgainAt = at) }
        if (opened.isNotEmpty()) state.value += opened.associateBy { it.id }
        state.value[id]
    }

    private fun closeWhere(status: OwnerRequestStatus, at: Long, note: String?, predicate: (OwnerRequest) -> Boolean): List<OwnerRequest> =
        synchronized(lock) {
            val closed = state.value.values.filter { it.status.isPending && predicate(it) }.sortedWith(oldestFirst)
                .map { it.copy(status = status, decidedAt = at, decisionNote = note) }
            state.value += closed.associateBy { it.id }
            closed
        }
}

/** Reference implementation of [SharedFolderRepository]. */
class InMemorySharedFolderRepository : SharedFolderRepository {
    private val state = MutableStateFlow<Map<FolderId, SharedFolder>>(emptyMap())
    private val lock = Any()

    private fun Map<FolderId, SharedFolder>.ordered() = values.sortedWith(compareBy({ it.addedAt }, { it.id.value }))

    override fun observeAll(): Flow<List<SharedFolder>> = state.map { it.ordered() }.distinctUntilChanged()
    override suspend fun getAll(): List<SharedFolder> = state.value.ordered()
    override suspend fun get(id: FolderId): SharedFolder? = state.value[id]
    override suspend fun getByTreeUri(treeUri: String): SharedFolder? = state.value.values.firstOrNull { it.treeUri == treeUri }

    override suspend fun upsert(folder: SharedFolder) = synchronized(lock) {
        require(state.value.values.none { it.treeUri == folder.treeUri && it.id != folder.id }) { "Another folder already uses that tree." }
        state.value += folder.id to folder
    }

    override suspend fun delete(id: FolderId) = state.update { it - id }
}

/** Reference implementation of [OperatorAppRepository]. */
class InMemoryOperatorAppRepository : OperatorAppRepository {
    private val state = MutableStateFlow<Map<String, OperatorApp>>(emptyMap())

    private fun Map<String, OperatorApp>.ordered() = values.sortedWith(compareBy({ it.authorisedAt }, { it.packageName }))

    override fun observeAll(): Flow<List<OperatorApp>> = state.map { it.ordered() }.distinctUntilChanged()
    override suspend fun getAll(): List<OperatorApp> = state.value.ordered()
    override suspend fun get(packageName: String): OperatorApp? = state.value[packageName]
    override suspend fun upsert(app: OperatorApp) = state.update { it + (app.packageName to app) }
    override suspend fun delete(packageName: String) = state.update { it - packageName }
}
