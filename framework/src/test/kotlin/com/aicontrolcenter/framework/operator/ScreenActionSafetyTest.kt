package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.GrantId
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.llm.ToolCallRequest
import com.aicontrolcenter.core.operator.GrantScope
import com.aicontrolcenter.core.operator.OperatorCapabilities
import com.aicontrolcenter.core.operator.OperatorGate
import com.aicontrolcenter.core.operator.PermissionGrant
import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.framework.tools.DefaultToolRegistry
import com.aicontrolcenter.framework.tools.ToolCallEnd
import com.aicontrolcenter.framework.tools.ToolExecutor
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.ScreenCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review 5 regressions for screen control: the per-app policy that asks before every action (P4),
 * the classification for apps that ask only before consequential actions (P4), the whole window in
 * a consequential preview and its re-check (P3), local-only mode (P6), no screen read for a call
 * the owner's switches refuse (P7) and Back naming the launcher (R1).
 */
class ScreenActionSafetyTest {
    private val pkg = "com.example.pay"
    private val payee = FakeControl(role = "TextView", text = "To: Alice Smith (alice@bank)", clickable = false, nodeKey = "payee")
    private val amount = FakeControl(role = "EditText", hint = "Amount", text = "20.00", editable = true, clickable = false, nodeKey = "amt")
    private val send = FakeControl(text = "Send money", resourceId = "$pkg:id/send", nodeKey = "send", context = "Send money")
    private val note = FakeControl(role = "EditText", hint = "Note", editable = true, clickable = false, nodeKey = "note")
    private val details = FakeControl(text = "Details", nodeKey = "details")
    private val list = FakeControl(role = "RecyclerView", scrollable = true, clickable = false, nodeKey = "list")
    private val transfer = FakeScreen(pkg, "Transfer", listOf(payee, amount, note, details, send, list))

    private val taps get() = f.screen.performed.filterIsInstance<ScreenCall.Tap>()
    private val f = OperatorFixture(initialScreen = transfer)

    @Test
    fun `an app asks before every tap, typing, scroll, Back and Home by default, with the whole window shown`() = runTest {
        f.authorise(pkg, "Pay", ScreenActionPolicy.ASK_EVERY_ACTION)
        f.authorise("com.android.launcher3", "Home")
        val calls = listOf(
            "screen.tap" to arrayOf<Pair<String, Any?>>("exact_text" to "Details"),
            "screen.type" to arrayOf<Pair<String, Any?>>("exact_text" to "20.00", "text" to "25.00"),
            "screen.scroll" to arrayOf<Pair<String, Any?>>("direction" to "down"),
            "screen.back" to emptyArray(),
            "screen.home" to emptyArray(),
        )
        for ((id, args) in calls) {
            val a = f.assess(id, *args)
            assertRisk(RiskLevel.CONSEQUENTIAL, a)
            assertTrue("Asked because \"Pay\" ($pkg) is set to ask you before every action." in a.preview, a.preview)
            // The owner sees the whole window: its title, the payee, the fields and every other text.
            assertTrue("window \"Transfer\"" in a.preview && "TextView: \"To: Alice Smith (alice@bank)\"" in a.preview, a.preview)
            assertTrue("Field \"Amount\" contains: \"20.00\"" in a.preview, a.preview)
            assertTrue("May send data off this phone through \"Pay\" ($pkg)." in a.preview, a.preview)
        }
        // Reading, waiting and screenshots do not act, so they do not ask.
        assertRisk(RiskLevel.SAFE, f.assess("screen.read"))
        assertRisk(RiskLevel.SAFE, f.assess("screen.wait_for", "text" to "Details"))
        assertRisk(RiskLevel.MODIFYING, f.assess("screen.screenshot"))
        // Assessing read the window without starting a session: no screen call an indicator follows.
        assertTrue(f.screen.calls.isEmpty(), f.screen.calls.toString())
        assertTrue(f.screen.inspected.isNotEmpty())
    }

