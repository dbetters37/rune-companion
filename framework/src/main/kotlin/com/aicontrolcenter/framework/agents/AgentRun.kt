package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.tools.ToolExecution

/** One turn for an agent to work on. */
data class AgentRunRequest(
    val agent: AgentProfile,
    /** The model to think with; the caller has already resolved availability and fallbacks. */
    val modelId: ModelId,
    /** The conversation so far, ending with the message to answer. Without a system message: the runner writes it. */
    val messages: List<ChatMessage>,
    val taskId: TaskId? = null,
    val rootTaskId: TaskId? = null,
    /** What is being sent, in words, for the record of data leaving the device. */
    val purpose: String = "prompt and conversation",
    /** The chat this turn belongs to, if any; recorded as provenance of what the agent saves to memory. */
    val conversationId: ConversationId? = null,
)

/** Why a run stopped. */
enum class RunEnd {
    /** The model gave its answer. */
    COMPLETED,

    /** The answer was cut off at the model's output limit; what there is of it is returned. */
    TRUNCATED,

    /** The provider declined or filtered the reply. */
    REFUSED,
    MODEL_CALL_LIMIT,
    TOOL_CALL_LIMIT,
    TIMED_OUT,

    /** A model call failed; see [AgentRunResult.error]. */
    FAILED,

    /**
     * The owner stopped the agent: it was switched off or deleted while the turn was running.
     * The run ends at the next model or tool call; [AgentRunResult.notice] says which it was.
     */
    STOPPED_BY_OWNER,
}

data class AgentRunResult(
    val end: RunEnd,
    /** The agent's last words: the answer when [end] is COMPLETED, otherwise whatever it had written. May be empty. */
    val text: String,
    /** For every ending but COMPLETED: one sentence for the owner saying what happened. */
    val notice: String?,
    val error: LlmException?,
    /** Every message this run added to the conversation, in order: assistant messages and tool results. */
    val produced: List<ChatMessage>,
    val usage: TokenUsage,
    val modelCalls: Int,
    val toolCalls: Int,
    /** The model that answered last, as the provider reported it. */
    val modelName: String?,
)

/**
 * Follows a run as it happens. Called from the run's own coroutine, in order; a slow listener
 * slows the run down. After cancellation, calls that record what was produced still arrive.
 */
interface AgentRunListener {
    /** Model call number [number] (1 is the first) is about to be made. */
    suspend fun onModelCall(number: Int) {}

    suspend fun onTextDelta(delta: String) {}

    suspend fun onReasoningDelta(delta: String) {}

    /**
     * A message joined the conversation: a complete assistant message, an assistant message
     * cut short because the run was stopped mid-reply, or a tool result. [modelName] and
     * [usage] are set for complete assistant messages.
     */
    suspend fun onMessage(message: ChatMessage, modelName: String?, usage: TokenUsage?) {}

    /** [spec] is null when the model asked for a tool that does not exist. */
    suspend fun onToolStarted(call: ToolCallRequest, spec: ToolSpec?) {}

    suspend fun onToolFinished(execution: ToolExecution) {}

    companion object {
        val NONE = object : AgentRunListener {}
    }
}
