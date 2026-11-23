package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.GlobalAction
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.framework.operator.OperatorFixture.Companion.json
import com.aicontrolcenter.testkit.FakeControl
import com.aicontrolcenter.testkit.FakeScreen
import com.aicontrolcenter.testkit.ScreenCall
import com.aicontrolcenter.testkit.contract.PreparedActionChecks
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenToolsTest {
    private val shopPackage = "com.example.shop"
    private val buy = FakeControl(text = "Buy now", resourceId = "$shopPackage:id/buy", nodeKey = "n1", context = "Row: Tea")
    private val backToList = FakeControl(text = "Back to list", nodeKey = "n2")
    private val coupon = FakeControl(role = "EditText", hint = "Coupon", text = "SAVE10", editable = true, clickable = false, nodeKey = "n3")
    private val message = FakeControl(role = "EditText", hint = "Message", editable = true, clickable = false, nodeKey = "n4")
    private val pin = FakeControl(role = "EditText", hint = "PIN", editable = true, password = true, nodeKey = "n5")
    private val icon = FakeControl(role = "ImageButton", nodeKey = "n6")
    private val list = FakeControl(role = "RecyclerView", scrollable = true, clickable = false, nodeKey = "n7")
    private val shop = FakeScreen(shopPackage, "Cart", listOf(buy, backToList, coupon, message, pin, icon, list))
    private val f = OperatorFixture(initialScreen = shop)

    private suspend fun ready() = f.authorise(shopPackage, "Shop")

    private val taps get() = f.screen.performed.filterIsInstance<ScreenCall.Tap>()

    @Test
    fun `read lists the controls of an authorised app without password fields`() = runTest {
        ready()
        val (assessment, outcome) = f.run("screen.read")
        assertEquals(CapabilityConstraint.App(shopPackage), assessment.constraint)
        assertRisk(RiskLevel.SAFE, assessment)
        assertTrue(outcome.ok, outcome.content)
        val text = outcome.content
        assertTrue("Screen of \"Shop\" ($shopPackage), window \"Cart\", snapshot 1" in text, text)
        assertTrue("1. Button \"Buy now\" id buy in \"Row: Tea\" [clickable]" in text, text)
        assertTrue("3. EditText \"SAVE10\" hint \"Coupon\" [editable]" in text, text)
        assertTrue("PIN" !in text && "1 password or sensitive field not shown" in text, text)
        assertEquals(1L, outcome.data!!.jsonObject.getValue("snapshot").jsonPrimitive.long)
    }

    @Test
    fun `an app the owner did not authorise is refused before anything of it is read`() = runTest {
        f.screen.screen = FakeScreen("com.example.bank", "Accounts", listOf(FakeControl(text = "Balance 5,000"), FakeControl(text = "Transfer")))
        for (id in listOf("screen.read", "screen.wait_for", "screen.screenshot", "screen.back", "screen.scroll")) {
            val args = when (id) {
                "screen.wait_for" -> arrayOf<Pair<String, Any?>>("text" to "Balance")
                "screen.scroll" -> arrayOf<Pair<String, Any?>>("direction" to "down")
                else -> emptyArray()
            }
            val (assessment, outcome) = f.run(id, *args)
            assertEquals(CapabilityConstraint.App("com.example.bank"), assessment.constraint, id)
            assertFalse("Balance" in assessment.preview || "Accounts" in assessment.preview, assessment.preview)
            assertFalse(outcome.ok)
            assertTrue("not an app the owner authorised" in outcome.content, outcome.content)
        }
        val (tap, tapOutcome) = f.run("screen.tap", "exact_text" to "Transfer")
        assertEquals("Tap on the screen of com.example.bank", tap.summary)
        assertFalse(tapOutcome.ok)
        assertTrue(f.screen.calls.isEmpty(), "nothing of the screen was read: ${f.screen.calls}")
    }

    @Test
    fun `protected apps are refused whatever was authorised`() = runTest {
        for (pkg in listOf("com.android.settings", OperatorFixture.OWN, "com.android.packageinstaller")) {
            f.authorise(pkg)
            f.screen.screen = FakeScreen(pkg, "Approve?", listOf(FakeControl(text = "Approve")))
            val (assessment, outcome) = f.run("screen.tap", "exact_text" to "Approve")
            assertEquals(CapabilityConstraint.App(pkg), assessment.constraint)
            assertTrue("protected" in outcome.content, outcome.content)
            assertFalse("Approve" in assessment.summary)
        }
        assertTrue(taps.isEmpty())
    }

    @Test
    fun `tapping a buy control is consequential and the preview shows what the fields hold`() = runTest {
        ready()
        val assessment = f.assess("screen.tap", "exact_text" to "Buy now")
        assertRisk(RiskLevel.CONSEQUENTIAL, assessment)
        assertEquals("Tap Button \"Buy now\" in \"Shop\" ($shopPackage)", assessment.summary)
        assertTrue("App: Shop ($shopPackage), window \"Cart\"" in assessment.preview, assessment.preview)
        assertTrue("Where: Row: Tea" in assessment.preview, assessment.preview)
        assertTrue("Field \"Coupon\" contains: \"SAVE10\"" in assessment.preview, assessment.preview)
        assertTrue("Asked because the control is labelled with \"buy\"" in assessment.preview, assessment.preview)
        assertEquals(CapabilityConstraint.App(shopPackage), assessment.constraint)
    }

    @Test
    fun `ordinary taps are modifying and unlabelled controls are consequential`() = runTest {
        ready()
        assertRisk(RiskLevel.MODIFYING, f.assess("screen.tap", "exact_text" to "Back to list"))
        val read = f.ok("screen.read")
        val snapshot = read.data!!.jsonObject.getValue("snapshot").jsonPrimitive.long
        val unlabelled = f.assess("screen.tap", "snapshot" to snapshot, "element" to 5)
        assertRisk(RiskLevel.CONSEQUENTIAL, unlabelled)
        assertTrue("unlabelled ImageButton" in unlabelled.summary, unlabelled.summary)
        assertTrue("no label" in unlabelled.preview)
        // Matching is per word and covers forms of the stems.
        for (label in listOf("Send", "Pay with card", "Subscribe", "btn_checkout", "Delete account", "Confirm order", "Install")) {
            f.screen.screen = FakeScreen(shopPackage, "Cart", listOf(FakeControl(text = label, nodeKey = label)))
            assertRisk(RiskLevel.CONSEQUENTIAL, f.assess("screen.tap", "exact_text" to label))
        }
        f.screen.screen = FakeScreen(shopPackage, "Cart", listOf(FakeControl(description = "Share photo", nodeKey = "d")))
        assertRisk(RiskLevel.CONSEQUENTIAL, f.assess("screen.tap", "exact_text" to "Share photo"))
        f.screen.screen = FakeScreen(shopPackage, "Cart", listOf(FakeControl(role = "ImageButton", resourceId = "$shopPackage:id/send_button", label = null, description = "Go", nodeKey = "r")))
        assertRisk(RiskLevel.CONSEQUENTIAL, f.assess("screen.tap", "exact_text" to "Go"))
    }

    @Test
    fun `a tap runs on the approved control and is refused when it changed`() = runTest {
        ready()
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("screen.tap"), json("exact_text" to "Back to list"), f.context)
        assertEquals("Back to list", taps.single().element.text)

        // Another control with the same text now stands where the approved one was.
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("screen.tap"), json("exact_text" to "Buy now"), f.context,
            change = { f.screen.screen = shop.copy(controls = listOf(buy.copy(nodeKey = "other"), coupon)) },
            nothingDone = { taps.size == 1 },
        ).also { assertTrue("no longer on screen" in it.content, it.content) }

        // The fields shown to the owner changed after approval.
        f.screen.screen = shop
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("screen.tap"), json("exact_text" to "Buy now"), f.context,
            change = { f.screen.screen = shop.copy(controls = shop.controls.map { if (it == coupon) coupon.copy(text = "SAVE99") else it }) },
            nothingDone = { taps.size == 1 },
        ).also { assertTrue("fields on screen changed" in it.content, it.content) }

        // Another app came to the front.
        f.screen.screen = shop
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("screen.tap"), json("exact_text" to "Back to list"), f.context,
            change = { f.screen.screen = FakeScreen("com.example.mail", "Inbox", listOf(backToList)) },
            nothingDone = { taps.size == 1 },
        ).also { assertTrue("app in front changed" in it.content, it.content) }
    }

    @Test
    fun `typing replaces text, and with submit it is consequential`() = runTest {
        ready()
        val plain = f.assess("screen.type", "exact_text" to "SAVE10", "text" to "SAVE20")
        assertRisk(RiskLevel.MODIFYING, plain)
        val outcome = f.tool("screen.type").execute(json("exact_text" to "SAVE10", "text" to "SAVE20"), f.context, plain)
        assertTrue(outcome.ok, outcome.content)
        assertEquals("SAVE20", f.screen.typed.single().second)

        val read = f.ok("screen.read").data!!.jsonObject.getValue("snapshot").jsonPrimitive.long
        val submit = f.assess("screen.type", "snapshot" to read, "element" to 4, "text" to "See you at 8\nbye", "submit" to true)
        assertRisk(RiskLevel.CONSEQUENTIAL, submit)
        assertTrue("Text to type: \"See you at 8⏎\nbye\"" in submit.preview, submit.preview)
        assertTrue("keyboard's action key" in submit.summary, submit.summary)
        assertTrue("does not take text" in f.fails("screen.type", "exact_text" to "Back to list", "text" to "x").content)
        assertTrue("not found" in f.fails("screen.type", "exact_text" to "PIN", "text" to "1234").content)
        assertEquals(1, f.screen.typed.size)
    }

    @Test
    fun `home leads to the launcher, back and scroll stay in the app`() = runTest {
        ready()
        val home = f.assess("screen.home")
        assertEquals("com.android.launcher3", home.leadsTo)
        assertEquals(CapabilityConstraint.App(shopPackage), home.constraint)
        f.screen.launcher = null
        assertTrue("home screen app is not known" in f.rejected("screen.home"))
        f.screen.launcher = "com.android.launcher3"
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("screen.home"), json(), f.context,
            change = { f.screen.launcher = "com.other.launcher" }, nothingDone = { f.screen.performed.none { it is ScreenCall.Global } },
        )
        f.screen.launcher = "com.android.launcher3"
        PreparedActionChecks.assertRunsWhenUnchanged(f.tool("screen.back"), json(), f.context)
        assertEquals(GlobalAction.BACK, (f.screen.performed.last() as ScreenCall.Global).action)
        assertTrue(f.assess("screen.back").leadsTo == null)
        val scroll = f.ok("screen.scroll", "direction" to "down")
        assertTrue("Scrolled down" in scroll.content)
        assertEquals(ScrollDirection.DOWN, (f.screen.performed.last() as ScreenCall.Scroll).direction)
        assertTrue("up, down" in f.rejected("screen.scroll", "direction" to "sideways"))
    }

    @Test
    fun `wait for and screenshots act in the app that was checked`() = runTest {
        ready()
        f.screen.onAction = { }
        val found = f.ok("screen.wait_for", "text" to "Buy", "timeout_ms" to 200)
        assertTrue("Buy now" in found.content, found.content)
        val (shot, outcome) = f.run("screen.screenshot", "to" to "shots/cart.png")
        assertRisk(RiskLevel.MODIFYING, shot)
        assertTrue(outcome.ok, outcome.content)
        assertTrue(f.file("shots/cart.png").length() > 0)
        assertTrue("never replace" in f.fails("screen.screenshot", "to" to "shots/cart.png").content)
        PreparedActionChecks.assertRefusesAfterChange(
            f.tool("screen.screenshot"), json("to" to "shots/two.png"), f.context,
            change = { f.screen.screen = FakeScreen("com.example.mail", "Inbox") }, nothingDone = { !f.file("shots/two.png").exists() },
        )
        assertTrue(".png" in f.rejected("screen.screenshot", "to" to "shot.jpg"))
    }

    @Test
    fun `Stop and an unavailable service are refused before anything else`() = runTest {
        ready()
        f.screen.stop()
        assertTrue("Stop" in f.rejected("screen.read"))
        f.screen.resume()
        f.screen.setState(ScreenServiceState.NOT_ENABLED)
        assertTrue("not switched on" in f.rejected("screen.tap", "exact_text" to "Buy now"))
        f.screen.setState(ScreenServiceState.READY)
        assertTrue("either" in f.rejected("screen.tap", "exact_text" to "Buy now", "element" to 1))
        assertTrue("go together" in f.rejected("screen.tap", "element" to 1))
    }

    @Test
    fun `Stop pressed after approval stops the action at the gateway`() = runTest {
        ready()
        val assessment = f.assess("screen.tap", "exact_text" to "Back to list")
        f.screen.stop()
        val outcome = f.tool("screen.tap").execute(json("exact_text" to "Back to list"), f.context, assessment)
        assertFalse(outcome.ok)
        assertTrue(taps.isEmpty())
    }
}
