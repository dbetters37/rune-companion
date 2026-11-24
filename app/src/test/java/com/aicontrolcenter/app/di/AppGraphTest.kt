package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.llm.ProviderHealth
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.testkit.FakeProviderAdapter
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.assertNull
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
    fun `starting stops what a shell command left running, once, and says so only when there was something`() = runTest(mainRule.dispatcher) {
        // G2: the app died under a shell command, and two of its processes were still there.
        val app = TestApp(backgroundScope)
        app.shellLeftovers = 2

        app.graph.start().join()
        app.graph.start().join()

        assertEquals(1, app.shellSweeps.get(), "once per start of the process")
        val said = app.activityLog.all.single { it.title == "Stopped what a shell command had left running" }
        assertTrue("2 processes" in said.detail, said.detail)
        assertEquals(6, app.agents.getAll().size, "the other steps ran")

        val clean = TestApp(backgroundScope)
        clean.graph.start().join()
        assertEquals(1, clean.shellSweeps.get())
        assertTrue(clean.activityLog.all.none { "left running" in it.title })
    }

    @Test
    fun `F3 - starting puts the workspace trash right after the shell sweep, before any agent acts`() = runTest(mainRule.dispatcher) {
        // What a kill can leave: content in a trash holder whose record was never written, and a half-staged file.
        val app = TestApp(backgroundScope)
        val root = app.workspaceRoot.directory()
        java.io.File(root, ".trash/$LOST_ID").mkdirs()
        java.io.File(root, ".trash/$LOST_ID/contract.txt").writeText("the owner's signed contract")
        java.io.File(root, ".trash/.staging").mkdirs()
        java.io.File(root, ".trash/.staging/w-half").writeText("half")
        var recordAtSweep: Boolean? = null
        app.duringShellSweep = { recordAtSweep = java.io.File(root, ".trash/$LOST_ID.json").exists() }

        app.graph.start().join()

        assertEquals(false, recordAtSweep, "the shell sweep comes first")
        // The tidy is file work in a coroutine of its own: waited for here on this thread, with a limit.
        val record = java.io.File(root, ".trash/$LOST_ID.json")
        repeat(1_000) { if (!record.exists()) Thread.sleep(10) }
        assertTrue(record.exists(), "the holder got its record back at start")
        // A look waits for the tidy, which holds the change lock, and is answered after it.
        assertNull(app.graph.workspace.trashLeftovers())
        assertTrue(java.io.File(root, ".trash/.staging").list().orEmpty().isEmpty(), "what was staged is gone")
        val listed = (app.graph.workspace.listTrash() as com.aicontrolcenter.core.operator.DocResult.Ok).value
        assertEquals(listOf(LOST_ID to true), listed.map { it.id to it.recovered }, "an agent's first look finds it listed, and its look changes nothing")
        assertTrue(app.activityLog.all.none { it.title == "Could not put the workspace trash right" })
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

    @Test
    fun `starting registers the simulator before the team and checks providers in the background`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val adapter = FakeProviderAdapter(health = ProviderHealth(ConnectionStatus.OFFLINE, "Connection refused."))
        app.adaptersByUrl["http://192.168.1.20:8080/v1"] = adapter
        app.providers.upsert(Samples.provider(status = ConnectionStatus.ONLINE))

        app.graph.start().join()
        advanceUntilIdle()
        assertNotNull(app.providers.get(Simulator.PROVIDER_ID), "the simulator is always there")
        assertNotNull(app.models.get(Simulator.MODEL_ID))
        assertEquals(ConnectionStatus.OFFLINE, app.providers.get(ProviderId("prv_sample"))?.status, "a stale status is replaced at start")

        // Back in the foreground soon after: the status is fresh, so nothing is contacted.
        adapter.health = ProviderHealth(ConnectionStatus.ONLINE)
        app.clock.advance(AppStartup.PROVIDER_RECHECK_MS)
        app.graph.onAppForegrounded()
        advanceUntilIdle()
        assertEquals(ConnectionStatus.ONLINE, app.providers.get(ProviderId("prv_sample"))?.status, "an old status is checked again")

        adapter.health = ProviderHealth(ConnectionStatus.OFFLINE, "Gone again.")
        app.graph.onAppForegrounded()
        advanceUntilIdle()
        assertEquals(ConnectionStatus.ONLINE, app.providers.get(ProviderId("prv_sample"))?.status, "a fresh status is not checked again")
    }

    @Test
    fun `deleting an agent removes its conversations`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val writer = app.agentNamed("Writer").profile
        val conversation = app.graph.agentChat.startConversation(writer.id)
        app.graph.agentManager.delete(writer.id)
        assertNull(app.conversations.getConversation(conversation.id))
    }

    private companion object {
        const val LOST_ID = "1700000000000-abc12345"
    }
}
