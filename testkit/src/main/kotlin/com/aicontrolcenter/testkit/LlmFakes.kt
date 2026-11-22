package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderAdapter
import com.aicontrolcenter.core.llm.ProviderAdapterFactory
import com.aicontrolcenter.core.llm.ProviderConnection
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

/** One scripted step of a fake model. */
sealed interface ScriptStep {
    /** The model answers with text and stops. */
    data class Reply(val text: String, val usage: TokenUsage = TokenUsage(10, 5)) : ScriptStep

    /** The model asks for tools to be run. */
    data class Calls(val calls: List<ToolCallRequest>, val text: String = "", val usage: TokenUsage = TokenUsage(10, 5)) : ScriptStep

    /** The call fails. */
    data class Fail(val error: LlmException) : ScriptStep
}

/**
 * A model that does what the test says. Either a fixed script (one step per call, the last
 * step repeating) or a handler that sees each request. Every request is recorded.
 */
class ScriptedLlmClient(
    private val modelName: String = "scripted-1",
    private val handler: suspend (request: ChatRequest, callIndex: Int) -> ScriptStep,
) : LlmClient {
    val requests = CopyOnWriteArrayList<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResponse {
        requests += request
        return when (val step = handler(request, requests.size - 1)) {
            is ScriptStep.Reply -> ChatResponse(ChatMessage.assistant(step.text), FinishReason.STOP, step.usage, modelName)
            is ScriptStep.Calls ->
                ChatResponse(ChatMessage.assistant(step.text, step.calls), FinishReason.TOOL_CALLS, step.usage, modelName)
            is ScriptStep.Fail -> throw step.error
        }
    }

    /** Streams the scripted reply in two text pieces, so consumers must handle partial text. */
    override fun chatStream(request: ChatRequest): Flow<ChatChunk> = flow {
        val response = chat(request)
        val text = response.message.text
        if (text.isNotEmpty()) {
            val middle = text.length / 2
            if (middle > 0) emit(ChatChunk.Text(text.substring(0, middle)))
            emit(ChatChunk.Text(text.substring(middle)))
        }
        response.message.toolCalls.forEach { emit(ChatChunk.ToolCall(it)) }
        emit(ChatChunk.Completed(response))
    }

    companion object {
        fun script(vararg steps: ScriptStep, modelName: String = "scripted-1") =
            ScriptedLlmClient(modelName) { _, index -> steps[index.coerceAtMost(steps.lastIndex)] }

        fun replies(vararg texts: String) = script(*texts.map { ScriptStep.Reply(it) }.toTypedArray())

        fun call(id: String, name: String, arguments: JsonObject = JsonObject(emptyMap())) =
            ToolCallRequest(id, name, arguments)
    }
}

/** A provider whose health, model list and client are set by the test. */
class FakeProviderAdapter(
    var models: List<RemoteModel> = emptyList(),
    var health: ProviderHealth = ProviderHealth(ConnectionStatus.ONLINE, latencyMs = 12),
    override val client: LlmClient = ScriptedLlmClient.replies("ok"),
    var listError: LlmException? = null,
) : ProviderAdapter {
    var closed = false
        private set

    override suspend fun checkHealth(): ProviderHealth = health

    override suspend fun listModels(): List<RemoteModel> {
        listError?.let { throw it }
        return models
    }

    override fun close() {
        closed = true
    }
}

/** Hands out adapters from [adapterFor] and remembers every connection it was asked to open. */
class FakeProviderAdapterFactory(
    override val kind: ProviderKind,
    private val adapterFor: (ProviderConnection) -> ProviderAdapter,
) : ProviderAdapterFactory {
    val connections = CopyOnWriteArrayList<ProviderConnection>()

    constructor(kind: ProviderKind, adapter: ProviderAdapter) : this(kind, { adapter })

    override fun create(connection: ProviderConnection): ProviderAdapter {
        connections += connection
        return adapterFor(connection)
    }
}
