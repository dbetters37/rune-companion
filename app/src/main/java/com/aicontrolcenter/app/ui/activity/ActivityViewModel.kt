package com.aicontrolcenter.app.ui.activity

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.formatDayHeading
import com.aicontrolcenter.app.ui.components.formatTimeOfDay
import com.aicontrolcenter.app.ui.components.localDateOf
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.ui.components.textChanges
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityCursor
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** The owner's choices for narrowing the log. An empty [types] means every type. */
@Immutable
data class ActivityFilterUi(
    val types: Set<ActivityType> = emptySet(),
    val minSeverity: Severity = Severity.INFO,
    /** Only events that moved information off the device. */
    val egressOnly: Boolean = false,
    /** Only one agent's events (a Usage row's link). */
    val agentId: AgentId? = null,
    /** Only events whose title or detail holds this text (Tier B search; K10). Blank: no text filter. */
    val text: String = "",
) {
    val isActive: Boolean get() = types.isNotEmpty() || minSeverity != Severity.INFO || egressOnly || agentId != null || text.isNotBlank()

    fun toCore() = ActivityFilter(
        types = types, agentId = agentId, minSeverity = minSeverity, egressOnly = egressOnly, text = text.trim().ifEmpty { null },
    )
}

/** The events of one calendar day, newest first. */
@Immutable
data class ActivityDayGroup(val heading: String, val rows: List<ActivityRowUi>)

@Immutable
data class ActivityUiState(
    val isLoading: Boolean = true,
    val groups: List<ActivityDayGroup> = emptyList(),
    val filter: ActivityFilterUi = ActivityFilterUi(),
    /** The agent of [ActivityFilterUi.agentId], by name, for its chip. */
    val agentName: String? = null,
    /** The event open in the inspector, if any. */
    val selected: EventDetailUi? = null,
    /** True when older matching events may exist beyond those shown ("Show older", D41). */
    val canShowOlder: Boolean = false,
    /** True while the next older page is read. */
    val loadingOlder: Boolean = false,
    /**
     * Once older pages are shown the list holds still; this counts the events recorded since, for "Show the newest"
     * (at most one page: [newerCapped] says there may be more).
     */
    val newer: Int = 0,
    val newerCapped: Boolean = false,
) {
    val eventCount: Int get() = groups.sumOf { it.rows.size }
}

