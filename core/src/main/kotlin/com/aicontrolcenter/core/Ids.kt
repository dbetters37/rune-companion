package com.aicontrolcenter.core

import kotlinx.serialization.Serializable
import java.util.UUID

/*
 * Strongly typed identifiers. Each is a thin wrapper around a string so that an agent id can
 * never be passed where a model id is expected. They serialize as plain JSON strings.
 */

@Serializable @JvmInline value class AgentId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class ProviderId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class ModelId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class AiAppId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class TaskId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class MemoryId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class SpaceId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class MessageId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class ToolId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class EventId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class RequestId(val value: String) { override fun toString(): String = value }
@Serializable @JvmInline value class ConversationId(val value: String) { override fun toString(): String = value }

/** Source of new identifiers. Injected everywhere so tests can be deterministic. */
fun interface IdGenerator {
    /** Returns a new unique id of the form `<prefix>_<random>`. */
    fun newId(prefix: String): String
}

object RandomIdGenerator : IdGenerator {
    override fun newId(prefix: String): String =
        prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(20)
}

fun IdGenerator.agentId() = AgentId(newId("agt"))
fun IdGenerator.providerId() = ProviderId(newId("prv"))
fun IdGenerator.modelId() = ModelId(newId("mdl"))
fun IdGenerator.aiAppId() = AiAppId(newId("app"))
fun IdGenerator.taskId() = TaskId(newId("tsk"))
fun IdGenerator.memoryId() = MemoryId(newId("mem"))
fun IdGenerator.spaceId() = SpaceId(newId("spc"))
fun IdGenerator.messageId() = MessageId(newId("msg"))
fun IdGenerator.eventId() = EventId(newId("evt"))
fun IdGenerator.requestId() = RequestId(newId("req"))
fun IdGenerator.conversationId() = ConversationId(newId("cnv"))
