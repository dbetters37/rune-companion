package com.aicontrolcenter.device.screen

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenPreview
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenServiceState
import com.aicontrolcenter.core.operator.ScreenSnapshot
import com.aicontrolcenter.core.operator.ScrollDirection
import com.aicontrolcenter.device.screen.OperatorHarness.Companion.NOTES
import com.aicontrolcenter.testkit.contract.InspectedScreen
import com.aicontrolcenter.testkit.contract.ScreenInspectContract
import com.aicontrolcenter.testkit.contract.ShownControl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import java.util.IdentityHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Makes test nodes behave like a phone's. There, a node reached through `getChild` or `getParent`
 * comes from Android's node cache and shows the app's view as it was when it was last fetched
 * (the cache follows the app only as accessibility events arrive, up to a second later); only
 * `refresh()` asks the app again. Robolectric's own shadow copies the app's node afresh on every
 * `getChild`, so with it no test ever sees a stale tree.
 *
 * [eventsArrived] empties the cache, as the app's events do in the end. [onRefresh] runs on every
 * `refresh()`, before the app is asked: a test changes the app's views there to act out a change
 * between a read and an action.
 */
@Implements(AccessibilityNodeInfo::class)
class PhoneNodeShadow : ShadowAccessibilityNodeInfo() {
    @field:RealObject
    private lateinit var node: AccessibilityNodeInfo

    /** The app's own node this copy stands for; null for a node not fetched through another (a window's root). */
    private var source: AccessibilityNodeInfo? = null

    @Implementation
    public override fun getChild(index: Int): AccessibilityNodeInfo? {
        @Suppress("UNCHECKED_CAST")
        val children = CHILDREN.get(this) as List<AccessibilityNodeInfo>?
        return children?.getOrNull(index)?.let(::fetched)
    }

    @Implementation
    public override fun getParent(): AccessibilityNodeInfo? = (PARENT.get(this) as AccessibilityNodeInfo?)?.let(::fetched)

    @Implementation
    public override fun refresh(): Boolean {
        onRefresh?.invoke(node)
        if (!super.refresh()) return false
        val live = source ?: return true
        // The app answers with the view as it is now. Android does not put that answer into its cache (a refresh bypasses it).
        @Suppress("DEPRECATION")
        val now = AccessibilityNodeInfo.obtain(live)
        INIT.invoke(node, now)
        node.text = now.text
        CHILDREN.set(this, (CHILDREN.get(Shadow.extract<PhoneNodeShadow>(now)) as List<*>?)?.let { ArrayList(it) })
        return true
    }

    /** The test's shared cache and hooks. */
    companion object {
        private val cache = IdentityHashMap<AccessibilityNodeInfo, AccessibilityNodeInfo>()

        /** Runs on every refresh while set, with the node being refreshed (still as it was). */
        @JvmStatic
        var onRefresh: ((AccessibilityNodeInfo) -> Unit)? = null

        /** The app's accessibility events arrived: the cache holds nothing stale any more. */
        @JvmStatic
        fun eventsArrived() = cache.clear()

        @JvmStatic
        fun reset() {
            cache.clear()
            onRefresh = null
        }

        private val CHILDREN = ShadowAccessibilityNodeInfo::class.java.getDeclaredField("children").apply { isAccessible = true }
        private val PARENT = ShadowAccessibilityNodeInfo::class.java.getDeclaredField("parent").apply { isAccessible = true }
        private val INIT = AccessibilityNodeInfo::class.java.getDeclaredMethod("init", AccessibilityNodeInfo::class.java).apply { isAccessible = true }

        /** A copy of what the cache holds for the app's node [live], fetching it from the app the first time only. */
        @Suppress("DEPRECATION")
        private fun fetched(live: AccessibilityNodeInfo): AccessibilityNodeInfo {
            val cached = cache.getOrPut(live) { AccessibilityNodeInfo.obtain(live) }
            return AccessibilityNodeInfo.obtain(cached).also { Shadow.extract<PhoneNodeShadow>(it).source = live }
        }
    }
}

