package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.AgentUsage
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.OwnerModelPrices
import com.aicontrolcenter.core.usage.OwnerUsage
import com.aicontrolcenter.core.usage.UsageLedger
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.core.usage.UsageTotals
import com.aicontrolcenter.core.usage.WorkKind
import com.aicontrolcenter.core.usage.WorkUsage
import com.aicontrolcenter.testkit.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three usage ports over one store, as the app wires them. */
data class UsageStores(val ledger: UsageLedger, val reports: UsageReports, val owner: OwnerUsage)

/** Samples of model calls, numbers and ids only. */
object UsageSamples {
    val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    fun at(text: String, zone: ZoneId = berlin): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    fun call(
        at: Long,
        model: String = "mdl_grok",
        agent: String? = "agt_researcher",
        source: UsageSource = UsageSource.OBJECTIVE,
        outcome: CallOutcome = CallOutcome.OK,
        error: ErrorClass? = if (outcome == CallOutcome.FAILED) ErrorClass.RATE_LIMITED else null,
        input: Long? = 1_000,
        output: Long? = 100,
        cached: Long? = 400,
        task: String? = "tsk_1",
        root: String? = "tsk_root",
        conversation: String? = null,
        label: String = "Grok 4 \"fast\"",
        providerLabel: String = "xAI",
        locality: Locality = Locality.CLOUD,
    ) = ModelCallSample(
        at = at, modelId = ModelId(model), modelLabel = label, providerId = ProviderId("prv_$model"), providerLabel = providerLabel, locality = locality,
        agentId = agent?.let(::AgentId), taskId = task?.let(::TaskId), rootTaskId = root?.let(::TaskId), conversationId = conversation?.let(::ConversationId),
        source = source, outcome = outcome, errorClass = error, inputTokens = input, outputTokens = output, cachedInputTokens = cached,
        answerMs = if (outcome == CallOutcome.OK) 2_000 else null, ttftMs = if (outcome == CallOutcome.OK) 400 else null,
        retries = if (outcome == CallOutcome.FAILED) 2 else 0, substituted = outcome == CallOutcome.OK && model == "mdl_sub",
        truncated = false, rateLimitedWaits = if (error == ErrorClass.RATE_LIMITED) 1 else 0, bytesSent = 4_096,
    )
}

/**
 * Phase 6, K11 (rules 6, 7): what every usage store must do. [createStores] gets the clock and
 * the owner's zone the store must use for "today" ([OwnerUsage.clearHistory]); ranges are given
 * by the caller ([UsageRange]).
 */
abstract class UsageLedgerContract {
    protected abstract fun createStores(clock: FakeClock, zone: ZoneId): UsageStores

    private val u = UsageSamples
    private val zone = u.berlin
    private val today = UsageRange.day(u.at("2026-11-25T12:00"), zone)

    @Test
    fun `calls of one hour, model, agent and source are summed into one row`() = runTest {
        val stores = createStores(FakeClock(u.at("2026-11-25T12:00")), zone)
        stores.ledger.record(u.call(u.at("2026-11-25T10:05")))
        stores.ledger.record(u.call(u.at("2026-11-25T10:55")))
        stores.ledger.record(u.call(u.at("2026-11-25T10:30"), outcome = CallOutcome.FAILED, input = null, output = null, cached = null))
        stores.ledger.record(u.call(u.at("2026-11-25T10:31"), input = null, output = null, cached = null))
        stores.ledger.record(u.call(u.at("2026-11-25T10:32"), outcome = CallOutcome.CANCELLED, input = null, output = null, cached = null))
        stores.ledger.record(u.call(u.at("2026-11-25T10:33"), outcome = CallOutcome.BLOCKED, input = null, output = null, cached = null))
        val expected = UsageTotals(
            calls = 6, ok = 3, failed = 1, cancelled = 1, blocked = 1, retries = 2, noUsage = 1, rateLimitedWaits = 1,
            errors = mapOf(ErrorClass.RATE_LIMITED to 1L), inputTokens = 2_000, outputTokens = 200, cachedInputTokens = 800,
            answerMsSum = 6_000, ttftMsSum = 1_200, ttftCount = 3, bytesSent = 6 * 4_096,
        )
        assertEquals(expected, stores.reports.totals(today))
        assertEquals(expected, stores.ledger.totalsSince(today.fromMs))
        assertEquals(2_000, stores.reports.totals(today).averageAnswerMs)
    }

