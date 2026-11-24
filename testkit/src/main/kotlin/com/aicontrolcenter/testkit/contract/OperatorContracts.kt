package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ConversationId
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantQuery
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorAppRepository
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerDecision
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestRepository
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.PermissionRepository
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.operator.SharedFolderRepository
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fully populated operator records, so persistence tests exercise every field. */
object OperatorSamples {
    val agent = AgentId("agt_operator")
    val otherAgent = AgentId("agt_other")
    val folder = CapabilityConstraint.Folder(FolderId("fld_reports"))
    val otherFolder = CapabilityConstraint.Folder(FolderId("fld_photos"))
    val app = CapabilityConstraint.App("com.example.notes")

    fun grant(
        id: String,
        scope: GrantScope = GrantScope.ALWAYS,
        task: String? = if (scope == GrantScope.ALWAYS) null else "tsk_1",
        at: Long = 1_000,
        agentId: AgentId = agent,
        capability: Capability = OperatorCapabilities.FOLDERS_READ,
        constraint: CapabilityConstraint = folder,
        expiresAt: Long? = if (scope == GrantScope.ONCE) at + PermissionGrant.ONCE_LIFETIME_MS else null,
    ) = PermissionGrant(
        id = GrantId(id), agentId = agentId, capability = capability, constraint = constraint, scope = scope,
        taskId = task?.let(::TaskId), requestId = RequestId("req_for_$id"), grantedAt = at, expiresAt = expiresAt,
    )

    fun request(
        id: String,
        kind: OwnerRequestKind = OwnerRequestKind.PERMISSION,
        at: Long = 1_000,
        expiresAt: Long = at + 1_800_000,
        task: String? = "tsk_1",
        conversation: String? = "cnv_1",
    ) = OwnerRequest(
        id = RequestId(id), kind = kind, agentId = agent, toolId = ToolId("folders.delete"), capability = OperatorCapabilities.FOLDERS_WRITE,
        constraint = folder, risk = if (kind == OwnerRequestKind.APPROVAL) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING,
        summary = "Delete \"2024, Q1.csv\" in Reports",
        details = listOf("Reports/2024, Q1.csv (12 KB)", "", "Line 2: \"quoted\"\nand a new line ✓"),
        dataGoesTo = "Gmail", reason = "I need to \"tidy up\" the folder,\nas you asked 🙂",
        taskId = task?.let(::TaskId), rootId = task?.let { TaskId("tsk_root") }, conversationId = conversation?.let(::ConversationId),
        createdAt = at, expiresAt = expiresAt, agentName = "Operator \"Ops\" 🤖", toolTitle = "Delete in a shared folder",
        targetLabel = "Reports, 2024", delegationChain = listOf(AgentId("agt_director"), otherAgent),
    )
}

/** What every [PermissionRepository] must do: scope matching, ONCE bound to its request, insert-only storage, revocation. */
abstract class PermissionRepositoryContract {
    protected abstract fun createRepository(): PermissionRepository

    private val s = OperatorSamples

    private fun query(task: String? = "tsk_1", at: Long = 5_000, constraint: CapabilityConstraint = s.folder, agentId: AgentId = s.agent) =
        GrantQuery(agentId, OperatorCapabilities.FOLDERS_READ, constraint, task?.let(::TaskId), at)

    private fun req(grantId: String) = RequestId("req_for_$grantId")

    @Test
    fun `stores every field, lists grants in force oldest first, and never replaces one`() = runTest {
        val repo = createRepository()
        val always = s.grant("grt_b", at = 2_000)
        val task = s.grant("grt_a", GrantScope.TASK, at = 2_000).copy(constraint = s.app, capability = OperatorCapabilities.SCREEN_OPERATE)
        val once = s.grant("grt_c", GrantScope.ONCE, task = null, at = 1_500)
        val bare = s.grant("grt_d", at = 3_000, agentId = s.otherAgent, constraint = CapabilityConstraint.None, capability = OperatorCapabilities.WEB_READ)
            .copy(requestId = null)
        val revoked = s.grant("grt_e", at = 100).copy(revokedAt = 900)
        val consumed = s.grant("grt_f", GrantScope.ONCE, at = 200).copy(consumedAt = 300)
        listOf(always, task, once, bare, revoked, consumed).forEach { repo.insert(it) }
        listOf(always, task, once, bare, revoked, consumed).forEach { assertEquals(it, repo.get(it.id)) }
        assertNull(repo.get(GrantId("grt_missing")))
        assertEquals(listOf(once, task, always), repo.observeForAgent(s.agent).first())
        assertEquals(listOf(once, task, always, bare), repo.observeAll().first())
        // Insert-only: a revoked or consumed grant cannot be brought back by reusing its id.
        assertFailsWith<IllegalArgumentException> { repo.insert(revoked.copy(revokedAt = null)) }
        assertFailsWith<IllegalArgumentException> { repo.insert(consumed.copy(consumedAt = null)) }
        assertFailsWith<IllegalArgumentException> { repo.insert(always.copy(expiresAt = 99_000)) }
        assertEquals(listOf(always, revoked, consumed), listOf(always, revoked, consumed).map { repo.get(it.id) })
    }

