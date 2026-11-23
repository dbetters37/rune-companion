package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.settings.FrameworkSettings
import com.aicontrolcenter.core.tasks.MessageType
import com.aicontrolcenter.core.tasks.Participant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.memory.MemoryTools
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient.Companion.call
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamToolsTest {

    private fun ask(agent: String, question: String) = buildJsonObject {
        put("agent", agent)
        put("question", question)
    }

    private fun delegate(agent: String, title: String, instructions: String) = buildJsonObject {
        put("agent", agent)
        put("title", title)
        put("instructions", instructions)
        putJsonArray("completion_criteria") { add("Short") }
    }

    /** One research task for the Researcher; [calls] are the tool calls each agent makes on its first turn of a task. */
    private suspend fun TestScope.team(settings: FrameworkSettings.() -> FrameworkSettings = { this }, calls: Map<String, Pair<String, JsonObject>>): TeamFixture {
        val team = TeamFixture(this, settings).setUp()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t", "Find apps", listOf("research"))))) }
        team.script.work = { s ->
            val planned = calls[s.agent]
            if (planned != null && !team.script.afterTools(s)) ScriptStep.Calls(listOf(call("c_${s.agent}", planned.first, planned.second)))
            else ScriptStep.Reply("${s.agent} result. Tool said: ${team.script.lastToolResult(s)}")
        }
        return team
    }

    private suspend fun TeamFixture.allowDelegation(vararg names: String) {
        for (agent in listOf(researcher, analyst, writer).filter { it.name in names }) {
            world.agents.upsert(agent.copy(delegation = DelegationPolicy(canDelegate = true, canRequestAssistance = true, maxDepth = 3)))
        }
    }

    @Test
    fun `an agent asks a teammate, and the question and answer are stored as messages`() = runTest {
        val team = team(calls = mapOf("Researcher" to ("team_ask_agent" to ask("Analyst", "Which app is cheapest?"))))
        team.script.ask = { ScriptStep.Reply("Beta is cheapest.") }
        val objective = team.start()
        team.settle()
        val work = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, work.status)
        assertTrue(work.result!!.contains("Analyst answered:\nBeta is cheapest."), work.result)
        val request = team.messagesOf(objective.id, MessageType.ASSISTANCE_REQUEST).single()
        assertEquals(Participant.Agent(team.researcher.id), request.from)
        assertEquals(Participant.Agent(team.analyst.id), request.to)
        assertEquals(1, request.hop)
        val response = team.messagesOf(objective.id, MessageType.ASSISTANCE_RESPONSE).single()
        assertEquals(request.id, response.replyTo)
        assertEquals("Beta is cheapest.", response.body)
        val asked = team.script.seenBy(OrchestrationProtocol.ASK).single()
        assertEquals("Analyst", asked.agent)
        assertTrue(asked.brief.contains("label=\"Question from Researcher\">\n${DataBlock.QUOTE}Which app is cheapest?"), asked.brief)
        val researcherTools = team.script.seenBy(OrchestrationProtocol.TASK).first().request.tools.map { it.name }
        assertTrue("team_ask_agent" in researcherTools && "team_delegate" !in researcherTools, "offered as its delegation policy allows")
        assertEquals(1, work.usage.messagesSent)
    }

    @Test
    fun `an agent allowed to delegate hands part of its task to a teammate and gets the result`() = runTest {
        val team = team(calls = mapOf("Researcher" to ("team_delegate" to delegate("Writer", "Draft summary", "Summarise the apps"))))
        team.allowDelegation("Researcher")
        val objective = team.start()
        team.settle()
        val (parent, child) = team.tree(objective.id).filter { it.kind == TaskKind.WORK }.sortedBy { it.createdAt }
        assertEquals(parent.id, child.parentId)
        assertEquals(listOf(team.researcher.id), child.delegationChain)
        assertEquals(team.writer.id, child.assignee)
        assertEquals(TaskStatus.COMPLETED, child.status)
        assertTrue(parent.result!!.contains("Writer completed the delegated task \"Draft summary\"") && parent.result!!.contains("Writer result."), parent.result)
        val delegation = team.messagesOf(objective.id, MessageType.DELEGATION).single()
        assertEquals(1, delegation.hop)
        assertEquals(child.id, delegation.taskId)
        val completion = team.messagesOf(objective.id, MessageType.COMPLETION).first { it.taskId == child.id }
        assertEquals(Participant.Agent(team.researcher.id), completion.to)
        val writerBrief = team.script.seenBy(OrchestrationProtocol.TASK).single { it.agent == "Writer" }.brief
        assertTrue(writerBrief.startsWith("${OrchestrationProtocol.TASK}\nResearcher delegated part of its work to you"), writerBrief)
        assertTrue(writerBrief.contains("${DataBlock.QUOTE}Summarise the apps"), "the delegator's instructions are quoted data")
        val results = DataBlock.parseAll(team.script.seenBy(OrchestrationProtocol.SYNTHESIS).single().brief).filter { it.label.startsWith(BriefWriter.RESULT_LABEL) }
        assertEquals(listOf("Result of task 1: Find apps (by Researcher)"), results.map { it.label }, "delegated work reaches the synthesis through its delegating task")
    }

    @Test
    fun `delegating without the right to is refused with a reason the model can act on`() = runTest {
        val team = team(calls = mapOf("Researcher" to ("team_delegate" to delegate("Writer", "Draft", "Summarise"))))
        team.world.agents.upsert(team.researcher.copy(toolIds = team.researcher.toolIds + TeamTools.DELEGATE_ID))
        val objective = team.start()
        team.settle()
        assertTrue(team.work(objective.id).single().result!!.contains("not allowed to delegate work"))
        assertTrue(team.tree(objective.id).count { it.kind == TaskKind.WORK } == 1)
    }

    @Test
    fun `delegation deeper than the objective's limit is refused and reported`() = runTest {
        val team = team(
            settings = { copy(maxDelegationDepth = 1) },
            calls = mapOf(
                "Researcher" to ("team_delegate" to delegate("Writer", "Draft", "Summarise")),
                "Writer" to ("team_delegate" to delegate("Analyst", "Check", "Check the numbers")),
            ),
        )
        team.allowDelegation("Researcher", "Writer")
        val objective = team.start()
        team.settle()
        val writerRun = team.script.seen.last { it.agent == "Writer" }
        assertTrue(team.script.lastToolResult(writerRun).startsWith("Refused: this would be hop 2"), team.script.lastToolResult(writerRun))
        assertEquals(2, team.tree(objective.id).count { it.kind == TaskKind.WORK })
        val limit = team.limitMessages(objective.id).single()
        assertEquals(Participant.System, limit.from)
        assertTrue(limit.body.contains("Writer was refused"))
    }

    @Test
    fun `an agent cannot ask back up the chain that delegated to it`() = runTest {
        val team = team(
            calls = mapOf(
                "Researcher" to ("team_delegate" to delegate("Writer", "Draft", "Summarise")),
                "Writer" to ("team_ask_agent" to ask("Researcher", "What did you mean?")),
            ),
        )
        team.allowDelegation("Researcher")
        val objective = team.start()
        team.settle()
        val writerRun = team.script.seen.last { it.agent == "Writer" }
        assertTrue(team.script.lastToolResult(writerRun).contains("already handed work down to you"))
        assertTrue(team.messagesOf(objective.id, MessageType.ASSISTANCE_REQUEST).isEmpty())
        assertEquals(1, team.limitMessages(objective.id).size)
    }

    @Test
    fun `asking the same teammate the same question a third time is refused`() = runTest {
        val team = TeamFixture(this).setUp()
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t", "Find apps", listOf("research"))))) }
        team.script.work = { s ->
            val asked = s.request.messages.count { it.role == com.aicontrolcenter.core.llm.ChatRole.TOOL }
            if (asked < 3) ScriptStep.Calls(listOf(call("c$asked", "team_ask_agent", ask("Analyst", if (asked == 1) "which app is CHEAPEST?" else "Which app is cheapest?"))))
            else ScriptStep.Reply("Done after asking.")
        }
        val objective = team.start()
        team.settle()
        assertEquals(2, team.messagesOf(objective.id, MessageType.ASSISTANCE_REQUEST).size)
        val last = team.script.seen.last { it.agent == "Researcher" }
        assertTrue(team.script.lastToolResult(last).startsWith("Refused: you already sent Analyst this request 2 times"), team.script.lastToolResult(last))
        assertEquals(TaskStatus.COMPLETED, team.work(objective.id).single().status)
    }

    @Test
    fun `delegation beyond the objective's task limit is refused`() = runTest {
        val team = team(settings = { copy(maxTasksPerObjective = 1) }, calls = mapOf("Researcher" to ("team_delegate" to delegate("Writer", "Draft", "Summarise"))))
        team.allowDelegation("Researcher")
        val objective = team.start()
        team.settle()
        assertTrue(team.work(objective.id).single().result!!.contains("Refused: the objective already has 1 tasks, the limit is 1."))
        assertEquals(1, team.limitMessages(objective.id).size)
    }

    @Test
    fun `asking beyond the objective's message limit is refused`() = runTest {
        val team = team(settings = { copy(maxMessagesPerObjective = 4) }, calls = mapOf("Researcher" to ("team_ask_agent" to ask("Analyst", "Which?"))))
        val objective = team.start()
        team.settle()
        assertTrue(team.work(objective.id).single().result!!.contains("Refused: the objective already has 3 messages, the limit is 4."))
        assertTrue(team.messagesOf(objective.id, MessageType.ASSISTANCE_REQUEST).isEmpty())
    }

    @Test
    fun `the team tools refuse outside a task of a running objective`() = runTest {
        val team = TeamFixture(this).setUp()
        val outcome = team.orchestrator.desk.ask(ToolContext(team.researcher), "Analyst", "Hello?")
        assertTrue(!outcome.ok && outcome.content.contains("only while working on a task"))
        val delegated = team.orchestrator.desk.delegate(ToolContext(team.researcher.copy(delegation = DelegationPolicy(canDelegate = true))), "Analyst", "x", "y", emptyList())
        assertTrue(!delegated.ok && delegated.content.contains("only while working on a task"))
    }

    @Test
    fun `a memory shared during a task is recorded as a MEMORY_SHARE message`() = runTest {
        val team = TeamFixture(this).setUp()
        val space = team.world.memory.createSpace(com.aicontrolcenter.core.memory.MemoryActor.Owner, "Team")
        val profile = team.researcher.copy(
            toolIds = team.researcher.toolIds + MemoryTools.SHARE_ID,
            memory = team.researcher.memory.copy(writableSpaces = setOf(space.id), readableSpaces = setOf(space.id)),
        )
        team.world.agents.upsert(profile)
        val memory = team.world.memory.seed(MemoryScope.Agent(team.researcher.id), "Prices", "Alpha costs 3")
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t", "Find apps", listOf("research"))))) }
        team.script.work = { s ->
            if (!team.script.afterTools(s)) ScriptStep.Calls(listOf(call("m1", "memory_share", buildJsonObject { put("memory_id", memory.id.value); put("space", "Team") })))
            else ScriptStep.Reply("Shared.")
        }
        val objective = team.start()
        team.settle()
        val share = team.messagesOf(objective.id, MessageType.MEMORY_SHARE).single()
        assertEquals(Participant.Agent(team.researcher.id), share.from)
        assertTrue(share.body.startsWith("Shared memory ${memory.id.value}") && !share.body.contains("Alpha costs 3"), share.body)
    }
}
