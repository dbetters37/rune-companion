package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskUsage
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.framework.agents.AgentRunListener
import com.aicontrolcenter.framework.agents.AgentRunRequest
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.RunEnd
import com.aicontrolcenter.framework.agents.RunLimits
import com.aicontrolcenter.framework.tools.ToolExecution

/** Whether an agent can take a step now, with what it would run on. */
internal sealed interface Readiness {
    data class Ready(
        val agent: AgentProfile,
        val modelId: ModelId,
        val modelName: String,
        val providerName: String,
        /** Its prompts are sent off this device. Shown on the task, so the owner sees where the work goes. */
        val leavesDevice: Boolean,
        val jsonCapable: Boolean,
    ) : Readiness {
        /** Where this agent's work goes, in words for the task's status line. */
        val destination: String
            get() = if (leavesDevice) "using $modelName; its prompts go to $providerName, off this device" else "using $modelName on this device"
    }

    data class NotReady(val reason: String) : Readiness
}

internal enum class StepEnd { SUCCESS, FAILED, TIMED_OUT }

/** How one agent run for a task ended. */
internal data class StepResult(
    val end: StepEnd,
    /** The answer (on success), or whatever the agent had written. */
    val text: String,
    /** A sentence on why the run did not succeed. */
    val failure: String?,
    /** Set when a per-task model or tool call limit ended the run, with the runner's notice. */
    val limitHit: String?,
    val usage: TaskUsage,
    /** What the run added to the conversation, for a follow-up run (the planning retry) to build on. */
    val produced: List<ChatMessage>,
    val modelName: String?,
) {
    val ok: Boolean get() = end == StepEnd.SUCCESS
}

/**
 * Runs one agent for one step of an objective: the work on a task, a review, the plan, the
 * synthesis, an answer to a teammate. The brief is stored in the task's conversation, the run
 * is recorded there as it happens, and the run's ending becomes a [StepResult]; nothing here
 * throws except cancellation. A run ended by a per-task limit is reported as a limit reached.
 */
internal class StepRunner(
    private val runner: AgentRunner,
    private val directory: RegistryDirectory,
    private val transcripts: TaskTranscripts,
    private val journal: ObjectiveJournal,
) {
    suspend fun prepare(agentId: AgentId): Readiness {
        val snapshot = directory.snapshot()
        val entry = snapshot.agentsById[agentId] ?: return Readiness.NotReady("the agent no longer exists")
        return when (val availability = entry.availability) {
            is AgentAvailability.Disabled -> Readiness.NotReady("${entry.profile.name} is switched off")
            is AgentAvailability.Unavailable -> Readiness.NotReady("${entry.profile.name} cannot run right now. ${availability.detail}")
            is AgentAvailability.Ready -> {
                val model = snapshot.modelsById[availability.modelId]
                    ?: return Readiness.NotReady("the model of ${entry.profile.name} is no longer registered")
                val provider = snapshot.provider(model)
                Readiness.Ready(
                    agent = entry.profile, modelId = model.id, modelName = model.displayName,
                    providerName = provider?.name ?: "its provider",
                    leavesDevice = provider?.effectiveLocality?.leavesDevice ?: false,
                    jsonCapable = model.capabilities.jsonOutput,
                )
            }
        }
    }

    suspend fun run(
        ready: Readiness.Ready,
        task: TaskRecord,
        /** Where the run is recorded; null records nothing (a short answer to a teammate, kept in the messages instead). */
        conversationId: ConversationId?,
        prompt: String,
        history: List<ChatMessage> = emptyList(),
        json: Boolean = false,
        limits: RunLimits = RunLimits(),
        extraTools: Set<ToolId> = emptySet(),
        label: String = "Task: ${task.title}",
        purpose: String = "task brief and results of other agents",
        /** Called after every tool call of the run. */
        onTool: suspend (ToolExecution) -> Unit = {},
    ): StepResult {
        conversationId?.let { transcripts.user(it, prompt) }
        val request = AgentRunRequest(
            agent = ready.agent, modelId = ready.modelId, messages = history + ChatMessage.user(prompt),
            taskId = task.id, rootTaskId = task.rootId, purpose = purpose, conversationId = conversationId,
            delegationChain = task.delegationChain, extraToolIds = extraTools,
            // Only where the model supports it: the request asks, the briefs say it in words as well.
            jsonOutput = json && ready.jsonCapable, limits = limits, activityLabel = label,
        )
        val recorder = conversationId?.let(transcripts::recorder)
        val listener = object : AgentRunListener {
            override suspend fun onMessage(message: ChatMessage, modelName: String?, usage: TokenUsage?) {
                recorder?.onMessage(message, modelName, usage)
            }

            override suspend fun onToolFinished(execution: ToolExecution) = onTool(execution)
        }
        val run = runner.run(request, listener)
        val usage = TaskUsage(run.modelCalls, run.toolCalls, run.usage.inputTokens, run.usage.outputTokens)
        val text = run.text.trim()
        fun failed(end: StepEnd, why: String, limit: String? = null) =
            StepResult(end, text, why, limit, usage, run.produced, run.modelName)
        val result = when (run.end) {
            RunEnd.COMPLETED ->
                if (text.isEmpty()) failed(StepEnd.FAILED, "${ready.agent.name} returned an empty answer.")
                else StepResult(StepEnd.SUCCESS, text, null, null, usage, run.produced, run.modelName)
            RunEnd.TRUNCATED ->
                if (text.isEmpty()) failed(StepEnd.FAILED, run.notice ?: "The answer was cut off.")
                else StepResult(StepEnd.SUCCESS, "$text\n\n[$TRUNCATED_NOTE]", null, null, usage, run.produced, run.modelName)
            RunEnd.TIMED_OUT -> failed(StepEnd.TIMED_OUT, run.notice ?: "The task ran out of time.")
            RunEnd.MODEL_CALL_LIMIT, RunEnd.TOOL_CALL_LIMIT -> failed(StepEnd.FAILED, run.notice ?: "A limit stopped the task.", run.notice)
            RunEnd.FAILED -> failed(StepEnd.FAILED, "${ready.agent.name} could not work: ${run.notice ?: "the model call failed."}")
            RunEnd.REFUSED, RunEnd.STOPPED_BY_OWNER -> failed(StepEnd.FAILED, run.notice ?: "The run was stopped.")
        }
        val failure = result.failure
        if (failure != null && conversationId != null) transcripts.note(conversationId, failure)
        // A per-task limit (model calls, tool calls, time) ended the run: the owner sees it as a limit, whatever the step was.
        val limit = result.limitHit ?: failure?.takeIf { result.end == StepEnd.TIMED_OUT }
        limit?.let { journal.limitReached(task.rootId, task, "\"${task.title}\": $it") }
        return result
    }

    companion object {
        const val TRUNCATED_NOTE = "The answer was cut off at the model's output limit."
    }
}