    @Test
    fun `an ordinary tap assessed while the app asked only before consequential actions is refused once the owner makes it ask every time`() = runTest {
        f.authorise(pkg, "Pay")
        val args = json("exact_text" to "Details")
        val tap = f.tool("screen.tap")
        val assessment = tap.assess(args, f.context)
        assertRisk(RiskLevel.MODIFYING, assessment)
        f.operatorApps.upsert(f.operatorApps.get(pkg)!!.copy(policy = ScreenActionPolicy.ASK_EVERY_ACTION))
        val outcome = tap.execute(args, f.context, assessment)
        assertFalse(outcome.ok)
        assertTrue("ask before every action" in outcome.content, outcome.content)
        assertTrue(taps.isEmpty())
    }

    /** P4b and the triage's P4x: OK in a delete dialog, Empty trash, a ride request, OAuth Allow, a slider, a code field, Continue under a purchase title. */
    @Test
    fun `controls the first word list missed ask even where the owner chose fewer questions`() = runTest {
        val gallery = "com.example.gallery"
        val screens = listOf(
            FakeScreen(gallery, "Delete 248 photos?", listOf(FakeControl(text = "Cancel", nodeKey = "c"), FakeControl(text = "OK", nodeKey = "ok", context = "Delete 248 photos?"))),
            FakeScreen(gallery, "Trash", listOf(FakeControl(text = "Empty trash", nodeKey = "e"))),
            FakeScreen(gallery, "Ride", listOf(FakeControl(text = "Request Lyft XL", nodeKey = "r"))),
            FakeScreen(gallery, "Sign in", listOf(FakeControl(text = "Allow", nodeKey = "a", context = "Example App wants to access your Google Account"))),
            FakeScreen(gallery, "Confirm purchase of 3 items for 249.00", listOf(FakeControl(text = "Continue", nodeKey = "go"))),
            FakeScreen(gallery, "Basket", listOf(FakeControl(role = "TextView", text = "Total: €249.00", clickable = false, nodeKey = "t"), FakeControl(text = "Done", nodeKey = "d"))),
        )
        f.authorise(gallery, "Gallery")
        for (s in screens) {
            f.screen.screen = s
            val target = s.controls.last().text!!
            val a = f.assess("screen.tap", "exact_text" to target)
            assertRisk(RiskLevel.CONSEQUENTIAL, a)
        }
        f.screen.screen = FakeScreen(
            gallery, "Pay",
            listOf(
                FakeControl(role = "SeekBar", description = "Slide to pay", scrollable = true, clickable = false, nodeKey = "slider"),
                FakeControl(role = "EditText", hint = "Code", editable = true, clickable = false, nodeKey = "otp"),
            ),
        )
        assertRisk(RiskLevel.CONSEQUENTIAL, f.assess("screen.scroll", "direction" to "right", "exact_text" to "Slide to pay"))
        // Without a target the first scrollable element moves, and it is classified the same way.
        assertRisk(RiskLevel.CONSEQUENTIAL, f.assess("screen.scroll", "direction" to "right"))
        val read = f.ok("screen.read").data!!.jsonObject.getValue("snapshot").jsonPrimitive.long
        assertRisk(RiskLevel.CONSEQUENTIAL, f.assess("screen.type", "snapshot" to read, "element" to 2, "text" to "481516"))
        // A plain "OK" on a screen that asks for nothing consequential stays an ordinary tap.
        f.screen.screen = FakeScreen(gallery, "Photos", listOf(FakeControl(role = "TextView", text = "Swipe left for the next photo", clickable = false, nodeKey = "h"), FakeControl(text = "OK", nodeKey = "ok")))
        assertRisk(RiskLevel.MODIFYING, f.assess("screen.tap", "exact_text" to "OK"))
    }

