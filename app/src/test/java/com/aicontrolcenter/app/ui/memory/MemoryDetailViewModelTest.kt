package com.aicontrolcenter.app.ui.memory

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.LinkRelation
import com.aicontrolcenter.core.memory.MemoryScope
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

class MemoryDetailViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.detail(app: TestApp, id: MemoryId) =
        MemoryDetailViewModel(id, app.graph.memoryService, app.graph.registryDirectory, app.conversations, ZoneOffset.UTC)
            .also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `provenance is told in plain words, with the chat and the model that answered`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val detail = assertNotNull(detail(app, sample.bulletPoints).uiState.value.memory)

        assertEquals("Prefers summaries as short bullet points", detail.record.title)
        assertTrue(detail.record.content.startsWith("When summarising sources"), "the full content is shown")
        val summary = detail.provenance.summary
        assertTrue(summary.startsWith("Saved by Researcher during a chat, using Llama 3.2 3B"), summary)
        val rows = detail.provenance.rows.associateBy { it.label }
        assertEquals("Researcher", rows.getValue("Created by").value)
        assertEquals(ProvenanceTarget.Agent(app.agentNamed("Researcher").profile.id), rows.getValue("Created by").target)
        assertEquals("Planning the Lisbon trip", rows.getValue("Chat").value)
        assertEquals(ProvenanceTarget.Conversation(sample.conversation), rows.getValue("Chat").target)
        assertTrue(rows.getValue("Model").value.startsWith("Llama 3.2 3B"))
        assertTrue(detail.record.accessCount > 0, "the Researcher recalled it in its work")
        assertNotNull(detail.lastUsed)
    }

    @Test
    fun `links are shown from this memory's side, in both directions`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val links = assertNotNull(detail(app, sample.lisbonTrip).uiState.value.memory).links

        val byOther = links.associateBy { it.other.id }
        assertEquals("Relates to", byOther.getValue(sample.ferryTimetable).phrase)
        assertTrue(byOther.getValue(sample.ferryTimetable).outgoing)
        assertEquals("Referenced by", byOther.getValue(sample.earlyFlights).phrase, "an incoming link reads backwards")
        assertEquals("No flights before 8:00", byOther.getValue(sample.earlyFlights).other.title)

        val contradiction = assertNotNull(detail(app, sample.groceriesUp).uiState.value.memory).links.single()
        assertEquals(LinkRelation.CONTRADICTS, contradiction.relation)
        assertEquals("Contradicted by", contradiction.phrase)
    }

    @Test
    fun `a shared copy keeps who first wrote it and tells when it was shared, from which memory`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val copy = assertNotNull(detail(app, sample.bulletPointsShared).uiState.value.memory)
        assertEquals(sample.teamSpace, (copy.record.scope as MemoryScope.Shared).spaceId)
        assertTrue(copy.provenance.summary.startsWith("Saved by Researcher during a chat"), "authorship is the original's: ${copy.provenance.summary}")
        val shared = copy.provenance.history.single()
        assertEquals("Shared here by you, as a copy of", shared.text)
        assertEquals(sample.bulletPoints, shared.related?.id)
        assertEquals("Prefers summaries as short bullet points", shared.related?.title)

        val original = assertNotNull(detail(app, sample.bulletPoints).uiState.value.memory)
        val shareLink = original.links.single { it.relation == LinkRelation.SHARED_COPY_OF }
        assertEquals("Shared as", shareLink.phrase)
        assertEquals(sample.bulletPointsShared, shareLink.other.id)
    }

    @Test
    fun `sharing into a space makes a linked copy there and leaves the original`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = detail(app, sample.ferryTimetable)
        assertEquals(setOf(sample.researchSpace, sample.teamSpace), viewModel.uiState.value.shareTargets.map { it.id }.toSet())

        viewModel.share(sample.researchSpace)

        assertEquals("Shared a copy into Research notes.", viewModel.uiState.value.message?.text)
        val copies = app.memories.list().filter { it.title == "Cascais trains run every 20 minutes" }
        assertEquals(2, copies.size)
        assertTrue(copies.any { it.scope == MemoryScope.Shared(sample.researchSpace) })
        assertTrue(copies.any { it.id == sample.ferryTimetable && it.scope is MemoryScope.Agent }, "the original stays private")
        assertTrue(viewModel.uiState.value.memory!!.links.any { it.relation == LinkRelation.SHARED_COPY_OF && !it.outgoing })
        assertFalse(viewModel.uiState.value.isBusy)
    }

    @Test
    fun `linking and unlinking change the memory's links at once`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = detail(app, sample.vegetarian)
        assertTrue(viewModel.uiState.value.memory!!.links.isEmpty())
        assertTrue(viewModel.uiState.value.linkCandidates.none { it.id == sample.vegetarian }, "a memory cannot be linked to itself")

        viewModel.link(sample.lisbonTrip, LinkRelation.SUPPORTS)
        val link = viewModel.uiState.value.memory!!.links.single()
        assertEquals("Supports", link.phrase)
        assertEquals(sample.lisbonTrip, link.other.id)
        assertEquals("Linked.", viewModel.uiState.value.message?.text)

        viewModel.unlink(link.id)
        assertTrue(viewModel.uiState.value.memory!!.links.isEmpty())
        assertTrue(app.memories.linksOf(sample.vegetarian).isEmpty())
    }

    @Test
    fun `deleting forgets the memory and its links and closes the screen`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = detail(app, sample.groceriesFlat)

        viewModel.delete()

        assertTrue(viewModel.uiState.value.deleted)
        assertNull(viewModel.uiState.value.memory)
        assertNull(app.memories.get(sample.groceriesFlat))
        assertTrue(app.memories.linksOf(sample.groceriesUp).isEmpty(), "the contradiction went with it")
    }

    @Test
    fun `a memory that does not exist shows nothing to act on`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val state = detail(app, MemoryId("mem_missing")).uiState.value
        assertFalse(state.isLoading)
        assertNull(state.memory)
    }
}
