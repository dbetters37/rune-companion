package com.aicontrolcenter.core.operator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round 4. J2: a few more characters and pairs that can pass for a quote mark inside a quoted
 * preview value (apostrophes or backticks with a combining mark between them, a combining mark
 * on punctuation, some further look-alikes). J3: a surrogate without its partner, which Java
 * cannot encode and which draws as a blank box.
 *
 * The marks are help for the reader: the line that shows the value is exact either way.
 */
class Round4PreviewTest {
    private fun cp(vararg codePoints: Int) = codePoints.joinToString("") { String(Character.toChars(it)) }

    private fun marker(codePoint: Int) = "⟨U+%04X⟩".format(codePoint)

    /** The plain double quotes left in a quoted value: the one that opens it and the one that ends it. */
    private fun plainQuotes(shown: String) = shown.replace(PreviewText.QUOTE, "").count { it == '"' }

    private val dotBelow = cp(0x0323)
    private val diaeresis = cp(0x0308)

    // ---- J2 ----

    @Test
    fun `two apostrophes or backticks with a combining mark between them are shown by their code, and the mark too`() {
        // The verifier's lines: "apostrophe, combining dot below, apostrophe", "…combining diaeresis…", "backtick, combining dot below, backtick".
        assertEquals("\"a⟨U+0027⟩⟨U+0323⟩⟨U+0027⟩b\"", PreviewText.quoted("a'$dotBelow'b"))
        assertEquals("\"a⟨U+0027⟩⟨U+0308⟩⟨U+0027⟩b\"", PreviewText.quoted("a'$diaeresis'b"))
        assertEquals("\"a⟨U+0060⟩⟨U+0323⟩⟨U+0060⟩b\"", PreviewText.quoted("a`$dotBelow`b"))
        // Several marks between, a mark on the last one, three in the run, and the kinds mixed.
        assertEquals("\"a⟨U+0027⟩⟨U+0323⟩⟨U+0308⟩⟨U+0027⟩⟨U+0308⟩b\"", PreviewText.quoted("a'$dotBelow$diaeresis'${diaeresis}b"))
        assertEquals("\"⟨U+0060⟩⟨U+0323⟩⟨U+0027⟩⟨U+0323⟩⟨U+2032⟩\"", PreviewText.quoted("`$dotBelow'$dotBelow′"))
        // One alone stays, as before; so do two with a letter between them.
        assertEquals("\"don't `x` l'été\"", PreviewText.quoted("don't `x` l'été"))
        assertEquals("\"'a' `e$diaeresis`\"", PreviewText.quoted("'a' `e$diaeresis`"))
    }

    @Test
    fun `a combining mark on punctuation is shown by its code, on a letter it stays`() {
        // The verifier's lines: an apostrophe with U+0315 beside it, a full stop carrying U+030E or U+030B.
        assertEquals("\"a'⟨U+0315⟩b\"", PreviewText.quoted("a'${cp(0x0315)}b"))
        assertEquals("\"a.⟨U+030E⟩b\"", PreviewText.quoted("a.${cp(0x030E)}b"))
        assertEquals("\"a.⟨U+030B⟩b\"", PreviewText.quoted("a.${cp(0x030B)}b"))
        // Every mark of the pile, and on any punctuation: brackets, dashes, the underscore, punctuation of other scripts.
        assertEquals("\"a.⟨U+030E⟩⟨U+0301⟩b\"", PreviewText.quoted("a.${cp(0x030E, 0x0301)}b"))
        for (base in listOf(",", ";", ":", "!", "?", ")", "(", "-", "_", "/", "\\", "`", "。", "、", "।", "״")) {
            val shown = PreviewText.quoted("a$base${cp(0x030B)}b")
            assertTrue(marker(0x030B) in shown, "$base: $shown")
        }
        // A mark cannot draw on the marker of what stands before it either: an escaped quote, a look-alike shown by its code.
        assertEquals("\"a${PreviewText.QUOTE}⟨U+0308⟩b\"", PreviewText.quoted("a\"${diaeresis}b"))
        assertEquals("\"a⟨U+201D⟩⟨U+0323⟩b\"", PreviewText.quoted("a”${dotBelow}b"))
        // Letters and digits keep their marks.
        for (text in listOf("e${cp(0x0301)}te${cp(0x0301)}", "a$diaeresis.b$dotBelow", "x${cp(0x030B)}", "1${cp(0x0305)}")) assertEquals("\"$text\"", PreviewText.quoted(text), text)
    }

