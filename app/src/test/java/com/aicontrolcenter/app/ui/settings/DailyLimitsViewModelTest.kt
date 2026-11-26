package com.aicontrolcenter.app.ui.settings

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.usage.TodayUse
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.usage.CallOutcome
import com.aicontrolcenter.core.usage.DailyLimits
import com.aicontrolcenter.core.usage.ModelCallSample
import com.aicontrolcenter.core.usage.UsageSource
import com.aicontrolcenter.framework.llm.DailyUse
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings › Daily limits (WP10a; rule 6, Q2): the fields, what Save stores, and today's use against each limit. */
class DailyLimitsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.limits(app: TestApp) =
        DailyLimitsViewModel(app.graph.settings, app.graph.todayUsage).also { it.uiState.activeIn(backgroundScope) }

    private fun call(at: Long = TestApp.START_TIME - 60_000) = ModelCallSample(
        at = at, modelId = ModelId("mdl_grok"), modelLabel = "Grok 4", providerId = ProviderId("prv_xai"), providerLabel = "xAI",
        locality = Locality.CLOUD, agentId = AgentId("agt_sample"), source = UsageSource.OBJECTIVE, outcome = CallOutcome.OK,
        inputTokens = 100, outputTokens = 50, answerMs = 1_000,
    )

    @Test
    fun `the fields start at the saved limits, the owner's Q2 defaults on a new phone`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = limits(app)
        assertEquals("200", viewModel.answers.text.toString())
        assertEquals("500000", viewModel.tokens.text.toString())
        assertEquals("1.00", viewModel.money.text.toString())
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.moneyLimitOn)
        assertTrue(state.usual)
        assertEquals(
            listOf(
                LimitLineUi("Answers", "0 of 200 today", reached = false),
                LimitLineUi("Tokens", "0 of 500,000 today", reached = false),
                LimitLineUi("Money", "No model used today has a price, so nothing is counted yet. Limit: US$1.00", reached = false),
            ),
            state.lines,
        )
        assertNull(state.reached)
    }

    @Test
    fun `Save stores exactly what the fields hold, and the money limit can be switched off`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = limits(app)
        viewModel.answers.setTextAndPlaceCursorAtEnd("350")
        viewModel.tokens.setTextAndPlaceCursorAtEnd("750000")
        viewModel.money.setTextAndPlaceCursorAtEnd("2,5")
        viewModel.save()
        var saved = app.settings.get()
        assertEquals(DailyLimits(350, 750_000, 2_500_000), saved.dailyLimits)
        assertEquals("Saved.", viewModel.uiState.value.savedMessage)
        assertEquals("2.50", viewModel.money.text.toString(), "the amount is written back as money")
        assertFalse(viewModel.uiState.value.usual)

        viewModel.setMoneyLimitOn(false)
        viewModel.save()
        saved = app.settings.get()
        assertNull(saved.maxSpendPerDayMicros, "no money limit")
        assertEquals(350, saved.maxModelCallsPerDay)
        assertFalse(viewModel.uiState.value.moneyLimitOn)
        assertEquals("No money limit", viewModel.uiState.value.lines[2].text)

        viewModel.restoreUsual()
        assertEquals(DailyLimits.DEFAULT, app.settings.get().dailyLimits)
        assertEquals("200", viewModel.answers.text.toString())
        assertTrue(viewModel.uiState.value.moneyLimitOn)
        assertTrue(viewModel.uiState.value.usual)
    }

    @Test
    fun `a value that cannot be a limit is refused with its reason and nothing is saved`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val viewModel = limits(app)
        viewModel.answers.setTextAndPlaceCursorAtEnd("0")
        viewModel.tokens.setTextAndPlaceCursorAtEnd("")
        viewModel.money.setTextAndPlaceCursorAtEnd("0.001")
        viewModel.save()
        val errors = viewModel.uiState.value.errors
        assertEquals("Choose between 1 and 100,000 answers.", errors.answers)
        assertEquals("Say how many tokens a day, for example 500000.", errors.tokens)
        assertEquals("Write an amount such as 1.00 or 0.50.", errors.money)
        assertEquals(FrameworkSettings().dailyLimits, app.settings.get().dailyLimits)
        assertNull(viewModel.uiState.value.savedMessage)

        viewModel.answers.setTextAndPlaceCursorAtEnd("100")
        viewModel.tokens.setTextAndPlaceCursorAtEnd("999")
        viewModel.money.setTextAndPlaceCursorAtEnd("0.00")
        viewModel.save()
        assertNull(viewModel.uiState.value.errors.answers)
        assertEquals("Choose between 1,000 and 1,000,000,000 tokens.", viewModel.uiState.value.errors.tokens)
        assertEquals("Choose between 0.01 and 1000.00 a day.", viewModel.uiState.value.errors.money)
    }

    @Test
    fun `today's use is counted as the limiter counts it, and raising a reached limit says to resume`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.settings.update { it.copy(maxModelCallsPerDay = 2) }
        repeat(2) { app.usage.record(call()) }
        val viewModel = limits(app)
        val state = viewModel.uiState.value
        assertEquals(LimitLineUi("Answers", "2 of 2 today", reached = true), state.lines[0])
        assertEquals(
            "Today's limit of 2 model answers is reached. It starts again at midnight, or you can raise it in Settings › Daily limits.",
            state.reached,
        )

        viewModel.answers.setTextAndPlaceCursorAtEnd("10")
        viewModel.save()
        assertEquals("Saved. Paused objectives do not go on by themselves: open each one and tap Resume.", viewModel.uiState.value.savedMessage)
        assertNull(viewModel.uiState.value.reached)
        assertEquals("2 of 10 today", viewModel.uiState.value.lines[0].text)
    }

    @Test
    fun `money is read as the owner types it`() {
        assertEquals(1_000_000, DailyLimitsViewModel.moneyMicros("1"))
        assertEquals(1_500_000, DailyLimitsViewModel.moneyMicros(" 1.5 "))
        assertEquals(250_000, DailyLimitsViewModel.moneyMicros("0,25"))
        assertEquals(500_000, DailyLimitsViewModel.moneyMicros(".50"))
        assertNull(DailyLimitsViewModel.moneyMicros("1.234"))
        assertNull(DailyLimitsViewModel.moneyMicros("."))
        assertNull(DailyLimitsViewModel.moneyMicros("abc"))
        assertEquals("1.00", DailyLimitsViewModel.moneyText(1_000_000))
        assertEquals("0.25", DailyLimitsViewModel.moneyText(250_000))
    }

    @Test
    fun `the lines say reached in words, and a money limit counts only priced models`() {
        val lines = DailyLimitsViewModel.linesOf(TodayUse(DailyUse(calls = 5, tokens = 1_200, spendMicros = 1_000_000, priced = true), DailyLimits(10, 1_000, 1_000_000)))
        assertEquals(LimitLineUi("Tokens", "1,200 of 1,000 today", reached = true), lines[1])
        assertEquals(LimitLineUi("Money", "US$1.00 of US$1.00 today (estimated)", reached = true), lines[2])
        assertEquals(
            "Today's limit of 1,000 tokens is reached. It starts again at midnight, or you can raise it in Settings › Daily limits.",
            DailyLimitsViewModel.reachedSentence(TodayUse(DailyUse(5, 1_200, 0, priced = false), DailyLimits(10, 1_000, 1_000_000))),
        )
    }
}
