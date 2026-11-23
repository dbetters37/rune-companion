package com.aicontrolcenter.app.ui.memory

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.ProvenanceAction
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryEditorViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun editor(app: TestApp, id: MemoryId? = null, scope: MemoryScope? = null) =
        MemoryEditorViewModel(app.graph.memoryService, app.graph.registryDirectory, app.clock, id, scope, app.graph.memoryConfig.shortTermTtlMs)

    @Test
    fun `a new memory needs a place, a title and content before anything is saved`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val count = app.memories.list().size
        val viewModel = editor(app)

        viewModel.save()

        val state = viewModel.uiState.value
        assertEquals("Choose where this memory goes.", state.scopeError)
        assertEquals("Give it a short title.", state.titleError)
        assertEquals("Write what should be remembered.", state.contentError)
        assertNull(state.savedId)
        assertEquals(count, app.memories.list().size)

        viewModel.setTitle("Prefers trains")
        assertNull(viewModel.uiState.value.titleError, "fixing a field clears its error")
    }

    @Test
    fun `the owner writes a memory into an agent's private memory, with the owner as its source`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val writer = app.agentNamed("Writer").profile
        val viewModel = editor(app)
        val choices = viewModel.uiState.value.scopeChoices
        assertTrue(choices.any { it.scope == MemoryScope.Agent(writer.id) && it.note == "Only Writer can recall it." })
        assertTrue(choices.any { it.kind == ScopeKind.SHARED && it.label == "Research notes" && it.note == "2 agents may read it." })

        viewModel.setScope(MemoryScope.Agent(writer.id))
        viewModel.setTitle("  Sign letters with first name only ")
        viewModel.setContent("Letters to friends end with the owner's first name, no surname.")
        viewModel.setKind(MemoryKind.PREFERENCE)
        viewModel.setImportance(0.73)
        viewModel.addTag(" Letters ")
        viewModel.addTag("letters")
        assertTrue(viewModel.uiState.value.isDirty)
        viewModel.save()

        val id = assertNotNull(viewModel.uiState.value.savedId)
        val saved = assertNotNull(app.memories.get(id))
        assertEquals("Sign letters with first name only", saved.title, "trimmed")
        assertEquals(MemoryScope.Agent(writer.id), saved.scope)
        assertEquals(MemoryKind.PREFERENCE, saved.kind)
        assertEquals(0.7, saved.importance, "kept to the slider's precision")
        assertEquals(setOf("letters"), saved.tags, "lower-case and without duplicates")
        assertEquals(MemoryTier.LONG_TERM, saved.tier)
        assertEquals(ProvenanceOrigin.OWNER, saved.provenance.origin)
        assertNull(saved.provenance.agentId)
        assertFalse(viewModel.uiState.value.isDirty)
    }

    @Test
    fun `the explorer's scope is pre-selected and a short-term memory expires`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = editor(app, scope = MemoryScope.Shared(sample.teamSpace))
        assertEquals(MemoryScope.Shared(sample.teamSpace), viewModel.uiState.value.form.scope)
        assertFalse(viewModel.uiState.value.isDirty, "a pre-selected place is not an edit")

        viewModel.setTitle("Visitors this weekend")
        viewModel.setContent("Two friends stay from Friday to Sunday.")
        viewModel.setShortTerm(true)
        viewModel.save()

        val saved = assertNotNull(app.memories.get(assertNotNull(viewModel.uiState.value.savedId)))
        assertEquals(MemoryTier.SHORT_TERM, saved.tier)
        assertNotNull(saved.expiresAt)
    }

    @Test
    fun `editing changes only what was edited and keeps where it came from`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val before = assertNotNull(app.memories.get(sample.lisbonTrip))
        val viewModel = editor(app, id = sample.lisbonTrip)
        val state = viewModel.uiState.value
        assertFalse(state.isNew)
        assertEquals("Researcher", state.fixedScope?.label)
        assertEquals(before.title, state.form.title)

        viewModel.setScope(MemoryScope.Shared(sample.teamSpace))
        assertEquals(before.scope, viewModel.uiState.value.form.scope, "an existing memory cannot move")
        viewModel.setTitle("Lisbon trip, 12 to 17 March")
        viewModel.save()

        val after = assertNotNull(app.memories.get(sample.lisbonTrip))
        assertEquals("Lisbon trip, 12 to 17 March", after.title)
        assertEquals(before.content, after.content)
        assertEquals(before.provenance, after.provenance, "provenance is never edited away")
        assertEquals(before.revision + 1, after.revision)
        val edit = after.history.last()
        assertEquals(ProvenanceAction.EDITED, edit.action)
        assertEquals(ProvenanceOrigin.OWNER, edit.byOrigin, "the history says the owner changed it")
        assertEquals(sample.lisbonTrip, viewModel.uiState.value.savedId)
    }

    @Test
    fun `task memory stays short-term`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val task = app.memories.list().single { it.scope is MemoryScope.Task }
        val viewModel = editor(app, id = task.id)
        assertTrue(viewModel.uiState.value.isTaskMemory)

        viewModel.setShortTerm(false)
        assertTrue(viewModel.uiState.value.form.shortTerm)
    }

    @Test
    fun `editing a memory that is gone says so`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val viewModel = editor(app, id = MemoryId("mem_gone"))
        assertTrue(viewModel.uiState.value.notFound)
        viewModel.save()
        assertNull(viewModel.uiState.value.savedId)
    }
}
