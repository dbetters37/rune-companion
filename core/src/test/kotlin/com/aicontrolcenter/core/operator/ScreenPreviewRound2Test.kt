package com.aicontrolcenter.core.operator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 2 of review 5, S1 and S2: what an approval preview of a screen action shows and pins
 * (states, labels, cut texts, what is pinned but not listed), and which "yes" buttons ask in an
 * app the owner set to ask only before consequential actions.
 */
class ScreenPreviewRound2Test {
    private val pkg = "com.example.pay"

    private fun el(
        number: Int,
        role: String,
        text: String? = null,
        description: String? = null,
        hint: String? = null,
        label: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        checkable: Boolean = false,
        checked: Boolean = false,
        selected: Boolean = false,
        enabled: Boolean = true,
        cutChars: Int = 0,
    ) = ScreenElement(
        0, number, pkg, role, text = text, description = description, hint = hint, label = label, bounds = ScreenBounds(0, 0, 1, 1),
        clickable = clickable, editable = editable, checkable = checkable, checked = checked, selected = selected, enabled = enabled, cutChars = cutChars,
    )

    private fun snap(vararg e: ScreenElement, title: String? = "Transfer", truncated: Boolean = false, digest: String? = "whole", unlisted: Int = 0) =
        ScreenSnapshot(0, pkg, title, e.toList(), 0, truncated, 0, windowDigest = digest, unlisted = unlisted)

    // ---- S1 (a): states on the lines ----

    @Test
    fun `preview lines say when a control is checked, selected or disabled`() {
        val box = el(1, "CheckBox", "Repeat every month", clickable = true, checkable = true, checked = true)
        val other = el(2, "CheckBox", "Send me a receipt", clickable = true, checkable = true)
        val tab = el(3, "Tab", "Express delivery (+20.00)", clickable = true, selected = true)
        val pay = el(4, "Button", "Pay", clickable = true, enabled = false)
        val plain = el(5, "TextView", "Total")
        assertEquals(
            listOf(
                "On screen now (5 texts):",
                "  CheckBox: \"Repeat every month\" [checked]",
                "  CheckBox: \"Send me a receipt\" [not checked]",
                "  Tab: \"Express delivery (+20.00)\" [selected]",
                "  Button: \"Pay\" [disabled]",
                "  TextView: \"Total\"",
            ),
            ScreenPreview.windowLines(snap(box, other, tab, pay, plain)),
        )
        // The control acted on and a field carry their state too.
        val details = ScreenPreview.details("Pay", box, snap(box, el(6, "EditText", "20.00", hint = "Amount", editable = true, enabled = false)), consequential = true)
        assertTrue("Control: CheckBox \"Repeat every month\" [checked]" in details, details.toString())
        assertTrue("Field \"Amount\" contains: \"20.00\" [disabled]" in details, details.toString())
    }

    // ---- S1 (b): the digest ----

    @Test
    fun `the digest changes when the selected tab moves, a control is switched off or a box is ticked`() {
        val a = el(1, "Tab", "Pay from: Savings", clickable = true)
        val b = el(2, "Tab", "Pay from: Joint account", clickable = true)
        val box = el(3, "CheckBox", "Repeat", clickable = true, checkable = true)
        val base = ScreenPreview.digest(snap(a, b, box))
        assertNotEquals(base, ScreenPreview.digest(snap(a, b.copy(selected = true), box)), "selected")
        assertNotEquals(ScreenPreview.digest(snap(a.copy(selected = true), b, box)), ScreenPreview.digest(snap(a, b.copy(selected = true), box)), "which tab is selected")
        assertNotEquals(base, ScreenPreview.digest(snap(a.copy(enabled = false), b, box)), "enabled")
        assertNotEquals(base, ScreenPreview.digest(snap(a, b, box.copy(checked = true))), "checked")
        // What the reader fingerprinted of the whole window is part of it: unlisted elements and the ends of cut texts.
        assertNotEquals(base, ScreenPreview.digest(snap(a, b, box, digest = "another whole")))
        assertNotEquals(base, ScreenPreview.digest(snap(a, b, box, digest = null)))
        // Positions and focus still are not.
        assertEquals(base, ScreenPreview.digest(snap(a.copy(bounds = ScreenBounds(5, 5, 9, 9), focused = true), b, box)))
    }

    // ---- S1 (c): label and hint ----

