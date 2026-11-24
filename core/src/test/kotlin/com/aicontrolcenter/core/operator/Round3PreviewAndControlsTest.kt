package com.aicontrolcenter.core.operator

import org.junit.Test
import java.text.Normalizer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 3. P1 (rest of S5): characters that can pass for a quote mark inside a quoted preview
 * value, beyond the list of round 2. C1 (rest of S2): a yes-button beside a price or an amount
 * that sits on a control itself (a tappable summary row), in an app set to ask only before
 * consequential actions.
 */
class Round3PreviewAndControlsTest {
    private fun cp(codePoint: Int) = String(Character.toChars(codePoint))

    private fun marker(codePoint: Int) = "⟨U+%04X⟩".format(codePoint)

    /** The plain double quotes left in a quoted value: the one that opens it and the one that ends it. */
    private fun plainQuotes(shown: String) = shown.replace(PreviewText.QUOTE, "").count { it == '"' }

    // ---- P1 ----

    @Test
    fun `the verifier's look-alikes are shown by their code, one alone and two in a row`() {
        // "U+1FEF Greek varia: one "a`b"  two "a``b"" and "U+02C8 modifier vertical line: … two "aˈˈb"".
        assertEquals("\"a⟨U+1FEF⟩b\"", PreviewText.quoted("a`b"))
        assertEquals("\"a⟨U+1FEF⟩⟨U+1FEF⟩b\"", PreviewText.quoted("a``b"))
        assertEquals("\"a⟨U+02C8⟩⟨U+02C8⟩b\"", PreviewText.quoted("aˈˈb"))
        for (fake in listOf("ˈˈ", "¨", "‴", "ˊˊ", "׳׳", "ꞌꞌ", "❛❛", "´´", "ʹʹ", "⁗")) {
            val value = "ls$fake in the top level of the workspace. Read-only listing; nothing else runs. $fake; rm -rf ../databases ; echo $fake"
            val shown = PreviewText.quoted(value)
            assertEquals(2, plainQuotes(shown), shown)
            for (c in fake.codePoints().toArray().distinct()) {
                assertFalse(cp(c) in shown, "U+%04X is still drawn in: %s".format(c, shown))
                assertTrue(marker(c) in shown, shown)
            }
        }
    }

    @Test
    fun `every character on the decided list is shown by its code wherever it stands`() {
        val listed = listOf(
            0x00A8, 0x2034, 0x2037, 0x2057, 0x05F3, 0x05F4, 0xA78B, 0xA78C, 0x275B, 0x275C, 0xFF40, 0x1FEF, 0x1FFD, 0x0374, 0x02B9, 0x02BA, 0x3003,
            // The verifier's other finds, which the rules below cover.
            0x02C8, 0x02CA, 0x02CB,
        )
        for (c in listed) {
            assertEquals("\"a${marker(c)}b\"", PreviewText.quoted("a${cp(c)}b"), "U+%04X".format(c))
            assertTrue(PreviewText.looksLikeQuote(c), "U+%04X".format(c))
        }
    }

    @Test
    fun `opening and closing quotes of every script, modifier letters and accents that stand alone are shown by their code`() {
        var quotes = 0
        var modifiers = 0
        for (c in 0x80..0x10FFFF) {
            val type = Character.getType(c)
            val expected = when {
                type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || type == Character.FINAL_QUOTE_PUNCTUATION.toInt() -> true.also { quotes++ }
                c in 0x02B0..0x02FF -> true
                type == Character.MODIFIER_SYMBOL.toInt() -> (c !in 0x1F3FB..0x1F3FF).also { if (it) modifiers++ }
                else -> continue
            }
            assertEquals(expected, PreviewText.looksLikeQuote(c), "U+%04X".format(c))
            if (expected) assertEquals("\"a${marker(c)}b\"", PreviewText.quoted("a${cp(c)}b"), "U+%04X".format(c))
        }
        // Outside the modifier letters block (counted above, whatever their category) Unicode has some 75 such accents and modifiers.
        assertTrue(quotes >= 10 && modifiers >= 60, "the JDK's character table was read: $quotes quotes, $modifiers modifiers")
        // « » ‹ › ‘ ’ “ ” are among them.
        for (c in listOf(0x00AB, 0x00BB, 0x2039, 0x203A, 0x2018, 0x2019, 0x201C, 0x201D)) assertTrue(PreviewText.looksLikeQuote(c), "U+%04X".format(c))
    }

