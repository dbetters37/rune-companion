package com.aicontrolcenter.app.ui.agents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.tools.ToolSpec
import kotlinx.coroutines.flow.flowOf
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.agents.AgentOperationException
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryFilter
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import kotlinx.coroutines.flow.map
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.tasks.toStatusLabel
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import java.time.ZoneId

/** One agent with everything its detail screen shows, already resolved against the registry. */
@Immutable
data class AgentDetailUi(
    val profile: AgentProfile,
    val status: StatusLabel,
    val stats: AgentStats,
    val isDesignatedDirector: Boolean,
    /** Null when no model is assigned. */
    val model: ModelRefUi?,
    val fallbackModels: List<ModelRefUi>,
    /** True when the registry has at least one model that could be assigned. */
    val modelsAvailable: Boolean,
    /** What the agent is doing right now ("Waiting for the model"), or null when it is idle. */
    val liveActivity: String? = null,
    /** Titles of the tools switched on for it that exist in the registry. */
    val tools: List<String> = emptyList(),
    /** It has a workspace tool switched on, so its files are in the workspace. */
    val usesWorkspace: Boolean = false,
    /** How many memories are in its private memory; null when the memory system is not available. */
    val privateMemoryCount: Int? = null,
    /** Names of the shared spaces its policy lets it read, and write. Spaces that no longer exist are left out. */
    val readableSpaces: List<String> = emptyList(),
    val writableSpaces: List<String> = emptyList(),
    /** The newest tasks given to it in objectives (for the Director, also the objectives themselves). */
    val recentTasks: List<AgentTaskUi> = emptyList(),
) {
    /** Only an agent with the Director role can be designated, and only if it is not already. */
    val canDesignateAsDirector: Boolean get() = profile.kind == AgentKind.DIRECTOR && !isDesignatedDirector

    /** Why this agent cannot be deleted, or null when it can. */
    val deleteBlockedReason: String?
        get() = if (isDesignatedDirector) {
            "${profile.name} is the designated Director, the agent that receives your objectives. " +
                "Designate another Director first, then this one can be deleted."
        } else {
            null
        }

    val hasHistory: Boolean get() = stats != AgentStats()
}

/** A task an agent was given, as a row on its page. */
@Immutable
data class AgentTaskUi(
    val id: TaskId,
    val title: String,
    /** The objective it belongs to; null for an objective itself. */
    val objectiveTitle: String?,
    val kind: TaskKind,
    val status: StatusLabel,
    val time: String,
)

@Immutable
data class AgentDetailUiState(
    val isLoading: Boolean = true,
    /** Null after loading means the agent is not in the registry (deleted here or elsewhere). */
    val agent: AgentDetailUi? = null,
    /** Set once after this screen deleted the agent; the route leaves the screen. */
    val deleted: Boolean = false,
    /** Set once after a duplicate was created; the route opens the copy. */
    val duplicateId: AgentId? = null,
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
)

