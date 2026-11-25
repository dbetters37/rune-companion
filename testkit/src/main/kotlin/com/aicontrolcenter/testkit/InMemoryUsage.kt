package com.aicontrolcenter.testkit

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.AgentUsage
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.ModelUsage
import com.aicontrolcenter.core.usage.OwnerModelPrices
import com.aicontrolcenter.core.usage.OwnerUsage
import com.aicontrolcenter.core.usage.ProviderUsage
import com.aicontrolcenter.core.usage.UsageHousekeeping
import com.aicontrolcenter.core.usage.UsageLedger
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.core.usage.UsageTotals
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.core.usage.WorkUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.ZoneId

/**
 * Reference implementation of the usage ports (Phase 6, K11): one hour row per hour (UTC),
 * model, agent and source, and one work row per work item and model, changed together under one
 * state update, as Room does them in one transaction. [zone] is the owner's time zone, read at
 * each use (it can change); [clock] gives "today" for [clearHistory] and for the bound of
 * [pruneHours], which never deletes today's rows.
 */
class InMemoryUsageStore(
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : UsageLedger, UsageReports, OwnerUsage, UsageHousekeeping {
    private data class HourKey(val hourStart: Long, val modelId: ModelId, val agentId: AgentId?, val source: UsageSource)

    private data class HourRow(
        val modelLabel: String,
        val providerId: ProviderId,
        val providerLabel: String,
        val locality: Locality,
        val totals: UsageTotals,
        /** When it was last added to, in recording order, to pick the newest labels. */
        val seq: Long,
    )

    private data class WorkKey(val kind: WorkKind, val id: String, val modelId: ModelId)

    private data class State(
        val hours: Map<HourKey, HourRow> = emptyMap(),
        val work: Map<WorkKey, WorkUsage> = emptyMap(),
        val seq: Long = 0,
    )

    private val state = MutableStateFlow(State())

    /** Hour rows, for assertions: (hour start, model, agent, source) to totals. */
    val hourRows: Map<List<Any?>, UsageTotals>
        get() = state.value.hours.entries.associate { (k, v) -> listOf(k.hourStart, k.modelId, k.agentId, k.source) to v.totals }

    override suspend fun record(sample: ModelCallSample) = state.update { current ->
        val seq = current.seq + 1
        val key = HourKey(sample.hourStart, sample.modelId, sample.agentId, sample.source)
        val before = current.hours[key]?.totals ?: UsageTotals()
        val row = HourRow(sample.modelLabel, sample.providerId, sample.providerLabel, sample.locality, before + UsageTotals.of(sample), seq)
        val work = current.work.toMutableMap()
        for (item in sample.workItems) {
            val workKey = WorkKey(item.kind, item.id, sample.modelId)
            val old = work[workKey]
            work[workKey] = WorkUsage(
                kind = item.kind, workId = item.id, modelId = sample.modelId,
                calls = (old?.calls ?: 0) + 1,
                inputTokens = (old?.inputTokens ?: 0) + (sample.inputTokens ?: 0),
                outputTokens = (old?.outputTokens ?: 0) + (sample.outputTokens ?: 0),
                cachedInputTokens = (old?.cachedInputTokens ?: 0) + (sample.cachedInputTokens ?: 0),
                updatedAt = maxOf(old?.updatedAt ?: Long.MIN_VALUE, sample.at),
            )
        }
        State(current.hours + (key to row), work, seq)
    }

    override suspend fun totalsSince(fromMs: Long): UsageTotals =
        state.value.hours.filterKeys { it.hourStart >= fromMs }.values.fold(UsageTotals()) { sum, row -> sum + row.totals }

    override suspend fun pruneHours(beforeMs: Long): Int {
        // Never one of today's rows, whatever is asked: they seed the daily limits (F4).
        val until = minOf(beforeMs, UsageRange.day(clock.now(), zone()).fromMs)
        var removed = 0
        state.update { current ->
            val kept = current.hours.filterKeys { it.hourStart >= until }
            removed = current.hours.size - kept.size
            current.copy(hours = kept)
        }
        return removed
    }

    /** The in-memory store cannot tell which work still exists. */
    override suspend fun pruneOrphanedWork(): Int = 0

    override suspend fun clearHistory(): Int {
        val today = UsageRange.day(clock.now(), zone())
        var removed = 0
        state.update { current ->
            val kept = current.hours.filterKeys { it.hourStart >= today.fromMs }
            removed = current.hours.size - kept.size
            current.copy(hours = kept, work = current.work.filterValues { it.updatedAt >= today.fromMs })
        }
        return removed
    }

    private fun State.inRange(range: UsageRange) = hours.filterKeys { range.containsHour(it.hourStart) }

    private fun State.totalsIn(range: UsageRange) = inRange(range).values.fold(UsageTotals()) { sum, row -> sum + row.totals }

    private fun State.byModelIn(range: UsageRange): List<ModelUsage> =
        inRange(range).entries.groupBy { it.key.modelId }.map { (modelId, rows) ->
            val newest = rows.maxWith(compareBy({ it.key.hourStart }, { it.value.seq })).value
            ModelUsage(modelId, newest.modelLabel, newest.providerId, newest.providerLabel, newest.locality, rows.sumTotals())
        }.sortedWith(compareByDescending<ModelUsage> { it.totals.calls }.thenBy { it.modelId.value })

    private fun List<Map.Entry<HourKey, HourRow>>.sumTotals() = fold(UsageTotals()) { sum, row -> sum + row.value.totals }

    override suspend fun totals(range: UsageRange): UsageTotals = state.value.totalsIn(range)

    override fun observeTotals(range: UsageRange): Flow<UsageTotals> = state.map { it.totalsIn(range) }.distinctUntilChanged()

    override suspend fun byModel(range: UsageRange): List<ModelUsage> = state.value.byModelIn(range)

    override fun observeByModel(range: UsageRange): Flow<List<ModelUsage>> = state.map { it.byModelIn(range) }.distinctUntilChanged()

    override suspend fun byAgent(range: UsageRange): List<AgentUsage> =
        state.value.inRange(range).entries.groupBy { it.key.agentId }.map { (agentId, rows) -> AgentUsage(agentId, rows.sumTotals()) }
            .sortedWith(compareByDescending<AgentUsage> { it.totals.calls }.thenBy { it.agentId == null }.thenBy { it.agentId?.value })

    override suspend fun byProvider(range: UsageRange): List<ProviderUsage> =
        state.value.inRange(range).entries.groupBy { it.value.providerId }.map { (providerId, rows) ->
            val newest = rows.maxWith(compareBy({ it.key.hourStart }, { it.value.seq })).value
            ProviderUsage(providerId, newest.providerLabel, newest.locality, rows.sumTotals())
        }.sortedWith(compareByDescending<ProviderUsage> { it.totals.calls }.thenBy { it.providerId.value })

    private fun State.workOf(kind: WorkKind, id: String) =
        work.values.filter { it.kind == kind && it.workId == id }.sortedBy { it.modelId.value }

    override suspend fun workUsage(kind: WorkKind, id: String): List<WorkUsage> = state.value.workOf(kind, id)

    override fun observeWorkUsage(kind: WorkKind, id: String): Flow<List<WorkUsage>> = state.map { it.workOf(kind, id) }.distinctUntilChanged()
}

/** Reference implementation of [ModelPrices] and [OwnerModelPrices]: prices by model, stamped with [clock] when saved. */
class InMemoryModelPrices(private val clock: Clock) : ModelPrices, OwnerModelPrices {
    private val state = MutableStateFlow<Map<ModelId, ModelPrice>>(emptyMap())

    private fun Map<ModelId, ModelPrice>.ordered() = values.sortedBy { it.modelId.value }

    override fun observeAll(): Flow<List<ModelPrice>> = state.map { it.ordered() }.distinctUntilChanged()

    override suspend fun getAll(): List<ModelPrice> = state.value.ordered()

    override suspend fun get(modelId: ModelId): ModelPrice? = state.value[modelId]

    override suspend fun set(price: ModelPrice): OwnerResult<ModelPrice> {
        val stored = price.copy(updatedAt = clock.now())
        state.update { it + (stored.modelId to stored) }
        return OwnerResult.Done(stored)
    }

    override suspend fun remove(modelId: ModelId): Boolean {
        var removed = false
        state.update { current ->
            removed = modelId in current
            current - modelId
        }
        return removed
    }
}
