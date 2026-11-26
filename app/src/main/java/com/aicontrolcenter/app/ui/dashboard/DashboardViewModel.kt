package com.aicontrolcenter.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.dashboard.network.AgentTraffic
import com.aicontrolcenter.app.ui.dashboard.network.toAgentNetwork
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.agents.AgentRuntimeState
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.Orchestrator
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskSummary
import com.aicontrolcenter.app.ui.team.TeamWords
import com.aicontrolcenter.core.memory.MemoryFilter
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import com.aicontrolcenter.core.tasks.TaskRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import com.aicontrolcenter.app.ui.models.describe
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.app.ui.agents.assignModelToAgentsWithoutOne
import com.aicontrolcenter.app.ui.agents.summariseNames
import com.aicontrolcenter.core.agents.AgentActivityMonitor
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.framework.llm.Simulator
import kotlinx.coroutines.flow.flowOf
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryService
import kotlinx.coroutines.flow.Flow
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.registry.AiAppRegistry
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Turns the live registry and activity log into the dashboard's state. All figures, the
 * attention list and the network are derived from one [com.aicontrolcenter.core.registry.RegistrySnapshot],
 * so they always describe the same moment.
 *
 * Phase 6 (WP9): built off the main thread when [computation] is the app's pool and bursts of changes build once (P2);
 * running objectives are read as summaries and messages as headers (N6); memories are counted, not read (P4); an agent
 * whose run waits for the owner or a teammate is shown waiting, not working (N3).
 */
