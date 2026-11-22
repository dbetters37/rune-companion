package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.AgentDao
import com.aicontrolcenter.data.db.entity.AgentEntity
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
 * [AgentRepository] on Room.
 *
 * Rows are converted to domain objects on [dispatcher], never on the caller's thread, so the
 * JSON columns are not parsed on the main thread when a screen collects a flow. A row that
 * cannot be converted is treated as absent (see `mapReadable`), here and in the other Room
 * repositories.
 */
class RoomAgentRepository internal constructor(
    private val dao: AgentDao,
    private val dispatcher: CoroutineDispatcher,
) : AgentRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.agentDao(), dispatcher)

    override fun observeAll(): Flow<List<AgentProfile>> =
        dao.observeAll().map { rows -> rows.mapReadable(AgentEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override fun observe(id: AgentId): Flow<AgentProfile?> =
        dao.observe(id.value).map { it?.readableOrNull(AgentEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getAll(): List<AgentProfile> = withContext(dispatcher) {
        dao.getAll().mapReadable(AgentEntity::toDomain)
    }

    override suspend fun get(id: AgentId): AgentProfile? = withContext(dispatcher) {
        dao.get(id.value)?.readableOrNull(AgentEntity::toDomain)
    }

    override suspend fun upsert(agent: AgentProfile) = withContext(dispatcher) {
        dao.upsert(agent.toEntity())
    }

    override suspend fun delete(id: AgentId) = dao.deleteWithStats(id.value)

    override fun observeStats(): Flow<Map<AgentId, AgentStats>> =
        dao.observeStats()
            .map { rows -> rows.associate { AgentId(it.agentId) to it.toDomain() } }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    override suspend fun getStats(id: AgentId): AgentStats = dao.getStats(id.value)?.toDomain() ?: AgentStats()

    /**
     * Atomic: the current value is read and the result written inside one write transaction,
     * so concurrent updates are applied one after another and none is lost. [transform] runs
     * inside that transaction and must be quick and free of side effects. For an id no agent
     * has, nothing is written and [transform] is not called.
     */
    override suspend fun updateStats(id: AgentId, transform: (AgentStats) -> AgentStats) {
        dao.updateStats(id.value) { stored -> transform(stored?.toDomain() ?: AgentStats()).toEntity(id) }
    }
}
