package com.aicontrolcenter.app.di

import com.aicontrolcenter.app.testing.MainDispatcherRule
import com.aicontrolcenter.app.testing.TestApp
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.orchestration.TeamTools
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Director's wiring: the orchestrator over the task ports, the team tools, and recovery at start-up. */
class AppGraphOrchestrationTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test
    fun `the team tools are registered once, beside the built-in ones`() = runTest(mainRule.dispatcher) {
        val graph = TestApp(backgroundScope).graph
        assertNotNull(graph.toolRegistry.byId(TeamTools.ASK_ID))
        assertNotNull(graph.toolRegistry.byId(TeamTools.DELEGATE_ID))
        assertNotNull(graph.toolRegistry.byFunctionName("clock_now"), "the built-in tools are still there")
        assertTrue(graph.orchestrator.running.value.isEmpty())
    }

    @Test
    fun `work that was running when the app stopped is marked interrupted at start-up, and waits for the owner`() = runTest(mainRule.dispatcher) {
        val app = TestApp(backgroundScope)
        val root = TaskId("tsk_before")
        val objective = TaskRecord(
            root, root, null, TaskKind.OBJECTIVE, "Compare note apps", "Compare note apps", requestedBy = TaskRequester.Owner,
            status = TaskStatus.RUNNING, createdAt = 1, updatedAt = 1, startedAt = 1,
        )
        val work = TaskRecord(
            TaskId("tsk_work"), root, root, TaskKind.WORK, "Research", "Research", requestedBy = TaskRequester.Owner,
            status = TaskStatus.RUNNING, createdAt = 2, updatedAt = 2, startedAt = 2,
        )
        app.tasks.upsertAll(listOf(objective, work))

        app.graph.start().join()
        app.graph.awaitStartup()

        assertEquals(TaskStatus.INTERRUPTED, app.tasks.get(work.id)?.status)
        val after = app.tasks.get(root)!!
        assertEquals(TaskStatus.INTERRUPTED, after.status)
        assertTrue(after.statusDetail.orEmpty().contains("Resume it to continue"), after.statusDetail)
        assertTrue(app.graph.orchestrator.running.value.isEmpty(), "nothing restarts without the owner (auto-resume is off)")
    }

    @Test
    fun `the foreground state follows the activity, which is when the work service may start`() = runTest(mainRule.dispatcher) {
        val graph = TestApp(backgroundScope).graph
        graph.awaitStartup() // not started: returns at once
        assertFalse(graph.isInForeground.value)
        graph.start().join()
        graph.onAppForegrounded()
        assertTrue(graph.isInForeground.value)
        graph.onAppBackgrounded()
        assertFalse(graph.isInForeground.value)
    }

    @Test
    fun `an objective asked for from the notification is handed to the shell once`() = runTest(mainRule.dispatcher) {
        val graph = TestApp(backgroundScope).graph
        graph.requestObjective(TaskId("tsk_1"))
        assertEquals(TaskId("tsk_1"), graph.objectiveToOpen.value)
        graph.objectiveOpened()
        assertEquals(null, graph.objectiveToOpen.value)
    }
}
