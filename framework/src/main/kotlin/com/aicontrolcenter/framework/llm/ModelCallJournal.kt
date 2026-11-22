package com.aicontrolcenter.framework.llm

import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelProvider
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    /** Set only for providers off this device; attached to the event once a request went out. */
    val egress: EgressInfo?,
) {
    var attempts = 0

    /** True from the moment the adapter was asked to send; before that nothing has left the device. */
    var sent = false

    /** True once this call's event has been handed to the log, so it is never written twice. */
    var recorded = false
}

/**
 * Writes the one MODEL_CALL event each gateway call produces. Events never contain message
 * content or secrets: only who called which model, what it cost and where the data went.
 */
internal class ModelCallJournal(private val activity: ActivityRecorder) {

    suspend fun blocked(modelId: ModelId, context: CallContext, reason: String) {
        activity.record(
            ActivityType.MODEL_CALL, "Model call blocked", reason, severity = Severity.WARNING,
            agentId = context.agentId, taskId = context.taskId, rootTaskId = context.rootTaskId,
            data = buildJsonObject {
                put("modelId", modelId.value)
                put("outcome", "blocked")
            },
        )
    }

    suspend fun succeeded(call: ModelCall, response: ChatResponse, durationMs: Long) {
        val requested = call.model.modelName
        val answered = response.modelName
        val substituted = answered.isNotBlank() && answered != requested
        // A dated snapshot of the requested name ("gpt-x" answered by "gpt-x-2026-01-01") is routine;
        // an unrelated model answering is something the owner should notice.
        val unrelated = substituted && !answered.startsWith(requested) && !requested.startsWith(answered)
        val usage = response.usage
        val detail = buildString {
            append(call.model.displayName).append(" via ").append(call.provider.name)
            if (usage != null) append(" · ${usage.inputTokens} in / ${usage.outputTokens} out tokens")
            append(" · ").append(durationMs).append(" ms")
            if (call.attempts > 1) append(" · ${call.attempts} attempts")
            if (substituted) append(" · answered by $answered, not the requested $requested")
        }
        record(call, "Model call", detail, if (unrelated) Severity.WARNING else Severity.INFO, durationMs) {
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

    suspend fun failed(call: ModelCall, error: LlmException, durationMs: Long) {
        val detail = "${call.model.displayName} via ${call.provider.name}: ${error.message}"
        record(call, "Model call failed", detail, Severity.ERROR, durationMs) {
            put("outcome", "failed")
            put("error", error::class.simpleName)
            put("retryable", error.retryable)
        }
    }

    suspend fun cancelled(call: ModelCall, durationMs: Long) {
        val detail = "${call.model.displayName} via ${call.provider.name}: stopped before the reply was complete"
        record(call, "Model call cancelled", detail, Severity.INFO, durationMs) { put("outcome", "cancelled") }
    }

    private suspend fun record(call: ModelCall, title: String, detail: String, severity: Severity, durationMs: Long, extra: JsonObjectBuilder.() -> Unit) {
        if (call.recorded) return
        call.recorded = true
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
                extra()
            },
            egress = call.egress.takeIf { call.sent },
        )
    }
}
