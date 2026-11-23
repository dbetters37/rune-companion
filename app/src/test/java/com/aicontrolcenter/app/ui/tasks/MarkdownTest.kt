package com.aicontrolcenter.app.ui.tasks

import com.aicontrolcenter.app.ui.tasks.markdown.InlineRun
import com.aicontrolcenter.app.ui.tasks.markdown.Markdown
import com.aicontrolcenter.app.ui.tasks.markdown.MarkdownBlock
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MarkdownTest {
    private fun plain(text: String) = listOf(InlineRun(text))

    @Test
    fun `headings, paragraphs, lists, quotes, rules and code become blocks`() {
        val blocks = Markdown.parse(
            """
            # Offline note apps
            Three apps work **fully offline**
            and sync when asked.

            ## Shortlist
            - Obsidian
              - plugins for sync
            * Joplin
            1. Install
            2) Import notes
            > Simulated document.

            ---
            ```kotlin
            val x = 1
            ```
            """.trimIndent(),
        )
        assertEquals(MarkdownBlock.Heading(1, plain("Offline note apps")), blocks[0])
        assertEquals(
            MarkdownBlock.Paragraph(listOf(InlineRun("Three apps work "), InlineRun("fully offline", bold = true), InlineRun(" and sync when asked."))),
            blocks[1],
        )
        assertEquals(MarkdownBlock.Heading(2, plain("Shortlist")), blocks[2])
        assertEquals(MarkdownBlock.ListItem(0, "•", plain("Obsidian")), blocks[3])
        assertEquals(MarkdownBlock.ListItem(1, "•", plain("plugins for sync")), blocks[4])
        assertEquals(MarkdownBlock.ListItem(0, "•", plain("Joplin")), blocks[5])
        assertEquals(MarkdownBlock.ListItem(0, "1.", plain("Install")), blocks[6])
        assertEquals(MarkdownBlock.ListItem(0, "2.", plain("Import notes")), blocks[7])
        assertEquals(MarkdownBlock.Quote(plain("Simulated document.")), blocks[8])
        assertEquals(MarkdownBlock.Rule, blocks[9])
        assertEquals(MarkdownBlock.Code("val x = 1", "kotlin"), blocks[10])
        assertEquals(11, blocks.size)
    }

    @Test
    fun `inline styles nest, and links keep their address`() {
        assertEquals(
            listOf(
                InlineRun("Use "), InlineRun("sync", code = true), InlineRun(" with "), InlineRun("care", italic = true),
                InlineRun(", see "), InlineRun("the docs", url = "https://example.org/docs"), InlineRun(" or "),
                InlineRun("both ", bold = true), InlineRun("styles", bold = true, italic = true), InlineRun("."),
            ),
            Markdown.inline("Use `sync` with *care*, see [the docs](https://example.org/docs) or **both _styles_**."),
        )
    }

    @Test
    fun `what is not markup stays as written`() {
        assertEquals(plain("snake_case_name and 2 * 3 * 4"), Markdown.inline("snake_case_name and 2 * 3 * 4"))
        assertEquals(plain("an **unclosed marker"), Markdown.inline("an **unclosed marker"))
        assertEquals(plain("a literal *star*"), Markdown.inline("a literal \\*star\\*"))
        assertEquals(plain("[not a link] (here)"), Markdown.inline("[not a link] (here)"))
    }

    @Test
    fun `a table keeps its header and pads short rows`() {
        val table = assertIs<MarkdownBlock.Table>(
            Markdown.parse(
                """
                | App | Offline | Price |
                |-----|:-------:|------:|
                | Obsidian | Yes | Free |
                | Joplin | Yes |
                """.trimIndent(),
            ).single(),
        )
        assertEquals(listOf("App", "Offline", "Price"), table.header.map(Markdown::plainText))
        assertEquals(listOf(listOf("Obsidian", "Yes", "Free"), listOf("Joplin", "Yes", "")), table.rows.map { row -> row.map(Markdown::plainText) })
    }

    @Test
    fun `an unclosed code fence runs to the end, and empty input has no blocks`() {
        assertEquals(listOf(MarkdownBlock.Code("line 1\nline 2", null)), Markdown.parse("```\nline 1\nline 2"))
        assertEquals(emptyList(), Markdown.parse("\n  \n"))
    }
}