    @Test
    fun `finds exactly the grants that cover a call, never a once grant`() = runTest {
        val repo = createRepository()
        val grants = listOf(
            s.grant("grt_always", at = 3_000),
            s.grant("grt_always_old", at = 2_000),
            s.grant("grt_task", GrantScope.TASK, at = 1_000),
            s.grant("grt_task_other", GrantScope.TASK, task = "tsk_2"),
            s.grant("grt_task_expired", GrantScope.TASK, at = 100, expiresAt = 5_000),
            s.grant("grt_once", GrantScope.ONCE, at = 4_000),
            s.grant("grt_once_chat", GrantScope.ONCE, task = null),
            s.grant("grt_once_used", GrantScope.ONCE).copy(consumedAt = 1_200),
            s.grant("grt_revoked").copy(revokedAt = 1_100),
            s.grant("grt_unconstrained", constraint = CapabilityConstraint.None),
            s.grant("grt_other_folder", constraint = s.otherFolder),
            s.grant("grt_other_agent", agentId = s.otherAgent),
            s.grant("grt_other_capability", capability = OperatorCapabilities.FOLDERS_WRITE),
            s.grant("grt_expires_later", at = 900, expiresAt = 5_001),
        )
        grants.forEach { repo.insert(it) }
        assertEquals(listOf("grt_expires_later", "grt_always_old", "grt_always", "grt_task"), repo.findMatching(query()).map { it.id.value })
        assertEquals(listOf("grt_expires_later", "grt_always_old", "grt_always"), repo.findMatching(query(task = null)).map { it.id.value })
        assertEquals(listOf("grt_unconstrained"), repo.findMatching(query(constraint = CapabilityConstraint.None)).map { it.id.value })
        assertEquals(listOf("grt_always_old", "grt_always", "grt_task"), repo.findMatching(query(at = 5_001)).map { it.id.value })
        // The storage must agree with the one definition of matching, whatever the query.
        val queries = listOf(null, "tsk_1", "tsk_2").flatMap { t ->
            listOf(CapabilityConstraint.None, s.folder, s.otherFolder, s.app).flatMap { c ->
                listOf(s.agent, s.otherAgent).flatMap { a -> listOf(0L, 1_150L, 4_999L, 5_000L, 700_000L).map { at -> query(t, at, c, a) } }
            }
        }
        for (q in queries) {
            val found = repo.findMatching(q)
            assertEquals(grants.filter { it.covers(q) }.sortedWith(PermissionGrant.PREFERENCE), found, q.toString())
            assertTrue(found.none { it.scope == GrantScope.ONCE }, q.toString())
        }
        // Reading consumes nothing.
        assertEquals(grants.toSet(), grants.map { repo.get(it.id) }.toSet())
        assertEquals(grants.first { it.id.value == "grt_task" }, repo.findByRequest(req("grt_task")))
        assertNull(repo.findByRequest(RequestId("req_missing")))
    }

    @Test
    fun `a once grant is consumed only for its own request, by exactly one of many racing callers`() = runTest {
        val repo = createRepository()
        val once = s.grant("grt_once", GrantScope.ONCE)
        val other = s.grant("grt_other_once", GrantScope.ONCE)
        listOf(once, other).forEach { repo.insert(it) }
        val winners = withContext(Dispatchers.Default) {
            (1..48).map { async { repo.consumeOnceFor(req("grt_once"), 2_000) } }.awaitAll()
        }.filterNotNull()
        assertEquals(listOf(once.copy(consumedAt = 2_000)), winners)
        assertEquals(once.copy(consumedAt = 2_000), repo.get(once.id))
        assertEquals(other, repo.get(other.id))
        assertEquals(listOf(other.id), repo.observeForAgent(s.agent).first().map { it.id })
        assertNull(repo.consumeOnceFor(req("grt_once"), 2_001))
        assertNull(repo.consumeOnceFor(RequestId("req_missing"), 2_000))
    }

    @Test
    fun `only an active once grant can be consumed or released`() = runTest {
        val repo = createRepository()
        val always = s.grant("grt_always")
        val task = s.grant("grt_task", GrantScope.TASK)
        val revoked = s.grant("grt_revoked", GrantScope.ONCE).copy(revokedAt = 1_100)
        val expiring = s.grant("grt_expiring", GrantScope.ONCE, expiresAt = 3_000)
        val left = s.grant("grt_left", GrantScope.ONCE)
        listOf(always, task, revoked, expiring, left).forEach { repo.insert(it) }
        assertNull(repo.consumeOnceFor(req("grt_always"), 2_000))
        assertNull(repo.consumeOnceFor(req("grt_task"), 2_000))
        assertNull(repo.consumeOnceFor(req("grt_revoked"), 2_000))
        assertNull(repo.consumeOnceFor(req("grt_expiring"), 3_000))
        assertNull(repo.releaseOnceFor(req("grt_always"), 2_000))
        assertNull(repo.releaseOnceFor(req("grt_revoked"), 2_000))
        assertEquals(listOf(always, task, revoked, expiring, left), listOf(always, task, revoked, expiring, left).map { repo.get(it.id) })
        assertEquals(expiring.copy(consumedAt = 2_999), repo.consumeOnceFor(req("grt_expiring"), 2_999))
        assertNull(repo.releaseOnceFor(req("grt_expiring"), 3_000))
        assertEquals(left.copy(revokedAt = 2_000), repo.releaseOnceFor(req("grt_left"), 2_000))
        assertNull(repo.consumeOnceFor(req("grt_left"), 2_001))
    }

