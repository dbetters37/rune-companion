package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.WaitClock
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.PreparedAction
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.MINUTE
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.START
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Round 2, B3, at the executor: every wait for the owner inside one tool call ends up in the
 * task's stored deadline exactly once. The broker writes its own wait when the answer comes;
 * the executor writes what else the call waited: the wait for the operated app right after an
 * approval (`changedSinceOwnerAnswered`) and a pause the tool makes itself while it runs
 * (Android's confirmation of an install).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerTimeDeadlineTest {
    /** A consequential call that waits [returnMs] for its app after the owner's answer, and [confirmMs] for Android's own confirmation while it runs. */
    private class Waits(fixture: BrokerFixture, val returnMs: Long, val confirmMs: Long) : Tool {
        private val constraint = fixture.inFolder
        override val spec = fixture.deleteInFolder

        override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
            val prepared = object : PreparedAction {
                override suspend fun changedSince(): String? = null

                override suspend fun changedSinceOwnerAnswered(): String? = WaitClock.pausedWhile { delay(returnMs); null }
            }
            return ActionAssessment(RiskLevel.CONSEQUENTIAL, "Delete 3 files in Reports", listOf("3 files"), constraint, prepared = prepared)
        }

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolOutcome = error("the framework runs the assessment it authorised")

        override suspend fun execute(arguments: JsonObject, context: ToolContext, assessment: ActionAssessment): ToolOutcome {
            WaitClock.pausedWhile { delay(confirmMs) }
            return ToolOutcome.success("Deleted.")
        }
    }

    @Test
    fun `B3 - every wait for the owner inside a call moves the stored deadline of its task and of the task delegating to it, once`() = runTest {
        val f = BrokerFixture(this).setUp()
        f.grants.insert(PermissionGrant(GrantId("grt_1"), f.agent.id, OperatorCapabilities.FOLDERS_WRITE, f.inFolder, GrantScope.ALWAYS, grantedAt = 1))
        val lead = f.runningTask("tsk_lead", deadlineAt = START + 10 * MINUTE, status = TaskStatus.WAITING_FOR_AGENT)
        val task = f.runningTask("tsk_work", deadlineAt = START + 2 * MINUTE, parent = "tsk_lead")
        val tool = Waits(f, returnMs = 20_000, confirmMs = 7_000)
        val executor = ToolExecutor(DefaultToolRegistry().also { it.register(tool) }, f.broker, f.activity, f.clock, timeSource = testTimeSource, tasks = f.tasks)
        suspend fun deadline(id: String) = f.tasks.get(com.aicontrolcenter.core.TaskId(id))!!.deadlineAt!! - START

        val call = async { executor.execute(ToolCallRequest("c1", tool.spec.functionName, JsonObject(emptyMap())), f.context()) }
        runCurrent()
        // The owner takes five minutes to approve: the broker writes that wait when the answer comes.
        advanceTimeBy(5 * MINUTE)
        f.owner.approve(f.pending().id)
        runCurrent()
        assertEquals(7 * MINUTE, deadline(task.id.value), "the broker's wait, once")
        assertEquals(15 * MINUTE, deadline(lead.id.value))

        // Then the call waits 20 s for its app to come back in front: written when that wait ends, before the tool runs.
        advanceTimeBy(19_000)
        runCurrent()
        assertEquals(7 * MINUTE, deadline(task.id.value))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(7 * MINUTE + 20_000, deadline(task.id.value), "the wait for the app is in the stored deadline")
        assertEquals(15 * MINUTE + 20_000, deadline(lead.id.value))

        // And 7 s on Android's own confirmation while the tool runs.
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(ToolCallEnd.OK, call.await().end)
        assertEquals(7 * MINUTE + 27_000, deadline(task.id.value), "broker 5 min + return 20 s + confirmation 7 s, each once")
        assertEquals(15 * MINUTE + 27_000, deadline(lead.id.value), "the delegating task waited as long")
        assertEquals(TaskStatus.RUNNING, f.tasks.get(task.id)?.status)
    }

    @Test
    fun `B3 - a call outside any task, or of a task that has finished, moves nothing`() = runTest {
        val f = BrokerFixture(this).setUp()
        f.grants.insert(PermissionGrant(GrantId("grt_1"), f.agent.id, OperatorCapabilities.FOLDERS_WRITE, f.inFolder, GrantScope.ALWAYS, grantedAt = 1))
        val other = f.runningTask("tsk_other", deadlineAt = START + 2 * MINUTE)
        val done = f.runningTask("tsk_work", deadlineAt = START + 2 * MINUTE, status = TaskStatus.COMPLETED)
        val tool = Waits(f, returnMs = 20_000, confirmMs = 7_000)
        val executor = ToolExecutor(DefaultToolRegistry().also { it.register(tool) }, f.broker, f.activity, f.clock, timeSource = testTimeSource, tasks = f.tasks)

        for (context in listOf(f.context(task = null, conversation = "cnv_1"), f.context(task = "tsk_work"))) {
            val call = async { executor.execute(ToolCallRequest("c1", tool.spec.functionName, JsonObject(emptyMap())), context) }
            runCurrent()
            f.owner.approve(f.pending().id)
            advanceTimeBy(MINUTE)
            assertEquals(ToolCallEnd.OK, call.await().end)
        }
        assertEquals(START + 2 * MINUTE, f.tasks.get(done.id)?.deadlineAt, "a finished task keeps its deadline")
        assertEquals(START + 2 * MINUTE, f.tasks.get(other.id)?.deadlineAt, "another task is not touched")
    }
}
