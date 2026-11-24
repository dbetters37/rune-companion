package com.aicontrolcenter.testkit.contract

import com.aicontrolcenter.core.operator.ScreenOperator
import com.aicontrolcenter.core.operator.ScreenPreview
import com.aicontrolcenter.core.operator.ScreenResult
import com.aicontrolcenter.core.operator.ScreenSnapshot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One control of the window a [ScreenInspectContract] test puts on screen. */
data class ShownControl(
    /** The short widget kind, as [com.aicontrolcenter.core.operator.ScreenElement.role] reports it. */
    val role: String = "TextView",
    val text: String? = null,
    val description: String? = null,
    val hint: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    /** A password field: counted as withheld, never read. */
    val password: Boolean = false,
)

/** A screen under test: [operator] reads it; [show] puts a window of [ScreenInspectContract.PACKAGE] in front. */
interface InspectedScreen {
    val operator: ScreenOperator

    fun show(title: String, controls: List<ShownControl>)
}

/**
 * What [ScreenOperator.inspect] gives an approval preview and its re-check, whatever reads the
 * screen: the list is bounded, long texts are cut and counted, and
 * [ScreenSnapshot.windowDigest] pins the whole window all the same (the elements the list leaves
 * out, the ends of cut texts, and checked, selected and enabled states), up to a ceiling above
 * which there is no fingerprint. The testkit's `FakeScreenOperator` and `:device`'s operator
 * over the accessibility service both pass it, so tools tested against the fake meet the same
 * snapshots on a phone.
 */
abstract class ScreenInspectContract {
    /**
     * A ready screen operator with [PACKAGE] authorised and nothing on screen yet. It
     * fingerprints windows of at most [digestMaxElements] elements.
     */
    protected abstract suspend fun TestScope.screen(digestMaxElements: Int = ScreenOperator.DIGEST_MAX_ELEMENTS): InspectedScreen

    private suspend fun InspectedScreen.inspect(maxElements: Int = ScreenOperator.INSPECT_MAX_ELEMENTS): ScreenSnapshot =
        assertIs<ScreenResult.Ok<ScreenSnapshot>>(operator.inspect(PACKAGE, maxElements)).value

    private fun lines(count: Int): List<ShownControl> = (1..count).map { ShownControl(text = "Line $it") }

    private fun <T> List<T>.with(index: Int, change: (T) -> T): List<T> = mapIndexed { i, item -> if (i == index) change(item) else item }

    @Test
    fun `inspect lists at most the limit and fingerprints what it leaves out`() = runTest {
        val screen = screen()
        screen.show("Statement", lines(30))
        val first = screen.inspect(maxElements = 10)

        assertEquals(10, first.elements.size)
        assertEquals("Line 10", first.elements.last().text)
        assertTrue(first.truncated)
        assertEquals(20, first.unlisted)
        val whole = assertNotNull(first.windowDigest, "the whole window is fingerprinted, also what the list leaves out")
        assertEquals(whole, screen.inspect(maxElements = 10).windowDigest, "the same window, read again, has the same fingerprint")

        // A line the list does not hold changes: the listed elements are the same, the fingerprint is not.
        screen.show("Statement", lines(30).with(24) { it.copy(text = "Line 25: pay Mallory") })
        val changed = screen.inspect(maxElements = 10)
        assertEquals(first.elements, changed.elements)
        assertNotEquals(whole, changed.windowDigest)
        assertNotEquals(ScreenPreview.digest(first), ScreenPreview.digest(changed), "so the approval's pinned digest changes too")

        // One more line at the end is a change as well.
        screen.show("Statement", lines(31))
        assertNotEquals(whole, screen.inspect(maxElements = 10).windowDigest)
    }

    @Test
    fun `a window that fits the list is not truncated and is fingerprinted too`() = runTest {
        val screen = screen()
        screen.show("Statement", lines(12))
        val all = screen.inspect()
        assertEquals(12, all.elements.size)
        assertFalse(all.truncated)
        assertEquals(0, all.unlisted)
        assertNotNull(all.windowDigest)
    }

