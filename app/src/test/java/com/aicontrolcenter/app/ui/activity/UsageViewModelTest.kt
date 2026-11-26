package com.aicontrolcenter.app.ui.activity

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.sampleApproval
import com.aicontrolcenter.app.testing.samplePermission
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ErrorClass
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Activity › Usage and cost (WP10a): the numbers match the daily limiter, costs come from the owner's prices, words are plain. */
class UsageViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private fun TestScope.usage(app: TestApp, requests: (Int) -> Flow<List<OwnerRequest>> = { app.graph.ownerRequests.observeRecent(it) }) =
        UsageViewModel(app.graph.usageReports, app.graph.modelPrices, app.graph.registryDirectory, requests, app.clock, { ZoneOffset.UTC })
            .also { it.uiState.activeIn(backgroundScope) }

    private fun sample(
        at: Long = TestApp.START_TIME - 30 * minute,
        model: String = "mdl_grok",
        label: String = "Grok 4",
        provider: String = "prv_xai",
        providerLabel: String = "xAI",
        locality: Locality = Locality.CLOUD,
        agent: String? = "agt_sample",
        outcome: CallOutcome = CallOutcome.OK,
        error: ErrorClass? = null,
        input: Long = 1_000,
        output: Long = 500,
        answerMs: Long = 2_000,
        ttftMs: Long? = 400,
    ) = ModelCallSample(
        at = at, modelId = ModelId(model), modelLabel = label, providerId = ProviderId(provider), providerLabel = providerLabel,
        locality = locality, agentId = agent?.let(::AgentId), source = UsageSource.OBJECTIVE, outcome = outcome, errorClass = error,
        inputTokens = input, outputTokens = output,
        answerMs = if (outcome == CallOutcome.OK) answerMs else null,
        ttftMs = if (outcome == CallOutcome.OK) ttftMs else null,
    )

    private val simulator = { sample(model = "mdl_simulator", label = "Simulator (not a real model)", provider = "prv_simulator", providerLabel = "Simulator", locality = Locality.ON_DEVICE, agent = null) }

    @Test
    fun `today counts answers, tokens and cost as the daily limits do, with models on this phone apart`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.agents.upsert(Samples.agent())
        repeat(2) { app.usage.record(sample()) }
        app.usage.record(simulator())
        app.prices.set(ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 3_000_000, outputPerMTokMicros = 15_000_000))
        val state = usage(app).uiState.value

        assertFalse(state.isLoading)
        val summary = assertNotNull(state.summary)
        // The same counted figures as the Dashboard's Today tile; the simulator on this phone beside them (D3A-2).
        assertEquals(2, summary.answers)
        assertEquals(3_000, summary.tokens)
        assertEquals(1, summary.phoneAnswers)
        assertEquals(1_500, summary.phoneTokens)
        assertEquals("US$0.02", summary.cost)
        assertEquals("Today's limits count 2 answers of 200 and 3,000 tokens of 500,000, and US$0.02 of US$1.00.", summary.lines[0])
        assertTrue("1 answer came from models on this phone: they cost nothing, and the daily limits do not count them." in summary.lines, summary.lines.toString())
        assertEquals(emptyList(), summary.unpriced, "the simulator costs nothing and needs no price")

        // The same count as the limiter's (DailyUse.of over today's rows).
        val limiter = app.graph.todayUsage.now().use
        assertEquals(2, limiter.calls)
        assertEquals(3_000, limiter.tokens)

        val grok = state.models.single { it.modelId == ModelId("mdl_grok") }
        assertEquals("xAI · In the cloud", grok.where)
        assertEquals(2, grok.answers)
        assertEquals("about US$0.02", grok.cost)
        assertEquals("Grok 4 via xAI", grok.logText, "the row opens the log on what each of its calls' entries says")
        assertEquals(listOf("Reliable: all 2 requests were answered."), grok.reliability)
        val phone = state.models.single { it.modelId == ModelId("mdl_simulator") }
        assertEquals("Free: on this phone", phone.cost)
        assertFalse(phone.needsPrice)

        assertEquals(listOf("Researcher" to 2L, "The app itself" to 1L), state.agents.map { it.name to it.answers })
        assertEquals(AgentId("agt_sample"), state.agents.first().agentId)
        // Wave 3 hand-off (handoff-WP10a.md item 2.6): each agent's cost from the prices of the models it used.
        assertEquals(listOf("about US$0.02", "Free: on this phone"), state.agents.map { it.cost })
    }

    @Test
    fun `an agent's cost counts only priced models, and says so when one of its models has no price`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.agents.upsert(Samples.agent())
        app.usage.record(sample(input = 100_000, output = 20_000))
        app.usage.record(sample(model = "mdl_llama", label = "Llama 3.3 70B", provider = "prv_groq", providerLabel = "Groq"))
        app.usage.record(sample(model = "mdl_llama", label = "Llama 3.3 70B", provider = "prv_groq", providerLabel = "Groq", agent = "agt_other"))
        app.prices.set(ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 3_000_000, outputPerMTokMicros = 15_000_000))
        val state = usage(app).uiState.value

        val researcher = state.agents.single { it.agentId == AgentId("agt_sample") }
        assertEquals("about US$0.60 for the models with a price", researcher.cost, "Grok's tokens at Grok's price; Llama's are not in it")
        val other = state.agents.single { it.agentId == AgentId("agt_other") }
        assertEquals("No price entered", other.cost)
    }

    @Test
    fun `a call the provider asked to slow down for is shown as such, never as an answer`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.usage.record(sample())
        app.usage.record(sample(outcome = CallOutcome.FAILED, error = ErrorClass.RATE_LIMITED, input = 0, output = 0))
        app.usage.record(sample(outcome = CallOutcome.FAILED, error = ErrorClass.NETWORK, input = 0, output = 0))
        val state = usage(app).uiState.value

        val grok = state.models.single()
        assertEquals(2, grok.answers, "the answered call and the one that could not connect; not the slowed-down one")
        assertEquals(
            listOf(
                "1 answered, 1 failed (1 could not connect).",
                "1 call ended because xAI asked the app to slow down. They are not counted as answers.",
            ),
            grok.reliability,
        )
        val summary = assertNotNull(state.summary)
        assertEquals(2, summary.answers)
        assertTrue("1 call ended because the provider asked the app to slow down. They are not counted as answers." in summary.lines)
        assertEquals(2, app.graph.todayUsage.now().use.calls, "the limiter counts the same two")
    }

    @Test
    fun `each period cuts the owner's local days`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.usage.record(sample())
        app.usage.record(sample(at = TestApp.START_TIME - 3 * day))
        app.usage.record(sample(at = TestApp.START_TIME - 20 * day))
        val viewModel = usage(app)

        fun answers() = viewModel.uiState.value.summary?.answers
        assertEquals(1, answers())
        viewModel.selectPeriod(UsagePeriod.WEEK)
        assertEquals(UsagePeriod.WEEK, viewModel.uiState.value.period)
        assertEquals(2, answers())
        viewModel.selectPeriod(UsagePeriod.DAYS_30)
        assertEquals(3, answers())
        viewModel.selectPeriod(UsagePeriod.THIS_MONTH)
        assertEquals(2, answers(), "20 days before 14 November is in October")
        assertFalse(viewModel.uiState.value.summary!!.lines.any { it.startsWith("Today's limits") }, "the limits line is for today only")
    }

    @Test
    fun `a model without a price is named, and its tokens stay out of the cost`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.usage.record(sample())
        app.usage.record(sample(model = "mdl_llama", label = "Llama 3", provider = "prv_groq", providerLabel = "Groq"))
        val viewModel = usage(app)

        var summary = assertNotNull(viewModel.uiState.value.summary)
        assertNull(summary.cost, "no money without a price (rule 29)")
        assertEquals(listOf("Grok 4", "Llama 3"), summary.unpriced.map { it.name }.sorted())

        app.prices.set(ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 3_000_000, outputPerMTokMicros = 15_000_000))
        summary = assertNotNull(viewModel.uiState.value.summary)
        assertEquals("US$0.01", summary.cost)
        assertEquals(listOf(UnpricedModelUi(ModelId("mdl_llama"), "Llama 3")), summary.unpriced)
        assertTrue(
            "Llama 3 has no price, so its tokens are not in the cost. Add a price in Models (0 if it is free)." in summary.lines,
            summary.lines.toString(),
        )
        val llama = viewModel.uiState.value.models.single { it.name == "Llama 3" }
        assertTrue(llama.needsPrice)
        assertEquals("No price entered", llama.cost)
    }

    @Test
    fun `average times are shown for models with five answers or more`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        repeat(4) { app.usage.record(sample(answerMs = 2_000, ttftMs = 400)) }
        val viewModel = usage(app)
        assertNull(viewModel.uiState.value.models.single().speed, "four answers say little")

        app.usage.record(sample(answerMs = 2_000, ttftMs = 400))
        assertEquals(
            "On average the first words came in under a second, the whole answer after 2 seconds.",
            viewModel.uiState.value.models.single().speed,
        )
    }

    @Test
    fun `how long requests waited for the owner, by kind`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val agent = AgentId("agt_sample")
        val start = TestApp.START_TIME - 60 * minute
        val requests = MutableStateFlow(
            listOf(
                sampleApproval(agent, id = "req_1", createdAt = start).copy(status = OwnerRequestStatus.APPROVED, decidedAt = start + 2 * minute),
                sampleApproval(agent, id = "req_2", createdAt = start).copy(status = OwnerRequestStatus.DENIED, decidedAt = start + 6 * minute),
                samplePermission(agent, id = "req_3", createdAt = start).copy(status = OwnerRequestStatus.EXPIRED, decidedAt = start + 30 * minute),
                sampleApproval(agent, id = "req_4", createdAt = TestApp.START_TIME - minute),
                sampleApproval(agent, id = "req_old", createdAt = start - 2 * day).copy(status = OwnerRequestStatus.APPROVED, decidedAt = start - 2 * day + minute),
                sampleApproval(agent, id = "req_cancelled", createdAt = start).copy(status = OwnerRequestStatus.CANCELLED, decidedAt = start + minute),
            ),
        )
        val waiting = assertNotNull(usage(app) { requests }.uiState.value.waiting)

        assertEquals(
            listOf(
                "1 permission request: 1 ran out of time before you answered.",
                "2 actions to approve: you answered 2 after 4 minutes on average, the longest after 6 minutes.",
            ),
            waiting.lines,
        )
        assertEquals(1, waiting.waitingNow)
        assertNull(waiting.partial)
    }

    @Test
    fun `nothing used shows nothing but the period`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val state = usage(app).uiState.value
        assertFalse(state.isLoading)
        assertNull(state.summary)
        assertEquals(emptyList(), state.models)
        assertNull(state.waiting)
    }

    @Test
    fun `plain words for times and money`() {
        assertEquals("under a second", secondsWords(40), "never \"0 seconds\" (D3A-16)")
        assertEquals("under a second", secondsWords(0))
        assertEquals("0.5 seconds", secondsWords(500))
        assertEquals("1 second", secondsWords(1_000))
        assertEquals("1 minute 5 seconds", secondsWords(65_000))
        assertEquals("under a minute", waitWords(20_000))
        assertEquals("1 hour 5 minutes", waitWords(65 * minute))
        assertEquals("less than US$0.01", moneyWords(1_000, "US$"))
        assertEquals("US$0.00", moneyWords(0, "US$"))
        assertEquals("about US$0.12", aboutMoney("US$0.12"))
        assertEquals("less than US$0.01", aboutMoney("less than US$0.01"))
        assertEquals("A, B and C", listWords(listOf("A", "B", "C")))
    }
}