    @Test
    fun `consuming and revoking race, and exactly one of them ends the grant`() = runTest {
        val repo = createRepository()
        val grants = (1..20).map { s.grant("grt_$it", GrantScope.ONCE) }
        grants.forEach { repo.insert(it) }
        val outcomes = withContext(Dispatchers.Default) {
            grants.map { g -> async { repo.consumeOnceFor(g.requestId!!, 2_000) } to async { repo.revoke(g.id, 2_000) } }
                .map { (consume, revoke) -> consume.await() to revoke.await() }
        }
        for ((i, pair) in outcomes.withIndex()) {
            val (consumed, revoked) = pair
            assertTrue((consumed == null) != (revoked == null), "grant ${i + 1}: exactly one must win")
            assertEquals(consumed ?: revoked, repo.get(grants[i].id))
        }
    }

    @Test
    fun `revoking ends grants once, per grant, agent, constraint or finished task`() = runTest {
        val repo = createRepository()
        val a = s.grant("grt_a")
        val b = s.grant("grt_b", GrantScope.TASK)
        val c = s.grant("grt_c", constraint = s.otherFolder)
        val used = s.grant("grt_used", GrantScope.ONCE).copy(consumedAt = 1_100)
        val other = s.grant("grt_other", agentId = s.otherAgent)
        val task2 = s.grant("grt_task2", GrantScope.TASK, task = "tsk_2", agentId = s.otherAgent)
        val once2 = s.grant("grt_once2", GrantScope.ONCE, task = "tsk_2", agentId = s.otherAgent)
        listOf(a, b, c, used, other, task2, once2).forEach { repo.insert(it) }
        assertEquals(a.copy(revokedAt = 2_000), repo.revoke(a.id, 2_000))
        assertNull(repo.revoke(a.id, 2_100))
        assertEquals(a.copy(revokedAt = 2_000), repo.get(a.id))
        assertNull(repo.revoke(used.id, 2_000))
        assertNull(repo.revoke(GrantId("grt_missing"), 2_000))
        assertEquals(1, repo.revokeAllWithConstraint(s.otherFolder, 3_000))
        assertEquals(c.copy(revokedAt = 3_000), repo.get(c.id))
        assertEquals(1, repo.revokeAllFor(s.agent, 4_000))
        assertEquals(b.copy(revokedAt = 4_000), repo.get(b.id))
        assertEquals(0, repo.revokeAllFor(s.agent, 5_000))
        assertEquals(used, repo.get(used.id))
        assertEquals(2, repo.revokeAllForTask(TaskId("tsk_2"), 6_000))
        assertEquals(listOf(task2.copy(revokedAt = 6_000), once2.copy(revokedAt = 6_000)), listOf(task2, once2).map { repo.get(it.id) })
        assertEquals(0, repo.revokeAllForTask(TaskId("tsk_2"), 6_100))
        assertEquals(listOf(other), repo.observeAll().first())
    }

    @Test
    fun `open observers see later changes`() = runTest {
        val repo = createRepository()
        val mine = collectIn(backgroundScope, repo.observeForAgent(s.agent))
        val all = collectIn(backgroundScope, repo.observeAll())
        awaitUntil { mine.isNotEmpty() && all.isNotEmpty() }
        repo.insert(s.grant("grt_a"))
        repo.insert(s.grant("grt_once", GrantScope.ONCE))
        repo.insert(s.grant("grt_other", agentId = s.otherAgent))
        awaitUntil { mine.last().size == 2 && all.last().size == 3 }
        repo.consumeOnceFor(req("grt_once"), 2_000)
        awaitUntil { mine.last().map { it.id.value } == listOf("grt_a") }
        repo.revokeAllFor(s.otherAgent, 3_000)
        awaitUntil { all.last().map { it.id.value } == listOf("grt_a") }
    }
}

/** An [OwnerRequestRepository] and the grant storage its [OwnerRequestRepository.decide] writes to (the same database). */
data class RequestStores(val requests: OwnerRequestRepository, val grants: PermissionRepository)

/** What every [OwnerRequestRepository] must do: storage, orders, compare-and-set answers that write their grant, expiry and cancellation. */
abstract class OwnerRequestRepositoryContract {
    protected abstract fun createStores(): RequestStores

    private fun createRepository() = createStores().requests

