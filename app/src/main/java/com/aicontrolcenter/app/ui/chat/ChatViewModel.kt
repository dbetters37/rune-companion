package com.aicontrolcenter.app.ui.chat

import com.aicontrolcenter.app.ui.components.OperatorOffTexts
import com.aicontrolcenter.app.ui.components.PhoneTools
import com.aicontrolcenter.app.ui.settings.PhoneOperatorSwitch
import com.aicontrolcenter.app.ui.tasks.NewObjectiveViewModel
import com.aicontrolcenter.core.ToolId
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.approvals.requestUi
import com.aicontrolcenter.app.ui.components.onPhoneModels
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.ui.components.textChanges
import com.aicontrolcenter.app.ui.models.describe
import com.aicontrolcenter.app.ui.models.isSimulator
import com.aicontrolcenter.app.ui.models.providerFailureText
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.agents.UnavailableReason
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.chat.Conversation
import com.aicontrolcenter.core.chat.ConversationEntry
import com.aicontrolcenter.core.chat.LiveTurn
import com.aicontrolcenter.core.chat.TurnResult
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.ToolRegistry
import com.aicontrolcenter.app.usage.TodayUse
import com.aicontrolcenter.core.backup.MaintenanceGate
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.WorkUsage
import com.aicontrolcenter.framework.chat.DefaultAgentChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

@Immutable
data class ChatUiState(
    val isLoading: Boolean = true,
    /** The conversation no longer exists (deleted here, elsewhere, or with its agent). */
    val notFound: Boolean = false,
    val title: String = "",
    val agentId: AgentId? = null,
    val agentName: String = "",
    val agentColorIndex: Int = 0,
    val items: List<TranscriptItem> = emptyList(),
    val live: LiveTurnUi? = null,
    /** Where what the owner writes is sent. Null when the agent has no usable model at all. */
    val destination: DestinationUi? = null,
    /** Why nothing can be sent right now, in the framework's words. Null when the agent can answer. */
    val blockedReason: String? = null,
    /** The provider to check when the last failure, or the block, looks like connectivity or credentials. */
    val checkProviderId: ProviderId? = null,
    val checkProviderName: String? = null,
    val isCheckingProvider: Boolean = false,
    val draft: String = "",
    val deleted: Boolean = false,
    val message: UserMessage? = null,
    /** Older entries exist than the ones shown (P5: the newest 50 first, then "Show earlier"). */
    val hasEarlier: Boolean = false,
    val isLoadingEarlier: Boolean = false,
    /** What this chat has used so far, with its estimated cost when the owner entered prices; null before any use. */
    val cost: ChatCostUi? = null,
    /** Today's limit's sentence while it is reached and this chat's text leaves the phone (rule 6); null otherwise. */
    val limitReached: String? = null,
    /** Phone operator is off and this agent has phone tools: said before the owner asks it anything, with "Turn on". */
    val operatorOff: Boolean = false,
    /** Phone operator is off now: a phone tool refused for that shows "Turn on" where it was refused. */
    val operatorSwitchedOff: Boolean = false,
    /** "Turn on" can switch it on here (the owner's switch is at hand). */
    val canTurnOnOperator: Boolean = false,
) {
    val isRunning: Boolean get() = live != null
    val canSend: Boolean get() = canSend(draft)

    /**
     * The last thing in the chat is a note that the turn failed, was cut off, or was stopped by the app or by today's
     * limit, and the agent could answer now: "Send again" runs the owner's last message again (R3).
     */
    val canSendAgain: Boolean
        get() = !isLoading && !notFound && blockedReason == null && !isRunning &&
            items.lastOrNull { it !is TranscriptItem.Request } is TranscriptItem.Notice

    /** Whether [text], as typed in the field now, could be sent. */
    fun canSend(text: CharSequence): Boolean = !isLoading && !notFound && blockedReason == null && !isRunning && text.isNotBlank()
}

