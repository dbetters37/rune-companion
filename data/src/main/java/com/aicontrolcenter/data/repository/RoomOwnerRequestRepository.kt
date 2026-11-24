package com.aicontrolcenter.data.repository

import androidx.room.withTransaction
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.OwnerDecision
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.OwnerRequestDao
import com.aicontrolcenter.data.db.entity.OwnerRequestEntity
import com.aicontrolcenter.data.db.mapper.mapReadable
import com.aicontrolcenter.data.db.mapper.readableOrNull
import com.aicontrolcenter.data.db.mapper.storedTextBytes
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [OwnerRequestRepository] on Room, in the app's (encrypted) database. It behaves like the
 * reference `InMemoryOwnerRequestRepository` of `:testkit` and runs the same contract suite.
 *
 * - Every way out of PENDING (an answer, expiry, cancellation, the sweeps) is one conditional
 *   `UPDATE … WHERE id = ? AND status = 'PENDING'` with a row-count check
 *   ([OwnerRequestDao.closePending]), so a request is closed at most once however many callers
 *   race. The sweeps run it once per candidate inside one write transaction and report only the
 *   requests their own update closed.
 * - [decide] runs in one write transaction: it reads the request to choose the outcome, closes
 *   it with that conditional update and, for a granted PERMISSION request, inserts the grant
 *   into `permission_grants`. If the grant cannot be stored (its id is taken) the transaction is
 *   rolled back and the request stays PENDING. Observers are told only after the commit, so
 *   whoever sees the request APPROVED finds its grant.
 * - A row that cannot be decoded is treated as absent: it is never shown as pending and cannot
 *   be answered.
 *
 * Dependency injection hands the broker [com.aicontrolcenter.core.operator.OwnerRequestQueue]
 * only; the owner's port gets this class. All work runs on [dispatcher].
 */
