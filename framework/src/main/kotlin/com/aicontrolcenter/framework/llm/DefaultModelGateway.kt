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
import com.aicontrolcenter.core.registry.ModelDirectory
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderDirectory
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.TokenCounts
import com.aicontrolcenter.core.usage.UsageLedger
import com.aicontrolcenter.core.usage.UsageReports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.time.ZoneId
import kotlin.time.TimeSource

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
 *  - Book-keeping (the log, the usage records and the provider's stored status) is best effort:
 *    a reply that was received is never turned into a failure because it could not be written down.
 *  - Today's limits (Phase 6, rule 6) are checked before every attempt that would reach a provider
 *    off this phone, retries and streamed calls included, through the one [DailyLimiter] every call
 *    of the process shares: a reservation is taken under its lock before the attempt is handed to
 *    the adapter, so no path of this class sends a request the limits did not allow. A refusal is
 *    [LlmException.DailyLimitReached] with the owner's sentence; nothing is sent.
 *  - Every call is timed on [timeSource] (time to the first chunk and to the whole answer of the
 *    attempt that answered, waits between attempts) and recorded in [usage] with the tokens the
 *    provider reported (Phase 6, K11). The records hold numbers, ids and labels only (rule 7).
 *  - Three calls that failed for a reason that passes by itself (no connection, no answer, a server
 *    error) within five minutes mark the provider as not answering ([ProviderOutages], D28), never
 *    over a rejected key; the re-checks then look again.
 *  - A provider that asks to slow down (429) is waited for as it asks ([RetryPolicy]); after the
 *    last attempt the failure stays [LlmException.RateLimited], so the caller waits rather than fails.
 *
 * @param usage where every call is recorded (Room in the app). Only adds; nothing here deletes usage (F4).
 * @param limiter today's limits; built from read-only usage reports, read-only prices and the
 *   read-only settings by default, so a gateway can never be built without one.
 * @param timeSource measures answer times; tests pass the test scheduler's.
 * @param zone the owner's zone, for "Not answering since 10:42".
 */
class DefaultModelGateway(
    private val providers: ProviderDirectory,
    private val models: ModelDirectory,
    private val settings: SettingsView,
    private val connections: ProviderConnections,
    activity: ActivityRecorder,
    private val clock: Clock,
    private val retry: RetryPolicy = RetryPolicy(),
    usage: UsageLedger = UsageLedger.None,
    private val limiter: DailyLimiter = DailyLimiter(UsageReports.None, ModelPrices.None, settings, clock),
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ModelGateway {

    private val journal = ModelCallJournal(activity, usage)

    /** Who has stopped answering (D28). */
    private val outages = ProviderOutages()

    /** Everything needed to carry out one call that policy allows. */
    private class Route(val call: ModelCall, val lease: ProviderLease, val client: LlmClient, val request: ChatRequest)

    override suspend fun chat(modelId: ModelId, request: ChatRequest, context: CallContext): ChatResponse {
        val route = open(modelId, request, context, streamed = false)
        val call = route.call
        try {
            while (true) {
                beforeAttempt(route, modelId)
                call.attempts++
                call.sent = true
                val started = timeSource.markNow()
                call.attemptStarted(started)
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
                if (failure == null) {
                    call.answeredIn = started.elapsedNow()
                    return succeed(call, response!!)
                }
                call.attemptFailed(failure)
                val pause = retry.delayBeforeRetry(call.attempts, failure) ?: throw fail(call, failure)
                waitBeforeRetry(call, pause)
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
                beforeAttempt(route, modelId)
                call.attempts++
                call.sent = true
                val started = timeSource.markNow()
                call.attemptStarted(started)
                var passedOn = false
                var completed: ChatResponse? = null
                var thrown: Throwable? = null
                route.client.chatStream(route.request)
                    .catch { thrown = it }
                    .collect { chunk ->
                        if (call.firstChunkAt == null) call.firstChunkAt = started.elapsedNow()
                        if (chunk is ChatChunk.Text && call.firstTextAt == null) call.firstTextAt = started.elapsedNow()
                        when {
                            completed != null -> Unit
                            chunk is ChatChunk.Completed -> {
                                completed = chunk.response
                                call.answeredIn = started.elapsedNow()
                            }
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
                call.attemptFailed(failure)
                val pause = (if (passedOn) null else retry.delayBeforeRetry(call.attempts, failure)) ?: throw fail(call, failure)
                waitBeforeRetry(call, pause)
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
        providerRefusal(read)?.let { refuse(modelId, context, it, model, read) }

        val prompted = needsPromptedTools(model, request)
        val wireRequest = request.copy(
            model = model.modelName,
            maxOutputTokens = clampOutput(request.maxOutputTokens, model.capabilities.maxOutputTokens),
        )
        val requestBytes = wireRequest.approximateBytes()
        fun callTo(provider: ModelProvider): ModelCall {
            val locality = provider.effectiveLocality
            val egress = if (locality.leavesDevice) {
                EgressInfo(provider.destinationHost(), locality, provider.id, context.purpose, requestBytes)
            } else {
                null
            }
            return ModelCall(
                model, provider, context, startedAt = clock.now(), streamed = streamed, promptedTools = prompted,
                messageCount = request.messages.size, toolCount = request.tools.size, egress = egress, requestBytes = requestBytes,
            )
        }
        val lease = try {
            connections.acquire(read)
        } catch (e: LlmException) {
            throw fail(callTo(read), e)
        }
        // Nothing has been sent yet: a refusal here costs nothing but the adapter that was built.
        providerRefusal(lease.provider)?.let { refuse(modelId, context, it, model, lease.provider) }
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
        val call = route.call
        settleLimits(call, used = null)
        journal.retryBlocked(call, reason, elapsed(call), clock.now())
        throw LlmException.BlockedByPolicy("$reason The call was not repeated.")
    }

    /**
     * Runs before every attempt: a retry is confirmed first ([confirmRetry]), then today's limits are asked for a
     * place (rule 6). The first attempt of a call reserves one; a retry checks again under the call's own
     * reservation. Where the call goes is the lease's record, so the limits apply to where the request really goes.
     * A refusal ends the call before anything more is sent.
     */
    private suspend fun beforeAttempt(route: Route, modelId: ModelId) {
        val call = route.call
        if (call.attempts > 0) confirmRetry(route, modelId)
        call.ticket = try {
            // The first attempt reserves the call's estimated tokens too, so calls in flight together count (SEC-W2A-4).
            val estimate = if (call.ticket == null) estimate(route.request) else null
            limiter.reserve(call.model.id, route.lease.provider.effectiveLocality, held = call.ticket, estimate = estimate)
        } catch (refused: LlmException.DailyLimitReached) {
            val sentence = refused.message.orEmpty()
            if (call.attempts == 0) {
                journal.limitReached(call, sentence, elapsed(call), clock.now())
            } else {
                settleLimits(call, used = null)
                journal.retryBlocked(call, sentence, elapsed(call), clock.now())
            }
            throw refused
        }
    }

    /** What [request] is expected to use: its estimated input, and the answer's room (or [DailyLimiter.DEFAULT_ANSWER_ESTIMATE]). */
    private fun estimate(request: ChatRequest): TokenCounts = TokenCounts(
        TokenEstimator.tokens(request.messages, request.tools).toLong().coerceAtLeast(0),
        (request.maxOutputTokens?.toLong() ?: DailyLimiter.DEFAULT_ANSWER_ESTIMATE).coerceAtLeast(0),
    )

    /** Waits [pauseMs] before the next attempt, as the provider or the retry policy asked. */
    private suspend fun waitBeforeRetry(call: ModelCall, pauseMs: Long) {
        call.retryWaitMs += pauseMs
        delay(pauseMs)
    }

    /** Ends the call's place in today's limits: it counts, with the tokens reported (none when it failed). Never throws. */
    private suspend fun settleLimits(call: ModelCall, used: TokenCounts?, at: Long = clock.now()) {
        call.ticket?.let { limiter.settle(it, call.model.id, used, at) }
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

    private suspend fun refuse(
        modelId: ModelId,
        context: CallContext,
        reason: String,
        model: ModelDescriptor? = null,
        provider: ModelProvider? = null,
    ): Nothing {
        bestEffort { journal.blocked(modelId, context, reason, clock.now(), model, provider) }
        throw LlmException.BlockedByPolicy(reason)
    }

    private suspend fun succeed(call: ModelCall, response: ChatResponse): ChatResponse {
        val at = clock.now()
        settleLimits(call, response.usage?.let { TokenCounts(it.inputTokens.coerceAtLeast(0), it.outputTokens.coerceAtLeast(0), it.cachedInputTokens.coerceAtLeast(0)) }, at)
        bestEffort { outages.answered(call.provider.id) }
        bestEffort {
            if (currentStatus(call.provider) != ConnectionStatus.ONLINE) {
                connections.storeStatus(call.provider.id, ConnectionStatus.ONLINE, null)
            }
        }
        bestEffort { journal.succeeded(call, response, elapsed(call), at) }
        return response
    }

    /** Writes the event of a call that ended without a result or a failure of its own. */
    private suspend fun closeUnrecorded(call: ModelCall) {
        withContext(NonCancellable) {
            // A call that sent a request counts towards today's limits however it ended.
            settleLimits(call, used = null)
            if (!call.recorded) bestEffort { journal.cancelled(call, elapsed(call), clock.now()) }
        }
    }

    /** Logs a final failure and returns it for the caller to throw. */
    private suspend fun fail(call: ModelCall, error: LlmException): LlmException {
        val at = clock.now()
        settleLimits(call, used = null, at)
        bestEffort {
            if (error is LlmException.Authentication) {
                connections.storeStatus(call.provider.id, ConnectionStatus.AUTH_ERROR, error.message)
            }
        }
        bestEffort { markIfNotAnswering(call, error, at) }
        bestEffort { journal.failed(call, error, elapsed(call), at) }
        return error
    }

    /**
     * D28: the third call in five minutes that found [call]'s provider not answering marks it OFFLINE, "Not
     * answering since 10:42" (the first of them), unless its key was rejected (that is the owner's to fix, and stays
     * shown) or it is already marked. Only calls that sent a request count.
     */
    private suspend fun markIfNotAnswering(call: ModelCall, error: LlmException, at: Long) {
        if (call.attempts == 0) return
        val since = outages.failed(call.provider.id, error, at) ?: return
        connections.storeStatusUnless(
            call.provider.id, ConnectionStatus.OFFLINE, ProviderOutages.sentence(since, zone()),
            keep = setOf(ConnectionStatus.AUTH_ERROR, ConnectionStatus.OFFLINE),
        )
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
