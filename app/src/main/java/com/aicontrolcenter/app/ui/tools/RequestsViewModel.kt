package com.aicontrolcenter.app.ui.tools

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.approvals.RequestUi
import com.aicontrolcenter.app.ui.approvals.everyHalfMinute
import com.aicontrolcenter.app.ui.approvals.isOpenAt
import com.aicontrolcenter.app.ui.approvals.requestUi
import com.aicontrolcenter.app.ui.tasks.AgentFaceUi
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.ZoneId

/** How a request ended, as the history filters it. */
enum class RequestOutcome(val label: String) {
    WAITING("Waiting"),
    ALLOWED("Allowed"),
    DENIED("Denied"),
    EXPIRED("Expired"),
    CANCELLED("No longer waiting"),
    ;

    companion object {
        /** Where [request] stands at [now]; a pending request past its expiry counts as expired. */
        fun of(request: OwnerRequest, now: Long): RequestOutcome = when (request.status) {
            OwnerRequestStatus.PENDING -> if (request.isOpenAt(now)) WAITING else EXPIRED
            OwnerRequestStatus.APPROVED -> ALLOWED
            OwnerRequestStatus.DENIED -> DENIED
            OwnerRequestStatus.EXPIRED -> EXPIRED
            OwnerRequestStatus.CANCELLED -> CANCELLED
        }
    }
}

/** What the history is narrowed to; null fields do not narrow. */
@Immutable
data class RequestFilter(
    val kind: OwnerRequestKind? = null,
    val outcome: RequestOutcome? = null,
    val agent: AgentId? = null,
) {
    val isActive: Boolean get() = kind != null || outcome != null || agent != null
}

@Immutable
data class RequestsUiState(
    val isLoading: Boolean = true,
    val filter: RequestFilter = RequestFilter(),
    /** Newest first, after the filter. */
    val requests: List<RequestUi> = emptyList(),
    /** How many requests the history holds before the filter. */
    val total: Int = 0,
    /** Agents that raised a request in the history, for the agent filter. */
    val agents: List<AgentFaceUi> = emptyList(),
)

/**
 * The History tab: the history of permission and approval requests, newest first, with how
 * each ended, filtered by kind, outcome and agent. Each opens its Approval screen, which also
 * answers a request that is still waiting.
 *
 * @param ticker moves "waiting" to "expired" as time passes; tests pass a single tick.
 */
class RequestsViewModel(
    requests: OwnerRequests,
    directory: RegistryDirectory,
    private val capabilityInfo: (Capability) -> CapabilityInfo?,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    ticker: Flow<Unit> = everyHalfMinute(),
) : ViewModel() {
    private val filter = MutableStateFlow(RequestFilter())

    val uiState: StateFlow<RequestsUiState> = combine(requests.observeRecent(HISTORY_LIMIT), directory.observe(), filter, ticker) { recent, snapshot, filter, _ ->
        val now = clock.now()
        val newestFirst = recent.sortedWith(compareByDescending<OwnerRequest> { it.createdAt }.thenByDescending { it.id.value })
        val shown = newestFirst.filter { request ->
            (filter.kind == null || request.kind == filter.kind) &&
                (filter.outcome == null || RequestOutcome.of(request, now) == filter.outcome) &&
                (filter.agent == null || request.agentId == filter.agent)
        }
        RequestsUiState(
            isLoading = false,
            filter = filter,
            requests = shown.map { requestUi(it, snapshot, capabilityInfo, now, zone) },
            total = recent.size,
            agents = newestFirst.distinctBy { it.agentId }.map { request ->
                val profile = snapshot.agentsById[request.agentId]?.profile
                AgentFaceUi(request.agentId, profile?.name ?: request.agentName ?: "Removed agent", profile?.colorIndex ?: 0)
            }.sortedBy { it.name.lowercase() },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RequestsUiState())

    /** Tapping the selected value again clears it. */
    fun setKind(kind: OwnerRequestKind?) = filter.update { it.copy(kind = kind.takeUnless { k -> k == it.kind }) }

    fun setOutcome(outcome: RequestOutcome?) = filter.update { it.copy(outcome = outcome.takeUnless { o -> o == it.outcome }) }

    fun setAgent(agent: AgentId?) = filter.update { it.copy(agent = agent.takeUnless { a -> a == it.agent }) }

    fun clearFilter() = filter.update { RequestFilter() }

    companion object {
        /** The newest requests the history shows. */
        const val HISTORY_LIMIT = 300
    }
}
