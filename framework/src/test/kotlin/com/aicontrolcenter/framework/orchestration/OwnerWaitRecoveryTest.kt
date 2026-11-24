package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.LambdaTool
import com.aicontrolcenter.framework.TestWorld
import com.aicontrolcenter.framework.orchestration.TeamFixture.Companion.MINUTE_MS
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review 5 fixes where the broker meets real objectives (from the integration reviewers' probes):
 * an Always answer releases a second call waiting on the same question (INT-5), a request raised
 * by work restarted before start-up recovery keeps its waiter (INT-4), and a wait for the owner
 * while the Director plans is not charged to the objective after a crash (INT-3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerWaitRecoveryTest {
    private val guarded = Capability("test.guarded")

    private fun TestWorld.guardedTool(): LambdaTool {
        capabilities.register(CapabilityInfo(guarded, "Guarded", "A permission the tests ask for.", ToolCategory.SYSTEM, grantedByDefault = false))
        return register(LambdaTool("guarded", capability = guarded) { ToolOutcome.success("guarded ran") })
    }

    private suspend fun TeamFixture.pending(): List<OwnerRequest> = world.ownerRequests.pending.first()

    @Test
    fun `an Always answer to one task's question releases the same question from a parallel task`() = runTest {
        val team = TeamFixture(this) { copy(maxConcurrentTasks = 2, approvalTimeoutMinutes = 30) }.setUp()
        val tool = team.world.guardedTool()
        team.world.agents.upsert(team.researcher.copy(toolIds = team.researcher.toolIds + tool.spec.id))
        team.script.plan = { _, _ ->
            ScriptStep.Reply(planJson(listOf(task("a", "First part", assignee = "Researcher"), task("b", "Second part", assignee = "Researcher"))))
        }
        val results = CopyOnWriteArrayList<String>()
        team.script.work = { s ->
            if (team.script.afterTools(s)) ScriptStep.Reply("Done. Tool said: ${team.script.lastToolResult(s).also { results += it }}")
            else ScriptStep.Calls(listOf(ScriptedLlmClient.call("c_${TeamScript.titleOf(s.brief).take(5)}", tool.spec.functionName)))
        }
        val objective = team.start()
        team.settle(1_000)
        val both = team.pending()
        assertEquals(2, both.size, "two calls of the same agent each raised a request")

        team.world.ownerRequests.grant(both[0].id, GrantScope.ALWAYS)
        team.settle(1_000)

        assertTrue(team.pending().isEmpty(), "the second question was settled by the Always answer")
        val settled = team.world.requests.get(both[1].id)!!
        assertEquals(OwnerRequestStatus.APPROVED, settled.status)
        assertEquals(2, tool.calls.size, "both calls ran")
        assertEquals(listOf("guarded ran", "guarded ran"), results.toList())
        team.settle()
        assertTrue(team.work(objective.id).all { it.status == TaskStatus.COMPLETED }, team.work(objective.id).map { it.status }.toString())
    }

    @Test
    fun `a request raised by work resumed before start-up recovery keeps its waiter and can be answered`() = runTest {
        val team = TeamFixture(this).setUp()
        val tool = team.world.guardedTool()
        team.world.agents.upsert(team.researcher.copy(toolIds = team.researcher.toolIds + tool.spec.id))
        val results = CopyOnWriteArrayList<String>()
        team.script.work = { s ->
            if (team.script.afterTools(s)) ScriptStep.Reply("Done. Tool said: ${team.script.lastToolResult(s).also { results += it }}")
            else ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", tool.spec.functionName)))
        }
        val objective = team.start()
        team.settle(1_000)
        team.orchestrator.pause(objective.id)
        team.settle(1_000)
        assertEquals(TaskStatus.PAUSED, team.objective(objective.id).status)

        // The owner taps Resume on a restored Objective screen before start-up reaches the recovery.
        val after = team.newOrchestrator()
        after.resume(objective.id)
        team.settle(1_000)
        val fresh = team.pending().single()

        after.recoverInterrupted() // start-up finishes after the tap
        team.settle(1_000)
        assertEquals(OwnerRequestStatus.PENDING, team.world.requests.get(fresh.id)?.status, "a live call waits for it, so start-up leaves it open")
        team.world.ownerRequests.grant(fresh.id, GrantScope.ONCE)
        team.settle()
        assertEquals(1, tool.calls.size)
        assertEquals(listOf("guarded ran"), results.toList())
        assertEquals(TaskStatus.COMPLETED, team.objective(objective.id).status)
    }

    @Test
    fun `a wait for the owner while the Director plans is credited to the objective after a crash`() = runTest {
        val team = TeamFixture(this) { copy(objectiveTimeoutMinutes = 10, taskTimeoutMinutes = 60, autoResumeInterruptedTasks = false) }.setUp()
        val tool = team.world.guardedTool()
        team.world.agents.upsert(team.director.copy(toolIds = team.director.toolIds + tool.spec.id))
        team.script.plan = { s, _ ->
            if (team.script.afterTools(s)) ScriptStep.Reply(planJson(listOf(task("t1", "Research the subject", skills = listOf("research")))))
            else ScriptStep.Calls(listOf(ScriptedLlmClient.call("p1", tool.spec.functionName)))
        }
        val left = timeLeftAfterCrashDuringWait(team)
        assertTrue(left >= 9 * MINUTE_MS, "the 8 minutes spent waiting for the owner are not charged: ${left / 60_000.0} min left of 10")
    }

    @Test
    fun `a wait while the Director plans, answered, leaves the objective its full time and the plan runs`() = runTest {
        val team = TeamFixture(this) { copy(objectiveTimeoutMinutes = 10, taskTimeoutMinutes = 60) }.setUp()
        val tool = team.world.guardedTool()
        team.world.agents.upsert(team.director.copy(toolIds = team.director.toolIds + tool.spec.id))
        team.script.plan = { s, _ ->
            if (team.script.afterTools(s)) ScriptStep.Reply(planJson(listOf(task("t1", "Research the subject", skills = listOf("research")))))
            else ScriptStep.Calls(listOf(ScriptedLlmClient.call("p1", tool.spec.functionName)))
        }
        val objective = team.start()
        team.settle(1_000)
        val before = team.objective(objective.id).deadlineAt!!
        team.world.clock.advance(12 * MINUTE_MS)
        team.settle(12 * MINUTE_MS)
        team.world.ownerRequests.grant(team.pending().single().id, GrantScope.ONCE)
        team.settle()
        val done = team.objective(objective.id)
        assertEquals(TaskStatus.COMPLETED, done.status, done.statusDetail)
        assertTrue(done.deadlineAt!! >= before + 12 * MINUTE_MS - 1_000, "the stored deadline moved with the wait")
        assertEquals(1, tool.calls.size)
    }

    /** Starts an objective in a killable "process", lets the request wait 8 minutes, kills it hard and recovers. */
    private suspend fun TestScope.timeLeftAfterCrashDuringWait(team: TeamFixture): Long {
        val app = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val objective = team.newOrchestrator(app).startObjective("Research note apps")
        team.settle(1_000)
        val request = team.pending().single()
        team.world.clock.advance(8 * MINUTE_MS)
        team.settle(8 * MINUTE_MS)
        // The store as the killed process left it (no finally blocks run on a real kill).
        val atDeath = team.tree(objective.id)
        assertEquals(OwnerRequestStatus.PENDING, team.world.requests.get(request.id)!!.status)
        app.cancel()
        team.settle(1_000)
        team.tasks.upsertAll(atDeath)
        val after = team.newOrchestrator()
        after.recoverInterrupted()
        val recovered = team.objective(objective.id)
        assertEquals(TaskStatus.INTERRUPTED, recovered.status, recovered.statusDetail)
        return recovered.deadlineAt!! - recovered.updatedAt
    }
}
