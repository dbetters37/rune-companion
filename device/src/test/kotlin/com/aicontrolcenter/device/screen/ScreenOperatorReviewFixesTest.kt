package com.aicontrolcenter.device.screen

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.MAIL
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.NOTES
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lets a test act out what `refresh()` does on a phone: it re-fetches the node from the app, so a
 * list that re-bound the view to another item reports the other item's text.
 */
@Implements(AccessibilityNodeInfo::class)
class RebindingNodeShadow : ShadowAccessibilityNodeInfo() {
    @field:RealObject
    private lateinit var node: AccessibilityNodeInfo

    @Implementation
    public override fun refresh(): Boolean {
        rebind?.invoke(node)
        return super.refresh()
    }

    /** The test's re-binding, run on every refresh while set. */
    companion object {
        @JvmStatic
        var rebind: ((AccessibilityNodeInfo) -> Unit)? = null
    }
}

/** Review 5: A4 (the keyboard action of a field that is not focused), A5 (a re-bound view), A9 (the banner over the lock screen) and the side-effect-free read assessments use. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [RebindingNodeShadow::class])
class ScreenOperatorReviewFixesTest {
    @After
    fun noRebind() {
        RebindingNodeShadow.rebind = null
    }

    private suspend fun OperatorHarness.readOk(agent: String = "Scout"): ScreenSnapshot =
        assertIs<ScreenResult.Ok<ScreenSnapshot>>(operator.read(agent, NOTES, 200)).value

    /** A4: Android's text fields offer the keyboard action only while focused; the operator focuses the field first. */
    @Test
    fun `typing with submit into a field that is not focused focuses it, then presses the keyboard action`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, "Chat") { field("message") { hint = "Message"; imeWhenFocused = true } }
        h.show(window)
        val message = h.readOk().elements.single { it.editable }

        assertEquals(ScreenResult.Ok(Unit), h.operator.type("Scout", message, "See you at 6", submit = true))
        assertEquals(
            listOf(
                Performed("message", AccessibilityNodeInfo.ACTION_FOCUS),
                Performed("message", AccessibilityNodeInfo.ACTION_SET_TEXT, "See you at 6"),
                Performed("message", AccessibilityAction.ACTION_IME_ENTER.id),
            ),
            window.log,
        )
    }

    /** A4: a field that cannot be focused by the operator is refused with what to do instead, and nothing is typed. */
    @Test
    fun `a field without the keyboard action is refused with what to do, and nothing is typed`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, "Chat") { field("message") { hint = "Message" } }
        h.show(window)
        val message = h.readOk().elements.single { it.editable }
        val result = assertIs<ScreenResult.NotSupported>(h.operator.type("Scout", message, "See you at 6", submit = true))
        assertTrue("Tap it first" in result.reason, result.reason)
        assertTrue(window.log.isEmpty())
    }

    /** A5: between the read and the click, the list re-bound the row's button to another item; nothing is clicked. */
    @Test
    fun `a control re-bound to another item just before the action is stale and nothing is done`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, "Invoices") {
            list {
                row("row1") { button("Delete invoice 1", name = "delete1") }
                row("row2") { button("Delete invoice 2", name = "delete2") }
            }
        }
        h.show(window)
        val first = h.readOk().elements.single { it.text == "Delete invoice 1" }
        RebindingNodeShadow.rebind = { node -> if (node.text?.toString() == "Delete invoice 1") node.text = "Delete invoice 2" }

        assertEquals(ScreenResult.Stale, h.operator.tap("Scout", first))
        assertTrue(window.log.isEmpty(), "nothing was clicked: ${window.log}")

        RebindingNodeShadow.rebind = null
        assertEquals(ScreenResult.Ok(Unit), h.operator.tap("Scout", first))
        assertEquals(listOf(Performed("delete1", AccessibilityNodeInfo.ACTION_CLICK)), window.log)
    }

    /** A9: the overlay is drawn above the lock screen, so it hides while the phone is locked and comes back after. */
    @Test
    fun `the banner hides over the lock screen and comes back when the phone is unlocked`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(TestWindow(NOTES, "Notes") { label("Groceries") })
        val banner = h.host.indicator
        h.readOk()
        runCurrent()
        assertEquals("Scout is operating Notes", banner.text)

        h.facts.locked = true
        h.link.changed()
        runCurrent()
        assertNull(banner.text, "the agent and the app are not named over the lock screen")

        h.facts.locked = false
        h.link.changed()
        runCurrent()
        assertEquals("Scout is operating Notes", banner.text)
    }

    /** The read an assessment shows the owner starts no session, keeps no snapshot and passes the same checks. */
    @Test
    fun `inspect reads the whole window without a session, under the same checks`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        h.show(TestWindow(NOTES, "Notes") { (1..250).forEach { label("Line $it") } })
        val inspected = assertIs<ScreenResult.Ok<ScreenSnapshot>>(h.operator.inspect(NOTES, 1_000)).value
        assertEquals(0L, inspected.id)
        assertEquals(250, inspected.elements.size)
        assertEquals("Line 250", inspected.elements.last().text)
        runCurrent()
        assertNull(h.host.indicator.text, "no session, so no banner")
        assertTrue(h.host.indicator.history.isEmpty())
        assertIs<ScreenResult.NotAuthorised>(h.operator.inspect(MAIL, 1_000))
    }
}
