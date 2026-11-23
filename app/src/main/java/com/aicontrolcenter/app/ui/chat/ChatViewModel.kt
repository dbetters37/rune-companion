package com.aicontrolcenter.app.ui.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.models.describe
import com.aicontrolcenter.app.ui.models.isSimulator
import com.aicontrolcenter.app.ui.models.providerFailureText
import com.aicontrolcenter.core.AgentId
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
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
) {
    val isRunning: Boolean get() = live != null
    val canSend: Boolean get() = !isLoading && !notFound && blockedReason == null && !isRunning && draft.isNotBlank()
}

/**
 * One conversation with an agent. Sending runs the turn in the framework's own scope, so it
 * goes on when this screen is left; the transcript and the live turn are read back from
 * [AgentChat], so returning shows whatever happened meanwhile.
 */
class ChatViewModel(
    private val conversationId: ConversationId,
    directory: RegistryDirectory,
    private val chat: AgentChat,
    private val tools: ToolRegistry,
    private val providerManager: ProviderManager,
) : ViewModel() {

    private data class Local(
        val draft: String = "",
        /** Whether the turn this screen last saw fail could succeed if tried again (network, time-out, overload). */
        val lastFailureRetryable: Boolean? = null,
        val isCheckingProvider: Boolean = false,
        val deleted: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private val conversation = chat.observeConversations(null).map { all -> all.firstOrNull { it.id == conversationId } }.distinctUntilChanged()
    private val live = chat.liveTurns.map { it[conversationId] }.distinctUntilChanged()

    val uiState: StateFlow<ChatUiState> = combine(
        directory.observe(),
        conversation,
        chat.observeEntries(conversationId),
        live,
        local,
    ) { snapshot, conversation, entries, live, local ->
        build(snapshot, conversation, entries, live, local)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    private fun build(
        snapshot: RegistrySnapshot,
        conversation: Conversation?,
        entries: List<ConversationEntry>,
        live: LiveTurn?,
        local: Local,
    ): ChatUiState {
        if (conversation == null) {
            return ChatUiState(isLoading = false, notFound = true, deleted = local.deleted, message = local.message)
        }
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
        val items = transcriptOf(
            entries = entries,
            tools = ::toolInfo,
            assignedModelName = primary?.modelName,
            turnRunning = live != null,
            activeToolCallId = live?.activeTool?.id,
        )
        val lastIsFailure = (items.lastOrNull() as? TranscriptItem.Notice)?.kind == NoticeKind.FAILURE
        val connectionFailure = lastIsFailure && live == null &&
            (local.lastFailureRetryable == true || provider?.status in PROBLEM_STATUSES)
        val offerCheck = provider != null && (blockedByConnection || connectionFailure)
        return ChatUiState(
            isLoading = false,
            title = conversation.title,
            agentId = conversation.agentId,
            agentName = profile?.name ?: "Removed agent",
            agentColorIndex = profile?.colorIndex ?: 0,
            items = items,
            live = live?.toUi { toolInfo(it)?.title },
            destination = destination,
            blockedReason = blocked,
            checkProviderId = provider?.id?.takeIf { offerCheck },
            checkProviderName = provider?.name?.takeIf { offerCheck },
            isCheckingProvider = local.isCheckingProvider,
            draft = local.draft,
            deleted = local.deleted,
            message = local.message,
        )
    }

    private fun toolInfo(functionName: String): ToolInfo? = tools.byFunctionName(functionName)?.spec?.let { ToolInfo(it.title, it.risk) }

    fun setDraft(text: String) = local.update { it.copy(draft = text) }

    /** Sends the draft. The turn runs on in the framework if this screen is left. */
    fun send() {
        val state = uiState.value
        val text = state.draft.trim()
        if (text.isEmpty() || !state.canSend) return
        local.update { it.copy(draft = "", lastFailureRetryable = null) }
        viewModelScope.launch {
            try {
                val result = chat.send(conversationId, text)
                if (result is TurnResult.Failed) local.update { it.copy(lastFailureRetryable = result.retryable) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (busy: IllegalStateException) {
                show(busy.message ?: "The agent is still answering.")
            } catch (failure: Exception) {
                show(failure.message ?: "The message could not be sent.")
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

    fun messageShown() = local.update { it.copy(message = null) }

    private fun show(text: String) = local.update { it.copy(message = UserMessage(text, ++messageCounter)) }

    private companion object {
        val CONNECTION_REASONS = setOf(UnavailableReason.PROVIDER_OFFLINE, UnavailableReason.PROVIDER_AUTH_ERROR)
        val PROBLEM_STATUSES = setOf(ConnectionStatus.OFFLINE, ConnectionStatus.AUTH_ERROR, ConnectionStatus.ERROR)
    }
}
