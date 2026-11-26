package com.aicontrolcenter.data.repository

import android.database.SQLException
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.usage.AgentModelUsage
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
import com.aicontrolcenter.core.usage.UsageTotals
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.core.usage.WorkUsage
import com.aicontrolcenter.data.db.AiccDatabase
import com.aicontrolcenter.data.db.dao.UsageDao
import com.aicontrolcenter.data.db.entity.ModelPriceEntity
import com.aicontrolcenter.data.db.entity.ModelUsageRow
import com.aicontrolcenter.data.db.entity.ProviderUsageRow
import com.aicontrolcenter.data.db.entity.WorkUsageEntity
import com.aicontrolcenter.data.db.mapper.mapReadable
import com.aicontrolcenter.data.db.mapper.readableOrNull
import com.aicontrolcenter.data.db.mapper.toDomain
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.data.db.mapper.toTotals
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.ZoneId

/*
 * Usage and prices on Room (schema 6; Phase 6, K11, rules 6, 7, 29). They behave like the
 * reference `InMemoryUsageStore` and `InMemoryModelPrices` of `:testkit` and run the same
 * contract suites (`UsageLedgerContract`, `ModelPricesContract`). The rows hold numbers, ids and
 * labels only, and nothing here is reachable by an agent tool (rule 7).
 */

/**
 * [UsageLedger] on Room: each call is added to its `usage_hours` row and its `work_usage` rows in
 * one short write transaction ([UsageDao.record]), so concurrent calls are all counted and a
 * report never sees half a call. Sums run in SQLite.
 */
class RoomUsageLedger internal constructor(
    private val dao: UsageDao,
    private val dispatcher: CoroutineDispatcher,
) : UsageLedger {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.usageDao(), dispatcher)

    override suspend fun record(sample: ModelCallSample) = withContext(dispatcher) { dao.record(sample) }

    override suspend fun totalsSince(fromMs: Long): UsageTotals = withContext(dispatcher) {
        dao.sums(fromMs, Long.MAX_VALUE).toTotals()
    }
}

/**
 * [UsageHousekeeping] on Room (security review of wave 1, F4): the retention pruning, kept apart
 * from the ledger agent-facing code holds. [pruneHours] is clamped to the start of the owner's
 * day ([UsageRange.day] at [clock]'s time in [zone], read at each call), so today's rows, which
 * seed the daily limits after a restart, can never be deleted whatever time it is given.
 * Only the upkeep side holds it.
 */
