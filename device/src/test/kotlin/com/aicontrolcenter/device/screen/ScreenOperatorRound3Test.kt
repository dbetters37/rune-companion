package com.aicontrolcenter.device.screen

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.NOTES
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 3 for the operator over the accessibility service, against a node tree that behaves like
 * a phone's ([PhoneNodeShadow]: `getChild` serves what was first fetched, only `refresh()` asks
 * the app).
 *
 * A1 (rest of S4): every step of one type call asks the app for the field's row again, so a row
 * re-bound to another chat after the call's own focus, or after its typing, stops the call.
 *
 * A2: a scroll no longer needs the scrolling area's label (the content it moves) to be as it was
 * read, and a re-read neither asks the app for views that are off screen nor counts them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [PhoneNodeShadow::class])
class ScreenOperatorRound3Test {
    @After
    fun forget() = PhoneNodeShadow.reset()

    private suspend fun OperatorHarness.readOk(agent: String = "Scout"): ScreenSnapshot =
        assertIs<ScreenResult.Ok<ScreenSnapshot>>(operator.read(agent, NOTES, 200)).value

    private fun TestWindow.performed(): List<String> = log.map { it.node + ":" + actionName(it.action) + (it.text?.let { t -> "=$t" } ?: "") }

    private fun actionName(action: Int): String = when (action) {
        AccessibilityNodeInfo.ACTION_FOCUS -> "FOCUS"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "SET_TEXT"
        AccessibilityNodeInfo.ACTION_CLICK -> "CLICK"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "SCROLL_FORWARD"
        AccessibilityAction.ACTION_IME_ENTER.id -> "IME_ENTER"
        else -> action.toString()
    }

    // ---- A1: the later steps of one type call ----

    /** A list of chats: each row names who it goes to and holds a reply field whose own text is the same in every row. */
    private fun chats(focused: Boolean) = TestWindow(NOTES, "Chats") {
        list {
            row("row1") {
                label("To: Alice", name = "to1")
                field("reply1") {
                    hint = "Reply"
                    if (focused) actions += AccessibilityAction.ACTION_IME_ENTER else imeWhenFocused = true
                }
            }
            row("row2") {
                label("To: Bob", name = "to2")
                field("reply2") { hint = "Reply"; imeWhenFocused = true }
            }
        }
    }

    /** The verifier's first case: the list re-binds the row the moment the field was focused. */
    @Test
    fun `a row re-bound right after this call focused the field stops the call, and nothing is typed`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = chats(focused = false)
        h.show(window)
        val field = h.readOk("Operator").elements.first { it.editable }
        assertEquals("Row: To: Alice · Chats", field.context)
        var rebound = false
        // The first time the app is asked again after the focus, its list has re-bound the row to another chat.
        PhoneNodeShadow.onRefresh = {
            if (!rebound && window.log.any { it.action == AccessibilityNodeInfo.ACTION_FOCUS }) {
                rebound = true
                window.node("to1").built.text = "To: Bob's lawyer"
            }
        }