    @Test
    fun `reports sum by model, agent and provider over a range, most calls first`() = runTest {
        val stores = createStores(FakeClock(u.at("2026-11-25T12:00")), zone)
        stores.ledger.record(u.call(u.at("2026-11-24T23:30"), model = "mdl_old"))
        stores.ledger.record(u.call(u.at("2026-11-25T00:10"), model = "mdl_a", agent = null, source = UsageSource.OTHER, task = null, root = null))
        stores.ledger.record(u.call(u.at("2026-11-25T09:00"), model = "mdl_b", agent = "agt_writer", label = "Old label"))
        stores.ledger.record(u.call(u.at("2026-11-25T11:00"), model = "mdl_b", agent = "agt_writer", label = "New label", locality = Locality.LOCAL_NETWORK))
        stores.ledger.record(u.call(u.at("2026-11-25T11:10"), model = "mdl_b", agent = "agt_researcher", source = UsageSource.CHAT, task = null, root = null, conversation = "cnv_1", label = "New label", locality = Locality.LOCAL_NETWORK))
        stores.ledger.record(u.call(u.at("2026-11-26T00:00"), model = "mdl_a"))
        val byModel = stores.reports.byModel(today)
        assertEquals(listOf("mdl_b", "mdl_a"), byModel.map { it.modelId.value })
        assertEquals(3, byModel.first().totals.calls)
        assertEquals("New label", byModel.first().modelLabel, "labels of the newest hour")
        assertEquals(Locality.LOCAL_NETWORK, byModel.first().locality)
        assertEquals(
            listOf(AgentUsage(AgentId("agt_writer"), byModel.first().totals.copy(calls = 2, ok = 2, inputTokens = 2_000, outputTokens = 200, cachedInputTokens = 800, answerMsSum = 4_000, ttftMsSum = 800, ttftCount = 2, bytesSent = 8_192))),
            stores.reports.byAgent(today).take(1),
        )
        assertEquals(listOf(AgentId("agt_writer"), AgentId("agt_researcher"), null), stores.reports.byAgent(today).map { it.agentId }, "no agent last")
        assertEquals(listOf("prv_mdl_b", "prv_mdl_a"), stores.reports.byProvider(today).map { it.providerId.value })
        assertEquals(4, stores.reports.totals(today).calls, "the day's hours only")
        assertEquals(6, stores.reports.totals(UsageRange.ALL).calls)
        assertEquals(6, stores.reports.totals(UsageRange.month(today.fromMs, zone)).calls)
        assertEquals(stores.reports.byModel(today), stores.reports.observeByModel(today).first())
        assertEquals(emptyList(), stores.reports.byModel(UsageRange(0, 1)))
    }

    @Test
    fun `a day in a half-hour zone is cut at the UTC hour that holds local midnight`() = runTest {
        val india = ZoneId.of("Asia/Kolkata")
        val stores = createStores(FakeClock(u.at("2026-11-25T12:00", india)), india)
        val day = UsageRange.day(u.at("2026-11-25T12:00", india), india)
        stores.ledger.record(u.call(u.at("2026-11-24T23:45", india)))
        stores.ledger.record(u.call(u.at("2026-11-25T00:15", india)))
        stores.ledger.record(u.call(u.at("2026-11-25T23:15", india)))
        stores.ledger.record(u.call(u.at("2026-11-25T23:45", india)))
        // 23:45 the evening before is in the hour that holds midnight; 23:45 tonight is in the next day's first hour.
        assertEquals(3, stores.reports.totals(day).calls)
    }

