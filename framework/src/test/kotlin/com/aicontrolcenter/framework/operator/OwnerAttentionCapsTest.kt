package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.agents.AgentProfile
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerAttention
import com.aicontrolcenter.core.tasks.ObjectiveOptions
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.MINUTE
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.START
import com.aicontrolcenter.testkit.Samples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 6, rules 13 and 14, C3 and D10: how much of the owner's attention agents may ask for at
 * once, through the real broker and capability requester sharing one [OwnerAttentionGate].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnerAttentionCapsTest {
    private class World(val b: BrokerFixture, val requester: DefaultCapabilityRequester, val broker: PermissionBroker)

    private suspend fun TestScope.world(): World {
        val b = BrokerFixture(this).setUp()
        val attention = OwnerAttentionGate(b.requests, b.waiters, b.activity)
        val broker = PermissionBroker(b.facts, b.requests, b.grants, b.waiters, b.tasks, b.activity, b.ids, b.clock, attention)
        val requester = DefaultCapabilityRequester(b.facts, b.requests, b.grants, b.waiters, b.activity, b.ids, b.clock, b.tasks, attention)
        return World(b, requester, broker)
    }

    private fun context(agent: AgentProfile, task: String?, root: String?) = ToolContext(agent, task?.let(::TaskId), root?.let(::TaskId))

    @Test
    fun `the 13th pending request of calls in progress is refused, nothing is stored and the call does not run rule 14`() = runTest {
        val w = world()
        val write = w.b.assessment(RiskLevel.MODIFYING)
        val waiting = (1..6).map { n -> async { w.broker.authorize(w.b.writeFolder, write, context(w.b.agent, "tsk_a$n", "tsk_root")) } }
        runCurrent()
        assertEquals(6, w.b.requests.observePending().first().size)
        // A seventh under the same objective: refused, nothing stored.
        val seventh = async { w.broker.authorize(w.b.writeFolder, write, context(w.b.agent, "tsk_a7", "tsk_root")) }
        runCurrent()
        val refusal = assertIs<Authorization.Denied>(seventh.await()).reason
        assertTrue("Many requests already wait for the owner's answer (6 for this objective)." in refusal, refusal)
        assertTrue(OwnerAttentionGate.ASSUME_AND_GO_ON in refusal)
        assertEquals(6, w.b.requests.observePending().first().size)
        // Six more of another objective and of chats: twelve in all; the thirteenth is refused.
        val more = (1..6).map { n -> async { w.broker.authorize(w.b.writeFolder, write, context(w.b.agent, if (n <= 3) "tsk_b$n" else null, if (n <= 3) "tsk_root2" else null)) } }
        runCurrent()
        assertEquals(OwnerAttention.MAX_PENDING_CALL_REQUESTS, w.b.requests.observePending().first().size)
        val thirteenth = async { w.broker.authorize(w.b.writeFolder, write, context(w.b.agent, "tsk_c1", "tsk_root3")) }
        runCurrent()
        assertTrue("(12)" in assertIs<Authorization.Denied>(thirteenth.await()).reason)
        assertEquals(12, w.b.allRequests().size, "nothing was stored for the refused calls")
        // The owner is told, once per task.
        assertEquals(2, w.b.events(com.aicontrolcenter.core.activity.ActivityType.PERMISSION).count { it.title == "Not asked: too many requests wait for you" })
        // An answer frees a place.
        w.b.owner.grant(w.b.requests.observePending().first().first().id, com.aicontrolcenter.core.operator.GrantScope.ONCE)
        runCurrent()
        val after = async { w.broker.authorize(w.b.writeFolder, write, context(w.b.agent, "tsk_c1", "tsk_root3")) }
        runCurrent()
        assertEquals(12, w.b.requests.observePending().first().size)
        (waiting + more + after).forEach { it.cancel() }
    }

    @Test
    fun `concurrent calls cannot pass the cap together`() = runTest {
        val w = world()
        val write = w.b.assessment(RiskLevel.MODIFYING)
        val calls = (1..20).map { n -> async { w.broker.authorize(w.b.writeFolder, write, context(w.b.agent, "tsk_$n", "tsk_root")) } }
        runCurrent()
        assertEquals(OwnerAttention.MAX_PENDING_CALL_REQUESTS_PER_OBJECTIVE, w.b.requests.observePending().first().size)
        assertEquals(14, calls.count { it.isCompleted })
        calls.forEach { it.cancel() }
    }

    @Test
    fun `agents' own asks - three open per objective and five in the app C3`() = runTest {
        val w = world()
        val agents = (1..4).map { n ->
            Samples.agent(id = "agt_$n", name = "Agent $n").copy(toolIds = setOf(w.b.writeFolder.id)).also { w.b.agents.upsert(it) }
        }
        suspend fun ask(agent: AgentProfile, root: String) =
            w.requester.ask(context(agent, "tsk_${agent.id.value}_$root", root), OperatorCapabilities.FOLDERS_WRITE, w.b.inFolder, "To save the report.")
        for (agent in agents.take(3)) assertIs<AskResult.Raised>(ask(agent, "tsk_root"))
        val fourth = assertIs<AskResult.Refused>(ask(agents[3], "tsk_root"))
        assertTrue("(3 for this objective)" in fourth.reason, fourth.reason)
        assertIs<AskResult.Raised>(ask(agents[3], "tsk_root2"))
        assertIs<AskResult.Raised>(w.requester.ask(context(agents[3], null, null), OperatorCapabilities.FOLDERS_READ, w.b.inFolder, "To read it."))
        val sixth = assertIs<AskResult.Refused>(
            w.requester.ask(context(agents[0], "tsk_six", "tsk_root3"), OperatorCapabilities.FOLDERS_READ, w.b.inFolder, "To read it."),
        )
        assertTrue("(5)" in sixth.reason, sixth.reason)
        assertEquals(5, w.b.requests.observePending().first().size)
    }

    @Test
    fun `an ask to use another app's screen in a routine's objective without the tick reaches nobody F12`() = runTest {
        val w = world()
        w.b.tasks.upsert(objective("tsk_routine", ObjectiveOptions(routineId = null, screenActionsAllowed = false, approvalTimeoutMinutes = 90)))
        val refused = w.requester.ask(context(w.b.agent, "tsk_x", "tsk_routine"), OperatorCapabilities.SCREEN_OPERATE, w.b.inNotes, "To tap the button.")
        assertEquals(AskResult.Refused(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED), refused)
        val unreadable = w.requester.ask(context(w.b.agent, "tsk_x", "tsk_missing"), OperatorCapabilities.SCREEN_READ, w.b.inNotes, "To read it.")
        assertEquals(AskResult.Refused(ObjectiveOptions.SCREEN_ACTIONS_NOT_ALLOWED), unreadable)
        assertTrue(w.b.allRequests().isEmpty(), "nothing reached the owner")
        // Other asks of that objective follow its own time-out.
        val raised = assertIs<AskResult.Raised>(w.requester.ask(context(w.b.agent, "tsk_x", "tsk_routine"), OperatorCapabilities.FOLDERS_WRITE, w.b.inFolder, "To save."))
        val request = w.b.requests.get(raised.requestId)!!
        assertEquals(90 * MINUTE, request.expiresAt - request.createdAt)
    }

    private fun objective(id: String, options: ObjectiveOptions) = TaskRecord(
        id = TaskId(id), rootId = TaskId(id), kind = TaskKind.OBJECTIVE, title = "Routine", instructions = "Do it.",
        requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING, createdAt = START, updatedAt = START, options = options,
    )
}
