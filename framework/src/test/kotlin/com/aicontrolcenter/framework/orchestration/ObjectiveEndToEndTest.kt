package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.llm.Simulator
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObjectiveEndToEndTest {

    @Test
    fun `the starter team completes an objective offline with the simulator, with a review round, synthesis and saved knowledge`() = runTest {
        val team = TeamFixture(this)
        val world = team.world
        world.providerManager.ensureBuiltIns()
        world.agentManager.ensureDefaults()
        world.memorySeeder.ensureDefaults()
        for (agent in world.agents.getAll()) world.agentManager.assignModel(agent.id, Simulator.MODEL_ID)
        val names = world.agents.getAll().associate { it.id to it.name }
        val directorId = world.settings.get().directorAgentId!!

        val objective = team.start(
            "Research this subject, compare available solutions, develop a plan, and organize the results into a document",
            ObjectiveOptions(reviewResults = true, saveKnowledge = true),
        )
        assertEquals(TaskStatus.RUNNING, objective.status)
        assertEquals(setOf(objective.id), team.orchestrator.running.value)
        team.settle()

        val done = team.objective(objective.id)
        assertEquals(TaskStatus.COMPLETED, done.status, done.error ?: done.statusDetail)
        val document = done.result.orEmpty()
        assertTrue(document.contains("Simulated document"), document)
        assertTrue(team.orchestrator.running.value.isEmpty())
        assertTrue(team.orchestrator.assignments.value.isEmpty())
        assertTrue(world.monitor.states.value.isEmpty())

        // The plan: one task per part of the objective, given to the specialists whose skills fit.
        val work = team.work(objective.id)
        assertEquals(4, work.size)
        assertEquals(listOf("Researcher", "Analyst", "Analyst", "Writer"), work.map { names[it.assignee] })
        assertTrue(work.all { it.status == TaskStatus.COMPLETED })
        assertEquals(listOf(work[0].id), work[1].dependsOn)
        assertTrue(document.contains(work[3].title), "every result is in the document")

        // Reviews by the Reviewer; the second task was sent back once and approved after its revision.
        val reviews = team.reviews(objective.id)
        assertTrue(reviews.all { names[it.assignee] == "Reviewer" && it.status == TaskStatus.COMPLETED })
        assertEquals(4, reviews.size, "three reviewed tasks, one of them twice")
        assertEquals(1, work[1].revisionRound)
        assertEquals(listOf(ReviewDecision.REVISE, ReviewDecision.APPROVED), work[1].reviews.map { it.decision })
        assertTrue(work[1].result.orEmpty().contains("Revised to address the review"))
        assertTrue(work[0].reviews.isEmpty())

        val synthesis = team.tree(objective.id).single { it.kind == TaskKind.SYNTHESIS }
        assertEquals(TaskStatus.COMPLETED, synthesis.status)
        assertEquals(directorId, synthesis.assignee)

        // Messages: the objective to the Director, assignments, review traffic and completions, all stored.
        val messages = team.messages(objective.id)
        val first = messages.first()
        assertEquals(MessageType.ASSIGNMENT, first.type)
        assertEquals(Participant.Owner, first.from)
        assertEquals(Participant.Agent(directorId), first.to)
        for (type in listOf(MessageType.STATUS, MessageType.ASSIGNMENT, MessageType.REVIEW_REQUEST, MessageType.REVIEW_RESULT, MessageType.COMPLETION, MessageType.FINDINGS)) {
            assertTrue(messages.any { it.type == type }, "a $type message")
        }
        assertEquals(Participant.Owner, messages.last { it.type == MessageType.COMPLETION }.to)

        // Transcripts: every task's work is a stored conversation that starts with its brief.
        for (task in team.tree(objective.id)) {
            val conversation = task.conversationId ?: error("${task.title} has no transcript")
            val entries = world.conversations.getEntries(conversation)
            assertEquals(ChatRole.USER, entries.first().message.role)
            assertTrue(entries.first().message.text.startsWith("[aicc-protocol "), task.title)
            assertTrue(entries.any { it.message.role == ChatRole.ASSISTANT }, task.title)
        }

        // Knowledge: a summary by the Director in a shared space, with provenance, and task memory promoted.
        val summary = world.memory.repository.list().single { it.kind == MemoryKind.SUMMARY }
        assertTrue(summary.scope is MemoryScope.Shared)
        assertEquals(directorId, summary.provenance.agentId)
        assertEquals(objective.id, summary.provenance.taskId)
        assertEquals(Simulator.MODEL_ID, summary.provenance.modelId)
        assertEquals(work.map { it.id }.toSet(), world.memory.promotions.map { it.second }.toSet())

        // The log: task state changes and messages, without message bodies beyond a first line.
        assertTrue(world.events(ActivityType.TASK).any { it.title.startsWith("Objective completed") })
        assertTrue(world.events(ActivityType.MESSAGE).size >= messages.size)
        assertTrue(world.events(ActivityType.MESSAGE).none { it.detail.contains("Simulated review: the result needs") && it.detail.length > 200 })
    }
}
