package com.aicontrolcenter.data.db.mapper

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.data.db.entity.ConversationEntity
import com.aicontrolcenter.data.db.entity.ConversationEntryEntity

internal fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id.value,
    agentId = agentId.value,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = ConversationId(id),
    agentId = AgentId(agentId),
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ConversationEntry.toEntity(): ConversationEntryEntity = ConversationEntryEntity(
    conversationId = conversationId.value,
    seq = seq,
    at = at,
    modelName = modelName,
    messageJson = encodeColumn(ChatMessage.serializer(), message),
    usageJson = usage?.let { encodeColumn(TokenUsage.serializer(), it) },
    error = error,
)

internal fun ConversationEntryEntity.toDomain(): ConversationEntry {
    val row = RowRef(ConversationEntryEntity.TABLE, "$conversationId#$seq")
    return ConversationEntry(
        conversationId = ConversationId(conversationId),
        seq = seq,
        message = row.json("message_json", ChatMessage.serializer(), messageJson),
        at = at,
        modelName = modelName,
        usage = usageJson?.let { row.json("usage_json", TokenUsage.serializer(), it) },
        error = error,
    )
}