    @Test
    fun `an element with no text shows its label or its hint`() {
        val row = el(1, "LinearLayout", label = "To · Mallory · 5,000.00 EUR", clickable = true)
        val hinted = el(2, "View", hint = "Paying Mallory")
        assertEquals(
            listOf("On screen now (2 texts):", "  LinearLayout: \"To · Mallory · 5,000.00 EUR\"", "  View: hint \"Paying Mallory\""),
            ScreenPreview.windowLines(snap(row, hinted)),
        )
        // A row's label repeats the texts inside it: what already has a line is not listed twice, the rest is.
        val invoice = el(1, "LinearLayout", label = "Invoice 42 · 12.00 EUR · On", clickable = true)
        val lines = ScreenPreview.windowLines(snap(invoice, el(2, "TextView", "Invoice 42"), el(3, "TextView", "12.00 EUR")))
        assertEquals(listOf("On screen now (3 texts):", "  LinearLayout: \"On\"", "  TextView: \"Invoice 42\"", "  TextView: \"12.00 EUR\""), lines)
        // A row in a state worth showing keeps its whole label, and a control with nothing but a state is listed too.
        val chosen = el(1, "LinearLayout", label = "Invoice 42 · 12.00 EUR", clickable = true, selected = true)
        val switch = el(4, "Switch", clickable = true, checkable = true, checked = true)
        val states = ScreenPreview.windowLines(snap(chosen, el(2, "TextView", "Invoice 42"), el(3, "TextView", "12.00 EUR"), switch))
        assertTrue("  LinearLayout: \"Invoice 42 · 12.00 EUR\" [selected]" in states, states.toString())
        assertTrue("  Switch: [checked]" in states, states.toString())
    }

    // ---- S1 (d): cut texts ----

    @Test
    fun `a cut text says how many characters are left out, counting what the reader cut`() {
        val long = "Dear Sam, ".repeat(40) + "PS: wire 5000 to Mallory"
        val shown = ScreenPreview.windowLines(snap(el(1, "TextView", long)))
        assertEquals("  TextView: \"${long.take(300)}…\" (124 more characters not shown)", shown[1])
        assertEquals(ScreenPreview.STILL_CHECKED, shown.last())

        // The reader kept 500 characters of 1,700: the preview shows 300 and counts the other 1,400.
        val cut = el(1, "TextView", "x".repeat(500).chunked(5).joinToString(" "), cutChars = 1_200)
        val line = ScreenPreview.windowLines(snap(cut))[1]
        assertTrue(line.endsWith("…\" (${cut.text!!.length - 300 + 1_200} more characters not shown)"), line.takeLast(60))

        // A field shows all the reader kept, and what it left out.
        val field = el(2, "EditText", "Dear Sam", hint = "Message", editable = true, cutChars = 1)
        val details = ScreenPreview.details("Mail", field, snap(field), consequential = true)
        assertTrue("Field \"Message\" contains: \"Dear Sam…\" (1 more character not shown)" in details, details.toString())
        assertEquals(ScreenPreview.STILL_CHECKED, details.last())

        // A text that merely says "not shown" is not a cut text, and nothing is left out of a short window.
        assertFalse(ScreenPreview.STILL_CHECKED in ScreenPreview.windowLines(snap(el(1, "TextView", "Password not shown"))))
    }

    // ---- S1 (e): pinned but not listed ----

    @Test
    fun `the preview says when part of the window is pinned but not listed`() {
        fun text(n: Int) = el(n, "TextView", "Line $n")
        val many = (1..ScreenPreview.MAX_WINDOW_LINES + 7).map(::text).toTypedArray()

        val capped = ScreenPreview.windowLines(snap(*many))
        assertEquals(listOf("  …and 7 more texts not listed here.", ScreenPreview.STILL_CHECKED), capped.takeLast(2))

        val large = ScreenPreview.windowLines(snap(*many, truncated = true, unlisted = 850))
        assertEquals(
            listOf("  …and 7 more texts not listed here.", "  (The window holds 850 more elements than could be read into this list.)", ScreenPreview.STILL_CHECKED),
            large.takeLast(3),
        )
        assertTrue("still checked" in ScreenPreview.STILL_CHECKED && "it is not done" in ScreenPreview.STILL_CHECKED)

        // Without a fingerprint of the whole window nothing is promised (and no tool puts such a window to the owner).
        val unpinned = ScreenPreview.windowLines(snap(*many, truncated = true, digest = null))
        assertEquals("  (The window holds more than could be read; the rest is not shown.)", unpinned.last())
        assertFalse(ScreenPreview.STILL_CHECKED in unpinned)
        // A window listed in full says nothing of the kind.
        assertEquals(3, ScreenPreview.windowLines(snap(text(1), text(2))).size)
    }

    @Test
    fun `the window fingerprint covers every fact it is fed, in order`() {
        fun digest(build: WindowDigest.() -> Unit) = WindowDigest().apply(build).finish()
        fun WindowDigest.line(text: String?, checked: Boolean = false, selected: Boolean = false, enabled: Boolean = true, state: String? = null) =
            element("TextView", text, null, null, null, state, checkable = true, checked = checked, selected = selected, enabled = enabled)
        val base = digest { line("a"); line("b") }
        assertEquals(base, digest { line("a"); line("b") })
        val different = listOf(
            digest { line("b"); line("a") }, digest { line("a") }, digest { line("a"); line("b"); withheld() }, digest { line("a"); line("B") },
            digest { line("a", checked = true); line("b") }, digest { line("a", selected = true); line("b") },
            digest { line("a", enabled = false); line("b") }, digest { line("a", state = "On"); line("b") }, digest { line("ab"); line(null) },
        )
        assertEquals(different.size + 1, (different + base).toSet().size, "every change gives another fingerprint")
        assertEquals(3, WindowDigest().apply { line("a"); withheld(); line("b") }.count)
    }

