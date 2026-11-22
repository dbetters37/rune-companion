package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.data.db.ActivityQueryBuilder
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.ActivityEventDao
import com.aicontrolcenter.data.db.entity.ActivityEventEntity
import com.aicontrolcenter.data.db.mapper.mapReadable
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
 * [ActivityLog] on Room.
 *
 * - Append-only: an event is never rewritten. Recording an id that is already stored is a
 *   no-op, which makes a retried write harmless.
 * - "Newest first" is by timestamp and, for equal timestamps, by insertion (latest first).
 * - Filtering, ordering and limiting all happen in SQL on indexes; see [ActivityQueryBuilder].
 */
class RoomActivityLog internal constructor(
    private val dao: ActivityEventDao,
    private val dispatcher: CoroutineDispatcher,
) : ActivityLog {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.activityEventDao(), dispatcher)

    override suspend fun record(event: ActivityEvent) {
        withContext(dispatcher) { dao.insert(event.toEntity()) }
    }

    /** @throws IllegalArgumentException if [limit] is negative. */
    override fun observe(filter: ActivityFilter, limit: Int): Flow<List<ActivityEvent>> {
        val query = ActivityQueryBuilder.build(filter, limit).toRoomRawQuery()
        return dao.observe(query)
            .map { rows -> rows.mapReadable(ActivityEventEntity::toDomain) }
            .distinctUntilChanged()
            .flowOn(dispatcher)
    }

    /** @throws IllegalArgumentException if [limit] is negative. */
    override suspend fun recent(filter: ActivityFilter, limit: Int): List<ActivityEvent> {
        val query = ActivityQueryBuilder.build(filter, limit).toRoomRawQuery()
        return withContext(dispatcher) { dao.select(query).mapReadable(ActivityEventEntity::toDomain) }
    }

    override suspend fun prune(olderThan: Long): Int = dao.deleteOlderThan(olderThan)

    override suspend fun clear() = dao.deleteAll()
}
