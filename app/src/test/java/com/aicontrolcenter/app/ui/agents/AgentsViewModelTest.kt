package com.aicontrolcenter.app.ui.agents

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.agents(app: TestApp, initial: AgentStatusFilter = AgentStatusFilter.ALL) =
        AgentsViewModel(app.graph.registryDirectory, initial).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `the starter team is listed in creation order with a calm status`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val state = agents(app).uiState.value

        assertEquals(listOf("Director", "Researcher", "Analyst", "Writer", "Reviewer", "Operator"), state.rows.map { it.name })
        assertEquals(6, state.totalCount)
        assertTrue(state.rows.all { it.model == null && it.status.text == "No model yet" && it.status.tone == StatusTone.INFO })
        assertTrue(state.rows.all { it.statusDetail == null }, "the chip already says everything there is to say")
        assertEquals(listOf("Director"), state.rows.filter { it.isDesignatedDirector }.map { it.name })
        assertFalse(state.hasActiveFilters)
    }

    @Test
    fun `search and the role and status filters narrow the list together`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        app.graph.agentManager.setEnabled(app.agentNamed("Writer").profile.id, false)
        val viewModel = agents(app)

        viewModel.setRoleFilter(AgentKind.SPECIALIST)
        assertEquals(listOf("Researcher", "Analyst", "Writer"), viewModel.uiState.value.rows.map { it.name })

        viewModel.setStatusFilter(AgentStatusFilter.DISABLED)
        assertEquals(listOf("Writer"), viewModel.uiState.value.rows.map { it.name })

        viewModel.setStatusFilter(AgentStatusFilter.UNAVAILABLE)
        assertEquals(listOf("Researcher", "Analyst"), viewModel.uiState.value.rows.map { it.name })

        viewModel.setQuery("  ANA ")
        assertEquals(listOf("Analyst"), viewModel.uiState.value.rows.map { it.name })
        assertTrue(viewModel.uiState.value.hasActiveFilters)

        viewModel.setQuery("nobody")
        assertEquals(emptyList(), viewModel.uiState.value.rows)
        assertEquals(6, viewModel.uiState.value.totalCount, "no match is not the same as no agents")

        viewModel.clearFilters()
        assertEquals(6, viewModel.uiState.value.rows.size)
        assertNull(viewModel.uiState.value.roleFilter)
    }

    @Test
    fun `search also finds an agent by its role or description`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val viewModel = agents(app)

        viewModel.setQuery("reviewer")
        assertEquals(listOf("Reviewer"), viewModel.uiState.value.rows.map { it.name })
        val description = app.agentNamed("Analyst").profile.description
        viewModel.setQuery(description.take(12))
        assertTrue("Analyst" in viewModel.uiState.value.rows.map { it.name })
    }

    @Test
    fun `the list opened from the dashboard starts with the requested status filter`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        app.registerSampleModel()
        app.graph.agentManager.assignModel(app.director().profile.id, ModelId("mdl_sample"))

        val state = agents(app, AgentStatusFilter.UNAVAILABLE).uiState.value
        assertEquals(AgentStatusFilter.UNAVAILABLE, state.statusFilter)
        assertEquals(5, state.rows.size)
        assertFalse("Director" in state.rows.map { it.name })
    }

    @Test
    fun `a row shows the model with its provider and where prompts really go`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        // Stored as "on device", but the address is on the local network: the wider one is shown.
        app.providers.upsert(Samples.provider(locality = Locality.ON_DEVICE))
        app.models.upsert(Samples.model())
        val viewModel = agents(app)
        app.graph.agentManager.assignModel(app.agentNamed("Researcher").profile.id, ModelId("mdl_sample"))
        app.graph.agentManager.assignModel(app.agentNamed("Analyst").profile.id, ModelId("mdl_gone"))

        val researcher = viewModel.uiState.value.rows.first { it.name == "Researcher" }
        assertEquals(ModelRefUi("Gemma 3 4B", "Home server", Locality.LOCAL_NETWORK), researcher.model)
        assertEquals("Ready", researcher.status.text)

        val analyst = viewModel.uiState.value.rows.first { it.name == "Analyst" }
        assertEquals(false, analyst.model?.registered)
        assertEquals("Model not registered", analyst.status.text)
        assertEquals("Its model is no longer registered.", analyst.statusDetail)
        assertEquals(StatusTone.UNAVAILABLE, analyst.status.tone)
    }

    @Test
    fun `an empty registry is an empty list, not a crash`() = runTest(mainRule.dispatcher) {
        val state = agents(TestApp(backgroundScope)).uiState.value
        assertFalse(state.isLoading)
        assertEquals(0, state.totalCount)
        assertEquals(emptyList(), state.rows)
    }
}
