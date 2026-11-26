package com.aicontrolcenter.app.ui.memory.graph

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.memory.MemoryNames
import com.aicontrolcenter.app.ui.memory.ScopeFilter
import com.aicontrolcenter.app.ui.memory.ScopeKind
import com.aicontrolcenter.app.ui.memory.ScopeOptionUi
import com.aicontrolcenter.app.ui.memory.ScopeUi
import com.aicontrolcenter.app.ui.memory.spaceColorIndex
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.EgressInfo
import com.aicontrolcenter.core.memory.GraphNode
import com.aicontrolcenter.core.memory.GraphRequest
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryGraphSnapshot
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.settings.SettingsRepository
import com.aicontrolcenter.memory.ForceLayout
import com.aicontrolcenter.memory.MemoryGraphJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which memories the graph draws. Nothing selected draws everything; selecting agents and spaces
 * draws only those, so two clusters can be compared side by side.
 */
@Immutable
data class GraphFilters(
    val agents: Set<AgentId> = emptySet(),
    val spaces: Set<SpaceId> = emptySet(),
    val includeTaskMemory: Boolean = true,
) {
    val showsEverything: Boolean get() = agents.isEmpty() && spaces.isEmpty()

    /**
     * The engine's request. Its sets mean "no filter" when null and "leave this kind out" when
     * empty, so a selection of agents only must pass an empty set of spaces, and the reverse.
     */
    fun request(maxNodes: Int): GraphRequest =
        if (showsEverything) {
            GraphRequest(includeTaskMemory = includeTaskMemory, maxNodes = maxNodes)
        } else {
            GraphRequest(agentIds = agents, spaceIds = spaces, includeTaskMemory = includeTaskMemory, maxNodes = maxNodes)
        }
}

/** The tapped node, for the card under the graph. */
@Immutable
data class SelectedNodeUi(val index: Int, val memoryId: MemoryId, val title: String, val scope: ScopeUi, val kind: MemoryKind?, val linkCount: Int)

/** An export ready to be written and handed to the share sheet. */
@Immutable
data class GraphExportUi(
    val fileName: String,
    val json: String,
    val nodeCount: Int,
    val edgeCount: Int,
    /** Exported while local-only mode was on (allowed: it is the owner's own act, rule 21). */
    val localOnly: Boolean = false,
)

@Immutable
data class GraphUiState(
    val isLoading: Boolean = true,
    val filters: GraphFilters = GraphFilters(),
    val agentOptions: List<ScopeOptionUi> = emptyList(),
    val spaceOptions: List<ScopeOptionUi> = emptyList(),
    val render: GraphRender = GraphRender(),
    /** True when the view holds more memories than it draws; the most important and recent are drawn. */
    val truncated: Boolean = false,
    /** The graph could not be built; the canvas shows why instead of an empty picture. */
    val failed: Boolean = false,
    val selected: SelectedNodeUi? = null,
    val isExporting: Boolean = false,
    val pendingExport: GraphExportUi? = null,
    val message: UserMessage? = null,
    /** The line under the export button (rule 21): the receiving app may send the file anywhere. */
    val exportNote: String = MemoryGraphViewModel.EXPORT_NOTE,
)

