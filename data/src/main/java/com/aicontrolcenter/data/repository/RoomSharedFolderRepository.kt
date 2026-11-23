package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.SharedFolderDao
import com.aicontrolcenter.data.db.entity.SharedFolderEntity
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
 * [SharedFolderRepository] on Room. It behaves like the reference
 * `InMemorySharedFolderRepository` of `:testkit` and runs the same contract suite.
 *
 * One tree is one folder: `tree_uri` is unique, and [upsert] of a folder whose tree another
 * folder already holds is refused instead of replacing that folder, whose grants would then
 * name a folder that no longer exists. Saving an existing id updates the row in place.
 */
class RoomSharedFolderRepository internal constructor(
    private val dao: SharedFolderDao,
    private val dispatcher: CoroutineDispatcher,
) : SharedFolderRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.sharedFolderDao(), dispatcher)

    override fun observeAll(): Flow<List<SharedFolder>> =
        dao.observeAll().map { rows -> rows.map(SharedFolderEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getAll(): List<SharedFolder> = withContext(dispatcher) { dao.getAll().map(SharedFolderEntity::toDomain) }

    override suspend fun get(id: FolderId): SharedFolder? = withContext(dispatcher) { dao.get(id.value)?.toDomain() }

    override suspend fun getByTreeUri(treeUri: String): SharedFolder? = withContext(dispatcher) { dao.getByTreeUri(treeUri)?.toDomain() }

    /** @throws IllegalArgumentException, storing nothing, if another folder has the same [SharedFolder.treeUri]. */
    override suspend fun upsert(folder: SharedFolder) = withContext(dispatcher) {
        require(dao.upsert(folder.toEntity())) { "Another folder already uses the tree of folder ${folder.id}." }
    }

    override suspend fun delete(id: FolderId) = withContext(dispatcher) { dao.delete(id.value) }
}
