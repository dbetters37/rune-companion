package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.UsageLedger
import com.aicontrolcenter.core.usage.UsageSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.TimeMark

/** Facts about one gateway call, gathered while it runs and written to the log when it ends. */
internal class ModelCall(
    val model: ModelDescriptor,
    val provider: ModelProvider,
    val context: CallContext,
    val startedAt: Long,
    val streamed: Boolean,
    val promptedTools: Boolean,
    val messageCount: Int,
    val toolCount: Int,
    /**
     * Set only for providers off this device; attached to the event once a request went out.
     * Its size is that of one attempt: the event reports it times the attempts that were sent.
     */
    val egress: EgressInfo?,
    /** Roughly how many bytes one attempt sends, wherever it goes (usage records count every attempt). */
    val requestBytes: Long = 0,
) {
    /** Attempts handed to the adapter, each of which sent the whole request. */
    var attempts = 0

    /** True from the moment the adapter was asked to send; before that nothing has left the device. */
    var sent = false

    /** True once this call's event has been handed to the log, so it is never written twice. */
    var recorded = false

    // ---- Phase 6 (WP4 item 1): timings on the gateway's time source, and what the provider said when it failed ----

    /** When the latest attempt was handed to the adapter. */
    var attemptStartedAt: TimeMark? = null

    /** From the start of the latest attempt to its first chunk of any kind (streamed calls only). */
    var firstChunkAt: Duration? = null

    /** From the start of the latest attempt to its first text. */
    var firstTextAt: Duration? = null

    /** From the start of the attempt that answered to its last byte. */
    var answeredIn: Duration? = null

    /** Time spent waiting between attempts. */
    var retryWaitMs: Long = 0

    /** The HTTP status of the latest failure, when the provider's answer carried one. */
    var httpStatus: Int? = null

    /** How long the provider last asked to wait (Retry-After). */
    var retryAfterMs: Long? = null

    /** Answers that said "slow down" (429): each one is a wait, the gateway's or, after the last attempt, the caller's. */
    var rateLimitedWaits = 0

    /** The failure of the latest attempt. */
    var lastFailure: LlmException? = null

    /** The call's place in today's limits, from its first attempt (rule 6). */
    var ticket: DailyLimiter.Ticket? = null

    /** A new attempt starts: what the previous one measured no longer describes the answer. */
    fun attemptStarted(mark: TimeMark) {
        attemptStartedAt = mark
        firstChunkAt = null
        firstTextAt = null
    }

    /** Notes what a failed attempt said. */
    fun attemptFailed(error: LlmException) {
        lastFailure = error
        httpStatus = when (error) {
            is LlmException.Server -> error.status
            is LlmException.InvalidRequest -> error.status
            is LlmException.RateLimited -> HTTP_TOO_MANY_REQUESTS
            else -> null
        }
        if (error is LlmException.RateLimited) {
            rateLimitedWaits++
            retryAfterMs = error.retryAfterMs
        }
    }

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

/**
 * Writes the one MODEL_CALL event each gateway call produces, and its usage record (Phase 6, K11). Events never
 * contain message content or secrets: only who called which model, what it cost and where the data went. Usage
 * records hold numbers, ids and the labels of the model and provider only (rule 7). Both are best effort and
 * independent: a failure to write one never stops the other, and never fails the call.
 */
internal class ModelCallJournal(private val activity: ActivityRecorder, private val usage: UsageLedger = UsageLedger.None) {

    /**
     * A call the gateway refused before sending anything. [model] and [provider] are what it knew when it refused; a
     * usage record (BLOCKED) is written only when both are known.
     */
    suspend fun blocked(modelId: ModelId, context: CallContext, reason: String, at: Long, model: ModelDescriptor? = null, provider: ModelProvider? = null) {
        safely {
            activity.record(
                ActivityType.MODEL_CALL, "Model call blocked", reason, severity = Severity.WARNING,
                agentId = context.agentId, taskId = context.taskId, rootTaskId = context.rootTaskId,
                data = buildJsonObject {
                    put("modelId", modelId.value)
                    put("outcome", "blocked")
                },
            )
        }
        if (model != null && provider != null) {
            safely { usage.record(sample(model, provider, context, at, CallOutcome.BLOCKED)) }
        }
    }

    /** Refused by today's limits before its first attempt: nothing was sent. */
    suspend fun limitReached(call: ModelCall, sentence: String, durationMs: Long, at: Long) {
        val detail = "${call.model.displayName} via ${call.provider.name}: $sentence"
        record(call, "Model call blocked", detail, Severity.WARNING, durationMs, at, CallOutcome.BLOCKED) {
            put("outcome", "blocked")
            put("reason", "daily_limit")
        }
    }

    suspend fun succeeded(call: ModelCall, response: ChatResponse, durationMs: Long, at: Long) {
        val requested = call.model.modelName
        val answered = response.modelName
        // D29: a dated snapshot or an alias of the requested name is the same model; only another model is noted.
        val substituted = answered.isNotBlank() && !ModelNames.sameModel(requested, answered)
        // A model that shares the requested name's stem ("grok-4" answered by "grok-4-fast") is a variant; an
        // unrelated model answering is something the owner should notice.
        val unrelated = substituted && !answered.startsWith(requested) && !requested.startsWith(answered)
        val usage = response.usage
        val detail = buildString {
            append(call.model.displayName).append(" via ").append(call.provider.name)
            if (usage != null) append(" · ${usage.inputTokens} in / ${usage.outputTokens} out tokens")
            append(" · ").append(durationMs).append(" ms")
            if (call.attempts > 1) append(" · ${call.attempts} attempts")
            if (substituted) append(" · answered by $answered, not the requested $requested")
        }
        record(
            call, "Model call", detail, if (unrelated) Severity.WARNING else Severity.INFO, durationMs, at, CallOutcome.OK,
            response = response, substituted = substituted,
        ) {
            put("outcome", "ok")
            put("answeredModel", answered)
            put("modelSubstituted", substituted)
            put("finishReason", response.finishReason.name)
            if (usage != null) {
                put("inputTokens", usage.inputTokens)
                put("outputTokens", usage.outputTokens)
                put("cachedInputTokens", usage.cachedInputTokens)
            }
        }
    }

    suspend fun failed(call: ModelCall, error: LlmException, durationMs: Long, at: Long) {
        val detail = "${call.model.displayName} via ${call.provider.name}: ${error.message}"
        record(call, "Model call failed", detail, Severity.ERROR, durationMs, at, CallOutcome.FAILED, error = error) {
            put("outcome", "failed")
            put("error", error::class.simpleName)
            put("retryable", error.retryable)
        }
    }

    /**
     * A retry that policy or today's limits no longer allowed, or that would have gone somewhere else than the
     * attempts before it. The attempts before it were sent, so its usage record is the failure they ended with.
     */
    suspend fun retryBlocked(call: ModelCall, reason: String, durationMs: Long, at: Long) {
        val tries = if (call.attempts == 1) "1 attempt" else "${call.attempts} attempts"
        val detail = "${call.model.displayName} via ${call.provider.name}: not repeated after $tries. $reason"
        record(call, "Model call blocked", detail, Severity.WARNING, durationMs, at, CallOutcome.FAILED, error = call.lastFailure) {
            put("outcome", "blocked")
        }
    }

    /**
     * A call stopped before it ended. [afterSlowDown]: every attempt it sent was answered "slow down", and it was stopped
     * while it waited to try again; its usage record is then that failure (RATE_LIMITED), so a restarted limiter does not
     * count it as one of today's answers either ([DailyUse.counted]).
     */
    suspend fun cancelled(call: ModelCall, durationMs: Long, at: Long, afterSlowDown: Boolean = false) {
        if (afterSlowDown) {
            val detail = "${call.model.displayName} via ${call.provider.name}: stopped while waiting, after ${call.provider.name} asked the app to slow down"
            record(call, "Model call cancelled", detail, Severity.INFO, durationMs, at, CallOutcome.FAILED, error = call.lastFailure) { put("outcome", "cancelled") }
            return
        }
        val detail = "${call.model.displayName} via ${call.provider.name}: stopped before the reply was complete"
        record(call, "Model call cancelled", detail, Severity.INFO, durationMs, at, CallOutcome.CANCELLED) { put("outcome", "cancelled") }
    }

    private suspend fun record(
        call: ModelCall,
        title: String,
        detail: String,
        severity: Severity,
        durationMs: Long,
        at: Long,
        outcome: CallOutcome,
        response: ChatResponse? = null,
        substituted: Boolean = false,
        error: LlmException? = null,
        extra: JsonObjectBuilder.() -> Unit,
    ) {
        if (call.recorded) return
        call.recorded = true
        val ttftMs = call.firstChunkAt?.takeIf { call.streamed && outcome == CallOutcome.OK }?.inWholeMilliseconds
        val answerMs = if (outcome == CallOutcome.OK) (call.answeredIn?.inWholeMilliseconds ?: durationMs) else null
        safely {
            activity.record(
                ActivityType.MODEL_CALL, title, detail, severity = severity,
                agentId = call.context.agentId, taskId = call.context.taskId, rootTaskId = call.context.rootTaskId,
                data = buildJsonObject {
                    put("modelId", call.model.id.value)
                    put("requestedModel", call.model.modelName)
                    put("providerId", call.provider.id.value)
                    put("provider", call.provider.name)
                    put("durationMs", durationMs)
                    put("attempts", call.attempts)
                    put("streamed", call.streamed)
                    put("promptedTools", call.promptedTools)
                    put("messages", call.messageCount)
                    put("tools", call.toolCount)
                    answerMs?.let { put("answerMs", it) }
                    ttftMs?.let { put("ttftMs", it) }
                    call.firstTextAt?.takeIf { outcome == CallOutcome.OK }?.let { put("firstTextMs", it.inWholeMilliseconds) }
                    if (call.retryWaitMs > 0) put("retryWaitMs", call.retryWaitMs)
                    call.httpStatus?.let { put("httpStatus", it) }
                    call.retryAfterMs?.let { put("retryAfterMs", it) }
                    if (call.rateLimitedWaits > 0) put("rateLimitedWaits", call.rateLimitedWaits)
                    extra()
                },
                egress = call.egress?.takeIf { call.sent }?.let { egress ->
                    egress.copy(approxBytes = egress.approxBytes?.times(call.attempts.coerceAtLeast(1)))
                },
            )
        }
        safely {
            val failure = error ?: call.lastFailure
            // A call refused before it sent anything is BLOCKED; one that sent a request is counted by how it ended.
            val ended = if (outcome == CallOutcome.FAILED && failure == null) CallOutcome.CANCELLED else outcome
            usage.record(
                sample(
                    call.model, call.provider, call.context, at, ended,
                    errorClass = failure?.takeIf { ended == CallOutcome.FAILED }?.let(ErrorClass::of),
                    response = response, answerMs = answerMs, ttftMs = ttftMs,
                    retries = (call.attempts - 1).coerceAtLeast(0), substituted = substituted,
                    rateLimitedWaits = call.rateLimitedWaits, bytesSent = call.requestBytes * call.attempts,
                ),
            )
        }
    }

    private fun sample(
        model: ModelDescriptor,
        provider: ModelProvider,
        context: CallContext,
        at: Long,
        outcome: CallOutcome,
        errorClass: ErrorClass? = null,
        response: ChatResponse? = null,
        answerMs: Long? = null,
        ttftMs: Long? = null,
        retries: Int = 0,
        substituted: Boolean = false,
        rateLimitedWaits: Int = 0,
        bytesSent: Long = 0,
    ): ModelCallSample {
        val used = response?.usage
        return ModelCallSample(
            at = at, modelId = model.id, modelLabel = model.displayName, providerId = provider.id, providerLabel = provider.name,
            locality = provider.effectiveLocality, agentId = context.agentId, taskId = context.taskId, rootTaskId = context.rootTaskId,
            conversationId = context.conversationId,
            source = when {
                context.taskId != null || context.rootTaskId != null -> UsageSource.OBJECTIVE
                context.conversationId != null -> UsageSource.CHAT
                else -> UsageSource.OTHER
            },
            outcome = outcome, errorClass = errorClass,
            inputTokens = used?.inputTokens?.coerceAtLeast(0), outputTokens = used?.outputTokens?.coerceAtLeast(0),
            cachedInputTokens = used?.cachedInputTokens?.coerceIn(0, used.inputTokens.coerceAtLeast(0)),
            answerMs = answerMs?.coerceAtLeast(0), ttftMs = ttftMs?.coerceAtLeast(0),
            retries = retries, substituted = substituted, truncated = response?.finishReason == FinishReason.LENGTH,
            rateLimitedWaits = rateLimitedWaits, bytesSent = bytesSent.coerceAtLeast(0),
        )
    }

    /** Book-keeping must never fail a call: see the class comment. */
    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing to do: the call's result stands.
        }
    }
}