    @Test
    fun `the further look-alikes of the verifier are shown by their code`() {
        // Twins of the heavy ornamental quotes, and the Vedic sign that draws as two strokes: wherever they stand.
        for (c in listOf(0x1F676, 0x1F677, 0x1F678, 0x1CD3)) {
            assertEquals("\"a${marker(c)}b\"", PreviewText.quoted("a${cp(c)}b"), "U+%04X".format(c))
            assertTrue(PreviewText.looksLikeQuote(c), "U+%04X".format(c))
        }
        // The NKo tone apostrophes and the Armenian apostrophe are apostrophes: one alone stays, two in a row do not,
        // nor one beside a plain apostrophe, nor two with a combining mark between them.
        for (c in listOf(0x07F4, 0x07F5, 0x055A)) {
            assertEquals("\"a${cp(c)}b\"", PreviewText.quoted("a${cp(c)}b"), "U+%04X".format(c))
            assertEquals("\"a${marker(c)}${marker(c)}b\"", PreviewText.quoted("a${cp(c, c)}b"), "U+%04X".format(c))
            assertEquals("\"a${marker(c)}⟨U+0027⟩b\"", PreviewText.quoted("a${cp(c)}'b"), "U+%04X".format(c))
            assertEquals("\"a${marker(c)}⟨U+0323⟩${marker(c)}b\"", PreviewText.quoted("a${cp(c)}$dotBelow${cp(c)}b"), "U+%04X".format(c))
        }
    }

    @Test
    fun `a half-width voiced sound mark is shown by its code unless it follows a half-width katakana letter`() {
        val voiced = cp(0xFF9E)
        val semiVoiced = cp(0xFF9F)
        assertEquals("\"a⟨U+FF9E⟩b\"", PreviewText.quoted("a${voiced}b"))
        assertEquals("\"a⟨U+FF9F⟩b\"", PreviewText.quoted("a${semiVoiced}b"))
        assertEquals("\"⟨U+FF9E⟩\"", PreviewText.quoted(voiced))
        assertEquals("\"ls⟨U+FF9E⟩ x\"", PreviewText.quoted("ls$voiced x"))
        // In words it is the mark of the letter before it.
        for (word in listOf("ｶﾞｷﾞｸﾞ ﾊﾟﾋﾟ", "ﾃﾞｰﾀ ﾊﾞｯｸｱｯﾌﾟ", "ｳﾞｧｲｵﾘﾝ", "ﾋﾞｰﾙ ﾌﾟﾘﾝﾀ")) assertEquals("\"$word\"", PreviewText.quoted(word), word)
        // A second one, one after the long-vowel mark, the middle dot or a full-width letter is no letter's mark.
        assertEquals("\"ｶﾞ⟨U+FF9E⟩\"", PreviewText.quoted("ｶﾞ$voiced"))
        assertEquals("\"ｰ⟨U+FF9E⟩\"", PreviewText.quoted("ｰ$voiced"))
        assertEquals("\"･⟨U+FF9F⟩\"", PreviewText.quoted("･$semiVoiced"))
        assertEquals("\"カ⟨U+FF9E⟩\"", PreviewText.quoted("カ$voiced"))
    }

    @Test
    fun `the verifier's three values cannot pretend to end early`() {
        for (fake in listOf("'$dotBelow'", cp(0x1F677), cp(0xFF9E), "`$diaeresis`", "'${cp(0x0315)}", ".${cp(0x030E)}", cp(0x055A, 0x055A), cp(0x1CD3))) {
            val value = "ls$fake in the top level of the workspace. Read-only listing; nothing else runs. $fake; rm -rf ../databases ; echo $fake"
            val shown = PreviewText.quoted(value)
            assertEquals(2, plainQuotes(shown), shown)
            // Nothing of the fake ending is drawn as it was: every one of its characters that is not plain punctuation alone has its code beside it.
            assertFalse(fake in shown, "$fake is still drawn in: $shown")
            assertFalse(Regex("['`′‵‚՚ߴߵ][\\p{Mn}\\p{Me}]*['`′‵‚՚ߴߵ]").containsMatchIn(shown), shown)
            assertFalse(Regex("\\p{P}[\\p{Mn}\\p{Me}&&[^\\uFE0E\\uFE0F]]").containsMatchIn(shown), shown)
        }
    }

