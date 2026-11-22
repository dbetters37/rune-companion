package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.AiAppId
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppRepository
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.AiAppDao
import com.aicontrolcenter.data.db.entity.AiAppEntity
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
 * [AiAppRepository] on Room. It records what the registry decided about each app and nothing
 * more; what may be done with an app is derived from the stored fields by `controlChannels()`.
 */
class RoomAiAppRepository internal constructor(
    private val dao: AiAppDao,
    private val dispatcher: CoroutineDispatcher,
) : AiAppRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.aiAppDao(), dispatcher)

    override fun observeAll(): Flow<List<AiApp>> =
        dao.observeAll().map { rows -> rows.map(AiAppEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getAll(): List<AiApp> = withContext(dispatcher) {
        dao.getAll().map(AiAppEntity::toDomain)
    }

    override suspend fun get(id: AiAppId): AiApp? = withContext(dispatcher) {
        dao.get(id.value)?.toDomain()
    }

    override suspend fun getByPackage(packageName: String): AiApp? = withContext(dispatcher) {
        dao.getByPackage(packageName)?.toDomain()
    }

    override suspend fun upsert(app: AiApp) = withContext(dispatcher) {
        dao.upsert(app.toEntity())
    }

    override suspend fun delete(id: AiAppId) = dao.delete(id.value)
}