    @Test
    fun `a call's tokens go to its task and objective, or to its chat, per model`() = runTest {
        val stores = createStores(FakeClock(u.at("2026-11-25T12:00")), zone)
        stores.ledger.record(u.call(u.at("2026-11-25T10:00"), task = "tsk_1", root = "tsk_root"))
        stores.ledger.record(u.call(u.at("2026-11-25T10:05"), task = "tsk_2", root = "tsk_root", model = "mdl_other", input = 50, output = 5, cached = null))
        stores.ledger.record(u.call(u.at("2026-11-25T09:00"), task = "tsk_1", root = "tsk_root", outcome = CallOutcome.FAILED, input = null, output = null, cached = null))
        stores.ledger.record(u.call(u.at("2026-11-25T10:10"), task = null, root = null, conversation = "cnv_1", source = UsageSource.CHAT))
        stores.ledger.record(u.call(u.at("2026-11-25T10:20"), task = null, root = null, conversation = null, source = UsageSource.OTHER))
        val r = stores.reports
        assertEquals(listOf(WorkUsage(WorkKind.TASK, "tsk_1", ModelId("mdl_grok"), 2, 1_000, 100, 400, u.at("2026-11-25T10:00"))), r.workUsage(WorkKind.TASK, "tsk_1"))
        assertEquals(
            listOf(
                WorkUsage(WorkKind.OBJECTIVE, "tsk_root", ModelId("mdl_grok"), 2, 1_000, 100, 400, u.at("2026-11-25T10:00")),
                WorkUsage(WorkKind.OBJECTIVE, "tsk_root", ModelId("mdl_other"), 1, 50, 5, 0, u.at("2026-11-25T10:05")),
            ),
            r.workUsage(WorkKind.OBJECTIVE, "tsk_root"),
        )
        assertEquals(listOf(WorkUsage(WorkKind.CHAT, "cnv_1", ModelId("mdl_grok"), 1, 1_000, 100, 400, u.at("2026-11-25T10:10"))), r.workUsage(WorkKind.CHAT, "cnv_1"))
        assertEquals(emptyList(), r.workUsage(WorkKind.CHAT, "tsk_1"))
        assertEquals(r.workUsage(WorkKind.OBJECTIVE, "tsk_root"), r.observeWorkUsage(WorkKind.OBJECTIVE, "tsk_root").first())
    }

    @Test
    fun `clearing the history keeps today, so today's limits do not reset`() = runTest {
        val stores = createStores(FakeClock(u.at("2026-11-25T12:00")), zone)
        stores.ledger.record(u.call(u.at("2026-11-20T10:00"), task = "tsk_old", root = "tsk_old_root"))
        stores.ledger.record(u.call(u.at("2026-11-24T23:59"), task = "tsk_old", root = "tsk_old_root"))
        stores.ledger.record(u.call(u.at("2026-11-25T00:01"), task = "tsk_new", root = "tsk_new_root"))
        stores.ledger.record(u.call(u.at("2026-11-25T11:00"), task = "tsk_new", root = "tsk_new_root"))
        assertEquals(2, stores.owner.clearHistory())
        assertEquals(2, stores.reports.totals(UsageRange.ALL).calls)
        assertEquals(2, stores.reports.totals(today).calls)
        assertEquals(emptyList(), stores.reports.workUsage(WorkKind.TASK, "tsk_old"))
        assertEquals(2, stores.reports.workUsage(WorkKind.TASK, "tsk_new").single().calls)
        assertEquals(0, stores.owner.clearHistory())
    }

