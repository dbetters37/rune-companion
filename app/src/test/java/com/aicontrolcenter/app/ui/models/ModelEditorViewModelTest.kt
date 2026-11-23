package com.aicontrolcenter.app.ui.models

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContains

class ModelEditorViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun editor(app: TestApp, id: ModelId) = ModelEditorViewModel(id, app.graph.registryDirectory, app.graph.providerManager, app.graph.agentManager)

    @Test
    fun `display name, capabilities and notes are saved, and the owner's description then stands`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.registerSampleModel()
        val id = ModelId("mdl_sample")
        val viewModel = editor(app, id)
        with(viewModel.uiState.value) {
            assertEquals("Gemma 3 4B", draft.displayName)
            assertEquals("gemma3:4b", wireName)
            assertEquals("Home server", providerName)
            assertEquals("8192", draft.contextWindowText)
            assertFalse(isDirty)
        }

        viewModel.setDisplayName("Gemma (fast)")
        viewModel.setNotes("Good enough for summaries.")
        viewModel.setReasoning(CapabilityTier.STRONG)
        viewModel.setCapabilities { it.copy(vision = false) }
        viewModel.setContextWindowText("32k")
        assertEquals("32", viewModel.uiState.value.draft.contextWindowText, "digits only")
        viewModel.setContextWindowText("32768")
        assertTrue(viewModel.uiState.value.isDirty)

        viewModel.save()
        val stored = assertNotNull(app.models.get(id))
        assertEquals("Gemma (fast)", stored.displayName)
        assertEquals("Good enough for summaries.", stored.notes)
        assertEquals(CapabilityTier.STRONG, stored.capabilities.reasoning)
        assertFalse(stored.capabilities.vision)
        assertEquals(32768, stored.capabilities.contextWindow)
        assertEquals(ModelSource.MANUAL, stored.source, "a refresh will no longer overwrite it")
        assertFalse(viewModel.uiState.value.isDirty)
        assertEquals("Saved.", viewModel.uiState.value.message?.text)
    }

    @Test
    fun `a blank name is refused, and switching off and removing work`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.registerSampleModel()
        app.graph.agentManager.ensureDefaults()
        app.graph.agentManager.assignModel(app.agentNamed("Writer").profile.id, ModelId("mdl_sample"))
        val viewModel = editor(app, ModelId("mdl_sample"))
        assertEquals(listOf("Writer"), viewModel.uiState.value.agentsUsing)

        viewModel.setDisplayName("  ")
        assertEquals("Give the model a display name.", viewModel.uiState.value.nameError)
        viewModel.save()
        assertEquals("Gemma 3 4B", app.models.get(ModelId("mdl_sample"))?.displayName)

        viewModel.setEnabled(false)
        assertFalse(assertNotNull(app.models.get(ModelId("mdl_sample"))).enabled)
        assertEquals("Disabled", viewModel.uiState.value.status.text)

        viewModel.remove()
        assertTrue(viewModel.uiState.value.removed)
        assertNull(app.models.get(ModelId("mdl_sample")))
        assertFalse(viewModel.uiState.value.notFound, "leaving after a removal is not a missing model")
    }

    @Test
    fun `the simulator cannot be described differently`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.providerManager.ensureBuiltIns()
        val viewModel = editor(app, Simulator.MODEL_ID)
        assertTrue(viewModel.uiState.value.isSimulator)
        viewModel.setDisplayName("A real model")
        assertFalse(viewModel.uiState.value.isDirty)
        assertEquals(Simulator.MODEL_DISPLAY_NAME, app.models.get(Simulator.MODEL_ID)?.displayName)
        assertEquals(Samples.model().displayName, "Gemma 3 4B")
    }

    // --- Use for agents ---

    @Test
    fun `use for agents offers every agent, the Director first and all chosen, and assigns the model to the chosen ones`() =
        runTest(mainRule.dispatcher) {
            val app = TestApp(backgroundScope)
            app.registerSampleModel()
            app.graph.providerManager.ensureBuiltIns()
            app.graph.agentManager.ensureDefaults()
            val sample = ModelId("mdl_sample")
            app.graph.agentManager.assignModel(app.agentNamed("Writer").profile.id, sample)
            app.graph.agentManager.assignModel(app.agentNamed("Researcher").profile.id, Simulator.MODEL_ID)
            val viewModel = editor(app, sample)
            assertEquals(6, viewModel.uiState.value.agentCount)

            viewModel.openUseForAgents()
            val open = assertNotNull(viewModel.uiState.value.useForAgents)
            assertEquals(
                listOf("Director", "Analyst", "Operator", "Researcher", "Reviewer", "Writer"),
                open.choices.map { it.name },
                "the Director first, then by name",
            )
            assertTrue(open.choices.first().isDirector)
            assertEquals(open.choices.map { it.id }.toSet(), open.selected, "every agent is chosen to begin with")
            val byName = open.choices.associateBy { it.name }
            assertEquals("Now: ${Simulator.MODEL_DISPLAY_NAME}", byName.getValue("Researcher").currentModel)
            assertEquals("Now: no model", byName.getValue("Analyst").currentModel)
            assertEquals("Already uses this model", byName.getValue("Writer").currentModel)
            assertEquals(5, open.changeCount)
            assertEquals("Leaves this device", open.warning?.title, "the same warning as the agent editor gives")
            assertFalse(assertNotNull(open.warning).blocksRunning)

            viewModel.toggleAgentChoice(byName.getValue("Analyst").id)
            assertEquals(4, viewModel.uiState.value.useForAgents?.changeCount)
            viewModel.useForChosenAgents()

            assertNull(viewModel.uiState.value.useForAgents, "the chooser closes")
            assertEquals(
                "Gemma 3 4B is now the model of Director, Operator, Researcher and Reviewer.",
                viewModel.uiState.value.message?.text,
                "names every agent that changed, and not the one that already used it",
            )
            for (name in listOf("Director", "Operator", "Researcher", "Reviewer", "Writer")) {
                assertEquals(sample, app.agentNamed(name).profile.modelId, name)
            }
            assertNull(app.agentNamed("Analyst").profile.modelId, "an agent taken off the list is left alone")
            assertEquals(5, viewModel.uiState.value.agentsUsing.size)
        }

    @Test
    fun `choosing agents that already use it changes nothing, and closing the chooser assigns nothing`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.registerSampleModel()
        app.graph.agentManager.ensureDefaults()
        val sample = ModelId("mdl_sample")
        val viewModel = editor(app, sample)

        viewModel.openUseForAgents()
        viewModel.dismissUseForAgents()
        assertNull(viewModel.uiState.value.useForAgents)
        assertTrue(app.snapshot().agents.all { it.profile.modelId == null })

        app.graph.agentManager.assignModel(app.agentNamed("Writer").profile.id, sample)
        viewModel.openUseForAgents()
        val open = assertNotNull(viewModel.uiState.value.useForAgents)
        open.choices.forEach { viewModel.toggleAgentChoice(it.id) }
        val writer = app.agentNamed("Writer").profile.id
        assertEquals(setOf(writer), viewModel.uiState.value.useForAgents?.selected, "an agent already on it cannot be unticked")
        assertEquals(0, viewModel.uiState.value.useForAgents?.changeCount)
        viewModel.useForChosenAgents()
        assertEquals("Every chosen agent already uses Gemma 3 4B.", viewModel.uiState.value.message?.text)
        assertEquals(1, app.snapshot().agents.count { it.profile.modelId == sample })
    }

    @Test
    fun `a model the agents cannot run on is still offered, as in the agent editor, and the reason is given`() =
        runTest(mainRule.dispatcher) {
            val app = TestApp(backgroundScope, FrameworkSettings(localOnlyMode = true))
            app.registerSampleModel()
            app.graph.agentManager.ensureDefaults()
            val sample = ModelId("mdl_sample")
            val viewModel = editor(app, sample)

            viewModel.openUseForAgents()
            val warning = assertNotNull(viewModel.uiState.value.useForAgents?.warning)
            assertEquals("Local-only mode is on", warning.title)
            assertTrue(warning.blocksRunning)
            assertContains(warning.text, "Home server would send data off this phone")

            viewModel.setEnabled(false)
            assertEquals("The model is disabled", viewModel.uiState.value.useForAgents?.warning?.title, "the open chooser follows the registry")

            val director = app.director().profile.id
            viewModel.uiState.value.useForAgents!!.choices.filter { it.id != director }.forEach { viewModel.toggleAgentChoice(it.id) }
            viewModel.useForChosenAgents()
            assertEquals(
                "Gemma 3 4B is now the model of Director. It cannot run on it yet (The model is disabled).",
                viewModel.uiState.value.message?.text,
            )
            assertEquals(sample, app.director().profile.modelId)
            assertFalse(app.director().availability.isReady)
        }

    @Test
    fun `agent names are all listed`() {
        assertEquals("", listNames(emptyList()))
        assertEquals("Writer", listNames(listOf("Writer")))
        assertEquals("Analyst and Writer", listNames(listOf("Analyst", "Writer")))
        assertEquals("A, B, C, D and E", listNames(listOf("A", "B", "C", "D", "E")))
    }
}