class RoomUsageHousekeeping internal constructor(
    private val dao: UsageDao,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val dispatcher: CoroutineDispatcher,
) : UsageHousekeeping {

    constructor(
        database: AiccDatabase,
        clock: Clock,
        zone: () -> ZoneId = { ZoneId.systemDefault() },
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(database.usageDao(), clock, zone, dispatcher)

    override suspend fun pruneHours(beforeMs: Long): Int = withContext(dispatcher) {
        dao.deleteHoursBefore(minOf(beforeMs, UsageRange.day(clock.now(), zone()).fromMs))
    }

    override suspend fun pruneOrphanedWork(): Int = withContext(dispatcher) { dao.deleteOrphanedWork() }
}

/**
 * [UsageReports] on Room. A range is `[from, until)` on the start of each row's UTC hour
 * ([UsageRange]); the owner's local day and month are cut by [UsageRange.day] and
 * [UsageRange.month], so these sums and the in-memory ones agree to the call. Lists are sorted by
 * SQLite: most calls first, then by id (calls made for no agent last). The labels of a model or
 * provider are those of its newest row in the range. A row whose locality or kind this build
 * cannot read is left out of the lists (and counted in the totals, which read numbers only).
 */
class RoomUsageReports internal constructor(
    private val dao: UsageDao,
    private val dispatcher: CoroutineDispatcher,
) : UsageReports {

    constructor(database: AiccDatabase, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.usageDao(), dispatcher)

    override suspend fun totals(range: UsageRange): UsageTotals = withContext(dispatcher) {
        dao.sums(range.fromMs, range.untilMs).toTotals()
    }

    override fun observeTotals(range: UsageRange): Flow<UsageTotals> =
        dao.observeSums(range.fromMs, range.untilMs).map { it.toTotals() }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun byModel(range: UsageRange): List<ModelUsage> = withContext(dispatcher) {
        dao.byModel(range.fromMs, range.untilMs).mapReadable(ModelUsageRow::toDomain)
    }

    override fun observeByModel(range: UsageRange): Flow<List<ModelUsage>> =
        dao.observeByModel(range.fromMs, range.untilMs).map { rows -> rows.mapReadable(ModelUsageRow::toDomain) }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    override suspend fun byAgent(range: UsageRange): List<AgentUsage> = withContext(dispatcher) {
        dao.byAgent(range.fromMs, range.untilMs).map { it.toDomain() }
    }

    override suspend fun byAgentAndModel(range: UsageRange): List<AgentModelUsage> = withContext(dispatcher) {
        dao.byAgentAndModel(range.fromMs, range.untilMs).map { it.toDomain() }
    }

    override suspend fun byProvider(range: UsageRange): List<ProviderUsage> = withContext(dispatcher) {
        dao.byProvider(range.fromMs, range.untilMs).mapReadable(ProviderUsageRow::toDomain)
    }

    override suspend fun workUsage(kind: WorkKind, id: String): List<WorkUsage> = withContext(dispatcher) {
        dao.workRows(kind.name, id).mapReadable(WorkUsageEntity::toDomain)
    }

    override fun observeWorkUsage(kind: WorkKind, id: String): Flow<List<WorkUsage>> =
        dao.observeWorkRows(kind.name, id).map { rows -> rows.mapReadable(WorkUsageEntity::toDomain) }
            .distinctUntilChanged()
            .flowOn(dispatcher)
}

/**
 * The owner's "Clear usage history" on Room: hour rows before today and work rows last updated
 * before today go in one transaction; today stays, so clearing never resets today's limits
 * (S29). "Today" is the owner's local day ([UsageRange.day]) at [clock]'s time in [zone], read
 * at each call (the owner's zone can change). Owner-only: only the Usage tab holds it.
 */
class RoomOwnerUsage internal constructor(
    private val dao: UsageDao,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val dispatcher: CoroutineDispatcher,
) : OwnerUsage {

    constructor(
        database: AiccDatabase,
        clock: Clock,
        zone: () -> ZoneId = { ZoneId.systemDefault() },
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(database.usageDao(), clock, zone, dispatcher)

    override suspend fun clearHistory(): Int {
        val today = UsageRange.day(clock.now(), zone())
        return withContext(dispatcher) { dao.clearBefore(today.fromMs) }
    }
}

/**
 * The owner's prices on Room, read ([ModelPrices]: the daily limiter, the Usage tab) and written
 * ([OwnerModelPrices]: Settings > Models, owner-only). A save is stamped with [clock]'s time.
 * The composition root hands each side to its own holder. A stored price that is no longer valid
 * (it cannot be, since every save is checked) is left out.
 */
class RoomModelPrices internal constructor(
    private val dao: UsageDao,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
) : ModelPrices, OwnerModelPrices {

    constructor(database: AiccDatabase, clock: Clock, dispatcher: CoroutineDispatcher = Dispatchers.IO) :
        this(database.usageDao(), clock, dispatcher)

    override fun observeAll(): Flow<List<ModelPrice>> =
        dao.observePrices().map { rows -> rows.mapReadable(ModelPriceEntity::toDomain) }.distinctUntilChanged().flowOn(dispatcher)

    override suspend fun getAll(): List<ModelPrice> = withContext(dispatcher) { dao.prices().mapReadable(ModelPriceEntity::toDomain) }

    override suspend fun get(modelId: ModelId): ModelPrice? = withContext(dispatcher) {
        dao.price(modelId.value)?.readableOrNull(ModelPriceEntity::toDomain)
    }

    /** Refused, with a sentence for the owner, only when the database could not store it. */
    override suspend fun set(price: ModelPrice): OwnerResult<ModelPrice> {
        val stored = price.copy(updatedAt = clock.now())
        return try {
            withContext(dispatcher) { dao.upsertPrice(stored.toEntity()) }
            OwnerResult.Done(stored)
        } catch (_: SQLException) {
            OwnerResult.Refused("The price could not be saved. Try again.")
        }
    }

    override suspend fun remove(modelId: ModelId): Boolean = withContext(dispatcher) { dao.deletePrice(modelId.value) > 0 }
}