class DashboardViewModel(
    private val directory: RegistryDirectory,
    activityLog: ActivityLog,
    private val aiAppRegistry: AiAppRegistry,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    monitor: AgentActivityMonitor? = null,
    private val agentManager: AgentManager? = null,
    memory: MemoryService? = null,
    /** With [tasks] and [messages]: what the team is doing on objectives. Null in tests of the registry alone. */
    orchestrator: Orchestrator? = null,
    tasks: TaskRepository? = null,
    messages: MessageRepository? = null,
    /**
     * Counts memories without reading them (P4, D35: the store's COUNT query, `MemoryRepository.observeCount`); without
     * it the memories are read through [memory] and counted here.
     */
    memoryCount: ((MemoryFilter) -> Flow<Int>)? = null,
    /** Where the state is built (the app's background pool, P2); tests stay on their own dispatcher. */
    computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {

    private data class Local(
        val selectedNodeId: String? = null,
        val isScanning: Boolean = false,
        val isAssigning: Boolean = false,
        val message: UserMessage? = null,
    )

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private val memorySummary: Flow<MemorySummaryUi?> = memory?.let { service ->
        val count = memoryCount?.invoke(MemoryFilter()) ?: service.observe(MemoryActor.Owner).map { it.size }
        combine(count.distinctUntilChanged(), service.observeSpaces().map { it.size }.distinctUntilChanged()) { memories, spaces -> MemorySummaryUi(memories, spaces) }
    } ?: flowOf(null)

    /**
     * The tasks of running objectives as summaries, and the newest agent-to-agent messages among them as headers (N6,
     * rule 16): never whole task rows or message bodies.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val orchestration: Flow<Orchestration> = if (orchestrator == null || tasks == null || messages == null) {
        flowOf(Orchestration())
    } else {
        val summaries = orchestrator.running.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else combine(ids.map { tasks.observeSummaries(it) }) { all -> all.flatMap { it } }
        }
        combine(orchestrator.running, summaries, messages.observeRecentHeaders(RECENT_MESSAGE_LIMIT)) { running, tasks, recent ->
            Orchestration(
                tasks = tasks.associateBy { it.id },
                traffic = recent.filter { it.rootId in running }.mapNotNull { message ->
                    val from = (message.from as? Participant.Agent)?.agentId ?: return@mapNotNull null
                    val to = (message.to as? Participant.Agent)?.agentId ?: return@mapNotNull null
                    AgentTraffic(from, to, message.id.value)
                },
            )
        }
    }

    private data class Orchestration(val tasks: Map<TaskId, TaskSummary> = emptyMap(), val traffic: List<AgentTraffic> = emptyList())

    val uiState: StateFlow<DashboardUiState> = combine(
        directory.observe(),
        activityLog.observe(limit = RECENT_ACTIVITY_LIMIT),
        monitor?.states ?: flowOf(emptyMap()),
        local,
        combine(memorySummary, orchestration) { memory, orchestration -> memory to orchestration },
    ) { snapshot, events, working, local, (memorySummary, orchestration) ->
        val network = snapshot.toAgentNetwork(working, orchestration.traffic)
        val now = clock.now()
        DashboardUiState(
            isLoading = false,
            summary = snapshot.toSummary(),
            quickStart = snapshot.quickStart(),
            working = snapshot.agents.mapNotNull { entry ->
                working[entry.profile.id]?.let { state -> workingAgent(entry.profile, state, orchestration.tasks) }
            },
            attention = snapshot.attentionItems(),
            network = network,
            selectedNode = network.node(local.selectedNodeId),
            recentActivity = events.map { event ->
                ActivityRowUi(
                    event = event,
                    time = formatCompactTimestamp(event.at, now, zone),
                    agentName = event.agentId?.let { snapshot.agentsById[it]?.profile?.name },
                )
            },
            isScanning = local.isScanning,
            isAssigning = local.isAssigning,
            message = local.message,
            memory = memorySummary,
        )
    }.flowOn(computation).conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DashboardUiState())

    /**
     * An agent at work. When it works on an objective's task, the row names the task and the
     * step alone (the monitor's detail repeats the title as "Task: <title> · <step>").
     */
    private fun workingAgent(profile: AgentProfile, state: AgentRuntimeState, tasks: Map<TaskId, TaskSummary>): WorkingAgentUi {
        val task = state.currentTaskId?.let(tasks::get)
        val detail = state.detail ?: "Working"
        val step = task?.let { TeamWords.stepOf(detail, it.title) } ?: detail
        return WorkingAgentUi(
            agentId = profile.id,
            name = profile.name,
            colorIndex = profile.colorIndex,
            detail = step,
            taskId = task?.id,
            taskTitle = task?.title,
            objectiveTitle = task?.let { tasks[it.rootId]?.title },
            waiting = state.activity == AgentActivity.WAITING_FOR_AGENT || state.activity == AgentActivity.WAITING_FOR_APPROVAL,
        )
    }

    /** Selects a node of the network, or clears the selection with null. Tapping the selected node clears it. */
    fun selectNode(id: String?) {
        local.update { it.copy(selectedNodeId = if (id == it.selectedNodeId) null else id) }
    }

    /** Scans the device for AI applications and reports the outcome as a message. */
    fun scanForApps() {
        if (local.value.isScanning) return
        local.update { it.copy(isScanning = true) }
        viewModelScope.launch {
            val text = try {
                aiAppRegistry.discover().describe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "The scan failed: ${failure.message ?: "unknown error"}"
            }
            local.update { it.copy(isScanning = false, message = UserMessage(text, ++messageCounter)) }
        }
    }

    /**
     * Gives the built-in simulator to every agent that has no model, so the whole pipeline can be
     * tried before a real provider is connected. The screen confirms first that it is not a real model.
     */
    fun trySimulator() {
        val manager = agentManager ?: return
        if (local.value.isAssigning) return
        local.update { it.copy(isAssigning = true) }
        viewModelScope.launch {
            val text = try {
                val snapshot = directory.snapshot()
                val simulator = snapshot.modelsById[Simulator.MODEL_ID]
                when {
                    simulator == null -> "The simulator is not registered. Restart the app to bring it back."
                    !simulator.enabled || snapshot.provider(simulator)?.enabled != true ->
                        "The simulator is switched off. Switch it on under Models first."
                    else -> {
                        val names = assignModelToAgentsWithoutOne(manager, directory, Simulator.MODEL_ID)
                        if (names.isEmpty()) {
                            "Every agent already has a model."
                        } else {
                            "${summariseNames(names)} now use the simulator. Open an agent and tap Chat to try it."
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Could not assign the simulator: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isAssigning = false, message = UserMessage(text, ++messageCounter)) }
        }
    }

    fun messageShown() {
        local.update { it.copy(message = null) }
    }

    companion object {
        const val RECENT_ACTIVITY_LIMIT = 5

        /** Messages looked at for the network's edges and pulses. */
        const val RECENT_MESSAGE_LIMIT = 60
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
