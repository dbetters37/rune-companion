package com.aicontrolcenter.framework.agents

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.CallContext
import com.aicontrolcenter.core.llm.ChatChunk
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRequest
import com.aicontrolcenter.core.llm.ChatResponse
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.FinishReason
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ModelGateway
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.llm.ToolDefinition
import com.aicontrolcenter.core.registry.AgentRepository
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.llm.RecalledMemoriesBlock
import com.aicontrolcenter.framework.llm.TokenEstimator
import com.aicontrolcenter.framework.memory.AutoRecall
import com.aicontrolcenter.framework.memory.MemoryReferences
import com.aicontrolcenter.framework.tools.ToolExecutor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs one turn of one agent: call the model, run the tools it asks for, feed the results
 * back, and repeat until it answers or a limit ends the run.
 *
 * Invariants the rest of the framework relies on:
 *  - The transcript only grows. Messages are sent back to the model exactly as they were
 *    received (including provider data), because providers validate their own earlier output.
 *  - Every tool call the model made gets exactly one result message, even when the run is
 *    stopped by a limit, a time-out or cancellation; the result then says that the tool did
 *    not run, or that it was stopped half-way. A transcript with an unanswered call would be
 *    rejected on the next turn.
 *  - Model failures and limits end the run with a result, never with an exception. Only
 *    cancellation of the caller propagates, after what was produced has been handed over.
 *  - Tool results go back to the model as tool messages and nowhere else: they cannot change
 *    limits, the tool list or the system prompt.
 *  - What the owner changes about the agent takes effect within the turn: the agent is read
 *    again before every model call and every tool call. Each call is authorised against the
 *    agent as it is then, so a tool switched off mid-turn is refused; an agent switched off or
 *    deleted mid-turn ends the run as [RunEnd.STOPPED_BY_OWNER].
 *  - Memory text never reaches the system message, and stored conversations never hold it:
 *     - With [recall], memories that relate to the newest owner or task message are looked up
 *       once, as the agent is stored when the turn starts, and put as a delimited data block
 *       ([RecalledMemoriesBlock]) in front of that message's text, in the request only. Recall
 *       never fails a turn, and the block does not change after the first model call. The
 *       record of data leaving the device then says that memories went too.
 *     - memory_search results are handed over for storing as references ([AgentRunResult.produced],
 *       [AgentRunListener.onMessage]); the model reads the result itself for the rest of the turn.
 *       References in [AgentRunRequest.messages] are written out again before the first model
 *       call, as the agent as it is stored then may read memory now ([MemoryReferences]).
 *  - Every tool call runs with an [AgentTurnInfo] in its coroutine context, so tools that record
 *    provenance know the model and conversation of the turn.
 *
 * A request can narrow a run, never widen it: [AgentRunRequest.limits] lowers the model call,
 * tool call and time limits for one run (a teammate's short answer, a task with little time
 * left before its deadline); [AgentRunRequest.extraToolIds] adds tools the framework decided
 * on (the team tools inside an objective's task), authorised like the agent's own; the
 * delegation chain is handed to every tool call; JSON output is requested when asked for.
 *
 * [estimatePromptTokens] does not count recalled memories, which are only known once the turn
 * runs; [AutoRecall] caps their size instead.
 */
class AgentRunner(
    private val gateway: ModelGateway,
    private val tools: ToolRegistry,
    private val executor: ToolExecutor,
    private val agents: AgentRepository,
    private val settings: SettingsRepository,
    private val monitor: DefaultAgentActivityMonitor,
    private val clock: Clock,
    private val prompts: AgentPromptComposer = AgentPromptComposer(),
    /** Null: no automatic recall, as before memory existed. */
    private val recall: AutoRecall? = null,
) {
    /** How memory tool results are stored and written out again; shared with the chat, which shows them. */
    internal val references = MemoryReferences(tools)

    suspend fun run(request: AgentRunRequest, listener: AgentRunListener = AgentRunListener.NONE): AgentRunResult {
        val limits = settings.get()
        val run = Run(request, listener, limits)
        monitor.runStarted(request.agent.id, request.activityLabel ?: "Preparing", request.taskId)
        try {
            val timeoutMinutes = limits.taskTimeoutMinutes.coerceAtLeast(1)
            val frameworkTimeoutMs = timeoutMinutes * 60_000L
            val timeoutMs = request.limits.timeoutMs?.coerceIn(1, frameworkTimeoutMs) ?: frameworkTimeoutMs
            val ending = withTimeoutOrNull(timeoutMs) { run.loop() }
                ?: Ending(
                    RunEnd.TIMED_OUT,
                    if (timeoutMs == frameworkTimeoutMs) "Stopped: the turn ran longer than $timeoutMinutes minutes, the time limit for one task."
                    else "Stopped: the turn reached its time limit of ${describeDuration(timeoutMs)}.",
                )
            run.closeOpenWork(ending.notice ?: "the turn ended")
            return run.result(ending)
        } finally {
            // Reached on every path, including cancellation: nothing produced or counted may be lost.
            withContext(NonCancellable) {
                run.closeOpenWork("the turn was stopped")
                monitor.runEnded(request.agent.id)
                run.saveStats()
            }
        }
    }

    /**
     * Roughly how many tokens the first model call of [request] would send: system prompt,
     * conversation and tool definitions. Lets a caller refuse a conversation that cannot fit
     * the model before anything is sent.
     */
    fun estimatePromptTokens(request: AgentRunRequest): Int {
        val offered = offeredTools(request)
        val system = ChatMessage.system(prompts.compose(request.agent, offered))
        // A stored memory search result is a reference here; it is counted at the most it can take once written out.
        val referenced = request.messages.sumOf { references.renderedCharsAtMost(it) }
        return TokenEstimator.tokens(listOf(system) + request.messages, offered.map(::definition)) +
            (referenced + LATIN_CHARS_PER_TOKEN - 1) / LATIN_CHARS_PER_TOKEN
    }

    /** As [estimatePromptTokens], with memory search results written out as [AgentRunRequest.agent] reads them now. */
    internal suspend fun estimateRenderedPromptTokens(request: AgentRunRequest): Int =
        estimatePromptTokens(request.copy(messages = references.forAgent(request.messages, request.agent)))

    /** The agent's enabled tools that exist, in a fixed order so that the request prefix is the same on every call. */
    private fun offeredTools(request: AgentRunRequest): List<ToolSpec> =
        (request.agent.toolIds + request.extraToolIds).mapNotNull { tools.byId(it)?.spec }.sortedBy { it.functionName }

    private fun definition(spec: ToolSpec) = ToolDefinition(spec.functionName, spec.description, spec.parameters)

    private class Ending(val end: RunEnd, val notice: String?, val error: LlmException? = null)

    private sealed interface Outcome {
        class Go(val agent: AgentProfile) : Outcome

        class Stop(val ending: Ending) : Outcome
    }

    /** The state of one run. */
    private inner class Run(val request: AgentRunRequest, val listener: AgentRunListener, val limits: FrameworkSettings) {
        private val agent = request.agent
        private val offered: List<ToolSpec> = offeredTools(request)
        private val definitions = offered.map(::definition)
        private val transcript = ArrayList<ChatMessage>().apply {
            add(ChatMessage.system(prompts.compose(agent, offered)))
            addAll(request.messages)
        }
        private val produced = ArrayList<ChatMessage>()
        private var callContext = CallContext(agent.id, request.taskId, request.rootTaskId, request.purpose)
        private val maxModelCalls = limits.maxModelCallsPerTask.coerceAtLeast(1).let { cap -> request.limits.maxModelCalls?.coerceIn(1, cap) ?: cap }
        private val maxToolCalls = limits.maxToolCallsPerTask.coerceAtLeast(0).let { cap -> request.limits.maxToolCalls?.coerceIn(0, cap) ?: cap }
        private val budget = request.callBudget?.also { it.limits(maxModelCalls, maxToolCalls) }

        private var modelCalls = 0
        private var modelErrors = 0
        private var toolCalls = 0
        private var toolErrors = 0
        private var usage = TokenUsage()
        private var modelName: String? = null
        private var lastText = ""

        /** Text of a reply that is still arriving. */
        private val partial = StringBuilder()

        /** Calls of the last assistant message that have no result message yet. */
        private val unanswered = ArrayList<ToolCallRequest>()

        /** The call whose tool is running right now, if any. */
        private var executing: ToolCallRequest? = null

        suspend fun loop(): Ending {
            while (true) {
                val chargedModelCalls = budget?.chargedModelCalls ?: 0
                if (modelCalls + chargedModelCalls >= maxModelCalls) {
                    val helpers = if (chargedModelCalls > 0) " and $chargedModelCalls by teammates answering its questions" else ""
                    return Ending(RunEnd.MODEL_CALL_LIMIT, "Stopped after $modelCalls model calls$helpers, the limit for one task.")
                }
                val profile = when (val now = current()) {
                    is Outcome.Stop -> return now.ending
                    is Outcome.Go -> now.agent
                }
                if (modelCalls == 0) {
                    renderReferences(profile)
                    recallMemories(profile)
                }
                val response = try {
                    callModel()
                } catch (e: LlmException) {
                    modelErrors++
                    return Ending(RunEnd.FAILED, e.message ?: "The model call failed.", e)
                }
                val calls = response.message.toolCalls
                when {
                    response.finishReason == FinishReason.REFUSED ->
                        return Ending(RunEnd.REFUSED, "The model declined to answer this request.")
                    calls.isNotEmpty() -> runTools(calls)?.let { return it }
                    response.finishReason == FinishReason.LENGTH ->
                        return Ending(RunEnd.TRUNCATED, "The reply was cut off because it reached the model's output limit.")
                    else -> return Ending(RunEnd.COMPLETED, null)
                }
            }
        }

        private suspend fun callModel(): ChatResponse {
            modelCalls++
            budget?.ownModelCall()
            partial.setLength(0)
            report("Waiting for the model")
            listener.onModelCall(modelCalls)
            val chatRequest = ChatRequest(
                messages = transcript.toList(), tools = definitions,
                temperature = agent.generation.temperature, maxOutputTokens = agent.generation.maxOutputTokens,
                jsonOutput = request.jsonOutput,
            )
            var completed: ChatResponse? = null
            var reasoning = false
            gateway.chatStream(request.modelId, chatRequest, callContext).collect { chunk ->
                when (chunk) {
                    is ChatChunk.Text -> {
                        if (partial.isEmpty()) report("Writing a reply")
                        partial.append(chunk.delta)
                        listener.onTextDelta(chunk.delta)
                    }
                    is ChatChunk.Reasoning -> {
                        if (!reasoning && partial.isEmpty()) report("Thinking")
                        reasoning = true
                        listener.onReasoningDelta(chunk.delta)
                    }
                    is ChatChunk.ToolCall -> Unit
                    is ChatChunk.Completed -> completed = chunk.response
                }
            }
            val response = completed ?: throw LlmException.MalformedResponse("The model's reply ended without a result.")
            partial.setLength(0)
            response.usage?.let { usage += it }
            modelName = response.modelName.ifBlank { modelName }
            lastText = response.message.text
            unanswered += response.message.toolCalls
            append(response.message, response.modelName.ifBlank { null }, response.usage)
            return response
        }

        /**
         * Writes the stored memory search results of the conversation out again, as [profile]
         * may read memory now. Runs before the first model call, so nothing has been sent yet.
         * Never fails the turn: what cannot be read is shown as no longer available.
         */
        private suspend fun renderReferences(profile: AgentProfile) {
            // Nothing has joined the transcript yet: it is the system prompt and the request's messages.
            val rendered = references.forAgent(request.messages, profile)
            for (i in rendered.indices) transcript[1 + i] = rendered[i]
        }

        /**
         * Puts the memories recalled for [profile] in front of the text of the newest owner or
         * task message, in the request only: the stored conversation keeps the message as the
         * owner wrote it. Runs before the first model call, so nothing has been sent yet and the
         * transcript still only grows from the model's point of view.
         */
        private suspend fun recallMemories(profile: AgentProfile) {
            val recall = recall ?: return
            if (!profile.memory.autoRecall || profile.memory.recallLimit <= 0) return
            val newest = transcript.indexOfLast { it.role == ChatRole.USER }
            if (newest < 0) return
            report("Recalling memories")
            val recalled = recall.recall(profile, request.messages, request.taskId, request.rootTaskId) ?: return
            transcript[newest] = RecalledMemoriesBlock.prepend(transcript[newest], recalled.section)
            val count = recalled.memories.size
            val memories = if (count == 1) "1 recalled memory" else "$count recalled memories"
            callContext = callContext.copy(purpose = "${request.purpose}, with $memories")
        }

        /**
         * The agent as it is stored now, or the ending of a run whose agent the owner switched
         * off or deleted. Its tool list and settings are what the next call is authorised against.
         */
        private suspend fun current(): Outcome {
            val now = agents.get(agent.id)
                ?: return Outcome.Stop(Ending(RunEnd.STOPPED_BY_OWNER, "Stopped: ${agent.name} was deleted while it was working."))
            if (!now.enabled) return Outcome.Stop(Ending(RunEnd.STOPPED_BY_OWNER, "Stopped: ${now.name} was switched off while it was working."))
            // Tools the framework offers for this run are authorised as the agent's own; everything else is as stored now.
            return Outcome.Go(if (request.extraToolIds.isEmpty()) now else now.copy(toolIds = now.toolIds + request.extraToolIds))
        }

        /** Runs the calls in order. Returns an ending when the tool budget ran out, or the agent was stopped, before all of them ran. */
        private suspend fun runTools(calls: List<ToolCallRequest>): Ending? {
            for (call in calls) {
                val chargedToolCalls = budget?.chargedToolCalls ?: 0
                if (toolCalls + chargedToolCalls >= maxToolCalls) {
                    val helpers = if (chargedToolCalls > 0) " and $chargedToolCalls by teammates answering its questions" else ""
                    return Ending(RunEnd.TOOL_CALL_LIMIT, "Stopped after $toolCalls tool calls$helpers, the limit for one task.")
                }
                val profile = when (val now = current()) {
                    is Outcome.Stop -> return now.ending
                    is Outcome.Go -> now.agent
                }
                val spec = tools.byFunctionName(call.name)?.spec
                report("Running tool: ${spec?.title ?: call.name.take(MAX_TOOL_NAME_SHOWN)}")
                listener.onToolStarted(call, spec)
                toolCalls++
                budget?.ownToolCall()
                executing = call
                val execution = withContext(AgentTurnInfo(request.modelId, modelName, request.conversationId)) {
                    executor.execute(call, ToolContext(profile, request.taskId, request.rootTaskId, request.delegationChain))
                }
                executing = null
                if (!execution.outcome.ok) toolErrors++
                unanswered.remove(call)
                val result = ChatMessage.toolResult(call.id, call.name, execution.outcome.content, isError = !execution.outcome.ok)
                // The model reads the result itself for the rest of the turn; a memory search result is stored as a reference.
                val stored = references.storedForm(execution)?.let { ChatMessage.toolResult(call.id, call.name, it, isError = false) } ?: result
                append(result, null, null, stored)
                listener.onToolFinished(execution)
            }
            return null
        }

        /**
         * Leaves the transcript in a state the next turn can build on: a reply that was still
         * arriving is kept as far as it got, and every call still waiting for its tool gets a
         * result saying why it did not run. A tool that was stopped while running is reported
         * as interrupted rather than as not run: the model must not assume that nothing
         * happened. Does nothing when nothing is open.
         */
        suspend fun closeOpenWork(reason: String) {
            if (partial.isNotBlank()) {
                lastText = partial.toString()
                partial.setLength(0)
                append(ChatMessage.assistant(lastText), modelName, null)
            }
            val open = unanswered.toList()
            val interrupted = executing
            unanswered.clear()
            executing = null
            for (call in open) {
                val explanation = if (call === interrupted) {
                    "This tool was stopped before it finished, so whether it had any effect is unknown. Reason: $reason"
                } else {
                    "This tool was not run: $reason"
                }
                append(ChatMessage.toolResult(call.id, call.name, explanation, isError = true), null, null)
            }
        }

        /** [message] joins the transcript the model reads; [stored], the form to keep, is what is handed over. */
        private suspend fun append(message: ChatMessage, modelName: String?, usage: TokenUsage?, stored: ChatMessage = message) {
            transcript += message
            produced += stored
            listener.onMessage(stored, modelName.takeIf { stored.role == ChatRole.ASSISTANT }, usage)
        }

        private fun report(detail: String) =
            monitor.report(agent.id, AgentActivity.WORKING, request.activityLabel?.let { "$it · $detail" } ?: detail, request.taskId)

        fun result(ending: Ending) = AgentRunResult(
            ending.end, lastText, ending.notice, ending.error, produced.toList(), usage, modelCalls, toolCalls, modelName,
        )

        suspend fun saveStats() {
            val now = clock.now()
            agents.updateStats(agent.id) {
                it.copy(
                    modelCalls = it.modelCalls + modelCalls, modelErrors = it.modelErrors + modelErrors,
                    toolCalls = it.toolCalls + toolCalls, toolErrors = it.toolErrors + toolErrors,
                    inputTokens = it.inputTokens + usage.inputTokens, outputTokens = it.outputTokens + usage.outputTokens,
                    lastActiveAt = now,
                )
            }
        }
    }

    /** "90 seconds", "1 minute", "15 minutes": a time limit in words. */
    private fun describeDuration(ms: Long): String {
        val seconds = (ms + 999) / 1000
        return when {
            seconds < 120 -> if (seconds == 1L) "1 second" else "$seconds seconds"
            else -> "${seconds / 60} minutes"
        }
    }

    private companion object {
        const val MAX_TOOL_NAME_SHOWN = 40
        const val LATIN_CHARS_PER_TOKEN = 4
    }
}