    private val s = OperatorSamples

    private val gid = GrantId("grt_answer")

    @Test
    fun `stores every field and lists in the documented orders`() = runTest {
        val repo = createRepository()
        val first = s.request("req_b", at = 1_000)
        val tie = s.request("req_c", OwnerRequestKind.APPROVAL, at = 1_000)
        val bare = s.request("req_a", at = 2_000, task = null, conversation = null).copy(
            toolId = null, constraint = CapabilityConstraint.None, details = emptyList(), dataGoesTo = null, reason = null,
            agentName = null, toolTitle = null, targetLabel = null, delegationChain = emptyList(),
        )
        val elsewhere = s.request("req_d", at = 3_000, conversation = "cnv_2").copy(rootId = TaskId("tsk_root2"))
        listOf(elsewhere, bare, tie, first).forEach { repo.create(it) }
        listOf(first, tie, bare, elsewhere).forEach { assertEquals(it, repo.get(it.id)) }
        assertNull(repo.get(RequestId("req_missing")))
        assertEquals(first, repo.observe(first.id).first())
        assertNull(repo.observe(RequestId("req_missing")).first())
        assertEquals(listOf(first, tie, bare, elsewhere), repo.observePending().first())
        assertEquals(listOf(elsewhere, bare, tie), repo.observeRecent(3).first())
        assertEquals(listOf(first, tie), repo.observeForConversation(ConversationId("cnv_1")).first())
        assertEquals(listOf(first, tie), repo.observeForObjective(TaskId("tsk_root")).first())
        assertEquals(listOf(elsewhere), repo.observeForObjective(TaskId("tsk_root2")).first())
    }

    @Test
    fun `creating refuses a request that is not pending or whose id is taken`() = runTest {
        val repo = createRepository()
        val r = s.request("req_1")
        repo.create(r)
        assertFailsWith<IllegalArgumentException> { repo.create(r.copy(summary = "Another")) }
        assertFailsWith<IllegalArgumentException> {
            repo.create(s.request("req_2").copy(status = OwnerRequestStatus.DENIED, decidedAt = 1_100))
        }
        assertEquals(r, repo.get(r.id))
        assertNull(repo.get(RequestId("req_2")))
    }

    @Test
    fun `an answer is recorded with its scope, note and time, and a grant writes its grant`() = runTest {
        val (repo, grants) = createStores()
        val permission = s.request("req_p")
        val approval = s.request("req_a", OwnerRequestKind.APPROVAL)
        val refused = s.request("req_d", OwnerRequestKind.APPROVAL)
        listOf(permission, approval, refused).forEach { repo.create(it) }
        val granted = permission.copy(status = OwnerRequestStatus.APPROVED, decidedAt = 2_000, decisionScope = GrantScope.TASK, decisionNote = "Only \"Reports\", please")
        val grant = permission.grantFor(GrantScope.TASK, gid, 2_000)
        assertEquals(DecideResult.Decided(granted, grant), repo.decide(permission.id, OwnerDecision.Grant(GrantScope.TASK, "Only \"Reports\", please"), 2_000, gid))
        assertEquals(granted, repo.get(permission.id))
        assertEquals(grant, grants.findByRequest(permission.id))
        assertEquals(grant, grants.get(gid))
        val approved = approval.copy(status = OwnerRequestStatus.APPROVED, decidedAt = 2_100)
        assertEquals(DecideResult.Decided(approved), repo.decide(approval.id, OwnerDecision.Approve(), 2_100, GrantId("grt_unused1")))
        val denied = refused.copy(status = OwnerRequestStatus.DENIED, decidedAt = 2_200, decisionNote = "Not now")
        assertEquals(DecideResult.Decided(denied), repo.decide(refused.id, OwnerDecision.Deny("Not now"), 2_200, GrantId("grt_unused2")))
        assertEquals(listOf(granted, approved, denied), listOf(permission, approval, refused).map { repo.get(it.id) })
        assertTrue(repo.observePending().first().isEmpty())
        assertEquals(DecideResult.AlreadyClosed(granted), repo.decide(permission.id, OwnerDecision.Deny(), 2_300, GrantId("grt_unused3")))
        // Only the granted request wrote a grant.
        assertEquals(listOf(grant), grants.observeAll().first())
        assertNull(grants.findByRequest(approval.id))
    }

    @Test
    fun `whoever sees the approval finds the grant already stored`() = runTest {
        val (repo, grants) = createStores()
        val requests = (1..10).map { s.request("req_$it") }
        requests.forEach { repo.create(it) }
        val seen = requests.map { r ->
            backgroundScope.async(Dispatchers.Default) {
                repo.observe(r.id).first { it?.status == OwnerRequestStatus.APPROVED }
                grants.findByRequest(r.id)
            }
        }
        withContext(Dispatchers.Default) {
            requests.forEachIndexed { i, r -> repo.decide(r.id, OwnerDecision.Grant(GrantScope.ONCE), 2_000, GrantId("grt_$i")) }
        }
        val found = withContext(Dispatchers.Default) { withTimeout(5_000) { seen.awaitAll() } }
        requests.forEachIndexed { i, r -> assertEquals(GrantId("grt_$i"), found[i]?.id, "${r.id}: the waiter woke before the grant existed") }
        assertEquals(requests.map { it.grantFor(GrantScope.ONCE, GrantId("grt_${requests.indexOf(it)}"), 2_000) }, found)
    }

