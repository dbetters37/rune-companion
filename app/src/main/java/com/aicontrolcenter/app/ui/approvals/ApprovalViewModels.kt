package com.aicontrolcenter.app.ui.approvals

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.ui.components.textChanges
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OwnerGrants
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.OwnerRequests
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Notifications are off: the owner is not told when a request starts waiting. */
    val notificationsOff: Boolean = false,
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
    /** Whether notifications can be shown (`NotificationAccess.enabled`). */
    notificationsEnabled: Flow<Boolean> = flowOf(true),
) : ViewModel() {
    val uiState: StateFlow<ApprovalsUiState> = combine(requests.pending, directory.observe(), ticker, notificationsEnabled) { pending, snapshot, _, notify ->
        val now = clock.now()
        ApprovalsUiState(
            isLoading = false,
            requests = pending.filter { it.isOpenAt(now) }.map { requestUi(it, snapshot, capabilityInfo, now, zone) },
            notificationsOff = !notify,
        )
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
    /** Where to go once the owner allowed a call on another app's screen; the screen acts on it once. */
    val afterAnswer: AfterAnswer? = null,
    /** D9: what the call the owner allowed or approved did, from the activity log; null while nothing was allowed. */
    val whatHappened: WhatHappenedUi? = null,
)

/** D9: what an allowed or approved call did, in one line, with a tone (never colour alone: the words say it). */
@Immutable
data class WhatHappenedUi(val text: String, val tone: StatusTone?)

/**
 * What follows once the owner allowed a call on another app's screen. They answered here, in AI
 * Control Center, while that app waits behind it, and the call acts only once that app is in
 * front again: so either the same call's next question opens (its approval right after its
 * permission), or this app steps aside and the app it operates comes back.
 */
sealed interface AfterAnswer {
    /** Step aside so [appLabel] comes back to the front. */
    data class ReturnToApp(val appLabel: String) : AfterAnswer

    /** The same call asks again at once: open [requestId]. */
    data class OpenRequest(val requestId: RequestId) : AfterAnswer
}

/**
 * One question to the owner: who asks, for what exactly, where data would go, and the answers
 * on offer. Only the owner's taps here answer it ([OwnerRequests]); an answer that comes too
 * late or after another one is reported as such, and nothing changes. A question nothing waits
 * for any more (its call stopped with an earlier run of the app) is closed as soon as it is
 * shown ([OwnerRequests.closeIfAbandoned]) and reads "No longer waiting", never with answers.
 *
 * @param grants the permissions in force: a refused request stops saying its agent is held once
 * one of them covers what was refused (the owner gave it directly in Tools).
 * @param ticker moves the time left; tests pass a single tick.
 */
class ApprovalViewModel(
    private val requestId: RequestId,
    private val requests: OwnerRequests,
    grants: OwnerGrants,
    directory: RegistryDirectory,
    private val capabilityInfo: (Capability) -> CapabilityInfo?,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    ticker: Flow<Unit> = everyHalfMinute(),
    /** D9: the activity log, read only; the call an answer let through names this request in its entry. Null: not shown. */
    activityLog: ActivityLog? = null,
) : ViewModel() {

    private data class Local(val note: String = "", val isBusy: Boolean = false, val message: UserMessage? = null, val afterAnswer: AfterAnswer? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    /** Bumped when this screen changed a closed request (the owner let the agent ask again), so it is read again. */
    private val reread = MutableStateFlow(0)

    /**
     * The request as it is now: from the pending list while a call waits for it (every change to
     * it re-emits), else from the store, closed first if nothing waits for it any more.
     */
    private val request: Flow<OwnerRequest?> = combine(requests.pending, reread) { pending, _ ->
        pending.firstOrNull { it.id == requestId } ?: requests.closeIfAbandoned(requestId)
    }.distinctUntilChanged()

    /** The tool calls of the request's agent, newest first, once the owner allowed or approved it (D9); empty otherwise. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val calls: Flow<List<ActivityEvent>> = request.map { it?.takeIf { request -> request.status == OwnerRequestStatus.APPROVED && request.kind != OwnerRequestKind.QUESTION }?.agentId }
        .distinctUntilChanged()
        .flatMapLatest { agent ->
            if (agent == null || activityLog == null) flowOf(emptyList()) else activityLog.observe(ActivityFilter(types = setOf(ActivityType.TOOL_CALL), agentId = agent), CALLS_READ)
        }

    private val requestAndCalls = combine(request, calls) { request, calls -> request to calls }

    val uiState: StateFlow<ApprovalUiState> = combine(requestAndCalls, directory.observe(), local, ticker, grants.observeAll()) { (request, calls), snapshot, local, _, grants ->
        val now = clock.now()
        ApprovalUiState(
            isLoading = false,
            notFound = request == null,
            request = request?.let { requestUi(it, snapshot, capabilityInfo, now, zone, grants) },
            note = local.note,
            isBusy = local.isBusy,
            message = local.message,
            afterAnswer = local.afterAnswer,
            whatHappened = request?.let { whatHappened(it, calls, now) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ApprovalUiState())

    /**
     * The note being typed. The field reads it and the keyboard writes it directly, so every key
     * lands in order; [ApprovalUiState.note] follows it.
     */
    val note = TextFieldState()

    init {
        viewModelScope.launch { note.textChanges().collect { text -> local.update { it.copy(note = text.take(MAX_NOTE)) } } }
    }

    /** Replaces the note from code; typing goes into [note] directly. */
    fun setNote(text: String) {
        note.putText(text.take(MAX_NOTE))
        local.update { it.copy(note = text.take(MAX_NOTE)) }
    }

    /** Answers the request with [choice]. Does nothing while an answer is being written. */
    fun choose(choice: RequestChoice) {
        if (local.value.isBusy) return
        val sent = note.text.toString().take(MAX_NOTE).trim().takeIf { it.isNotEmpty() }
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            var allowedOnScreen: OwnerRequest? = null
            val text = try {
                val result = when (choice) {
                    RequestChoice.ALLOW_ONCE -> requests.grant(requestId, GrantScope.ONCE, sent)
                    RequestChoice.ALLOW_FOR_TASK -> requests.grant(requestId, GrantScope.TASK, sent)
                    RequestChoice.ALLOW_ALWAYS -> requests.grant(requestId, GrantScope.ALWAYS, sent)
                    RequestChoice.APPROVE -> requests.approve(requestId, sent)
                    RequestChoice.DENY -> requests.deny(requestId, sent)
                }
                if (result is DecideResult.Decided && choice != RequestChoice.DENY && result.request.constraint is CapabilityConstraint.App) {
                    allowedOnScreen = result.request
                }
                describe(result, choice)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Your answer could not be saved: ${failure.message ?: "storage error"}. The agent is still waiting."
            }
            note.putText("")
            local.update { it.copy(isBusy = false, note = "", message = UserMessage(text, ++messageCounter)) }
            allowedOnScreen?.let { answered -> local.update { it.copy(afterAnswer = nextStep(answered)) } }
        }
    }

    /**
     * After a refused permission, the owner changes their mind: the agent may ask again at once
     * ([OwnerRequests.letAskAgain]). Nothing is allowed by it. Does nothing while busy.
     */
    fun letAskAgain() {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val text = try {
                when (val result = requests.letAskAgain(requestId)) {
                    is OwnerResult.Done -> "${result.value.agentName ?: "The agent"} can ask again. Its next call or request for this permission is put to you."
                    is OwnerResult.Refused -> result.reason
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "That could not be saved: ${failure.message ?: "storage error"}. Nothing changed."
            }
            reread.update { it + 1 }
            local.update { it.copy(isBusy = false, message = UserMessage(text, ++messageCounter)) }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    /** The screen went where [ApprovalUiState.afterAnswer] said. */
    fun afterAnswerDone() = local.update { it.copy(afterAnswer = null) }

    /**
     * After allowing [answered], a call on another app's screen: the same call's approval when it
     * asks for one right after its permission, else back to that app.
     */
    private suspend fun nextStep(answered: OwnerRequest): AfterAnswer {
        val app = answered.targetLabel ?: (answered.constraint as CapabilityConstraint.App).packageName
        if (answered.kind != OwnerRequestKind.PERMISSION) return AfterAnswer.ReturnToApp(app)
        val next = withTimeoutOrNull(FOLLOW_UP_MS) {
            requests.pending.map { pending -> pending.firstOrNull { it.followsUp(answered) } }.first { it != null }
        }
        return next?.let { AfterAnswer.OpenRequest(it.id) } ?: AfterAnswer.ReturnToApp(app)
    }

    /** The approval the same call raises right after its permission [answered]. */
    private fun OwnerRequest.followsUp(answered: OwnerRequest): Boolean =
        id != answered.id && kind == OwnerRequestKind.APPROVAL && agentId == answered.agentId && toolId == answered.toolId &&
            constraint == answered.constraint && createdAt >= answered.createdAt

    private fun describe(result: DecideResult, choice: RequestChoice): String = when (result) {
        // The call is checked again before it runs (the app, the folder, Stop, the screen in front), so say what was given, not what will run.
        is DecideResult.Decided -> when (choice) {
            RequestChoice.ALLOW_ONCE -> "Allowed just this once. The action is checked again before it runs."
            RequestChoice.ALLOW_FOR_TASK -> "Allowed until the task ends. Each action is still checked before it runs."
            RequestChoice.ALLOW_ALWAYS -> "Always allowed, until you remove it in Tools › Allowed. Each action is still checked before it runs."
            RequestChoice.APPROVE -> "Approved. It runs once, exactly as shown, unless something changed meanwhile; then the agent is told it was not run."
            RequestChoice.DENY -> "Denied. The agent is told, with your note if you wrote one."
        }
        is DecideResult.AlreadyClosed ->
            if (result.request.status == OwnerRequestStatus.CANCELLED) {
                "Your answer was not used. " + (result.request.decisionNote ?: "Nothing waits for it any more.")
            } else {
                "This request was already closed (${result.request.statusAt(clock.now()).text.lowercase()}). Nothing changed."
            }
        is DecideResult.Expired -> "Too late: the request expired, so the agent was refused. Nothing was allowed."
        is DecideResult.NotAccepted -> result.problem
        DecideResult.NotFound -> "This request no longer exists. Nothing changed."
    }

    companion object {
        /** The longest note the owner can send with an answer. */
        const val MAX_NOTE = 500

        /** How many of the agent's newest tool calls are looked through for the one an answer let through (D9). */
        const val CALLS_READ = 200

        /** How long to wait, after a permission for a screen call, for that call's approval before stepping aside. */
        private const val FOLLOW_UP_MS = 1_500L
    }
}