class RoomOwnerRequestRepository internal constructor(
    private val database: AiccDatabase,
    private val dao: OwnerRequestDao,
    private val dispatcher: CoroutineDispatcher,
) : OwnerRequestRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database, database.ownerRequestDao(), dispatcher)

    /**
     * @throws IllegalArgumentException, storing nothing, if the request is not PENDING, its id
     *   is taken, or it holds more than [MAX_TEXT_BYTES] of text.
     */
    override suspend fun create(request: OwnerRequest) {
        require(request.status == OwnerRequestStatus.PENDING) { "A new request must be pending (request ${request.id})." }
        val entity = request.toEntity()
        val textBytes = entity.storedTextBytes()
        require(textBytes <= MAX_TEXT_BYTES) {
            "Request ${request.id} holds $textBytes bytes of text; at most $MAX_TEXT_BYTES can be stored."
        }
        withContext(dispatcher) {
            require(dao.insert(entity) != -1L) { "Request ${request.id} already exists." }
        }
    }

    override suspend fun get(id: RequestId): OwnerRequest? = withContext(dispatcher) {
        dao.get(id.value)?.readableOrNull(OwnerRequestEntity::toDomain)
    }

    override fun observe(id: RequestId): Flow<OwnerRequest?> =
        dao.observe(id.value).map { it?.readableOrNull(OwnerRequestEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override fun observePending(): Flow<List<OwnerRequest>> = dao.observePending().readable()

    /** @throws IllegalArgumentException if [limit] is negative. */
    override fun observeRecent(limit: Int): Flow<List<OwnerRequest>> {
        require(limit >= 0) { "limit must not be negative, was $limit" }
        return dao.observeRecent(limit).readable()
    }

    override fun observeForConversation(conversationId: ConversationId): Flow<List<OwnerRequest>> =
        dao.observeForConversation(conversationId.value).readable()

    override fun observeForObjective(rootId: TaskId): Flow<List<OwnerRequest>> = dao.observeForObjective(rootId.value).readable()

    /**
     * See [com.aicontrolcenter.core.operator.OwnerRequestDecisions.decide].
     * @throws IllegalArgumentException, changing nothing, if a grant with [grantId] exists.
     */
    override suspend fun decide(id: RequestId, decision: OwnerDecision, at: Long, grantId: GrantId): DecideResult =
        withContext(dispatcher) { database.withTransaction { decideInTransaction(id, decision, at, grantId) } }

    private suspend fun decideInTransaction(id: RequestId, decision: OwnerDecision, at: Long, grantId: GrantId): DecideResult {
        val current = dao.get(id.value)?.readableOrNull(OwnerRequestEntity::toDomain) ?: return DecideResult.NotFound
        if (!current.status.isPending) return DecideResult.AlreadyClosed(current)
        current.problemWith(decision)?.let { return DecideResult.NotAccepted(current, it) }
        if (current.isExpiredAt(at)) {
            val expired = current.copy(status = OwnerRequestStatus.EXPIRED, decidedAt = at, decisionNote = null)
            return if (closePending(expired)) DecideResult.Expired(expired) else alreadyClosed(id)
        }
        val grant = (decision as? OwnerDecision.Grant)?.let { current.grantFor(it.scope, grantId, at) }
        val decided = when (decision) {
            is OwnerDecision.Grant -> current.copy(
                status = OwnerRequestStatus.APPROVED, decidedAt = at, decisionScope = decision.scope, decisionNote = decision.note,
            )
            is OwnerDecision.Approve -> current.copy(status = OwnerRequestStatus.APPROVED, decidedAt = at, decisionNote = decision.note)
            is OwnerDecision.Deny -> current.copy(status = OwnerRequestStatus.DENIED, decidedAt = at, decisionNote = decision.note)
        }
        if (!closePending(decided)) return alreadyClosed(id)
        // Thrown inside the transaction, so the request goes back to PENDING with it.
        if (grant != null) require(dao.insertGrant(grant.toEntity()) != -1L) { "Grant $grantId already exists." }
        return DecideResult.Decided(decided, grant)
    }

    /** The conditional update that moves [closed] out of PENDING; true if this call did it. */
    private suspend fun closePending(closed: OwnerRequest): Boolean =
        dao.closePending(closed.id.value, closed.status.name, checkNotNull(closed.decidedAt), closed.decisionScope?.name, closed.decisionNote) == 1

    /** Reached only if the row changed between the read and the update, which the write transaction rules out. */
    private suspend fun alreadyClosed(id: RequestId): DecideResult =
        dao.get(id.value)?.readableOrNull(OwnerRequestEntity::toDomain)?.let { DecideResult.AlreadyClosed(it) } ?: DecideResult.NotFound

    /** @throws IllegalArgumentException if [status] is neither EXPIRED nor CANCELLED (checked first). */
    override suspend fun close(id: RequestId, status: OwnerRequestStatus, at: Long, note: String?): OwnerRequest? {
        requireClosing(status)
        return withContext(dispatcher) {
            database.withTransaction {
                if (dao.closePending(id.value, status.name, at, null, note) == 1) dao.get(id.value) else null
            }?.readableOrNull(OwnerRequestEntity::toDomain)
        }
    }

    override suspend fun closeOverdue(at: Long, note: String?): List<OwnerRequest> =
        closeEach(OwnerRequestStatus.EXPIRED, at, note) { dao.pendingDueBy(at) }

    override suspend fun cancelAllPending(at: Long, note: String?): List<OwnerRequest> =
        closeEach(OwnerRequestStatus.CANCELLED, at, note) { dao.pending() }

    override suspend fun cancelAsksForTask(taskId: TaskId, at: Long, note: String?): List<OwnerRequest> =
        closeEach(OwnerRequestStatus.CANCELLED, at, note) { dao.pendingAsksOf(taskId.value) }

    override suspend fun lastRefusal(agentId: AgentId, capability: Capability, constraint: CapabilityConstraint, since: Long): OwnerRequest? =
        withContext(dispatcher) {
            dao.lastRefusal(agentId.value, capability.id, constraint.stored, since)?.readableOrNull(OwnerRequestEntity::toDomain)
        }

    override suspend fun countAskedAgain(id: RequestId): OwnerRequest? = withContext(dispatcher) {
        database.withTransaction {
            if (dao.countAskedAgain(id.value) == 1) dao.get(id.value) else null
        }?.readableOrNull(OwnerRequestEntity::toDomain)
    }

    /**
     * Closes each request [candidates] lists (oldest first) with its own conditional update, in
     * one write transaction, and returns those this call closed, as stored.
     */
    private suspend fun closeEach(
        status: OwnerRequestStatus,
        at: Long,
        note: String?,
        candidates: suspend () -> List<OwnerRequestEntity>,
    ): List<OwnerRequest> = withContext(dispatcher) {
        database.withTransaction {
            candidates()
                .filter { dao.closePending(it.id, status.name, at, null, note) == 1 }
                .map { it.copy(status = status.name, decidedAt = at, decisionScope = null, decisionNote = note) }
        }.mapReadable(OwnerRequestEntity::toDomain)
    }

    private fun requireClosing(status: OwnerRequestStatus) =
        require(status == OwnerRequestStatus.EXPIRED || status == OwnerRequestStatus.CANCELLED) {
            "close() only expires or cancels a request, not $status."
        }

    private fun Flow<List<OwnerRequestEntity>>.readable(): Flow<List<OwnerRequest>> =
        map { rows -> rows.mapReadable(OwnerRequestEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    companion object {
        /**
         * Most UTF-8 bytes of text one request may hold (summary, details, the agent's reason
         * and the other text columns together). A larger row could not be read back through
         * Android's cursor window of about 2 MB and would break the pending list, the approval
         * screen and the history. A preview that long is refused when the request is raised,
         * so the call it belongs to is refused too, and nothing runs unseen.
         */
        const val MAX_TEXT_BYTES = 1024 * 1024
    }
}
