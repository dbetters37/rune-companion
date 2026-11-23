package com.aicontrolcenter.app.ui.agents

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.app.testing.activeIn
import com.aicontrolcenter.app.testing.letTheTeamWork
import com.aicontrolcenter.app.testing.teamOnSimulator
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.tasks.TaskKind
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentRecentTasksTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun TestScope.detail(app: TestApp, id: AgentId) = AgentDetailViewModel(
        id, app.graph.registryDirectory, app.graph.agentManager, tasks = app.graph.tasks, clock = app.clock, zone = ZoneOffset.UTC,
    ).also { it.uiState.activeIn(backgroundScope) }

    @Test
    fun `an agent's page lists the tasks it was given, with their objective, and the Director's its objectives`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        app.teamOnSimulator()
        val researcher = detail(app, app.agentNamed("Researcher").profile.id)
        assertEquals(emptyList(), researcher.uiState.value.agent!!.recentTasks)

        val objective = app.graph.orchestrator.startObjective("Research note apps, compare them, and write a summary")
        letTheTeamWork()

        val tasks = researcher.uiState.value.agent!!.recentTasks
        assertTrue(tasks.isNotEmpty())
        assertTrue(tasks.all { it.kind == TaskKind.WORK && it.objectiveTitle == objective.title && it.status.text == "Completed" }, tasks.toString())

        val director = detail(app, app.director().profile.id).uiState.value.agent!!.recentTasks
        assertTrue(director.any { it.kind == TaskKind.OBJECTIVE && it.id == objective.id && it.objectiveTitle == null })
        assertTrue(director.any { it.kind == TaskKind.SYNTHESIS })
    }
}
