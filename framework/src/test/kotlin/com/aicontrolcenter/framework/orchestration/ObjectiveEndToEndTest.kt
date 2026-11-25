package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.ReviewDecision
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.llm.Simulator
import com.aicontrolcenter.framework.orchestration.TeamFixture.Companion.MINUTE_MS
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
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

    @Test
    fun `a team whose provider answers 503 and 429 and then stops answering waits each time, and finishes when it answers again`() = runTest {
        // R4 part 1 + D28: provider trouble is waited out, never a failure; no attempt is used.
        val team = TeamFixture(this).setUp()
        // The Analyst thinks with the same scripted model, served by a provider of its own.
        val analystModel = team.world.serve(team.script.client, com.aicontrolcenter.core.registry.ModelCapabilities(toolCalling = true, jsonOutput = true))
        team.world.agents.upsert(team.analyst.copy(modelId = analystModel.id))
        val provider = team.world.providers.get(analystModel.providerId)!!
        var planCalls = 0
        team.script.plan = { _, _ ->
            if (planCalls++ < 3) ScriptStep.Fail(LlmException.RateLimited("Too many requests", retryAfterMs = 60_000))
            else ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research")), task("b", "Compare apps", listOf("analysis"), dependsOn = listOf("a")))))
        }
        var workCalls = 0
        team.script.work = { s ->
            when {
                TeamScript.titleOf(s.brief) == "Find apps" && workCalls++ < 6 -> ScriptStep.Fail(LlmException.Server("Service unavailable", 503))
                TeamScript.titleOf(s.brief) == "Find apps" -> {
                    // Done; then the Analyst's provider stops answering altogether before the next task.
                    team.world.providers.upsert(team.world.providers.get(provider.id)!!.copy(status = ConnectionStatus.OFFLINE))
                    ScriptStep.Reply("Apps found")
                }
                else -> ScriptStep.Reply("Apps compared")
            }
        }
        val objective = team.start("Research note apps")
        // Planning waits 1, then 2, then 2 minutes; "Find apps" 30 seconds, then 2 minutes: about 8 minutes in all.
        repeat(10) { team.pass(MINUTE_MS) }
        val compare = team.byTitle(objective.id, "Compare apps")
        assertEquals(TaskStatus.QUEUED, compare.status, compare.statusDetail)
        assertTrue(compare.statusDetail!!.startsWith("Waiting: Test server on this phone is not answering"), compare.statusDetail)
        assertTrue(team.rechecks.calls.isNotEmpty(), "the provider was asked to be checked again")

        // It answers again: the next re-check says so, and the work goes on to the end.
        team.rechecks.answer = { id -> team.world.providers.upsert(team.world.providers.get(id)!!.copy(status = ConnectionStatus.ONLINE)); ConnectionStatus.ONLINE }
        repeat(3) { team.pass(MINUTE_MS) }
        team.settle()
        val done = team.objective(objective.id)
        assertEquals(TaskStatus.COMPLETED, done.status, done.statusDetail)
        assertTrue(team.work(objective.id).all { it.status == TaskStatus.COMPLETED && it.attempt == 0 }, "waits use no attempt")
        assertEquals(0, done.attempt)
        assertTrue(team.world.log.all.any { it.title.startsWith("Planning waits for its provider") })
        assertTrue(team.world.log.all.count { it.title.startsWith("Task waits for its provider") } >= 2)
    }
}
