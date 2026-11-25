package com.aicontrolcenter.framework.chat

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.IdGenerator
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.resolveAvailability
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.ConversationRepository
import com.aicontrolcenter.core.chat.LiveTurn
import com.aicontrolcenter.core.chat.TurnPhase
import com.aicontrolcenter.core.chat.TurnResult
import com.aicontrolcenter.core.conversationId
import com.aicontrolcenter.core.llm.ChatMessage
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderRechecks
import com.aicontrolcenter.core.llm.TokenUsage
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.registry.AgentDirectory
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelDirectory
import com.aicontrolcenter.core.registry.ProviderDirectory
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.framework.agents.AgentDeletionListener
import com.aicontrolcenter.framework.agents.AgentRunListener
import com.aicontrolcenter.framework.agents.AgentRunRequest
import com.aicontrolcenter.framework.agents.AgentRunResult
import com.aicontrolcenter.framework.agents.AgentRunner
import com.aicontrolcenter.framework.agents.RunEnd
import com.aicontrolcenter.framework.tools.ToolExecution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Direct chat with one agent. See [AgentChat] for the contract; how it is kept:
 *
 *  - A turn runs in [scope], the framework's own, so it outlives the screen that started it.
 *    [send] merely waits for it.
 *  - Everything is stored the moment it exists: the owner's message before the model is
 *    called, each assistant message and tool result as the runner hands them over. Whatever
 *    ends the turn, the conversation shows exactly what happened up to that point.
 *  - The model is sent the stored conversation, whole. When that cannot fit the model's
 *    context window the turn fails with an explanation; messages are never dropped quietly,
 *    because an agent that silently forgets is worse than one that says it is full.
 *  - A turn that fails leaves a visible entry ([ConversationEntry.error]). Such entries are
 *    for the owner and are never sent to a model.
 *  - Memory text is not kept in conversations: a memory_search result is stored as a reference
 *    and shown from current memory, as the owner may read it, every time [observeEntries] emits;
 *    a memory that was forgotten shows as no longer available. The runner does the same for the
 *    agent when it sends the conversation to the model. Recalled memories are never stored.
 *  - Text that is still arriving is gathered per turn and published to [liveTurns] at most
 *    every [LIVE_TEXT_INTERVAL_MS], and at once whenever the phase changes or a reply is
 *    complete, so a long reply costs time in proportion to its length. Text held back by that
 *    interval is published when it is over even if nothing more arrives, so the preview never
 *    lags behind a reply that pauses.
 *
 * A tool call that waits for the owner to answer a permission or approval request shows it in
 * the live turn ([LiveTurn.waitingForOwner]) until the wait ends; stopping the turn ([cancel])
 * withdraws the request.
 *
 * Registered as an [AgentDeletionListener], it stops the turns of a deleted agent and removes
 * its conversations.
 *
 * Phase 6 (WP7):
 *  - R3: a turn is marked as started in the store when the owner's message is stored, and the
 *    mark is removed when the turn ends, however it ends. A mark found at the next start means
 *    the app stopped during that turn: [recoverInterruptedTurns] stores a visible note saying so,
 *    and [sendAgain] runs the turn of the owner's last message again without storing it twice.
 *  - D28: an agent that cannot answer only because its provider is not answering has that
 *    provider checked again first ([ProviderRechecks]; never the owner's provider manager).
 *  - Rule 6: a turn stopped by today's limit says the limit's sentence, nothing else.
 *  - No turn starts while a backup is restored ([MaintenanceGate.RESTORING]).
 */
class DefaultAgentChat(
    private val conversations: ConversationRepository,
    private val agents: AgentDirectory,
    private val models: ModelDirectory,
    private val providers: ProviderDirectory,
    private val settings: SettingsView,
    private val runner: AgentRunner,
    private val activity: ActivityRecorder,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** Looks again at a provider that is not answering before the turn is refused (D28). */
    private val rechecks: ProviderRechecks = ProviderRechecks.None,
    /** True while a backup is restored (the maintenance gate's `busy`): no turn starts meanwhile. */
    private val restoring: StateFlow<Boolean> = MutableStateFlow(false),
) : AgentChat, AgentDeletionListener {

    private class RunningTurn(val agentId: AgentId, val job: Job)

    /** Guards [running] together with the matching entry of [live]. Never held while suspending. */
    private val turnsLock = Any()
    private val running = HashMap<ConversationId, RunningTurn>()
    private val live = MutableStateFlow<Map<ConversationId, LiveTurn>>(emptyMap())

    /** Serialises changes to conversation records (title, last-updated time), which are read-modify-write. */
    private val recordLock = Mutex()

    override val liveTurns: StateFlow<Map<ConversationId, LiveTurn>> = live.asStateFlow()

    override fun observeConversations(agentId: AgentId?): Flow<List<Conversation>> = conversations.observeConversations(agentId)

    override fun observeEntries(id: ConversationId): Flow<List<ConversationEntry>> = conversations.observeEntries(id).map { entries ->
        if (entries.none { runner.references.isReference(it.message) }) entries
        else runner.references.forOwner(entries, conversations.getConversation(id)?.agentId)
    }

    override suspend fun startConversation(agentId: AgentId, title: String?): Conversation {
        requireNotNull(agents.get(agentId)) { "That agent no longer exists." }
        val now = clock.now()
        val conversation = Conversation(ids.conversationId(), agentId, cleanTitle(title) ?: DEFAULT_TITLE, createdAt = now, updatedAt = now)
        conversations.upsertConversation(conversation)
        return conversation
    }

    override suspend fun rename(id: ConversationId, title: String) {
        val newTitle = requireNotNull(cleanTitle(title)) { "Give the conversation a title." }
        changeRecord(id) { it.copy(title = newTitle) } ?: throw IllegalArgumentException(CONVERSATION_GONE)
    }

    override suspend fun delete(id: ConversationId) {
        cancel(id)
        conversations.deleteConversation(id)
    }

    override suspend fun send(id: ConversationId, text: String): TurnResult {
        val message = text.trim()
        require(message.isNotEmpty()) { "Write a message first." }
        return start(id, message)
    }

    /** R3 ("Send again"): the turn of the owner's last message, run again; the message is not stored a second time. */
    override suspend fun sendAgain(id: ConversationId): TurnResult = start(id, null)

    /** Runs a turn for [text], or ([text] null) for the owner's last stored message again. */
    private suspend fun start(id: ConversationId, text: String?): TurnResult {
        if (restoring.value) return TurnResult.Failed(MaintenanceGate.RESTORING, retryable = true)
        val conversation = conversations.getConversation(id) ?: throw IllegalArgumentException(CONVERSATION_GONE)
        if (text == null && conversations.getEntries(id).none { it.error == null && it.message.role == ChatRole.USER }) {
            return TurnResult.Failed(NOTHING_TO_SEND_AGAIN, retryable = false)
        }
        val result = CompletableDeferred<TurnResult>()
        val job = scope.launch(start = CoroutineStart.LAZY) { result.complete(turn(conversation, text)) }
        synchronized(turnsLock) {
            if (id in running) {
                job.cancel()
                throw IllegalStateException("${conversation.title}: the agent is still answering. Wait for it to finish, or stop it first.")
            }
            running[id] = RunningTurn(conversation.agentId, job)
            live.update { it + (id to LiveTurn(id, TurnPhase.PREPARING)) }
        }
        // Covers a turn whose body never ran because the framework scope was already shut down.
        job.invokeOnCompletion {
            release(id, job)
            result.complete(TurnResult.Cancelled)
        }
        job.start()
        return result.await()
    }

    override suspend fun cancel(id: ConversationId) {
        val turn = synchronized(turnsLock) { running[id] } ?: return
        turn.job.cancel()
        // Returning only when the turn has wound down means the caller sees everything that was kept.
        turn.job.join()
    }

    /**
     * R3: for every turn the app stopped in the middle of (its mark is still in the store), a visible note that the
     * reply was cut off, then the mark is removed. A turn of this process that is running is left alone. Called once
     * at start, before any turn runs. Returns how many notes were stored.
     */
    override suspend fun recoverInterruptedTurns(): Int {
        var stored = 0
        for (turn in conversations.interruptedTurns()) {
            val id = turn.conversationId
            if (synchronized(turnsLock) { id in running }) continue
            val conversation = conversations.getConversation(id)
            if (conversation != null) {
                val name = agents.get(conversation.agentId)?.name ?: "the agent"
                val notice = cutOff(name)
                // Stored before the mark goes: an app that dies in between says it twice rather than not at all.
                storeError(id, notice)
                try {
                    activity.record(ActivityType.TASK, "Chat reply cut off", notice, severity = Severity.WARNING, agentId = conversation.agentId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e // the note is stored; only the log entry is missing
                }
                stored++
            }
            conversations.markTurnEnded(id)
        }
        return stored
    }

    override suspend fun onAgentDeleted(agent: AgentProfile) {
        val turns = synchronized(turnsLock) { running.values.filter { it.agentId == agent.id }.map { it.job } }
        turns.forEach { it.cancel() }
        turns.joinAll()
        conversations.deleteByAgent(agent.id)
    }

    // ---- one turn ----

    /**
     * Runs the turn to its end and never throws: every way a turn can end is a [TurnResult]. The turn is marked as
     * started in the store from the moment the owner's message is stored until it ends (R3).
     */
    private suspend fun turn(conversation: Conversation, text: String?): TurnResult {
        val id = conversation.id
        val result = try {
            answer(conversation, text)
        } catch (e: CancellationException) {
            TurnResult.Cancelled
        } catch (e: Exception) {
            withContext(NonCancellable) {
                failed(id, conversation.agentId, "The turn stopped because of an error inside the app (${e::class.simpleName}).", retryable = false, log = true)
            }
        }
        withContext(NonCancellable) {
            try {
                changeRecord(id) { it.copy(updatedAt = clock.now()) }
            } catch (e: Exception) {
                // Only the ordering of the conversation list depends on this.
            }
            try {
                conversations.markTurnEnded(id)
            } catch (e: Exception) {
                // A mark left behind only makes the next start say the reply was cut off.
            }
        }
        currentCoroutineContext()[Job]?.let { release(id, it) }
        return result
    }

    /** [text]: the owner's new message; null runs the turn of the last one again ([sendAgain]). */
    private suspend fun answer(conversation: Conversation, text: String?): TurnResult {
        val id = conversation.id
        withContext(NonCancellable) {
            if (text != null) {
                val isFirstMessage = conversations.getEntries(id).isEmpty()
                store(id, ChatMessage.user(text))
                if (isFirstMessage) changeRecord(id) { if (it.title == DEFAULT_TITLE) it.copy(title = titleFrom(text)) else it }
            }
            conversations.markTurnStarted(id, clock.now())
        }

        val agent = agents.get(conversation.agentId)
            ?: return failed(id, conversation.agentId, "This agent no longer exists.", retryable = false, log = true)
        val modelsById = models.getAll().associateBy { it.id }
        var providersById = providers.getAll().associateBy { it.id }
        var availability = resolveAvailability(agent, modelsById, providersById, settings.get())
        // D28: a provider that is only not answering is looked at again before the turn is refused.
        val stale = (availability as? AgentAvailability.Unavailable)?.takeIf { it.reason.isTransient }
            ?.let { agent.modelId?.let { primary -> modelsById[primary]?.providerId } }
        if (stale != null) {
            try {
                rechecks.recheckIfStale(stale, RECHECK_OLDER_THAN_MS)
            } catch (e: Exception) {
                if (e is CancellationException) throw e // a failed check only leaves the provider as it was
            }
            providersById = providers.getAll().associateBy { it.id }
            availability = resolveAvailability(agent, modelsById, providersById, settings.get())
        }
        val modelId = when (availability) {
            is AgentAvailability.Ready -> availability.modelId
            is AgentAvailability.Disabled ->
                return failed(id, agent.id, "${agent.name} is switched off. Switch it on to talk to it.", retryable = false, log = true)
            is AgentAvailability.Unavailable ->
                return failed(id, agent.id, "${agent.name} cannot answer right now. ${availability.detail}", retryable = availability.reason.isTransient, log = true)
        }
        val model = modelsById.getValue(modelId)
        val locality = providersById[model.providerId]?.effectiveLocality
        live.update { turns -> turns.change(id) { it.copy(modelName = model.displayName, locality = locality) } }

        val history = if (text != null) ConversationHistory.toMessages(conversations.getEntries(id)) else ConversationHistory.forSendAgain(conversations.getEntries(id))
        val request = AgentRunRequest(agent, modelId, history, conversationId = id)
        tooLongFor(model, agent, request)?.let { return failed(id, agent.id, it, retryable = false, log = true) }

        val run = coroutineScope {
            val recorder = Recorder(id, timers = this)
            try {
                runner.run(request, recorder)
            } finally {
                recorder.stop()
            }
        }
        return when (run.end) {
            RunEnd.COMPLETED -> completed(run)
            RunEnd.TRUNCATED -> {
                // The reply is kept and returned; the note next to it tells the owner it is not the whole answer.
                storeError(id, run.notice ?: "The reply is incomplete.")
                completed(run)
            }
            // Rule 6: today's limit is said in its own sentence (the gateway logged the refused call).
            RunEnd.FAILED -> if (run.error is LlmException.DailyLimitReached) {
                failed(id, agent.id, run.notice?.takeIf { it.isNotBlank() } ?: DailyLimits.PAUSED_TEXT, retryable = false, log = false)
            } else {
                // The gateway has already logged the failed model call.
                failed(id, agent.id, "${agent.name} could not answer. ${run.notice}", run.error?.retryable ?: false, log = false)
            }
            RunEnd.REFUSED, RunEnd.MODEL_CALL_LIMIT, RunEnd.TOOL_CALL_LIMIT, RunEnd.TIMED_OUT ->
                failed(id, agent.id, run.notice ?: "The turn was stopped.", retryable = false, log = true)
            // The owner switched the agent off or deleted it: their decision, not a failure, and the note says so.
            RunEnd.STOPPED_BY_OWNER -> stoppedByOwner(id, agent.id, run.notice ?: "Stopped: ${agent.name} was switched off.")
        }
    }

    private suspend fun stoppedByOwner(id: ConversationId, agentId: AgentId, notice: String): TurnResult {
        withContext(NonCancellable) {
            storeError(id, notice)
            activity.record(ActivityType.TASK, "Agent turn stopped by the owner", notice, agentId = agentId)
        }
        return TurnResult.Cancelled
    }

    private fun completed(run: AgentRunResult) = TurnResult.Completed(run.text, run.usage, run.modelCalls, run.toolCalls)

    /** An explanation when the conversation cannot fit the model's context window, or null when it fits or the window is unknown. */
    private suspend fun tooLongFor(model: ModelDescriptor, agent: AgentProfile, request: AgentRunRequest): String? {
        val window = model.capabilities.contextWindow ?: return null
        val replyRoom = minOf(agent.generation.maxOutputTokens ?: DEFAULT_REPLY_ROOM, window / 4)
        val needed = runner.estimateRenderedPromptTokens(request) + replyRoom
        if (needed <= window) return null
        return "This conversation has grown too long for ${model.displayName}: it needs about $needed tokens and the model's context window " +
            "holds $window. Nothing was sent. Start a new conversation, or give ${agent.name} a model with a larger context window."
    }

    /**
     * Stores the turn as it unfolds and mirrors it into [liveTurns]. One per turn, called by the
     * runner in order from the turn's coroutine. Text held back by the publishing interval is
     * published by a timer in [timers] once the interval has passed, so a reply that pauses is
     * shown up to where it paused; the timer runs apart from the turn, hence [lock].
     */
    private inner class Recorder(private val id: ConversationId, private val timers: CoroutineScope) : AgentRunListener {
        private val lock = Any()
        private val text = StringBuilder()
        private val reasoning = StringBuilder()

        /** Whether [liveTurns] shows this turn receiving a reply, and whether it lags behind the buffers. */
        private var receiving = false
        private var behind = false
        private var publishedAt = 0L

        /** Publishes what is held back once the interval has passed; set while something is held back. */
        private var flush: Job? = null

        override suspend fun onModelCall(number: Int) = synchronized(lock) {
            clearBuffers()
            show { it.copy(phase = TurnPhase.WAITING_FOR_MODEL, partialText = "", partialReasoning = "", activeTool = null, modelCalls = number) }
        }

        override suspend fun onTextDelta(delta: String) = synchronized(lock) {
            text.append(delta)
            arrived()
        }

        override suspend fun onReasoningDelta(delta: String) = synchronized(lock) {
            reasoning.append(delta)
            arrived()
        }

        override suspend fun onMessage(message: ChatMessage, modelName: String?, usage: TokenUsage?) {
            // The reply is complete (or cut short): show all of it before the stored message takes its place.
            synchronized(lock) { if (behind) publish() }
            withContext(NonCancellable) { store(id, message, modelName, usage) }
            if (message.role == ChatRole.ASSISTANT) {
                synchronized(lock) {
                    clearBuffers()
                    show { it.copy(partialText = "", partialReasoning = "") }
                }
            }
        }

        override suspend fun onToolStarted(call: ToolCallRequest, spec: ToolSpec?) = synchronized(lock) {
            if (behind) publish()
            receiving = false
            show { it.copy(phase = TurnPhase.RUNNING_TOOL, activeTool = call, toolCalls = it.toolCalls + 1) }
        }

        override suspend fun onToolFinished(execution: ToolExecution) = synchronized(lock) { show { it.copy(activeTool = null, waitingForOwner = null) } }

        override suspend fun onWaitingForOwner(request: OwnerRequest) = synchronized(lock) { show { it.copy(waitingForOwner = request) } }

        override suspend fun onOwnerAnswered(request: OwnerRequest) = synchronized(lock) {
            show { if (it.waitingForOwner?.id == request.id) it.copy(waitingForOwner = null) else it }
        }

        /** Ends the timer; called when the turn ends, however it ends. */
        fun stop() = synchronized(lock) { cancelFlush() }

        /**
         * Publishes at once when this starts the receiving phase or the interval has passed since
         * the last publication; otherwise makes sure the timer publishes it when the interval is over.
         */
        private fun arrived() {
            behind = true
            val since = System.nanoTime() - publishedAt
            when {
                !receiving || since >= LIVE_TEXT_INTERVAL_NS -> publish()
                flush == null -> {
                    val waitMs = ((LIVE_TEXT_INTERVAL_NS - since + NANOS_PER_MS - 1) / NANOS_PER_MS).coerceAtLeast(1)
                    flush = timers.launch {
                        delay(waitMs)
                        val self = coroutineContext.job
                        synchronized(lock) {
                            // A timer that was replaced while it waited for the lock leaves the publishing to its successor.
                            if (flush === self) {
                                flush = null
                                if (behind) publish()
                            }
                        }
                    }
                }
            }
        }

        private fun publish() {
            cancelFlush()
            val partialText = text.toString()
            val partialReasoning = reasoning.toString()
            receiving = true
            behind = false
            publishedAt = System.nanoTime()
            show { it.copy(phase = TurnPhase.RECEIVING, partialText = partialText, partialReasoning = partialReasoning) }
        }

        private fun clearBuffers() {
            text.setLength(0)
            reasoning.setLength(0)
            receiving = false
            behind = false
            cancelFlush()
        }

        private fun cancelFlush() {
            flush?.cancel()
            flush = null
        }

        private fun show(change: (LiveTurn) -> LiveTurn) = live.update { it.change(id, change) }
    }

    private fun Map<ConversationId, LiveTurn>.change(id: ConversationId, change: (LiveTurn) -> LiveTurn): Map<ConversationId, LiveTurn> {
        val current = this[id] ?: return this
        return this + (id to change(current))
    }

    // ---- storage ----

    private suspend fun store(id: ConversationId, message: ChatMessage, modelName: String? = null, usage: TokenUsage? = null) {
        conversations.appendEntry(ConversationEntry(id, seq = -1, message = message, at = clock.now(), modelName = modelName, usage = usage))
    }

    /** The message is written by the framework, not the agent, hence the system role; it is shown to the owner and never sent to a model. */
    private suspend fun storeError(id: ConversationId, message: String) {
        conversations.appendEntry(ConversationEntry(id, seq = -1, message = ChatMessage.system(message), at = clock.now(), error = message))
    }

    private suspend fun failed(id: ConversationId, agentId: AgentId, message: String, retryable: Boolean, log: Boolean): TurnResult.Failed {
        withContext(NonCancellable) {
            storeError(id, message)
            if (log) activity.record(ActivityType.ERROR, "Agent turn stopped", message, severity = Severity.WARNING, agentId = agentId)
        }
        return TurnResult.Failed(message, retryable)
    }

    /** Applies [change] to the stored conversation and returns the result, or null when it no longer exists. */
    private suspend fun changeRecord(id: ConversationId, change: (Conversation) -> Conversation): Conversation? = recordLock.withLock {
        val current = conversations.getConversation(id) ?: return@withLock null
        change(current).also { if (it != current) conversations.upsertConversation(it) }
    }

    private fun release(id: ConversationId, job: Job) {
        synchronized(turnsLock) {
            if (running[id]?.job === job) {
                running.remove(id)
                live.update { it - id }
            }
        }
    }

    private fun cleanTitle(raw: String?): String? = raw?.let { WHITESPACE.replace(it, " ").trim() }?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE_LENGTH)

    private fun titleFrom(text: String): String {
        val firstLine = WHITESPACE.replace(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), " ").trim()
        return if (firstLine.length <= AUTO_TITLE_LENGTH) firstLine else firstLine.take(AUTO_TITLE_LENGTH - 1).trimEnd() + "…"
    }

    companion object {
        /** The title of a conversation until its first message names it. */
        const val DEFAULT_TITLE = "New conversation"
        const val MAX_TITLE_LENGTH = 80

        /** How often text that is still arriving is published to [liveTurns]: smooth to watch, and cheap for long replies. */
        private const val LIVE_TEXT_INTERVAL_MS = 75L
        private const val NANOS_PER_MS = 1_000_000L
        private const val LIVE_TEXT_INTERVAL_NS = LIVE_TEXT_INTERVAL_MS * NANOS_PER_MS
        private const val AUTO_TITLE_LENGTH = 48
        private const val DEFAULT_REPLY_ROOM = 512
        private const val CONVERSATION_GONE = "That conversation no longer exists."

        /** Said when there is no message of the owner's to send again. */
        const val NOTHING_TO_SEND_AGAIN = "There is no message of yours to send again."

        /** A provider whose status is older than this is checked again before a turn is refused for it (D28). */
        const val RECHECK_OLDER_THAN_MS = 60_000L

        /** R3: the note for a reply the app stopped in the middle of. */
        fun cutOff(agentName: String) = "The app stopped before $agentName finished answering. Send your message again to try again."
        private val WHITESPACE = Regex("\\s+")
    }
}
