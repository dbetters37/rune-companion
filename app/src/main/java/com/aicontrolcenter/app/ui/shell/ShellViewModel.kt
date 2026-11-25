package com.aicontrolcenter.app.ui.shell

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.dashboard.AttentionItem
import com.aicontrolcenter.app.ui.dashboard.AttentionTarget
import com.aicontrolcenter.app.ui.dashboard.attentionItems
import com.aicontrolcenter.core.RoutineRunId
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.framework.routines.WaitingRoutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * What the navigation chrome needs to know: how many things need attention in each section, and
 * how many questions wait for the owner's answer.
 */
@Immutable
data class ShellUiState(
    val badges: Map<Section, Int> = emptyMap(),
    /**
     * What waits for the owner's answer: approvals and permission requests, and agents' questions (Phase 6, Tier B),
     * shown together as "Waiting for you" above the bottom bar and at the top of the rail.
     */
    val pendingRequests: Int = 0,
    /** Of [pendingRequests], the agents' questions. */
    val pendingQuestions: Int = 0,
    /**
     * A routine run waiting for the owner's tap that the app has not shown yet in this process (WP17, F12): the shell
     * opens the confirmation step once for it, when the app is on screen. Nothing starts without the owner's tap there.
     */
    val routineToShow: RoutineRunId? = null,
) {
    fun badge(section: Section): Int = badges[section] ?: 0

    /** Attention hidden behind the phone's "More" item. */
    val moreBadge: Int get() = badges.filterKeys { it !in Section.bottomBar }.values.sum()
}

/**
 * Feeds the attention badges of the bottom bar, the rail and the "More" sheet. The counts come
 * from the same function as the dashboard's list, so a badge always matches what is listed.
 *
 * @param pendingRequests the requests waiting for the owner (`OwnerRequests.pending`, which
 *   leaves out those past their expiry): approvals, permission requests and agents' questions.
 * @param waitingRoutines the routine runs waiting for the owner's tap (`WaitingRoutines.observeWaiting`).
 */
class ShellViewModel(
    directory: RegistryDirectory,
    pendingRequests: Flow<List<OwnerRequest>> = flowOf(emptyList()),
    waitingRoutines: Flow<List<WaitingRoutine>> = flowOf(emptyList()),
) : ViewModel() {
    /** Waiting runs already shown in this process, so each opens the confirmation step at most once. */
    private val shown = MutableStateFlow<Set<RoutineRunId>>(emptySet())

    val uiState: StateFlow<ShellUiState> = combine(directory.observe(), pendingRequests, waitingRoutines, shown) { snapshot, pending, waiting, seen ->
        ShellUiState(
            badges = badgesFor(snapshot.attentionItems()),
            pendingRequests = pending.size,
            pendingQuestions = pending.count { it.kind == OwnerRequestKind.QUESTION },
            routineToShow = waiting.firstOrNull { it.runId !in seen }?.runId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellUiState())

    /** The shell opened the confirmation step for [run] (or the owner already saw it there). */
    fun routineShown(run: RoutineRunId) = shown.update { it + run }

    companion object {
        /** Counts attention items by the section in which each is fixed. */
        fun badgesFor(items: List<AttentionItem>): Map<Section, Int> =
            items.groupingBy { it.target.section }.eachCount()
    }
}

/** The section in which an attention item is dealt with. */
val AttentionTarget.section: Section
    get() = when (this) {
        is AttentionTarget.Agent, AttentionTarget.Agents, AttentionTarget.UnavailableAgents -> Section.AGENTS
        is AttentionTarget.AiApp, is AttentionTarget.Provider -> Section.MODELS
    }
