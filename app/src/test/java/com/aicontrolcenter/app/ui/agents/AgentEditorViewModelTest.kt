package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentField
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.AgentStats
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.framework.agents.AgentTemplates
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentEditorViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun editor(app: TestApp, editing: AgentId? = null, template: String? = null, store: DraftStore = DraftStore.None) =
        AgentEditorViewModel(app.graph.agentManager, app.graph.registryDirectory, editing, template, store)

    private class MemoryDraftStore(var value: String? = null) : DraftStore {
        override fun read(): String? = value
        override fun write(value: String?) {
            this.value = value
        }
    }

    @Test
    fun `a template opens with a free name, valid and unchanged`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val state = editor(app, template = AgentTemplates.RESEARCHER).uiState.value

        assertFalse(state.isLoading)
        assertTrue(state.isNew)
        assertEquals("Researcher 2", state.draft.name, "the starter team already has a Researcher")
        assertEquals(AgentTemplates.RESEARCHER, state.draft.templateId)
        assertEquals(emptyMap(), state.issues)
        assertFalse(state.isDirty)
        assertEquals(emptyList(), state.models, "no models exist in this phase")
    }

    @Test
    fun `saving a new agent creates it in the registry`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val viewModel = editor(app, template = AgentTemplates.CODER)
        viewModel.setName("Build Engineer")
        viewModel.addSkill("  Gradle ")
        viewModel.addResponsibility("Keep the build green")
        viewModel.addResponsibility("keep the build GREEN")
        assertTrue(viewModel.uiState.value.isDirty)

        viewModel.save()
        val savedId = assertNotNull(viewModel.uiState.value.savedAgentId)
        val saved = assertNotNull(app.agents.get(savedId))
        assertEquals("Build Engineer", saved.name)
        assertEquals(AgentKind.SPECIALIST, saved.kind)
        assertTrue("gradle" in saved.skills, "skills are stored lower-case")
        assertEquals(1, saved.responsibilities.count { it.equals("Keep the build green", ignoreCase = true) })
        assertNull(saved.modelId)
        assertFalse(viewModel.uiState.value.isDirty)
        assertEquals(7, app.agents.getAll().size)

        viewModel.save()
        assertEquals(7, app.agents.getAll().size, "a second tap on Save creates nothing more")
        assertNull(viewModel.uiState.value.message)

        viewModel.savedHandled()
        assertNull(viewModel.uiState.value.savedAgentId)
    }

    @Test
    fun `problems reported by the manager appear on their field and block saving`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val viewModel = editor(app, template = AgentTemplates.BLANK)

        viewModel.setName("   ")
        assertEquals("Give the agent a name.", viewModel.uiState.value.issue(AgentField.NAME))

        viewModel.setName("analyst")
        assertEquals("Another agent is already called \"analyst\".", viewModel.uiState.value.issue(AgentField.NAME))

        viewModel.edit { it.copy(generation = it.generation.copy(temperature = 3.5)) }
        viewModel.edit { it.copy(delegation = it.delegation.copy(maxDepth = 99)) }
        assertEquals("Temperature must be between 0 and 2.", viewModel.uiState.value.issue(AgentField.GENERATION))
        assertNotNull(viewModel.uiState.value.issue(AgentField.DELEGATION))

        viewModel.save()
        val state = viewModel.uiState.value
        assertNull(state.savedAgentId)
        assertFalse(state.isSaving)
        assertEquals("Fix the marked fields, then save again.", state.message?.text)
        assertEquals(6, app.agents.getAll().size, "nothing was created")

        viewModel.setName("Analyst Two")
        viewModel.setTemperature(0.74)
        viewModel.edit { it.copy(delegation = it.delegation.copy(maxDepth = 1)) }
        assertEquals(emptyMap(), viewModel.uiState.value.issues)
        assertEquals(0.7, viewModel.uiState.value.draft.generation.temperature)
    }

    @Test
    fun `editing an existing agent changes it in place and keeps its track record`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val writer = app.agentNamed("Writer").profile
        app.agents.updateStats(writer.id) { AgentStats(tasksCompleted = 9) }
        val viewModel = editor(app, editing = writer.id)

        val loaded = viewModel.uiState.value
        assertFalse(loaded.isNew)
        assertEquals("Writer", loaded.draft.name)
        assertFalse(loaded.isDirty)
        assertEquals(emptyMap(), loaded.issues, "an agent may keep its own name")

        viewModel.edit { it.copy(description = "Writes release notes.", personality = "Dry") }
        viewModel.setMaxOutputTokensText("4 0 9 6 tokens")
        viewModel.save()

        assertEquals(writer.id, viewModel.uiState.value.savedAgentId)
        val saved = assertNotNull(app.agents.get(writer.id))
        assertEquals("Writes release notes.", saved.description)
        assertEquals(4096, saved.generation.maxOutputTokens)
        assertEquals(writer.createdAt, saved.createdAt)
        assertEquals(9, app.agents.getStats(writer.id).tasksCompleted)
        assertEquals(6, app.agents.getAll().size)
    }

    @Test
    fun `the fields lead - an edit never overwrites keys the draft has not seen, and Save takes the fields as they are`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val writer = app.agentNamed("Writer").profile
        val viewModel = editor(app, editing = writer.id)
        assertEquals("Writer", viewModel.fields.name.text.toString(), "the loaded agent is put in its fields once")
        assertEquals(writer.systemInstructions, viewModel.fields.instructions.text.toString())

        // Keys the draft has not caught up with yet (no snapshot notification has gone out in this test).
        viewModel.fields.description.setTextAndPlaceCursorAtEnd("Writes release notes, typed fast")
        viewModel.edit { it.copy(colorIndex = 3) }
        assertEquals("Writes release notes, typed fast", viewModel.fields.description.text.toString(), "a change from elsewhere kept the keys")
        assertEquals("Writes release notes, typed fast", viewModel.uiState.value.draft.description)

        // A text changed from code goes into its field.
        viewModel.setName("Release Writer")
        assertEquals("Release Writer", viewModel.fields.name.text.toString())

        viewModel.fields.personality.setTextAndPlaceCursorAtEnd("Dry, exact")
        viewModel.save()
        val saved = assertNotNull(app.agents.get(writer.id))
        assertEquals("Release Writer", saved.name)
        assertEquals("Writes release notes, typed fast", saved.description)
        assertEquals("Dry, exact", saved.personality, "the last keys before Save are saved")
        assertEquals(3, saved.colorIndex)
    }

    @Test
    fun `the designated Director cannot be given another role`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val director = app.director().profile
        val viewModel = editor(app, editing = director.id)

        viewModel.edit { it.copy(kind = AgentKind.SPECIALIST) }
        assertTrue(assertNotNull(viewModel.uiState.value.issue(AgentField.KIND)).contains("designated Director"))
        viewModel.save()
        assertEquals(AgentKind.DIRECTOR, app.agents.get(director.id)?.kind)
    }

    @Test
    fun `the model picker offers registered models and a model is never its own fallback`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        app.providers.upsert(Samples.provider())
        app.providers.upsert(Samples.provider(id = "prv_cloud", name = "Hosted", baseUrl = "https://api.example.com/v1", locality = Locality.CLOUD, createdAt = 2_000))
        app.models.upsert(Samples.model())
        app.models.upsert(Samples.model(id = "mdl_cloud", providerId = "prv_cloud", modelName = "big-1", enabled = false, createdAt = 2_000))
        val viewModel = editor(app, template = AgentTemplates.ANALYST)

        val options = viewModel.uiState.value.models
        assertEquals(listOf("Home server" to Locality.LOCAL_NETWORK, "Hosted" to Locality.CLOUD), options.map { it.providerName to it.locality })
        assertEquals(listOf(null, "Disabled"), options.map { it.note })

        viewModel.setModel(ModelId("mdl_sample"))
        viewModel.addFallback(ModelId("mdl_sample"))
        viewModel.addFallback(ModelId("mdl_cloud"))
        viewModel.addFallback(ModelId("mdl_cloud"))
        assertEquals(listOf(ModelId("mdl_cloud")), viewModel.uiState.value.draft.fallbackModelIds)

        viewModel.setModel(ModelId("mdl_cloud"))
        assertEquals(emptyList(), viewModel.uiState.value.draft.fallbackModelIds)
        viewModel.setModel(null)
        assertNull(viewModel.uiState.value.draft.modelId)

        app.models.delete(ModelId("mdl_cloud"))
        assertEquals(1, viewModel.uiState.value.models.size, "the picker follows the registry while open")
    }

    @Test
    fun `an agent deleted elsewhere cannot be edited or saved`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val gone = editor(app, editing = AgentId("agt_gone"))
        assertTrue(gone.uiState.value.notFound)
        gone.setName("Ghost")
        gone.save()
        assertNull(gone.uiState.value.savedAgentId)

        val writer = app.agentNamed("Writer").profile
        val open = editor(app, editing = writer.id)
        app.graph.agentManager.delete(writer.id)
        open.setName("Writer the Second")
        open.save()
        assertNull(open.uiState.value.savedAgentId)
        assertEquals("That agent no longer exists.", open.uiState.value.message?.text)
        assertFalse(open.uiState.value.isSaving)
    }

    @Test
    fun `an unsaved draft survives the screen being recreated and is forgotten once saved or discarded`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val store = MemoryDraftStore()
        val first = editor(app, template = AgentTemplates.WRITER, store = store)
        first.setName("Copy Editor")
        assertNotNull(store.value)

        val recreated = editor(app, template = AgentTemplates.WRITER, store = store)
        assertEquals("Copy Editor", recreated.uiState.value.draft.name)
        assertTrue(recreated.uiState.value.isDirty, "the restored draft still differs from the template")

        recreated.discard()
        assertNull(store.value)

        val unreadable = editor(app, template = AgentTemplates.WRITER, store = MemoryDraftStore("{not json"))
        assertEquals("Writer 2", unreadable.uiState.value.draft.name, "a corrupt stored draft falls back to the template")
    }

    @Test
    fun `a blank agent starts from an empty draft with a usable name`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val state = editor(app).uiState.value
        assertEquals(AgentDraft(name = "New agent"), state.draft)
        assertEquals("Researcher 3", AgentEditorViewModel.uniqueName("Researcher", listOf("researcher", "Researcher 2")))
    }

    @Test
    fun `the editor offers every tool grouped by family, in plain words, and switching one on grants nothing`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.start().join()
        val operator = app.agentNamed("Operator").profile.id
        val viewModel = AgentEditorViewModel(
            app.graph.agentManager, app.graph.registryDirectory, operator, null, DraftStore.None,
            tools = app.graph.toolRegistry.all().map { it.spec },
        )
        val state = viewModel.uiState.value
        val families = state.toolFamilies.map { it.first }
        assertEquals(com.aicontrolcenter.app.ui.tools.ToolFamily.entries.filter { it in families }, families, "families in catalogue order")
        val workspace = state.toolFamilies.first { it.first == com.aicontrolcenter.app.ui.tools.ToolFamily.WORKSPACE }.second
        assertEquals(12, workspace.size)
        assertEquals("Deletes items in the workspace trash for good. You approve it every time.", workspace.first { it.id.value == "workspace.empty_trash" }.description)
        assertTrue(state.tools.first { it.id.value == "web.read_page" }.sendsDataOffDevice)

        viewModel.setTool(com.aicontrolcenter.core.ToolId("scripts.run_shell"), false)
        viewModel.save()
        assertFalse(com.aicontrolcenter.core.ToolId("scripts.run_shell") in app.agentNamed("Operator").profile.toolIds)
        assertEquals(emptyList(), app.permissions.observeAll().first(), "a tool switch is not a permission")
    }
}