/**
 * One conversation with an agent. Sending runs the turn in the framework's own scope, so it
 * goes on when this screen is left; the transcript and the live turn are read back from
 * [AgentChat], so returning shows whatever happened meanwhile.
 *
 * Questions the agent's tool calls put to the owner in this chat ([requests]) appear in the
 * transcript where they were raised, each with the way to its Approval screen; they are never
 * answered here.
 *
 * Only the newest [ConversationPages.PAGE] entries are read at first (P5, D31); [showEarlier]
 * reads the page before them. The transcript is rebuilt when the conversation, its entries, the
 * live turn or the registry change, never for a key typed into [draft].
 *
 * @param awaitStartup waits for start-up, which withdraws requests left open when the app last
 *   stopped; a message sent before it finished could otherwise have its fresh request withdrawn.
 * @param pages the conversation's entries in parts (the store in the app)
 * @param usage this chat's usage rows (`UsageReports.observeWorkUsage(CHAT, id)`), for the cost line
 * @param prices the owner's prices, read only
 * @param today today's use and limits as the gateway counts them, for the limit sentence
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val conversationId: ConversationId,
    directory: RegistryDirectory,
    private val chat: AgentChat,
    private val tools: ToolRegistry,
    private val providerManager: ProviderManager,
    requests: Flow<List<OwnerRequest>> = flowOf(emptyList()),
    private val capabilityInfo: (Capability) -> CapabilityInfo? = { null },
    private val clock: Clock = WallClock,
    private val awaitStartup: suspend () -> Unit = {},
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val pages: ConversationPages = ConversationPages.of(chat),
    usage: Flow<List<WorkUsage>> = flowOf(emptyList()),
    prices: Flow<List<ModelPrice>> = flowOf(emptyList()),
    today: Flow<TodayUse> = flowOf(TodayUse.NONE),
    /** The owner's Phone operator switch, for "Turn on"; owner UI only. Null: only the way to Settings is offered. */
    private val operatorSwitch: PhoneOperatorSwitch? = null,
    /** Which tools need Phone operator on (the registered tools' specs in the app). */
    private val isPhoneTool: (ToolId) -> Boolean = PhoneTools.CATALOGUE,
) : ViewModel() {

    private data class Local(
        val draft: String = "",
        /** Whether the turn this screen last saw fail could succeed if tried again (network, time-out, overload). */
        val lastFailureRetryable: Boolean? = null,
        val isCheckingProvider: Boolean = false,
        val isLoadingEarlier: Boolean = false,
        val deleted: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /**
     * The message being written. The field reads it and the keyboard writes it directly, so every
     * key lands in order; [ChatUiState.draft] follows it.
     */
    val draft = TextFieldState()

    init {
        viewModelScope.launch { draft.textChanges().collect { text -> local.update { it.copy(draft = text) } } }
    }

    private val conversation = chat.observeConversations(null).map { all -> all.firstOrNull { it.id == conversationId } }.distinctUntilChanged()
    private val live = chat.liveTurns.map { it[conversationId] }.distinctUntilChanged()

    /** The lowest entry number shown once "Show earlier" was used; null shows the newest page. */
    private val shownFrom = MutableStateFlow<Int?>(null)
    private var window: EntryWindow = EntryWindow.EMPTY

    private val entries: Flow<EntryWindow> = combine(pages.observeLatestSeq(conversationId), shownFrom, ::Pair)
        .mapLatest { (latest, from) -> pages.window(conversationId, latest, from) }
        .distinctUntilChanged()

    /** Everything but what changes while the owner types: rebuilt only when the chat itself changes. */
    private val transcript: Flow<Built> = combine(directory.observe(), conversation, entries, live, requests) { snapshot, conversation, window, live, requests ->
        this.window = window
        build(snapshot, conversation, window, live, requests)
    }

    private val cost: Flow<ChatCostUi?> = combine(usage, prices, directory.observe()) { rows, priced, snapshot ->
        chatCostOf(rows, priced.associateBy { it.modelId }, snapshot.onPhoneModels())
    }.distinctUntilChanged()

    val uiState: StateFlow<ChatUiState> = combine(transcript, local, cost, today) { built, local, cost, today ->
        built.withLocal(local, cost, today)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    /** The transcript and what it says about the agent, before what the owner is doing on the screen is added. */
    private data class Built(
        val state: ChatUiState,
        /** The agent's own provider (not the simulator), worth checking when something looks like connectivity. */
        val provider: ProviderId?,
        val providerName: String?,
        val providerHasProblem: Boolean,
        val blockedByConnection: Boolean,
    )

    private fun Built.withLocal(local: Local, cost: ChatCostUi?, today: TodayUse): ChatUiState {
        if (state.notFound) return state.copy(deleted = local.deleted, message = local.message)
        val lastIsFailure = (state.items.lastOrNull { it !is TranscriptItem.Request } as? TranscriptItem.Notice)?.kind == NoticeKind.FAILURE
        val connectionFailure = lastIsFailure && state.live == null && (local.lastFailureRetryable == true || providerHasProblem)
        val offerCheck = provider != null && (blockedByConnection || connectionFailure)
        val limit = today.limits.reachedBy(today.use.calls, today.use.tokens, today.use.spendMicros)
        return state.copy(
            checkProviderId = provider?.takeIf { offerCheck },
            checkProviderName = providerName?.takeIf { offerCheck },
            isCheckingProvider = local.isCheckingProvider,
            isLoadingEarlier = local.isLoadingEarlier,
            draft = local.draft,
            deleted = local.deleted,
            message = local.message,
            cost = cost,
            limitReached = limit?.takeIf { state.destination?.locality?.leavesDevice == true }?.let { today.limits.sentence(it) },
        )
    }

    private fun build(
        snapshot: RegistrySnapshot,
        conversation: Conversation?,
        window: EntryWindow,
        live: LiveTurn?,
        requests: List<OwnerRequest>,
    ): Built {
        if (conversation == null) {
            return Built(ChatUiState(isLoading = false, notFound = true), null, null, providerHasProblem = false, blockedByConnection = false)
        }
        val entries = window.entries
        val entry = snapshot.agentsById[conversation.agentId]
        val profile = entry?.profile
        val availability = entry?.availability
        val primary = profile?.modelId?.let { snapshot.modelsById[it] }
        val shownModel = when (availability) {
            is AgentAvailability.Ready -> snapshot.modelsById[availability.modelId]
            else -> primary
        }
        val shownProvider = shownModel?.let(snapshot::provider)
        val destination = if (shownModel != null && shownProvider != null) {
            DestinationUi(
                modelName = shownModel.displayName,
                providerName = shownProvider.name,
                locality = shownProvider.effectiveLocality,
                isSimulator = shownModel.isSimulator,
                usingFallback = (availability as? AgentAvailability.Ready)?.usingFallback == true,
            )
        } else {
            null
        }
        val blocked = when {
            profile == null -> "This agent no longer exists."
            availability is AgentAvailability.Disabled -> "${profile.name} is switched off. Switch it on on its page to talk to it."
            availability is AgentAvailability.Unavailable -> "${profile.name} cannot answer right now. ${availability.detail}"
            else -> null
        }
        // The provider worth checking: the one the agent's own model lives on.
        val provider = primary?.let(snapshot::provider)?.takeUnless { it.isSimulator }
        val blockedByConnection = (availability as? AgentAvailability.Unavailable)?.reason in CONNECTION_REASONS
        val now = clock.now()
        // Requests raised before the first entry shown belong to the earlier part.
        val shownSince = if (window.hasEarlier) entries.firstOrNull()?.at else null
        val items = withRequests(
            transcriptOf(
                entries = entries,
                tools = ::toolInfo,
                assignedModelName = primary?.modelName,
                turnRunning = live != null,
                activeToolCallId = live?.activeTool?.id,
            ),
            entries,
            requests.filter { shownSince == null || it.createdAt >= shownSince }.map { it.createdAt to requestUi(it, snapshot, capabilityInfo, now, zone) },
        )
        val state = ChatUiState(
            isLoading = false,
            title = conversation.title,
            agentId = conversation.agentId,
            agentName = profile?.name ?: "Removed agent",
            agentColorIndex = profile?.colorIndex ?: 0,
            items = items,
            live = live?.toUi { toolInfo(it)?.title },
            destination = destination,
            blockedReason = blocked,
            hasEarlier = window.hasEarlier,
            operatorOff = !snapshot.settings.operatorEnabled && profile?.toolIds?.any(isPhoneTool) == true,
            operatorSwitchedOff = !snapshot.settings.operatorEnabled,
            canTurnOnOperator = operatorSwitch != null,
        )
        return Built(state, provider?.id, provider?.name, provider?.status in PROBLEM_STATUSES, blockedByConnection)
    }

    private fun toolInfo(functionName: String): ToolInfo? =
        toolInfoOf(functionName) { name -> tools.byFunctionName(name)?.spec?.let { ToolInfo(it.title, it.risk) } }

    /** Replaces the message from code; typing goes into [draft] directly. */
    fun setDraft(text: String) {
        draft.putText(text)
        local.update { it.copy(draft = text) }
    }

    /** Sends exactly what the field holds. The turn runs on in the framework if this screen is left. */
    fun send() {
        val state = uiState.value
        val text = draft.text.toString().trim()
        if (text.isEmpty() || !state.canSend(text)) return
        draft.putText("")
        local.update { it.copy(draft = "", lastFailureRetryable = null) }
        viewModelScope.launch {
            try {
                awaitStartup()
                ended(chat.send(conversationId, text))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (busy: IllegalStateException) {
                show(busy.message ?: "The agent is still answering.")
            } catch (failure: Exception) {
                show(failure.message ?: "The message could not be sent.")
            }
        }
    }

    /**
     * "Send again" (R3): runs the owner's last message again after the turn failed, was cut off, or was stopped by the
     * app or today's limit, without storing the message a second time. The turn runs on if this screen is left.
     */
    fun sendAgain() {
        if (!uiState.value.canSendAgain) return
        local.update { it.copy(lastFailureRetryable = null) }
        viewModelScope.launch {
            try {
                awaitStartup()
                ended(chat.sendAgain(conversationId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (busy: IllegalStateException) {
                show(busy.message ?: "The agent is still answering.")
            } catch (failure: Exception) {
                show(failure.message ?: "The message could not be sent again.")
            }
        }
    }

    /** A failure the chat does not store (nothing was run) is said once on the screen; one it stored is in the transcript. */
    private fun ended(result: TurnResult) {
        if (result !is TurnResult.Failed) return
        if (result.message in NOT_STORED) show(result.message) else local.update { it.copy(lastFailureRetryable = result.retryable) }
    }

    /** Reads the page of entries before the first one shown (P5), back to the start of its turn. */
    fun showEarlier() {
        val first = window.entries.firstOrNull() ?: return
        if (!window.hasEarlier || local.value.isLoadingEarlier) return
        local.update { it.copy(isLoadingEarlier = true) }
        viewModelScope.launch {
            try {
                val older = pages.toTurnStart(conversationId, pages.entriesBefore(conversationId, first.seq, ConversationPages.PAGE))
                older.firstOrNull()?.let { shownFrom.value = minOf(shownFrom.value ?: it.seq, it.seq) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                show("Earlier messages could not be read: ${failure.message ?: "storage error"}")
            } finally {
                local.update { it.copy(isLoadingEarlier = false) }
            }
        }
    }

    /** Stops the turn in progress. What was produced so far is kept. */
    fun stop() {
        viewModelScope.launch {
            try {
                chat.cancel(conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                show("Could not stop the turn: ${failure.message ?: "unexpected error"}")
            }
        }
    }

    fun rename(title: String) {
        viewModelScope.launch {
            try {
                chat.rename(conversationId, title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                show(failure.message ?: "Could not rename the conversation.")
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                chat.delete(conversationId)
                local.update { it.copy(deleted = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                show("Could not delete the conversation: ${failure.message ?: "storage error"}")
            }
        }
    }

    /** Checks the agent's provider now; a stale offline or rejected status otherwise keeps the agent from answering. */
    fun checkProvider() {
        val state = uiState.value
        val id = state.checkProviderId ?: return
        if (local.value.isCheckingProvider) return
        local.update { it.copy(isCheckingProvider = true) }
        viewModelScope.launch {
            val text = try {
                val health = providerManager.checkProvider(id)
                val name = state.checkProviderName ?: "The provider"
                if (health.status == ConnectionStatus.ONLINE) "${health.describe(name)} Send your message again." else health.describe(name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failure.providerFailureText("The check")
            }
            local.update { it.copy(isCheckingProvider = false, lastFailureRetryable = null, message = UserMessage(text, ++messageCounter)) }
        }
    }

    /** "Turn on" on the Phone operator card or under a refused phone tool: the owner's switch, as in Settings. */
    fun turnOnOperator() {
        val switch = operatorSwitch ?: return
        viewModelScope.launch {
            try {
                switch.set(true)
                show(OperatorOffTexts.TURNED_ON)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                show(NewObjectiveViewModel.OPERATOR_NOT_TURNED_ON)
            }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun show(text: String) = local.update { it.copy(message = UserMessage(text, ++messageCounter)) }

    private companion object {
        val CONNECTION_REASONS = setOf(UnavailableReason.PROVIDER_OFFLINE, UnavailableReason.PROVIDER_AUTH_ERROR)
        val PROBLEM_STATUSES = setOf(ConnectionStatus.OFFLINE, ConnectionStatus.AUTH_ERROR, ConnectionStatus.ERROR)

        /** Refusals before anything ran: the chat stores no note for them. */
        val NOT_STORED = setOf(DefaultAgentChat.NOTHING_TO_SEND_AGAIN, MaintenanceGate.RESTORING)
    }
}
