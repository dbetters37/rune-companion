package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.PermissionGrantDao
import com.aicontrolcenter.data.db.entity.PermissionGrantEntity
import com.aicontrolcenter.data.db.mapper.mapReadable
import com.aicontrolcenter.data.db.mapper.readableOrNull
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
 * [PermissionRepository] on Room, in the app's (encrypted) database. It behaves like the
 * reference `InMemoryPermissionRepository` of `:testkit` and runs the same contract suite.
 *
 * - Insert-only: [insert] never replaces a row, so a revoked or consumed grant cannot come back.
 * - Every change of state (revoke, consume, release) is one conditional `UPDATE` that matches
 *   only a grant still in force, and its row count decides the outcome: of any number of
 *   concurrent callers, exactly one ends a given grant. Nothing reads a grant and then writes it.
 * - [findMatching] lets SQLite narrow to the grants in force for the agent, capability and
 *   constraint (an index), then applies [PermissionGrant.covers], the one definition of scope
 *   and expiry, and [PermissionGrant.PREFERENCE] in Kotlin.
 * - A row that cannot be decoded is treated as absent, so it never authorises anything.
 *
 * Dependency injection hands out [com.aicontrolcenter.core.operator.GrantLookup] or
 * [com.aicontrolcenter.core.operator.GrantStore], never this class. All work runs on [dispatcher].
 */
class RoomPermissionRepository internal constructor(
    private val dao: PermissionGrantDao,
    private val dispatcher: CoroutineDispatcher,
) : PermissionRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.permissionGrantDao(), dispatcher)

    override fun observeForAgent(agentId: AgentId): Flow<List<PermissionGrant>> =
        dao.observeInForce(agentId.value).map { it.mapReadable(PermissionGrantEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override fun observeAll(): Flow<List<PermissionGrant>> =
        dao.observeAllInForce().map { it.mapReadable(PermissionGrantEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun get(id: GrantId): PermissionGrant? = withContext(dispatcher) {
        dao.get(id.value)?.readableOrNull(PermissionGrantEntity::toDomain)
    }

    /** @throws IllegalArgumentException, storing nothing, if a grant with this id exists (in any state). */
    override suspend fun insert(grant: PermissionGrant) = withContext(dispatcher) {
        require(dao.insert(grant.toEntity()) != -1L) { "Grant ${grant.id} already exists." }
    }

    override suspend fun revoke(id: GrantId, at: Long): PermissionGrant? = withContext(dispatcher) {
        dao.revokeAndGet(id.value, at)?.readableOrNull(PermissionGrantEntity::toDomain)
    }

    override suspend fun revokeAllFor(agentId: AgentId, at: Long): Int = withContext(dispatcher) {
        dao.revokeAllFor(agentId.value, at)
    }

    override suspend fun revokeAllWithConstraint(constraint: CapabilityConstraint, at: Long): Int = withContext(dispatcher) {
        dao.revokeAllWithConstraint(constraint.stored, at)
    }

    override suspend fun revokeAllForTask(taskId: TaskId, at: Long): Int = withContext(dispatcher) {
        dao.revokeAllForTask(taskId.value, at)
    }

    override suspend fun findMatching(query: GrantQuery): List<PermissionGrant> = withContext(dispatcher) {
        dao.candidates(query.agentId.value, query.capability.id, query.constraint.stored)
            .mapReadable(PermissionGrantEntity::toDomain)
            .filter { it.covers(query) }
            .sortedWith(PermissionGrant.PREFERENCE)
    }

    override suspend fun findByRequest(requestId: RequestId): PermissionGrant? = withContext(dispatcher) {
        dao.firstForRequest(requestId.value)?.readableOrNull(PermissionGrantEntity::toDomain)
    }

    override suspend fun consumeOnceFor(requestId: RequestId, at: Long): PermissionGrant? = withContext(dispatcher) {
        dao.consumeOnceAndGet(requestId.value, at)?.readableOrNull(PermissionGrantEntity::toDomain)
    }

    override suspend fun releaseOnceFor(requestId: RequestId, at: Long): PermissionGrant? = withContext(dispatcher) {
        dao.releaseOnceAndGet(requestId.value, at)?.readableOrNull(PermissionGrantEntity::toDomain)
    }
}
