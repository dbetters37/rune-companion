package com.aicontrolcenter.core.usage

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.settings.FrameworkSettings
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 6, K11: the pure parts of usage, cost and daily limits (rules 6, 7, 29). */
class UsageModelTest {
    private fun sample(
        at: Long = 1_000,
        outcome: CallOutcome = CallOutcome.OK,
        input: Long? = 1_000,
        output: Long? = 200,
        cached: Long? = 0,
        taskId: String? = null,
        rootId: String? = null,
        conversation: String? = null,
    ) = ModelCallSample(
        at = at, modelId = ModelId("mdl_1"), modelLabel = "Grok 4", providerId = ProviderId("prv_x"), providerLabel = "xAI",
        locality = Locality.CLOUD, agentId = AgentId("agt_r"), taskId = taskId?.let(::TaskId), rootTaskId = rootId?.let(::TaskId),
        conversationId = conversation?.let(::ConversationId), source = UsageSource.OBJECTIVE, outcome = outcome,
        errorClass = if (outcome == CallOutcome.FAILED) ErrorClass.TIMEOUT else null,
        inputTokens = input, outputTokens = output, cachedInputTokens = cached,
        answerMs = if (outcome == CallOutcome.OK) 900 else null, ttftMs = if (outcome == CallOutcome.OK) 300 else null,
    )

    @Test
    fun `costs are estimated from the owner's prices, cached input at its own price`() {
        val price = ModelPrice(ModelId("mdl_1"), inputPerMTokMicros = 3_000_000, outputPerMTokMicros = 15_000_000, cachedInputPerMTokMicros = 300_000)
        // 1,000,000 input of which 400,000 cached: 600,000 x 3 + 400,000 x 0.3 = 1.80 + 0.12; 100,000 output x 15 = 1.50.
        assertEquals(3_420_000, CostEstimate.of(TokenCounts(1_000_000, 100_000, 400_000), price))
        // Without a cached price, cached input costs the input price.
        assertEquals(4_500_000, CostEstimate.of(TokenCounts(1_000_000, 100_000, 400_000), price.copy(cachedInputPerMTokMicros = null)))
        // Rounded half up to whole millionths: 1 token at 3 per Mtok is 3 millionths; 1 token at 0.5 per Mtok is 0.5 → 1.
        assertEquals(3, CostEstimate.of(TokenCounts(1, 0), price))
        assertEquals(1, CostEstimate.of(TokenCounts(1, 0), price.copy(inputPerMTokMicros = 500_000)))
        assertEquals(0, CostEstimate.of(TokenCounts(1, 0), price.copy(inputPerMTokMicros = 499_999)))
        // Cached above the input count is treated as input.
        assertEquals(CostEstimate.of(TokenCounts(10, 0, 10), price), CostEstimate.of(TokenCounts(10, 0, 50), price))
        // No price: no money.
        assertNull(CostEstimate.of(TokenCounts(1_000, 1_000), null))
        // Huge numbers do not overflow.
        assertEquals(Long.MAX_VALUE, CostEstimate.of(TokenCounts(Long.MAX_VALUE / 2, Long.MAX_VALUE / 2), price.copy(outputPerMTokMicros = ModelPrice.MAX_PER_MTOK_MICROS)))
    }

    @Test
    fun `the cost of a work item adds its priced models only`() {
        val prices = mapOf(ModelId("a") to ModelPrice(ModelId("a"), 1_000_000, 2_000_000))
        val rows = listOf(
            WorkUsage(WorkKind.OBJECTIVE, "tsk_root", ModelId("a"), 3, 1_000_000, 500_000, 0, 10),
            WorkUsage(WorkKind.OBJECTIVE, "tsk_root", ModelId("unpriced"), 9, 9_000_000, 9_000_000, 0, 10),
        )
        assertEquals(2_000_000, CostEstimate.of(rows, prices))
        assertNull(CostEstimate.of(rows.drop(1), prices))
        assertNull(CostEstimate.of(emptyList(), prices))
    }