    @Test
    fun `answers that do not fit, or a grant id already taken, change nothing`() = runTest {
        val (repo, grants) = createStores()
        val permission = s.request("req_p")
        val chat = s.request("req_chat", task = null)
        val approval = s.request("req_a", OwnerRequestKind.APPROVAL)
        listOf(permission, chat, approval).forEach { repo.create(it) }
        assertIs<DecideResult.NotAccepted>(repo.decide(approval.id, OwnerDecision.Grant(GrantScope.ALWAYS), 2_000, gid))
        assertIs<DecideResult.NotAccepted>(repo.decide(approval.id, OwnerDecision.Grant(GrantScope.ONCE), 2_000, gid))
        assertIs<DecideResult.NotAccepted>(repo.decide(permission.id, OwnerDecision.Approve(), 2_000, gid))
        assertIs<DecideResult.NotAccepted>(repo.decide(chat.id, OwnerDecision.Grant(GrantScope.TASK), 2_000, gid))
        assertEquals(DecideResult.NotFound, repo.decide(RequestId("req_missing"), OwnerDecision.Deny(), 2_000, gid))
        assertEquals(listOf(permission, chat, approval), listOf(permission, chat, approval).map { repo.get(it.id) })
        assertTrue(grants.observeAll().first().isEmpty())
        grants.insert(s.grant("grt_taken"))
        assertFailsWith<IllegalArgumentException> { repo.decide(permission.id, OwnerDecision.Grant(GrantScope.ALWAYS), 2_000, GrantId("grt_taken")) }
        assertEquals(permission, repo.get(permission.id))
        assertNull(grants.findByRequest(permission.id))
    }

    @Test
    fun `exactly one of many racing deciders wins, and exactly one grant is written`() = runTest {
        val (repo, grants) = createStores()
        val requests = (1..10).map { s.request("req_$it", at = it.toLong()) }
        requests.forEach { repo.create(it) }
        val decisions = listOf(OwnerDecision.Grant(GrantScope.ONCE), OwnerDecision.Grant(GrantScope.ALWAYS), OwnerDecision.Deny("no"), OwnerDecision.Grant(GrantScope.TASK))
        for (request in requests) {
            val results = withContext(Dispatchers.Default) {
                (0 until 24).map { n -> async { repo.decide(request.id, decisions[n % decisions.size], 2_000L + n, GrantId("grt_${request.id}_$n")) } }.awaitAll()
            }
            val won = results.filterIsInstance<DecideResult.Decided>()
            assertEquals(1, won.size, "${request.id}: $results")
            assertEquals(won.single().request, repo.get(request.id))
            assertEquals(won.single().grant, grants.findByRequest(request.id))
            assertTrue(results.all { it is DecideResult.Decided || (it is DecideResult.AlreadyClosed && it.request == won.single().request) })
        }
        assertEquals(grants.observeAll().first().size, requests.count { repo.get(it.id)?.decisionScope != null })
    }

    @Test
    fun `deciding, expiring and cancelling race, and exactly one wins`() = runTest {
        val repo = createRepository()
        val requests = (1..20).map { s.request("req_$it") }
        requests.forEach { repo.create(it) }
        val outcomes = withContext(Dispatchers.Default) {
            requests.map { r ->
                listOf(
                    async { (repo.decide(r.id, OwnerDecision.Grant(GrantScope.ALWAYS), 2_000, GrantId("grt_a_${r.id}")) as? DecideResult.Decided)?.request },
                    async { repo.close(r.id, OwnerRequestStatus.CANCELLED, 2_000, "Task cancelled") },
                    async { repo.close(r.id, OwnerRequestStatus.EXPIRED, 2_000, null) },
                    async { (repo.decide(r.id, OwnerDecision.Deny(), 2_000, GrantId("grt_d_${r.id}")) as? DecideResult.Decided)?.request },
                )
            }.map { it.awaitAll() }
        }
        for ((i, results) in outcomes.withIndex()) {
            val winners = results.filterNotNull()
            assertEquals(1, winners.size, "request ${i + 1}: $results")
            assertEquals(winners.single(), repo.get(requests[i].id))
        }
    }

