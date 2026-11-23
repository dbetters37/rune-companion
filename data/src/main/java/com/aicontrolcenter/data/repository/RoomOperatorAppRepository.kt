package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.OperatorAppDao
import com.aicontrolcenter.data.db.entity.OperatorAppEntity
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
 * [OperatorAppRepository] on Room: the single record of which apps the owner authorised for
 * screen operation. It behaves like the reference `InMemoryOperatorAppRepository` of `:testkit`
 * and runs the same contract suite. Storage refuses nothing: protected packages are refused by
 * the owner port, the broker and the screen operator.
 */
class RoomOperatorAppRepository internal constructor(
    private val dao: OperatorAppDao,
    private val dispatcher: CoroutineDispatcher,
) : OperatorAppRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.operatorAppDao(), dispatcher)

    override fun observeAll(): Flow<List<OperatorApp>> =
        dao.observeAll().map { rows -> rows.map(OperatorAppEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getAll(): List<OperatorApp> = withContext(dispatcher) { dao.getAll().map(OperatorAppEntity::toDomain) }

    override suspend fun get(packageName: String): OperatorApp? = withContext(dispatcher) { dao.get(packageName)?.toDomain() }

    override suspend fun upsert(app: OperatorApp) = withContext(dispatcher) { dao.upsert(app.toEntity()) }

    override suspend fun delete(packageName: String) = withContext(dispatcher) { dao.delete(packageName) }
}
