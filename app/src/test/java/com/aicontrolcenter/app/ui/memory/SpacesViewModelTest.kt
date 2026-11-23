package com.aicontrolcenter.app.ui.memory

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.framework.memory.MemorySeeder
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

class SpacesViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.spaces(app: TestApp) =
        SpacesViewModel(app.graph.memoryService, app.graph.registryDirectory).also { it.uiState.activeIn(backgroundScope) }

    private fun TestScope.space(app: TestApp, id: SpaceId) =
        SpaceDetailViewModel(id, app.graph.memoryService, app.graph.registryDirectory, app.graph.agentManager, app.clock, ZoneOffset.UTC)
            .also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `spaces are listed with their memory counts and who may read and write them`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()

        val rows = spaces(app).uiState.value.spaces
        assertEquals(listOf("Research notes", MemorySeeder.TEAM_SPACE_NAME), rows.map { it.name })
        val research = rows[0]
        assertEquals(1, research.memoryCount)
        assertEquals(listOf("Researcher", "Analyst").sorted(), research.readers.sorted())
        assertEquals(listOf("Researcher"), research.writers)
        val team = rows[1]
        assertEquals(4, team.memoryCount)
        assertEquals(6, team.readers.size, "the whole starter team reads it")
        assertEquals(listOf(app.director().profile.name), team.writers, "only the Director adds to it")
    }

    @Test
    fun `creating a space reports it once so it can be opened, and a duplicate name is refused`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val viewModel = spaces(app)

        viewModel.create("  Household  ", "Bills, repairs and appointments.")
        val created = assertNotNull(viewModel.uiState.value.createdSpaceId)
        val space = assertNotNull(app.memories.getSpace(created))
        assertEquals("Household", space.name)
        assertEquals("Bills, repairs and appointments.", space.description)
        assertTrue(viewModel.uiState.value.spaces.any { it.name == "Household" && it.readers.isEmpty() }, "nobody may use a new space")
        viewModel.createdHandled()
        assertNull(viewModel.uiState.value.createdSpaceId)

        viewModel.create("household", "")
        assertEquals("A knowledge space named \"household\" already exists.", viewModel.uiState.value.createError)
        assertNull(viewModel.uiState.value.createdSpaceId)
        viewModel.clearCreateError()
        assertNull(viewModel.uiState.value.createError)

        viewModel.create("   ", "")
        assertEquals("Give the space a name.", viewModel.uiState.value.createError)
    }

    @Test
    fun `read and write are granted per agent through its memory policy`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val writer = app.agentNamed("Writer").profile
        val viewModel = space(app, sample.researchSpace)
        val before = viewModel.uiState.value.space!!.access.single { it.agentId == writer.id }
        assertFalse(before.canRead)
        assertFalse(before.canWrite)

        viewModel.setCanRead(writer.id, true)
        viewModel.setCanWrite(writer.id, true)

        val stored = assertNotNull(app.agents.get(writer.id)).memory
        assertTrue(sample.researchSpace in stored.readableSpaces)
        assertTrue(sample.researchSpace in stored.writableSpaces)
        val after = viewModel.uiState.value.space!!.access.single { it.agentId == writer.id }
        assertTrue(after.canRead && after.canWrite)
        assertEquals(3, viewModel.uiState.value.space!!.readerCount)
        assertTrue(
            app.activityLog.recent().any { it.type == ActivityType.REGISTRY && it.title == "Agent updated" && it.agentId == writer.id },
            "an ordinary, logged agent update",
        )

        viewModel.setCanRead(writer.id, false)
        val revoked = assertNotNull(app.agents.get(writer.id)).memory
        assertFalse(sample.researchSpace in revoked.readableSpaces)
        assertTrue(sample.researchSpace in revoked.writableSpaces, "write is a separate grant")
    }

    @Test
    fun `renaming saves the new name and refuses one already in use`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = space(app, sample.researchSpace)

        viewModel.rename("Team knowledge", "")
        assertEquals("A knowledge space named \"Team knowledge\" already exists.", viewModel.uiState.value.renameError)

        viewModel.rename("Travel research", "Trips and how to get there.")
        assertTrue(viewModel.uiState.value.renamed)
        assertEquals("Travel research", viewModel.uiState.value.space?.space?.name)
        assertEquals("Trips and how to get there.", app.memories.getSpace(sample.researchSpace)?.description)
        viewModel.renameHandled()
        assertFalse(viewModel.uiState.value.renamed)
    }

    @Test
    fun `deleting a space deletes its memories and takes it out of every policy`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val viewModel = space(app, sample.researchSpace)
        assertEquals(1, viewModel.uiState.value.space!!.memoryCount)

        viewModel.delete()

        assertTrue(viewModel.uiState.value.deleted)
        assertNull(viewModel.uiState.value.space)
        assertNull(app.memories.getSpace(sample.researchSpace))
        assertTrue(app.memories.list().none { it.scope == MemoryScope.Shared(sample.researchSpace) })
        assertTrue(app.agents.getAll().none { sample.researchSpace in it.memory.readableSpaces || sample.researchSpace in it.memory.writableSpaces })
        assertNotNull(app.memories.get(sample.ferryTimetable), "private memories are untouched")
    }
}