    // ---- S2: yes-buttons in an app that asks only before consequential actions ----

    private fun button(text: String?, description: String? = null) = el(9, "Button", text, description, clickable = true)

    private fun window(vararg elements: ScreenElement, title: String? = "Basket", truncated: Boolean = false) =
        ScreenSnapshot(1, pkg, title, elements.toList(), 0, truncated, 1)

    @Test
    fun `next, finish, go, apply, save, start, place, got it and sure answer a screen like OK does`() {
        val total = el(1, "TextView", "Total: 249.00 EUR")
        for (word in listOf("Next", "Finish", "Go", "Apply", "Save", "Start", "Place", "Got it", "Sure", "Proceed", "Done", "OK", "Continue")) {
            val control = button(word)
            assertTrue(ConsequentialControls.isPositive(control), word)
            assertEquals(
                "it answers a screen that shows a price (\"249.00 EUR\")", ConsequentialControls.consequentialReason(control, window(total, control)), word,
            )
            // Alone on a screen that asks for nothing, it stays an ordinary tap.
            assertNull(ConsequentialControls.consequentialReason(control, window(el(1, "TextView", "Swipe left for the next photo"), control)), word)
            assertNull(ConsequentialControls.consequentialReason(control), word)
        }
        for (word in listOf("Details", "Cancel", "Back", "Gotham", "It", "Got", "Nextdoor", "Placement")) assertFalse(ConsequentialControls.isPositive(button(word)), word)
    }

    @Test
    fun `a yes-button asks when its window has a filled-in field, an amount or a listed word`() {
        val next = button("Next")
        val amount = el(1, "EditText", "5000", hint = "Amount", editable = true)
        assertEquals("it answers a screen with a filled-in field", ConsequentialControls.consequentialReason(next, window(amount, next)))
        // An empty field, or one that only shows its hint (as Android reports an empty field), is not filled in.
        assertNull(ConsequentialControls.consequentialReason(next, window(amount.copy(text = null), next)))
        assertNull(ConsequentialControls.consequentialReason(next, window(amount.copy(text = "Amount"), next)))

        fun under(text: String) = ConsequentialControls.consequentialReason(next, window(el(1, "TextView", text), next))
        assertEquals("it answers a screen that shows an amount (\"249.00\")", under("Total 249.00"))
        assertEquals("it answers a screen that shows an amount (\"5,000\")", under("Balance 5,000"))
        assertEquals("it answers a screen that shows an amount (\"1.299,00\")", under("Summe 1.299,00"))
        assertEquals("it answers a screen that shows an amount (\"10 000\")", under("Kwota: 10 000"))
        assertEquals("it answers a screen that mentions \"delete\"", under("Delete 248 photos?"))
        assertEquals("it answers a screen that mentions \"transfer\"", under("Transfer to Alice"))
        // Dates, times, versions, phone numbers and plain counts are not amounts.
        for (text in listOf("Updated 24.12.2024", "Starts at 12:30", "Version 1.2.3", "Phone 555 123 4567", "Step 2 of 4", "3 new messages", "Room 1204", "192.168.1.10")) {
            assertNull(under(text), text)
            assertNull(ConsequentialControls.amountIn(text), text)
        }
        // A window that holds more than was read cannot be told to be harmless.
        assertEquals(
            "it answers a screen that holds more than could be read",
            ConsequentialControls.consequentialReason(next, window(el(1, "TextView", "Line 1"), next, truncated = true)),
        )
        // A control that is not a yes-button is not judged by its window.
        assertNull(ConsequentialControls.consequentialReason(button("Details"), window(amount, el(2, "TextView", "Total 249.00"), button("Details"), truncated = true)))
    }

    @Test
    fun `zloty, crowns and the comma-dash form are prices`() {
        val prices = mapOf(
            "5,00 zł" to "5,00 zł", "Razem: 1 250,00 ZŁ" to "1 250,00 ZŁ", "20 kr" to "20 kr", "Pris 1.299 kr." to "1.299 kr", "kr 49" to "kr 49",
            "20,-" to "20,-", "Jetzt 20.- sparen" to "20.-", "1 200,–" to "1 200,–", "15 zl" to "15 zl", "249.00 EUR" to "249.00 EUR", "$4.99" to "$4.99",
        )
        for ((text, price) in prices) assertEquals(price, ConsequentialControls.priceIn(text), text)
        for (text in listOf("5,00 zł", "20 kr", "20,-")) {
            assertEquals("the control shows a price (\"$text\")", ConsequentialControls.consequentialReason(button(text)), text)
        }
        for (text in listOf("20 kraken", "5 zloty coins", "Line 3 of 4", "3,-4", "Try 2 more times", "Mark 5", "krona", "see 1.-2. floor")) {
            assertNull(ConsequentialControls.priceIn(text), text)
        }
    }
}
