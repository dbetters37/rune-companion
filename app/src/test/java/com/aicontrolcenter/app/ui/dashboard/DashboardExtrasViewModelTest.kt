package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.health.SafeStartReason
import com.aicontrolcenter.app.health.SafeStartState
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.ui.activity.UnpricedModelUi
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.StandbyBucket
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.testkit.MutableTimedWaits
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

/** The Dashboard's Phase 6 parts (WP10a): the Today tile, Getting started (D47), the phone's attention items, safe start. */
class DashboardExtrasViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val minute = 60_000L

    private fun TestScope.extras(app: TestApp, detailed: Boolean = true, waits: MutableTimedWaits = MutableTimedWaits()) =
        DashboardExtrasViewModel(
            today = app.graph.todayUsage,
            settings = app.graph.settings,
            directory = app.graph.registryDirectory,
            tasks = app.graph.tasks,
            backgroundHealth = app.graph.backgroundHealth,
            storage = app.graph.storageWatch,
            safeStart = app.graph.safeStart,
            clock = app.clock,
            reports = if (detailed) app.graph.usageReports else null,
            prices = if (detailed) app.graph.modelPrices else null,
            zone = { ZoneOffset.UTC },
            waits = waits,
        ).also { it.uiState.activeIn(backgroundScope) }

    private fun call(
        at: Long = TestApp.START_TIME - 30 * minute,
        model: String = "mdl_grok",
        label: String = "Grok 4",
        locality: Locality = Locality.CLOUD,
    ) = ModelCallSample(
        at = at, modelId = ModelId(model), modelLabel = label, providerId = ProviderId("prv_$model"), providerLabel = "Provider",
        locality = locality, agentId = AgentId("agt_sample"), source = UsageSource.OBJECTIVE, outcome = CallOutcome.OK,
        inputTokens = 1_000, outputTokens = 500, answerMs = 1_000,
    )

    private val grokPrice = ModelPrice(ModelId("mdl_grok"), inputPerMTokMicros = 3_000_000, outputPerMTokMicros = 15_000_000)

    @Test
    fun `the Today tile counts what the limiter counts and shows the cost when every used model has a price`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        repeat(2) { app.usage.record(call()) }
        app.usage.record(call(model = "mdl_simulator", label = "Simulator", locality = Locality.ON_DEVICE))
        app.usage.record(call(at = TestApp.START_TIME - 5 * 24 * 60 * minute))
        app.prices.set(grokPrice)
        val today = assertNotNull(extras(app).uiState.value.today)

        assertTrue(today.used)
        assertEquals(2, today.answers, "the simulator on this phone is not counted")
        assertEquals(3_000, today.tokens)
        assertEquals("US$0.02", today.cost)
        assertNull(today.costNote)
        assertEquals(DailyLimits.DEFAULT, today.limits)
        assertEquals("This month: 3 answers · 4,500 tokens · US$0.03", today.month)
        assertNull(today.reached)
        assertEquals(app.graph.todayUsage.now().use.calls, today.answers)
    }

    @Test
    fun `no cost is shown while a model used today has no price, and the tile names it`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.usage.record(call())
        app.usage.record(call(model = "mdl_llama", label = "Llama 3"))
        app.prices.set(grokPrice)
        val today = assertNotNull(extras(app).uiState.value.today)

        assertNull(today.cost, "a cost that leaves out a model would read as the whole day's")
        assertEquals("No cost shown: Llama 3 has no price yet.", today.costNote)
        assertEquals(listOf(UnpricedModelUi(ModelId("mdl_llama"), "Llama 3")), today.unpriced)
        assertEquals("This month: 2 answers · 3,000 tokens", today.month)
    }

    @Test
    fun `a reached limit is said with the limiter's own sentence`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.settings.update { it.copy(maxModelCallsPerDay = 1) }
        app.usage.record(call())
        val today = assertNotNull(extras(app).uiState.value.today)
        assertEquals(DailyLimits(1, 500_000, 1_000_000).sentence(com.aicontrolcenter.core.usage.DailyLimitKind.CALLS), today.reached)
    }

    @Test
    fun `without the usage reports the tile still shows today's numbers`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = extras(app, detailed = false)
        var today = assertNotNull(viewModel.uiState.value.today)
        assertFalse(today.used)
        assertNull(today.month)

        app.usage.record(call())
        app.prices.set(grokPrice)
        today = assertNotNull(viewModel.uiState.value.today)
        assertEquals(1, today.answers)
        assertEquals("US$0.01", today.cost)
        assertEquals(DashboardExtrasViewModel.PRICED_ONLY, today.costNote, "it cannot tell whether every model has a price, so it says what the cost covers")
    }

    @Test
    fun `Getting started shows until the owner says done, and remembers it (D47)`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = extras(app)
        val card = assertNotNull(viewModel.uiState.value.gettingStarted)
        assertEquals(listOf("What the words mean", "Add your key", "Set a daily limit", "Try the simulator"), card.steps.map { it.title })
        assertFalse(card.steps[1].done, "no provider yet")
        assertTrue(card.steps[1].text.contains("Groq, OpenRouter, Gemini's OpenAI-compatible address"), card.steps[1].text)
        assertTrue(card.steps[2].text.contains("200 answers, 500,000 tokens and US$1.00 a day"), card.steps[2].text)

        app.registerSampleModel()
        assertTrue(assertNotNull(viewModel.uiState.value.gettingStarted).steps[1].done, "a real provider was added")

        viewModel.finishGettingStarted()
        assertTrue(app.settings.get().onboardingCompleted)
        assertNull(viewModel.uiState.value.gettingStarted)
        assertNull(extras(app).uiState.value.gettingStarted, "it does not come back")
    }

    @Test
    fun `the phone's attention items name what Android does and lead to where it is fixed`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val waits = MutableTimedWaits()
        val viewModel = extras(app, waits = waits)
        assertEquals(emptyList(), viewModel.uiState.value.phone, "nothing known, nothing to say")

        app.backgroundHealth.current.value = BackgroundState(
            notificationsAllowed = false, batteryOptimised = true, backgroundRestricted = false, standbyBucket = StandbyBucket.RARE,
        )
        app.storage.freeBytes = 200L * 1024 * 1024
        app.graph.storageWatch.check()
        waits.due.value = TestApp.START_TIME + 10 * minute
        val items = viewModel.uiState.value.phone
        assertEquals(listOf("phone-notifications", "phone-background", "phone-storage", "phone-waiting"), items.map { it.id })
        assertEquals(PhoneTarget.Settings, items[0].target)
        assertEquals("Battery saving applies to the app and Android rarely lets it run. Fix it with one tap in Settings › Working in the background.", items[1].detail)
        assertEquals("The phone is running out of room", items[2].title)
        assertNull(items[2].target)
        assertEquals("Work tries again at 12:10", items[3].title)
        assertEquals(StatusTone.WAITING, items[3].tone)

        app.storage.freeBytes = 50L * 1024 * 1024
        app.graph.storageWatch.check()
        assertEquals(StatusTone.UNAVAILABLE, viewModel.uiState.value.phone.single { it.id == "phone-storage" }.tone)
    }

    @Test
    fun `an objective paused by today's limit leads to the objective`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val at = TestApp.START_TIME - 10 * minute
        app.tasks.upsert(objective("obj_limit", "Compare note apps", TaskStatus.PAUSED, DailyLimits.PAUSED_TEXT, at))
        app.tasks.upsert(objective("obj_owner", "Plan a trip", TaskStatus.PAUSED, "Paused by the owner.", at))
        val items = extras(app).uiState.value.phone
        val paused = items.single()
        assertEquals("Paused by today's limit: Compare note apps", paused.title)
        assertEquals(PhoneTarget.Objective(TaskId("obj_limit")), paused.target)
    }

    @Test
    fun `safe start shows its reason, and Back to normal leaves it`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = extras(app)
        assertNull(viewModel.uiState.value.safeStart)

        app.safeStart.state.value = SafeStartState(on = true, reason = SafeStartReason.CRASHES)
        assertEquals(SafeStartReason.CRASHES.ownerText, assertNotNull(viewModel.uiState.value.safeStart).reason)

        viewModel.leaveSafeStart()
        assertEquals(1, app.safeStart.left.get())
        assertNull(viewModel.uiState.value.safeStart)
    }

    private fun objective(id: String, title: String, status: TaskStatus, detail: String, at: Long) = TaskRecord(
        id = TaskId(id), rootId = TaskId(id), kind = TaskKind.OBJECTIVE, title = title, instructions = title,
        requestedBy = TaskRequester.Owner, status = status, statusDetail = detail, createdAt = at, updatedAt = at,
    )
}