    @Test
    fun `ordinary writing stays as it is`() {
        for (text in listOf(
            // The verifier's samples that stayed readable in round 3.
            "データをコピーして、ノートに書く。スーパーでラーメンを買う",
            "ｶﾞｷﾞｸﾞ ﾊﾟﾋﾟ",
            "Καλημέρα κόσμε, τι κάνεις; Ελληνικά ΑΒΓ άέήίόύώ ϊϋ ΐΰ",
            "Ἐν ἀρχῇ ἦν ὁ λόγος",
            "שלום עולם, מה שלומך היום",
            "שָׁלוֹם",
            "مرحبا بالعالم",
            "مَرْحَبًا بِالْعَالَم، كَيْفَ حَالُكَ؟",
            "Привет, мир",
            "नमस्ते दुनिया, आज सोमवार है।",
            "สวัสดีชาวโลก วันนี้วันจันทร์ ที่นี่ น้ำ",
            "안녕하세요 세계",
            "你好，世界。「引用」",
            "Xin chào thế giới, tiếng Việt",
            "Größe über Äpfel, déjà vu, ¿qué tal?, smörgåsbord",
            "ok 👍 done ✅ 🎉 😀",
            "wave " + cp(0x1F44B, 0x1F3FD),
            "heart " + cp(0x2764, 0xFE0F),
            "flag " + cp(0x1F1EF, 0x1F1F5),
            "press " + cp(0x31, 0xFE0F, 0x20E3),
            // Keycaps on punctuation, and punctuation that is an emoji with its selector.
            "press " + cp(0x23, 0xFE0F, 0x20E3) + " or " + cp(0x2A, 0xFE0F, 0x20E3) + " or " + cp(0x23, 0x20E3),
            "really" + cp(0x203C, 0xFE0F) + " what" + cp(0x2049, 0xFE0F),
            // The same languages written with separate accents (as file names from other systems are).
            java.text.Normalizer.normalize("Xin chào thế giới, tiếng Việt; crème brûlée, Ångström. Ἐν ἀρχῇ (ἦν) ὁ λόγος!", java.text.Normalizer.Form.NFD),
            // One NKo tone apostrophe, one Armenian apostrophe.
            "ߒߞߏ ߞߊ߲ߴ", "Հայերեն մ՚",
        )) {
            assertEquals("\"$text\"", PreviewText.quoted(text), text)
            assertEquals(text, PreviewText.exact(text), text)
        }
        // By themselves the half-width sound marks and the single apostrophes of other scripts are no quote marks.
        for (c in listOf(0xFF9E, 0xFF9F, 0x07F4, 0x07F5, 0x055A, 0x0323, 0x030E)) assertFalse(PreviewText.looksLikeQuote(c), "U+%04X".format(c))
    }

    // ---- J3 ----

    @Test
    fun `a surrogate without its partner is shown by its code, never as it is`() {
        // The verifier's command: Java hands the shell a question mark for it, which the shell reads as a pattern.
        assertEquals("echo keep⟨U+D800⟩", PreviewText.exact("echo keep\uD800"))
        assertEquals("\"echo keep⟨U+D800⟩\"", PreviewText.quoted("echo keep\uD800"))
        // A low one alone, the two in the wrong order, a high one before a letter, at the start, in the middle.
        assertEquals("⟨U+DC00⟩x", PreviewText.exact("\uDC00x"))
        assertEquals("a⟨U+DC00⟩⟨U+D800⟩b", PreviewText.exact("a\uDC00\uD800b"))
        assertEquals("a⟨U+D83D⟩x", PreviewText.exact("a\uD83Dx"))
        assertEquals("rm keep⟨U+DFFF⟩ *", PreviewText.exact("rm keep\uDFFF *"))
        // Many of one are counted like any other marked character.
        assertEquals("a⟨U+D800⟩×5b", PreviewText.exact("a" + "\uD800".repeat(5) + "b"))
        // No unpaired surrogate is left in what is shown, whatever stands around it.
        for (text in listOf("\uD800", "\uDBFF\uDBFF", "😀\uD83D", "\uDE00😀", "x\uD800́y", "\"\uDC00\"", "'\uD800'")) {
            for (shown in listOf(PreviewText.exact(text), PreviewText.quoted(text))) {
                assertTrue(shown.indices.none { i -> shown[i].isSurrogate() && !isPaired(shown, i) }, "$shown has an unpaired surrogate")
            }
        }
        // A pair is one character and stays.
        assertEquals("ok 😀 " + cp(0x1F676 - 1), PreviewText.exact("ok 😀 " + cp(0x1F676 - 1)))
        assertEquals("\"😀\"", PreviewText.quoted("😀"))
    }

    private fun isPaired(text: String, i: Int): Boolean =
        if (text[i].isHighSurrogate()) i + 1 < text.length && text[i + 1].isLowSurrogate() else i > 0 && text[i - 1].isHighSurrogate()
}
