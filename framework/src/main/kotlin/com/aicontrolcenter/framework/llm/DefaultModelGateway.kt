package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.LlmClient
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ModelRepository
import com.aicontrolcenter.core.registry.ProviderRepository
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * The single door to every model. See [ModelGateway] for what it promises; the decisions that
 * are not obvious from there:
 *
 *  - Policy is checked on every call against the registry as it is now, so disabling a model
 *    or switching on local-only mode takes effect immediately, even mid-turn. A retry is a new
 *    sending of the prompt and is checked again in full: model, provider, local-only mode and
 *    the connection lease are re-read before every attempt, and if anything about where or how
 *    the call would go has changed (address, kind, headers, key), it is not sent again.
 *  - Where a call goes, and with which key, is decided by the record its lease was built from
 *    ([ProviderLease.provider]), not by an earlier read that an edit may have overtaken; policy
 *    is applied to that record too, and the call is logged as going where it says.
 *  - A streamed call is retried only while nothing has been passed on: once text has been
 *    shown, repeating the call would show it twice.
 *  - Exactly one activity event is written per call, whether it succeeded, failed, was refused
 *    or was abandoned (cancelled, or the consumer of the stream stopped), and it carries egress
 *    information from the moment a request went out, because a call that did not finish may
 *    still have delivered the prompt.
 *  - Book-keeping (the log and the provider's stored status) is best effort: a reply that was
 *    received is never turned into a failure because it could not be written down.
 */
class DefaultModelGateway(
    private val providers: ProviderRepository,
    private val models: ModelRepository,
    private val settings: SettingsView,
    private val connections: ProviderConnections,
    activity: ActivityRecorder,
    private val clock: Clock,
    private val retry: RetryPolicy = RetryPolicy(),
) : ModelGateway {

    private val journal = ModelCallJournal(activity)

    /** Everything needed to carry out one call that policy allows. */
    private class Route(val call: ModelCall, val lease: ProviderLease, val client: LlmClient, val request: ChatRequest)

    override suspend fun chat(modelId: ModelId, request: ChatRequest, context: CallContext): ChatResponse {
        val route = open(modelId, request, context, streamed = false)
        val call = route.call
        try {
            while (true) {
                if (call.attempts > 0) confirmRetry(route, modelId)
                call.attempts++
                call.sent = true
                var response: ChatResponse? = null
                val failure: LlmException? = try {
                    response = route.client.chat(route.request)
                    null
                } catch (e: CancellationException) {
                    // Ours: let it through. Anything else is a time-out that leaked out of the adapter.
                    currentCoroutineContext().ensureActive()
                    e.asLlmException(call.provider.name, route.lease)
                } catch (e: Exception) {
                    e.asLlmException(call.provider.name, route.lease)
                }
                if (failure == null) return succeed(call, response!!)
                val pause = retry.delayBeforeRetry(call.attempts, failure) ?: throw fail(call, failure)
                delay(pause)
            }
        } finally {
            closeUnrecorded(call)
        }
    }

    override fun chatStream(modelId: ModelId, request: ChatRequest, context: CallContext): Flow<ChatChunk> = flow {
        val route = open(modelId, request, context, streamed = true)
        val call = route.call
        try {
            while (true) {
                if (call.attempts > 0) confirmRetry(route, modelId)
                call.attempts++
                call.sent = true
                var passedOn = false
                var completed: ChatResponse? = null
                var thrown: Throwable? = null
                route.client.chatStream(route.request)
                    .catch { thrown = it }
                    .collect { chunk ->
                        when {
                            completed != null -> Unit
                            chunk is ChatChunk.Completed -> completed = chunk.response
                            else -> {
                                passedOn = true
                                emit(chunk)
                            }
                        }
                    }
                val response = completed
                val failure = thrown?.asLlmException(call.provider.name, route.lease)
                    ?: if (response == null) LlmException.MalformedResponse("${call.provider.name} ended the reply without finishing it.") else null
                if (failure == null) {
                    emit(ChatChunk.Completed(succeed(call, response!!)))
                    return@flow
                }
                val pause = (if (passedOn) null else retry.delayBeforeRetry(call.attempts, failure)) ?: throw fail(call, failure)
                delay(pause)
            }
        } finally {
            closeUnrecorded(call)
        }
    }

    /**
     * Applies policy and prepares the call. Every refusal is logged and thrown as [LlmException.BlockedByPolicy].
     *
     * The provider record is read here to refuse early, but the call is decided by the record
     * the lease was built from: an edit may land between this read and [ProviderConnections.acquire],
     * and the lease pairs the key with the record it was stored for. So every provider refusal is
     * applied again to that record, and the call is logged as going where that record says.
     */
    private suspend fun open(modelId: ModelId, request: ChatRequest, context: CallContext, streamed: Boolean): Route {
        val model = models.get(modelId) ?: refuse(modelId, context, "The model assigned here is no longer registered.")
        if (!model.enabled) refuse(modelId, context, "Model ${model.displayName} is disabled.")
        val read = providers.get(model.providerId)
            ?: refuse(modelId, context, "The provider of ${model.displayName} is no longer registered.")
        providerRefusal(read)?.let { refuse(modelId, context, it) }

        val prompted = needsPromptedTools(model, request)
        val wireRequest = request.copy(
            model = model.modelName,
            maxOutputTokens = clampOutput(request.maxOutputTokens, model.capabilities.maxOutputTokens),
        )
        fun callTo(provider: ModelProvider): ModelCall {
            val locality = provider.effectiveLocality
            val egress = if (locality.leavesDevice) {
                EgressInfo(provider.destinationHost(), locality, provider.id, context.purpose, wireRequest.approximateBytes())
            } else {
                null
            }
            return ModelCall(
                model, provider, context, startedAt = clock.now(), streamed = streamed, promptedTools = prompted,
                messageCount = request.messages.size, toolCount = request.tools.size, egress = egress,
            )
        }
        val lease = try {
            connections.acquire(read)
        } catch (e: LlmException) {
            throw fail(callTo(read), e)
        }
        // Nothing has been sent yet: a refusal here costs nothing but the adapter that was built.
        providerRefusal(lease.provider)?.let { refuse(modelId, context, it) }
        val client = if (prompted) PromptedToolsClient(lease.adapter.client) else lease.adapter.client
        return Route(callTo(lease.provider), lease, client, wireRequest)
    }

    /** Why policy forbids calling [provider] now, or null when it allows it. */
    private suspend fun providerRefusal(provider: ModelProvider): String? = when {
        !provider.enabled -> "Provider ${provider.name} is disabled."
        provider.effectiveLocality.leavesDevice && settings.get().localOnlyMode ->
            "Local-only mode is on and ${provider.name} would send data off this device."
        else -> null
    }

    /**
     * Runs before every attempt after the first. The pause before a retry can last seconds, and
     * the owner may have switched on local-only mode, disabled or removed the model or provider,
     * or changed its address or key in the meantime: each of [open]'s refusals is applied again,
     * and the lease is acquired again so that a changed key or record shows. The retry goes out
     * only to the same endpoint, with the same key, through the same adapter; otherwise the call
     * ends here as blocked and its event says why.
     */
    private suspend fun confirmRetry(route: Route, modelId: ModelId) {
        val reason = retryRefusal(route, modelId) ?: return
        bestEffort { journal.retryBlocked(route.call, reason, elapsed(route.call)) }
        throw LlmException.BlockedByPolicy("$reason The call was not repeated.")
    }

    private suspend fun retryRefusal(route: Route, modelId: ModelId): String? {
        val before = route.call
        val model = models.get(modelId) ?: return "The model assigned here is no longer registered."
        if (!model.enabled) return "Model ${model.displayName} is disabled."
        val provider = providers.get(model.providerId) ?: return "The provider of ${model.displayName} is no longer registered."
        providerRefusal(provider)?.let { return it }
        val locality = provider.effectiveLocality
        val changed = "The settings of ${provider.name} changed while the call was waiting to be repeated."
        val sameModel = model.providerId == before.model.providerId && model.modelName == before.model.modelName &&
            model.capabilities == before.model.capabilities
        val old = before.provider
        val sameEndpoint = provider.kind == old.kind && provider.baseUrl == old.baseUrl && provider.extraHeaders == old.extraHeaders &&
            provider.apiKeyAlias == old.apiKeyAlias && locality == old.effectiveLocality
        if (!sameModel || !sameEndpoint) return changed
        val lease = try {
            connections.acquire(provider)
        } catch (e: LlmException) {
            return e.message ?: changed
        }
        // The cache hands out the same lease only while record and key are unchanged.
        if (lease !== route.lease) return changed
        return providerRefusal(lease.provider)
    }

    /**
     * A model without native tool calling gets the prompted protocol whenever tools are offered,
     * and also when the history already contains tool calls or results: its provider would
     * reject or drop those messages, so they have to be rewritten into text either way.
     */
    private fun needsPromptedTools(model: ModelDescriptor, request: ChatRequest): Boolean =
        !model.capabilities.toolCalling &&
            (request.tools.isNotEmpty() || request.messages.any { it.role == ChatRole.TOOL || it.toolCalls.isNotEmpty() })

    private fun clampOutput(requested: Int?, modelLimit: Int?): Int? =
        if (requested != null && modelLimit != null) minOf(requested, modelLimit) else requested

    private suspend fun refuse(modelId: ModelId, context: CallContext, reason: String): Nothing {
        bestEffort { journal.blocked(modelId, context, reason) }
        throw LlmException.BlockedByPolicy(reason)
    }

    private suspend fun succeed(call: ModelCall, response: ChatResponse): ChatResponse {
        bestEffort {
            if (currentStatus(call.provider) != ConnectionStatus.ONLINE) {
                connections.storeStatus(call.provider.id, ConnectionStatus.ONLINE, null)
            }
            journal.succeeded(call, response, elapsed(call))
        }
        return response
    }

    /** Writes the event of a call that ended without a result or a failure of its own. */
    private suspend fun closeUnrecorded(call: ModelCall) {
        if (call.recorded) return
        withContext(NonCancellable) { bestEffort { journal.cancelled(call, elapsed(call)) } }
    }

    /** Logs a final failure and returns it for the caller to throw. */
    private suspend fun fail(call: ModelCall, error: LlmException): LlmException {
        bestEffort {
            if (error is LlmException.Authentication) {
                connections.storeStatus(call.provider.id, ConnectionStatus.AUTH_ERROR, error.message)
            }
            journal.failed(call, error, elapsed(call))
        }
        return error
    }

    /** The stored status now, not the one seen when the call started: a concurrent call may have changed it. */
    private suspend fun currentStatus(provider: ModelProvider): ConnectionStatus? = providers.get(provider.id)?.status

    private fun elapsed(call: ModelCall): Long = (clock.now() - call.startedAt).coerceAtLeast(0)

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // See the class comment: failing to write the log or the status must not fail the call.
        }
    }
}
