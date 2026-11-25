package com.aicontrolcenter.core.llm

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Talks to one provider's chat endpoint. Implementations are safe to call concurrently. */
interface LlmClient {
    /** @throws LlmException for every failure; cancellation propagates as usual. */
    suspend fun chat(request: ChatRequest): ChatResponse

    /**
     * Streams the reply. Failures surface as [LlmException] from the flow. The default
     * implementation performs a normal call and emits the result at once, which is correct for
     * providers or models that cannot stream.
     */
    fun chatStream(request: ChatRequest): Flow<ChatChunk> = flow {
        val response = chat(request)
        if (response.message.text.isNotEmpty()) emit(ChatChunk.Text(response.message.text))
        response.message.toolCalls.forEach { emit(ChatChunk.ToolCall(it)) }
        emit(ChatChunk.Completed(response))
    }
}

/** Why a model call failed, in terms the framework can act on. */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** True when the same request may succeed if sent again later. */
    abstract val retryable: Boolean

    /** The key is missing, wrong or not allowed to use this model. */
    class Authentication(message: String, cause: Throwable? = null) : LlmException(message, cause) {
        override val retryable = false
    }

    /**
     * The provider asked the app to slow down (HTTP 429). [retryAfterMs] is the wait it asked for, if it named one.
     * [dailyQuota]: it said that a daily quota is used up, so waiting minutes does not help; by default, when [message]
     * says so ([namesDailyQuota]); an adapter that reads more of the provider's answer (a quota's id in the body) sets it.
     */
    class RateLimited(
        message: String,
        val retryAfterMs: Long? = null,
        cause: Throwable? = null,
        val dailyQuota: Boolean = namesDailyQuota(message),
    ) : LlmException(message, cause) {
        override val retryable = true

        companion object {
            /**
             * "per day", "per-day", "PerDay", "daily", "today", and the short forms "RPD" and "TPD" (requests and tokens
             * per day): what providers' 429 answers say when the limit reached is a daily one. A per-minute limit
             * ("per minute", "RPM", "per-min") says none of these.
             */
            private val DAILY = Regex("per[\\s_-]?day|daily|today|(?<![a-z])[rt]pd(?![a-z])", RegexOption.IGNORE_CASE)

            /** True when [text], a provider's answer to a call, names a daily quota. */
            fun namesDailyQuota(text: String): Boolean = DAILY.containsMatchIn(text)
        }
    }

    /** The provider is temporarily at capacity. */
    class Overloaded(message: String, cause: Throwable? = null) : LlmException(message, cause) {
        override val retryable = true
    }

    class ModelNotFound(message: String, cause: Throwable? = null) : LlmException(message, cause) {
        override val retryable = false
    }

    /** The conversation no longer fits the model's context window. */
    class ContextTooLong(message: String, cause: Throwable? = null) : LlmException(message, cause) {
        override val retryable = false
    }

    /** The provider rejected the request as malformed or unsupported. */
    class InvalidRequest(message: String, val status: Int? = null, cause: Throwable? = null) : LlmException(message, cause) {
        override val retryable = false
    }

    class Server(message: String, val status: Int, cause: Throwable? = null) : LlmException(message, cause) {
        override val retryable = true
    }

    /** Could not reach the provider: DNS, connection, TLS, or a dropped stream. */
    class Network(message: String, cause: Throwable? = null) : LlmException(message, cause) {
        override val retryable = true
    }

    class Timeout(message: String, cause: Throwable? = null) : LlmException(message, cause) {
        override val retryable = true
    }

    /** The provider answered with something this adapter could not interpret. */
    class MalformedResponse(message: String, cause: Throwable? = null) : LlmException(message, cause) {
        override val retryable = false
    }

    /** The adapter or model cannot do what was asked (for example images to a text-only model). */
    class Unsupported(message: String) : LlmException(message) {
        override val retryable = false
    }

    /** The framework refused to make the call: local-only mode, a disabled model or provider, or no model. */
    class BlockedByPolicy(message: String) : LlmException(message) {
        override val retryable = false
    }

    /**
     * The framework refused the call because today's limit of model answers, tokens or money is
     * reached (Phase 6, K11, rule 6). [message] is the sentence for the owner, e.g. "Today's limit
     * of 200 model answers is reached. It starts again at midnight, or you can raise it in
     * Settings > Daily limits." Not retryable today: a running objective pauses instead of failing.
     */
    class DailyLimitReached(message: String) : LlmException(message) {
        override val retryable = false
    }
}

/** A model as reported by a provider's listing. */
data class RemoteModel(
    val name: String,
    val displayName: String? = null,
    /** Capabilities the provider reported. Null when it reports none; they are then estimated from the name. */
    val capabilities: ModelCapabilities? = null,
    val ownedBy: String? = null,
    val sizeBytes: Long? = null,
)

data class ProviderHealth(
    val status: ConnectionStatus,
    /** A sentence for the user when the status is not ONLINE. */
    val message: String? = null,
    val latencyMs: Long? = null,
)

/** A provider record together with its secret, resolved from the vault at the moment of use. */
class ProviderConnection(val provider: ModelProvider, val apiKey: String?) {
    override fun toString(): String = "ProviderConnection(${provider.name})"
}

/** One provider, ready to use. Created per connection; cheap to create and to close. */
interface ProviderAdapter {
    /** Never throws: every failure becomes a status and a message. */
    suspend fun checkHealth(): ProviderHealth

    /** @throws LlmException */
    suspend fun listModels(): List<RemoteModel>

    val client: LlmClient

    fun close() {}
}

/** Creates adapters for one [ProviderKind]. Registering a factory is all it takes to add a provider type. */
interface ProviderAdapterFactory {
    val kind: ProviderKind

    fun create(connection: ProviderConnection): ProviderAdapter
}

/** Who is making a model call and why; used for the activity log and usage accounting. */
data class CallContext(
    val agentId: AgentId? = null,
    val taskId: TaskId? = null,
    val rootTaskId: TaskId? = null,
    /** What is being sent, in words, for the "information left the device" record. */
    val purpose: String = "prompt and conversation",
    /** The owner's chat the call answers in, so its cost is counted per chat (Phase 6, K8); null outside a chat. */
    val conversationId: ConversationId? = null,
)

/**
 * The single door through which the framework calls models. It resolves a registered model to
 * its provider and adapter, refuses calls that policy forbids (local-only mode, disabled model
 * or provider), switches to the prompted tool protocol for models without native tool calling,
 * and records usage and any transfer of data off the device.
 */
interface ModelGateway {
    /** @throws LlmException */
    suspend fun chat(modelId: ModelId, request: ChatRequest, context: CallContext = CallContext()): ChatResponse

    fun chatStream(modelId: ModelId, request: ChatRequest, context: CallContext = CallContext()): Flow<ChatChunk>
}
