package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.agents.AgentActivity
import com.aicontrolcenter.core.chat.TurnResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.LambdaTool
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.operator.OperatorTaskHooks
import com.aicontrolcenter.framework.orchestration.TeamFixture.Companion.MINUTE_MS
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Waiting for the owner inside objectives and chats: the time it takes is nobody's time limit, and stopping withdraws the question. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerWaitTest {
    private val guarded = Capability("test.guarded")

    /** A tool needing a capability that is not granted by default, so every first call asks the owner. */
    private fun TestWorld.guardedTool(): LambdaTool {
        capabilities.register(CapabilityInfo(guarded, "Guarded", "A permission the tests ask for.", ToolCategory.SYSTEM, grantedByDefault = false))
        return register(LambdaTool("guarded", capability = guarded) { ToolOutcome.success("guarded ran") })
    }

    /** The researcher calls [tool] once, then answers. */
    private suspend fun TeamFixture.researcherCalls(tool: LambdaTool) {
        world.agents.upsert(researcher.copy(toolIds = researcher.toolIds + tool.spec.id))
        script.work = { s -> if (script.afterTools(s)) ScriptStep.Reply("Found it.") else ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", tool.spec.functionName))) }
    }

    private suspend fun TeamFixture.pending(): OwnerRequest = world.ownerRequests.pending.first().single()

    @Test
    fun `a one-minute task waits five minutes for the owner, then runs the call and completes`() = runTest {
        val team = TeamFixture(this) { copy(taskTimeoutMinutes = 1, maxTaskAttempts = 1) }.setUp()
        val tool = team.world.guardedTool()
        team.researcherCalls(tool)
        val objective = team.start()
        team.settle(1_000)

        val request = team.pending()
        val waiting = team.work(objective.id).single()
        assertEquals(TaskStatus.WAITING_FOR_OWNER, waiting.status)
        assertEquals(waiting.id, request.taskId)
        assertEquals(objective.id, request.rootId)
        assertEquals(AgentActivity.WAITING_FOR_APPROVAL, team.world.monitor.states.value[team.researcher.id]?.activity)
        val deadline = waiting.deadlineAt!!

        team.world.clock.advance(5 * MINUTE_MS)
        team.settle(5 * MINUTE_MS)
        assertEquals(TaskStatus.WAITING_FOR_OWNER, team.work(objective.id).single().status, "five minutes on, still waiting: no time limit ran")
        team.world.ownerRequests.grant(request.id, GrantScope.ONCE)
        team.settle()

        val done = team.work(objective.id).single()
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals("Found it.", done.result)
        assertEquals(1, tool.calls.size)
        assertTrue(done.deadlineAt!! >= deadline + 5 * MINUTE_MS, "the stored deadline moved with the wait")
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertTrue(team.limitMessages(objective.id).isEmpty())
    }

    @Test
    fun `a three-minute objective waits ten minutes for the owner and keeps its time`() = runTest {
        val team = TeamFixture(this) { copy(objectiveTimeoutMinutes = 3, taskTimeoutMinutes = 60) }.setUp()
        val tool = team.world.guardedTool()
        team.researcherCalls(tool)
        val objective = team.start()
        team.settle(1_000)
        val request = team.pending()
        val before = team.objective(objective.id).deadlineAt!!

        team.world.clock.advance(10 * MINUTE_MS)
        team.settle(10 * MINUTE_MS)
        val waiting = team.objective(objective.id)
        assertEquals(TaskStatus.RUNNING, waiting.status, "ten minutes into a three-minute limit")
        assertTrue(waiting.deadlineAt!! >= before + 9 * MINUTE_MS, "the heartbeat stores the deadline moved by the wait: ${waiting.deadlineAt!! - before}")

        team.world.ownerRequests.grant(request.id, GrantScope.TASK)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertEquals(TaskStatus.COMPLETED, team.work(objective.id).single().status)
        assertTrue(team.limitMessages(objective.id).isEmpty())
    }

    @Test
    fun `pausing the objective while a task waits withdraws the question, keeps the time left, and resuming asks again`() = runTest {
        val team = TeamFixture(this).setUp()
        val tool = team.world.guardedTool()
        team.researcherCalls(tool)
        val objective = team.start()
        team.settle(1_000)
        val request = team.pending()
        val deadline = team.objective(objective.id).deadlineAt!!

        team.world.clock.advance(4 * MINUTE_MS)
        team.settle(4 * MINUTE_MS)
        team.orchestrator.pause(objective.id)
        team.settle(1_000)
        assertEquals(OwnerRequestStatus.CANCELLED, team.world.requests.get(request.id)?.status)
        assertEquals(TaskStatus.PAUSED, team.work(objective.id).single().status)
        val paused = team.objective(objective.id)
        assertTrue(paused.deadlineAt!! >= deadline + 4 * MINUTE_MS, "the time spent waiting is not taken from what is left")
        assertTrue(team.world.ownerRequests.pending.first().isEmpty())

        team.orchestrator.resume(objective.id)
        team.settle(1_000)
        val again = team.pending()
        assertNotEquals(request.id, again.id)
        team.world.ownerRequests.grant(again.id, GrantScope.ALWAYS)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
        assertEquals(1, tool.calls.size)
    }

    @Test
    fun `after a crash, a task that waited for the owner is recovered like one waiting for an agent, and its question is withdrawn first`() = runTest {
        val team = TeamFixture(this).setUp()
        var hold = true
        team.script.work = { if (hold) awaitCancellation() else ScriptStep.Reply("Done") }
        val app = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val objective = team.newOrchestrator(app).startObjective("Research note apps")
        team.settle(MINUTE_MS)
        app.cancel() // the app is killed: nothing records that the work stopped
        team.settle(MINUTE_MS)
        // As the store was when it died: the task waiting for the owner, the question open.
        val work = team.work(objective.id).single()
        team.tasks.update(work.id) { it.copy(status = TaskStatus.WAITING_FOR_OWNER) }
        val now = team.world.clock.now()
        val open = OwnerRequest(
            id = RequestId("req_open"), kind = OwnerRequestKind.PERMISSION, agentId = team.researcher.id, toolId = ToolId("test.guarded"),
            capability = guarded, risk = RiskLevel.MODIFYING, summary = "Use the guarded tool", taskId = work.id, rootId = objective.id,
            createdAt = now, expiresAt = now + 30 * MINUTE_MS,
        )
        team.world.requests.create(open)

        hold = false
        val after = team.newOrchestrator()
        assertEquals(listOf(objective.id), after.recoverInterrupted())
        val withdrawn = team.world.requests.get(open.id)!!
        assertEquals(OwnerRequestStatus.CANCELLED, withdrawn.status)
        assertEquals(OperatorTaskHooks.STOPPED_NOTE, withdrawn.decisionNote)
        val interrupted = team.work(objective.id).single()
        assertEquals(TaskStatus.INTERRUPTED, interrupted.status)
        assertEquals(1, interrupted.attempt, "the stopped run counts as an attempt, as for a task waiting for an agent")
        assertTrue(team.world.events(ActivityType.PERMISSION).any { it.title == "Requests withdrawn at start-up" })

        after.resume(objective.id)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
    }

    @Test
    fun `a task that ends takes its task grants and its open asks with it, grants for always stay`() = runTest {
        val team = TeamFixture(this).setUp()
        val release = CompletableDeferred<Unit>()
        team.script.work = {
            release.await()
            ScriptStep.Reply("Done")
        }
        val objective = team.start()
        team.settle(1_000)
        val work = team.work(objective.id).single()
        val now = team.world.clock.now()
        val agent = team.researcher.id
        val web = OperatorCapabilities.WEB_READ
        team.world.grants.insert(PermissionGrant(GrantId("grt_task"), agent, web, CapabilityConstraint.None, GrantScope.TASK, work.id, grantedAt = now))
        team.world.grants.insert(PermissionGrant(GrantId("grt_once"), agent, web, CapabilityConstraint.None, GrantScope.ONCE, work.id, RequestId("req_used"), grantedAt = now, expiresAt = now + 10 * MINUTE_MS))
        team.world.grants.insert(PermissionGrant(GrantId("grt_always"), agent, web, CapabilityConstraint.None, GrantScope.ALWAYS, grantedAt = now))
        val ask = OwnerRequest(
            id = RequestId("req_ask"), kind = OwnerRequestKind.PERMISSION, agentId = agent, capability = web, risk = RiskLevel.SAFE,
            summary = "Researcher asks for \"Read web pages\"", reason = "To check prices.", taskId = work.id, rootId = objective.id,
            createdAt = now, expiresAt = now + 30 * MINUTE_MS,
        )
        team.world.requests.create(ask)

        release.complete(Unit)
        team.settle()
        assertEquals(TaskStatus.COMPLETED, team.work(objective.id).single().status)
        assertNotNull(team.world.grants.get(GrantId("grt_task"))?.revokedAt)
        assertNotNull(team.world.grants.get(GrantId("grt_once"))?.revokedAt)
        assertNull(team.world.grants.get(GrantId("grt_always"))?.revokedAt)
        assertEquals(OwnerRequestStatus.CANCELLED, team.world.requests.get(ask.id)?.status)
    }

    @Test
    fun `in a chat the question shows in the live turn, and stopping the chat withdraws it`() = runTest {
        val world = TestWorld(scope = backgroundScope).also { it.time.source = testTimeSource }
        val tool = world.guardedTool()
        val client = ScriptedLlmClient { _, index -> if (index == 0) ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", tool.spec.functionName))) else ScriptStep.Reply("Done.") }
        val agent = world.agent(world.serve(client), tools = setOf(tool.spec.id))
        val conversation = world.chat.startConversation(agent.id)
        val turn = async { world.chat.send(conversation.id, "Use the guarded tool.") }
        runCurrent()

        val waiting = assertNotNull(world.chat.liveTurns.value[conversation.id]?.waitingForOwner)
        assertEquals(conversation.id, waiting.conversationId)
        assertEquals(OwnerRequestKind.PERMISSION, waiting.kind)
        assertEquals(listOf(waiting.id), world.ownerRequests.observeForConversation(conversation.id).first().map { it.id })
        assertEquals(AgentActivity.WAITING_FOR_APPROVAL, world.monitor.states.value[agent.id]?.activity)

        world.chat.cancel(conversation.id)
        assertEquals(TurnResult.Cancelled, turn.await())
        assertEquals(OwnerRequestStatus.CANCELLED, world.requests.get(waiting.id)?.status)
        assertTrue(tool.calls.isEmpty())
        assertNull(world.chat.liveTurns.value[conversation.id])
    }

    @Test
    fun `in a chat the owner's answer lets the turn go on`() = runTest {
        val world = TestWorld(scope = backgroundScope).also { it.time.source = testTimeSource }
        val tool = world.guardedTool()
        val client = ScriptedLlmClient { _, index -> if (index == 0) ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", tool.spec.functionName))) else ScriptStep.Reply("Done.") }
        val agent = world.agent(world.serve(client), tools = setOf(tool.spec.id))
        val conversation = world.chat.startConversation(agent.id)
        val turn = async { world.chat.send(conversation.id, "Use the guarded tool.") }
        runCurrent()
        val waiting = assertNotNull(world.chat.liveTurns.value[conversation.id]?.waitingForOwner)

        world.ownerRequests.grant(waiting.id, GrantScope.ONCE)
        advanceUntilIdle()
        assertEquals("Done.", assertIs<TurnResult.Completed>(turn.await()).text)
        assertEquals(1, tool.calls.size)
        assertNull(world.chat.liveTurns.value[conversation.id])
    }
}