    @Test
    fun `prices are checked when they are typed in`() {
        assertNull(ModelPrice.check(0, 0, null, "US$"))
        assertTrue(ModelPrice.check(-1, 0, null, "US$")!!.contains("negative"))
        assertTrue(ModelPrice.check(0, ModelPrice.MAX_PER_MTOK_MICROS + 1, null, "US$") != null)
        assertTrue(ModelPrice.check(0, 0, null, " ") != null)
        assertTrue(ModelPrice.check(0, 0, null, "DOLLARS-US") != null)
        assertFailsWith<IllegalArgumentException> { ModelPrice(ModelId("m"), -1, 0) }
        assertEquals("US$", ModelPrice(ModelId("m"), 1, 1).currencyLabel)
    }

    @Test
    fun `daily limits have the owner's defaults, reach at the limit and never relax on restore`() {
        assertEquals(DailyLimits(200, 500_000, 1_000_000), DailyLimits.DEFAULT)
        assertEquals(DailyLimits.DEFAULT, FrameworkSettings().dailyLimits)
        assertEquals(DailyLimits(50, 1_000, null), FrameworkSettings(maxModelCallsPerDay = 50, maxTokensPerDay = 1_000, maxSpendPerDayMicros = null).dailyLimits)
        val limits = DailyLimits.DEFAULT
        assertNull(limits.reachedBy(calls = 199, tokens = 0, spendMicros = 0), "the 200th call goes")
        assertEquals(DailyLimitKind.CALLS, limits.reachedBy(calls = 200, tokens = 0, spendMicros = 0), "the 201st does not")
        assertEquals(DailyLimitKind.TOKENS, limits.reachedBy(calls = 1, tokens = 500_000, spendMicros = 0))
        assertEquals(DailyLimitKind.SPEND, limits.reachedBy(calls = 1, tokens = 1, spendMicros = 1_000_000))
        assertNull(DailyLimits.NONE.reachedBy(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(DailyLimits(100, 500_000, 2_000), DailyLimits(100, null, 5_000).stricter(DailyLimits(300, 500_000, 2_000)))
        assertEquals(DailyLimits.NONE, DailyLimits.NONE.stricter(DailyLimits.NONE))
        assertEquals(
            "Today's limit of 200 model answers is reached. It starts again at midnight, or you can raise it in Settings > Daily limits.",
            limits.sentence(DailyLimitKind.CALLS),
        )
        assertEquals(
            "Today's limit of 500,000 tokens is reached. It starts again at midnight, or you can raise it in Settings > Daily limits.",
            limits.sentence(DailyLimitKind.TOKENS),
        )
        assertEquals(
            "Today's spending limit of US$1.00 is reached. It starts again at midnight, or you can raise it in Settings > Daily limits.",
            limits.sentence(DailyLimitKind.SPEND),
        )
        assertEquals(
            "Today's limit of 1 model answer is reached. It starts again at midnight, or you can raise it in Settings > Daily limits.",
            DailyLimits(1, null, null).sentence(DailyLimitKind.CALLS),
            "one answer is said in the singular",
        )
        assertEquals("Today's limit of 1 token is reached.", DailyLimits(null, 1, null).sentence(DailyLimitKind.TOKENS).substringBefore(" It starts"))
        assertEquals("US$0.25", DailyLimits.money(250_000, "US$"))
        assertEquals("US$1,234.57", DailyLimits.money(1_234_567_890, "US$"))
        assertFalse(LlmException.DailyLimitReached("x").retryable)
    }

    @Test
    fun `a sample adds its counts, and only an answered call carries times`() {
        val ok = UsageTotals.of(sample())
        assertEquals(UsageTotals(calls = 1, ok = 1, inputTokens = 1_000, outputTokens = 200, answerMsSum = 900, ttftMsSum = 300, ttftCount = 1), ok)
        val failed = UsageTotals.of(sample(outcome = CallOutcome.FAILED, input = null, output = null, cached = null))
        assertEquals(UsageTotals(calls = 1, failed = 1, errors = mapOf(ErrorClass.TIMEOUT to 1L)), failed)
        val noUsage = UsageTotals.of(sample(input = null, output = null, cached = null))
        assertEquals(1, noUsage.noUsage)
        val sum = ok + failed + noUsage
        assertEquals(3, sum.calls)
        assertEquals(900, sum.averageAnswerMs)
        assertEquals(300, sum.averageTtftMs)
        assertEquals(sum, UsageTotals.of(listOf(sample(), sample(outcome = CallOutcome.FAILED, input = null, output = null, cached = null), sample(input = null, output = null, cached = null))))
        assertNull(UsageTotals().averageAnswerMs)
        assertFailsWith<IllegalArgumentException> { sample(outcome = CallOutcome.FAILED).copy(errorClass = null) }
        assertFailsWith<IllegalArgumentException> { sample().copy(errorClass = ErrorClass.AUTH) }
        assertFailsWith<IllegalArgumentException> { sample().copy(answerMs = null) }
        assertFailsWith<IllegalArgumentException> { sample(outcome = CallOutcome.CANCELLED).copy(answerMs = 5) }
        assertFailsWith<IllegalArgumentException> { sample(input = -1) }
        assertFailsWith<IllegalArgumentException> { UsageTotals(errors = mapOf(ErrorClass.AUTH to 0L)) }
    }

    @Test
    fun `a call's tokens go to its task and objective, or else to its chat`() {
        assertEquals(
            listOf(WorkItem(WorkKind.TASK, "tsk_1"), WorkItem(WorkKind.OBJECTIVE, "tsk_root")),
            sample(taskId = "tsk_1", rootId = "tsk_root", conversation = "cnv_1").workItems,
        )
        assertEquals(listOf(WorkItem(WorkKind.OBJECTIVE, "tsk_root")), sample(rootId = "tsk_root").workItems)
        assertEquals(listOf(WorkItem(WorkKind.CHAT, "cnv_1")), sample(conversation = "cnv_1").workItems)
        assertEquals(emptyList(), sample().workItems)
    }

    @Test
    fun `failures map to the usage columns`() {
        assertEquals(ErrorClass.AUTH, ErrorClass.of(LlmException.Authentication("x")))
        assertEquals(ErrorClass.RATE_LIMITED, ErrorClass.of(LlmException.RateLimited("x")))
        assertEquals(ErrorClass.OVERLOADED, ErrorClass.of(LlmException.Overloaded("x")))
        assertEquals(ErrorClass.TIMEOUT, ErrorClass.of(LlmException.Timeout("x")))
        assertEquals(ErrorClass.NETWORK, ErrorClass.of(LlmException.Network("x")))
        assertEquals(ErrorClass.SERVER, ErrorClass.of(LlmException.Server("x", 503)))
        assertEquals(ErrorClass.INVALID, ErrorClass.of(LlmException.InvalidRequest("x")))
        assertEquals(ErrorClass.CONTEXT, ErrorClass.of(LlmException.ContextTooLong("x")))
        assertEquals(ErrorClass.OTHER, ErrorClass.of(LlmException.MalformedResponse("x")))
    }

    @Test
    fun `days and months are cut at whole hours in the owner's zone`() {
        fun at(zone: ZoneId, text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        val berlin = ZoneId.of("Europe/Berlin")
        val noon = at(berlin, "2026-11-25T12:00")
        assertEquals(UsageRange(at(berlin, "2026-11-25T00:00"), at(berlin, "2026-11-26T00:00")), UsageRange.day(noon, berlin))
        assertEquals(UsageRange(at(berlin, "2026-11-01T00:00"), at(berlin, "2026-12-01T00:00")), UsageRange.month(noon, berlin))
        assertEquals(UsageRange(at(berlin, "2026-11-19T00:00"), at(berlin, "2026-11-26T00:00")), UsageRange.lastDays(7, noon, berlin))
        // A day with a clock change has 23 hours.
        val spring = UsageRange.day(at(berlin, "2026-03-29T12:00"), berlin)
        assertEquals(23 * UsageRange.HOUR_MS, spring.untilMs - spring.fromMs)
        // A half-hour zone: the day starts at the start of the UTC hour that holds local midnight.
        val india = ZoneId.of("Asia/Kolkata")
        val day = UsageRange.day(at(india, "2026-11-25T10:00"), india)
        assertEquals(LocalDateTime.parse("2026-11-24T18:00").toInstant(ZoneOffset.UTC).toEpochMilli(), day.fromMs)
        assertEquals(24 * UsageRange.HOUR_MS, day.untilMs - day.fromMs)
        assertEquals(0, day.fromMs % UsageRange.HOUR_MS)
        assertTrue(day.containsHour(day.fromMs))
        assertFalse(day.containsHour(day.untilMs))
        assertEquals(-UsageRange.HOUR_MS, UsageRange.hourStart(-1), "hours before 1970 floor downwards")
        assertFailsWith<IllegalArgumentException> { UsageRange(2, 1) }
    }
}