/**
 * The execution log (D41, K10): newest first, grouped by day, filterable and searchable, one page at a time.
 *
 * The newest page ([pageSize] events) follows the log as it grows. "Show older" reads the next page after the last event
 * shown ([ActivityLog.observePage] with an [ActivityCursor]); from then on the list holds still, so no event can slip
 * between pages while new ones arrive, and a line counts the events recorded since ("Show the newest" goes back to the
 * live list). Every page is read once, at most [pageSize] rows (rule 16). Changing a filter or the search starts again
 * from the newest page. State is built off the main thread ([computation], `Dispatchers.Default` in the app).
 *
 * @param initialAgent opens the log on one agent's events (a Usage row's link).
 * @param initialText opens the log searched for this text (a model's row: "<model> via <provider>").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(
    private val activityLog: ActivityLog,
    directory: RegistryDirectory,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val pageSize: Int = PAGE_SIZE,
    initialAgent: AgentId? = null,
    initialText: String? = null,
    computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {

    /** The search field's text (rule 27: the field reads and the keyboard writes it directly). */
    val search: TextFieldState = TextFieldState(initialText.orEmpty())

    private val filter = MutableStateFlow(ActivityFilterUi(agentId = initialAgent, text = initialText.orEmpty().trim()))
    private val selected = MutableStateFlow<ActivityEvent?>(null)

    /** The list held still after "Show older": the newest page as it was then, and the older pages read after it. */
    private data class Held(val key: ActivityFilterUi, val head: List<ActivityEvent>, val older: List<ActivityEvent>, val exhausted: Boolean) {
        val events: List<ActivityEvent> get() = head + older
    }

    private val held = MutableStateFlow<Held?>(null)
    private val loadingOlder = MutableStateFlow(false)
    private var olderJob: Job? = null

    /** The newest page, live, with the filter it was read for (so a page never shows under another filter). */
    private val head = filter.flatMapLatest { current ->
        activityLog.observePage(current.toCore(), before = null, limit = pageSize).map { current to it }
    }

    @Volatile private var latestHead: Pair<ActivityFilterUi, List<ActivityEvent>>? = null

    init {
        viewModelScope.launch {
            search.textChanges().distinctUntilChanged().collect { text ->
                val trimmed = text.trim()
                if (trimmed != filter.value.text) changeFilter { it.copy(text = trimmed) }
            }
        }
    }

    val uiState: StateFlow<ActivityUiState> =
        combine(head, held, loadingOlder, directory.observe(), selected) { (shownFilter, newest), heldPages, loading, snapshot, selected ->
            latestHead = shownFilter to newest
            val still = heldPages?.takeIf { it.key == shownFilter }
            val events = still?.events ?: newest
            val newer = still?.let { newerThan(newest, it.head) } ?: (0 to false)
            ActivityUiState(
                isLoading = false,
                groups = group(events, snapshot),
                filter = shownFilter,
                agentName = shownFilter.agentId?.let { id -> snapshot.agentsById[id]?.profile?.name ?: "an agent that no longer exists" },
                selected = selected?.toDetail(snapshot, zone()),
                canShowOlder = if (still != null) !still.exhausted else newest.size >= pageSize,
                loadingOlder = loading,
                newer = newer.first,
                newerCapped = newer.second,
            )
        }.flowOn(computation).conflate().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState(filter = filter.value))

    fun toggleType(type: ActivityType) = changeFilter {
        it.copy(types = if (type in it.types) it.types - type else it.types + type)
    }

    fun setMinSeverity(severity: Severity) = changeFilter { it.copy(minSeverity = severity) }

    fun setEgressOnly(egressOnly: Boolean) = changeFilter { it.copy(egressOnly = egressOnly) }

    /** Shows every agent's events again. */
    fun clearAgent() = changeFilter { it.copy(agentId = null) }

    fun clearFilters() {
        search.putText("")
        changeFilter { ActivityFilterUi() }
    }

    /** Opens the log on [agentId]'s events or on [text] (a Usage row's link), with no other filter. */
    fun showOnly(agentId: AgentId?, text: String?) {
        search.putText(text.orEmpty())
        changeFilter { ActivityFilterUi(agentId = agentId, text = text.orEmpty().trim()) }
    }

    /** Reads the next page of older events after the last one shown; the list holds still from now on. */
    fun showOlder() {
        if (olderJob?.isActive == true) return
        val (shownFilter, newest) = latestHead ?: return
        val current = held.value?.takeIf { it.key == shownFilter }
        if (current?.exhausted == true) return
        val base = current?.events ?: newest
        val last = base.lastOrNull() ?: return
        olderJob = viewModelScope.launch {
            loadingOlder.value = true
            try {
                val next = activityLog.observePage(shownFilter.toCore(), ActivityCursor.after(last), pageSize).first()
                if (filter.value == shownFilter) {
                    held.value = Held(shownFilter, current?.head ?: newest, (current?.older ?: emptyList()) + next, exhausted = next.size < pageSize)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing more can be read now; the button stays for another try.
            } finally {
                loadingOlder.value = false
            }
        }
    }

    /** Back to the live list of the newest events. */
    fun showNewest() {
        olderJob?.cancel()
        held.value = null
    }

    /** Opens the inspector for [event]. The event is held as it was, so it stays readable if it later leaves the list. */
    fun select(event: ActivityEvent) = selected.update { event }

    fun closeDetail() = selected.update { null }

    private fun changeFilter(change: (ActivityFilterUi) -> ActivityFilterUi) {
        olderJob?.cancel()
        held.value = null
        filter.update(change)
    }

    /** How many of [newest] were recorded after the first event of [heldHead], and whether there may be more. */
    private fun newerThan(newest: List<ActivityEvent>, heldHead: List<ActivityEvent>): Pair<Int, Boolean> {
        val top = heldHead.firstOrNull() ?: return newest.size to (newest.size >= pageSize)
        val index = newest.indexOfFirst { it.id == top.id }
        return if (index >= 0) index to false else newest.count { it.at >= top.at && it.id != top.id } to (newest.size >= pageSize)
    }

    private fun group(events: List<ActivityEvent>, snapshot: RegistrySnapshot): List<ActivityDayGroup> {
        val zone = zone()
        val today = localDateOf(clock.now(), zone)
        return events
            .groupBy { localDateOf(it.at, zone) }
            .toSortedMap(compareByDescending { it })
            .map { (day, dayEvents) ->
                ActivityDayGroup(
                    heading = formatDayHeading(day, today),
                    rows = dayEvents.map { event ->
                        ActivityRowUi(
                            event = event,
                            time = formatTimeOfDay(event.at, zone),
                            agentName = event.agentId?.let { snapshot.agentsById[it]?.profile?.name },
                        )
                    },
                )
            }
    }

    companion object {
        /** Events per page: what the log shows first, and what each "Show older" adds. */
        const val PAGE_SIZE = 100
    }
}
