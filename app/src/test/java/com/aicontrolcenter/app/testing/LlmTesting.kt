package com.aicontrolcenter.app.testing

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.llm.RemoteModel
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.testkit.FakeProviderAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A model whose reply arrives in [pieces] and stops after the first one until [release] is
 * called, so a test (or a screenshot) can look at a turn while it is still streaming.
 *
 * [reasoning] is part of the finished reply. With [streamReasoning] it is also streamed ahead
 * of the text, as a provider with thinking summaries does; the chat publishes live text at most
 * every 75 ms of wall-clock time, so the client then waits that long before the first text.
 */
class GatedLlmClient(
    private val pieces: List<String>,
    private val reasoning: String = "",
    private val modelName: String = "scripted-1",
    private val usage: TokenUsage = TokenUsage(42, 7),
    private val streamReasoning: Boolean = false,
) : LlmClient {
    private val gate = CompletableDeferred<Unit>()
    val requests = CopyOnWriteArrayList<ChatRequest>()

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun chat(request: ChatRequest): ChatResponse {
        requests += request
        gate.await()
        return response()
    }

    override fun chatStream(request: ChatRequest): Flow<ChatChunk> = flow {
        requests += request
        if (streamReasoning && reasoning.isNotEmpty()) {
            emit(ChatChunk.Reasoning(reasoning))
            Thread.sleep(LIVE_TEXT_INTERVAL_MS)
        }
        pieces.firstOrNull()?.let { emit(ChatChunk.Text(it)) }
        gate.await()
        pieces.drop(1).forEach { emit(ChatChunk.Text(it)) }
        emit(ChatChunk.Completed(response()))
    }

    private companion object {
        /** A little more than the chat's own interval between live-text updates. */
        const val LIVE_TEXT_INTERVAL_MS = 90L
    }

    private fun response() = ChatResponse(
        message = ChatMessage(com.aicontrolcenter.core.llm.ChatRole.ASSISTANT, pieces.joinToString(""), reasoning = reasoning.ifEmpty { null }),
        finishReason = FinishReason.STOP,
        usage = usage,
        modelName = modelName,
    )
}

/**
 * Connects a provider through the real provider manager, served by [client] (and listing
 * [listed]), and registers one model on it. Nothing touches a network: the fake factory hands
 * out the adapter registered for [baseUrl].
 */
suspend fun TestApp.serveModel(
    client: LlmClient,
    name: String = "Test server",
    baseUrl: String = "http://127.0.0.1:9100/v1",
    kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    modelName: String = "scripted-1",
    displayName: String = "Scripted model",
    capabilities: ModelCapabilities = ModelCapabilities(toolCalling = true),
    apiKey: String? = null,
    listed: List<RemoteModel> = emptyList(),
): ModelDescriptor {
    adaptersByUrl[baseUrl] = FakeProviderAdapter(models = listed, client = client)
    val provider = graph.providerManager.addProvider(ProviderDraft(name = name, kind = kind, baseUrl = baseUrl, apiKey = apiKey))
    return graph.providerManager.addModel(provider.id, modelName, displayName, capabilities)
}
