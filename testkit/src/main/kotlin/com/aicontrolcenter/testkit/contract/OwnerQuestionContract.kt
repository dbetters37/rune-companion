package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.TaskId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OwnerDecision
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.tools.Capability
import com.aicontrolcenter.core.tools.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fully populated questions, so persistence tests exercise every field. */
object QuestionSamples {
    val agent = AgentId("agt_researcher")

    fun question(
        id: String,
        at: Long = 1_000,
        task: String? = "tsk_1",
        options: List<String> = listOf("Berlin, \"the capital\"", "Paris 🗼"),
    ) = OwnerRequest(
        id = RequestId(id), kind = OwnerRequestKind.QUESTION, agentId = agent, toolId = ToolId("team.ask_owner"),
        capability = Capability("team.ask_owner"), risk = RiskLevel.SAFE, summary = "A question from Researcher",
        reason = "I need to know which city,\n\"please\"", taskId = task?.let(::TaskId), rootId = task?.let { TaskId("tsk_root") },
        createdAt = at, expiresAt = at + 20 * 60_000, agentName = "Researcher 🔎", toolTitle = "Ask the owner",
        question = "Which city should the report cover?\nOne line is enough, \"thanks\".", options = options,
    )
}

/**
 * Phase 6, K3 (rules 2, 13, 33): what every [com.aicontrolcenter.core.operator.OwnerRequestRepository]
 * must do with an agent's QUESTION. A separate suite from `OwnerRequestRepositoryContract` so a
 * store can run it from the schema that adds the question columns (WP1b) on.
 */
abstract class OwnerQuestionContract {
    protected abstract fun createStores(): RequestStores

    @Test
    fun `a question is stored with its text, options and answer`() = runTest {
        val repo = createStores().requests
        val full = QuestionSamples.question("req_q1")
        val bare = QuestionSamples.question("req_q2", at = 2_000, task = null, options = emptyList()).copy(reason = null, toolId = null, agentName = null, toolTitle = null)
        repo.create(full)
        repo.create(bare)
        assertEquals(full, repo.get(full.id))
        assertEquals(bare, repo.get(bare.id))
        assertEquals(listOf(full, bare), repo.observePending().first())
        assertEquals(listOf(full), repo.observeForObjective(TaskId("tsk_root")).first())
    }

    @Test
    fun `an answer closes the question through the compare-and-set and writes no grant`() = runTest {
        val stores = createStores()
        val repo = stores.requests
        repo.create(QuestionSamples.question("req_q1"))
        repo.create(QuestionSamples.question("req_q2"))
        val answered = assertIs<DecideResult.Decided>(repo.decide(RequestId("req_q1"), OwnerDecision.Answer("Berlin, \"please\" 🙂\nthanks"), 1_100, GrantId("grt_x")))
        assertNull(answered.grant)
        assertEquals(OwnerRequestStatus.APPROVED, answered.request.status)
        assertEquals("Berlin, \"please\" 🙂\nthanks", answered.request.answer)
        assertEquals(1_100, answered.request.decidedAt)
        assertNull(answered.request.decisionNote)
        assertEquals(answered.request, repo.get(RequestId("req_q1")))
        val decideYourself = assertIs<DecideResult.Decided>(repo.decide(RequestId("req_q2"), OwnerDecision.Answer(null), 1_200, GrantId("grt_y")))
        assertNull(decideYourself.request.answer, "decide yourself")
        assertEquals(OwnerRequestStatus.APPROVED, repo.get(RequestId("req_q2"))!!.status)
        assertEquals(emptyList(), stores.grants.observeAll().first(), "an answer never grants")
        assertNull(stores.grants.findByRequest(RequestId("req_q1")))
        assertIs<DecideResult.AlreadyClosed>(repo.decide(RequestId("req_q1"), OwnerDecision.Answer("Paris"), 1_300, GrantId("grt_z")))
        assertEquals("Berlin, \"please\" 🙂\nthanks", repo.get(RequestId("req_q1"))!!.answer)
    }

