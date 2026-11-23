package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
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
    /**
     * The conversation so far, ending with the message to answer, as stored. Without a system
     * message: the runner writes it. Stored memory_search results (references) are written out
     * by the runner as the agent may read memory when the run starts.
     */
    val messages: List<ChatMessage>,
    val taskId: TaskId? = null,
    val rootTaskId: TaskId? = null,
    /** What is being sent, in words, for the record of data leaving the device. */
    val purpose: String = "prompt and conversation",
    /** The chat this turn belongs to, if any; recorded as provenance of what the agent saves to memory. */
    val conversationId: ConversationId? = null,
    /** Agents that delegated down to the task of this run, outermost first; handed to every tool call. */
    val delegationChain: List<AgentId> = emptyList(),
    /**
     * Tools offered for this run on top of the agent's own, decided by the framework, never by
     * content: the orchestrator offers the team tools inside an objective's task as the agent's
     * delegation policy allows. They are authorised like the agent's own tools.
     */
    val extraToolIds: Set<ToolId> = emptySet(),
    /** Ask the model for one JSON object as its reply. Set only for models that support it. */
    val jsonOutput: Boolean = false,
    /** Lower limits than the framework-wide ones for this run. */
    val limits: RunLimits = RunLimits(),
    /**
     * What the activity monitor shows while this run works, e.g. "Task: Compare apps". The
     * runner's own step ("Waiting for the model") is added after it. Null shows the step alone.
     */
    val activityLabel: String? = null,
)

/**
 * Limits for one run that are lower than the framework's settings: a short sub-run, or a task
 * with less time left before its deadline. Null keeps the framework limit; a value above the
 * framework limit has no effect, so a run can never be given more than the owner allowed.
 */
data class RunLimits(
    val maxModelCalls: Int? = null,
    val maxToolCalls: Int? = null,
    val timeoutMs: Long? = null,
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
    /**
     * Every message this run added to the conversation, in order: assistant messages and tool
     * results, in the form to store. A memory_search result is a reference (the query and the
     * ids it showed, no memory text), which [AgentRunner] writes out again from current memory
     * when it is in [AgentRunRequest.messages] of a later run.
     */
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
     * [usage] are set for complete assistant messages. It comes in the form to store, as in
     * [AgentRunResult.produced]: a memory_search result is a reference, not memory text.
     */
    suspend fun onMessage(message: ChatMessage, modelName: String?, usage: TokenUsage?) {}

    /** [spec] is null when the model asked for a tool that does not exist. */
    suspend fun onToolStarted(call: ToolCallRequest, spec: ToolSpec?) {}

    suspend fun onToolFinished(execution: ToolExecution) {}

    companion object {
        val NONE = object : AgentRunListener {}
    }
}
