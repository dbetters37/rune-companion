package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.ProviderDao
import com.aicontrolcenter.data.db.entity.ProviderEntity
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** [ProviderRepository] on Room. Stores key aliases only; keys live in the secret vault. */
class RoomProviderRepository internal constructor(
    private val dao: ProviderDao,
    private val dispatcher: CoroutineDispatcher,
) : ProviderRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.providerDao(), dispatcher)

    override fun observeAll(): Flow<List<ModelProvider>> =
        dao.observeAll().map { rows -> rows.map(ProviderEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getAll(): List<ModelProvider> = withContext(dispatcher) {
        dao.getAll().map(ProviderEntity::toDomain)
    }

    override suspend fun get(id: ProviderId): ModelProvider? = withContext(dispatcher) {
        dao.get(id.value)?.toDomain()
    }

    override suspend fun upsert(provider: ModelProvider) = withContext(dispatcher) {
        dao.upsert(provider.toEntity())
    }

    override suspend fun delete(id: ProviderId) = dao.delete(id.value)
}
