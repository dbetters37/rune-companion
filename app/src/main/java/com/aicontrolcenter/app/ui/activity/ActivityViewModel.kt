package com.aicontrolcenter.app.ui.activity

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.formatDayHeading
import com.aicontrolcenter.app.ui.components.formatTimeOfDay
import com.aicontrolcenter.app.ui.components.localDateOf
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityEvent
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityLog
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.ZoneId

/** The owner's choices for narrowing the log. An empty [types] means every type. */
@Immutable
data class ActivityFilterUi(
    val types: Set<ActivityType> = emptySet(),
    val minSeverity: Severity = Severity.INFO,
    /** Only events that moved information off the device. */
    val egressOnly: Boolean = false,
) {
    val isActive: Boolean get() = types.isNotEmpty() || minSeverity != Severity.INFO || egressOnly

    fun toCore() = ActivityFilter(types = types, minSeverity = minSeverity, egressOnly = egressOnly)
}

/** The events of one calendar day, newest first. */
@Immutable
data class ActivityDayGroup(val heading: String, val rows: List<ActivityRowUi>)

@Immutable
data class ActivityUiState(
    val isLoading: Boolean = true,
    val groups: List<ActivityDayGroup> = emptyList(),
    val filter: ActivityFilterUi = ActivityFilterUi(),
    /** The event open in the inspector, if any. */
    val selected: EventDetailUi? = null,
    /** True when the log holds more matching events than are loaded. */
    val truncated: Boolean = false,
) {
    val eventCount: Int get() = groups.sumOf { it.rows.size }
}

/** The execution log: newest first, grouped by day, filterable, with an inspector for one event. */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(
    activityLog: ActivityLog,
    directory: RegistryDirectory,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val limit: Int = DEFAULT_LIMIT,
) : ViewModel() {

    private val filter = MutableStateFlow(ActivityFilterUi())
    private val selected = MutableStateFlow<ActivityEvent?>(null)

    private val events = filter.flatMapLatest { current ->
        activityLog.observe(current.toCore(), limit).map { current to it }
    }

    val uiState: StateFlow<ActivityUiState> = combine(events, directory.observe(), selected) { (filter, events), snapshot, selected ->
        ActivityUiState(
            isLoading = false,
            groups = group(events, snapshot),
            filter = filter,
            selected = selected?.toDetail(snapshot, zone),
            truncated = events.size >= limit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun toggleType(type: ActivityType) = filter.update {
        it.copy(types = if (type in it.types) it.types - type else it.types + type)
    }

    fun setMinSeverity(severity: Severity) = filter.update { it.copy(minSeverity = severity) }

    fun setEgressOnly(egressOnly: Boolean) = filter.update { it.copy(egressOnly = egressOnly) }

    fun clearFilters() = filter.update { ActivityFilterUi() }

    /** Opens the inspector for [event]. The event is held as it was, so it stays readable if it later leaves the list. */
    fun select(event: ActivityEvent) = selected.update { event }

    fun closeDetail() = selected.update { null }

    private fun group(events: List<ActivityEvent>, snapshot: RegistrySnapshot): List<ActivityDayGroup> {
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
        const val DEFAULT_LIMIT = 300
    }
}
