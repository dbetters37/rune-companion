package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.llm.ToolDefinition
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.llm.TokenEstimator
import com.aicontrolcenter.framework.operator.OperatorFixture
import com.aicontrolcenter.framework.operator.PermissionRequestTool
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test support for lean requests (Phase 6, WP15, rule 28).
 */

/** A model gateway that hands every request to [client] unchanged and keeps the context of each call. */
internal class ClientGateway(private val client: LlmClient) : ModelGateway {
    val contexts = CopyOnWriteArrayList<CallContext>()

    override suspend fun chat(modelId: ModelId, request: ChatRequest, context: CallContext): ChatResponse {
        contexts += context
        return client.chat(request)
    }

    override fun chatStream(modelId: ModelId, request: ChatRequest, context: CallContext): Flow<ChatChunk> {
        contexts += context
        return client.chatStream(request)
    }
}

/** Registers the phone operator's tools (over [OperatorFixture]'s fakes) and `permissions.request` in [world]: every tool the app registers but the team tools. */
internal fun registerOperatorTools(world: TestWorld): OperatorFixture {
    val operator = OperatorFixture()
    operator.tools.values.forEach(world.toolRegistry::register)
    world.toolRegistry.register(PermissionRequestTool(world.requester))
    return operator
}

/** Estimated tokens of tool definitions, as the runner's budget counts them. */
internal fun toolTokens(definitions: List<ToolDefinition>): Int = TokenEstimator.tokens(emptyList(), definitions)

/** Estimated tokens of a text. */
internal fun textTokens(text: String): Int = TokenEstimator.tokens(text)

/** The system message of [request]. */
internal fun systemOf(request: ChatRequest): String = request.messages.first { it.role == ChatRole.SYSTEM }.text

/** A definition exactly as it goes on the wire (name, description, schema), for byte-for-byte comparisons. */
internal fun ToolDefinition.rendered(): String = "$name\u0000$description\u0000$parameters"

/** The function names [request] offers. */
internal fun namesIn(request: ChatRequest): List<String> = request.tools.map { it.name }

/** Arguments for a `tools_open` call. */
internal fun family(name: String): JsonObject = JsonObject(mapOf("family" to JsonPrimitive(name)))

/** The text of the tool result for [callId] in [messages]. */
internal fun resultOf(messages: List<ChatMessage>, callId: String): String =
    messages.mapNotNull { it.toolResult }.single { it.callId == callId }.content
