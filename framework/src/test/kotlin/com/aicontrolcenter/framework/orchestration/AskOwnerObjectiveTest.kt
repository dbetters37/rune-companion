package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.operator.OwnerAttentionGate
import com.aicontrolcenter.framework.operator.OwnerQuestions
import com.aicontrolcenter.framework.operator.OwnerWaitLedger
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6, C1 (Tier B): `team.ask_owner` inside a real objective run. The work agent asks, the
 * task waits for the owner without its time running out, the owner answers in the app, and the
 * agent's next step reads the answer as quoted data; the time-out and a pause behave as rule 13 says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskOwnerObjectiveTest {
    private suspend fun TestScope.team(questionMinutes: Int = 20): TeamFixture {
        val team = TeamFixture(this) { copy(ownerQuestionTimeoutMinutes = questionMinutes) }.setUp()
        val world = team.world
        val questions = OwnerQuestions(
            world.requests, world.waiters, world.tasks, team.messages, world.facts, world.activity, world.ids, world.clock,
            OwnerAttentionGate(world.requests, world.waiters, world.activity), OwnerWaitLedger(), world.answeredQuestions,
        )
        TeamTools.registerAskOwner(world.toolRegistry, world.capabilities, questions)
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t", "Find apps", listOf("research"))))) }
        team.script.work = { s ->
            if (team.script.afterTools(s)) ScriptStep.Reply("Done, with: " + team.script.lastToolResult(s))
            else ScriptStep.Calls(listOf(ToolCallRequest("q1", "team_ask_owner", json("question" to "Should I include paid apps?", "options" to listOf("Free only", "Both")))))
        }
        return team
    }

    @Test
    fun `the agent asks, the task waits without its time running out, and the answer comes back as the owner's words`() = runTest {
        val team = team(questionMinutes = 240)
        val objective = team.start()
        // Plan, then the work step asks and waits.
        advanceTimeBy(5_000)
        runCurrent()
        val question = team.world.requests.observePending().first().single()
        assertEquals(OwnerRequestKind.QUESTION, question.kind)
        assertEquals(listOf("Free only", "Both"), question.options)
        val waiting = team.work(objective.id).single()
        assertEquals(TaskStatus.WAITING_FOR_OWNER, waiting.status)
        // The owner takes longer than the task's (15 min) and the objective's (90 min) own limits; the wait does not count.
        team.settle(2 * 60 * 60_000L)
        assertEquals(TaskStatus.WAITING_FOR_OWNER, team.work(objective.id).single().status)
        assertEquals(OwnerResult.Done(Unit), team.world.ownerRequests.answer(question.id, "Both, but mark the paid ones"))
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status, work.error ?: work.statusDetail)
        val result = work.result.orEmpty()
        assertTrue("The owner answered, in their own words:" in result && "│ Both, but mark the paid ones" in result, result)
        val messages = team.messages.list(objective.id)
        assertTrue(messages.any { it.type == MessageType.QUESTION && it.to == Participant.Owner })
        assertTrue(messages.any { it.type == MessageType.ANSWER && it.from == Participant.Owner && it.body == "Both, but mark the paid ones" })
    }

    @Test
    fun `unanswered, the agent is told to decide itself after the question time-out`() = runTest {
        val team = team()
        val objective = team.start()
        advanceTimeBy(5_000)
        runCurrent()
        val question = team.world.requests.observePending().first().single()
        advanceTimeBy(20 * 60_000L + 1_000)
        runCurrent()
        team.settle()
        assertEquals(OwnerRequestStatus.EXPIRED, team.world.requests.get(question.id)!!.status)
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status)
        assertTrue("did not answer within 20 minutes" in work.result.orEmpty(), work.result)
    }

    @Test
    fun `pausing the objective withdraws the question, and an answer then does nothing`() = runTest {
        val team = team()
        val objective = team.start()
        advanceTimeBy(5_000)
        runCurrent()
        val question = team.world.requests.observePending().first().single()
        team.orchestrator.pause(objective.id)
        runCurrent()
        assertEquals(OwnerRequestStatus.CANCELLED, team.world.requests.get(question.id)!!.status)
        assertTrue(team.world.ownerRequests.answer(question.id, "Both") is OwnerResult.Refused)
        assertTrue(team.messages.list(objective.id).none { it.type == MessageType.ANSWER })
    }
}