    @Test
    fun `after the deadline, a late answer, a cancel, a start-up sweep and an overdue sweep race, and exactly one wins`() = runTest {
        val repo = createRepository()
        // One request at a time: a sweep closes every pending request, so each race needs its own.
        for (i in 1..20) {
            val r = s.request("req_$i", at = 1_000, expiresAt = 5_000)
            repo.create(r)
            val results = withContext(Dispatchers.Default) {
                listOf(
                    async { (repo.decide(r.id, OwnerDecision.Grant(GrantScope.ALWAYS), 5_000, GrantId("grt_${r.id}")) as? DecideResult.Expired)?.request },
                    async { repo.close(r.id, OwnerRequestStatus.CANCELLED, 5_000, "The chat was stopped.") },
                    async { repo.cancelAllPending(5_000, "Restarted.").firstOrNull { it.id == r.id } },
                    async { repo.closeOverdue(5_000, "No answer in time.").firstOrNull { it.id == r.id } },
                ).awaitAll()
            }
            val winners = results.filterNotNull()
            assertEquals(1, winners.size, "request $i: $results")
            assertEquals(winners.single(), repo.get(r.id))
            assertTrue(winners.single().status == OwnerRequestStatus.EXPIRED || winners.single().status == OwnerRequestStatus.CANCELLED)
        }
    }

    @Test
    fun `a start-up sweep and answers race, and exactly one wins`() = runTest {
        val repo = createRepository()
        for (i in 1..20) {
            val r = s.request("req_$i")
            repo.create(r)
            val results = withContext(Dispatchers.Default) {
                listOf(
                    async { (repo.decide(r.id, OwnerDecision.Deny(), 2_000, GrantId("grt_${r.id}")) as? DecideResult.Decided)?.request },
                    async { repo.cancelAllPending(2_000, "Restarted.").firstOrNull { it.id == r.id } },
                ).awaitAll()
            }
            val winners = results.filterNotNull()
            assertEquals(1, winners.size, "request $i: $results")
            assertEquals(winners.single(), repo.get(r.id))
        }
    }

    @Test
    fun `an answer at or after the deadline expires the request instead`() = runTest {
        val (repo, grants) = createStores()
        val r = s.request("req_1", at = 1_000, expiresAt = 5_000)
        repo.create(r)
        val expired = r.copy(status = OwnerRequestStatus.EXPIRED, decidedAt = 5_000)
        assertEquals(DecideResult.Expired(expired), repo.decide(r.id, OwnerDecision.Grant(GrantScope.ALWAYS), 5_000, gid))
        assertEquals(expired, repo.get(r.id))
        assertNull(grants.findByRequest(r.id))
        assertEquals(DecideResult.AlreadyClosed(expired), repo.decide(r.id, OwnerDecision.Deny(), 5_001, gid))
        val justInTime = s.request("req_2", at = 1_000, expiresAt = 5_000)
        repo.create(justInTime)
        assertIs<DecideResult.Decided>(repo.decide(justInTime.id, OwnerDecision.Deny(), 4_999, gid))
    }

    @Test
    fun `close ends only pending requests, as expired or cancelled`() = runTest {
        val repo = createRepository()
        val a = s.request("req_a")
        val b = s.request("req_b")
        listOf(a, b).forEach { repo.create(it) }
        val cancelled = a.copy(status = OwnerRequestStatus.CANCELLED, decidedAt = 2_000, decisionNote = "The chat was stopped.")
        assertEquals(cancelled, repo.close(a.id, OwnerRequestStatus.CANCELLED, 2_000, "The chat was stopped."))
        assertNull(repo.close(a.id, OwnerRequestStatus.EXPIRED, 2_100, null))
        assertEquals(cancelled, repo.get(a.id))
        repo.decide(b.id, OwnerDecision.Deny(), 2_000, gid)
        assertNull(repo.close(b.id, OwnerRequestStatus.CANCELLED, 2_100, null))
        assertNull(repo.close(RequestId("req_missing"), OwnerRequestStatus.CANCELLED, 2_100, null))
        for (status in listOf(OwnerRequestStatus.PENDING, OwnerRequestStatus.APPROVED, OwnerRequestStatus.DENIED)) {
            assertFailsWith<IllegalArgumentException> { repo.close(a.id, status, 2_200, null) }
        }
    }

    @Test
    fun `overdue requests expire and start-up cancels what is left pending`() = runTest {
        val repo = createRepository()
        val early = s.request("req_early", at = 10, expiresAt = 100)
        val onTime = s.request("req_ontime", at = 20, expiresAt = 200)
        val later = s.request("req_later", at = 30, expiresAt = 300)
        val answered = s.request("req_answered", at = 5, expiresAt = 50)
        listOf(early, onTime, later, answered).forEach { repo.create(it) }
        repo.decide(answered.id, OwnerDecision.Deny(), 40, gid)
        val expired = listOf(early, onTime).map { it.copy(status = OwnerRequestStatus.EXPIRED, decidedAt = 200, decisionNote = "No answer in time.") }
        assertEquals(expired, repo.closeOverdue(200, "No answer in time."))
        assertEquals(listOf(later), repo.observePending().first())
        assertTrue(repo.closeOverdue(200, null).isEmpty())
        val cancelled = later.copy(status = OwnerRequestStatus.CANCELLED, decidedAt = 250, decisionNote = "The app restarted.")
        assertEquals(listOf(cancelled), repo.cancelAllPending(250, "The app restarted."))
        assertTrue(repo.cancelAllPending(260, null).isEmpty())
        assertEquals(OwnerRequestStatus.DENIED, repo.get(answered.id)?.status)
        assertEquals(expired + cancelled, listOf(early, onTime, later).map { repo.get(it.id) })
    }

