package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.ModelDao
import com.aicontrolcenter.data.db.entity.ModelEntity
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

/** [ModelRepository] on Room. */
class RoomModelRepository internal constructor(
    private val dao: ModelDao,
    private val dispatcher: CoroutineDispatcher,
) : ModelRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.modelDao(), dispatcher)

    override fun observeAll(): Flow<List<ModelDescriptor>> =
        dao.observeAll().map { rows -> rows.mapReadable(ModelEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getAll(): List<ModelDescriptor> = withContext(dispatcher) {
        dao.getAll().mapReadable(ModelEntity::toDomain)
    }

    override suspend fun get(id: ModelId): ModelDescriptor? = withContext(dispatcher) {
        dao.get(id.value)?.readableOrNull(ModelEntity::toDomain)
    }

    override suspend fun getByProvider(providerId: ProviderId): List<ModelDescriptor> = withContext(dispatcher) {
        dao.getByProvider(providerId.value).mapReadable(ModelEntity::toDomain)
    }

    override suspend fun upsert(model: ModelDescriptor) = withContext(dispatcher) {
        dao.upsert(model.toEntity())
    }

    /** All-or-nothing: the whole list is written in one transaction. */
    override suspend fun upsertAll(models: List<ModelDescriptor>) = withContext(dispatcher) {
        dao.upsertAll(models.map(ModelDescriptor::toEntity))
    }

    override suspend fun delete(id: ModelId) = dao.delete(id.value)

    override suspend fun deleteByProvider(providerId: ProviderId) = dao.deleteByProvider(providerId.value)
}
