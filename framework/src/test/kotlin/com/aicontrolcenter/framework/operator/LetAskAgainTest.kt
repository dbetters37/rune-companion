package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.RequestId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.AskResult
import com.aicontrolcenter.core.operator.DecideResult
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequest
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.OwnerRequestStatus
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.tools.Authorization
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.BrokerFixture.Companion.MINUTE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 2, B2: after a Deny the owner can change their mind at once. Built from the verifier's
 * `V5BrokerMeetingProbeTest` T2 (a refused permission without a target is refused unasked for
 * ten minutes, in any task or chat, and so is the agent's own ask): "Let it ask again" on the
 * refused request ends that, through the owner-only port, and gives nothing by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LetAskAgainTest {
    private suspend fun TestScope.fixture() = BrokerFixture(this).setUp()

    private fun Authorization.reason(): String = assertIs<Authorization.Denied>(this).reason

    private val page = CapabilityConstraint.None

    private fun BrokerFixture.readPage(task: String?, conversation: String? = null) =
        start(readPage, assessment(RiskLevel.SAFE, page, "Read the web page example.org"), context(task = task, conversation = conversation))

    private fun BrokerFixture.requester() = DefaultCapabilityRequester(facts, requests, grants, waiters, activity, ids, clock)

    @Test
    fun `B2 - after a refusal the owner lets the agent ask again, and the next call is put to the owner as usual`() = runTest {
        val f = fixture()
        val first = f.readPage("tsk_a")
        val refused = f.pending()
        f.owner.deny(refused.id, "not now")
        assertIs<Authorization.Denied>(first.await())

        // The verifier's finding: in a new task and in a chat the retry is refused without asking, and so is the agent's own ask.
        advanceTimeBy(MINUTE)
        assertTrue("not asked again" in f.readPage("tsk_retry").await().reason())
        assertTrue("not asked again" in f.readPage(null, "cnv_1").await().reason())
        assertIs<AskResult.Refused>(f.requester().ask(f.context(task = "tsk_c"), OperatorCapabilities.WEB_READ, page, "The owner just told me to go ahead."))
        assertTrue(f.owner.pending.first().isEmpty())
        assertTrue(f.requests.get(refused.id)!!.holdsBackAt(f.clock.now()))

        // The owner changes their mind on the refused request.
        val opened = assertIs<OwnerResult.Done<OwnerRequest>>(f.owner.letAskAgain(refused.id)).value
        assertEquals(f.clock.now(), opened.askAgainAt)
        assertEquals(OwnerRequestStatus.DENIED, opened.status, "the refusal stays a refusal in the history")
        assertFalse(opened.holdsBackAt(f.clock.now()))
        assertTrue(f.grants.observeAll().first().isEmpty(), "letting it ask gives nothing")
        val logged = f.events(ActivityType.PERMISSION).single { it.title.startsWith("Owner let the agent ask again") }
        assertEquals(refused.id.value, logged.data?.get("requestId")?.jsonPrimitive?.content)
        assertEquals("true", logged.data?.get("byOwner")?.jsonPrimitive?.content)

        // At once, well inside the ten minutes: the same call raises a normal request and waits for the answer.
        val again = f.readPage("tsk_b")
        assertTrue(again.isActive, "the call waits for the owner instead of being refused unasked")
        val asked = f.pending()
        assertEquals(OwnerRequestKind.PERMISSION, asked.kind)
        assertEquals(listOf(GrantScope.ONCE, GrantScope.TASK, GrantScope.ALWAYS), asked.offeredScopes)
        assertIs<DecideResult.Decided>(f.owner.grant(asked.id, GrantScope.ONCE))
        assertIs<Authorization.AllowedByOwner>(again.await())
    }

    @Test
    fun `B2 - the agent's own ask is raised again too, and a new refusal starts a new wait`() = runTest {
        val f = fixture()
        val requester = f.requester()
        val raised = assertIs<AskResult.Raised>(requester.ask(f.context(), OperatorCapabilities.WEB_READ, page, "To read reviews."))
        f.owner.deny(raised.requestId)
        advanceTimeBy(2 * MINUTE)
        assertIs<AskResult.Refused>(requester.ask(f.context(), OperatorCapabilities.WEB_READ, page, "Please."))

        assertIs<OwnerResult.Done<OwnerRequest>>(f.owner.letAskAgain(raised.requestId))
        val second = assertIs<AskResult.Raised>(requester.ask(f.context(), OperatorCapabilities.WEB_READ, page, "The owner said I may ask."))
        assertEquals(second.requestId, f.pending().id)

        // Refused again: that refusal holds on its own, the earlier one stays opened.
        f.owner.deny(second.requestId)
        advanceTimeBy(MINUTE)
        val held = assertIs<AskResult.Refused>(requester.ask(f.context(), OperatorCapabilities.WEB_READ, page, "Once more."))
        assertTrue("not asked again for 9 more minutes" in held.reason, held.reason)
        assertEquals(1, f.requests.get(second.requestId)?.askedAgain)
        // Asked for on the earlier refusal again: that one keeps its time, and the newer refusal of the same permission is opened with it.
        val firstOpenedAt = f.requests.get(raised.requestId)!!.askAgainAt
        advanceTimeBy(MINUTE)
        assertEquals(firstOpenedAt, assertIs<OwnerResult.Done<OwnerRequest>>(f.owner.letAskAgain(raised.requestId)).value.askAgainAt)
        assertEquals(f.clock.now(), f.requests.get(second.requestId)?.askAgainAt)
        assertNull(f.requests.lastRefusal(f.agent.id, OperatorCapabilities.WEB_READ, page, 0), "opening one refusal opens every refusal of that agent, permission and target")
        assertEquals(2, f.events(ActivityType.PERMISSION).count { it.title.startsWith("Owner let the agent ask again") })
        // With nothing held any more, once more changes and logs nothing.
        assertIs<OwnerResult.Done<OwnerRequest>>(f.owner.letAskAgain(second.requestId))
        assertEquals(2, f.events(ActivityType.PERMISSION).count { it.title.startsWith("Owner let the agent ask again") })
    }

    @Test
    fun `B2 - only a refused permission can be opened, and another target or agent is still held`() = runTest {
        val f = fixture()
        assertIs<OwnerResult.Refused>(f.owner.letAskAgain(RequestId("req_missing")))

        // An approval the owner denied holds nothing back, so there is nothing to open.
        val delete = f.start(f.deleteInFolder, f.assessment(RiskLevel.CONSEQUENTIAL, summary = "Delete \"old.txt\" in Reports"))
        val permission = f.pending()
        f.owner.grant(permission.id, GrantScope.ALWAYS)
        testScheduler.runCurrent()
        val approval = f.pending()
        f.owner.deny(approval.id)
        delete.await()
        assertTrue("Only a permission you refused" in assertIs<OwnerResult.Refused>(f.owner.letAskAgain(approval.id)).reason)
        assertTrue("Only a permission you refused" in assertIs<OwnerResult.Refused>(f.owner.letAskAgain(permission.id)).reason)

        // Two refusals: the web, and the screen of Notes. Opening the first leaves the second in force.
        val web = f.readPage("tsk_a")
        val webRefusal = f.pending()
        f.owner.deny(webRefusal.id)
        web.await()
        val tap = f.start(f.tap, f.assessment(RiskLevel.MODIFYING, f.inNotes, "Tap \"New note\" in Notes"))
        val tapRefusal = f.pending()
        f.owner.deny(tapRefusal.id)
        tap.await()
        assertIs<OwnerResult.Done<OwnerRequest>>(f.owner.letAskAgain(webRefusal.id))
        assertTrue("not asked again" in f.broker.authorize(f.tap, f.assessment(RiskLevel.MODIFYING, f.inNotes, "Tap \"New note\" in Notes"), f.context()).reason())
        assertNull(f.requests.get(tapRefusal.id)?.askAgainAt)
    }

    @Test
    fun `B2 - the model is told how the owner can end the wait, by a way that exists`() = runTest {
        val f = fixture()
        val first = f.readPage("tsk_a")
        f.owner.deny(f.pending().id)
        first.await()
        advanceTimeBy(6 * MINUTE)
        val told = f.readPage("tsk_b").await().reason()
        assertTrue("refused Operator the permission \"Read web pages\" 6 minutes ago" in told, told)
        assertTrue("not asked again for 4 more minutes" in told, told)
        assertTrue("Tools, History, on the refused request, with \"Let it ask again\"" in told, told)
        assertFalse("can still allow it in the app" in told, "the old text named a way that did not exist: $told")
        assertFalse("Tools > Allowed" in told || "Tools › Allowed" in told, told)
    }
}
