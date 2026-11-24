package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerDecision
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tasks.TaskStatus
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.MINUTE
import com.aicontrolcenter.testkit.FakeSharedFolders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review 5 fixes on the life of a question to the owner: who still waits for it (INT-2, INT-4),
 * an answer that covers another question (INT-5), withdrawals that close what they are about
 * (INT-11), and no asking again right after a refusal (R4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestLifecycleTest {
    private suspend fun TestScope.fixture() = BrokerFixture(this).setUp()

    private fun Authorization.reason(): String = assertIs<Authorization.Denied>(this).reason

    private val modifying = RiskLevel.MODIFYING

    /** A request as an earlier run of the app left it: pending in the store, with no waiter in this process. */
    private suspend fun BrokerFixture.leftByEarlierRun(kind: OwnerRequestKind = OwnerRequestKind.APPROVAL): OwnerRequest {
        val now = clock.now()
        val request = OwnerRequest(
            id = RequestId("req_dead"), kind = kind, agentId = agent.id, toolId = deleteInFolder.id, capability = OperatorCapabilities.FOLDERS_WRITE,
            constraint = inFolder, risk = RiskLevel.CONSEQUENTIAL, summary = "Delete \"old.txt\" in Reports", createdAt = now - MINUTE,
            expiresAt = now + 4 * 60 * MINUTE, agentName = agent.name, toolTitle = deleteInFolder.title, taskId = TaskId("tsk_work"),
        )
        requests.create(request)
        return request
    }

    /**
     * The request store as a withdrawal sees it when an answer crosses it: the first time the
     * withdrawal lists the pending requests (its grants are revoked and its target is gone by
     * then), [crossing] is written first, as an answer that passed its checks just before would be.
     */
    private class CrossedBy(private val store: OwnerRequestRepository, private val crossing: suspend () -> Unit) : OwnerRequestRepository by store {
        private var crossed = false

        override fun observePending(): Flow<List<OwnerRequest>> = flow {
            if (!crossed) {
                crossed = true
                crossing()
            }
            emitAll(store.observePending())
        }
    }

    // ---- INT-2: a request whose waiter is gone can never be answered ----

    @Test
    fun `a request left pending by an earlier run is not offered, and answering it closes it as no longer waiting`() = runTest {
        val f = fixture()
        val dead = f.leftByEarlierRun()
        assertTrue(f.owner.pending.first().isEmpty(), "the owner is not shown a question nothing waits for")

        val answered = assertIs<DecideResult.AlreadyClosed>(f.owner.approve(dead.id), "an approval of it is never recorded")
        assertEquals(OwnerRequestStatus.CANCELLED, answered.request.status)
        assertEquals(DefaultOwnerRequests.NOT_WAITING_NOTE, answered.request.decisionNote)
        assertEquals(OwnerRequestStatus.CANCELLED, f.requests.get(dead.id)?.status)
        assertTrue(f.log.all.none { it.title.startsWith("Owner approved") }, "no approval is logged")
        val logged = f.log.all.single { it.title.startsWith("Request closed, not answered") }
        assertEquals(dead.id.value, logged.data?.get("requestId")?.jsonPrimitive?.content)
    }

    @Test
    fun `the approval screen's check closes a request nothing waits for, and leaves a live one open`() = runTest {
        val f = fixture()
        val dead = f.leftByEarlierRun(OwnerRequestKind.PERMISSION)
        val closed = f.owner.closeIfAbandoned(dead.id)
        assertEquals(OwnerRequestStatus.CANCELLED, closed?.status)
        assertEquals(DefaultOwnerRequests.NOT_WAITING_NOTE, closed?.decisionNote)

        val live = f.start(f.writeFolder, f.assessment(modifying))
        val request = f.pending()
        assertEquals(OwnerRequestStatus.PENDING, f.owner.closeIfAbandoned(request.id)?.status)
        assertEquals(listOf(request.id), f.owner.pending.first().map { it.id })
        f.owner.grant(request.id, GrantScope.ONCE)
        assertEquals(Authorization.AllowedByOwner(listOf(request.id)), live.await())
        assertTrue(f.waiters.waiting.value.isEmpty(), "the waiter is gone once its request is closed")
        assertNull(f.owner.closeIfAbandoned(RequestId("req_missing")))
    }

    // ---- INT-4: start-up withdraws only what nothing waits for ----

    @Test
    fun `start-up withdraws the requests of an earlier run, never one a live call raised before start-up finished`() = runTest {
        val f = fixture()
        val dead = f.leftByEarlierRun()
        val fresh = f.start(f.writeFolder, f.assessment(modifying))
        val live = f.requests.observePending().first().single { it.id != dead.id }
        val hooks = OperatorTaskHooks(f.requests, f.grants, f.waiters, f.activity, f.clock)

        assertEquals(listOf(dead.id), hooks.startUp().map { it.id })
        assertEquals(OwnerRequestStatus.CANCELLED, f.requests.get(dead.id)?.status)
        assertEquals(OperatorTaskHooks.STOPPED_NOTE, f.requests.get(dead.id)?.decisionNote)
        assertEquals(OwnerRequestStatus.PENDING, f.requests.get(live.id)?.status, "the call that raised it still waits")
        f.owner.grant(live.id, GrantScope.ONCE)
        assertEquals(Authorization.AllowedByOwner(listOf(live.id)), fresh.await())
        // INT-8: the withdrawal is logged on its own, with its request id and whose it was.
        val logged = f.log.all.single { it.title.startsWith("Request withdrawn at start-up") }
        assertEquals(dead.id.value, logged.data?.get("requestId")?.jsonPrimitive?.content)
        assertEquals(f.agent.id, logged.agentId)
        assertEquals(dead.taskId, logged.taskId)
    }

    @Test
    fun `a task that ends withdraws its agent's asks, each logged with its request id`() = runTest {
        val f = fixture()
        val requester = DefaultCapabilityRequester(f.facts, f.requests, f.grants, f.waiters, f.activity, f.ids, f.clock)
        val ask = assertIs<AskResult.Raised>(requester.ask(f.context(task = "tsk_work"), OperatorCapabilities.WEB_READ, CapabilityConstraint.None, "To read reviews.")).requestId
        val task = f.runningTask().copy(status = TaskStatus.CANCELLED)
        val hooks = OperatorTaskHooks(f.requests, f.grants, f.waiters, f.activity, f.clock)

        hooks.taskEnded(task)

        assertEquals(OwnerRequestStatus.CANCELLED, f.requests.get(ask)?.status)
        // INT-8: its own entry, naming the request, the agent and the task; the summary lists the ids too.
        val logged = f.log.all.single { it.title.startsWith("Request withdrawn, its task ended") }
        assertEquals(ask.value, logged.data?.get("requestId")?.jsonPrimitive?.content)
        assertEquals(f.agent.id, logged.agentId)
        assertEquals(task.id, logged.taskId)
        assertEquals(task.rootId, logged.rootTaskId)
        val summary = f.log.all.single { it.title.startsWith("Task permissions ended") }
        assertEquals(listOf(ask.value), summary.data?.get("requestIds")?.jsonArray?.map { it.jsonPrimitive.content })
    }

    // ---- INT-5: an answer for the task or always settles the other questions it covers ----

    @Test
    fun `always on one request settles the other pending requests it covers, whose calls then go on`() = runTest {
        val f = fixture()
        val first = f.start(f.writeFolder, f.assessment(modifying, summary = "Write a.txt"), f.context(task = "tsk_a"))
        val second = f.start(f.writeFolder, f.assessment(modifying, summary = "Write b.txt"), f.context(task = "tsk_b"))
        val chat = f.start(f.writeFolder, f.assessment(modifying, summary = "Write c.txt"), f.context(task = null, conversation = "cnv_1"))
        val otherFolder = f.start(f.readFolder, f.assessment(RiskLevel.SAFE, summary = "Read d.txt"))
        val asked = DefaultCapabilityRequester(f.facts, f.requests, f.grants, f.waiters, f.activity, f.ids, f.clock)
            .ask(f.context(task = "tsk_c"), OperatorCapabilities.FOLDERS_WRITE, f.inFolder, "To tidy the reports.")
        val ask = assertIs<AskResult.Raised>(asked).requestId
        val (a, b, c) = f.requests.observePending().first().filter { it.toolId == f.writeFolder.id }

        assertIs<DecideResult.Decided>(f.owner.grant(a.id, GrantScope.ALWAYS))
        runCurrent()

        assertEquals(Authorization.AllowedByOwner(listOf(a.id)), first.await())
        assertEquals(Authorization.AllowedByOwner(listOf(b.id)), second.await(), "the other task's call goes on")
        assertEquals(Authorization.AllowedByOwner(listOf(c.id)), chat.await(), "and the chat's")
        val settled = f.requests.get(b.id)!!
        assertEquals(OwnerRequestStatus.APPROVED, settled.status)
        assertEquals(GrantScope.ONCE, settled.decisionScope, "for its own one call: the lasting grant is the one the owner gave")
        assertTrue(settled.decisionNote!!.startsWith("Already answered: you allowed this always"), settled.decisionNote)
        assertEquals(OwnerRequestStatus.CANCELLED, f.requests.get(ask)?.status, "the agent's own ask is answered by the grant")
        assertEquals(1, f.grants.observeAll().first().count { it.scope == GrantScope.ALWAYS }, "no second lasting grant")
        assertTrue(otherFolder.isActive, "a request for another permission still waits")
        assertEquals(listOf(f.readFolder.id), f.owner.pending.first().map { it.toolId })
        assertEquals(3, f.log.all.count { it.title.startsWith("Settled by your answer") })
        otherFolder.cancel()
    }

    @Test
    fun `for the task settles only that task's requests, and once settles nothing`() = runTest {
        val f = fixture()
        val first = f.start(f.writeFolder, f.assessment(modifying, summary = "Write a.txt"), f.context(task = "tsk_a"))
        val sameTask = f.start(f.writeFolder, f.assessment(modifying, summary = "Write b.txt"), f.context(task = "tsk_a"))
        val otherTask = f.start(f.writeFolder, f.assessment(modifying, summary = "Write c.txt"), f.context(task = "tsk_b"))
        val (a, b, c) = f.requests.observePending().first()
        f.owner.grant(a.id, GrantScope.TASK)
        runCurrent()
        assertIs<Authorization.AllowedByOwner>(first.await())
        assertEquals(Authorization.AllowedByOwner(listOf(b.id)), sameTask.await())
        assertTrue(otherTask.isActive)
        f.owner.grant(c.id, GrantScope.ONCE)
        assertIs<Authorization.AllowedByOwner>(otherTask.await())

        val x = f.start(f.writeFolder, f.assessment(modifying, summary = "Write x.txt"), f.context(task = "tsk_x"))
        val y = f.start(f.writeFolder, f.assessment(modifying, summary = "Write y.txt"), f.context(task = "tsk_x"))
        val (rx, _) = f.requests.observePending().first()
        f.owner.grant(rx.id, GrantScope.ONCE)
        runCurrent()
        assertIs<Authorization.AllowedByOwner>(x.await())
        assertTrue(y.isActive, "a once answer is for its own call only")
        y.cancel()
    }

    @Test
    fun `an always never answers an action waiting for approval, which is still put to the owner`() = runTest {
        val f = fixture()
        // A delete allowed for this one call now waits for its approval; a write in another task asks for the same permission.
        val delete = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL, summary = "Delete \"old.txt\" in Reports"), f.context(task = "tsk_a"))
        f.owner.grant(f.pending().id, GrantScope.ONCE)
        runCurrent()
        val approval = f.pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
        val write = f.start(f.writeFolder, f.assessment(modifying, summary = "Write b.txt"), f.context(task = "tsk_b"))
        val permission = f.requests.observePending().first().single { it.kind == OwnerRequestKind.PERMISSION }

        assertIs<DecideResult.Decided>(f.owner.grant(permission.id, GrantScope.ALWAYS))
        runCurrent()

        assertIs<Authorization.AllowedByOwner>(write.await())
        assertEquals(OwnerRequestStatus.PENDING, f.requests.get(approval.id)?.status, "the delete still waits for its own yes")
        assertTrue(delete.isActive)
        assertEquals(listOf(approval.id), f.owner.pending.first().map { it.id })
        f.owner.deny(approval.id)
        assertIs<Authorization.Denied>(delete.await())
    }

    @Test
    fun `a direct grant in Tools settles the waiting calls it covers`() = runTest {
        val f = fixture()
        val waiting = f.start(f.writeFolder, f.assessment(modifying))
        val request = f.pending()
        val grants = DefaultOwnerGrants(f.grants, f.agents, f.capabilities, f.folders, f.apps, { f.protectedPackages }, f.requests, f.waiters, f.activity, f.ids, f.clock)
        assertIs<OwnerResult.Done<*>>(grants.grant(f.agent.id, OperatorCapabilities.FOLDERS_WRITE, f.inFolder))
        runCurrent()
        assertEquals(Authorization.AllowedByOwner(listOf(request.id)), waiting.await())
        assertTrue(f.requests.get(request.id)!!.decisionNote!!.endsWith("in Tools."))
    }

    // ---- INT-11: withdrawing closes the related requests, and an answer never gives back what was withdrawn ----

    @Test
    fun `withdrawing an app closes its pending requests, and its always cannot come back when it is authorised again`() = runTest {
        val f = fixture()
        val apps = DefaultOwnerOperatorApps(f.apps, f.grants, { f.protectedPackages }, f.requests, f.activity, f.clock)
        val tap = f.start(f.tap, f.assessment(modifying, f.inNotes, "Tap \"New note\" in Notes"))
        val request = f.pending()

        assertTrue(apps.withdraw(f.app.packageName))
        runCurrent()
        val closed = f.requests.get(request.id)!!
        assertEquals(OwnerRequestStatus.CANCELLED, closed.status)
        assertEquals("You withdrew Notes from screen control, so this was not done.", closed.decisionNote)
        assertTrue("withdrew Notes" in tap.await().reason())
        assertIs<DecideResult.AlreadyClosed>(f.owner.grant(request.id, GrantScope.ALWAYS))

        assertIs<OwnerResult.Done<*>>(apps.authorise(f.app.packageName, f.app.label, f.app.signingCertSha256))
        val query = GrantQuery(f.agent.id, OperatorCapabilities.SCREEN_OPERATE, f.inNotes, TaskId("tsk_other"), f.clock.now())
        assertTrue(f.grants.findMatching(query).isEmpty(), "nothing was granted for the withdrawn app")
        val later = f.start(f.tap, f.assessment(modifying, f.inNotes, "Tap \"New note\" in Notes"), f.context(task = "tsk_other"))
        assertTrue(later.isActive, "a later tap asks the owner again")
        assertEquals(1, f.requests.observePending().first().size)
        later.cancel()
    }

    @Test
    fun `an answer that races a withdrawal never grants access to the withdrawn app or folder`() = runTest {
        val f = fixture()
        val tap = f.start(f.tap, f.assessment(modifying, f.inNotes, "Tap \"New note\" in Notes"))
        val tapRequest = f.pending()
        // Withdrawn straight in the store, as if the owner's withdrawal had not reached the requests yet.
        f.apps.delete(f.app.packageName)
        val refused = assertIs<DecideResult.AlreadyClosed>(f.owner.grant(tapRequest.id, GrantScope.ALWAYS))
        assertEquals(OwnerRequestStatus.CANCELLED, refused.request.status)
        assertTrue("no longer authorised for screen control" in refused.request.decisionNote!!, refused.request.decisionNote)
        assertNull(f.grants.findByRequest(tapRequest.id))
        assertIs<Authorization.Denied>(tap.await())

        val write = f.start(f.writeFolder, f.assessment(modifying))
        val writeRequest = f.pending()
        f.folders.delete(f.folder.id)
        val gone = assertIs<DecideResult.AlreadyClosed>(f.owner.grant(writeRequest.id, GrantScope.ALWAYS))
        assertEquals("The folder was removed, so this was not allowed.", gone.request.decisionNote)
        assertIs<Authorization.Denied>(write.await())
    }

    @Test
    fun `removing a folder, revoking a permission and Stop each close the requests about them`() = runTest {
        val f = fixture()
        val gateway = FakeSharedFolders(f.folders)
        val folders = DefaultOwnerFolders(gateway, f.folders, f.grants, f.requests, f.activity, f.ids, f.clock)
        val grants = DefaultOwnerGrants(f.grants, f.agents, f.capabilities, f.folders, f.apps, { f.protectedPackages }, f.requests, f.waiters, f.activity, f.ids, f.clock)
        val hooks = OperatorTaskHooks(f.requests, f.grants, f.waiters, f.activity, f.clock)

        // A folder: its question goes with it.
        val write = f.start(f.writeFolder, f.assessment(modifying))
        assertTrue(folders.remove(f.folder.id))
        assertEquals("You removed the folder \"Reports\", so this was not done.", f.requests.observeRecent(1).first().single().decisionNote)
        assertIs<Authorization.Denied>(write.await())

        // A permission an action waiting for approval relied on.
        f.folders.upsert(f.folder)
        val always = assertIs<OwnerResult.Done<com.aicontrolcenter.core.operator.PermissionGrant>>(
            grants.grant(f.agent.id, OperatorCapabilities.FOLDERS_WRITE, f.inFolder),
        ).value
        val delete = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL, summary = "Delete \"old.txt\" in Reports"))
        val approval = f.pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
        assertTrue(grants.revoke(always.id))
        runCurrent()
        assertEquals(OwnerRequestStatus.CANCELLED, f.requests.get(approval.id)?.status)
        assertIs<Authorization.Denied>(delete.await())

        // Stop on screen control.
        val tap = f.start(f.tap, f.assessment(modifying, f.inNotes, "Tap \"Send\" in Notes"))
        val tapRequest = f.pending()
        f.screenStopped.value = true
        assertEquals(listOf(tapRequest.id), hooks.screenStopped().map { it.id })
        assertEquals(OperatorTaskHooks.SCREEN_STOPPED_NOTE, f.requests.get(tapRequest.id)?.decisionNote)
        assertIs<Authorization.Denied>(tap.await())

        // Revoke all: what the agent asks for goes too.
        f.screenStopped.value = false
        val read = f.start(f.readFolder, f.assessment(RiskLevel.SAFE))
        grants.revokeAll(f.agent.id)
        runCurrent()
        assertIs<Authorization.Denied>(read.await())
        assertTrue(f.requests.observePending().first().isEmpty())
        assertEquals(4, f.log.all.count { it.title.startsWith("Request withdrawn, ") && it.data?.get("requestId") != null })
    }

    @Test
    fun `an always written while its app is being withdrawn is revoked with the app and never comes back`() = runTest {
        val f = fixture()
        val tap = f.start(f.tap, f.assessment(modifying, f.inNotes, "Tap \"New note\" in Notes"))
        val request = f.pending()
        // The owner's Always passed its checks just before the withdrawal and is written in the middle of it.
        val crossed = CrossedBy(f.requests) { f.requests.decide(request.id, OwnerDecision.Grant(GrantScope.ALWAYS), f.clock.now(), GrantId("grt_crossing")) }
        val apps = DefaultOwnerOperatorApps(f.apps, f.grants, { f.protectedPackages }, crossed, f.activity, f.clock)

        assertTrue(apps.withdraw(f.app.packageName))
        runCurrent()

        assertEquals(OwnerRequestStatus.APPROVED, f.requests.get(request.id)?.status, "the answer was written")
        assertNotNull(f.grants.get(GrantId("grt_crossing"))?.revokedAt, "but what it granted went with the app")
        assertIs<Authorization.Denied>(tap.await())
        assertIs<OwnerResult.Done<*>>(apps.authorise(f.app.packageName, f.app.label, f.app.signingCertSha256))
        val query = GrantQuery(f.agent.id, OperatorCapabilities.SCREEN_OPERATE, f.inNotes, TaskId("tsk_other"), f.clock.now())
        assertTrue(f.grants.findMatching(query).isEmpty(), "authorised again, the app starts with no permission")
    }

    @Test
    fun `an always written while every permission is revoked, or its folder removed, is revoked too`() = runTest {
        val f = fixture()
        val write = f.start(f.writeFolder, f.assessment(modifying))
        val request = f.pending()
        val crossed = CrossedBy(f.requests) { f.requests.decide(request.id, OwnerDecision.Grant(GrantScope.ALWAYS), f.clock.now(), GrantId("grt_crossing")) }
        val grants = DefaultOwnerGrants(f.grants, f.agents, f.capabilities, f.folders, f.apps, { f.protectedPackages }, crossed, f.waiters, f.activity, f.ids, f.clock)

        assertEquals(1, grants.revokeAll(f.agent.id), "the permission the crossing answer stored is counted and revoked")
        runCurrent()
        assertNotNull(f.grants.get(GrantId("grt_crossing"))?.revokedAt)
        assertIs<Authorization.Denied>(write.await())

        val read = f.start(f.readFolder, f.assessment(RiskLevel.SAFE))
        val readRequest = f.pending()
        val crossedAgain = CrossedBy(f.requests) { f.requests.decide(readRequest.id, OwnerDecision.Grant(GrantScope.ALWAYS), f.clock.now(), GrantId("grt_crossing_2")) }
        val folders = DefaultOwnerFolders(FakeSharedFolders(f.folders), f.folders, f.grants, crossedAgain, f.activity, f.ids, f.clock)
        assertTrue(folders.remove(f.folder.id))
        runCurrent()
        assertNotNull(f.grants.get(GrantId("grt_crossing_2"))?.revokedAt, "no permission stays listed for a folder that is gone")
        assertIs<Authorization.Denied>(read.await())
    }

    @Test
    fun `authorising a different app under the same name withdraws the questions about the old one`() = runTest {
        val f = fixture()
        val apps = DefaultOwnerOperatorApps(f.apps, f.grants, { f.protectedPackages }, f.requests, f.activity, f.clock)
        val tap = f.start(f.tap, f.assessment(modifying, f.inNotes, "Tap \"New note\" in Notes"))
        val request = f.pending()

        // Same package, another signing certificate: not the app the question was about.
        assertIs<OwnerResult.Done<*>>(apps.authorise(f.app.packageName, f.app.label, "cd".repeat(32)))
        runCurrent()
        val closed = f.requests.get(request.id)!!
        assertEquals(OwnerRequestStatus.CANCELLED, closed.status)
        assertTrue("different app" in closed.decisionNote!!, closed.decisionNote)
        assertIs<Authorization.Denied>(tap.await())
        assertIs<DecideResult.AlreadyClosed>(f.owner.grant(request.id, GrantScope.ALWAYS))

        // Authorising the very same app again withdraws nothing.
        val again = f.start(f.tap, f.assessment(modifying, f.inNotes, "Tap \"New note\" in Notes"))
        assertIs<OwnerResult.Done<*>>(apps.authorise(f.app.packageName, f.app.label, "cd".repeat(32)))
        runCurrent()
        assertTrue(again.isActive)
        assertEquals(OwnerRequestStatus.PENDING, f.pending().status)
        again.cancel()
    }

    // ---- R4: no asking again right after a refusal ----

    @Test
    fun `after a refusal the same call is refused without asking for ten minutes, and the repeat is counted`() = runTest {
        val f = fixture()
        val first = f.start(f.writeFolder, f.assessment(modifying))
        val refusedRequest = f.pending()
        f.owner.deny(refusedRequest.id, "Not this week.")
        assertIs<Authorization.Denied>(first.await())

        advanceTimeBy(3 * MINUTE)
        val again = f.broker.authorize(f.writeFolder, f.assessment(modifying), f.context(task = "tsk_other")).reason()
        assertTrue("refused Operator the permission \"Change a shared folder\" 3 minutes ago" in again, again)
        assertTrue("not asked again for 7 more minutes" in again, again)
        assertTrue(f.requests.observePending().first().isEmpty(), "the owner is not asked again")
        assertEquals(1, f.allRequests().size)
        assertEquals(1, f.requests.get(refusedRequest.id)?.askedAgain)
        val logged = f.log.all.single { it.title.startsWith("Not asked again") }
        assertEquals(refusedRequest.id.value, logged.data?.get("requestId")?.jsonPrimitive?.content)

        // Another target, or another permission, is a different question.
        val elsewhere = f.start(f.readFolder, f.assessment(RiskLevel.SAFE))
        assertTrue(elsewhere.isActive)
        f.owner.deny(f.pending().id)
        elsewhere.await()

        // The cool-down over, the owner is asked again.
        advanceTimeBy(7 * MINUTE + 1_000)
        val later = f.start(f.writeFolder, f.assessment(modifying))
        assertTrue(later.isActive)
        assertEquals(OwnerRequestStatus.PENDING, f.pending().status)
        later.cancel()
    }

    @Test
    fun `an agent's own ask right after a refusal is refused and counted, and a grant given meanwhile needs no ask`() = runTest {
        val f = fixture()
        val requester = DefaultCapabilityRequester(f.facts, f.requests, f.grants, f.waiters, f.activity, f.ids, f.clock)
        val raised = assertIs<AskResult.Raised>(requester.ask(f.context(), OperatorCapabilities.WEB_READ, CapabilityConstraint.None, "To read reviews."))
        f.owner.deny(raised.requestId)
        advanceTimeBy(MINUTE)

        val refused = assertIs<AskResult.Refused>(requester.ask(f.context(), OperatorCapabilities.WEB_READ, CapabilityConstraint.None, "Please, it matters."))
        assertTrue("not asked again for 9 more minutes" in refused.reason, refused.reason)
        val call = f.broker.authorize(f.readPage, f.assessment(RiskLevel.SAFE, CapabilityConstraint.None, "Read the web page \"https://example.org\""), f.context())
        assertIs<Authorization.Denied>(call)
        assertEquals(2, f.requests.get(raised.requestId)?.askedAgain, "both repeats are counted on the refusal")
        assertTrue(f.requests.observePending().first().isEmpty())

        // The owner changes their mind in Tools: covered, so nothing needs asking.
        val grants = DefaultOwnerGrants(f.grants, f.agents, f.capabilities, f.folders, f.apps, { f.protectedPackages }, f.requests, f.waiters, f.activity, f.ids, f.clock)
        grants.grant(f.agent.id, OperatorCapabilities.WEB_READ)
        assertEquals(AskResult.AlreadyGranted, requester.ask(f.context(), OperatorCapabilities.WEB_READ, CapabilityConstraint.None, "Thanks."))
        assertEquals(
            Authorization.Allowed,
            f.broker.authorize(f.readPage, f.assessment(RiskLevel.SAFE, CapabilityConstraint.None, "Read the web page \"https://example.org\""), f.context()),
        )
    }

    @Test
    fun `an approval refused does not stop the next action from being put to the owner`() = runTest {
        val f = fixture()
        val grants = DefaultOwnerGrants(f.grants, f.agents, f.capabilities, f.folders, f.apps, { f.protectedPackages }, f.requests, f.waiters, f.activity, f.ids, f.clock)
        grants.grant(f.agent.id, OperatorCapabilities.FOLDERS_WRITE, f.inFolder)
        val first = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL, summary = "Delete \"a.txt\" in Reports"))
        f.owner.deny(f.pending().id)
        assertIs<Authorization.Denied>(first.await())
        val second = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL, summary = "Delete \"b.txt\" in Reports"))
        assertEquals(ToolId("folders.delete"), f.pending().toolId, "each consequential action is its own question")
        second.cancel()
        assertTrue(f.events(ActivityType.PERMISSION).none { it.title.startsWith("Not asked again") })
    }
}