    @Test
    fun `a finished task cancels only its agents' own asks`() = runTest {
        val repo = createRepository()
        val ask = s.request("req_ask", at = 10).copy(toolId = null)
        val laterAsk = s.request("req_ask2", at = 20).copy(toolId = null)
        val call = s.request("req_call", at = 30)
        val otherTask = s.request("req_other", at = 40, task = "tsk_2").copy(toolId = null)
        val chat = s.request("req_chat", at = 50, task = null).copy(toolId = null)
        listOf(ask, laterAsk, call, otherTask, chat).forEach { repo.create(it) }
        repo.decide(laterAsk.id, OwnerDecision.Deny(), 60, gid)
        val cancelled = ask.copy(status = OwnerRequestStatus.CANCELLED, decidedAt = 100, decisionNote = "The task finished.")
        assertEquals(listOf(cancelled), repo.cancelAsksForTask(TaskId("tsk_1"), 100, "The task finished."))
        assertEquals(listOf(call, otherTask, chat), repo.observePending().first())
        assertTrue(repo.cancelAsksForTask(TaskId("tsk_1"), 110, null).isEmpty())
    }

    @Test
    fun `the newest refusal of the same agent, permission and target is found, and asking again is counted on it`() = runTest {
        val repo = createRepository()
        val old = s.request("req_old", at = 10)
        val newer = s.request("req_new", at = 20)
        val approved = s.request("req_ok", at = 30)
        val approval = s.request("req_appr", OwnerRequestKind.APPROVAL, at = 40)
        val otherFolder = s.request("req_fld", at = 50).copy(constraint = s.otherFolder)
        val otherAgent = s.request("req_agt", at = 60).copy(agentId = s.otherAgent)
        val open = s.request("req_open", at = 70)
        listOf(old, newer, approved, approval, otherFolder, otherAgent, open).forEach { repo.create(it) }
        repo.decide(old.id, OwnerDecision.Deny(), 1_000, GrantId("grt_x1"))
        repo.decide(newer.id, OwnerDecision.Deny("No"), 2_000, GrantId("grt_x2"))
        repo.decide(approved.id, OwnerDecision.Grant(GrantScope.ONCE), 3_000, GrantId("grt_x3"))
        repo.decide(approval.id, OwnerDecision.Deny(), 4_000, GrantId("grt_x4"))
        repo.decide(otherFolder.id, OwnerDecision.Deny(), 4_000, GrantId("grt_x5"))
        repo.decide(otherAgent.id, OwnerDecision.Deny(), 4_000, GrantId("grt_x6"))
        val capability = OperatorCapabilities.FOLDERS_WRITE

        assertEquals(newer.id, repo.lastRefusal(s.agent, capability, s.folder, since = 0)?.id, "the newest refusal, not an approval, an approval request or another target")
        assertEquals(newer.id, repo.lastRefusal(s.agent, capability, s.folder, since = 2_000)?.id, "decided at the bound counts")
        assertNull(repo.lastRefusal(s.agent, capability, s.folder, since = 2_001), "older refusals are past the cool-down")
        assertNull(repo.lastRefusal(s.agent, OperatorCapabilities.FOLDERS_READ, s.folder, since = 0))
        assertEquals(otherFolder.id, repo.lastRefusal(s.agent, capability, s.otherFolder, since = 0)?.id)

        assertEquals(1, repo.countAskedAgain(newer.id)?.askedAgain)
        assertEquals(2, repo.countAskedAgain(newer.id)?.askedAgain)
        assertEquals(2, repo.get(newer.id)?.askedAgain)
        assertEquals(2, repo.lastRefusal(s.agent, capability, s.folder, since = 0)?.askedAgain)
        assertNull(repo.countAskedAgain(approved.id), "only a refused request counts")
        assertNull(repo.countAskedAgain(open.id))
        assertNull(repo.countAskedAgain(RequestId("req_missing")))
        assertEquals(0, repo.get(approved.id)?.askedAgain)
        assertEquals(OwnerRequestStatus.PENDING, repo.get(open.id)?.status)
    }

    @Test
    fun `open observers see later changes`() = runTest {
        val repo = createRepository()
        val pending = collectIn(backgroundScope, repo.observePending())
        val one = collectIn(backgroundScope, repo.observe(RequestId("req_1")))
        val recent = collectIn(backgroundScope, repo.observeRecent(10))
        val chat = collectIn(backgroundScope, repo.observeForConversation(ConversationId("cnv_1")))
        awaitUntil { pending.isNotEmpty() && one.isNotEmpty() && recent.isNotEmpty() && chat.isNotEmpty() }
        repo.create(s.request("req_1"))
        repo.create(s.request("req_2", at = 2_000))
        awaitUntil { pending.last().size == 2 && one.last() != null && recent.last().size == 2 && chat.last().size == 2 }
        repo.decide(RequestId("req_1"), OwnerDecision.Grant(GrantScope.ONCE), 3_000, gid)
        awaitUntil { pending.last().map { it.id.value } == listOf("req_2") && one.last()?.status == OwnerRequestStatus.APPROVED }
        repo.close(RequestId("req_2"), OwnerRequestStatus.CANCELLED, 3_100, null)
        awaitUntil { pending.last().isEmpty() && chat.last().all { !it.status.isPending } && recent.last().first().status == OwnerRequestStatus.CANCELLED }
    }
}

