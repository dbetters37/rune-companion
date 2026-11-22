package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.ui.shell.Section
import com.aicontrolcenter.app.ui.shell.ShellViewModel
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.testkit.Samples
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

class DashboardViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.dashboard(app: TestApp) = DashboardViewModel(
        app.graph.registryDirectory, app.graph.activityLog, app.graph.aiAppRegistry, app.clock, ZoneOffset.UTC,
    ).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `a fresh starter team is explained once and is not a list of errors`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val state = dashboard(app).uiState.value

        assertFalse(state.isLoading)
        assertEquals(SETUP_NOTICE, state.setupNotice)
        assertEquals(emptyList(), state.attention)
        with(state.summary) {
            assertEquals(6, agentCount)
            assertEquals(0, readyAgents)
            assertEquals(6, agentsWithoutModel)
            assertEquals(0, unavailableAgents)
            assertEquals(0, disabledAgents)
            assertEquals(0, modelCount)
            assertEquals(0, providerCount)
        }
        val director = assertNotNull(state.summary.director)
        assertEquals("Director", director.name)
        assertFalse(director.canRun)
        assertTrue(director.awaitingModel, "a Director without a model is waiting, not broken")
        assertEquals("Cannot run yet: no model", director.runLabel)
    }

    @Test
    fun `an empty registry asks for nothing and explains nothing`() = runTest(mainRule.dispatcher) {
        val state = dashboard(TestApp(backgroundScope)).uiState.value

        assertFalse(state.isLoading)
        assertEquals(0, state.summary.agentCount)
        assertNull(state.summary.director)
        assertNull(state.setupNotice, "there is no agent that would need a model")
        assertEquals(emptyList(), state.attention, "with nobody to direct, a missing Director is not a problem yet")
        assertEquals(emptyList(), state.network.nodes)
        assertEquals(emptyList(), state.recentActivity)
    }

    @Test
    fun `faults are listed before setup that was never finished`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        app.providers.upsert(Samples.provider(status = ConnectionStatus.AUTH_ERROR))
        app.models.upsert(Samples.model())
        app.graph.agentManager.assignModel(app.agentNamed("Analyst").profile.id, ModelId("mdl_sample"))

        val state = dashboard(app).uiState.value
        assertEquals(
            listOf("The Director has no model", "Analyst cannot run", "4 agents have no model"),
            state.attention.map { it.title },
        )
        assertEquals(
            listOf(StatusTone.ATTENTION, StatusTone.UNAVAILABLE, StatusTone.ATTENTION),
            state.attention.map { it.tone },
        )
        assertEquals("Researcher, Writer and 2 others cannot run until each has a model.", state.attention.last().detail)
    }

    @Test
    fun `once a model exists, agents without one are something to fix`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        app.registerSampleModel()
        val state = dashboard(app).uiState.value

        assertNull(state.setupNotice)
        val director = app.director().profile
        assertEquals(
            listOf(
                "The Director has no model" to AttentionTarget.Agent(director.id),
                "5 agents have no model" to AttentionTarget.UnavailableAgents,
            ),
            state.attention.map { it.title to it.target },
        )
        assertEquals(1, state.summary.modelCount)
        assertEquals(1, state.summary.providerCount)
    }

    @Test
    fun `an agent with a working model is ready and drops out of the attention list`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        app.registerSampleModel()
        val viewModel = dashboard(app)
        app.snapshot().agents.forEach { app.graph.agentManager.assignModel(it.profile.id, ModelId("mdl_sample")) }

        val state = viewModel.uiState.value
        assertEquals(emptyList(), state.attention)
        assertEquals(6, state.summary.readyAgents)
        assertTrue(assertNotNull(state.summary.director).canRun)
        assertEquals("Can run", state.summary.director?.runLabel)
        assertTrue(state.network.edges.all { it.live })
    }

    @Test
    fun `a broken provider is reported per agent with the framework's reason`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        app.providers.upsert(Samples.provider(status = ConnectionStatus.OFFLINE))
        app.models.upsert(Samples.model())
        val researcher = app.agentNamed("Researcher").profile
        app.snapshot().agents.forEach { app.graph.agentManager.assignModel(it.profile.id, ModelId("mdl_sample")) }

        val state = dashboard(app).uiState.value
        val item = state.attention.first { it.target == AttentionTarget.Agent(researcher.id) }
        assertEquals("Researcher cannot run", item.title)
        assertEquals("Provider Home server is offline.", item.detail)
        assertEquals(StatusTone.UNAVAILABLE, item.tone)
        assertEquals("The Director cannot run", state.attention.first().title)
        assertEquals("Cannot run: provider offline", state.summary.director?.runLabel)
        assertEquals(6, state.summary.unavailableAgents)
        assertEquals(0, state.summary.agentsWithoutModel)
    }

    @Test
    fun `a disabled or missing Director is the first thing reported`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val viewModel = dashboard(app)
        val director = app.director().profile

        app.graph.agentManager.setEnabled(director.id, false)
        assertEquals("The Director is disabled", viewModel.uiState.value.attention.first().title)
        assertEquals(AttentionTarget.Agent(director.id), viewModel.uiState.value.attention.first().target)
        assertEquals(1, viewModel.uiState.value.summary.disabledAgents)

        app.settings.update { it.copy(directorAgentId = null) }
        val state = viewModel.uiState.value
        assertEquals("No Director is designated", state.attention.first().title)
        assertEquals(AttentionTarget.Agents, state.attention.first().target)
        assertNull(state.summary.director)
        assertEquals(emptyList(), state.network.edges, "without a Director there is no reporting structure")
    }

    @Test
    fun `apps that are gone, disabled in Android or unverified lead to their own page`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        app.installSampleApps()
        app.graph.aiAppRegistry.discover()
        val viewModel = dashboard(app)

        val helper = app.appNamed("AI Helper")
        assertEquals(
            listOf("AI Helper is unverified" to AttentionTarget.AiApp(helper.id)),
            viewModel.uiState.value.attention.map { it.title to it.target },
        )
        assertEquals(3, viewModel.uiState.value.summary.installedApps)

        app.scanner.aiApps = listOf(TestApp.assistant.copy(enabledInSystem = false), TestApp.lookalike)
        app.graph.aiAppRegistry.refreshStatuses()
        app.graph.aiAppRegistry.confirm(helper.id)

        val state = viewModel.uiState.value
        assertEquals(
            listOf("Example Chatbot is no longer installed", "Sample Assistant is disabled in Android"),
            state.attention.map { it.title },
        )
        assertEquals(1, state.summary.installedApps)
        assertEquals(3, state.summary.registeredApps)
        assertEquals(AiAppStatus.NOT_INSTALLED, app.appNamed("Example Chatbot").status)
    }

    @Test
    fun `navigation badges count the same items by the section that fixes them`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val shell = ShellViewModel(app.graph.registryDirectory).also { it.uiState.activeIn(backgroundScope) }
        assertEquals(emptyMap(), shell.uiState.value.badges, "a fresh install shows no badges")

        app.installSampleApps()
        app.graph.aiAppRegistry.discover()
        app.registerSampleModel()

        assertEquals(mapOf(Section.AGENTS to 2, Section.MODELS to 1), shell.uiState.value.badges)
        assertEquals(1, shell.uiState.value.moreBadge, "Models is behind More on a phone")
    }

    @Test
    fun `the network puts the Director in the centre and everyone else reports to it`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val viewModel = dashboard(app)
        val network = viewModel.uiState.value.network
        val director = app.director().profile

        assertEquals(director.id.value, network.hub?.id)
        assertEquals(6, network.nodes.size)
        assertEquals(5, network.edges.size)
        assertTrue(network.edges.all { it.toId == director.id.value && !it.live })
        assertEquals(emptyList(), network.pulses)

        val researcher = app.agentNamed("Researcher").profile
        viewModel.selectNode(researcher.id.value)
        assertEquals("Researcher", viewModel.uiState.value.selectedNode?.name)
        assertEquals("No model yet", viewModel.uiState.value.selectedNode?.status?.text)
        viewModel.selectNode(researcher.id.value)
        assertNull(viewModel.uiState.value.selectedNode, "tapping the selected node again clears the selection")

        viewModel.selectNode(researcher.id.value)
        app.graph.agentManager.delete(researcher.id)
        assertNull(viewModel.uiState.value.selectedNode, "a deleted agent cannot stay selected")
    }

    @Test
    fun `scanning from the dashboard reports the outcome and survives a failing scanner`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.installSampleApps()
        val viewModel = dashboard(app)

        viewModel.scanForApps()
        assertEquals("Scan complete: 3 new, 0 changed, 0 no longer installed.", viewModel.uiState.value.message?.text)
        assertFalse(viewModel.uiState.value.isScanning)

        viewModel.scanForApps()
        assertEquals("Scan complete. Nothing has changed.", viewModel.uiState.value.message?.text)

        viewModel.messageShown()
        assertNull(viewModel.uiState.value.message)

        app.scanFailure = IllegalStateException("package manager unavailable")
        viewModel.scanForApps()
        assertEquals("The scan failed: package manager unavailable", viewModel.uiState.value.message?.text)
        assertFalse(viewModel.uiState.value.isScanning, "a failed scan must not leave the button disabled")
    }

    @Test
    fun `latest activity shows the five newest events with the agent's name`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val rows = dashboard(app).uiState.value.recentActivity

        assertEquals(DashboardViewModel.RECENT_ACTIVITY_LIMIT, rows.size)
        assertEquals("Starter team created", rows.first().event.title)
        assertEquals("Operator", rows[1].agentName)
        assertTrue(rows.zipWithNext().all { (newer, older) -> newer.event.at >= older.event.at })
    }
}