    @Test
    fun `a character that is a quote, an apostrophe, a backtick or an acute accent in a compatibility form is shown by its code`() {
        var found = 0
        for (c in 0x80..0x2FFFF) {
            if (!Character.isDefined(c) || Character.getType(c) == Character.SURROGATE.toInt()) continue
            val text = cp(c)
            val forms = listOf(Normalizer.normalize(text, Normalizer.Form.NFKC), Normalizer.normalize(text, Normalizer.Form.NFKD))
            if (forms.none { form -> form.any { it == '"' || it == '\'' || it == '`' || it == '´' } }) continue
            found++
            assertTrue(PreviewText.looksLikeQuote(c), "U+%04X".format(c))
            assertEquals("\"a${marker(c)}b\"", PreviewText.quoted("a${text}b"), "U+%04X".format(c))
        }
        // The full-width quote, apostrophe and backtick, and the Greek varia (the same character as the backtick). The acute
        // accent and the Greek oxia turn into a blank and a combining accent in these forms: they are marked by the other rules.
        assertTrue(found >= 4, "found $found")
        for (c in listOf(0xFF02, 0xFF07, 0xFF40, 0x00B4, 0x1FEF, 0x1FFD)) assertTrue(PreviewText.looksLikeQuote(c), "U+%04X".format(c))
    }

    @Test
    fun `the plain apostrophe, backtick and caret stay when alone, and no pair of look-alikes is left to draw a double quote`() {
        assertEquals("\"don't `x` 2^10 'so'\"", PreviewText.quoted("don't `x` 2^10 'so'"))
        assertEquals("\"a⟨U+0027⟩⟨U+0027⟩b⟨U+0060⟩⟨U+0060⟩c\"", PreviewText.quoted("a''b``c"))
        // A typographic apostrophe is shown by its code also alone, and so is a plain one right beside it, on either side.
        assertEquals("\"it⟨U+2019⟩s\"", PreviewText.quoted("it’s"))
        assertEquals("\"a⟨U+2019⟩⟨U+0027⟩b\"", PreviewText.quoted("a’'b"))
        assertEquals("\"a⟨U+0027⟩⟨U+2019⟩b\"", PreviewText.quoted("a'’b"))
        assertEquals("\"a⟨U+2032⟩⟨U+2032⟩b 5′\"", PreviewText.quoted("a′′b 5′"))
        // Whatever is inside, the only plain double quotes left are the two around the value.
        val all = (0x80..0x2FFF).filter { PreviewText.looksLikeQuote(it) }.joinToString("") { cp(it) }
        for (value in listOf(all, "x\"”''\"ʺˈˈ`` ¨", "＂＇＇｀｀")) {
            val shown = PreviewText.quoted(value)
            assertEquals(2, plainQuotes(shown), shown)
            assertFalse(Regex("['`′‵‚]{2}").containsMatchIn(shown), shown)
        }
    }

    @Test
    fun `ordinary text in other scripts stays readable`() {
        for (text in listOf(
            "コーヒーをください。ありがとうございます、スーパーマーケットへ行きます",
            "こんにちは「世界」、今日は月曜日です",
            "Καλημέρα, τι κάνεις; Είμαι καλά, ευχαριστώ πολύ.",
            "שלום עולם, מה שלומך היום",
            "مرحبا بالعالم، كيف حالك اليوم؟",
            "Привет, мир! Как дела? Ёжик в тумане.",
            "你好，世界！今天是星期一。",
            "안녕하세요, 오늘은 월요일입니다.",
            "नमस्ते दुनिया, आज सोमवार है।",
            "สวัสดีชาวโลก วันนี้วันจันทร์",
            "Zażółć gęślą jaźń; Größe: 5 m², Ångström, façade, niño, crème brûlée",
            "Thanks 👍🏽 see you at 6 ☕️ 2^10 = 1024, a_b, ~/notes, 50% (ca.) [1] {x} <y> #tag @home",
        )) {
            assertEquals("\"$text\"", PreviewText.quoted(text), text)
            assertEquals(text, PreviewText.exact(text), text)
        }
        // The Japanese long-vowel mark, Greek and Hebrew letters are not look-alikes of a quote.
        for (c in listOf(0x30FC, 0x3099, 0x03B1, 0x03AC, 0x05D0, 0x05E9, 0x0301, 0x00E9, 0x1F3FD)) assertFalse(PreviewText.looksLikeQuote(c), "U+%04X".format(c))
    }