/** `:device`'s operator over the accessibility service passes the same contract as the testkit's fake screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AccessibilityScreenInspectTest : ScreenInspectContract() {
    override suspend fun TestScope.screen(digestMaxElements: Int): InspectedScreen {
        val h = OperatorHarness(this, reader = ScreenReader(digestMaxElements = digestMaxElements))
        h.authorise()
        return object : InspectedScreen {
            override val operator: ScreenOperator = h.operator

            override fun show(title: String, controls: List<ShownControl>) = h.show(
                TestWindow(PACKAGE, title) {
                    controls.forEachIndexed { i, c ->
                        child("android.widget.${c.role}", "control $i") {
                            text = c.text
                            description = c.description
                            hint = c.hint
                            clickable = c.clickable
                            editable = c.editable
                            checkable = c.checkable
                            checked = c.checked
                            selected = c.selected
                            enabled = c.enabled
                            password = c.password
                        }
                    }
                },
            )
        }
    }
}

/**
 * Round 2 of review 5 for the operator over the accessibility service: S1 (a window larger than
 * the node limit is fingerprinted to its end), S3 (the keyboard action of a field that was just
 * focused, with Android's node cache still stale), S4 (a row re-bound to another item whose
 * button text is the same) and S6 (nobody reads Android's settings on the thread that looks at
 * the state).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], shadows = [PhoneNodeShadow::class])
class ScreenOperatorRound2Test {
    @After
    fun forget() = PhoneNodeShadow.reset()

    private suspend fun OperatorHarness.readOk(agent: String = "Scout"): ScreenSnapshot =
        assertIs<ScreenResult.Ok<ScreenSnapshot>>(operator.read(agent, NOTES, 200)).value

    private suspend fun OperatorHarness.inspectOk(maxElements: Int = ScreenOperator.INSPECT_MAX_ELEMENTS): ScreenSnapshot =
        assertIs<ScreenResult.Ok<ScreenSnapshot>>(operator.inspect(NOTES, maxElements)).value

    // ---- S1: more nodes than a read takes ----

    @Test
    fun `past the node limit the walk goes on only to fingerprint the rest of the window`() = runTest {
        val h = OperatorHarness(this, reader = ScreenReader(maxNodes = 40, digestMaxElements = 500))
        h.authorise()
        fun page(change: (Int) -> String = { "Line $it" }) = TestWindow(NOTES, "Statement") { (1..100).forEach { label(change(it), name = "line $it") } }
        h.show(page())
        val first = h.inspectOk()

        // The root and 39 lines are read into the tree; the other 61 lines are only fingerprinted.
        assertEquals(39, first.elements.size)
        assertTrue(first.truncated)
        assertEquals(61, first.unlisted)
        val whole = assertNotNull(first.windowDigest)
        assertTrue(ScreenPreview.STILL_CHECKED in ScreenPreview.windowLines(first), ScreenPreview.windowLines(first).takeLast(3).toString())
        assertTrue("  (The window holds 61 more elements than could be read into this list.)" in ScreenPreview.windowLines(first))

        h.show(page { if (it == 90) "Line 90: you pay 9,999.00 EUR to Mallory" else "Line $it" })
        val changed = h.inspectOk()
        assertEquals(first.elements, changed.elements)
        assertNotEquals(whole, changed.windowDigest, "a text past the node limit is pinned")

        // A read for the model stops at the limit and has no fingerprint to offer.
        val read = h.readOk()
        assertTrue(read.truncated)
        assertNull(read.windowDigest)
    }

    @Test
    fun `a window with more elements than the ceiling, or nested too deep, has no fingerprint`() = runTest {
        val h = OperatorHarness(this, reader = ScreenReader(maxNodes = 40, digestMaxElements = 80))
        h.authorise()
        h.show(TestWindow(NOTES, "Feed") { (1..100).forEach { label("Line $it") } })
        val over = h.inspectOk()
        assertNull(over.windowDigest)
        assertTrue(over.truncated)
        assertEquals(0, over.unlisted)

        val deep = OperatorHarness(this, reader = ScreenReader(maxDepth = 3))
        deep.authorise()
        deep.show(TestWindow(NOTES, "Nested") { child { child { child { child { label("At the bottom") } } } } })
        val nested = assertIs<ScreenResult.Ok<ScreenSnapshot>>(deep.operator.inspect(NOTES)).value
        assertTrue(nested.truncated)
        assertNull(nested.windowDigest, "what lies deeper than the walk goes is not vouched for")
    }

    @Test
    fun `layout past the node limit that shows nothing does not make the list incomplete`() = runTest {
        val h = OperatorHarness(this, reader = ScreenReader(maxNodes = 11))
        h.authorise()
        h.show(TestWindow(NOTES, "Short") { (1..10).forEach { label("Line $it") }; (1..5).forEach { child(name = "spacer $it") } })
        val all = h.inspectOk()
        assertEquals(10, all.elements.size)
        assertFalse(all.truncated)
        assertEquals(0, all.unlisted)
        assertNotNull(all.windowDigest)
    }

    // ---- S3: the keyboard action after a focus, with a stale node cache ----

    @Test
    fun `typing with submit into an unfocused field works while Android's node cache still shows it unfocused`() = runTest {
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
        // The model's cache is what this test says it is: a tree read through the root still shows the field as it was.
        assertFalse(h.readOk().elements.single { it.editable }.focused, "the cache was stale all along, as on a phone")
        PhoneNodeShadow.eventsArrived()
        assertTrue(h.readOk().elements.single { it.editable }.focused)
    }

    @Test
    fun `a field that reports its keyboard action a moment after the focus is waited for`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, "Chat") { field("message") { hint = "Message"; focusable = true } }
        h.show(window)
        val message = h.readOk().elements.single { it.editable }
        val field = window.node("message")
        val start = currentTime
        // The app adds the action 200 ms after it was focused.
        PhoneNodeShadow.onRefresh = {
            if (field.built.isFocused && currentTime - start >= 200 && field.built.actionList.none { it.id == AccessibilityAction.ACTION_IME_ENTER.id }) {
                field.built.addAction(AccessibilityAction.ACTION_IME_ENTER)
            }
        }

        assertEquals(ScreenResult.Ok(Unit), h.operator.type("Scout", message, "See you at 6", submit = true))
        assertEquals(
            listOf(AccessibilityNodeInfo.ACTION_FOCUS, AccessibilityNodeInfo.ACTION_SET_TEXT, AccessibilityAction.ACTION_IME_ENTER.id),
            window.log.map { it.action },
        )
        assertTrue(currentTime - start in 200..AccessibilityScreenOperator.FOCUS_SETTLE_MS, "waited ${currentTime - start} ms")
    }

    @Test
    fun `a focused field that never offers a keyboard action is refused after a short, bounded wait, and nothing is typed`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, "Chat") { field("message") { hint = "Message"; focusable = true } }
        h.show(window)
        val message = h.readOk().elements.single { it.editable }
        val start = currentTime

        val result = assertIs<ScreenResult.NotSupported>(h.operator.type("Scout", message, "See you at 6", submit = true))
        assertTrue("no keyboard action" in result.reason, result.reason)
        assertEquals(listOf(Performed("message", AccessibilityNodeInfo.ACTION_FOCUS)), window.log, "focused, then nothing typed")
        assertEquals(AccessibilityScreenOperator.FOCUS_SETTLE_MS, currentTime - start)
    }

    // ---- S4: a row re-bound to another item ----

    private fun invoices() = TestWindow(NOTES, "Invoices") {
        list {
            row("row1") { label("Invoice 1", name = "title1"); button("Delete", name = "delete1") }
            row("row2") { label("Invoice 2", name = "title2"); button("Delete", name = "delete2") }
        }
    }

    /** The verifier's example: both rows say "Delete"; the list re-binds row 1 to another item just before the click. */
    @Test
    fun `a row re-bound to another item whose button still says Delete is stale, and nothing is clicked`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = invoices()
        h.show(window)
        val first = h.readOk().elements.first { it.text == "Delete" }
        assertEquals("Row: Invoice 1 · Invoices", first.context)

        // The button itself is the same as before; its row now shows another invoice.
        PhoneNodeShadow.onRefresh = { node -> if (node.text?.toString() == "Delete") window.node("title1").built.text = "Invoice 9" }
        assertEquals(ScreenResult.Stale, h.operator.tap("Scout", first))
        assertTrue(window.log.isEmpty(), "nothing was clicked: ${window.log}")

        // The same row, as it was read, is tapped.
        PhoneNodeShadow.onRefresh = null
        window.node("title1").built.text = "Invoice 1"
        PhoneNodeShadow.eventsArrived()
        assertEquals(ScreenResult.Ok(Unit), h.operator.tap("Scout", first))
        assertEquals(listOf(Performed("delete1", AccessibilityNodeInfo.ACTION_CLICK)), window.log)
    }

    @Test
    fun `the row is asked from the app before a tap, not taken from Android's node cache`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = invoices()
        h.show(window)
        val first = h.readOk().elements.first { it.text == "Delete" }

        // The list re-bound the row before the tap's own read, and the cache has not caught up: that read still finds "Invoice 1".
        window.node("title1").built.text = "Invoice 9"
        assertEquals("Row: Invoice 1 · Invoices", h.readOk().elements.first { it.text == "Delete" }.context, "the cache is stale")
        assertEquals(ScreenResult.Stale, h.operator.tap("Scout", first))
        assertTrue(window.log.isEmpty(), "nothing was clicked: ${window.log}")
    }

    @Test
    fun `a row that is gone, or a list around the control that changed shape, is stale`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = invoices()
        h.show(window)
        val elements = h.readOk().elements
        val second = elements.last { it.text == "Delete" }
        val list = elements.single { it.scrollable }

        // Scrolling still works: the list and what its label is made of are asked again and are the same.
        assertEquals(ScreenResult.Ok(Unit), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, list))
        // The second row's title is hidden now (scrolled half out): the row no longer reads as it did.
        window.node("title2").built.isVisibleToUser = false
        assertEquals(ScreenResult.Stale, h.operator.tap("Scout", second))
        assertEquals(listOf("list"), window.log.map { it.node }, "only the scroll ran")
    }

    /** The app shows another page in the same window (same id, another title) between the tap's read and the click. */
    @Test
    fun `a window that became another page under the same id is stale`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = invoices()
        h.show(window)
        val first = h.readOk().elements.first { it.text == "Delete" }
        var turned = false
        PhoneNodeShadow.onRefresh = {
            if (!turned) {
                turned = true
                h.show(TestWindow(NOTES, "Trash") { label("Deleted invoices") })
            }
        }

        assertEquals(ScreenResult.Stale, h.operator.tap("Scout", first))
        assertTrue(turned && window.log.isEmpty(), "nothing was clicked: ${window.log}")
    }

    /** A scrolling page of plain text: its label keeps 300 characters, so only the start of the page is asked again before a scroll. */
    @Test
    fun `a long page scrolls, and a change at its start makes the scroll stale`() = runTest {
        val h = OperatorHarness(this)
        h.authorise()
        val window = TestWindow(NOTES, "Article") {
            child("android.widget.ScrollView", "page") {
                scrollable = true
                actions += AccessibilityAction.ACTION_SCROLL_FORWARD
                (1..400).forEach { label("Paragraph $it of the article", name = "p$it") }
            }
        }
        h.show(window)
        val page = h.readOk().elements.single { it.scrollable }
        assertEquals(300, page.label!!.length, "the label is the start of the page")
        var asked = 0
        PhoneNodeShadow.onRefresh = { asked++ }

        assertEquals(ScreenResult.Ok(Unit), h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, page))
        assertTrue(asked < 40, "the whole page was asked from the app again: $asked nodes")

        window.node("p2").built.text = "Paragraph 2 was replaced"
        assertEquals(ScreenResult.Stale, h.operator.scroll("Scout", NOTES, ScrollDirection.DOWN, page))
        assertEquals(1, window.log.size, "only the first scroll ran: ${window.log}")
    }

    // ---- S6: the state before the first read ----

    @Test
    fun `the state is not known yet until the first background read, and looking at it reads nothing`() = runTest {
        val h = OperatorHarness(this, connected = false)
        h.availability.enabled = true

        assertEquals(ScreenServiceState.CHECKING, h.operator.state.value, "nothing is read on the thread that looks")
        assertEquals(ScreenServiceState.CHECKING, h.operator.state.value)
        assertEquals(0, h.availability.reads, "Android's settings were not asked on the caller's thread")

        runCurrent()
        assertEquals(ScreenServiceState.STARTING, h.operator.state.value, "the state follows when the background read lands")
        assertTrue(h.availability.reads > 0)
        assertNotNull(h.availability.onChange, "changes are watched from then on")
    }

    @Test
    fun `a call made before the first read still gets the real state, never the placeholder`() = runTest {
        val h = OperatorHarness(this, connected = false)
        assertEquals(ScreenResult.Unavailable(ScreenServiceState.NOT_ENABLED), h.operator.read("Scout", NOTES))
        assertEquals(ScreenServiceState.NOT_ENABLED, h.operator.state.value)
    }
}