    @Test
    fun `a question takes no approval, refusal or scope, and an answer fits nothing else`() = runTest {
        val stores = createStores()
        val repo = stores.requests
        repo.create(QuestionSamples.question("req_q"))
        for (decision in listOf(OwnerDecision.Approve(), OwnerDecision.Deny(), OwnerDecision.Grant(GrantScope.ALWAYS), OwnerDecision.Answer("  "))) {
            assertIs<DecideResult.NotAccepted>(repo.decide(RequestId("req_q"), decision, 1_100, GrantId("grt_1")), decision.toString())
        }
        assertEquals(OwnerRequestStatus.PENDING, repo.get(RequestId("req_q"))!!.status)
        val approval = OperatorSamples.request("req_a", OwnerRequestKind.APPROVAL)
        val permission = OperatorSamples.request("req_p")
        repo.create(approval)
        repo.create(permission)
        assertIs<DecideResult.NotAccepted>(repo.decide(approval.id, OwnerDecision.Answer("yes"), 1_100, GrantId("grt_2")))
        assertIs<DecideResult.NotAccepted>(repo.decide(permission.id, OwnerDecision.Answer("yes"), 1_100, GrantId("grt_3")))
        assertEquals(listOf(OwnerRequestStatus.PENDING, OwnerRequestStatus.PENDING), listOf(repo.get(approval.id)!!.status, repo.get(permission.id)!!.status))
        assertTrue(stores.grants.observeAll().first().isEmpty())
    }

    @Test
    fun `answering a question leaves every other request as it was (rules 2 and 33)`() = runTest {
        val stores = createStores()
        val repo = stores.requests
        val approval = OperatorSamples.request("req_a", OwnerRequestKind.APPROVAL)
        val permission = OperatorSamples.request("req_p")
        repo.create(approval)
        repo.create(permission)
        repo.create(QuestionSamples.question("req_q"))
        repo.decide(RequestId("req_q"), OwnerDecision.Answer("yes, do it"), 1_100, GrantId("grt_q"))
        assertEquals(approval, repo.get(approval.id))
        assertEquals(permission, repo.get(permission.id))
        assertEquals(listOf(permission, approval).sortedBy { it.id.value }, repo.observePending().first().sortedBy { it.id.value })
    }

    @Test
    fun `a question never counts as a refusal and cannot be opened for asking again`() = runTest {
        val repo = createStores().requests
        repo.create(QuestionSamples.question("req_q"))
        repo.close(RequestId("req_q"), OwnerRequestStatus.EXPIRED, 1_100, "No answer in time")
        assertNull(repo.lastRefusal(QuestionSamples.agent, Capability("team.ask_owner"), com.aicontrolcenter.core.tools.CapabilityConstraint.None, 0))
        assertNull(repo.letAskAgain(RequestId("req_q"), 1_200))
        assertNull(repo.countAskedAgain(RequestId("req_q")))
        assertEquals(OwnerRequestStatus.EXPIRED, repo.get(RequestId("req_q"))!!.status)
        // Like any request, a question nobody waits for is cancelled with its task's asks.
        repo.create(QuestionSamples.question("req_q2").copy(toolId = null))
        assertEquals(listOf(RequestId("req_q2")), repo.cancelAsksForTask(TaskId("tsk_1"), 1_300, "The task ended").map { it.id })
    }

    @Test
    fun `of concurrent answers exactly one wins`() = runTest {
        val repo = createStores().requests
        repo.create(QuestionSamples.question("req_q"))
        val results = withContext(Dispatchers.Default) {
            (1..20).map { n -> async { repo.decide(RequestId("req_q"), OwnerDecision.Answer("answer $n"), 1_100, GrantId("grt_$n")) } }.awaitAll()
        }
        assertEquals(1, results.count { it is DecideResult.Decided })
        val stored = repo.get(RequestId("req_q"))!!
        assertEquals((results.single { it is DecideResult.Decided } as DecideResult.Decided).request.answer, stored.answer)
    }
}