    /** P3: the payee is not a field and not in the button's row; it is shown, and a change to it refuses the tap. */
    @Test
    fun `a consequential tap shows and pins the whole window, so a changed payee refuses it`() = runTest {
        f.authorise(pkg, "Pay")
        val args = json("exact_text" to "Send money")
        val tool = f.tool("screen.tap")
        val assessment = tool.assess(args, f.context)
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertTrue("To: Alice Smith" in assessment.preview, assessment.preview)
        f.screen.screen = transfer.copy(controls = transfer.controls.map { if (it == payee) payee.copy(text = "To: Mallory (mallory@bank)") else it })
        assertEquals("what the window shows changed", assessment.prepared!!.changedSince())
        val outcome = tool.execute(args, f.context, assessment)
        assertFalse(outcome.ok)
        assertTrue(taps.isEmpty())
        // Unchanged, it runs.
        f.screen.screen = transfer
        val again = tool.assess(args, f.context)
        assertTrue(tool.execute(args, f.context, again).ok)
        assertEquals(1, taps.size)
    }

    /** P3b: a field after 210 lines of text is shown and pinned; the preview read is not cut at 200 elements. */
    @Test
    fun `a field far down a long window is shown and a change to it refuses the tap`() = runTest {
        val browser = "com.example.browser"
        val filler = (1..210).map { FakeControl(role = "TextView", text = "Line $it of the page", clickable = false, nodeKey = "t$it") }
        val to = FakeControl(role = "EditText", hint = "Recipient IBAN", text = "DE00 1111 (Alice)", editable = true, clickable = false, nodeKey = "to")
        val sendTransfer = FakeControl(text = "Send transfer", nodeKey = "send")
        f.screen.screen = FakeScreen(browser, "Bank", filler + to + sendTransfer)
        f.authorise(browser, "Browser")
        val assessment = f.assess("screen.tap", "exact_text" to "Send transfer")
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertTrue("Field \"Recipient IBAN\" contains: \"DE00 1111 (Alice)\"" in assessment.preview, assessment.preview)
        f.screen.screen = FakeScreen(browser, "Bank", filler + to.copy(text = "DE99 6666 (Mallory)") + sendTransfer)
        assertNotNull(assessment.prepared!!.changedSince())
    }

    /** P6: local-only mode refuses every tool that acts on another app, before anything is read; reading stays allowed. */
    @Test
    fun `local-only mode refuses acting on a screen, before anything of it is read`() = runTest {
        f.authorise(pkg, "Pay")
        f.authorise("com.android.launcher3", "Home")
        f.settings = f.settings.copy(localOnlyMode = true)
        for ((id, args) in listOf(
            "screen.tap" to arrayOf<Pair<String, Any?>>("exact_text" to "Details"),
            "screen.type" to arrayOf<Pair<String, Any?>>("exact_text" to "20.00", "text" to "x"),
            "screen.scroll" to arrayOf<Pair<String, Any?>>("direction" to "down"),
            "screen.back" to emptyArray(),
            "screen.home" to emptyArray(),
        )) {
            val reason = f.rejected(id, *args)
            assertTrue("Local-only mode is on" in reason && "may send data off this phone" in reason, reason)
            assertTrue(f.tool(id).spec.sendsDataOffDevice, id)
        }
        assertTrue(f.screen.calls.isEmpty() && f.screen.inspected.isEmpty())
        assertTrue(f.ok("screen.read").ok)
        assertFalse(f.tool("screen.read").spec.sendsDataOffDevice)
        assertFalse(f.tool("screen.screenshot").spec.sendsDataOffDevice)

        // The broker refuses them too, on its own fresh facts, should the setting change while a call waits.
        val b = BrokerFixture(this).setUp()
        b.settings.update { it.copy(localOnlyMode = true) }
        val tap = f.tool("screen.tap").spec
        b.agents.upsert(b.agent.copy(toolIds = b.agent.toolIds + tap.id))
        val refusal = OperatorGate.refusal(b.facts.forCall(b.agent.id, tap, b.assessment(RiskLevel.MODIFYING, b.inNotes, "Tap")))
        assertTrue(refusal != null && "Local-only mode is on" in refusal, refusal.toString())
    }

