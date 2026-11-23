package com.aicontrolcenter.app.ui.memory

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.RecallQuery
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.ZoneId

/** Which memories the explorer shows by where they live. One at a time, so the meaning is never a guess. */
sealed interface ScopeFilter {
    data object All : ScopeFilter

    /** One agent's private memory, and its task memory when that is included. */
    data class Agent(val id: AgentId) : ScopeFilter

    data class Space(val id: SpaceId) : ScopeFilter
}

@Immutable
data class ExplorerFilters(
    val query: String = "",
    val scope: ScopeFilter = ScopeFilter.All,
    /** Short-term working memory of tasks; on by default so nothing is hidden. */
    val includeTaskMemory: Boolean = true,
    /** Empty: every kind. */
    val kinds: Set<MemoryKind> = emptySet(),
) {
    val isFiltered: Boolean get() = query.isNotBlank() || scope != ScopeFilter.All || !includeTaskMemory || kinds.isNotEmpty()

    fun matches(record: MemoryRecord): Boolean = matchesScope(record.scope) && (kinds.isEmpty() || record.kind in kinds)

    private fun matchesScope(scope: MemoryScope): Boolean = when (scope) {
        is MemoryScope.Agent -> this.scope == ScopeFilter.All || this.scope == ScopeFilter.Agent(scope.agentId)
        is MemoryScope.Shared -> this.scope == ScopeFilter.All || this.scope == ScopeFilter.Space(scope.spaceId)
        is MemoryScope.Task -> includeTaskMemory && (this.scope == ScopeFilter.All || this.scope == ScopeFilter.Agent(scope.agentId))
    }
}

/** An agent or space the explorer can be narrowed to. */
@Immutable
data class ScopeOptionUi(val filter: ScopeFilter, val label: String, val colorIndex: Int, val kind: ScopeKind)

@Immutable
data class ExplorerUiState(
    val isLoading: Boolean = true,
    val filters: ExplorerFilters = ExplorerFilters(),
    val agentOptions: List<ScopeOptionUi> = emptyList(),
    val spaceOptions: List<ScopeOptionUi> = emptyList(),
    val rows: List<MemoryRowUi> = emptyList(),
    /** Every memory the owner has, whatever the filters. */
    val totalCount: Int = 0,
    val isSearching: Boolean = false,
    /** The last search could not run; the list is empty for that reason, not because nothing matched. */
    val searchFailed: Boolean = false,
)

/**
 * The memory explorer: every memory the owner can see, narrowed by scope, kind and task memory,
 * and ranked by the engine's recall (meaning, keywords, recency and importance) when a search
 * is typed.
 *
 * Searching is browsing, so it never counts as using a memory (`recordAccess = false`): looking
 * something up must not make it rank higher for the agents. A typed query waits
 * [searchDelayMs] for the next keystroke before it runs; a change to the stored memories
 * re-runs the current search at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryExplorerViewModel(
    private val memory: MemoryService,
    directory: RegistryDirectory,
    private val clock: Clock,
    initialScope: ScopeFilter = ScopeFilter.All,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val searchDelayMs: Long = SEARCH_DELAY_MS,
) : ViewModel() {
    private val filters = MutableStateFlow(ExplorerFilters(scope = initialScope))
    private val searching = MutableStateFlow(false)
    private var lastQuery = ""

    private data class Inputs(
        val memories: List<MemoryRecord>,
        val spaces: List<KnowledgeSpace>,
        val snapshot: RegistrySnapshot,
        val filters: ExplorerFilters,
    )

    private val built = combine(memory.observe(MemoryActor.Owner), memory.observeSpaces(), directory.observe(), filters) { memories, spaces, snapshot, f ->
        Inputs(memories, spaces, snapshot, f)
    }.mapLatest { inputs ->
        val query = inputs.filters.query.trim()
        if (query.isNotEmpty() && query != lastQuery) {
            searching.value = true
            delay(searchDelayMs)
        }
        lastQuery = query
        build(inputs).also { searching.value = false }
    }

    val uiState: StateFlow<ExplorerUiState> = combine(built, searching) { state, isSearching -> state.copy(isSearching = isSearching) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExplorerUiState(filters = filters.value))

    fun setQuery(query: String) = filters.update { it.copy(query = query) }

    /** Selecting the current scope again goes back to everything. */
    fun setScope(scope: ScopeFilter) = filters.update { it.copy(scope = if (it.scope == scope) ScopeFilter.All else scope) }

    fun toggleKind(kind: MemoryKind) = filters.update { if (kind in it.kinds) it.copy(kinds = it.kinds - kind) else it.copy(kinds = it.kinds + kind) }

    fun setIncludeTaskMemory(include: Boolean) = filters.update { it.copy(includeTaskMemory = include) }

    fun clearFilters() = filters.update { ExplorerFilters() }

    private suspend fun build(inputs: Inputs): ExplorerUiState {
        val f = inputs.filters
        val names = MemoryNames.of(inputs.snapshot, inputs.spaces)
        val now = clock.now()
        val inView = inputs.memories.filter(f::matches)
        val query = f.query.trim()
        var failed = false
        val rows = if (query.isEmpty()) {
            inView.map { row(it, names, now, relevance = null) }
        } else {
            // Recall takes exact scopes; the ones in view are exactly those of the memories that pass the filters.
            val scopes = inView.mapTo(HashSet()) { it.scope }
            if (scopes.isEmpty()) {
                emptyList()
            } else {
                try {
                    memory.recall(
                        MemoryActor.Owner,
                        RecallQuery(text = query, limit = MAX_RESULTS, scopes = scopes, kinds = f.kinds, recordAccess = false),
                    ).filter { f.matches(it.record) }.map { row(it.record, names, now, relevance = it.score) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed = true
                    emptyList()
                }
            }
        }
        return ExplorerUiState(
            isLoading = false,
            filters = f,
            agentOptions = inputs.snapshot.agents.map {
                ScopeOptionUi(ScopeFilter.Agent(it.profile.id), it.profile.name, it.profile.colorIndex, ScopeKind.PRIVATE)
            },
            spaceOptions = inputs.spaces.sortedBy { it.name.lowercase() }.map {
                ScopeOptionUi(ScopeFilter.Space(it.id), it.name, spaceColorIndex(it.id), ScopeKind.SHARED)
            },
            rows = rows,
            totalCount = inputs.memories.size,
            searchFailed = failed,
        )
    }

    private fun row(record: MemoryRecord, names: MemoryNames, now: Long, relevance: Double?) = MemoryRowUi(
        id = record.id,
        title = record.title,
        preview = previewOf(record.content),
        scope = names.scope(record.scope),
        kind = record.kind,
        importance = record.importance,
        date = formatCompactTimestamp(record.updatedAt, now, zone),
        shortTerm = record.tier == MemoryTier.SHORT_TERM,
        relevance = relevance,
    )

    companion object {
        const val SEARCH_DELAY_MS = 300L

        /** The engine's own cap on one recall. */
        const val MAX_RESULTS = 50
    }
}
