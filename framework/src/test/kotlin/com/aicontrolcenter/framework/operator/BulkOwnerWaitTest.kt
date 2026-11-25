package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.MINUTE
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.START
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 6, D12: the bulk rule's 10-minute window does not run on while an agent waits for the
 * owner. 15 changes, an 11-minute wait for an approval, then 6 changes: the call asks the owner
 * (before, the 15 had dropped out of the window by then).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkOwnerWaitTest {
    private val agent = AgentId("agt_op")
    private val key = RecentFileChanges.Key(agent, TaskId("tsk_work"), TaskId("tsk_root"))
    private val waitKey = OwnerWaitLedger.Key(agent, TaskId("tsk_work"), TaskId("tsk_root"))

    @Test
    fun `fifteen changes, an eleven-minute owner wait, then six changes asks the owner`() {
        val ledger = OwnerWaitLedger()
        val counted = RecentFileChanges(ownerWaits = ledger)
        val plain = RecentFileChanges()
        for (changes in listOf(counted, plain)) assertTrue(changes.take(key, 15, START, approved = false))
        ledger.record(waitKey, START + MINUTE, START + 12 * MINUTE)
        val now = START + 12 * MINUTE + 1_000
        assertTrue(counted.wouldExceed(key, 6, now), "the wait does not age the 15 changes")
        assertEquals(15, counted.count(key, now))
        assertFalse(plain.wouldExceed(key, 6, now), "without the ledger they had dropped out")
        // Ten minutes of the agent's own time later, they drop out.
        assertFalse(counted.wouldExceed(key, 6, START + 21 * MINUTE + 1_000))
    }

    @Test
    fun `waits of another agent count only where they share the task or the objective`() {
        val ledger = OwnerWaitLedger()
        val changes = RecentFileChanges(ownerWaits = ledger)
        changes.take(key, 15, START, approved = false)
        // Another agent of another objective waited: no effect.
        ledger.record(OwnerWaitLedger.Key(AgentId("agt_other"), TaskId("tsk_x"), TaskId("tsk_other")), START + MINUTE, START + 12 * MINUTE)
        assertEquals(15, changes.count(key, START + 9 * MINUTE))
        assertEquals(0L, ledger.waitedMs(agent, TaskId("tsk_work"), TaskId("tsk_root"), START, START + 9 * MINUTE))
        // Overlapping waits of the same objective (a helper's) and of the agent itself are counted once.
        ledger.record(OwnerWaitLedger.Key(AgentId("agt_helper"), TaskId("tsk_y"), TaskId("tsk_root")), START + MINUTE, START + 7 * MINUTE)
        ledger.record(waitKey, START + 5 * MINUTE, START + 8 * MINUTE)
        assertEquals(7 * MINUTE, ledger.waitedMs(agent, TaskId("tsk_work"), TaskId("tsk_root"), START, START + 20 * MINUTE))
        assertTrue(changes.wouldExceed(key, 6, START + 12 * MINUTE))
        assertFalse(changes.wouldExceed(key, 6, START + 17 * MINUTE + 1))
    }

    @Test
    fun `a wait that goes on holds the window still, also while other calls look`() {
        val ledger = OwnerWaitLedger()
        val changes = RecentFileChanges(ownerWaits = ledger)
        changes.take(key, 15, START, approved = false)
        val handle = ledger.start(waitKey, START + MINUTE)
        // Another agent's look at minute 10.5 must not drop the waiting agent's changes.
        changes.count(RecentFileChanges.Key(AgentId("agt_other"), null, null), START + 10 * MINUTE + 30_000)
        ledger.finish(handle, START + 12 * MINUTE)
        assertTrue(changes.wouldExceed(key, 6, START + 12 * MINUTE + 1_000))
    }

    @Test
    fun `the broker writes every finished wait to the ledger`() = runTest {
        val b = BrokerFixture(this).setUp()
        val ledger = OwnerWaitLedger()
        val broker = PermissionBroker(b.facts, b.requests, b.grants, b.waiters, b.tasks, b.activity, b.ids, b.clock, ownerWaits = ledger)
        b.runningTask()
        b.grants.insert(PermissionGrant(GrantId("grt_1"), b.agent.id, b.deleteInFolder.capability, b.inFolder, GrantScope.ALWAYS, grantedAt = 1))
        val call = async { broker.authorize(b.deleteInFolder, b.assessment(RiskLevel.CONSEQUENTIAL), b.context()) }
        runCurrent()
        advanceTimeBy(11 * MINUTE)
        b.owner.approve(b.pending().id)
        runCurrent()
        assertIs<Authorization.AllowedByOwner>(call.await())
        val wait = ledger.all().single()
        assertEquals(OwnerWaitLedger.Key(b.agent.id, TaskId("tsk_work"), TaskId("tsk_root")), wait.key)
        assertEquals(11 * MINUTE, wait.until!! - wait.from)
    }
}
