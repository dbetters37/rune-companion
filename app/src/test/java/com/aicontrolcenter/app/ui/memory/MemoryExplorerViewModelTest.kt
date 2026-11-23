package com.aicontrolcenter.app.ui.memory

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.framework.memory.MemorySeeder
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryExplorerViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.explorer(app: TestApp, scope: ScopeFilter = ScopeFilter.All) =
        MemoryExplorerViewModel(app.graph.memoryService, app.graph.registryDirectory, app.clock, scope, searchDelayMs = 0)
            .also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `browsing lists every memory newest first, each named by where it lives`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val viewModel = explorer(app)

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        val stored = app.memories.list()
        assertEquals(stored.size, state.totalCount)
        assertEquals(stored.map { it.id }, state.rows.map { it.id }, "newest first, as stored")
        val byTitle = state.rows.associateBy { it.title }
        assertEquals(ScopeUi(ScopeKind.PRIVATE, "Researcher", app.agentNamed("Researcher").profile.colorIndex, agentId = app.agentNamed("Researcher").profile.id), byTitle.getValue("Lisbon trip, 12 to 16 March").scope)
        assertEquals(MemorySeeder.TEAM_SPACE_NAME, byTitle.getValue("The owner is vegetarian").scope.label)
        assertEquals(ScopeKind.TASK, byTitle.getValue("Compare Sintra and Cascais by train").scope.kind)
        assertEquals("Researcher · task", byTitle.getValue("Compare Sintra and Cascais by train").scope.label)
        assertTrue(byTitle.getValue("Compare Sintra and Cascais by train").shortTerm)
        assertTrue(state.agentOptions.any { it.label == "Researcher" })
        assertEquals(listOf("Research notes", MemorySeeder.TEAM_SPACE_NAME), state.spaceOptions.map { it.label })
    }

    @Test
    fun `an agent filter shows its private and task memory, and task memory can be left out`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val researcher = app.agentNamed("Researcher").profile.id
        val viewModel = explorer(app, ScopeFilter.Agent(researcher))

        val titles = viewModel.uiState.value.rows.map { it.title }.toSet()
        assertEquals(
            setOf("Prefers summaries as short bullet points", "Lisbon trip, 12 to 16 March", "Cascais trains run every 20 minutes", "Compare Sintra and Cascais by train"),
            titles,
            "its private memory and task memory, not the space it writes to",
        )

        viewModel.setIncludeTaskMemory(false)
        assertFalse("Compare Sintra and Cascais by train" in viewModel.uiState.value.rows.map { it.title })
        assertTrue(viewModel.uiState.value.filters.isFiltered)

        // Choosing the selected scope again goes back to everything.
        viewModel.setScope(ScopeFilter.Agent(researcher))
        assertEquals(ScopeFilter.All, viewModel.uiState.value.filters.scope)
    }

    @Test
    fun `a space filter and a kind filter narrow the list`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = explorer(app)

        viewModel.setScope(ScopeFilter.Space(sample.teamSpace))
        val inTeam = viewModel.uiState.value.rows
        assertEquals(
            setOf("The owner is vegetarian", "No flights before 8:00", "Weekly review every Monday", "Prefers summaries as short bullet points"),
            inTeam.map { it.title }.toSet(),
        )
        assertTrue(inTeam.all { it.scope.spaceId == sample.teamSpace })

        viewModel.toggleKind(MemoryKind.PREFERENCE)
        assertEquals(setOf("No flights before 8:00", "Prefers summaries as short bullet points"), viewModel.uiState.value.rows.map { it.title }.toSet())

        viewModel.clearFilters()
        assertEquals(viewModel.uiState.value.totalCount, viewModel.uiState.value.rows.size)
    }

    @Test
    fun `searching ranks by relevance as the owner and never counts as using a memory`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val before = app.memories.list().associate { it.id to it.accessCount }
        val viewModel = explorer(app)

        viewModel.setQuery("vegetarian food")
        val rows = viewModel.uiState.value.rows
        assertEquals(sample.vegetarian, rows.first().id, "the memory about it ranks first")
        assertTrue(rows.all { it.relevance != null }, "a search shows how well each matched")
        assertTrue(rows.zipWithNext().all { (a, b) -> a.relevance!! >= b.relevance!! }, "best match first")
        assertEquals(before, app.memories.list().associate { it.id to it.accessCount }, "browsing does not raise recall counts")
    }

    @Test
    fun `a search honours the filters and says when nothing matches`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = explorer(app)

        viewModel.setScope(ScopeFilter.Space(sample.researchSpace))
        viewModel.setQuery("vegetarian")
        assertTrue(viewModel.uiState.value.rows.isEmpty(), "the vegetarian memory is in another space")
        assertFalse(viewModel.uiState.value.searchFailed)

        viewModel.setScope(ScopeFilter.All)
        assertEquals(sample.vegetarian, viewModel.uiState.value.rows.first().id)
    }

    @Test
    fun `an empty memory says so`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.graph.agentManager.ensureDefaults()
        val state = explorer(app).uiState.value
        assertEquals(0, state.totalCount)
        assertTrue(state.rows.isEmpty())
    }
}
