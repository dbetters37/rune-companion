package com.aicontrolcenter.app.ui.models

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ModelSource
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

class ModelEditorViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun editor(app: TestApp, id: ModelId) = ModelEditorViewModel(id, app.graph.registryDirectory, app.graph.providerManager)

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
}
