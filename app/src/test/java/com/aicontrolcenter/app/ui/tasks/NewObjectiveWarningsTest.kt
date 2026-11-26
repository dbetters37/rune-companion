package com.aicontrolcenter.app.ui.tasks

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.app.usage.TodayUse
import com.aicontrolcenter.core.health.BackgroundState
import com.aicontrolcenter.core.health.StorageLevel
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.framework.llm.DailyUse
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.testkit.FakeStorageWatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The New objective form's Phase 6 lines (WP11a): today's use against the daily limits, the Director's reasoning rating
 * (D27), what may stop the work in the background (A3), and the phone almost full (R8), which refuses the start.
 */
class NewObjectiveWarningsTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.form(
        app: TestApp,
        today: MutableStateFlow<TodayUse> = MutableStateFlow(TodayUse.NONE),
        storage: FakeStorageWatch = FakeStorageWatch(),
        background: MutableStateFlow<BackgroundState> = MutableStateFlow(BackgroundState.UNKNOWN),
    ) = NewObjectiveViewModel(
        app.graph.orchestrator, app.graph.registryDirectory, app.graph.memoryService, app.notifications, app.graph::awaitStartup,
        today = today, storage = storage, background = background,
    ).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `today's use is said against the limits, in answers, tokens and money`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val today = MutableStateFlow(TodayUse(DailyUse(37, 12_400, 120_000, priced = true), DailyLimits.DEFAULT))
        val vm = form(app, today)
        val line = assertNotNull(vm.uiState.value.today)
        assertEquals("Today: 37 of 200 answers · 12,400 of 500,000 tokens · US$0.12 of US$1.00", line.text)
        assertFalse(line.reached)

        today.value = TodayUse(DailyUse(37, 12_400, 0, priced = false), DailyLimits.DEFAULT)
        assertEquals(
            "Today: 37 of 200 answers · 12,400 of 500,000 tokens · US$1.00 (counted once you enter prices)", vm.uiState.value.today?.text,
            "no money spent is shown without a price, but the money limit is (D3A-8)",
        )

        // Prices in euros: the money limit is read in them, as the limiter's sentence does (D3A-8).
        today.value = TodayUse(DailyUse(37, 12_400, 120_000, priced = true), DailyLimits.DEFAULT, currency = "€")
        assertEquals("Today: 37 of 200 answers · 12,400 of 500,000 tokens · €0.12 of €1.00", vm.uiState.value.today?.text)

        today.value = TodayUse(DailyUse(200, 12_400, 0, priced = false), DailyLimits.DEFAULT)
        assertTrue(vm.uiState.value.today!!.reached)
        assertTrue("Today's limit is reached" in vm.uiState.value.today!!.note)

        today.value = TodayUse(DailyUse(5, 10, 0, priced = false), DailyLimits.NONE)
        assertNull(vm.uiState.value.today, "no limits, no line")
    }

    @Test
    fun `a Director whose model is rated Basic at reasoning is warned about`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val vm = form(app)
        assertNull(vm.uiState.value.reasoningWarning)
        val model = assertNotNull(app.models.get(Simulator.MODEL_ID))
        app.models.upsert(model.copy(capabilities = model.capabilities.copy(reasoning = CapabilityTier.BASIC)))
        app.graph.registryDirectory.observe().first { snapshot -> snapshot.modelsById[Simulator.MODEL_ID]?.capabilities?.reasoning == CapabilityTier.BASIC }
        val warning = assertNotNull(vm.uiState.value.reasoningWarning)
        assertTrue("rated Basic at reasoning" in warning, warning)
    }

    @Test
    fun `what may stop the work in the background is said in plain words`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val background = MutableStateFlow(BackgroundState.UNKNOWN)
        val vm = form(app, background = background)
        assertNull(vm.uiState.value.backgroundWarning)
        background.value = BackgroundState(notificationsAllowed = false, batteryOptimised = true, backgroundRestricted = false, standbyBucket = null)
        val warning = assertNotNull(vm.uiState.value.backgroundWarning)
        assertTrue("notifications are off, battery saving applies to this app" in warning, warning)
        assertTrue("Working in the background" in warning)
    }

    @Test
    fun `an almost full phone is said, and refuses the start`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val storage = FakeStorageWatch(freeBytes = 1L)
        val vm = form(app, storage = storage)
        assertEquals(StorageLevel.CRITICAL, storage.check())
        assertTrue(vm.uiState.value.storageFull)
        // Under the banner's title "The phone is almost full": the body does not say it again (D3A-12a).
        assertEquals("Free some room on the phone before starting an objective.", vm.uiState.value.storageWarning)

        vm.setText("Plan a weekend in Porto.")
        vm.start()
        assertNull(vm.uiState.value.startedId, "nothing starts on an almost full phone")
        assertEquals("The phone is almost full. Free some room before starting an objective.", vm.uiState.value.message?.text)
        assertTrue(app.tasks.observeObjectives().first().isEmpty())

        storage.freeBytes = 10L * 1024 * 1024 * 1024
        vm.start()
        assertNotNull(vm.uiState.value.startedId, vm.uiState.value.message?.text)
        assertFalse(vm.uiState.value.storageFull)
    }
}
