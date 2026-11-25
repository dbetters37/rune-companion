package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.tasks.AgentMessage
import com.aicontrolcenter.core.tasks.MessageRepository
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review of wave 2a, INT-4: the briefs read the owner's notes, and finished and reviewed tasks the answers they got,
 * with queries by type (and task), never the objective's whole message list each time; an ordinary objective reads
 * its whole list about once, as before wave 2a.
 */
class MessageReadsTest {
    /** Counts whole-list reads and the rows they return, and the rows of reads by type. */
    private class Counting(private val inner: MessageRepository) : MessageRepository by inner {
        val lists = AtomicInteger()
        val rows = AtomicInteger()
        val typedRows = AtomicInteger()

        override suspend fun list(rootId: TaskId): List<AgentMessage> = inner.list(rootId).also {
            lists.incrementAndGet()
            rows.addAndGet(it.size)
        }

        override suspend fun listOfTypes(rootId: TaskId, types: Set<MessageType>, taskId: TaskId?): List<AgentMessage> =
            inner.listOfTypes(rootId, types, taskId).also { typedRows.addAndGet(it.size) }
    }

    private fun TeamFixture.orchestrator(messages: MessageRepository) = DefaultOrchestrator(
        tasks, messages, world.conversations, world.agents, world.directory, world.settings, world.runner,
        world.toolRegistry, world.monitor, world.activity, world.ids, world.clock, test.backgroundScope, world.memory,
        timeSource = world.time, operatorHooks = world.operatorHooks,
    )

    @Test
    fun `an objective of four tasks reads its whole message list once`() = runTest {
        val team = TeamFixture(this).setUp()
        val counting = Counting(team.messages)
        team.script.plan = { _, _ ->
            ScriptStep.Reply(
                planJson(
                    listOf(
                        task("a", "Find apps", skills = listOf("research")), task("b", "Compare apps", skills = listOf("analysis")),
                        task("c", "Price apps", skills = listOf("research")), task("d", "Write it up", skills = listOf("writing"), dependsOn = listOf("a", "b", "c")),
                    ),
                ),
            )
        }
        val objective = team.orchestrator(counting).startObjective("Research note apps", ObjectiveOptions())
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        println("Four tasks: ${counting.lists.get()} whole-list reads, ${counting.rows.get()} rows; ${counting.typedRows.get()} rows read by type")
        assertTrue(counting.lists.get() <= 1, "whole-list reads: ${counting.lists.get()}")
        assertEquals(0, counting.typedRows.get(), "no notes, no answers: nothing more is read")
    }

    @Test
    fun `an objective of twelve reviewed tasks reads its whole message list once, and the notes by type`() = runTest {
        val team = TeamFixture(this).setUp()
        val counting = Counting(team.messages)
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson((1..12).map { task("t$it", "Research part $it", skills = listOf("research"), needsReview = true) })) }
        val notes = OwnerNotes(team.tasks, team.messages, team.world.agents, team.world.activity, team.world.ids, team.world.clock)
        val objective = team.orchestrator(counting).startObjective("Research note apps", ObjectiveOptions())
        notes.addNote(objective.id, null, "Only free apps, please.")
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        println("Twelve reviewed tasks: ${counting.lists.get()} whole-list reads, ${counting.rows.get()} rows; ${counting.typedRows.get()} rows read by type")
        assertTrue(counting.lists.get() <= 1, "whole-list reads: ${counting.lists.get()}")
        // Each brief read the one note, not the objective's messages.
        assertTrue(counting.typedRows.get() in 1..60, "rows read by type: ${counting.typedRows.get()}")
    }
}