/**
 * The memory graph: [MemoryService.graph] for the owner, laid out by the engine's [ForceLayout]
 * on [computation] (never the main thread: hundreds of nodes take a noticeable fraction of a
 * second), re-built whenever memories, spaces, agents or the filters change.
 *
 * Export turns the same view into the neutral "aicc.memory-graph" JSON ([MemoryGraphJson]) for
 * external graph viewers. It hands memory content to another app, which may send it anywhere.
 * It is the owner's own act through Android's share sheet, so it is allowed in local-only mode
 * too, with the line "The app you choose may send it off the phone" (rule 21, D37); agents'
 * hand-overs stay blocked there. Every hand-off is logged as data leaving the device. The log
 * names counts and the format, never what the memories say.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryGraphViewModel(
    private val memory: MemoryService,
    directory: RegistryDirectory,
    private val settings: SettingsRepository,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {
    private data class Loaded(
        val render: GraphRender = GraphRender(),
        val agentOptions: List<ScopeOptionUi> = emptyList(),
        val spaceOptions: List<ScopeOptionUi> = emptyList(),
        val truncated: Boolean = false,
        val failed: Boolean = false,
        val filters: GraphFilters = GraphFilters(),
    )

    private data class Local(val isExporting: Boolean = false, val pendingExport: GraphExportUi? = null, val message: UserMessage? = null)

    private val filters = MutableStateFlow(GraphFilters())
    private val selectedId = MutableStateFlow<String?>(null)
    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private val loaded = combine(memory.observe(MemoryActor.Owner), memory.observeSpaces(), directory.observe(), filters) { memories, spaces, snapshot, f ->
        Inputs(memories, spaces, snapshot, f)
    }.mapLatest(::load)

    private data class Inputs(val memories: List<MemoryRecord>, val spaces: List<KnowledgeSpace>, val snapshot: RegistrySnapshot, val filters: GraphFilters)

    val uiState: StateFlow<GraphUiState> = combine(loaded, selectedId, local) { data, selected, local ->
        val index = data.render.indexOf(selected)
        GraphUiState(
            isLoading = false,
            filters = data.filters,
            agentOptions = data.agentOptions,
            spaceOptions = data.spaceOptions,
            render = data.render,
            truncated = data.truncated,
            failed = data.failed,
            selected = index?.let {
                val node = data.render.nodes[it]
                SelectedNodeUi(it, node.memoryId, node.label, node.scope, node.kind, data.render.neighbours(it).size)
            },
            isExporting = local.isExporting,
            pendingExport = local.pendingExport,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GraphUiState())

    private suspend fun load(inputs: Inputs): Loaded {
        val names = MemoryNames.of(inputs.snapshot, inputs.spaces)
        val base = Loaded(
            agentOptions = inputs.snapshot.agents.map { ScopeOptionUi(ScopeFilter.Agent(it.profile.id), it.profile.name, it.profile.colorIndex, ScopeKind.PRIVATE) },
            spaceOptions = inputs.spaces.sortedBy { it.name.lowercase() }.map { ScopeOptionUi(ScopeFilter.Space(it.id), it.name, spaceColorIndex(it.id), ScopeKind.SHARED) },
            filters = inputs.filters,
        )
        return try {
            val snapshot = memory.graph(MemoryActor.Owner, inputs.filters.request(MAX_VIEW_NODES))
            val positions = withContext(computation) { layoutOf(snapshot) }
            val records = inputs.memories.associateBy { it.id }
            base.copy(render = renderOf(snapshot, positions, records, names), truncated = snapshot.truncated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            base.copy(failed = true)
        }
    }

    /** Selecting the selected node again clears the selection. */
    fun selectNode(nodeId: String?) = selectedId.update { if (it == nodeId) null else nodeId }

    /** Adds or removes an agent's cluster from the view. */
    fun toggleAgent(id: AgentId) = filters.update { f -> f.copy(agents = if (id in f.agents) f.agents - id else f.agents + id) }

    fun toggleSpace(id: SpaceId) = filters.update { f -> f.copy(spaces = if (id in f.spaces) f.spaces - id else f.spaces + id) }

    fun showEverything() = filters.update { it.copy(agents = emptySet(), spaces = emptySet()) }

    fun setIncludeTaskMemory(include: Boolean) = filters.update { it.copy(includeTaskMemory = include) }

    /** Prepares the export of what the view shows (up to [MAX_EXPORT_NODES] memories); the route writes and shares it. */
    fun exportGraph() {
        if (local.value.isExporting || local.value.pendingExport != null) return
        local.update { it.copy(isExporting = true) }
        viewModelScope.launch {
            try {
                // Rule 21 (D37): the export is the owner's own act through Android's share sheet, so it is allowed in
                // local-only mode too; the owner is told that the app they choose may send it off the phone.
                val localOnly = settings.get().localOnlyMode
                val snapshot = memory.graph(MemoryActor.Owner, filters.value.request(MAX_EXPORT_NODES))
                val json = MemoryGraphJson.encode(snapshot, prettyPrint = true)
                val export = GraphExportUi(fileNameFor(clock.now(), zone), json, snapshot.nodes.size, snapshot.edges.size, localOnly = localOnly)
                local.update {
                    it.copy(isExporting = false, pendingExport = export, message = if (localOnly) message(LOCAL_ONLY_EXPORT) else it.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                local.update { it.copy(isExporting = false, message = message("The graph could not be exported: ${failure.message ?: "storage error"}")) }
            }
        }
    }

    /**
     * Called by the route once it tried to share the pending export. [failure] null means the
     * share sheet opened with the file, which is recorded as data leaving the device.
     */
    fun exportHandled(failure: String?) {
        val export = local.value.pendingExport ?: return
        local.update { it.copy(pendingExport = null, message = failure?.let(::message) ?: it.message) }
        if (failure != null) return
        viewModelScope.launch {
            try {
                activity.record(
                    ActivityType.DATA_EGRESS,
                    "Memory graph exported",
                    "${export.nodeCount} memories and ${export.edgeCount} links were handed to the share sheet as an " +
                        "aicc.memory-graph file, full text included. Where they go is up to the app you chose." +
                        if (export.localOnly) " Local-only mode was on; this was your own export." else "",
                    egress = EgressInfo(
                        destinationHost = SHARE_SHEET_DESTINATION,
                        // The receiving app is unknown, so assume the widest reach.
                        locality = Locality.CLOUD,
                        purpose = "memory graph export (aicc.memory-graph JSON)",
                        approxBytes = export.json.toByteArray().size.toLong(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The export already happened; a log that cannot be written must not undo it or crash.
            }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun message(text: String) = UserMessage(text, ++messageCounter)

    companion object {
        /** The view stays readable and fast up to here; the most important and most recent memories are kept. */
        const val MAX_VIEW_NODES = 400

        const val MAX_EXPORT_NODES = 10_000

        /** Under the export button, and said once more when exporting in local-only mode (rule 21). */
        const val EXPORT_NOTE = "The app you choose may send it off the phone."

        const val LOCAL_ONLY_EXPORT = "Local-only mode keeps agents' work on this phone; this export is yours. $EXPORT_NOTE"

        /** Marks an egress whose destination is an app the owner picks in the Android share sheet. */
        const val SHARE_SHEET_DESTINATION = "app:share-sheet"

        private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT)

        /** The layout the view uses. Pure and deterministic, so the same snapshot always gives the same picture. */
        fun layoutOf(snapshot: MemoryGraphSnapshot) = ForceLayout().layout(snapshot)

        /** "memory-graph-20261114-1200.aicc-graph.json": sortable, and says what it is to whoever receives it. */
        fun fileNameFor(now: Long, zone: ZoneId): String = "memory-graph-" + fileStamp.format(Instant.ofEpochMilli(now).atZone(zone)) + ".aicc-graph.json"

        internal fun renderOf(
            snapshot: MemoryGraphSnapshot,
            positions: Map<String, com.aicontrolcenter.memory.LayoutPoint>,
            records: Map<MemoryId, MemoryRecord>,
            names: MemoryNames,
        ): GraphRender {
            val nodes = snapshot.nodes.map { node ->
                val memoryId = MemoryId(node.id.removePrefix(NODE_PREFIX))
                val point = positions[node.id]
                GraphNodeUi(
                    id = node.id,
                    memoryId = memoryId,
                    label = node.label,
                    scope = records[memoryId]?.let { names.scope(it.scope) } ?: scopeOf(node, names),
                    kind = MemoryKind.entries.firstOrNull { it.name.equals(node.subkind, ignoreCase = true) },
                    weight = node.weight,
                    activation = node.activation,
                    x = (point?.x ?: 0.5).toFloat(),
                    y = (point?.y ?: 0.5).toFloat(),
                )
            }
            val index = nodes.withIndex().associate { (i, n) -> n.id to i }
            val edges = snapshot.edges.mapNotNull { edge ->
                val a = index[edge.source] ?: return@mapNotNull null
                val b = index[edge.target] ?: return@mapNotNull null
                GraphEdgeUi(edge.id, a, b, LinkRelation.entries.firstOrNull { it.name == edge.relation }, edge.weight, edge.directed)
            }
            return GraphRender(nodes, edges)
        }

        /** For a node whose memory is not in the owner's list yet (it arrived between two reads): its scope from the snapshot. */
        private fun scopeOf(node: GraphNode, names: MemoryNames): ScopeUi {
            val owner = node.ownerAgentId?.let(::AgentId)
            val space = node.clusterIds.firstOrNull { it.startsWith(SPACE_CLUSTER) }?.removePrefix(SPACE_CLUSTER)?.let(::SpaceId)
            return when {
                node.visibility == "shared" && space != null -> ScopeUi(ScopeKind.SHARED, names.spaceName(space), spaceColorIndex(space), spaceId = space)
                node.visibility == "task" && owner != null ->
                    ScopeUi(ScopeKind.TASK, names.agentName(owner) + ScopeUi.TASK_SUFFIX, names.agents[owner]?.colorIndex ?: 0, agentId = owner)
                owner != null -> ScopeUi(ScopeKind.PRIVATE, names.agentName(owner), names.agents[owner]?.colorIndex ?: 0, agentId = owner)
                else -> ScopeUi(ScopeKind.SHARED, "Unknown", 0)
            }
        }

        private const val NODE_PREFIX = "mem:"
        private const val SPACE_CLUSTER = "cl:space:"
    }
}
