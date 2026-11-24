package com.aicontrolcenter.framework.orchestration

import com.aicontrolcenter.core.agents.DelegationPolicy
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.WaitClock
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityInfo
import com.aicontrolcenter.core.tools.ToolCategory
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.LambdaTool
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.planJson
import com.aicontrolcenter.framework.orchestration.TeamScript.Companion.task
import com.aicontrolcenter.testkit.ScriptStep
import com.aicontrolcenter.testkit.ScriptedLlmClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 2, B3: owner time a gateway pauses by itself (Android's install confirmation, the wait
 * for the operated app after an approval) is written to the task's stored deadline, as the
 * permission broker's own waits are. Built from the verifier's `V5GatewayPauseProbeTest`: a task
 * limited to 2 minutes, the owner takes 100 s inside a tool call, the agent then works 30 s and
 * asks a teammate, who gets what the stored deadline says is left.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayPauseDeadlineTest {
    private val guarded = Capability("test.guarded")

    /** [leftSeconds]: what the stored deadline said was left about 10 s after the owner's 100 s; [movedMs]: how far the stored deadline moved in all. */
    private class Outcome(val leftSeconds: Long, val movedMs: Long, val results: List<String>, val status: TaskStatus)

    /** As AndroidAppActions.awaitOutcome does for Android's confirmation: the gateway itself pauses the limits for the owner's [ms]. */
    private fun TeamFixture.gatewayTool(name: String, ms: Long) = world.register(
        LambdaTool(name) {
            WaitClock.pausedWhile { delay(ms); world.clock.advance(ms) }
            ToolOutcome.success("Android installed it.")
        },
    )

    private suspend fun TestScope.scenario(gatewayPause: Boolean): Outcome {
        val team = TeamFixture(this) { copy(taskTimeoutMinutes = 2, objectiveTimeoutMinutes = 60, approvalTimeoutMinutes = 30) }.setUp()
        team.world.capabilities.register(CapabilityInfo(guarded, "Guarded", "A permission the test asks for.", ToolCategory.SYSTEM, grantedByDefault = false))
        val install = team.gatewayTool("install", 100_000)
        // The same 100 s spent on a question of the permission broker.
        val asks = team.world.register(LambdaTool("guarded", capability = guarded) { ToolOutcome.success("guarded ran") })
        val first = if (gatewayPause) install else asks
        team.world.agents.upsert(team.researcher.copy(toolIds = team.researcher.toolIds + install.spec.id + asks.spec.id))
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t", "Set up the app", listOf("research"))))) }
        val results = CopyOnWriteArrayList<String>()
        var step = 0
        team.script.work = { s ->
            when {
                s.agent != "Researcher" -> ScriptStep.Reply("?")
                step == 0 -> { step = 1; ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", first.spec.functionName))) }
                step == 1 -> {
                    step = 2
                    results += team.script.lastToolResult(s)
                    // The model takes 30 s of the task's own time, then asks a teammate.
                    delay(30_000); team.world.clock.advance(30_000)
                    ScriptStep.Calls(listOf(ScriptedLlmClient.call("ask1", "team_ask_agent", buildJsonObject { put("agent", "Analyst"); put("question", "Which is cheapest?") })))
                }
                else -> { results += team.script.lastToolResult(s); ScriptStep.Reply("Done.") }
            }
        }
        team.script.ask = { _ -> delay(5_000); team.world.clock.advance(5_000); ScriptStep.Reply("Beta is cheapest.") }
        val objective = team.start()
        team.settle(1_000)
        if (!gatewayPause) {
            val request = team.world.ownerRequests.pending.first().single()
            team.world.clock.advance(100_000)
            team.settle(100_000)
            team.world.ownerRequests.grant(request.id, GrantScope.ONCE)
        }
        team.settle(110_000)
        val mid = team.work(objective.id).single()
        val left = (mid.deadlineAt!! - team.world.clock.now()) / 1000
        team.settle()
        val done = team.work(objective.id).single()
        return Outcome(left, done.deadlineAt!! - (done.startedAt!! + 2 * 60_000L), results.toList(), done.status)
    }

    @Test
    fun `B3 - after a gateway's own pause the teammate gets the time the task really has left`() = runTest {
        val gateway = scenario(gatewayPause = true)
        assertEquals(listOf("Android installed it.", "Analyst answered:\nBeta is cheapest."), gateway.results, "the stored deadline had moved, so the teammate had time to answer")
        assertEquals(TaskStatus.COMPLETED, gateway.status)
        assertEquals(100_000L, gateway.movedMs, "the owner's 100 s are in the stored deadline, once")
        assertTrue(gateway.leftSeconds in 80..120, "so the task had its two minutes less its own work left, not 20 s: ${gateway.leftSeconds} s")
    }

    @Test
    fun `B3 - the broker's own wait is still counted once, and both kinds of wait leave the same time`() = runTest {
        val broker = scenario(gatewayPause = false)
        assertEquals(listOf("guarded ran", "Analyst answered:\nBeta is cheapest."), broker.results)
        assertEquals(TaskStatus.COMPLETED, broker.status)
        // The broker wrote its wait when the answer came (100 s by the app's clock, which also ticks a little by itself);
        // the executor around the call did not write it again.
        assertTrue(broker.movedMs in 100_000L..101_000L, "the broker's wait is in the stored deadline once, not twice: ${broker.movedMs} ms")
    }

    @Test
    fun `B3 - a pause under a delegated task moves the delegating task's stored deadline once, as it happens`() = runTest {
        val team = TeamFixture(this) { copy(taskTimeoutMinutes = 5, objectiveTimeoutMinutes = 60) }.setUp()
        val install = team.gatewayTool("install", 100_000)
        team.world.agents.upsert(team.researcher.copy(delegation = DelegationPolicy(canDelegate = true, canRequestAssistance = true, maxDepth = 3)))
        team.world.agents.upsert(team.writer.copy(toolIds = team.writer.toolIds + install.spec.id))
        team.script.plan = { _, _ -> ScriptStep.Reply(planJson(listOf(task("t", "Set up the app", listOf("research"))))) }
        team.script.work = { s ->
            when {
                team.script.afterTools(s) -> ScriptStep.Reply("${s.agent} done: ${team.script.lastToolResult(s)}")
                s.agent == "Researcher" -> ScriptStep.Calls(
                    listOf(
                        ScriptedLlmClient.call(
                            "d1", "team_delegate",
                            buildJsonObject {
                                put("agent", "Writer"); put("title", "Install it"); put("instructions", "Install the app")
                                putJsonArray("completion_criteria") { add("Installed") }
                            },
                        ),
                    ),
                )
                else -> ScriptStep.Calls(listOf(ScriptedLlmClient.call("c1", install.spec.functionName)))
            }
        }
        val objective = team.start()
        team.settle(1_000)
        suspend fun tasks() = team.tree(objective.id).filter { it.kind == TaskKind.WORK }.sortedBy { it.createdAt }
        val (parentBefore, childBefore) = tasks()
        assertEquals(TaskStatus.WAITING_FOR_AGENT, parentBefore.status)
        // The owner's 100 s on Android's confirmation, under the delegated task: nothing moves while they last.
        team.settle(50_000)
        val (parentDuring, childDuring) = tasks()
        assertEquals(parentBefore.deadlineAt, parentDuring.deadlineAt)
        assertEquals(childBefore.deadlineAt, childDuring.deadlineAt)
        team.settle(DAY)
        val (parent, child) = tasks()
        assertEquals(TaskStatus.COMPLETED, child.status)
        assertEquals(TaskStatus.COMPLETED, parent.status)
        assertEquals(childBefore.deadlineAt!! + 100_000, child.deadlineAt, "the delegated task's deadline moved by the pause")
        assertEquals(parentBefore.deadlineAt!! + 100_000, parent.deadlineAt, "and the delegating task's by the same pause, once: not again when its own call ended")
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
