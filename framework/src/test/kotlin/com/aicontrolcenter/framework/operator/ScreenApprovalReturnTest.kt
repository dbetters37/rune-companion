package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorApp
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OwnerRequestKind
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.tasks.withPausableTimeout
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecution
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.ScreenCall
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review 5, A1: the owner answers inside AI Control Center, so while the answer is given the app
 * in front is this app, not the one operated. An approved screen action waits (outside every time
 * limit) until the operated app is in front again, re-checks the control and the window, and only
 * then acts. "Allow just this once" for a screen read works the same way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenApprovalReturnTest {
    private val pkg = "com.example.messages"
    private val sam = FakeControl(role = "TextView", text = "Chat with Sam", clickable = false, nodeKey = "who")
    private val compose = FakeControl(role = "EditText", description = "Message", text = "See you at 6", editable = true, clickable = false, nodeKey = "compose")
    private val send = FakeControl(text = "Send", nodeKey = "send")
    private val messages = FakeScreen(pkg, "Sam", listOf(sam, compose, send))
    private val ownApp = FakeScreen(OperatorFixture.OWN, "Approval needed", listOf(FakeControl(text = "Approve")))

    private class World(val broker: BrokerFixture, val tools: OperatorFixture, val executor: ToolExecutor)

    private suspend fun world(test: TestScope, policy: ScreenActionPolicy, vararg toolIds: String): World {
        val b = BrokerFixture(test).setUp()
        b.agent = b.agent.copy(toolIds = b.agent.toolIds + toolIds.map(::ToolId))
        b.agents.upsert(b.agent)
        b.apps.upsert(OperatorApp(pkg, "Messages", 20, "cd".repeat(32), policy))
        val o = OperatorFixture(messages)
        o.authorise(pkg, "Messages", policy)
        val registry = DefaultToolRegistry().also { r -> toolIds.forEach { r.register(o.tool(it)) } }
        return World(b, o, ToolExecutor(registry, b.broker, b.activity, b.clock, timeSource = test.testTimeSource))
    }

    private suspend fun grantAlways(b: BrokerFixture) {
        for ((id, capability) in listOf("grt_op" to OperatorCapabilities.SCREEN_OPERATE, "grt_read" to OperatorCapabilities.SCREEN_READ)) {
            b.grants.insert(PermissionGrant(GrantId(id), b.agent.id, capability, CapabilityConstraint.App(pkg), GrantScope.ALWAYS, grantedAt = 1))
        }
    }

    private fun TestScope.tapSend(w: World): Deferred<ToolExecution> =
        async { w.executor.execute(ToolCallRequest("c1", "screen_tap", json("exact_text" to "Send")), w.broker.context()) }.also { runCurrent() }

    /** The owner opens this app to answer; [back] ms later the operated app is in front again (null: never). */
    private fun TestScope.answerInApp(w: World, back: Long?, screenThen: FakeScreen = messages, answer: suspend () -> Unit) {
        w.tools.screen.screen = ownApp
        launch {
            answer()
            if (back != null) {
                delay(back)
                w.tools.screen.screen = screenThen
            }
        }
        runCurrent()
    }

    private val World.taps get() = tools.screen.performed.filterIsInstance<ScreenCall.Tap>()

    @Test
    fun `an approved tap waits for the operated app to be in front again, then runs`() = runTest {
        val w = world(this, ScreenActionPolicy.ASK_EVERY_ACTION, "screen.tap")
        grantAlways(w.broker)
        val call = tapSend(w)
        val approval = w.broker.pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
        assertTrue(approval.details.any { "Chat with Sam" in it }, approval.details.toString())
        answerInApp(w, back = 4_000) { w.broker.owner.approve(approval.id) }
        val done = call.await()
        assertEquals(ToolCallEnd.OK, done.end, done.outcome.content)
        assertEquals("Send", w.taps.single().element.text)
    }

    @Test
    fun `an approved tap whose app does not come back is refused, and says why`() = runTest {
        val w = world(this, ScreenActionPolicy.ASK_EVERY_ACTION, "screen.tap")
        grantAlways(w.broker)
        val call = tapSend(w)
        val approval = w.broker.pending()
        answerInApp(w, back = null) { w.broker.owner.approve(approval.id) }
        val done = call.await()
        assertEquals(ToolCallEnd.DENIED, done.end)
        assertTrue("\"Messages\" did not come back within 30 seconds" in done.outcome.content, done.outcome.content)
        assertTrue(w.taps.isEmpty())
    }

    @Test
    fun `an approved tap whose window changed while the owner was away is refused`() = runTest {
        val w = world(this, ScreenActionPolicy.ASK_EVERY_ACTION, "screen.tap")
        grantAlways(w.broker)
        val call = tapSend(w)
        val approval = w.broker.pending()
        val otherChat = messages.copy(controls = listOf(sam.copy(text = "Chat with Mallory"), compose, send))
        answerInApp(w, back = 2_000, screenThen = otherChat) { w.broker.owner.approve(approval.id) }
        val done = call.await()
        assertEquals(ToolCallEnd.DENIED, done.end)
        assertTrue("what the window shows changed" in done.outcome.content, done.outcome.content)
        assertTrue(w.taps.isEmpty())
    }

    @Test
    fun `typing with submit approved in the app runs once the app is back`() = runTest {
        // Even where the owner chose fewer questions, pressing the action key asks; the answer is given in this app.
        val w = world(this, ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY, "screen.type")
        grantAlways(w.broker)
        val call = async {
            w.executor.execute(ToolCallRequest("t1", "screen_type", json("exact_text" to "See you at 6", "text" to "See you at 7", "submit" to true)), w.broker.context())
        }
        runCurrent()
        val approval = w.broker.pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
        answerInApp(w, back = 1_500) { w.broker.owner.approve(approval.id) }
        val done = call.await()
        assertEquals(ToolCallEnd.OK, done.end, done.outcome.content)
        assertEquals("See you at 7", w.tools.screen.typed.single().second)
    }

    @Test
    fun `a read allowed just this once in the app runs once the app is back`() = runTest {
        val w = world(this, ScreenActionPolicy.ASK_EVERY_ACTION, "screen.read")
        val call = async { w.executor.execute(ToolCallRequest("r1", "screen_read", json()), w.broker.context()) }
        runCurrent()
        val ask = w.broker.pending()
        assertEquals(OwnerRequestKind.PERMISSION, ask.kind)
        answerInApp(w, back = 3_000) { w.broker.owner.grant(ask.id, GrantScope.ONCE) }
        val done = call.await()
        assertEquals(ToolCallEnd.OK, done.end, done.outcome.content)
        assertTrue("Chat with Sam" in done.outcome.content, done.outcome.content)
    }

    @Test
    fun `a tap allowed just this once and then approved runs once the app is back`() = runTest {
        val w = world(this, ScreenActionPolicy.ASK_EVERY_ACTION, "screen.tap")
        val call = tapSend(w)
        val ask = w.broker.pending()
        assertEquals(OwnerRequestKind.PERMISSION, ask.kind)
        answerInApp(w, back = 1_000) { w.broker.owner.grant(ask.id, GrantScope.ONCE) }
        val approval = w.broker.pending()
        assertEquals(OwnerRequestKind.APPROVAL, approval.kind)
        answerInApp(w, back = 1_000) { w.broker.owner.approve(approval.id) }
        val done = call.await()
        assertEquals(ToolCallEnd.OK, done.end, done.outcome.content)
        assertEquals(1, w.taps.size)
    }

    @Test
    fun `waiting for the app to come back does not count against the run's time limit`() = runTest {
        val w = world(this, ScreenActionPolicy.ASK_EVERY_ACTION, "screen.tap")
        grantAlways(w.broker)
        val call = async {
            withPausableTimeout(10_000, testTimeSource) {
                w.executor.execute(ToolCallRequest("c1", "screen_tap", json("exact_text" to "Send")), w.broker.context())
            }
        }
        runCurrent()
        val approval = w.broker.pending()
        // The owner takes 5 minutes to answer, and 20 more seconds to get back to the app: both are the owner's time.
        launch {
            delay(5 * 60_000)
            w.tools.screen.screen = ownApp
            w.broker.owner.approve(approval.id)
            delay(20_000)
            w.tools.screen.screen = messages
        }
        val done = assertNotNull(call.await(), "the run's limit ran out while the owner was answering")
        assertEquals(ToolCallEnd.OK, done.end, done.outcome.content)
    }

    @Test
    fun `Stop pressed while waiting for the app ends the wait and nothing is done`() = runTest {
        val w = world(this, ScreenActionPolicy.ASK_EVERY_ACTION, "screen.tap")
        grantAlways(w.broker)
        val call = tapSend(w)
        val approval = w.broker.pending()
        answerInApp(w, back = null) {
            w.broker.owner.approve(approval.id)
            delay(1_000)
            w.tools.screen.stop()
        }
        val done = call.await()
        assertEquals(ToolCallEnd.DENIED, done.end)
        assertTrue("pressed Stop" in done.outcome.content, done.outcome.content)
        assertFalse(w.taps.isNotEmpty())
    }
}
