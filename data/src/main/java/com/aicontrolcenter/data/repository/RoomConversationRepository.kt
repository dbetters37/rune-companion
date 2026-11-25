package com.aicontrolcenter.data.repository

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.chat.InterruptedTurn
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.ConversationDao
import com.aicontrolcenter.data.db.entity.ConversationEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryRow
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
 * [ConversationRepository] on Room.
 *
 * - Conversations are listed most recently updated first; equal times are ordered by id, so
 *   the order is stable.
 * - Entries are append-only and numbered per conversation from 0. An entry that cannot be
 *   decoded is treated as absent, like unreadable rows of the other repositories; the entries
 *   around it keep their numbers.
 * - A conversation is removed together with its entries in one transaction.
 * - Messages of any size can be stored and read back; see [ConversationEntryRow] for why that
 *   needs care.
 * - A turn in progress is marked on the conversation's row (schema 6, R3) and unmarked when it
 *   ends; saving the conversation keeps the mark, deleting it removes it.
 * - Long conversations are read in parts by sequence number, from the primary key (schema 6, P2).
 * - Deleting a conversation also deletes what its model calls cost (`work_usage`), in the same
 *   transaction.
 */
class RoomConversationRepository internal constructor(
    private val dao: ConversationDao,
    private val dispatcher: CoroutineDispatcher,
) : ConversationRepository {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.conversationDao(), dispatcher)

    override fun observeConversations(agentId: AgentId?): Flow<List<Conversation>> {
        val rows = if (agentId == null) dao.observeAll() else dao.observeByAgent(agentId.value)
        return rows.map { it.map(ConversationEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)
    }

    override suspend fun getConversation(id: ConversationId): Conversation? = dao.get(id.value)?.toDomain()

    override suspend fun upsertConversation(conversation: Conversation) = dao.upsert(conversation.toEntity())

    override suspend fun deleteConversation(id: ConversationId) = dao.delete(id.value)

    override suspend fun deleteByAgent(agentId: AgentId) = dao.deleteByAgent(agentId.value)

    override fun observeEntries(id: ConversationId): Flow<List<ConversationEntry>> =
        dao.observeEntryRows(id.value).map { rows -> rows.toEntries() }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getEntries(id: ConversationId): List<ConversationEntry> = withContext(dispatcher) {
        dao.entryRows(id.value).toEntries()
    }

    /**
     * @throws IllegalArgumentException if the conversation does not exist. Nothing is stored
     *   then.
     */
    override suspend fun appendEntry(entry: ConversationEntry): ConversationEntry = withContext(dispatcher) {
        val seq = requireNotNull(dao.append(entry.toEntity())) { "No such conversation: ${entry.conversationId}" }
        entry.copy(seq = seq)
    }

    override suspend fun markTurnStarted(id: ConversationId, at: Long) = withContext(dispatcher) { dao.markTurnStarted(id.value, at) }

    override suspend fun markTurnEnded(id: ConversationId) = withContext(dispatcher) { dao.markTurnEnded(id.value) }

    override suspend fun interruptedTurns(): List<InterruptedTurn> = withContext(dispatcher) {
        dao.interruptedTurns().map { InterruptedTurn(ConversationId(it.id), it.turnStartedAt) }
    }

    override fun observeLatestSeq(id: ConversationId): Flow<Int?> =
        dao.observeLatestSeq(id.value).distinctUntilChanged().flowOn(dispatcher)

    override suspend fun entriesAfter(id: ConversationId, seq: Int): List<ConversationEntry> = withContext(dispatcher) {
        dao.entryRowsAfter(id.value, seq).toEntries()
    }

    override suspend fun entriesBefore(id: ConversationId, seq: Int, limit: Int): List<ConversationEntry> = withContext(dispatcher) {
        if (limit <= 0) emptyList() else dao.entryRowsBefore(id.value, seq, limit).asReversed().toEntries()
    }

    private suspend fun List<ConversationEntryRow>.toEntries(): List<ConversationEntry> =
        mapNotNull { row -> row.withWholeMessage()?.readableOrNull(ConversationEntryEntity::toDomain) }

    /**
     * Fetches the rest of a message that is longer than the part delivered with its row.
     *
     * The pieces come from separate queries. That is sound because an entry is never rewritten;
     * the only thing that can happen in between is that it is deleted, in which case there is
     * no entry to return.
     */
    private suspend fun ConversationEntryRow.withWholeMessage(): ConversationEntryEntity? {
        var message = messageHead
        if (messageBytes > message.size) {
            val whole = message.copyOf(messageBytes)
            var filled = message.size
            while (filled < messageBytes) {
                val part = dao.messageBytes(conversationId, seq, firstByte = filled + 1, count = ConversationEntryRow.PART_BYTES)
                if (part == null || part.isEmpty() || filled + part.size > messageBytes) return null
                part.copyInto(whole, destinationOffset = filled)
                filled += part.size
            }
            message = whole
        }
        return ConversationEntryEntity(conversationId, seq, at, modelName, message.decodeToString(), usageJson, error)
    }
}
