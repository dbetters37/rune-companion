package com.aicontrolcenter.providers.anthropic

import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.LlmException
import com.anthropic.core.http.StreamResponse
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Performs single Claude turns through the official SDK: one request, one assistant message.
 * Whatever the model returns, including tool arguments and text that reads like instructions,
 * is handed back as data; this class acts on none of it.
 *
 * Both entry points are safe to call from any dispatcher: the SDK's blocking work runs on
 * [Dispatchers.IO], and cancelling the caller aborts the HTTP exchange instead of leaving it
 * to finish in the background.
 */
internal class AnthropicLlmClient(private val sdk: SdkClient, private val options: AnthropicOptions) : LlmClient {

    /** Set once the endpoint has rejected the refusal-fallback opt-in; it is not sent again. */
    private val fallbacksRejected = AtomicBoolean(false)

    /**
     * A plain request and response, except when `maxOutputTokens` is so large that the SDK
     * only permits the call as a stream; then the reply is streamed and assembled here, which
     * the caller cannot tell apart.
     */
    override suspend fun chat(request: ChatRequest): ChatResponse {
        if (RequestBuilder.maxTokens(request, streaming = false) > RequestBuilder.MAX_TOKENS_WITHOUT_STREAMING) {
            var completed: ChatResponse? = null
            chatStream(request).collect { if (it is ChatChunk.Completed) completed = it.response }
            return completed ?: throw LlmException.MalformedResponse("The stream ended without a completed reply.")
        }
        // The SDK reads a response lazily, so the reply is taken apart inside the call, where
        // a body that is not a message surfaces as a mapped error like every other failure.
        val turn = sendWithRecovery(request, streaming = false) { params ->
            sdk.call { ResponseMapper.rawTurn(it.beta().messages().create(params)) }
        }
        return ResponseMapper.chatResponse(turn)
    }

    /**
     * Emits text and reasoning-summary deltas as they arrive, a tool call when its block is
     * complete, and finally exactly one [ChatChunk.Completed] holding the assembled reply.
     * If the reply ends in a refusal, text streamed before it is partial output to discard;
     * the completed response says so through its finish reason.
     *
     * Cancelling collection closes the stream, which ends generation on the provider's side.
     */
    override fun chatStream(request: ChatRequest): Flow<ChatChunk> = callbackFlow {
        val stream = openStream(request)
        launch {
            try {
                val assembler = StreamAssembler()
                // Reading blocks this thread between events; the stream is closed from
                // awaitClose below to end such a read when the collector goes away.
                stream.use { events -> for (event in events) assembler.accept(event)?.let { send(it) } }
                send(ChatChunk.Completed(ResponseMapper.chatResponse(assembler.finish())))
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                close(sdk.errors.map(e))
            }
        }
        awaitClose { stream.close() }
    }.flowOn(Dispatchers.IO)

    private suspend fun openStream(request: ChatRequest): StreamResponse<BetaRawMessageStreamEvent> =
        sendWithRecovery(request, streaming = true) { params ->
            sdk.open { it.beta().messages().createStreaming(params) }
        }

    /**
     * Sends the request, and repeats it once in each of two situations in which the provider
     * rejects the request for something this adapter added rather than for what the caller
     * asked. Both are recognised by the wording of a 400, which only ever decides on that
     * one resend.
     *
     *  - The endpoint does not accept the refusal-fallback opt-in (a gateway or platform
     *    without the beta names the header or the parameter). The opt-in is dropped for good,
     *    so the default-on option cannot make a provider unusable.
     *  - Replayed thinking blocks no longer match the conversation, because the caller edited
     *    or trimmed earlier history, or changed the system prompt or the tools. Retrying
     *    unchanged can never succeed, so the turn is sent without any thinking blocks, which
     *    is Anthropic's documented recovery. The model answers without that earlier reasoning.
     */
    private suspend fun <T> sendWithRecovery(request: ChatRequest, streaming: Boolean, call: suspend (MessageCreateParams) -> T): T {
        var fallbacks = options.refusalFallbacks && !fallbacksRejected.get() && RequestBuilder.sendsFallbacks(request)
        var replayThinking = true
        while (true) {
            try {
                return call(RequestBuilder.build(request, streaming, fallbacks, replayThinking))
            } catch (e: LlmException.InvalidRequest) {
                val reason = ErrorMapper.rejectionDetail(e).lowercase()
                when {
                    e.status != 400 -> throw e
                    fallbacks && "fallback" in reason -> {
                        fallbacksRejected.set(true)
                        fallbacks = false
                    }
                    replayThinking && "thinking" in reason && "signature" in reason -> replayThinking = false
                    else -> throw e
                }
            }
        }
    }

    override fun toString(): String = "AnthropicLlmClient($sdk)"
}
