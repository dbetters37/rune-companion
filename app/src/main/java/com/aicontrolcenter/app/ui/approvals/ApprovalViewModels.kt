package com.aicontrolcenter.app.ui.approvals

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** A tick now and then every half minute, so the time left on a request moves. */
fun everyHalfMinute(): Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(30_000)
    }
}

@Immutable
data class ApprovalsUiState(
    val isLoading: Boolean = true,
    /** Requests waiting for an answer, oldest first. */
    val requests: List<RequestUi> = emptyList(),
)

/**
 * The requests waiting for the owner, oldest first. Requests past their expiry are left out:
 * they can no longer be answered.
 *
 * @param ticker moves the time left; tests pass a single tick.
 */
class ApprovalsViewModel(
    requests: OwnerRequests,
    directory: RegistryDirectory,
    private val capabilityInfo: (Capability) -> CapabilityInfo?,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    ticker: Flow<Unit> = everyHalfMinute(),
) : ViewModel() {
    val uiState: StateFlow<ApprovalsUiState> = combine(requests.pending, directory.observe(), ticker) { pending, snapshot, _ ->
        val now = clock.now()
        ApprovalsUiState(isLoading = false, requests = pending.filter { it.isOpenAt(now) }.map { requestUi(it, snapshot, capabilityInfo, now, zone) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApprovalsUiState())
}

@Immutable
data class ApprovalUiState(
    val isLoading: Boolean = true,
    /** No request has this id (it may have been cleared with its agent's data). */
    val notFound: Boolean = false,
    val request: RequestUi? = null,
    /** The owner's optional note to the agent, sent with the answer. */
    val note: String = "",
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
)

/**
 * One question to the owner: who asks, for what exactly, where data would go, and the answers
 * on offer. Only the owner's taps here answer it ([OwnerRequests]); an answer that comes too
 * late or after another one is reported as such, and nothing changes.
 *
 * @param ticker moves the time left; tests pass a single tick.
 */
class ApprovalViewModel(
    private val requestId: RequestId,
    private val requests: OwnerRequests,
    directory: RegistryDirectory,
    private val capabilityInfo: (Capability) -> CapabilityInfo?,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    ticker: Flow<Unit> = everyHalfMinute(),
) : ViewModel() {

    private data class Local(val note: String = "", val isBusy: Boolean = false, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /** The request as it is now: from the pending list while it waits (every change to it re-emits), from the store once closed. */
    private val request: Flow<OwnerRequest?> = requests.pending
        .map { pending -> pending.firstOrNull { it.id == requestId } ?: requests.get(requestId) }
        .distinctUntilChanged()

    val uiState: StateFlow<ApprovalUiState> = combine(request, directory.observe(), local, ticker) { request, snapshot, local, _ ->
        val now = clock.now()
        ApprovalUiState(
            isLoading = false,
            notFound = request == null,
            request = request?.let { requestUi(it, snapshot, capabilityInfo, now, zone) },
            note = local.note,
            isBusy = local.isBusy,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApprovalUiState())

    fun setNote(text: String) = local.update { it.copy(note = text.take(MAX_NOTE)) }

    /** Answers the request with [choice]. Does nothing while an answer is being written. */
    fun choose(choice: RequestChoice) {
        if (local.value.isBusy) return
        val note = local.value.note.trim().takeIf { it.isNotEmpty() }
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val text = try {
                val result = when (choice) {
                    RequestChoice.ALLOW_ONCE -> requests.grant(requestId, GrantScope.ONCE, note)
                    RequestChoice.ALLOW_FOR_TASK -> requests.grant(requestId, GrantScope.TASK, note)
                    RequestChoice.ALLOW_ALWAYS -> requests.grant(requestId, GrantScope.ALWAYS, note)
                    RequestChoice.APPROVE -> requests.approve(requestId, note)
                    RequestChoice.DENY -> requests.deny(requestId, note)
                }
                describe(result, choice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Your answer could not be saved: ${failure.message ?: "storage error"}. The agent is still waiting."
            }
            local.update { it.copy(isBusy = false, note = "", message = UserMessage(text, ++messageCounter)) }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun describe(result: DecideResult, choice: RequestChoice): String = when (result) {
        is DecideResult.Decided -> when (choice) {
            RequestChoice.ALLOW_ONCE -> "Allowed just this once. The agent goes on."
            RequestChoice.ALLOW_FOR_TASK -> "Allowed until the task ends. The agent goes on."
            RequestChoice.ALLOW_ALWAYS -> "Always allowed, until you remove it in Tools › Allowed. The agent goes on."
            RequestChoice.APPROVE -> "Approved. The action runs once, exactly as shown."
            RequestChoice.DENY -> "Denied. The agent is told, with your note if you wrote one."
        }
        is DecideResult.AlreadyClosed -> "This request was already closed (${result.request.statusAt(clock.now()).text.lowercase()}). Nothing changed."
        is DecideResult.Expired -> "Too late: the request expired, so the agent was refused. Nothing was allowed."
        is DecideResult.NotAccepted -> result.problem
        DecideResult.NotFound -> "This request no longer exists. Nothing changed."
    }

    private companion object {
        const val MAX_NOTE = 500
    }
}
