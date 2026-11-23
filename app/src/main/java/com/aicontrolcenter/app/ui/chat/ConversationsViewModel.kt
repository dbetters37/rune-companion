package com.aicontrolcenter.app.ui.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.agents.AgentAvailability
import com.aicontrolcenter.core.chat.AgentChat
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.app.ui.models.isSimulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

@Immutable
data class ConversationRowUi(
    val id: ConversationId,
    val title: String,
    val lastActivity: String,
    /** What the agent is doing in it right now, when a turn is running. */
    val livePhase: String?,
)

@Immutable
data class ConversationsUiState(
    val isLoading: Boolean = true,
    /** The agent is not in the registry. */
    val notFound: Boolean = false,
    val agentName: String = "",
    val agentColorIndex: Int = 0,
    val status: StatusLabel? = null,
    /** Why the agent cannot answer, when it cannot. */
    val statusDetail: String? = null,
    val destination: DestinationUi? = null,
    val conversations: List<ConversationRowUi> = emptyList(),
    val isCreating: Boolean = false,
    /** Set once after a conversation was started here; the route opens it. */
    val openConversation: ConversationId? = null,
    val message: UserMessage? = null,
)

/** An agent's conversations, most recent first, and the way to start a new one. */
class ConversationsViewModel(
    private val agentId: AgentId,
    directory: RegistryDirectory,
    private val chat: AgentChat,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private data class Local(val isCreating: Boolean = false, val openConversation: ConversationId? = null, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    val uiState: StateFlow<ConversationsUiState> = combine(
        directory.observe(),
        chat.observeConversations(agentId),
        chat.liveTurns,
        local,
    ) { snapshot, conversations, live, local ->
        val entry = snapshot.agentsById[agentId]
        if (entry == null) {
            return@combine ConversationsUiState(isLoading = false, notFound = true, message = local.message)
        }
        val ready = entry.availability as? AgentAvailability.Ready
        val model = (ready?.modelId ?: entry.profile.modelId)?.let { snapshot.modelsById[it] }
        val provider = model?.let(snapshot::provider)
        val now = clock.now()
        ConversationsUiState(
            isLoading = false,
            agentName = entry.profile.name,
            agentColorIndex = entry.profile.colorIndex,
            status = entry.availability.toStatusLabel(),
            statusDetail = (entry.availability as? AgentAvailability.Unavailable)?.detail
                ?: if (entry.availability is AgentAvailability.Disabled) "${entry.profile.name} is switched off." else null,
            destination = if (model != null && provider != null) {
                DestinationUi(model.displayName, provider.name, provider.effectiveLocality, model.isSimulator, ready?.usingFallback == true)
            } else {
                null
            },
            conversations = conversations.map { conversation ->
                ConversationRowUi(
                    id = conversation.id,
                    title = conversation.title,
                    lastActivity = formatCompactTimestamp(conversation.updatedAt, now, zone),
                    livePhase = live[conversation.id]?.toUi { null }?.phase,
                )
            },
            isCreating = local.isCreating,
            openConversation = local.openConversation,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationsUiState())

    fun newConversation() {
        if (local.value.isCreating) return
        local.update { it.copy(isCreating = true) }
        viewModelScope.launch {
            try {
                val conversation = chat.startConversation(agentId)
                local.update { it.copy(isCreating = false, openConversation = conversation.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update {
                    it.copy(isCreating = false, message = UserMessage(failure.message ?: "Could not start a conversation.", ++messageCounter))
                }
            }
        }
    }

    fun openHandled() = local.update { it.copy(openConversation = null) }

    fun messageShown() = local.update { it.copy(message = null) }
}