    @Test
    fun `a long text is cut, counted and fingerprinted to its end`() = runTest {
        val max = ScreenOperator.MAX_TEXT_CHARS
        val letter = "Dear Sam, ".repeat(70)
        val screen = screen()
        screen.show("Draft", listOf(ShownControl(text = letter + "PS: wire 500 to Alice")))
        val first = screen.inspect()
        val shown = first.elements.single()

        assertEquals((letter + "PS: wire 500 to Alice").take(max), shown.text)
        assertEquals(letter.length + "PS: wire 500 to Alice".length - max, shown.cutChars)

        // Only the end, past what the element keeps, changes.
        screen.show("Draft", listOf(ShownControl(text = letter + "PS: wire 900 to Mallory")))
        val changed = screen.inspect()
        assertEquals(shown.text, changed.elements.single().text)
        assertNotEquals(assertNotNull(first.windowDigest), changed.windowDigest, "the uncut text is what the fingerprint pins")
        assertNotEquals(ScreenPreview.digest(first), ScreenPreview.digest(changed))
    }

    @Test
    fun `checked, selected and enabled states are read and fingerprinted, listed or not`() = runTest {
        val controls = listOf(
            ShownControl(role = "Button", text = "Pay", clickable = true),
            ShownControl(role = "CheckBox", text = "Repeat every month", clickable = true, checkable = true),
        ) + lines(8) + ShownControl(role = "CheckBox", text = "Share my address", clickable = true, checkable = true) + lines(3)
        val screen = screen()
        screen.show("Checkout", controls)
        val base = screen.inspect(maxElements = 5)
        assertTrue(base.elements[0].enabled && !base.elements[0].selected && !base.elements[1].checked)

        val changes = mapOf<String, (List<ShownControl>) -> List<ShownControl>>(
            "a listed box is ticked" to { it.with(1) { c -> c.copy(checked = true) } },
            "a listed control is selected" to { it.with(0) { c -> c.copy(selected = true) } },
            "a listed control is switched off" to { it.with(0) { c -> c.copy(enabled = false) } },
            "a box the list leaves out is ticked" to { it.with(10) { c -> c.copy(checked = true) } },
            "a line the list leaves out is selected" to { it.with(7) { c -> c.copy(selected = true) } },
            "a line the list leaves out is switched off" to { it.with(7) { c -> c.copy(enabled = false) } },
        )
        for ((what, change) in changes) {
            screen.show("Checkout", change(controls))
            val now = screen.inspect(maxElements = 5)
            assertNotEquals(base.windowDigest, now.windowDigest, what)
            assertNotEquals(ScreenPreview.digest(base), ScreenPreview.digest(now), what)
        }
        screen.show("Checkout", controls.with(1) { it.copy(checked = true) }.with(0) { it.copy(selected = true, enabled = false) })
        val read = screen.inspect(maxElements = 5)
        assertTrue(read.elements[1].checked && read.elements[0].selected && !read.elements[0].enabled, "the listed elements carry their states")
    }

    @Test
    fun `a window above the ceiling has no fingerprint`() = runTest {
        val screen = screen(digestMaxElements = 20)
        screen.show("Feed", lines(20))
        assertNotNull(screen.inspect(maxElements = 10).windowDigest, "at the ceiling the window is still fingerprinted")

        screen.show("Feed", lines(21))
        val over = screen.inspect(maxElements = 10)
        assertNull(over.windowDigest, "one element more and nothing vouches for the window")
        assertTrue(over.truncated)
        assertEquals(0, over.unlisted)
        assertEquals(10, over.elements.size)
    }

    @Test
    fun `a password field is counted and marked in the fingerprint, and nothing of what it holds is`() = runTest {
        val controls = listOf(ShownControl(text = "Sign in"), ShownControl(role = "EditText", text = "hunter2", editable = true, password = true))
        val screen = screen()
        screen.show("Account", controls)
        val first = screen.inspect()
        assertEquals(1, first.withheld)
        assertEquals(listOf("Sign in"), first.elements.mapNotNull { it.text })

        screen.show("Account", controls.with(1) { it.copy(text = "another password") })
        assertEquals(first.windowDigest, screen.inspect().windowDigest, "what a password field holds is never part of the fingerprint")
        screen.show("Account", controls.take(1))
        assertNotEquals(first.windowDigest, screen.inspect().windowDigest, "that the field is there is")
    }

    @Test
    fun `a read for the model carries no fingerprint, only inspect does`() = runTest {
        val screen = screen()
        screen.show("Statement", lines(5))
        val read = assertIs<ScreenResult.Ok<ScreenSnapshot>>(screen.operator.read("Scout", PACKAGE)).value
        assertNull(read.windowDigest)
        assertEquals(0, read.unlisted)
    }

    /** The app the contract's windows belong to. */
    companion object {
        const val PACKAGE = "com.example.notes"
    }
}
