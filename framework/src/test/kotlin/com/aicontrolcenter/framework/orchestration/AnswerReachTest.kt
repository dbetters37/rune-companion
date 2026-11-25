package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.Capabilities
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.operator.OwnerAttentionGate
import com.aicontrolcenter.framework.operator.OwnerQuestions
import com.aicontrolcenter.framework.operator.OwnerWaitLedger
import com.aicontrolcenter.framework.operator.PermissionBroker
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.titleOf
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient.Companion.call
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 6, rule 32 (security review of wave 2a, SEC-W2A-1): the owner's answer to a question
 * leaves the task that asked (to the task that delegated it, to the tasks that depend on it, and
 * to the Director's synthesis), so each of those asks the owner before its first call that may
 * send information off the phone. Over a real objective, the real broker and the real question tool.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnswerReachTest {
    /** A SAFE tool, granted by default, that sends what it is given off the phone: only rule 32 asks for it. */
    private class SendOut : Tool {
        val sent = CopyOnWriteArrayList<String>()
        override val spec = ToolSpec(
            ToolId("probe.send_out"), "send_out", "Send out (test)", "Sends text to a web address.", JsonObject(emptyMap()),
            ToolCategory.WEB, Capabilities.BASIC_INFO, RiskLevel.SAFE, sendsDataOffDevice = true,
        )

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome {
            sent += context.taskId?.value.orEmpty()
            return ToolOutcome.success("Sent.")
        }
    }

    private suspend fun TestScope.team(): Pair<TeamFixture, SendOut> {
        val team = TeamFixture(this).setUp()
        val world = team.world
        val questions = OwnerQuestions(
            world.requests, world.waiters, world.tasks, team.messages, world.facts, world.activity, world.ids, world.clock,
            OwnerAttentionGate(world.requests, world.waiters, world.activity), OwnerWaitLedger(), world.answeredQuestions,
        )
        TeamTools.registerAskOwner(world.toolRegistry, world.capabilities, questions)
        val sendOut = SendOut()
        world.toolRegistry.register(sendOut)
        for (agent in listOf(team.director, team.researcher, team.writer)) {
            world.agents.upsert(world.agents.get(agent.id)!!.let { it.copy(toolIds = it.toolIds + sendOut.spec.id) })
        }
        return team to sendOut
    }

    private fun sendOut() = ScriptStep.Calls(listOf(ToolCallRequest("s1", "send_out", JsonObject(emptyMap()))))

    private fun askOwner() =
        ScriptStep.Calls(listOf(ToolCallRequest("q1", "team_ask_owner", json("question" to "Which six characters does your latest notification show?"))))

    private suspend fun TeamFixture.pending(): OwnerRequest = world.requests.observePending().first().single()

    /** The owner answers the waiting question with a code, sent anyway. */
    private suspend fun TeamFixture.answer() {
        val question = pending()
        assertEquals(OwnerRequestKind.QUESTION, question.kind)
        assertEquals(OwnerResult.Done(Unit), world.ownerRequests.answer(question.id, "482913", sendAnyway = true))
        test.advanceTimeBy(5_000)
        test.runCurrent()
    }

    /** The call waiting now is [title]'s after-answer approval; the owner approves it. */
    private suspend fun TeamFixture.approveAfterAnswer(objective: com.aicontrolcenter.core.tasks.TaskRecord, title: String, sendOut: SendOut) {
        val approval = pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind, "the call of \"$title\" waits for the owner")
        assertEquals(PermissionBroker.AFTER_ANSWER, approval.details.first())
        val task = byTitle(objective.id, title)
        assertEquals(task.id, approval.taskId)
        assertTrue(task.id.value !in sendOut.sent, "nothing was sent before the owner saw it")
        world.ownerRequests.approve(approval.id)
        test.advanceTimeBy(5_000)
        test.runCurrent()
    }

    @Test
    fun `the task that depends on the one that asked, and the synthesis, each ask before sending anything off the phone`() = runTest {
        val (team, sendOut) = team()
        team.script.plan = { _, _ ->
            ScriptStep.Reply(
                planJson(
                    listOf(
                        task("a", "Log in to the portal", listOf("research")),
                        task("b", "Fetch the report", listOf("research"), dependsOn = listOf("a")),
                    ),
                ),
            )
        }
        team.script.work = { s ->
            when {
                titleOf(s.brief) == "Log in to the portal" ->
                    if (team.script.afterTools(s)) ScriptStep.Reply("Logged in with " + team.script.lastToolResult(s)) else askOwner()
                team.script.afterTools(s) -> ScriptStep.Reply("Fetched.")
                else -> sendOut()
            }
        }
        team.script.synthesis = { s -> if (team.script.afterTools(s)) ScriptStep.Reply("# Final\nAll done.") else sendOut() }
        val objective = team.start()
        advanceTimeBy(5_000)
        runCurrent()
        team.answer()
        // "Fetch the report" has the owner's answer in its brief.
        val brief = team.script.seenBy(OrchestrationProtocol.TASK).last { titleOf(it.brief) == "Fetch the report" }.brief
        assertTrue(brief.lines().any { "482913" in it }, brief)
        team.approveAfterAnswer(objective, "Fetch the report", sendOut)
        team.approveAfterAnswer(objective, "Final result", sendOut)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertEquals(2, sendOut.sent.size)
    }

    @Test
    fun `the task that delegated the work in which the owner answered asks before sending anything off the phone`() = runTest {
        val (team, sendOut) = team()
        team.world.agents.upsert(team.world.agents.get(team.researcher.id)!!.copy(delegation = DelegationPolicy(canDelegate = true, canRequestAssistance = true, maxDepth = 3)))
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("a", "Find apps", skills = listOf("research"))))) }
        team.script.work = { s ->
            val last = team.script.lastToolResult(s)
            when {
                s.agent == "Writer" -> if (team.script.afterTools(s)) ScriptStep.Reply("Logged in with $last") else askOwner()
                !team.script.afterTools(s) -> ScriptStep.Calls(listOf(call("d1", "team_delegate", buildJsonObject {
                    put("agent", "Writer")
                    put("title", "Log in")
                    put("instructions", "Log in to the portal.")
                })))
                "completed the delegated task" in last -> sendOut()
                else -> ScriptStep.Reply("Found.")
            }
        }
        team.script.synthesis = { ScriptStep.Reply("# Final\nAll done.") }
        val objective = team.start()
        advanceTimeBy(5_000)
        runCurrent()
        team.answer()
        team.approveAfterAnswer(objective, "Find apps", sendOut)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertEquals(listOf(team.byTitle(objective.id, "Find apps").id.value), sendOut.sent.toList())
    }
}
