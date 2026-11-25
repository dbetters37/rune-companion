@file:Phase6Stub("WP9")

package com.aicontrolcenter.app.ui.team

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import com.aicontrolcenter.app.Phase6Stub
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.settings.SettingsView
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Team view shows (WP9 fills it). */
@Immutable
data class TeamUiState(val isLoading: Boolean = true)

/**
 * The Team view's state (WP9), built off the main thread from summaries only (rule 16: `observeSummaries`,
 * `observeHeaders`, `observeObjectiveRows`, never whole task rows or messages); at most one polite live summary every 10 s.
 *
 * @param pendingRequests who waits for the owner: PERMISSION, APPROVAL and QUESTION (`OwnerRequests.pending`).
 * @param memory the spaces along the bottom (`observeSpaces`), read as the owner reads them.
 * @param settings read only: Calm.
 */
class TeamViewModel(
    @Suppress("unused") private val objectiveId: TaskId?,
    @Suppress("unused") private val tasks: TaskRepository,
    @Suppress("unused") private val messages: MessageRepository,
    @Suppress("unused") private val directory: RegistryDirectory,
    @Suppress("unused") private val pendingRequests: Flow<List<OwnerRequest>>,
    @Suppress("unused") private val monitor: AgentActivityMonitor,
    @Suppress("unused") private val memory: MemoryService,
    @Suppress("unused") private val settings: SettingsView,
    @Suppress("unused") private val clock: Clock,
) : ViewModel() {
    val uiState: StateFlow<TeamUiState> = MutableStateFlow(TeamUiState()).asStateFlow()
}