    /** P7: the owner's switches are read before anything of the screen: no find, no read, no session, no screen text logged. */
    @Test
    fun `a call the owner's switches refuse reads nothing of the screen`() = runTest {
        val notes = "com.example.notes"
        f.screen.screen = FakeScreen(
            notes, "Notes",
            listOf(
                FakeControl(role = "EditText", hint = "Body", text = "my bank PIN reminder: 4711", editable = true, clickable = false, nodeKey = "b"),
                FakeControl(text = "Share note", nodeKey = "s"),
            ),
        )
        f.authorise(notes, "Notes")
        val b = BrokerFixture(this).setUp()
        b.settings.update { it.copy(accessibilityOperatorEnabled = false) }
        f.settings = b.settings.get()
        val tool = f.tool("screen.tap")
        val agent = b.agent.copy(toolIds = b.agent.toolIds + tool.spec.id)
        b.agents.upsert(agent)
        b.grants.insert(PermissionGrant(GrantId("grt_tap"), agent.id, OperatorCapabilities.SCREEN_OPERATE, CapabilityConstraint.App(notes), GrantScope.ALWAYS, grantedAt = 1))
        val executor = ToolExecutor(DefaultToolRegistry().also { it.register(tool) }, b.broker, b.activity, b.clock)
        val exec = executor.execute(ToolCallRequest("c1", "screen_tap", json("exact_text" to "Share note")), b.context(who = agent))
        assertTrue(exec.end != ToolCallEnd.OK)
        assertTrue("Screen control is switched off in Settings." in exec.outcome.content, exec.outcome.content)
        assertTrue(f.screen.calls.isEmpty() && f.screen.inspected.isEmpty(), f.screen.calls.toString())
        val logged = b.events(ActivityType.TOOL_CALL).joinToString { it.detail }
        assertFalse("Share note" in logged || "PIN" in logged, logged)
        // Phone operator switched off: the same.
        f.settings = f.settings.copy(accessibilityOperatorEnabled = true, operatorEnabled = false)
        assertTrue("Phone operator is switched off" in f.rejected("screen.read"))
        assertTrue(f.screen.calls.isEmpty() && f.screen.inspected.isEmpty())
        // OPERATOR-OFF: through the executor it is a refusal with the gate's code, logged with it.
        val off = executor.execute(ToolCallRequest("c2", "screen_tap", json("exact_text" to "Share note")), b.context(who = agent))
        assertEquals(ToolCallEnd.DENIED, off.end)
        assertEquals(com.aicontrolcenter.core.operator.OperatorGate.OPERATOR_OFF_CODE, off.outcome.refusal)
        val line = b.events(ActivityType.TOOL_CALL).last().data!!
        assertEquals(com.aicontrolcenter.core.operator.OperatorGate.OPERATOR_OFF_CODE, (line["refusal"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    /** R1: Back may leave the app, so the broker refuses it unless the launcher is authorised, like Home. */
    @Test
    fun `Back is refused unless the home screen app is authorised too`() = runTest {
        f.authorise(pkg, "Pay")
        val back = f.assess("screen.back")
        assertEquals("com.android.launcher3", back.leadsTo)
        val b = BrokerFixture(this).setUp()
        val spec = f.tool("screen.back").spec
        b.agents.upsert(b.agent.copy(toolIds = b.agent.toolIds + spec.id))
        b.apps.upsert(b.app.copy(packageName = pkg))
        val refusal = OperatorGate.refusal(b.facts.forCall(b.agent.id, spec, back))
        assertTrue(refusal != null && "hand control to another app" in refusal, refusal.toString())
        b.apps.upsert(b.app.copy(packageName = "com.android.launcher3"))
        assertNull(OperatorGate.refusal(b.facts.forCall(b.agent.id, spec, back)))
        // After Back, the agent is told where it led.
        f.authorise("com.android.launcher3", "Home")
        f.screen.onAction = { call -> if (call is ScreenCall.Global) f.screen.screen = FakeScreen("com.android.launcher3", "Home") }
        val (_, outcome) = f.run("screen.back")
        assertTrue("it left \"Pay\", and com.android.launcher3 is in front now" in outcome.content, outcome.content)
    }
}