    // ---- C1 ----

    private val pkg = "com.example.bank"

    private fun el(number: Int, role: String, text: String? = null, description: String? = null, label: String? = null, clickable: Boolean = false, editable: Boolean = false, hint: String? = null) =
        ScreenElement(0, number, pkg, role, text = text, description = description, hint = hint, label = label, bounds = ScreenBounds(0, 0, 1, 1), clickable = clickable, editable = editable)

    private fun window(vararg elements: ScreenElement) = ScreenSnapshot(1, pkg, "Step 2", elements.toList(), 0, false, 1)

    private val yesWords = listOf("OK", "Continue", "Yes", "Next", "Save", "Done", "Proceed", "Got it")

    /** The verifier's rows: a row that is tappable and carries its text itself, as Compose apps report a summary row. */
    @Test
    fun `a yes-button beside a price or an amount on a tappable row asks`() {
        for (word in yesWords) {
            val button = el(9, "Button", word, clickable = true)
            fun beside(row: ScreenElement) = ConsequentialControls.consequentialReason(button, window(row, button))
            assertEquals("it answers a screen that shows a price (\"249.00 EUR\")", beside(el(1, "View", "Total: 249.00 EUR", clickable = true)), word)
            assertEquals("it answers a screen that shows a price (\"5,000.00 EUR\")", beside(el(1, "View", label = "To Mallory · 5,000.00 EUR", clickable = true)), word)
            assertEquals("it answers a screen that shows a price (\"€9.99\")", beside(el(1, "View", description = "Plan: €9.99 a month", clickable = true)), word)
            assertEquals("it answers a screen that shows an amount (\"1.299,00\")", beside(el(1, "View", "Summe 1.299,00", clickable = true)), word)
            assertEquals("it answers a screen that shows an amount (\"5,000\")", beside(el(1, "View", label = "Balance · 5,000", clickable = true)), word)
            // As before for a text that is not a control.
            assertEquals("it answers a screen that shows a price (\"249.00 EUR\")", beside(el(1, "TextView", "Total: 249.00 EUR")), word)
        }
    }

    @Test
    fun `the listed words still count only on what is not a control itself, and a plain count is not an amount`() {
        val ok = el(9, "Button", "OK", clickable = true)
        // The decision of round 3: a "Delete" button elsewhere in the window does not make every OK a delete.
        assertNull(ConsequentialControls.consequentialReason(ok, window(el(1, "View", "Delete all 248 photos for good", clickable = true), ok)))
        assertNull(ConsequentialControls.consequentialReason(ok, window(el(1, "Button", "Send", clickable = true), ok)))
        assertEquals("it answers a screen that mentions \"delete\"", ConsequentialControls.consequentialReason(ok, window(el(1, "TextView", "Delete all 248 photos for good"), ok)))
        for (text in listOf("248 photos", "Step 2 of 4", "Updated 24.12.2024", "Starts at 12:30", "Version 1.2.3", "Room 1204")) {
            assertNull(ConsequentialControls.consequentialReason(ok, window(el(1, "View", text, clickable = true), ok)), text)
        }
        // A price on a control of another app's overlay, or on a button that is not a yes-button, changes nothing.
        val foreign = el(1, "View", "Total: 249.00 EUR", clickable = true).copy(packageName = "com.other")
        assertNull(ConsequentialControls.consequentialReason(ok, window(foreign, ok)))
        val details = el(9, "Button", "Details", clickable = true)
        assertNull(ConsequentialControls.consequentialReason(details, window(el(1, "View", "Total: 249.00 EUR", clickable = true), details)))
    }

    @Test
    fun `a field is still judged by whether it is filled in, not by a hint that looks like an amount`() {
        val next = el(9, "Button", "Next", clickable = true)
        val empty = el(1, "EditText", "0.00", hint = "0.00", clickable = true, editable = true)
        assertNull(ConsequentialControls.consequentialReason(next, window(empty, next)))
        assertEquals("it answers a screen with a filled-in field", ConsequentialControls.consequentialReason(next, window(empty.copy(text = "5000"), next)))
        assertEquals("it answers a screen with a filled-in field", ConsequentialControls.consequentialReason(next, window(empty.copy(text = "249.00"), next)))
    }
}
