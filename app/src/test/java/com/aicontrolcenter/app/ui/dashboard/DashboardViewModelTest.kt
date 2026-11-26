package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.ui.shell.Section
import com.aicontrolcenter.app.ui.shell.ShellViewModel
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.dashboard.network.toAgentNetwork
import com.aicontrolcenter.app.testing.GatedLlmClient
import com.aicontrolcenter.app.testing.serveModel
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.framework.llm.Simulator
import kotlinx.coroutines.launch
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
        monitor = app.graph.activityMonitor, agentManager = app.graph.agentManager,
    ).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `a fresh starter team is explained once and is not a list of errors`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val state = dashboard(app).uiState.value

        assertFalse(state.isLoading)
        val quickStart = assertNotNull(state.quickStart, "agents without a model get one-tap help")
        assertEquals(6, quickStart.agentsWithoutModel.size)
        assertFalse(quickStart.hasRealModels)
        assertFalse(quickStart.simulatorAvailable, "the simulator is registered at start, which this test skipped")
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
        assertNull(state.quickStart, "there is no agent that would need a model")
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
            listOf("The Director has no model", "Home server rejected its key", "Analyst cannot run", "4 agents have no model"),
            state.attention.map { it.title },
        )
        assertEquals(
            listOf(StatusTone.ATTENTION, StatusTone.UNAVAILABLE, StatusTone.UNAVAILABLE, StatusTone.ATTENTION),
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

        assertEquals(true, state.quickStart?.hasRealModels)
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

    @Test
    fun `try with the simulator gives every agent without a model the simulator`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        app.registerSampleModel()
        app.graph.agentManager.assignModel(app.agentNamed("Writer").profile.id, ModelId("mdl_sample"))
        val viewModel = dashboard(app)
        val quickStart = assertNotNull(viewModel.uiState.value.quickStart)
        assertTrue(quickStart.simulatorAvailable)
        assertTrue(quickStart.hasRealModels)
        assertEquals(5, quickStart.agentsWithoutModel.size)

        viewModel.trySimulator()
        assertNull(viewModel.uiState.value.quickStart, "every agent has a model now")
        assertEquals(ModelId("mdl_sample"), app.agentNamed("Writer").profile.modelId, "a model the owner chose is kept")
        val others = app.agents.getAll().filter { it.name != "Writer" }
        assertTrue(others.all { it.modelId == Simulator.MODEL_ID })
        assertTrue(assertNotNull(viewModel.uiState.value.message).text.endsWith("now use the simulator. Open an agent and tap Chat to try it."))
        assertTrue(app.agentNamed("Researcher").availability.isReady, "the simulator can answer at once")
    }

    @Test
    fun `the simulator is not offered when it is switched off`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        app.graph.providerManager.setModelEnabled(Simulator.MODEL_ID, false)
        val viewModel = dashboard(app)
        assertFalse(assertNotNull(viewModel.uiState.value.quickStart).simulatorAvailable)
        viewModel.trySimulator()
        assertEquals("The simulator is switched off. Switch it on under Models first.", viewModel.uiState.value.message?.text)
        assertTrue(app.agents.getAll().all { it.modelId == null })
    }

    @Test
    fun `provider problems and removed models need attention, and the summary counts providers by state`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        app.providers.upsert(Samples.provider(id = "prv_ok", name = "Desk PC"))
        app.providers.upsert(Samples.provider(id = "prv_down", name = "Laptop", status = ConnectionStatus.OFFLINE, createdAt = 2_000).copy(statusMessage = "Connection refused."))
        app.providers.upsert(
            Samples.provider(id = "prv_cloud", name = "Hosted", baseUrl = "https://api.example.com/v1", locality = Locality.CLOUD, status = ConnectionStatus.UNKNOWN, createdAt = 3_000)
                .copy(apiKeyAlias = null),
        )
        app.models.upsert(Samples.model(providerId = "prv_ok"))
        val researcher = app.agentNamed("Researcher").profile
        app.graph.agentManager.assignModel(researcher.id, ModelId("mdl_sample"))
        app.models.delete(ModelId("mdl_sample"))

        val state = dashboard(app).uiState.value
        val titles = state.attention.map { it.title }
        assertTrue("Laptop is offline" in titles, titles.toString())
        assertTrue("Hosted has no API key" in titles, titles.toString())
        assertTrue("Researcher's model was removed" in titles, titles.toString())
        assertEquals(AttentionTarget.Provider(ProviderId("prv_down")), state.attention.first { it.title == "Laptop is offline" }.target)
        assertTrue(state.attention.first { it.title == "Laptop is offline" }.detail.startsWith("Connection refused."))
        assertTrue(titles.indexOf("Laptop is offline") < titles.indexOf("Researcher's model was removed"), "providers before agents")
        with(state.summary) {
            assertEquals(3, providerCount)
            assertEquals(1, providersOnline)
            assertEquals(1, providerProblems)
        }
        assertEquals(mapOf(Section.MODELS to 2), ShellViewModel.badgesFor(state.attention).filterKeys { it == Section.MODELS })

        app.settings.update { it.copy(localOnlyMode = true) }
        val local = dashboard(app).uiState.value.attention.map { it.title }
        assertFalse("Laptop is offline" in local, "local-only mode blocks it anyway; its old status is not news")
        assertFalse("Hosted has no API key" in local)
    }

    @Test
    fun `agents at work are listed with what they are doing and ringed in the network, with no dot looping`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val client = GatedLlmClient(listOf("Thinking it over", " and done."))
        val model = app.serveModel(client)
        val researcher = app.agentNamed("Researcher").profile
        app.graph.agentManager.assignModel(researcher.id, model.id)
        app.graph.agentManager.assignModel(app.director().profile.id, model.id)
        val viewModel = dashboard(app)
        assertEquals(emptyList(), viewModel.uiState.value.working)

        val conversation = app.graph.agentChat.startConversation(researcher.id)
        backgroundScope.launch { app.graph.agentChat.send(conversation.id, "Look into it") }
        val working = viewModel.uiState.value.working.single()
        assertEquals("Researcher", working.name)
        assertEquals("Writing a reply", working.detail)
        val node = assertNotNull(viewModel.uiState.value.network.node(researcher.id.value))
        assertTrue(node.working)
        assertEquals(StatusTone.WORKING, node.status.tone)
        assertEquals(emptyList(), viewModel.uiState.value.network.pulses, "a chat sends no message between agents: nothing travels (N2)")

        client.release()
        assertEquals(emptyList(), viewModel.uiState.value.working)
        assertEquals(emptyList(), viewModel.uiState.value.network.pulses)
    }

    @Test
    fun `memories are counted by the store's count, not read (P4)`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val counted = java.util.concurrent.atomic.AtomicInteger()
        val viewModel = DashboardViewModel(
            app.graph.registryDirectory, app.graph.activityLog, app.graph.aiAppRegistry, app.clock, ZoneOffset.UTC,
            monitor = app.graph.activityMonitor, memory = app.graph.memoryService,
            memoryCount = { filter -> counted.incrementAndGet(); kotlinx.coroutines.flow.flowOf(42).also { assertEquals(com.aicontrolcenter.core.memory.MemoryFilter(), filter) } },
        ).also { it.uiState.activeIn(backgroundScope) }
        assertEquals(42, viewModel.uiState.value.memory?.memoryCount)
        assertEquals(1, counted.get())
    }

    @Test
    fun `an agent waiting for you or for a teammate is never shown as working (N3)`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val snapshot = app.snapshot()
        val researcher = app.agentNamed("Researcher").profile.id
        val analyst = app.agentNamed("Analyst").profile.id
        val network = snapshot.toAgentNetwork(
            mapOf(
                researcher to com.aicontrolcenter.core.agents.AgentRuntimeState(researcher, com.aicontrolcenter.core.agents.AgentActivity.WAITING_FOR_APPROVAL, "Waiting for your answer: Delete files"),
                analyst to com.aicontrolcenter.core.agents.AgentRuntimeState(analyst, com.aicontrolcenter.core.agents.AgentActivity.WAITING_FOR_AGENT, "Waiting for Researcher's answer"),
            ),
        )
        with(network.node(researcher.value)!!) {
            assertFalse(working)
            assertTrue(waiting)
            assertEquals("Waiting for you", status.text)
            assertEquals(StatusTone.ATTENTION, status.tone)
        }
        with(network.node(analyst.value)!!) {
            assertFalse(working)
            assertEquals("Waiting for a teammate", status.text)
        }
    }
}