/** Shows one agent and performs the actions available on it through [AgentManager]. */
class AgentDetailViewModel(
    private val agentId: AgentId,
    directory: RegistryDirectory,
    private val agentManager: AgentManager,
    monitor: AgentActivityMonitor? = null,
    tools: List<ToolSpec> = emptyList(),
    memory: MemoryService? = null,
    tasks: TaskRepository? = null,
    private val clock: Clock = WallClock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private val toolTitles: Map<ToolId, String> = tools.associate { it.id to it.title }

    private data class Local(
        val deleted: Boolean = false,
        val duplicateId: AgentId? = null,
        val isBusy: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private val privateCount = memory?.observe(MemoryActor.Owner, MemoryFilter(scopes = setOf(MemoryScope.Agent(agentId))))?.map { it.size }
        ?: flowOf(null)

    /** Its tasks across the newest objectives, newest change first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val recentTasks: Flow<List<Pair<TaskRecord, String?>>> = tasks?.let { repository ->
        repository.observeObjectives().flatMapLatest { objectives ->
            val shown = objectives.take(RECENT_OBJECTIVES)
            if (shown.isEmpty()) flowOf(emptyList()) else combine(shown.map { repository.observeTree(it.id) }) { trees -> trees.flatMap { it } }
        }.map { all ->
            val titles = all.filter { it.kind == TaskKind.OBJECTIVE }.associate { it.id to it.title }
            all.filter { it.assignee == agentId }.sortedByDescending { it.updatedAt }.take(RECENT_TASKS)
                .map { task -> task to titles[task.rootId]?.takeIf { task.kind != TaskKind.OBJECTIVE } }
        }
    } ?: flowOf(emptyList())

    val uiState: StateFlow<AgentDetailUiState> = combine(
        directory.observe(),
        monitor?.states ?: flowOf(emptyMap()),
        local,
        combine(privateCount, recentTasks) { count, recent -> count to recent },
        memory?.observeSpaces() ?: flowOf(emptyList()),
    ) { snapshot, working, local, (privateCount, recent), spaces ->
        val entry = snapshot.agentsById[agentId]
        val spaceNames = spaces.associate { it.id to it.name }
        AgentDetailUiState(
            isLoading = false,
            agent = entry?.let {
                AgentDetailUi(
                    profile = it.profile,
                    status = it.availability.toStatusLabel(),
                    stats = it.stats,
                    isDesignatedDirector = it.isDesignatedDirector,
                    model = it.profile.modelId?.let(snapshot::modelRef),
                    fallbackModels = it.profile.fallbackModelIds.map(snapshot::modelRef),
                    modelsAvailable = snapshot.models.isNotEmpty(),
                    liveActivity = working[agentId]?.let { state -> state.detail ?: "Working" },
                    tools = it.profile.toolIds.mapNotNull(toolTitles::get).sorted(),
                    usesWorkspace = it.profile.toolIds.any { id -> id.value.startsWith("workspace.") },
                    privateMemoryCount = privateCount,
                    readableSpaces = it.profile.memory.readableSpaces.mapNotNull(spaceNames::get).sorted(),
                    writableSpaces = it.profile.memory.writableSpaces.mapNotNull(spaceNames::get).sorted(),
                    recentTasks = recent.map { (task, objectiveTitle) ->
                        AgentTaskUi(
                            id = task.id,
                            title = task.title,
                            objectiveTitle = objectiveTitle,
                            kind = task.kind,
                            status = task.status.toStatusLabel(task.kind),
                            time = formatCompactTimestamp(task.updatedAt, clock.now(), zone),
                        )
                    },
                )
            },
            deleted = local.deleted,
            duplicateId = local.duplicateId,
            isBusy = local.isBusy,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentDetailUiState())

    fun setEnabled(enabled: Boolean) = run { agentManager.setEnabled(agentId, enabled) }

    fun designateAsDirector() = run(successMessage = "This agent is now the designated Director.") {
        agentManager.designateDirector(agentId)
    }

    fun duplicate() = run {
        val copy = agentManager.duplicate(agentId)
        local.update { it.copy(duplicateId = copy.id, message = message("Created \"${copy.name}\".")) }
    }

    fun delete() = run {
        agentManager.delete(agentId)
        local.update { it.copy(deleted = true) }
    }

    fun duplicateHandled() = local.update { it.copy(duplicateId = null) }

    fun messageShown() = local.update { it.copy(message = null) }

    /**
     * Runs one action at a time. A refusal from the manager (for example deleting the designated
     * Director) and a storage failure are both reported to the owner instead of being thrown.
     */
    private fun run(successMessage: String? = null, action: suspend () -> Unit) {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val failure: String? = try {
                action()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: AgentOperationException) {
                refused.message ?: "That is not possible right now."
            } catch (failure: Exception) {
                "Something went wrong: ${failure.message ?: "storage error"}"
            }
            local.update {
                val text = failure ?: successMessage
                it.copy(isBusy = false, message = if (text != null) message(text) else it.message)
            }
        }
    }

    private fun message(text: String) = UserMessage(text, ++messageCounter)

    private companion object {
        const val RECENT_OBJECTIVES = 20
        const val RECENT_TASKS = 6
    }
}
