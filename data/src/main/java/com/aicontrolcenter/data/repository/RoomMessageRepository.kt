package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.AgentMessageDao
import com.aicontrolcenter.data.db.entity.AgentMessageEntity
import com.aicontrolcenter.data.db.mapper.mapReadable
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
 * [MessageRepository] on Room, in the app's (encrypted) database. It behaves like the reference
 * `InMemoryMessageRepository` of `:testkit` and runs the same contract suite.
 *
 * - Append-only. A message whose id is already stored is ignored, so a message re-sent after a
 *   crash appears once and a sent message is never rewritten.
 * - An objective's messages are in sending order (time, then id); [observeRecent] is the exact
 *   reverse across all objectives. Both are read from an index and limited by SQLite.
 * - [count] counts every stored message of the objective, readable or not: it is what the
 *   orchestrator's message limit is checked against, and that bound must not loosen because a
 *   row cannot be decoded. The lists leave unreadable rows out, as everywhere in this module.
 * - No message thrown or logged here contains a message body.
 *
 * All work runs on [dispatcher].
 */
class RoomMessageRepository internal constructor(
    private val dao: AgentMessageDao,
    private val dispatcher: CoroutineDispatcher,
) : MessageRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.agentMessageDao(), dispatcher)

    /** @throws IllegalArgumentException, storing nothing, if the message holds more than [MAX_TEXT_BYTES] of text. */
    override suspend fun append(message: AgentMessage) = withContext(dispatcher) {
        val entity = message.toEntity()
        val textBytes = entity.storedTextBytes()
        require(textBytes <= MAX_TEXT_BYTES) {
            "Message ${message.id} holds $textBytes bytes of text; at most $MAX_TEXT_BYTES can be stored."
        }
        dao.insertIfAbsent(entity)
    }

    override fun observe(rootId: TaskId): Flow<List<AgentMessage>> =
        dao.observe(rootId.value).map { rows -> rows.mapReadable(AgentMessageEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    /** @throws IllegalArgumentException if [limit] is negative. */
    override fun observeRecent(limit: Int): Flow<List<AgentMessage>> {
        require(limit >= 0) { "limit must not be negative, was $limit" }
        return dao.observeRecent(limit).map { rows -> rows.mapReadable(AgentMessageEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)
    }

    override suspend fun list(rootId: TaskId): List<AgentMessage> = withContext(dispatcher) {
        dao.list(rootId.value).mapReadable(AgentMessageEntity::toDomain)
    }

    override suspend fun count(rootId: TaskId): Int = withContext(dispatcher) { dao.count(rootId.value) }

    override suspend fun deleteFor(rootId: TaskId) = withContext(dispatcher) { dao.deleteFor(rootId.value) }

    companion object {
        /**
         * Most UTF-8 bytes of text (body and ids) one message may hold: half of Android's cursor
         * window of about 2 MB, so the row can always be read back. Findings passed between
         * agents are far smaller; a larger body belongs in the task's result.
         */
        const val MAX_TEXT_BYTES = 1024 * 1024
    }
}