/** What every [SharedFolderRepository] must do. */
abstract class SharedFolderRepositoryContract {
    protected abstract fun createRepository(): SharedFolderRepository

    private fun folder(id: String, at: Long, readOnly: Boolean = false) = SharedFolder(
        FolderId(id), "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2F$id", "Documents, \"$id\" 📁", at, readOnly,
    )

    @Test
    fun `stores every field, lists oldest first and finds by tree`() = runTest {
        val repo = createRepository()
        val b = folder("fld_b", 100, readOnly = true)
        val a = folder("fld_a", 100)
        val c = folder("fld_c", 50)
        listOf(b, a, c).forEach { repo.upsert(it) }
        assertEquals(listOf(c, a, b), repo.getAll())
        assertEquals(listOf(c, a, b), repo.observeAll().first())
        assertEquals(b, repo.get(b.id))
        assertEquals(a, repo.getByTreeUri(a.treeUri))
        assertNull(repo.getByTreeUri("content://elsewhere"))
        repo.upsert(b.copy(readOnly = false, displayName = "Renamed"))
        assertEquals(b.copy(readOnly = false, displayName = "Renamed"), repo.get(b.id))
        // One tree, one folder: another id for the same tree is refused, and the first folder stays.
        assertFailsWith<IllegalArgumentException> { repo.upsert(folder("fld_dup", 300).copy(treeUri = a.treeUri)) }
        assertNull(repo.get(FolderId("fld_dup")))
        assertEquals(a, repo.getByTreeUri(a.treeUri))
        repo.delete(a.id)
        repo.delete(FolderId("fld_missing"))
        assertNull(repo.get(a.id))
        assertEquals(listOf("fld_c", "fld_b"), repo.getAll().map { it.id.value })
    }

    @Test
    fun `open observers see later changes`() = runTest {
        val repo = createRepository()
        val seen = collectIn(backgroundScope, repo.observeAll())
        awaitUntil { seen.isNotEmpty() }
        repo.upsert(folder("fld_a", 1))
        awaitUntil { seen.last().size == 1 }
        repo.upsert(folder("fld_a", 1, readOnly = true))
        awaitUntil { seen.last().single().readOnly }
        repo.delete(FolderId("fld_a"))
        awaitUntil { seen.last().isEmpty() }
    }
}

/** What every [OperatorAppRepository] must do. */
abstract class OperatorAppRepositoryContract {
    protected abstract fun createRepository(): OperatorAppRepository

    @Test
    fun `stores every field, lists by authorisation time and package, and withdraws`() = runTest {
        val repo = createRepository()
        val notes = OperatorApp("com.example.notes", "Notes, \"daily\" 📝", 200, signingCertSha256 = "a1b2".repeat(16))
        val alpha = OperatorApp("com.alpha.mail", "Mail", 200)
        val first = OperatorApp("org.first.app", "First", 100)
        listOf(notes, alpha, first).forEach { repo.upsert(it) }
        assertEquals(listOf(first, alpha, notes), repo.getAll())
        assertEquals(listOf(first, alpha, notes), repo.observeAll().first())
        assertEquals(notes, repo.get(notes.packageName))
        assertNull(repo.get("com.missing"))
        repo.upsert(notes.copy(label = "Notes"))
        assertEquals("Notes", repo.get(notes.packageName)?.label)
        // The policy is stored; an app asks before every action unless the owner chose otherwise.
        assertEquals(ScreenActionPolicy.ASK_EVERY_ACTION, repo.get(alpha.packageName)?.policy)
        repo.upsert(notes.copy(label = "Notes", policy = ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY))
        assertEquals(notes.copy(label = "Notes", policy = ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY), repo.get(notes.packageName))
        assertEquals(3, repo.getAll().size)
        repo.delete(alpha.packageName)
        repo.delete("com.missing")
        assertEquals(listOf("org.first.app", "com.example.notes"), repo.getAll().map { it.packageName })
    }

    @Test
    fun `open observers see later changes`() = runTest {
        val repo = createRepository()
        val seen = collectIn(backgroundScope, repo.observeAll())
        awaitUntil { seen.isNotEmpty() }
        repo.upsert(OperatorApp("com.example.notes", "Notes", 1))
        awaitUntil { seen.last().size == 1 }
        repo.delete("com.example.notes")
        awaitUntil { seen.last().isEmpty() }
    }
}
