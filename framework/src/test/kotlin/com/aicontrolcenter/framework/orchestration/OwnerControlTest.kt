package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.llm.ChatRole
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.orchestration.TeamFixture.Companion.MINUTE_MS
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient.Companion.call
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerControlTest {

    @Test
    fun `pausing stops the running work and nothing new starts, and resuming restarts it from its brief`() = runTest {
        val team = TeamFixture(this).setUp()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research")), task("b", "Compare apps", listOf("comparison"), dependsOn = listOf("a"))))) }
        var hold = true
        team.script.work = { s -> if (hold) awaitCancellation() else ScriptStep.Reply("Done: ${TeamScript.titleOf(s.brief)}") }
        val objective = team.start()
        team.settle(MINUTE_MS)
        assertEquals(TaskStatus.RUNNING, team.work(objective.id)[0].status)
        assertEquals(mapOf(team.researcher.id to team.work(objective.id)[0].id), team.orchestrator.assignments.value)
        assertEquals("Task: Find apps · Waiting for the model", team.world.monitor.states.value[team.researcher.id]?.detail)

        team.orchestrator.pause(objective.id)
        assertEquals(TaskStatus.PAUSED, team.objective(objective.id).status)
        assertEquals(listOf(TaskStatus.PAUSED, TaskStatus.QUEUED), team.work(objective.id).map { it.status })
        assertTrue(team.orchestrator.running.value.isEmpty())
        assertTrue(team.orchestrator.assignments.value.isEmpty())
        assertTrue(team.world.monitor.states.value.isEmpty())
        val seen = team.script.seen.size
        team.settle()
        assertEquals(seen, team.script.seen.size, "nothing runs while paused")

        hold = false
        team.orchestrator.resume(objective.id)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        val find = team.work(objective.id)[0]
        val transcript = team.world.conversations.getEntries(find.conversationId!!)
        assertEquals(2, transcript.count { it.message.role == ChatRole.USER && it.message.text.startsWith(OrchestrationProtocol.TASK) })
        assertTrue(transcript.any { it.error == "Restarted from its brief." })
        val statuses = team.messagesOf(objective.id, MessageType.STATUS).filter { it.from == Participant.Owner }.map { it.body }
        assertEquals(listOf("Paused by the owner.", "Resumed."), statuses)
    }

    @Test
    fun `the app pausing with its own reason says that reason, not the owner's pause, and resumes like any pause`() = runTest {
        // Wave 3 (WP8; WP2's storage pause): "Paused: the phone is almost full" is the objective's own text and the
        // team's message, from the system, and the log has no owner action for it.
        val team = TeamFixture(this).setUp()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research"))))) }
        var hold = true
        team.script.work = { s -> if (hold) awaitCancellation() else ScriptStep.Reply("Done: ${TeamScript.titleOf(s.brief)}") }
        val objective = team.start()
        team.settle(MINUTE_MS)
        val reason = "Paused: the phone is almost full. Free some room, then resume it."
        team.orchestrator.pause(objective.id, reason)
        val paused = team.objective(objective.id)
        assertEquals(TaskStatus.PAUSED, paused.status)
        assertEquals(reason, paused.statusDetail)
        assertEquals(reason, team.work(objective.id)[0].statusDetail)
        assertTrue(team.orchestrator.running.value.isEmpty())
        val statuses = team.messagesOf(objective.id, MessageType.STATUS)
        assertEquals(listOf(reason), statuses.filter { it.from == Participant.System && it.body == reason }.map { it.body })
        assertTrue(statuses.none { it.body == "Paused by the owner." })
        val logged = team.world.log.observe(limit = 200).first()
        assertTrue(logged.none { it.type == com.aicontrolcenter.core.activity.ActivityType.USER_ACTION && it.title == "Objective paused" }, "not the owner's action")
        assertTrue(logged.any { it.title.startsWith("Objective paused") && it.type == com.aicontrolcenter.core.activity.ActivityType.TASK }, "the change itself is still logged")
        hold = false
        team.orchestrator.resume(objective.id)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
    }

    @Test
    fun `pausing during a delegation pauses both, and on resume the delegating task starts over`() = runTest {
        val team = TeamFixture(this).setUp()
        team.world.agents.upsert(team.researcher.copy(delegation = DelegationPolicy(canDelegate = true, maxDepth = 2)))
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research"))))) }
        var hold = true
        team.script.work = { s ->
            when {
                s.agent == "Researcher" && !team.script.afterTools(s) ->
                    ScriptStep.Calls(listOf(call("d1", "team_delegate", buildJsonObject { put("agent", "Writer"); put("instructions", "Draft it") })))
                s.agent == "Writer" && hold -> awaitCancellation()
                else -> ScriptStep.Reply("${s.agent} done")
            }
        }
        val objective = team.start()
        team.settle(MINUTE_MS)
        val (parent, child) = team.tree(objective.id).filter { it.kind == TaskKind.WORK }.sortedBy { it.createdAt }
        assertEquals(TaskStatus.WAITING_FOR_AGENT, parent.status)
        assertEquals(TaskStatus.RUNNING, child.status)
        team.orchestrator.pause(objective.id)
        assertEquals(listOf(TaskStatus.PAUSED, TaskStatus.PAUSED), listOf(team.tasks.get(parent.id)!!.status, team.tasks.get(child.id)!!.status))
        hold = false
        team.orchestrator.resume(objective.id)
        team.settle()
        assertEquals(TaskStatus.CANCELLED, team.tasks.get(child.id)!!.status)
        assertEquals(TaskStatus.COMPLETED, team.tasks.get(parent.id)!!.status)
        assertEquals(3, team.tree(objective.id).count { it.kind == TaskKind.WORK }, "the restarted task delegated again")
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
    }

    @Test
    fun `cancelling stops everything unfinished, keeps what was produced and writes no document`() = runTest {
        val team = TeamFixture(this).setUp()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research")), task("b", "Compare apps", listOf("comparison"))))) }
        team.script.work = { s -> if (s.agent == "Analyst") awaitCancellation() else ScriptStep.Reply("Apps found") }
        val objective = team.start()
        team.settle(MINUTE_MS)
        team.orchestrator.cancel(objective.id)
        team.settle()
        val cancelled = team.objective(objective.id)
        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.result)
        val (find, compare) = team.work(objective.id)
        assertEquals(TaskStatus.COMPLETED, find.status)
        assertEquals("Apps found", find.result)
        assertEquals(TaskStatus.CANCELLED, compare.status)
        assertEquals(TaskStatus.CANCELLED, team.tree(objective.id).single { it.kind == TaskKind.SYNTHESIS }.status)
        assertTrue(team.script.seenBy(OrchestrationProtocol.SYNTHESIS).isEmpty())
        assertEquals(Participant.Owner, team.messagesOf(objective.id, MessageType.CANCEL).single().from)
        assertTrue(team.orchestrator.running.value.isEmpty())
    }

    @Test
    fun `cancelling one task lets the Director finish without it, and its dependents are cancelled`() = runTest {
        val team = TeamFixture(this).setUp()
        team.script.plan = { _, _ ->
            ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research")), task("b", "Compare apps", listOf("comparison")), task("c", "Write report", listOf("writing"), dependsOn = listOf("b")))))
        }
        team.script.work = { s -> if (s.agent == "Analyst") awaitCancellation() else ScriptStep.Reply("Done") }
        val objective = team.start()
        team.settle(MINUTE_MS)
        val compare = team.byTitle(objective.id, "Compare apps")
        team.orchestrator.cancelTask(compare.id)
        team.settle()
        assertEquals(TaskStatus.CANCELLED, team.tasks.get(compare.id)!!.status)
        assertTrue(team.byTitle(objective.id, "Write report").error!!.startsWith(ObjectiveDriver.DEPENDENCY_PREFIX))
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertTrue(team.script.seenBy(OrchestrationProtocol.SYNTHESIS).single().brief.contains("Compare apps — was cancelled: Cancelled by the owner."))
    }

    @Test
    fun `running a failed task again re-queues its dependents and writes the final result again`() = runTest {
        val team = TeamFixture(this) { copy(maxTaskAttempts = 1) }.setUp()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research")), task("b", "Compare apps", listOf("comparison"), dependsOn = listOf("a"))))) }
        var broken = true
        team.script.work = { if (broken) ScriptStep.Reply(" ") else ScriptStep.Reply("Fine") }
        val objective = team.start()
        team.settle()
        assertEquals(TaskStatus.FAILED, team.objective(objective.id).status)
        val find = team.byTitle(objective.id, "Find apps")
        assertFailsWith<IllegalStateException> { team.orchestrator.retryTask(team.reviews(objective.id).firstOrNull()?.id ?: team.tree(objective.id).single { it.kind == TaskKind.SYNTHESIS }.id) }
        broken = false
        team.orchestrator.retryTask(find.id)
        assertEquals(TaskStatus.RUNNING, team.objective(objective.id).status)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertEquals(listOf(1, 0), team.work(objective.id).map { it.attempt })
        assertTrue(team.work(objective.id).all { it.status == TaskStatus.COMPLETED })
        assertEquals(2, team.script.seenBy(OrchestrationProtocol.SYNTHESIS).size)
        assertFailsWith<IllegalStateException> { team.orchestrator.retryTask(find.id) }
    }

    @Test
    fun `an objective is deleted with its tasks, messages and transcripts, but only once it has ended`() = runTest {
        val team = TeamFixture(this).setUp()
        team.script.work = { awaitCancellation() }
        val objective = team.start()
        team.settle(MINUTE_MS)
        assertFailsWith<IllegalStateException> { team.orchestrator.delete(objective.id) }
        team.orchestrator.cancel(objective.id)
        val conversations = team.tree(objective.id).mapNotNull { it.conversationId }
        assertTrue(conversations.size >= 2)
        team.orchestrator.delete(objective.id)
        assertTrue(team.tree(objective.id).isEmpty())
        assertTrue(team.messages(objective.id).isEmpty())
        assertTrue(conversations.all { team.world.conversations.getConversation(it) == null })
    }

    @Test
    fun `a task that runs past its time limit times out`() = runTest {
        val team = TeamFixture(this) { copy(taskTimeoutMinutes = 1, maxTaskAttempts = 1) }.setUp()
        team.script.work = {
            delay(5 * MINUTE_MS)
            ScriptStep.Reply("too late")
        }
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.TIMED_OUT, work.status)
        assertTrue(work.error!!.contains("longer than 1 minutes"), work.error)
        assertEquals(TaskStatus.FAILED, team.objective(objective.id).status)
    }

    @Test
    fun `the objective's time limit stops unfinished work and the final result is written from what exists`() = runTest {
        val team = TeamFixture(this) { copy(objectiveTimeoutMinutes = 3, taskTimeoutMinutes = 60) }.setUp()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", listOf("research")), task("b", "Compare apps", listOf("comparison"))))) }
        team.script.work = { s -> if (s.agent == "Analyst") awaitCancellation() else ScriptStep.Reply("Apps found") }
        val objective = team.start()
        team.settle(2 * MINUTE_MS)
        assertEquals(TaskStatus.RUNNING, team.byTitle(objective.id, "Compare apps").status)
        team.settle()
        val compare = team.byTitle(objective.id, "Compare apps")
        assertEquals(TaskStatus.CANCELLED, compare.status)
        assertTrue(compare.error!!.contains("objective reached its time limit of 3 minutes"))
        assertTrue(team.limitMessages(objective.id).single().body.contains("time limit of 3 minutes"))
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertTrue(team.script.seenBy(OrchestrationProtocol.SYNTHESIS).single().brief.contains("Apps found"))
    }

    @Test
    fun `after a crash, interrupted work waits for the owner unless auto-resume is on`() = runTest {
        for (autoResume in listOf(false, true)) {
            val team = TeamFixture(this) { copy(autoResumeInterruptedTasks = autoResume) }.setUp()
            var hold = true
            team.script.work = { if (hold) awaitCancellation() else ScriptStep.Reply("Done") }
            val app = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val before = team.newOrchestrator(app)
            val objective = before.startObjective("Research note apps")
            team.settle(MINUTE_MS)
            app.cancel() // the app is killed: nothing gets to record that the work stopped
            team.settle(MINUTE_MS)
            assertEquals(TaskStatus.RUNNING, team.work(objective.id).single().status)

            hold = false
            val after = team.newOrchestrator()
            assertEquals(listOf(objective.id), after.recoverInterrupted())
            val status = team.messagesOf(objective.id, MessageType.STATUS).filter { it.from == Participant.System }.map { it.body }
            assertTrue(status.first().startsWith("Interrupted: the app stopped"), status.first())
            assertEquals(autoResume, status.last() == "Resumed automatically after the interruption.")
            if (!autoResume) {
                team.settle()
                assertEquals(TaskStatus.INTERRUPTED, team.objective(objective.id).status)
                assertEquals(TaskStatus.INTERRUPTED, team.work(objective.id).single().status)
                assertTrue(after.running.value.isEmpty())
                after.resume(objective.id)
            }
            team.settle()
            assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status, "autoResume=$autoResume")
            assertEquals("Done", team.work(objective.id).single().result)
            assertTrue(after.recoverInterrupted().isEmpty(), "nothing is left to recover")
        }
    }
}
