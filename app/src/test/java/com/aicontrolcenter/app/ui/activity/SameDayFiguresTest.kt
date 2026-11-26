package com.aicontrolcenter.app.ui.activity

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.recordSampleUsage
import com.aicontrolcenter.app.ui.dashboard.DashboardExtrasViewModel
import com.aicontrolcenter.app.ui.settings.DailyLimitsViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One day, one set of figures (wave 3 design review, D3A-1 and D3A-2): the Dashboard's Today tile, Activity › Usage and
 * cost and Settings › Daily limits read the same day of usage (`recordSampleUsage`: Grok priced, Llama 3.3 70B not, the
 * simulator on this phone) and show the same answers, tokens and money, the money being what the daily limit counts.
 */
class SameDayFiguresTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.dashboard(app: TestApp) = DashboardExtrasViewModel(
        today = app.graph.todayUsage, settings = app.graph.settings, directory = app.graph.registryDirectory, tasks = app.graph.tasks,
        backgroundHealth = app.graph.backgroundHealth, storage = app.graph.storageWatch, safeStart = app.graph.safeStart, clock = app.clock,
        reports = app.graph.usageReports, prices = app.graph.modelPrices, zone = { ZoneOffset.UTC },
    ).also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.usage(app: TestApp) =
        UsageViewModel(app.graph.usageReports, app.graph.modelPrices, app.graph.registryDirectory, { flowOf(emptyList()) }, app.clock, { ZoneOffset.UTC })
            .also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.limits(app: TestApp) =
        DailyLimitsViewModel(app.graph.settings, app.graph.todayUsage).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `the Today tile, Usage and cost and Daily limits show one money figure and the same counts for the same day`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.recordSampleUsage()
        val limiter = app.graph.todayUsage.now().use

        val tile = assertNotNull(dashboard(app).uiState.value.today)
        val summary = assertNotNull(usage(app).uiState.value.summary)
        val lines = limits(app).uiState.value.lines

        // Money: what the limiter counts toward the US$1.00 limit, on all three screens. Never "No cost".
        assertEquals("US$0.38", tile.cost)
        assertEquals("of US$1.00", tile.costCaption)
        assertEquals("US$0.38", summary.cost)
        assertEquals("US$0.38 of US$1.00 today (estimated)", lines.single { it.title == "Money" }.text)
        assertEquals(380_100, limiter.spendMicros, "the limiter's own figure, which the screens round")
        assertEquals("Llama 3.3 70B has no price yet, so its tokens are not in the cost.", tile.costNote)

        // Counts: the models off this phone, as the limits count them; the free part on this phone said beside them.
        assertEquals(22, limiter.calls)
        assertEquals(73_500, limiter.tokens)
        assertEquals(listOf(22L, 22L), listOf(tile.answers, summary.answers))
        assertEquals(listOf(73_500L, 73_500L), listOf(tile.tokens, summary.tokens))
        assertEquals("22 of 200 today", lines.single { it.title == "Answers" }.text)
        assertEquals("73,500 of 500,000 today", lines.single { it.title == "Tokens" }.text)
        assertEquals(6, summary.phoneAnswers)
        assertEquals(2_520, summary.phoneTokens)
        assertEquals("Models on this phone also answered 6 times today: free, and not counted.", tile.phoneNote)
    }

    @Test
    fun `this month reads the same on the Today tile and on Usage and cost`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.recordSampleUsage()
        val vm = usage(app)
        vm.selectPeriod(UsagePeriod.THIS_MONTH)
        val month = assertNotNull(vm.uiState.value.summary)
        val line = assertNotNull(assertNotNull(dashboard(app).uiState.value.today).month)

        assertEquals(47, month.answers)
        assertEquals(151_000, month.tokens)
        val cost = assertNotNull(month.cost)
        assertEquals("This month: 47 answers · 151,000 tokens · $cost for the models with a price", line)
    }

    @Test
    fun `with no price at all the cost is not known, never free`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.recordSampleUsage()
        app.graph.ownerModelPrices.remove(com.aicontrolcenter.core.ModelId("mdl_grok"))
        val tile = assertNotNull(dashboard(app).uiState.value.today)
        assertEquals(DashboardExtrasViewModel.COST_NOT_KNOWN, tile.cost)
        assertEquals("no price entered", tile.costCaption)
        val note = assertNotNull(tile.costNote)
        assertTrue("Grok 4" in note && "Llama 3.3 70B" in note && note.endsWith(" have no price yet, so nothing counts toward your US$1.00 limit."), note)
        assertEquals(null, assertNotNull(usage(app).uiState.value.summary).cost, "Usage and cost says \"Not known\" for it too")
        assertTrue(limits(app).uiState.value.lines.single { it.title == "Money" }.text.startsWith("No model used today has a price"))
    }
}
