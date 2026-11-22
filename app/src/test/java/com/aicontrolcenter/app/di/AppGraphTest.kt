package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.activity.Severity
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppGraphTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `starting seeds the starter team once and designates its Director`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val first = app.graph.start()
        first.join()

        assertEquals(6, app.agents.getAll().size)
        assertEquals(app.director().profile.id, app.settings.get().directorAgentId)

        assertSame(first, app.graph.start(), "a second start is the same piece of work")
        assertEquals(6, app.agents.getAll().size)
    }

    @Test
    fun `registered apps are re-checked at start, on a package change and on return to the foreground`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        app.graph.aiAppRegistry.discover()
        app.scanner.aiApps = listOf(TestApp.assistant, TestApp.lookalike)

        app.graph.start().join()
        assertEquals(AiAppStatus.NOT_INSTALLED, app.appNamed("Example Chatbot").status)

        app.scanner.aiApps = listOf(TestApp.chatbot, TestApp.lookalike)
        app.packageChanges.emit(Unit)
        assertEquals(AiAppStatus.INSTALLED, app.appNamed("Example Chatbot").status)
        assertEquals(AiAppStatus.NOT_INSTALLED, app.appNamed("Sample Assistant").status)

        app.scanner.aiApps = listOf(TestApp.chatbot, TestApp.assistant, TestApp.lookalike)
        app.graph.onAppForegrounded()
        assertEquals(AiAppStatus.INSTALLED, app.appNamed("Sample Assistant").status)
    }

    @Test
    fun `the retention setting prunes old activity at start and whenever it changes`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val now = TestApp.START_TIME
        app.activityLog.record(Samples.event("evt_ancient", at = now - 400 * day))
        app.activityLog.record(Samples.event("evt_month_old", at = now - 20 * day))
        app.activityLog.record(Samples.event("evt_recent", at = now - day))

        app.graph.start().join()
        assertTrue(app.activityLog.all.none { it.id.value == "evt_ancient" }, "the default keeps 30 days")
        assertTrue(app.activityLog.all.any { it.id.value == "evt_month_old" })

        app.settings.update { it.copy(activityRetentionDays = 7) }
        assertTrue(app.activityLog.all.none { it.id.value == "evt_month_old" })
        assertTrue(app.activityLog.all.any { it.id.value == "evt_recent" })

        app.settings.update { it.copy(activityRetentionDays = 0) }
        assertTrue(app.activityLog.all.any { it.id.value == "evt_recent" }, "zero keeps everything")
    }

    @Test
    fun `a failing start-up step is logged and does not stop the others`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        app.graph.aiAppRegistry.discover()
        app.scanFailure = IllegalStateException("package manager died")

        app.graph.start().join()
        assertEquals(6, app.agents.getAll().size, "agents are still created when the app scan fails")
        val failure = assertNotNull(app.activityLog.all.firstOrNull { it.type == ActivityType.ERROR })
        assertEquals("Could not refresh AI app statuses", failure.title)
        assertEquals("package manager died", failure.detail)
        assertEquals(Severity.ERROR, failure.severity)
    }

    @Test
    fun `hand-offs made through the graph's launcher are always logged`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        app.graph.aiAppRegistry.discover()

        app.graph.aiAppLauncher.handOffText(app.appNamed("Example Chatbot"), "hello")
        assertEquals(listOf("com.example.chatbot" to "hello"), app.launcher.handedOff)
        assertEquals(ActivityType.DATA_EGRESS, app.activityLog.all.last().type)
    }
}
