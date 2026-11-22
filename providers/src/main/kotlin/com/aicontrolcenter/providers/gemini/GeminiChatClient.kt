package com.aicontrolcenter.providers.gemini

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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonObject

/**
 * Gemini chat: `POST models/{model}:generateContent`, or `:streamGenerateContent?alt=sse` for a
 * stream of the same response objects as server-sent events. There is no end-of-stream marker;
 * the reply is complete when a candidate carries a finish reason.
 */
internal class GeminiChatClient(
    private val http: ProviderHttp,
    private val memory: ParameterMemory,
    private val memoryScope: String,
) : LlmClient {

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val negotiation = negotiate(request)
        val path = GeminiRequests.path(request.model, "generateContent")
        val reply = http.postJson(path, negotiation.body(), recovery = negotiation) as? JsonObject
            ?: throw http.errors.malformed("the reply was not a JSON object")
        val assembler = GeminiReplyAssembler(http, request.model)
        assembler.accept(reply)
        return assembler.complete()
    }

    override fun chatStream(request: ChatRequest): Flow<ChatChunk> = flow {
        val negotiation = negotiate(request)
        val path = GeminiRequests.path(request.model, "streamGenerateContent")
        val assembler = GeminiReplyAssembler(http, request.model)
        http.postLines(path, negotiation.body(), query = mapOf("alt" to "sse"), recovery = negotiation)
            .sseEvents()
            .takeWhile { event ->
                if (event.data.isNotBlank()) {
                    val chunk = http.decode(event.data) as? JsonObject ?: throw http.errors.malformed("a stream event was not a JSON object")
                    assembler.accept(chunk).forEach { emit(it) }
                }
                !assembler.finished
            }
            .collect()
        if (!assembler.finished) throw http.errors.droppedStream()
        emit(ChatChunk.Completed(assembler.complete()))
    }

    private fun negotiate(request: ChatRequest): Negotiation {
        if (request.model.isBlank()) throw LlmException.InvalidRequest("No model name was given for this call.")
        return Negotiation(request)
    }

    /**
     * One call's view of the optional settings; see
     * [com.aicontrolcenter.providers.http.ParameterMemory] for the general scheme. Specific to
     * Gemini is the reasoning setting: an objection to one of its two spellings moves on to
     * the other, an objection to thinking as such gives up on both.
     */
    private inner class Negotiation(private val request: ChatRequest) : Recovery {
        private val scope = "$memoryScope|${request.model}"

        @Volatile
        private var omitted: Set<String> = memory.refused(scope)

        @Volatile
        private var sent: Set<String> = emptySet()

        fun body(): JsonObject {
            val built = GeminiRequests.build(request, omitted)
            sent = built.optional
            return built.body
        }

        override fun adjust(failure: HttpFailure): JsonObject? {
            val specific = RefusedSetting.find(failure, sent.associateWith(GeminiRequests::aliases))
            val thinkingSettings = sent.filter { it in THINKING_SETTINGS }
            val refused = when {
                specific != null -> setOf(specific.setting) to specific.permanent
                else -> RefusedSetting.find(failure, thinkingSettings.associateWith { GeminiRequests.THINKING_ALIASES })
                    ?.let { THINKING_SETTINGS to it.permanent }
            } ?: return null
            omitted = omitted + refused.first
            if (refused.second) refused.first.forEach { memory.remember(scope, it) }
            return body()
        }
    }

    private companion object {
        val THINKING_SETTINGS = setOf(GeminiRequests.THINKING_LEVEL, GeminiRequests.THINKING_BUDGET)
    }
}
