package com.aicontrolcenter.providers.openai

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.providers.http.HttpFailure
import com.aicontrolcenter.providers.http.ParameterMemory
import com.aicontrolcenter.providers.http.ProviderHttp
import com.aicontrolcenter.providers.http.Recovery
import com.aicontrolcenter.providers.http.RefusedSetting
import com.aicontrolcenter.providers.http.sseEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonObject

/**
 * Chat Completions client: `POST {base}/chat/completions`, plain or as server-sent events.
 *
 * Optional settings are negotiated per model; see [Negotiation].
 *
 * @param memoryScope identifies the provider and its address in [memory]
 */
internal class OpenAiChatClient(
    private val http: ProviderHttp,
    private val memory: ParameterMemory,
    private val memoryScope: String,
) : LlmClient {

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val negotiation = negotiate(request, stream = false)
        val reply = http.postJson(PATH, negotiation.body(), recovery = negotiation) as? JsonObject
            ?: throw http.errors.malformed("the reply was not a JSON object")
        val assembler = OpenAiReplyAssembler(http, negotiation.origin, request.model)
        assembler.accept(reply)
        if (!assembler.sawChoice) throw http.errors.malformed("the reply contained no choices")
        return assembler.complete()
    }

    override fun chatStream(request: ChatRequest): Flow<ChatChunk> = flow {
        val negotiation = negotiate(request, stream = true)
        val assembler = OpenAiReplyAssembler(http, negotiation.origin, request.model)
        var sawDone = false
        http.postLines(PATH, negotiation.body(), recovery = negotiation)
            .sseEvents()
            // Stop reading at the terminator: a server that keeps the connection open must not stall the reply.
            .takeWhile { event -> (event.data.trim() != DONE).also { if (!it) sawDone = true } }
            .collect { event ->
                if (event.data.isBlank()) return@collect
                val chunk = http.decode(event.data) as? JsonObject ?: throw http.errors.malformed("a stream event was not a JSON object")
                assembler.accept(chunk).forEach { emit(it) }
            }
        // Without a finish reason or the terminator, the connection was lost part-way.
        if (!sawDone && !assembler.finished) throw http.errors.droppedStream()
        val response = assembler.complete()
        response.message.toolCalls.forEach { emit(ChatChunk.ToolCall(it)) }
        emit(ChatChunk.Completed(response))
    }

    private fun negotiate(request: ChatRequest, stream: Boolean): Negotiation {
        if (request.model.isBlank()) throw LlmException.InvalidRequest("No model name was given for this call.")
        return Negotiation(request, stream, http.host())
    }

    /**
     * One call's view of the optional settings. It starts from what [memory] already knows the
     * model refuses and, as the transport's [Recovery], reacts to a rejection that names a
     * setting which was sent: the body is rebuilt without it, and a refusal of the setting
     * itself (as opposed to its value) is remembered for the model.
     */
    private inner class Negotiation(
        private val request: ChatRequest,
        private val stream: Boolean,
        val origin: String,
    ) : Recovery {
        private val scope = "$memoryScope|${request.model}"

        @Volatile
        private var omitted: Set<String> = memory.refused(scope)

        @Volatile
        private var sent: Set<String> = emptySet()

        fun body(): JsonObject {
            val built = OpenAiRequests.build(request, stream, omitted, origin)
            sent = built.optional
            return built.body
        }

        override fun adjust(failure: HttpFailure): JsonObject? {
            val refusal = RefusedSetting.find(failure, sent.associateWith(OpenAiRequests::aliases)) ?: return null
            var dropped = setOf(refusal.setting)
            // An objection to the value of max_tokens would only be repeated for its replacement.
            if (refusal.setting == OpenAiRequests.MAX_TOKENS && !refusal.permanent) dropped = dropped + OpenAiRequests.MAX_COMPLETION_TOKENS
            omitted = omitted + dropped
            if (refusal.permanent) memory.remember(scope, refusal.setting)
            return body()
        }
    }

    private companion object {
        const val PATH = "chat/completions"
        const val DONE = "[DONE]"
    }
}
