package com.aicontrolcenter.app.ui.memory

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.populateWithMemories
import com.aicontrolcenter.app.ui.agents.AgentDetailViewModel
import com.aicontrolcenter.app.ui.agents.AgentEditorViewModel
import com.aicontrolcenter.app.ui.dashboard.DashboardViewModel
import com.aicontrolcenter.app.ui.dashboard.MemorySummaryUi
import com.aicontrolcenter.app.ui.settings.MemoryInfoUi
import com.aicontrolcenter.app.ui.settings.SettingsViewModel
import com.aicontrolcenter.framework.memory.MemorySeeder
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Memory as the agent screens, the dashboard and settings show it. */
class MemoryAcrossScreensTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `the agent editor offers existing spaces for reading and writing, and saves the choice`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val sample = app.populateWithMemories()
        val writer = app.agentNamed("Writer").profile
        val editor = AgentEditorViewModel(
            app.graph.agentManager, app.graph.registryDirectory, writer.id, null,
            spaces = app.graph.memoryService.observeSpaces(),
        )
        assertEquals(listOf("Research notes", MemorySeeder.TEAM_SPACE_NAME), editor.uiState.value.spaces.map { it.name })

        editor.setSpaceReadable(sample.researchSpace, true)
        editor.setSpaceWritable(sample.researchSpace, true)
        editor.setSpaceReadable(sample.teamSpace, false)
        assertTrue(editor.uiState.value.isDirty)
        editor.save()

        val stored = assertNotNull(app.agents.get(writer.id)).memory
        assertEquals(setOf(sample.researchSpace), stored.readableSpaces)
        assertEquals(setOf(sample.researchSpace), stored.writableSpaces)
    }

    @Test
    fun `the agent detail counts its private memories and names its spaces`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val researcher = app.agentNamed("Researcher").profile
        val detail = AgentDetailViewModel(researcher.id, app.graph.registryDirectory, app.graph.agentManager, memory = app.graph.memoryService)
            .also { it.uiState.activeIn(backgroundScope) }

        val agent = assertNotNull(detail.uiState.value.agent)
        assertEquals(3, agent.privateMemoryCount, "private memory only, not task memory or what it wrote to spaces")
        assertEquals(listOf("Research notes", MemorySeeder.TEAM_SPACE_NAME), agent.readableSpaces)
        assertEquals(listOf("Research notes"), agent.writableSpaces)
    }

    @Test
    fun `the dashboard counts memories and spaces`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.populateWithMemories()
        val dashboard = DashboardViewModel(
            app.graph.registryDirectory, app.activityLog, app.graph.aiAppRegistry, app.clock, memory = app.graph.memoryService,
        ).also { it.uiState.activeIn(backgroundScope) }

        assertEquals(MemorySummaryUi(memoryCount = app.memories.list().size, spaceCount = 2), dashboard.uiState.value.memory)
    }

    @Test
    fun `settings state where memory lives and how long short-term memory lasts`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val info = MemoryInfoUi.of(app.graph.memoryConfig.shortTermTtlMs, app.graph.embedder.locality)
        assertEquals(3, info.shortTermDays)
        assertTrue(info.searchOnDevice, "the shipped embedder runs offline")
        val settings = SettingsViewModel(app.settings, app.activityLog, app.graph.activityRecorder, app.deviceInspector, "0.3.0", memoryInfo = info)
            .also { it.uiState.activeIn(backgroundScope) }
        assertEquals(info, settings.uiState.value.memory)
        assertFalse(settings.uiState.value.isLoading)
    }
}
