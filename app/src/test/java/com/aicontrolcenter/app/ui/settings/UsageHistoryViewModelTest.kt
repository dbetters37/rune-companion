package com.aicontrolcenter.app.ui.settings

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Settings › Usage history (WP10a; S29): how long usage is kept, and clearing it, which never clears today. */
class UsageHistoryViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.history(app: TestApp) =
        UsageHistoryViewModel(app.graph.settings, app.graph.ownerUsage).also { it.uiState.activeIn(backgroundScope) }

    private fun call(at: Long) = ModelCallSample(
        at = at, modelId = ModelId("mdl_grok"), modelLabel = "Grok 4", providerId = ProviderId("prv_xai"), providerLabel = "xAI",
        locality = Locality.CLOUD, source = UsageSource.CHAT, outcome = CallOutcome.OK, inputTokens = 10, outputTokens = 5, answerMs = 100,
    )

    @Test
    fun `the choices are 90 days, 1 year, 2 years and always, and the saved one is selected`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = history(app)
        assertEquals(listOf("90 days", "1 year", "2 years", "Always"), viewModel.uiState.value.choices.map { it.label })
        assertEquals(730, viewModel.uiState.value.retentionDays)

        viewModel.setRetentionDays(90)
        assertEquals(90, app.settings.get().usageRetentionDays)
        assertEquals(90, viewModel.uiState.value.retentionDays)
        viewModel.setRetentionDays(0)
        assertEquals(0, app.settings.get().usageRetentionDays)

        viewModel.setRetentionDays(12)
        assertEquals(0, app.settings.get().usageRetentionDays, "a choice the screen does not offer is ignored")
    }

    @Test
    fun `clearing deletes the history before today and keeps today, so today's limits stay`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.usage.record(call(TestApp.START_TIME - 3 * 24 * 3_600_000L))
        app.usage.record(call(TestApp.START_TIME - 60_000))
        val viewModel = history(app)

        viewModel.clearHistory()
        assertEquals(UsageHistoryViewModel.CLEARED, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.clearing)
        assertEquals(1, app.usage.totals(UsageRange.ALL).calls, "only today's call is left")
        assertEquals(1, app.usage.totals(UsageRange.day(TestApp.START_TIME, ZoneOffset.UTC)).calls)
        assertEquals(1, app.graph.todayUsage.now().use.calls)

        viewModel.clearHistory()
        assertEquals(UsageHistoryViewModel.NOTHING_TO_CLEAR, viewModel.uiState.value.message)
    }
}