    @Test
    fun `old hours are pruned and recent ones kept`() = runTest {
        val stores = createStores(FakeClock(u.at("2026-11-25T12:00")), zone)
        stores.ledger.record(u.call(u.at("2026-01-01T10:00")))
        stores.ledger.record(u.call(u.at("2026-01-01T11:00"), agent = "agt_writer"))
        stores.ledger.record(u.call(u.at("2026-11-25T11:00")))
        assertEquals(2, stores.ledger.pruneHours(u.at("2026-06-01T00:00")))
        assertEquals(1, stores.reports.totals(UsageRange.ALL).calls)
        assertEquals(3, stores.reports.workUsage(WorkKind.TASK, "tsk_1").single().calls, "work rows are kept")
        assertEquals(0, stores.ledger.pruneHours(u.at("2026-06-01T00:00")))
    }

    @Test
    fun `concurrent calls are all counted, and open reports see them`() = runTest {
        val stores = createStores(FakeClock(u.at("2026-11-25T12:00")), zone)
        val totals = collectIn(backgroundScope, stores.reports.observeTotals(today))
        val work = collectIn(backgroundScope, stores.reports.observeWorkUsage(WorkKind.OBJECTIVE, "tsk_root"))
        awaitUntil { totals.isNotEmpty() && work.isNotEmpty() }
        withContext(Dispatchers.Default) {
            (1..50).map { n -> launch { stores.ledger.record(u.call(u.at("2026-11-25T10:00") + n, agent = if (n % 2 == 0) "agt_a" else "agt_b")) } }.joinAll()
        }
        assertEquals(50, stores.ledger.totalsSince(today.fromMs).calls)
        assertEquals(50_000, stores.reports.totals(today).inputTokens)
        awaitUntil { totals.last().calls == 50L && work.last().singleOrNull()?.calls == 50L }
    }
}

/** Phase 6, K11 (rule 29): the owner's prices, stored as typed and read back. */
abstract class ModelPricesContract {
    /** [ModelPrices] and [OwnerModelPrices] over one store, stamping saves with [clock]. */
    protected abstract fun createStores(clock: FakeClock): Pair<ModelPrices, OwnerModelPrices>

    @Test
    fun `prices are stored, replaced, listed by model and removed`() = runTest {
        val clock = FakeClock(5_000)
        val (prices, owner) = createStores(clock)
        assertEquals(emptyList(), prices.getAll())
        val grok = ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 3_000_000, outputPerMTokMicros = 15_000_000, cachedInputPerMTokMicros = 750_000, currencyLabel = "US$", updatedAt = 1)
        val local = ModelPrice(ModelId("mdl_a_local"), 0, 0, null, "€")
        assertEquals(grok.copy(updatedAt = 5_000), assertIs<OwnerResult.Done<ModelPrice>>(owner.set(grok)).value, "stamped with the time of saving")
        clock.current = 6_000
        owner.set(local)
        assertEquals(listOf(local.copy(updatedAt = 6_000), grok.copy(updatedAt = 5_000)), prices.getAll())
        assertEquals(prices.getAll(), prices.observeAll().first())
        assertEquals(grok.copy(updatedAt = 5_000), prices.get(ModelId("mdl_grok")))
        clock.current = 7_000
        owner.set(grok.copy(outputPerMTokMicros = 10_000_000))
        assertEquals(10_000_000, prices.get(ModelId("mdl_grok"))!!.outputPerMTokMicros)
        assertEquals(7_000, prices.get(ModelId("mdl_grok"))!!.updatedAt)
        assertNull(prices.get(ModelId("mdl_none")))
        assertTrue(owner.remove(ModelId("mdl_grok")))
        assertTrue(!owner.remove(ModelId("mdl_grok")))
        assertEquals(listOf(ModelId("mdl_a_local")), prices.getAll().map { it.modelId })
    }

    @Test
    fun `an open list of prices sees changes`() = runTest {
        val (prices, owner) = createStores(FakeClock(1))
        val seen = collectIn(backgroundScope, prices.observeAll())
        awaitUntil { seen.isNotEmpty() }
        owner.set(ModelPrice(ModelId("mdl_1"), 1, 2))
        awaitUntil { seen.last().size == 1 }
        owner.remove(ModelId("mdl_1"))
        awaitUntil { seen.last().isEmpty() }
    }
}
