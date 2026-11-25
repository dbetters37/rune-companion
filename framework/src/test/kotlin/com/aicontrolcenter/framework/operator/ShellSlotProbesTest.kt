package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.CommandLimits
import com.aicontrolcenter.core.operator.CommandOutcome
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ShellRunner
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.MINUTE
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.START
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecution
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.FakeWorkspaceRoot
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 6, D11 (rule 15): one shell command at a time, from the owner's question to the end of the
 * call. The Phase 5 verifier's probes (V7 G1-B, V8 R5-B and R5-C, scratchpad verify9) re-run as
 * assertions through the real permission broker and tool executor, in virtual time; a command takes
 * as long as its "sleep N" says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellSlotProbesTest {
    private class World(
        val b: BrokerFixture,
        val workspace: LocalWorkspace,
        val executor: ToolExecutor,
        val shellRuns: MutableList<String>,
        val second: AgentProfile,
        val third: AgentProfile,
    ) {
        private var calls = 0

        suspend fun shell(who: AgentProfile, task: String, command: String): ToolExecution =
            executor.execute(ToolCallRequest("c${++calls}", "scripts_run_shell", json("command" to command)), b.context(task = task, who = who))

        suspend fun pending() = b.requests.observePending().first()
    }

    private suspend fun TestScope.world(): World {
        val b = BrokerFixture(this).setUp()
        val f = OperatorFixture()
        val shellId = ToolId("scripts.run_shell")
        val agent = b.agent.copy(toolIds = b.agent.toolIds + shellId)
        b.agents.upsert(agent)
        b.agent = agent
        val second = Samples.agent(id = "agt_second", name = "Second").copy(toolIds = setOf(shellId)).also { b.agents.upsert(it) }
        // Third has the tool switched on but was never given the permission.
        val third = Samples.agent(id = "agt_third", name = "Third").copy(toolIds = setOf(shellId)).also { b.agents.upsert(it) }
        val runs = CopyOnWriteArrayList<String>()
        val shell = object : ShellRunner {
            override suspend fun run(command: String, workingDirectory: WorkspaceFile, limits: CommandLimits): CommandOutcome {
                runs += command
                val ms = Regex("sleep (\\d+)").find(command)?.groupValues?.get(1)?.toLong()?.times(1_000) ?: 10L
                delay(ms)
                return CommandOutcome.Finished(0, "ran: $command", "", false, ms)
            }
        }
        val base = f.dependencies
        val workspace = LocalWorkspace(FakeWorkspaceRoot(f.root), b.clock, io = EmptyCoroutineContext)
        val d = OperatorToolDependencies(
            workspace = workspace, sharedFolders = base.sharedFolders, folders = base.folders, media = base.media, apps = base.apps, web = base.web,
            screen = base.screen, authorisedApps = base.authorisedApps, settings = { b.settings.get() }, protectedPackages = base.protectedPackages,
            shell = shell, termux = base.termux, clock = b.clock, ownPackage = base.ownPackage, limits = base.limits, egress = OperatorEgress.logTo(b.activity),
        )
        val tool = OperatorToolset.create(d).single { it.spec.id == shellId }
        val registry = DefaultToolRegistry().also { it.register(tool) }
        for ((n, who) in listOf(agent, second).withIndex()) {
            b.grants.insert(PermissionGrant(GrantId("grt_$n"), who.id, tool.spec.capability, CapabilityConstraint.None, GrantScope.ALWAYS, grantedAt = 1))
        }
        for (task in listOf("tsk_a", "tsk_b", "tsk_c")) b.runningTask(task, deadlineAt = START + 15 * MINUTE)
        return World(b, workspace, ToolExecutor(registry, b.broker, b.activity, b.clock, timeSource = testTimeSource, tasks = b.tasks), runs, second, third)
    }

    @Test
    fun `G1-B - two agents' commands - the second is refused before the owner is asked, and runs once asked again after the first`() = runTest {
        val w = world()
        val first = async { w.shell(w.b.agent, "tsk_a", "sleep 30") }
        runCurrent()
        val second = async { w.shell(w.second, "tsk_b", "echo b") }
        runCurrent()
        // Only the first command is put to the owner; the second ended at once, unasked.
        assertEquals(1, w.pending().size)
        assertTrue(second.isCompleted)
        assertEquals(ToolCallEnd.DENIED, second.await().end)
        assertEquals(ShellSlot.SLOT_TAKEN, second.await().outcome.content)
        advanceTimeBy(MINUTE)
        // While the first waits for its approval, asking anew is refused the same way.
        val anew = async { w.shell(w.second, "tsk_b", "echo b") }
        runCurrent()
        assertEquals(ShellSlot.SLOT_TAKEN, anew.await().outcome.content)
        w.b.owner.approve(w.pending().single().id)
        runCurrent()
        // While it runs too.
        assertEquals(ShellSlot.SLOT_TAKEN, w.shell(w.second, "tsk_b", "echo b").outcome.content)
        advanceTimeBy(31_000)
        assertTrue(first.await().outcome.ok)
        // D12: the call's log entry says how long of it was the owner's time (the minute before the approval).
        val logged = w.b.events(com.aicontrolcenter.core.activity.ActivityType.TOOL_CALL).first { it.title == "Tool used: Run a shell command" }
        assertEquals(MINUTE.toString(), logged.data!!["ownerWaitMs"].toString())
        // Once it has ended, the second asks again as it was told: one request, one run.
        val third = async { w.shell(w.second, "tsk_b", "echo b") }
        runCurrent()
        w.b.owner.approve(w.pending().single().id)
        advanceTimeBy(1_000)
        assertTrue(third.await().outcome.ok, third.await().outcome.content)
        assertEquals(listOf("sleep 30", "echo b"), w.shellRuns.toList())
        assertEquals(2, w.b.allRequests().size, "the owner was asked once for each command that ran")
    }

    @Test
    fun `R5-B - two commands of one agent in one turn - the second is never put to the owner`() = runTest {
        val w = world()
        val one = async { w.shell(w.b.agent, "tsk_a", "sleep 30") }
        runCurrent()
        val two = async { w.shell(w.b.agent, "tsk_a", "echo two") }
        runCurrent()
        assertEquals(1, w.pending().size, "only the first command waits for the owner")
        assertEquals(ShellSlot.SLOT_TAKEN, two.await().outcome.content)
        w.b.owner.approve(w.pending().single().id)
        runCurrent()
        advanceTimeBy(31_000)
        assertTrue(one.await().outcome.ok)
        assertEquals(listOf("sleep 30"), w.shellRuns.toList())
        assertEquals(1, w.b.allRequests().size)
        // The slot is free again once the call ended.
        assertEquals(null, w.workspace.reserveCommand("probe").also { w.workspace.releaseReservation("probe") })
    }

    @Test
    fun `R5-C - an agent without the permission is asked for it first and learns nothing of a running command`() = runTest {
        val w = world()
        val first = async { w.shell(w.b.agent, "tsk_a", "sleep 30") }
        runCurrent()
        w.b.owner.approve(w.pending().single().id)
        runCurrent()
        assertTrue(w.workspace.commandRunning())
        // Third asks while Operator's command runs: the owner is asked for the permission, nothing about the command leaks.
        val busy = async { w.shell(w.third, "tsk_c", "echo c") }
        runCurrent()
        val asked = w.pending().single()
        assertEquals(OwnerRequestKind.PERMISSION, asked.kind)
        assertFalse(busy.isCompleted)
        w.b.owner.deny(asked.id)
        runCurrent()
        val refused = busy.await().outcome.content
        assertTrue("did not give" in refused, refused)
        assertFalse("running" in refused || "one at a time" in refused || "waits for the owner" in refused, refused)
        advanceTimeBy(31_000)
        assertTrue(first.await().outcome.ok)
    }

    @Test
    fun `a call cancelled while it waits for its approval gives the slot back`() = runTest {
        val w = world()
        val first = async { w.shell(w.b.agent, "tsk_a", "echo one") }
        runCurrent()
        assertEquals(1, w.pending().size)
        first.cancel()
        runCurrent()
        assertTrue(w.pending().isEmpty())
        val next = async { w.shell(w.second, "tsk_b", "echo b") }
        runCurrent()
        w.b.owner.approve(w.pending().single().id)
        advanceTimeBy(1_000)
        assertTrue(next.await().outcome.ok, next.await().outcome.content)
    }

    @Test
    fun `a refused approval gives the slot back`() = runTest {
        val w = world()
        val first = async { w.shell(w.b.agent, "tsk_a", "echo one") }
        runCurrent()
        w.b.owner.deny(w.pending().single().id)
        runCurrent()
        assertEquals(ToolCallEnd.DENIED, first.await().end)
        val next = async { w.shell(w.second, "tsk_b", "echo b") }
        runCurrent()
        assertEquals(1, w.pending().size)
        w.b.owner.approve(w.pending().single().id)
        advanceTimeBy(1_000)
        assertTrue(next.await().outcome.ok)
    }
}
