package com.aicontrolcenter.providers.ollama

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.serialization.json.JsonObject

/**
 * Ollama's native chat: `POST /api/chat`, as one JSON object or as newline-delimited JSON.
 *
 * A request for reasoning effort is sent as `think`. Models without a thinking mode reject
 * that with a 400; the call is then repeated without it and the model remembered.
 */
internal class OllamaChatClient(
    private val http: ProviderHttp,
    private val memory: ParameterMemory,
    private val memoryScope: String,
) : LlmClient {

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val negotiation = negotiate(request, stream = false)
        val reply = http.postJson(PATH, negotiation.body(), recovery = negotiation) as? JsonObject
            ?: throw http.errors.malformed("the reply was not a JSON object")
        val assembler = OllamaReplyAssembler(http, request.model)
        assembler.accept(reply)
        if (reply["message"] !is JsonObject) throw http.errors.malformed("the reply contained no message")
        return assembler.complete()
    }

    override fun chatStream(request: ChatRequest): Flow<ChatChunk> = flow {
        val negotiation = negotiate(request, stream = true)
        val assembler = OllamaReplyAssembler(http, request.model)
        http.postLines(PATH, negotiation.body(), accept = ProviderHttp.NDJSON, recovery = negotiation)
            // Reading stops with the object marked done, whether or not the server then closes the connection.
            .takeWhile { line ->
                if (line.isNotBlank()) {
                    val chunk = http.decode(line) as? JsonObject ?: throw http.errors.malformed("a stream line was not a JSON object")
                    assembler.accept(chunk).forEach { emit(it) }
                }
                !assembler.done
            }
            .collect()
        if (!assembler.done) throw http.errors.droppedStream()
        emit(ChatChunk.Completed(assembler.complete()))
    }

    private fun negotiate(request: ChatRequest, stream: Boolean): Negotiation {
        if (request.model.isBlank()) throw LlmException.InvalidRequest("No model name was given for this call.")
        return Negotiation(request, stream)
    }

    private inner class Negotiation(private val request: ChatRequest, private val stream: Boolean) : Recovery {
        private val scope = "$memoryScope|${request.model}"

        @Volatile
        private var omitted: Set<String> = memory.refused(scope)

        @Volatile
        private var sent: Set<String> = emptySet()

        fun body(): JsonObject {
            val built = OllamaRequests.build(request, stream, omitted)
            sent = built.optional
            return built.body
        }

        override fun adjust(failure: HttpFailure): JsonObject? {
            val refusal = RefusedSetting.find(failure, sent.associateWith { OllamaRequests.THINK_ALIASES }) ?: return null
            omitted = omitted + refusal.setting
            if (refusal.permanent) memory.remember(scope, refusal.setting)
            return body()
        }
    }

    private companion object {
        const val PATH = "api/chat"
    }
}
