package com.aicontrolcenter.app.ui.tasks.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.lazy.LazyListScope
import com.aicontrolcenter.app.ui.tasks.LocalScreenWork
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.appColors

/**
 * Renders Markdown written by an agent as readable text in the app's type scale. A long text is parsed off the main
 * thread (P7, [LocalScreenWork]); until it is, its beginning is shown as plain text.
 */
@Composable
fun MarkdownText(source: String, modifier: Modifier = Modifier, textStyle: TextStyle = MaterialTheme.typography.bodyLarge) {
    val blocks = rememberMarkdown(source)
    if (blocks == null) {
        Text(source.take(PLACEHOLDER_CHARS), style = textStyle, color = MaterialTheme.colorScheme.onSurface, modifier = modifier)
    } else {
        MarkdownView(blocks, modifier, textStyle)
    }
}

/**
 * [source] parsed: at once when it is short, off the main thread on [LocalScreenWork] when it is long (P7); null until
 * then.
 */
@Composable
fun rememberMarkdown(source: String): List<MarkdownBlock>? {
    if (source.length <= PARSE_AT_ONCE_CHARS) return remember(source) { Markdown.parse(source) }
    val work = LocalScreenWork.current
    val parsed by produceState<List<MarkdownBlock>?>(null, source, work) { value = withContext(work) { Markdown.parse(source) } }
    return parsed
}

/**
 * One lazy item per block of [blocks] (P7): a long document composes and measures only what is on screen. Keys are the
 * block's place and content, so an edit above does not reuse a block below.
 */
fun LazyListScope.markdownItems(blocks: List<MarkdownBlock>, keyPrefix: String, modifier: Modifier = Modifier) {
    items(blocks.size, key = { index -> "$keyPrefix-$index-${blocks[index].hashCode()}" }) { index ->
        val block = blocks[index]
        val gap = if (index == 0) 0.dp else gapBefore(block, blocks[index - 1])
        Box(modifier.padding(top = gap)) { Block(block, MaterialTheme.typography.bodyLarge) }
    }
}

/** Texts up to this long are parsed while composing; longer ones off the main thread. */
const val PARSE_AT_ONCE_CHARS = 4_000

/** How much of a long text shows as plain text while it is parsed. */
private const val PLACEHOLDER_CHARS = 1_500

@Composable
fun MarkdownView(blocks: List<MarkdownBlock>, modifier: Modifier = Modifier, textStyle: TextStyle = MaterialTheme.typography.bodyLarge) {
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            val gap = if (index == 0) 0.dp else gapBefore(block, blocks[index - 1])
            Box(Modifier.padding(top = gap)) { Block(block, textStyle) }
        }
    }
}

private fun gapBefore(block: MarkdownBlock, previous: MarkdownBlock): Dp = when {
    block is MarkdownBlock.Heading -> if (block.level <= 2) 20.dp else 14.dp
    previous is MarkdownBlock.Heading -> 6.dp
    block is MarkdownBlock.ListItem && previous is MarkdownBlock.ListItem -> 4.dp
    else -> 10.dp
}

@Composable
private fun Block(block: MarkdownBlock, textStyle: TextStyle) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    when (block) {
        is MarkdownBlock.Heading -> Text(
            annotated(block.text),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            },
            color = onSurface,
            modifier = Modifier.semantics { heading() },
        )
        is MarkdownBlock.Paragraph -> Text(annotated(block.text), style = textStyle, color = onSurface)
        is MarkdownBlock.ListItem -> Row(Modifier.padding(start = (block.level * 18).dp)) {
            Text(
                block.marker,
                style = textStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = if (block.marker == "•") 16.dp else 24.dp),
            )
            Text(annotated(block.text), style = textStyle, color = onSurface, modifier = Modifier.weight(1f))
        }
        is MarkdownBlock.Code -> Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, appColors.hairline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                block.text,
                style = MonoTextStyle,
                color = onSurface,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
        is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall))
            Text(
                annotated(block.text),
                style = textStyle.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        is MarkdownBlock.Table -> Table(block)
        MarkdownBlock.Rule -> HorizontalDivider(color = appColors.hairline, modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** Up to three columns share the width; wider tables scroll sideways with readable columns. */
@Composable
private fun Table(table: MarkdownBlock.Table) {
    val scrolls = table.header.size > 3
    Surface(
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        border = BorderStroke(1.dp, appColors.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier) {
            TableRow(table.header, header = true, scrolls = scrolls)
            table.rows.forEach { row ->
                HorizontalDivider(color = appColors.hairline)
                TableRow(row, header = false, scrolls = scrolls)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<List<InlineRun>>, header: Boolean, scrolls: Boolean) {
    Row(
        modifier = (if (scrolls) Modifier else Modifier.fillMaxWidth())
            .then(if (header) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        cells.forEach { cell ->
            Text(
                annotated(cell),
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = (if (scrolls) Modifier.width(150.dp) else Modifier.weight(1f)).padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun annotated(runs: List<InlineRun>): AnnotatedString {
    val link = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    return remember(runs, link, codeBackground) {
        buildAnnotatedString {
            runs.forEach { run ->
                val style = SpanStyle(
                    fontWeight = if (run.bold) FontWeight.SemiBold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    background = if (run.code) codeBackground else Color.Unspecified,
                    textDecoration = if (run.strike) TextDecoration.LineThrough else null,
                )
                val url = run.url
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    // Opened only when the owner taps it; nothing in the document is followed on its own.
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                        withStyle(style) { append(run.text) }
                    }
                } else {
                    withStyle(style) { append(run.text) }
                }
            }
        }
    }
}