        assertEquals(ScreenResult.Stale, h.operator.type("Operator", field, "I accept the offer", submit = true))
        assertTrue(rebound)
        assertEquals(listOf("reply1:FOCUS"), window.performed(), "focused, and then neither typed nor sent")
    }

    /** The verifier's second case: the row is re-bound between the typing and the keyboard action. */
    @Test
    fun `a row re-bound between the typing and the keyboard action stops the call, and the text is not sent`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = chats(focused = true)
        h.show(window)
        val field = h.readOk("Operator").elements.first { it.editable }
        var rebound = false
        PhoneNodeShadow.onRefresh = {
            if (!rebound && window.log.any { it.action == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
                rebound = true
                window.node("to1").built.text = "To: Bob's lawyer"
            }
        }

        assertEquals(ScreenResult.Stale, h.operator.type("Operator", field, "I accept the offer", submit = true))
        assertTrue(rebound)
        assertEquals(listOf("reply1:SET_TEXT=I accept the offer"), window.performed(), "typed, and not sent")
    }

    @Test
    fun `a row that stays the same is typed into and sent, whatever the field says about itself after the focus and the typing`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = chats(focused = false)
        h.show(window)
        val field = h.readOk("Operator").elements.first { it.editable }
        // As an app's field does: once focused it describes its state, and once typed into it holds the text.
        PhoneNodeShadow.onRefresh = {
            val reply = window.node("reply1").built
            if (window.log.any { it.action == AccessibilityNodeInfo.ACTION_FOCUS }) reply.stateDescription = "Editing"
            if (window.log.any { it.action == AccessibilityNodeInfo.ACTION_SET_TEXT }) reply.text = "I accept the offer"
        }

        assertEquals(ScreenResult.Ok(Unit), h.operator.type("Operator", field, "I accept the offer", submit = true))
        assertEquals(listOf("reply1:FOCUS", "reply1:SET_TEXT=I accept the offer", "reply1:IME_ENTER"), window.performed())
    }

    @Test
    fun `the first step of typing still needs the whole control, label included, to be as it was read`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = chats(focused = true)
        h.show(window)
        val field = h.readOk("Operator").elements.first { it.editable }
        // Before anything was done, a field that describes itself differently is not the field that was read.
        window.node("reply1").built.stateDescription = "Read only"

        assertEquals(ScreenResult.Stale, h.operator.type("Operator", field, "I accept the offer", submit = false))
        assertTrue(window.log.isEmpty(), window.performed().toString())
    }

    // ---- A2: scrolling ----

    /** The verifier's case: two scrolls with no element named, the second before Android's node cache followed the first. */
    @Test
    fun `scrolling twice in a row works while the node cache still shows the earlier content`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, "Article") { list("page") { repeat(8) { label("Line ${it + 1}", name = "line$it") } } }
        h.show(window)

        assertEquals(ScreenResult.Ok(Unit), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, null))
        // The app scrolled: its rows now show the next lines. The events have not reached the cache yet.
        repeat(8) { window.node("line$it").built.text = "Line ${it + 9}" }
        assertEquals(ScreenResult.Ok(Unit), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, null))
        PhoneNodeShadow.eventsArrived()
        assertEquals(ScreenResult.Ok(Unit), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, null))
        assertEquals(listOf("page:SCROLL_FORWARD", "page:SCROLL_FORWARD", "page:SCROLL_FORWARD"), window.performed())
    }

    /** The agent names an element that did not change; what moved is the rest of the list it sits in. */
    @Test
    fun `scrolling the list around a named element works after the list's other content moved`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, "Article") {
            list("page") {
                label("Pinned: read me first", name = "pinned")
                repeat(8) { label("Line ${it + 1}", name = "line$it") }
            }
        }
        h.show(window)
        val pinned = h.readOk().elements.first { it.text == "Pinned: read me first" }

        // A tap elsewhere loaded other lines; the cache has not followed, and the pinned line is as it was read.
        repeat(8) { window.node("line$it").built.text = "Line ${it + 9}" }
        assertEquals(ScreenResult.Ok(Unit), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, pinned))
        assertEquals(listOf("page:SCROLL_FORWARD"), window.performed())
    }

    private fun longPage(offScreen: Int) = TestWindow(NOTES, "Article") {
        child("android.widget.ScrollView", "page") {
            scrollable = true
            actions += AccessibilityAction.ACTION_SCROLL_FORWARD
            actions += AccessibilityAction.ACTION_SCROLL_BACKWARD
            child("android.widget.LinearLayout", "column") {
                repeat(offScreen) { label("Earlier paragraph $it", name = "gone$it") { visible = false } }
                repeat(6) { label("Paragraph ${it + 1} on screen", name = "seen$it") }
            }
        }
    }

    /** The verifier's case: 1,600 views scrolled off above what is visible, on a page that is not recycled. */
    @Test
    fun `a long page stays scrollable, and what is off screen is not asked from the app again`() = runTest {
        for (offScreen in listOf(0, 1400, 1600, 5000)) {
            val h = OperatorHarness(this)
            h.authorise()
            val window = longPage(offScreen)
            h.show(window)
            assertEquals(7, h.readOk().elements.size, "the page and its six visible paragraphs")
            var asked = 0
            PhoneNodeShadow.onRefresh = { asked++ }

            assertEquals(ScreenResult.Ok(Unit), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, null), "$offScreen views off screen")
            assertTrue(asked <= 4, "$offScreen views off screen: $asked nodes were asked from the app again for one scroll")
            assertEquals(listOf("page:SCROLL_FORWARD"), window.performed())
            PhoneNodeShadow.reset()
        }
    }

    /** The same for a tap: the label of a card is read from what is visible in it. */
    @Test
    fun `a tap on a control with many views off screen inside it works, asking only for what is visible`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, "Feed") {
            child("android.widget.FrameLayout", "card") {
                clickable = true
                actions += AccessibilityAction.ACTION_CLICK
                repeat(1600) { label("Earlier line $it", name = "gone$it") { visible = false } }
                label("Open the article", name = "seen")
            }
        }
        h.show(window)
        val card = h.readOk().elements.first { it.clickable }
        assertEquals("Open the article", card.label)
        var asked = 0
        PhoneNodeShadow.onRefresh = { asked++ }

        assertEquals(ScreenResult.Ok(Unit), h.operator.tap("Scout", card))
        assertTrue(asked <= 6, "$asked nodes were asked from the app again for one tap")
        assertEquals(listOf("card:CLICK"), window.performed())

        // What is visible in it still decides: the card now shows another article.
        PhoneNodeShadow.onRefresh = null
        window.node("seen").built.text = "Open the invoice"
        assertEquals(ScreenResult.Stale, h.operator.tap("Scout", card))
        assertEquals(listOf("card:CLICK"), window.performed(), "only the first tap ran")
    }

    @Test
    fun `a scroll is still refused when the scrolling area is not where it was read`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        // A list inside a row of an outer list: its context is that row.
        fun shelves() = TestWindow(NOTES, "Library") {
            child("android.widget.LinearLayout", "shelf") {
                collectionItem = true
                label("Shelf: Invoices", name = "shelfTitle")
                list("books") { repeat(3) { label("Book ${it + 1}", name = "book$it") } }
            }
        }
        val window = shelves()
        h.show(window)
        val books = h.readOk().elements.single { it.scrollable }
        assertEquals("Row: Shelf: Invoices · Library", books.context)

        // The outer row was re-bound to another shelf: the list in it is not the one that was read.
        window.node("shelfTitle").built.text = "Shelf: Contracts"
        assertEquals(ScreenResult.Stale, h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, books))
        assertTrue(window.log.isEmpty(), window.performed().toString())

        // The app shows another page in the same window between the scroll's read and its action.
        val other = shelves()
        h.show(other)
        PhoneNodeShadow.eventsArrived()
        val there = h.readOk().elements.single { it.scrollable }
        var turned = false
        PhoneNodeShadow.onRefresh = {
            if (!turned) {
                turned = true
                h.show(TestWindow(NOTES, "Trash") { label("Deleted books") })
            }
        }
        assertEquals(ScreenResult.Stale, h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, there))
        assertTrue(turned && other.log.isEmpty(), other.performed().toString())
    }
}
