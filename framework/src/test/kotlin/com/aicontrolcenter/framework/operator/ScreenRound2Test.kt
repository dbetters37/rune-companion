package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.ScreenActionPolicy
import com.aicontrolcenter.core.operator.ScreenPreview
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.ScreenCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 2 of review 5 for the screen tools, built from the verifiers' probes: what a
 * consequential preview shows and pins (S1), which advance and commit buttons ask in an app set
 * to ask less (S2), typographic quotes in a quoted command (S5) and a service state that is not
 * known yet (S6).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenRound2Test {
    private val pkg = "com.example.pay"
    private val f = OperatorFixture()
    private val taps get() = f.screen.performed.filterIsInstance<ScreenCall.Tap>()

    private fun text(text: String, key: String) = FakeControl(role = "TextView", text = text, clickable = false, nodeKey = key)

    // ---- S1 ----

    /** (a) and (b): the owner sees which option a Pay would use, and a moved selection refuses the tap. */
    @Test
    fun `the approval shows checked and selected options, and a changed option refuses the tap`() = runTest {
        val repeat = FakeControl(role = "CheckBox", text = "Repeat every month", checkable = true, checked = true, nodeKey = "repeat")
        val standard = FakeControl(role = "Tab", text = "Standard delivery", nodeKey = "std")
        val express = FakeControl(role = "Tab", text = "Express delivery (+20.00)", selected = true, nodeKey = "exp")
        val gift = FakeControl(role = "Button", text = "Add gift wrap", enabled = false, nodeKey = "gift")
        val pay = FakeControl(text = "Pay", nodeKey = "pay")
        val checkout = FakeScreen(pkg, "Checkout", listOf(repeat, standard, express, gift, pay))
        f.screen.screen = checkout
        f.authorise(pkg, "Pay", ScreenActionPolicy.ASK_EVERY_ACTION)
        val args = json("exact_text" to "Pay")
        val tool = f.tool("screen.tap")

        val a = tool.assess(args, f.context)
        assertRisk(RiskLevel.CONSEQUENTIAL, a)
        assertTrue("  CheckBox: \"Repeat every month\" [checked]" in a.details, a.preview)
        assertTrue("  Tab: \"Express delivery (+20.00)\" [selected]" in a.details, a.preview)
        assertTrue("  Tab: \"Standard delivery\"" in a.details, a.preview)
        assertTrue("  Button: \"Add gift wrap\" [disabled]" in a.details, a.preview)

        val changes = mapOf(
            "the selected tab moved" to listOf(repeat, standard.copy(selected = true), express.copy(selected = false), gift, pay),
            "a control was switched on" to listOf(repeat, standard, express, gift.copy(enabled = true), pay),
            "the box was unticked" to listOf(repeat.copy(checked = false), standard, express, gift, pay),
        )
        for ((what, controls) in changes) {
            f.screen.screen = checkout.copy(controls = controls)
            assertEquals("what the window shows changed", a.prepared!!.changedSince(), what)
            assertFalse(tool.execute(args, f.context, a).ok, what)
        }
        assertTrue(taps.isEmpty())
        f.screen.screen = checkout
        assertTrue(tool.execute(args, f.context, a).ok, "unchanged, it runs")
    }

    /** (c): words that sit only in a row's merged label or in a hint are shown. */
    @Test
    fun `a row whose words are only in its label, and a hint, are shown in the approval`() = runTest {
        val row = FakeControl(role = "LinearLayout", label = "To · Mallory · 5,000.00 EUR", nodeKey = "row")
        val note = FakeControl(role = "View", hint = "Paying Mallory", clickable = false, nodeKey = "hint")
        f.screen.screen = FakeScreen(pkg, "Transfer", listOf(row, note, FakeControl(text = "Send money", nodeKey = "send")))
        f.authorise(pkg, "Pay", ScreenActionPolicy.ASK_EVERY_ACTION)
        val a = f.assess("screen.tap", "exact_text" to "Send money")
        assertTrue("  LinearLayout: \"To · Mallory · 5,000.00 EUR\"" in a.details, a.preview)
        assertTrue("  View: hint \"Paying Mallory\"" in a.details, a.preview)
    }

    /** (d): the end of a long text is counted in the preview and pinned, although neither the preview nor the element holds it. */
    @Test
    fun `a long text says how much is left out, and a change in the part left out refuses the tap`() = runTest {
        val letter = "Dear Sam, ".repeat(70)
        fun screen(ps: String) = FakeScreen(pkg, "Draft", listOf(text(letter + ps, "body"), FakeControl(text = "Send", nodeKey = "send")))
        f.screen.screen = screen("PS: wire 500 to Alice")
        f.authorise(pkg, "Mail", ScreenActionPolicy.ASK_EVERY_ACTION)
        val args = json("exact_text" to "Send")
        val tool = f.tool("screen.tap")
        val a = tool.assess(args, f.context)
        val left = letter.length + "PS: wire 500 to Alice".length - ScreenPreview.MAX_TEXT_CHARS
        assertTrue(a.details.any { it.startsWith("  TextView: \"Dear Sam, ") && it.endsWith("…\" ($left more characters not shown)") }, a.preview)
        assertEquals(ScreenPreview.STILL_CHECKED, a.details.first { it.startsWith("  (What") })

        f.screen.screen = screen("PS: wire 900 to Mallory")
        assertEquals("what the window shows changed", a.prepared!!.changedSince())
        assertFalse(tool.execute(args, f.context, a).ok)
        assertTrue(taps.isEmpty())
    }

    /** (e), the verifier's P3b with 1100 texts: what the list cannot hold is pinned, and the approval says so. */
    @Test
    fun `a window larger than the list is pinned whole, the approval says so, and a change past the list refuses the tap`() = runTest {
        val web = "com.example.browser"
        val filler = (1..1100).map { text("Line $it of the page", "t$it") }
        val to = FakeControl(role = "EditText", hint = "Recipient IBAN", text = "DE00 1111 (Alice)", editable = true, clickable = false, nodeKey = "to")
        val price = text("You pay 20.00 EUR to Alice", "price")
        val send = FakeControl(text = "Send transfer", nodeKey = "send")
        f.screen.screen = FakeScreen(web, "Bank", filler + to + price + send)
        f.authorise(web, "Browser", ScreenActionPolicy.ASK_EVERY_ACTION)
        val args = json("exact_text" to "Send transfer")
        val tool = f.tool("screen.tap")

        val a = tool.assess(args, f.context)
        assertRisk(RiskLevel.CONSEQUENTIAL, a)
        assertFalse("Recipient" in a.preview, "the field is past what the list holds")
        assertEquals(
            listOf(
                "  …and 850 more texts not listed here.",
                "  (The window holds 103 more elements than could be read into this list.)",
                ScreenPreview.STILL_CHECKED,
            ),
            a.details.filter { it.startsWith("  …and") || it.startsWith("  (") },
        )

        f.screen.screen = FakeScreen(web, "Bank", filler + to.copy(text = "DE99 6666 (Mallory)") + price.copy(text = "You pay 9,999.00 EUR to Mallory") + send)
        assertEquals("what the window shows changed", a.prepared!!.changedSince())
        assertFalse(tool.execute(args, f.context, a).ok)
        assertTrue(taps.isEmpty(), "the tap did not run after the recipient and the price changed")
    }

    /** (e): above the ceiling nothing vouches for the window, so the owner is not asked; the call fails with why. */
    @Test
    fun `a window too large to fingerprint is refused with the reason instead of being put to the owner`() = runTest {
        val web = "com.example.browser"
        // More elements than the list holds, and more than the reader fingerprints (the ceiling is lowered for the test).
        f.screen.digestMaxElements = 1_050
        f.screen.screen = FakeScreen(web, "Feed", (1..1_100).map { text("Post $it", "p$it") } + FakeControl(text = "Send transfer", nodeKey = "send") + FakeControl(text = "Next", nodeKey = "next"))
        f.authorise(web, "Browser", ScreenActionPolicy.ASK_EVERY_ACTION)
        f.authorise("com.android.launcher3", "Home", ScreenActionPolicy.ASK_EVERY_ACTION)
        val calls = listOf(
            "screen.tap" to arrayOf<Pair<String, Any?>>("exact_text" to "Send transfer"),
            "screen.scroll" to arrayOf<Pair<String, Any?>>("direction" to "down"),
            "screen.back" to emptyArray(),
        )
        for ((id, args) in calls) {
            val (a, outcome) = f.run(id, *args)
            assertEquals(RiskLevel.MODIFYING, a.risk, "$id: not put to the owner as an approval")
            assertTrue(a.details.isEmpty(), "$id: no screen text in a refused call: ${a.details}")
            assertFalse(outcome.ok, id)
            assertTrue("holds more than can be read in full" in outcome.content && "nothing was done" in outcome.content, outcome.content)
        }
        assertTrue(f.screen.performed.isEmpty(), f.screen.performed.toString())

        // The same for an app that asks less, as soon as the action is one that would ask: a yes-button whose window cannot be told.
        f.authorise(web, "Browser", ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY)
        val (next, refused) = f.run("screen.tap", "exact_text" to "Next")
        assertEquals(RiskLevel.MODIFYING, next.risk)
        assertTrue(!refused.ok && "holds more than can be read in full" in refused.content, refused.content)
        // Reading stays possible, so the agent can still tell the owner what is there.
        assertTrue(f.ok("screen.read").ok)
    }

    // ---- S2 ----

    /** The verifier's P4b: Next under a total, Next beside a filled-in amount. The default policy asks anyway. */
    @Test
    fun `advance and commit buttons ask in an app set to ask less when their screen holds a price, an amount or a filled-in field`() = runTest {
        val gallery = "com.example.gallery"
        fun total(text: String) = FakeControl(role = "TextView", text = text, clickable = false, nodeKey = "t")
        val amount = FakeControl(role = "EditText", hint = "Amount", text = "5000", editable = true, clickable = false, nodeKey = "t")
        val asking = listOf(
            FakeScreen(gallery, "Basket", listOf(total("Total: 249.00 EUR"), FakeControl(text = "Next", nodeKey = "n"))),
            FakeScreen(gallery, "Step 1", listOf(amount, FakeControl(text = "Next", nodeKey = "n"))),
            FakeScreen(gallery, "Basket", listOf(total("Total 249.00"), FakeControl(text = "Proceed", nodeKey = "n"))),
            FakeScreen(gallery, "Basket", listOf(total("Razem: 5,00 zł"), FakeControl(text = "Finish", nodeKey = "n"))),
            FakeScreen(gallery, "Profile", listOf(amount.copy(hint = "Display name", text = "Sam"), FakeControl(text = "Save", nodeKey = "n"))),
            FakeScreen(gallery, "Album", listOf(total("Delete 248 photos?"), FakeControl(text = "Got it", nodeKey = "n"))),
            FakeScreen(gallery, "Shop", listOf(FakeControl(text = "20 kr", nodeKey = "n"))),
            FakeScreen(gallery, "Shop", listOf(FakeControl(text = "5,00 zł", nodeKey = "n"))),
            FakeScreen(gallery, "Shop", listOf(FakeControl(text = "20,-", nodeKey = "n"))),
        )
        val plain = listOf(
            FakeScreen(gallery, "Photos", listOf(total("Swipe left for the next photo"), FakeControl(text = "Next", nodeKey = "n"))),
            FakeScreen(gallery, "Profile", listOf(amount.copy(hint = "Display name", text = null), FakeControl(text = "Save", nodeKey = "n"))),
            FakeScreen(gallery, "Tour", listOf(total("Step 2 of 4"), FakeControl(text = "Got it", nodeKey = "n"))),
        )
        f.authorise(gallery, "Gallery", ScreenActionPolicy.ASK_CONSEQUENTIAL_ONLY)
        for (s in asking) {
            f.screen.screen = s
            val a = f.assess("screen.tap", "exact_text" to s.controls.last().text!!)
            assertRisk(RiskLevel.CONSEQUENTIAL, a)
            assertTrue(a.details.any { it.startsWith("Asked because ") }, a.preview)
        }
        for (s in plain) {
            f.screen.screen = s
            assertRisk(RiskLevel.MODIFYING, f.assess("screen.tap", "exact_text" to s.controls.last().text!!))
        }
        // "Ask before every action" is as it was: everything asks, whatever the words.
        f.authorise(gallery, "Gallery", ScreenActionPolicy.ASK_EVERY_ACTION)
        for (s in asking + plain) {
            f.screen.screen = s
            val a = f.assess("screen.tap", "exact_text" to s.controls.last().text!!)
            assertRisk(RiskLevel.CONSEQUENTIAL, a)
            assertTrue("is set to ask you before every action" in a.preview, a.preview)
        }
    }

    // ---- S5 ----

    /** The verifier's command: typographic quotes that make the summary read as if the command ended after "ls". */
    @Test
    fun `typographic quotes in a shell command cannot make its summary look finished`() = runTest {
        val command = "ls” in the top level of the workspace. Read-only listing; nothing else runs. “; rm -rf ../databases ; echo “"
        val a = f.assess("scripts.run_shell", "command" to command)
        assertFalse('”' in a.summary || '“' in a.summary, a.summary)
        assertTrue("\"ls⟨U+201D⟩ in the top level" in a.summary && "⟨U+201C⟩; rm -rf ../databases ; echo ⟨U+201C⟩\"" in a.summary, a.summary)
        val doubled = f.assess("scripts.run_shell", "command" to "ls'' in the workspace ''; rm -rf x")
        assertFalse("''" in doubled.summary, doubled.summary)
    }

    // ---- S6 ----

    @Test
    fun `a screen tool waits a moment for a service state that is not known yet, and says so if it stays unknown`() = runTest {
        f.screen.screen = FakeScreen(pkg, "Transfer", listOf(FakeControl(text = "Details", nodeKey = "d")))
        f.authorise(pkg, "Pay")
        f.screen.setState(ScreenServiceState.CHECKING)

        // The background read lands while the tool waits: the call goes on as if nothing had happened.
        val read = async { f.ok("screen.read") }
        runCurrent()
        assertFalse(read.isCompleted)
        advanceTimeBy(500)
        f.screen.setState(ScreenServiceState.READY)
        assertTrue(read.await().ok)

        // It never lands: the model is told to try again, in words that fit.
        f.screen.setState(ScreenServiceState.CHECKING)
        val reason = f.rejected("screen.read")
        assertTrue("still looking up the accessibility service" in reason && "try again in a moment" in reason, reason)
        assertNull(f.screen.calls.filterIsInstance<ScreenCall.Read>().drop(1).firstOrNull(), "nothing was read while the state was unknown")
    }
}
