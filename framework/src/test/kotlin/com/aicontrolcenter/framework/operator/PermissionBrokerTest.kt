package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestQueue
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.tasks.TaskKind
import com.aicontrolcenter.core.tasks.TaskRecord
import com.aicontrolcenter.core.tasks.TaskRequester
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.core.tasks.withPausableTimeout
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.agents.AgentTurnInfo
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.MINUTE
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.START
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionBrokerTest {
    private suspend fun TestScope.fixture(queue: (OwnerRequestQueue) -> OwnerRequestQueue = { it }) = BrokerFixture(this, queue).setUp()

    private fun Authorization.reason(): String = assertIs<Authorization.Denied>(this).reason

    private val modifying get() = RiskLevel.MODIFYING

    @Test
    fun `steps 1 to 3 refuse on facts read now, before anything is put to the owner`() = runTest {
        val f = fixture()
        suspend fun refused(spec: com.aicontrolcenter.core.tools.ToolSpec, assessment: com.aicontrolcenter.core.tools.ActionAssessment, expect: String) {
            val reason = f.broker.authorize(spec, assessment, f.context()).reason()
            assertTrue(expect in reason, "expected \"$expect\" in: $reason")
        }
        // The run's snapshot of the agent still has the tool; the stored agent does not.
        f.agents.upsert(f.agent.copy(toolIds = f.agent.toolIds - f.writeFolder.id))
        refused(f.writeFolder, f.assessment(modifying), "is not switched on for Operator")
        f.agents.upsert(f.agent)
        refused(f.writeFolder, f.assessment(modifying, constraint = CapabilityConstraint.None), "wrong kind of target")
        refused(f.writeFolder, f.assessment(modifying, constraint = CapabilityConstraint.Folder(FolderId("fld_gone"))), "removed")
        refused(f.tap, f.assessment(modifying, constraint = CapabilityConstraint.App("com.unknown.app")), "not an app the owner authorised")
        refused(f.tap, f.assessment(modifying, constraint = CapabilityConstraint.App(BrokerFixture.OWN_PACKAGE)), "protected app")
        refused(f.tap, f.assessment(modifying, constraint = CapabilityConstraint.App("com.android.settings")), "protected app")
        refused(f.home, f.assessment(modifying, constraint = f.inNotes, leadsTo = "com.example.launcher"), "hand control to another app")
        f.screenStopped.value = true
        refused(f.tap, f.assessment(modifying, constraint = f.inNotes), "pressed Stop")
        f.screenStopped.value = false
        f.settings.update { it.copy(accessibilityOperatorEnabled = false) }
        refused(f.tap, f.assessment(modifying, constraint = f.inNotes), "Screen control is switched off")
        f.settings.update { it.copy(accessibilityOperatorEnabled = true, localOnlyMode = true) }
        refused(f.readPage, f.assessment(constraint = CapabilityConstraint.None, dataGoesTo = "example.com"), "Local-only mode")
        f.settings.update { it.copy(localOnlyMode = false, operatorEnabled = false) }
        refused(f.writeFolder, f.assessment(modifying), "Phone operation is switched off")
        f.settings.update { it.copy(operatorEnabled = true) }
        val unknown = BrokerFixture.spec("made.up", Capability("made.up"), RiskLevel.SAFE)
        f.agents.upsert(f.agent.copy(toolIds = f.agent.toolIds + unknown.id))
        refused(unknown, f.assessment(constraint = CapabilityConstraint.None), "not a permission this app knows")
        f.agents.upsert(f.agent.copy(enabled = false))
        refused(f.writeFolder, f.assessment(modifying), "Operator is switched off")
        f.agents.delete(f.agent.id)
        refused(f.writeFolder, f.assessment(modifying), "no longer exists")
        assertTrue(f.allRequests().isEmpty(), "nothing the broker refuses is put to the owner")
    }

    @Test
    fun `the order holds - a refusal before the grant check, the grant check before the approval`() = runTest {
        val f = fixture()
        // A consequential call on a removed folder is refused, not asked about.
        f.broker.authorize(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL, CapabilityConstraint.Folder(FolderId("fld_gone"))), f.context()).reason()
        assertTrue(f.allRequests().isEmpty())
        // Without a grant, a consequential call asks for the permission first, then for this one action.
        val call = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL, summary = "Delete \"2024.csv\" in Reports", details = listOf("1 file, 3 KB")))
        val permission = f.pending()
        assertEquals(OwnerRequestKind.PERMISSION, permission.kind)
        f.owner.grant(permission.id, GrantScope.TASK)
        runCurrent()
        val approval = f.pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
        f.owner.approve(approval.id)
        assertEquals(Authorization.AllowedByOwner(listOf(permission.id, approval.id)), call.await())
    }

    @Test
    fun `a capability granted by default, or a grant that covers the call, needs no question`() = runTest {
        val f = fixture()
        assertEquals(Authorization.Allowed, f.broker.authorize(f.readWorkspace, f.assessment(constraint = CapabilityConstraint.None), f.context()))
        assertEquals(Authorization.Allowed, f.broker.authorize(f.clockTool, f.assessment(constraint = CapabilityConstraint.None), f.context(task = null)))
        f.grants.insert(PermissionGrant(GrantId("grt_task"), f.agent.id, OperatorCapabilities.FOLDERS_READ, f.inFolder, GrantScope.TASK, TaskId("tsk_work"), grantedAt = 1))
        f.grants.insert(PermissionGrant(GrantId("grt_always"), f.agent.id, OperatorCapabilities.FOLDERS_WRITE, f.inFolder, GrantScope.ALWAYS, grantedAt = 1))
        assertEquals(Authorization.Allowed, f.broker.authorize(f.readFolder, f.assessment(), f.context()))
        assertEquals(Authorization.Allowed, f.broker.authorize(f.writeFolder, f.assessment(modifying), f.context(task = null)))
        assertTrue(f.allRequests().isEmpty())
        // The TASK grant covers its own task only.
        val elsewhere = f.start(f.readFolder, f.assessment(), f.context(task = "tsk_other"))
        assertEquals(TaskId("tsk_other"), f.pending().taskId)
        elsewhere.cancel()
    }

    @Test
    fun `a missing grant raises a permission request with the call's preview, and once allows exactly that call`() = runTest {
        val f = fixture()
        val call = f.start(f.writeFolder, f.assessment(modifying, details = listOf("12 bytes")), f.context(conversation = "cnv_1"))
        assertTrue(call.isActive, "the call waits for the owner")
        val request = f.pending()
        assertEquals(OwnerRequestKind.PERMISSION, request.kind)
        assertEquals(f.agent.id, request.agentId)
        assertEquals(f.writeFolder.id, request.toolId)
        assertEquals(OperatorCapabilities.FOLDERS_WRITE, request.capability)
        assertEquals(f.inFolder, request.constraint)
        assertEquals(RiskLevel.MODIFYING, request.risk)
        assertEquals("Write \"notes.txt\" in Reports", request.summary)
        assertTrue(request.details.first().startsWith("Needs the permission \"Change a shared folder\""), request.details.toString())
        assertEquals("12 bytes", request.details.last())
        assertEquals(TaskId("tsk_work"), request.taskId)
        assertEquals(TaskId("tsk_root"), request.rootId)
        assertEquals("cnv_1", request.conversationId?.value)
        assertEquals("Operator", request.agentName)
        assertEquals("Title of folders.write", request.toolTitle)
        assertEquals("Reports", request.targetLabel)
        assertEquals(listOf("agt_director"), request.delegationChain.map { it.value })
        assertEquals(request.createdAt + 30 * MINUTE, request.expiresAt)
        assertNull(request.reason, "a tool call carries no agent text")

        f.owner.grant(request.id, GrantScope.ONCE)
        assertEquals(Authorization.AllowedByOwner(listOf(request.id)), call.await())
        assertNotNull(f.grants.findByRequest(request.id)?.consumedAt, "used up by the call the owner saw")
        // A ONCE grant covers nothing else: the same call again is asked about again.
        val again = f.start(f.writeFolder, f.assessment(modifying))
        assertNotEquals(request.id, f.pending().id)
        again.cancel()
    }

    @Test
    fun `answered for the task or always, the grant covers later calls`() = runTest {
        val f = fixture()
        val first = f.start(f.writeFolder, f.assessment(modifying))
        f.owner.grant(f.pending().id, GrantScope.TASK)
        assertIs<Authorization.AllowedByOwner>(first.await())
        assertEquals(Authorization.Allowed, f.broker.authorize(f.writeFolder, f.assessment(modifying), f.context()))
        val otherTask = f.start(f.writeFolder, f.assessment(modifying), f.context(task = "tsk_next"))
        f.owner.grant(f.pending().id, GrantScope.ALWAYS)
        assertIs<Authorization.AllowedByOwner>(otherTask.await())
        assertEquals(Authorization.Allowed, f.broker.authorize(f.writeFolder, f.assessment(modifying), f.context(task = null)), "always covers a chat too")
        assertEquals(listOf(GrantScope.TASK, GrantScope.ALWAYS), f.grants.observeAll().first().map { it.scope })
    }

    @Test
    fun `a consequential call asks for approval every time, whatever was granted, and an approval grants nothing`() = runTest {
        val f = fixture()
        f.grants.insert(PermissionGrant(GrantId("grt_always"), f.agent.id, OperatorCapabilities.FOLDERS_WRITE, f.inFolder, GrantScope.ALWAYS, grantedAt = 1))
        repeat(2) { round ->
            val call = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL, summary = "Delete \"$round.csv\" in Reports", details = listOf("1 file", "3 KB"), dataGoesTo = "the trash"))
            val approval = f.pending()
            assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
            assertEquals("Delete \"$round.csv\" in Reports", approval.summary)
            assertEquals(listOf("1 file", "3 KB"), approval.details, "the exact preview")
            assertEquals("the trash", approval.dataGoesTo)
            assertTrue(approval.offeredScopes.isEmpty())
            assertIs<DecideResult.NotAccepted>(f.owner.grant(approval.id, GrantScope.ALWAYS), "an approval is never answered with \"always\"")
            assertIs<DecideResult.NotAccepted>(f.owner.grant(approval.id, GrantScope.TASK))
            f.owner.approve(approval.id)
            assertEquals(Authorization.AllowedByOwner(listOf(approval.id)), call.await())
        }
        assertEquals(listOf(GrantId("grt_always")), f.grants.observeAll().first().map { it.id }, "approving stored no grant")
        // The declared risk counts even when the call is assessed lower.
        val declared = f.start(f.deleteInFolder, f.assessment(RiskLevel.SAFE))
        assertEquals(OwnerRequestKind.APPROVAL, f.pending().kind)
        declared.cancel()
    }

    @Test
    fun `a refusal passes the owner's note on as quoted data, and an unanswered request expires`() = runTest {
        val f = fixture()
        val refused = f.start(f.writeFolder, f.assessment(modifying))
        f.owner.deny(f.pending().id, "  Not that folder; use Drafts.  ")
        val reason = refused.await().reason()
        assertTrue("did not give Operator the permission \"Change a shared folder\"" in reason, reason)
        assertTrue("The owner's note, quoted: \"Not that folder; use Drafts.\"" in reason, reason)

        val unanswered = f.start(f.writeFolder, f.assessment(modifying))
        val request = f.pending()
        advanceTimeBy(30 * MINUTE - 1)
        runCurrent()
        assertTrue(unanswered.isActive, "still waiting just before the expiry")
        advanceTimeBy(1)
        runCurrent()
        val expired = unanswered.await().reason()
        assertTrue("did not answer within 30 minutes" in expired, expired)
        assertEquals(OwnerRequestStatus.EXPIRED, f.requests.get(request.id)?.status)
        assertIs<DecideResult.AlreadyClosed>(f.owner.grant(request.id, GrantScope.ALWAYS), "too late: nothing is granted")
        assertTrue(f.grants.observeAll().first().isEmpty())

        // The time allowed is read when a request is raised.
        f.settings.update { it.copy(approvalTimeoutMinutes = 2) }
        val short = f.start(f.writeFolder, f.assessment(modifying))
        assertEquals(2 * MINUTE, f.pending().let { it.expiresAt - it.createdAt })
        short.cancel()
    }

    /** A queue whose waiter sees answers only when [showAnswers], and whose closing waits for [closing]: the waiter's timer and the owner meet. */
    private class Meeting(private val inner: OwnerRequestQueue) : OwnerRequestQueue by inner {
        @Volatile var showAnswers = true
        @Volatile var closing: CompletableDeferred<Unit>? = null

        override fun observe(id: RequestId): Flow<OwnerRequest?> = inner.observe(id).filter { showAnswers || it?.status?.isPending != false }

        override suspend fun close(id: RequestId, status: OwnerRequestStatus, at: Long, note: String?): OwnerRequest? {
            closing?.await()
            return inner.close(id, status, at, note)
        }
    }

    @Test
    fun `race - the owner answers as the request expires - one of them wins, and the call follows the winner`() = runTest {
        var meeting: Meeting? = null
        val f = fixture { inner -> Meeting(inner).also { meeting = it } }
        val m = meeting!!
        // The owner's yes is stored a moment before the expiry, but the waiter's timer fires before it sees it: the yes stands.
        m.showAnswers = false
        val answeredFirst = f.start(f.writeFolder, f.assessment(modifying))
        val first = f.pending()
        advanceTimeBy(30 * MINUTE - 1)
        assertIs<DecideResult.Decided>(f.owner.grant(first.id, GrantScope.ALWAYS))
        advanceTimeBy(1)
        runCurrent()
        assertEquals(Authorization.AllowedByOwner(listOf(first.id)), answeredFirst.await())
        assertEquals(OwnerRequestStatus.APPROVED, f.requests.get(first.id)?.status)

        // The timer fires first and, while it closes the request, the owner answers at the expiry: too late, and the call is refused.
        val gate = CompletableDeferred<Unit>()
        m.closing = gate
        val expiresFirst = f.start(f.tap, f.assessment(modifying, constraint = f.inNotes))
        val second = f.pending()
        advanceTimeBy(30 * MINUTE)
        runCurrent()
        assertIs<DecideResult.Expired>(f.owner.grant(second.id, GrantScope.ALWAYS), "decided at the expiry: expired, not granted")
        gate.complete(Unit)
        runCurrent()
        assertTrue("did not answer within 30 minutes" in expiresFirst.await().reason())
        assertEquals(OwnerRequestStatus.EXPIRED, f.requests.get(second.id)?.status)
        assertNull(f.grants.findByRequest(second.id), "nothing was granted")
    }

    @Test
    fun `race - two calls waiting on the same capability each have their own request, and a once answer serves only its own call`() = runTest {
        val f = fixture()
        val first = f.start(f.writeFolder, f.assessment(modifying, summary = "Write a.txt"))
        val second = f.start(f.writeFolder, f.assessment(modifying, summary = "Write b.txt"))
        val (a, b) = f.requests.observePending().first()
        assertEquals(listOf("Write a.txt", "Write b.txt"), listOf(a.summary, b.summary))
        f.owner.grant(b.id, GrantScope.ONCE)
        assertEquals(Authorization.AllowedByOwner(listOf(b.id)), second.await())
        assertTrue(first.isActive, "the other call still waits for its own answer")
        assertNull(f.grants.consumeOnceFor(a.id, f.clock.now()), "the other request has no grant to use")
        assertNull(f.grants.consumeOnceFor(b.id, f.clock.now()), "used up by its own call, once")
        f.owner.grant(a.id, GrantScope.ONCE)
        assertEquals(Authorization.AllowedByOwner(listOf(a.id)), first.await())
    }

    @Test
    fun `a grant withdrawn between the owner's yes and its use allows nothing`() = runTest {
        val f = fixture()
        val once = f.start(f.writeFolder, f.assessment(modifying))
        val onceRequest = f.pending()
        val granted = assertIs<DecideResult.Decided>(f.owner.grant(onceRequest.id, GrantScope.ONCE)).grant!!
        f.grants.revoke(granted.id, f.clock.now())
        assertTrue("withdrawn before it could be used" in once.await().reason())

        val task = f.start(f.writeFolder, f.assessment(modifying))
        val taskGrant = assertIs<DecideResult.Decided>(f.owner.grant(f.pending().id, GrantScope.TASK)).grant!!
        f.grants.revoke(taskGrant.id, f.clock.now())
        assertTrue("withdrawn before it could be used" in task.await().reason())
    }

    @Test
    fun `cancelling the waiting call withdraws its request and releases a once grant it did not use`() = runTest {
        val f = fixture()
        f.runningTask()
        val call = f.start(f.writeFolder, f.assessment(modifying))
        val request = f.pending()
        call.cancel()
        runCurrent()
        val closed = f.requests.get(request.id)!!
        assertEquals(OwnerRequestStatus.CANCELLED, closed.status)
        assertEquals(PermissionBroker.CANCELLED_NOTE, closed.decisionNote)
        assertEquals(TaskStatus.RUNNING, f.tasks.get(TaskId("tsk_work"))?.status)
        assertIs<DecideResult.AlreadyClosed>(f.owner.grant(request.id, GrantScope.ALWAYS))

        // The owner answers "once" just as the call is stopped: the grant is left unused, so it is released.
        val stopped = f.start(f.writeFolder, f.assessment(modifying))
        val answered = f.pending()
        f.owner.grant(answered.id, GrantScope.ONCE)
        stopped.cancel()
        runCurrent()
        assertEquals(OwnerRequestStatus.APPROVED, f.requests.get(answered.id)?.status)
        assertNotNull(f.grants.findByRequest(answered.id)?.revokedAt, "released")
        assertTrue(f.grants.observeAll().first().isEmpty())
    }

    @Test
    fun `after every wait the facts are read again`() = runTest {
        val f = fixture()
        // The folder is removed while the owner is asked.
        val write = f.start(f.writeFolder, f.assessment(modifying))
        f.folders.delete(f.folder.id)
        f.owner.grant(f.pending().id, GrantScope.ALWAYS)
        assertTrue("removed" in write.await().reason())
        f.folders.upsert(f.folder)
        // The tool is switched off while its approval is pending (the permission is stored now).
        val delete = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL))
        assertEquals(OwnerRequestKind.APPROVAL, f.pending().kind)
        f.agents.upsert(f.agent.copy(toolIds = f.agent.toolIds - f.deleteInFolder.id))
        f.owner.approve(f.pending().id)
        assertTrue("not switched on" in delete.await().reason())
        f.agents.upsert(f.agent)
        // The permission the call relied on is revoked while its approval is pending.
        val again = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL))
        f.grants.revokeAllFor(f.agent.id, f.clock.now())
        f.owner.approve(f.pending().id)
        assertTrue("withdrawn while the owner was being asked to approve" in again.await().reason())
        // Stop pressed while a screen call waits.
        val tap = f.start(f.tap, f.assessment(modifying, constraint = f.inNotes))
        f.screenStopped.value = true
        f.owner.grant(f.pending().id, GrantScope.ONCE)
        assertTrue("pressed Stop" in tap.await().reason())
    }

    @Test
    fun `while it waits the task is WAITING_FOR_OWNER, and afterwards its deadline is later by the time waited`() = runTest {
        val f = fixture()
        val lead = f.runningTask("tsk_lead", deadlineAt = START + 10 * MINUTE, status = TaskStatus.WAITING_FOR_AGENT)
        val task = f.runningTask("tsk_work", deadlineAt = START + MINUTE, parent = "tsk_lead")
        val call = f.start(f.writeFolder, f.assessment(modifying))
        val waiting = f.tasks.get(task.id)!!
        assertEquals(TaskStatus.WAITING_FOR_OWNER, waiting.status)
        assertEquals("Waiting for the owner's permission: Write \"notes.txt\" in Reports", waiting.statusDetail)
        advanceTimeBy(5 * MINUTE)
        f.owner.grant(f.pending().id, GrantScope.ONCE)
        assertIs<Authorization.AllowedByOwner>(call.await())
        val after = f.tasks.get(task.id)!!
        assertEquals(TaskStatus.RUNNING, after.status)
        assertEquals("Operator is working.", after.statusDetail)
        assertEquals(START + 6 * MINUTE, after.deadlineAt)
        assertEquals(START + 15 * MINUTE, f.tasks.get(lead.id)?.deadlineAt, "the delegating task waited as long")
        assertEquals(TaskStatus.WAITING_FOR_AGENT, f.tasks.get(lead.id)?.status)
    }

    @Test
    fun `an objective keeps its status while the Director waits, and a task the owner paused stays paused`() = runTest {
        val f = fixture()
        val root = TaskRecord(
            id = TaskId("tsk_root"), rootId = TaskId("tsk_root"), kind = TaskKind.OBJECTIVE, title = "Objective", instructions = "Do it.",
            requestedBy = TaskRequester.Owner, status = TaskStatus.RUNNING, createdAt = START, updatedAt = START, deadlineAt = START + 30 * MINUTE,
        )
        f.tasks.upsert(root)
        val planning = f.start(f.writeFolder, f.assessment(modifying), f.context(task = "tsk_root"))
        assertEquals(TaskStatus.RUNNING, f.tasks.get(root.id)?.status)
        advanceTimeBy(2 * MINUTE)
        f.owner.grant(f.pending().id, GrantScope.ONCE)
        planning.await()
        assertEquals(START + 32 * MINUTE, f.tasks.get(root.id)?.deadlineAt, "its time moved with the wait")

        f.runningTask()
        val paused = f.start(f.writeFolder, f.assessment(modifying))
        f.tasks.update(TaskId("tsk_work")) { it.copy(status = TaskStatus.PAUSED) }
        paused.cancel()
        runCurrent()
        assertEquals(TaskStatus.PAUSED, f.tasks.get(TaskId("tsk_work"))?.status)
    }

    @Test
    fun `the wait does not count against the limits around the call`() = runTest {
        val f = fixture()
        val run = async {
            withPausableTimeout(MINUTE, testTimeSource) {
                delay(30_000)
                f.broker.authorize(f.writeFolder, f.assessment(modifying), f.context()).also { delay(29_000) }
            }
        }
        advanceTimeBy(30_001)
        runCurrent()
        val request = f.pending()
        advanceTimeBy(5 * MINUTE)
        f.owner.grant(request.id, GrantScope.ONCE)
        advanceTimeBy(29_001)
        runCurrent()
        assertEquals(Authorization.AllowedByOwner(listOf(request.id)), run.await(), "one minute of the run's own time, five of the owner's")
    }

    @Test
    fun `the request, the owner's decision and what the call did with it are logged`() = runTest {
        val f = fixture()
        val call = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL, summary = "Delete x"))
        val permission = f.pending()
        f.owner.grant(permission.id, GrantScope.TASK)
        runCurrent()
        val approval = f.pending()
        f.owner.deny(approval.id, "No.")
        assertTrue("did not approve this action" in call.await().reason())
        val tool = "Title of folders.delete"
        assertEquals(
            listOf("Permission asked: $tool", "Owner gave permission for this task: $tool", "Permission given for this task: $tool"),
            f.events(ActivityType.PERMISSION).map { it.title },
        )
        assertEquals(
            listOf("Approval asked: $tool", "Owner did not approve: $tool", "Refused by the owner: $tool"),
            f.events(ActivityType.APPROVAL).map { it.title },
        )
        assertTrue(f.events(ActivityType.PERMISSION).all { it.data?.get("requestId")?.jsonPrimitive?.content == permission.id.value })
        assertTrue(f.events(ActivityType.APPROVAL).all { it.data?.get("requestId")?.jsonPrimitive?.content == approval.id.value })
        assertTrue(f.events(ActivityType.APPROVAL).all { it.taskId == TaskId("tsk_work") && it.agentId == f.agent.id })
    }

    @Test
    fun `the run is told about the wait, and tools the framework offered the run count as switched on`() = runTest {
        val f = fixture()
        val seen = ArrayList<String>()
        val waits = object : OwnerWaits() {
            override suspend fun waiting(request: OwnerRequest) {
                seen += "waiting ${request.status}"
            }

            override suspend fun answered(request: OwnerRequest) {
                seen += "answered ${request.status}"
            }
        }
        val offered = BrokerFixture.spec("folders.list", OperatorCapabilities.FOLDERS_READ, RiskLevel.SAFE)
        assertTrue("not switched on" in f.broker.authorize(offered, f.assessment(), f.context()).reason())
        val call = async {
            withContext(AgentTurnInfo(ModelId("mdl_1"), null, null, extraToolIds = setOf(offered.id)) + waits) {
                f.broker.authorize(offered, f.assessment(), f.context())
            }
        }
        runCurrent()
        f.owner.grant(f.pending().id, GrantScope.ONCE)
        assertIs<Authorization.AllowedByOwner>(call.await())
        assertEquals(listOf("waiting PENDING", "answered APPROVED"), seen)
    }
}
