package com.aicontrolcenter.app.ui.memory.graph

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.app.ui.memory.ScopeKind
import com.aicontrolcenter.core.activity.ActivityFilter
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryGraphSnapshot
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.memory.MemoryGraphJson
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

class MemoryGraphViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.graphOf(app: TestApp) = MemoryGraphViewModel(
        app.graph.memoryService, app.graph.registryDirectory, app.settings, app.graph.activityRecorder, app.clock,
        computation = mainRule.dispatcher, zone = ZoneOffset.UTC,
    ).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `the graph draws every memory, laid out inside the view, with its links and scopes`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val state = graphOf(app).uiState.value

        assertFalse(state.isLoading)
        assertFalse(state.failed)
        val render = state.render
        assertEquals(app.memories.list().size, render.nodes.size)
        assertTrue(render.nodes.all { it.x in 0f..1f && it.y in 0f..1f }, "positions are in the unit square")
        val byMemory = render.nodes.associateBy { it.memoryId }
        assertEquals(ScopeKind.SHARED, byMemory.getValue(sample.vegetarian).shape)
        assertEquals("Researcher", byMemory.getValue(sample.lisbonTrip).scope.label)
        assertTrue(byMemory.getValue(sample.vegetarian).radiusDp > byMemory.getValue(sample.ferryTimetable).radiusDp, "more important is bigger")
        assertEquals(
            setOf(LinkRelation.RELATES_TO, LinkRelation.REFERENCES, LinkRelation.CONTRADICTS, LinkRelation.SHARED_COPY_OF),
            render.relations.toSet(),
        )
        val contradiction = render.edges.single { it.relation == LinkRelation.CONTRADICTS }
        assertEquals(setOf(sample.groceriesFlat, sample.groceriesUp), setOf(render.nodes[contradiction.source].memoryId, render.nodes[contradiction.target].memoryId))

        val again = MemoryGraphViewModel.layoutOf(app.graph.memoryService.graph(com.aicontrolcenter.core.memory.MemoryActor.Owner, state.filters.request(MemoryGraphViewModel.MAX_VIEW_NODES)))
        assertEquals(again.getValue(byMemory.getValue(sample.vegetarian).id).x.toFloat(), byMemory.getValue(sample.vegetarian).x, "the layout is deterministic")
    }

    @Test
    fun `filters narrow the graph to chosen agents and spaces`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = graphOf(app)
        val researcher = app.agentNamed("Researcher").profile.id

        viewModel.toggleAgent(researcher)
        val onlyResearcher = viewModel.uiState.value.render.nodes
        assertTrue(onlyResearcher.isNotEmpty())
        assertTrue(onlyResearcher.all { it.scope.agentId == researcher }, "only the Researcher's private and task memory")
        assertTrue(onlyResearcher.any { it.shape == ScopeKind.TASK })

        viewModel.setIncludeTaskMemory(false)
        assertTrue(viewModel.uiState.value.render.nodes.none { it.shape == ScopeKind.TASK })

        viewModel.toggleSpace(sample.teamSpace)
        val withTeam = viewModel.uiState.value.render.nodes
        assertTrue(withTeam.any { it.scope.spaceId == sample.teamSpace })
        assertTrue(withTeam.none { it.scope.spaceId == sample.researchSpace })
        assertTrue(viewModel.uiState.value.render.edges.any { it.relation == LinkRelation.SHARED_COPY_OF }, "the share link joins the two clusters")

        viewModel.showEverything()
        assertTrue(viewModel.uiState.value.filters.showsEverything)
    }

    @Test
    fun `tapping a node selects it for the card, and tapping it again clears it`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = graphOf(app)
        val node = viewModel.uiState.value.render.nodes.single { it.memoryId == sample.lisbonTrip }

        viewModel.selectNode(node.id)
        val selected = assertNotNull(viewModel.uiState.value.selected)
        assertEquals("Lisbon trip, 12 to 16 March", selected.title)
        assertEquals(sample.lisbonTrip, selected.memoryId)
        assertEquals(2, selected.linkCount)

        viewModel.selectNode(node.id)
        assertNull(viewModel.uiState.value.selected)
    }

    @Test
    fun `export writes the neutral graph format of the view, and the hand-off is logged without content`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = graphOf(app)

        viewModel.exportGraph()
        val export = assertNotNull(viewModel.uiState.value.pendingExport)
        assertTrue(export.fileName.matches(Regex("memory-graph-\\d{8}-\\d{4}\\.aicc-graph\\.json")), export.fileName)
        val decoded = MemoryGraphJson.decode(export.json)
        assertEquals(MemoryGraphSnapshot.SCHEMA, decoded.schema)
        assertEquals(app.memories.list().map { "mem:${it.id.value}" }.toSet(), decoded.nodes.map { it.id }.toSet())
        assertEquals(viewModel.uiState.value.render.edges.size, decoded.edges.size)
        val vegetarian = decoded.nodes.single { it.id == "mem:${sample.vegetarian.value}" }
        assertTrue(vegetarian.content.startsWith("No meat or fish"), "the export carries the full text, as the note says")
        assertTrue(decoded.clusters.any { it.kind == "space" && it.label == "Research notes" })

        viewModel.exportHandled(failure = null)
        assertNull(viewModel.uiState.value.pendingExport)
        val event = app.activityLog.recent(ActivityFilter(egressOnly = true)).first()
        assertEquals(ActivityType.DATA_EGRESS, event.type)
        assertEquals("Memory graph exported", event.title)
        val egress = assertNotNull(event.egress)
        assertEquals(MemoryGraphViewModel.SHARE_SHEET_DESTINATION, egress.destinationHost)
        assertEquals(Locality.CLOUD, egress.locality)
        assertEquals(export.json.toByteArray().size.toLong(), egress.approxBytes)
        val memoryText = app.memories.list().map { it.content }
        assertTrue(memoryText.none { it in event.detail || it in event.title }, "the log never carries memory content")
    }

    @Test
    fun `export of a filtered view only carries that view`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = graphOf(app)
        viewModel.toggleSpace(sample.teamSpace)

        viewModel.exportGraph()
        val decoded = MemoryGraphJson.decode(assertNotNull(viewModel.uiState.value.pendingExport).json)
        val inTeam = app.memories.list().filter { it.scope == MemoryScope.Shared(sample.teamSpace) }
        assertEquals(inTeam.map { "mem:${it.id.value}" }.toSet(), decoded.nodes.map { it.id }.toSet())
    }

    /** Rule 21 (D37): the owner's own export through the share sheet is allowed in local-only mode, with the line about the receiving app. */
    @Test
    fun `local-only mode allows the owner's own export and says the chosen app may send it off the phone`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        app.settings.update { it.copy(localOnlyMode = true) }
        val viewModel = graphOf(app)
        assertEquals("The app you choose may send it off the phone.", viewModel.uiState.value.exportNote)

        viewModel.exportGraph()

        val export = assertNotNull(viewModel.uiState.value.pendingExport)
        assertTrue(export.localOnly)
        assertTrue(viewModel.uiState.value.message!!.text.endsWith("The app you choose may send it off the phone."))
        viewModel.exportHandled(failure = null)
        val event = app.activityLog.recent(ActivityFilter(egressOnly = true)).single { it.title == "Memory graph exported" }
        assertTrue("Local-only mode was on; this was your own export." in event.detail, event.detail)
        assertTrue(app.activityLog.recent().none { it.title == "Memory graph export blocked" })
    }

    @Test
    fun `a failed share is reported and not logged as an export`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val viewModel = graphOf(app)
        viewModel.exportGraph()

        viewModel.exportHandled(failure = "No app on this phone can receive the file.")

        assertNull(viewModel.uiState.value.pendingExport)
        assertEquals("No app on this phone can receive the file.", viewModel.uiState.value.message?.text)
        assertTrue(app.activityLog.recent().none { it.title == "Memory graph exported" })
    }
}
