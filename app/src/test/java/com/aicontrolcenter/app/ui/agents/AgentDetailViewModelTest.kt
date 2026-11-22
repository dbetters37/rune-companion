package com.aicontrolcenter.app.ui.agents

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.framework.agents.AgentTemplates
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentDetailViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.detail(app: TestApp, id: AgentId) =
        AgentDetailViewModel(id, app.graph.registryDirectory, app.graph.agentManager).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `duplicating creates an independent copy and reports its id once`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val researcher = app.agentNamed("Researcher").profile
        val viewModel = detail(app, researcher.id)

        viewModel.duplicate()
        val copyId = assertNotNull(viewModel.uiState.value.duplicateId)
        val copy = assertNotNull(app.agents.get(copyId))
        assertEquals("Researcher copy", copy.name)
        assertEquals(researcher.systemInstructions, copy.systemInstructions)
        assertEquals("Created \"Researcher copy\".", viewModel.uiState.value.message?.text)
        assertFalse(viewModel.uiState.value.isBusy)

        viewModel.duplicateHandled()
        assertNull(viewModel.uiState.value.duplicateId)
        assertEquals(7, app.agents.getAll().size)
    }

    @Test
    fun `deleting an ordinary agent removes it and closes the screen`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val writer = app.agentNamed("Writer").profile
        val viewModel = detail(app, writer.id)
        assertNull(viewModel.uiState.value.agent?.deleteBlockedReason)

        viewModel.delete()
        assertTrue(viewModel.uiState.value.deleted)
        assertNull(viewModel.uiState.value.agent)
        assertNull(app.agents.get(writer.id))
    }

    @Test
    fun `the designated Director cannot be deleted and the reason is given`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val director = app.director().profile
        val viewModel = detail(app, director.id)

        val agent = assertNotNull(viewModel.uiState.value.agent)
        assertTrue(agent.isDesignatedDirector)
        assertFalse(agent.canDesignateAsDirector)
        assertTrue(assertNotNull(agent.deleteBlockedReason).contains("Designate another Director first"))

        // Even if the screen let the request through, the manager refuses and nothing is lost.
        viewModel.delete()
        assertFalse(viewModel.uiState.value.deleted)
        assertEquals(
            "Director is the designated Director. Designate another Director before deleting it.",
            viewModel.uiState.value.message?.text,
        )
        assertNotNull(app.agents.get(director.id))
    }

    @Test
    fun `designating a second Director moves the position and frees the first for deletion`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val first = app.director().profile
        val second = app.graph.agentManager.createFromTemplate(AgentTemplates.DIRECTOR, "Night Director")
        val viewModel = detail(app, second.id)
        assertTrue(assertNotNull(viewModel.uiState.value.agent).canDesignateAsDirector)

        viewModel.designateAsDirector()
        assertEquals(second.id, app.settings.get().directorAgentId)
        assertTrue(assertNotNull(viewModel.uiState.value.agent).isDesignatedDirector)
        assertEquals("This agent is now the designated Director.", viewModel.uiState.value.message?.text)

        val firstScreen = detail(app, first.id)
        assertNull(assertNotNull(firstScreen.uiState.value.agent).deleteBlockedReason)
        firstScreen.delete()
        assertTrue(firstScreen.uiState.value.deleted)
    }

    @Test
    fun `only an agent with the Director role can be designated`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val director = app.director().profile
        val viewModel = detail(app, app.agentNamed("Analyst").profile.id)
        assertFalse(assertNotNull(viewModel.uiState.value.agent).canDesignateAsDirector)

        viewModel.designateAsDirector()
        assertEquals(
            "Only an agent with the Director role can be designated as Director.",
            viewModel.uiState.value.message?.text,
        )
        assertEquals(director.id, app.settings.get().directorAgentId)
    }

    @Test
    fun `disabling and enabling is reflected in the status`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val viewModel = detail(app, app.agentNamed("Reviewer").profile.id)

        viewModel.setEnabled(false)
        assertEquals("Disabled", viewModel.uiState.value.agent?.status?.text)
        viewModel.setEnabled(true)
        assertEquals("No model yet", viewModel.uiState.value.agent?.status?.text)
    }

    @Test
    fun `models, fallbacks and the track record are resolved against the registry`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val researcher = app.agentNamed("Researcher").profile
        val viewModel = detail(app, researcher.id)

        val before = assertNotNull(viewModel.uiState.value.agent)
        assertNull(before.model)
        assertFalse(before.modelsAvailable)
        assertFalse(before.hasHistory)

        app.registerSampleModel()
        app.graph.agentManager.assignModel(researcher.id, ModelId("mdl_sample"))
        app.agents.updateStats(researcher.id) { AgentStats(tasksAssigned = 4, tasksCompleted = 3, tasksFailed = 1) }

        val after = assertNotNull(viewModel.uiState.value.agent)
        assertEquals("Gemma 3 4B", after.model?.name)
        assertTrue(after.modelsAvailable)
        assertTrue(after.hasHistory)
        assertEquals(3, after.stats.tasksCompleted)
        assertEquals("Ready", after.status.text)
    }

    @Test
    fun `a stale id shows nothing and every action is refused without crashing`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val viewModel = detail(app, AgentId("agt_gone"))
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.agent)

        viewModel.duplicate()
        assertEquals("That agent no longer exists.", viewModel.uiState.value.message?.text)
        viewModel.setEnabled(false)
        viewModel.designateAsDirector()
        viewModel.delete()
        assertFalse(viewModel.uiState.value.isBusy)
        assertEquals(6, app.agents.getAll().size)
    }
}
