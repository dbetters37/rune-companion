package com.aicontrolcenter.app.ui.memory.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicontrolcenter.app.ui.memory.ScopeKind
import com.aicontrolcenter.app.ui.theme.AppColors
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.memory.LinkRelation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Space kept free around the laid-out graph so edge nodes and their labels are never cut off. */
val GraphCanvasPadding = 28.dp

/** Extra distance around a node that still counts as tapping it. */
private val TapSlop = 12.dp

/** Above this many nodes only the selection, its neighbours and the most important memories are labelled. */
private const val LABEL_ALL_BELOW = 14

/** How the canvas draws one relation. Contradictions are the one style that must never be missed. */
internal data class EdgeStyle(val color: Color, val widthDp: Float, val dash: FloatArray?, val arrow: Boolean, val crossMark: Boolean = false)

internal fun edgeStyle(relation: LinkRelation?, colors: AppColors, scheme: ColorScheme): EdgeStyle = when (relation) {
    LinkRelation.CONTRADICTS -> EdgeStyle(colors.unavailable, 2.5f, floatArrayOf(7f, 5f), arrow = false, crossMark = true)
    LinkRelation.SUPPORTS -> EdgeStyle(colors.ready, 2f, null, arrow = true)
    LinkRelation.SHARED_COPY_OF -> EdgeStyle(scheme.primary, 1.75f, floatArrayOf(1.5f, 4.5f), arrow = true)
    LinkRelation.DERIVED_FROM, LinkRelation.PART_OF -> EdgeStyle(scheme.secondary, 1.75f, null, arrow = true)
    LinkRelation.SUPERSEDES -> EdgeStyle(colors.attention, 1.75f, floatArrayOf(10f, 4f), arrow = true)
    LinkRelation.REFERENCES -> EdgeStyle(scheme.outline, 1.25f, null, arrow = true)
    LinkRelation.RELATES_TO, null -> EdgeStyle(scheme.outline, 1.25f, null, arrow = false)
}

/**
 * The memory graph, drawn entirely on a canvas so hundreds of memories stay smooth: nodes
 * coloured by their agent or space, sized by importance and glowing with recent use, shaped
 * by scope (circle: private, rounded square: shared, ringed circle: task), and edges styled by
 * relation.
 *
 * Pinch zooms around the fingers, a drag pans once zoomed, a tap selects the nearest node or
 * clears the selection. Screen readers get a summary and, as custom actions, the most
 * important memories to select; the Explorer tab is the full list.
 */
@Composable
fun MemoryGraphCanvas(
    render: GraphRender,
    selectedIndex: Int?,
    viewport: GraphViewport,
    onViewportChange: (GraphViewport) -> Unit,
    onSelect: (nodeId: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = appColors
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val currentRender by rememberUpdatedState(render)
    val currentViewport by rememberUpdatedState(viewport)
    val currentOnViewportChange by rememberUpdatedState(onViewportChange)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurface, fontSize = 11.sp)

    val accessibleNodes = render.nodes.withIndex().sortedByDescending { it.value.weight }.take(MAX_ACCESSIBLE_NODES)
    Canvas(
        modifier = modifier
            .testTag(GRAPH_TEST_TAG)
            .semantics {
                contentDescription = "Memory graph: ${render.nodes.size} memories and ${render.edges.size} links. " +
                    "Use the actions to select a memory, or the Explorer tab for the full list."
                customActions = accessibleNodes.map { (_, node) ->
                    CustomAccessibilityAction("Select ${node.label}") {
                        currentOnSelect(node.id)
                        true
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    val padding = GraphCanvasPadding.toPx()
                    val hit = currentRender.hitTest(position, currentViewport, size.width.toFloat(), size.height.toFloat(), padding, TapSlop.toPx()) {
                        it.radiusDp.dp.toPx()
                    }
                    currentOnSelect(hit?.let { currentRender.nodes[it].id })
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    currentOnViewportChange(currentViewport.transformed(zoom, pan, centroid, size.width.toFloat(), size.height.toFloat()))
                }
            },
    ) {
        drawBackdrop(colors, scheme)
        val padding = GraphCanvasPadding.toPx()
        val points = render.nodes.map { viewport.toScreen(it.x, it.y, size.width, size.height, padding) }
        val focus = selectedIndex?.let { setOf(it) + render.neighbours(it) }
        render.edges.forEach { edge ->
            val dimmed = focus != null && !(edge.source == selectedIndex || edge.target == selectedIndex)
            drawEdge(
                from = points[edge.source],
                to = points[edge.target],
                fromRadius = render.nodes[edge.source].radiusDp.dp.toPx(),
                toRadius = render.nodes[edge.target].radiusDp.dp.toPx(),
                style = edgeStyle(edge.relation, colors, scheme),
                weight = edge.weight,
                alpha = if (dimmed) 0.18f else 1f,
            )
        }
        render.nodes.forEachIndexed { index, node ->
            val accent = colors.agentAccent(node.scope.colorIndex)
            val dimmed = focus != null && index !in focus
            drawNode(points[index], node.radiusDp.dp.toPx(), accent, node.shape, node.activation, selected = index == selectedIndex, dimmed = dimmed, scheme = scheme)
        }
        val labelled = render.nodes.indices.filter { index ->
            when {
                focus != null -> index in focus
                render.nodes.size < LABEL_ALL_BELOW || viewport.scale >= 2.5f -> true
                else -> render.nodes[index].weight >= 0.75
            }
        }.take(MAX_LABELS)
        labelled.forEach { index ->
            val node = render.nodes[index]
            drawLabel(measurer, node.label, points[index], node.radiusDp.dp.toPx(), labelStyle, scheme.surface, bold = index == selectedIndex)
        }
    }
}

const val GRAPH_TEST_TAG = "memory-graph"
private const val MAX_ACCESSIBLE_NODES = 40
private const val MAX_LABELS = 60

private fun DrawScope.drawBackdrop(colors: AppColors, scheme: ColorScheme) {
    drawRect(scheme.surfaceContainerLowest)
    val glow = scheme.primary.copy(alpha = if (colors.isDark) 0.10f else 0.06f)
    drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center = center, radius = size.minDimension * 0.75f))
}

private fun DrawScope.drawEdge(from: Offset, to: Offset, fromRadius: Float, toRadius: Float, style: EdgeStyle, weight: Double, alpha: Float) {
    val delta = to - from
    val length = delta.getDistance()
    val gap = 3.dp.toPx()
    if (length <= fromRadius + toRadius + 2 * gap) return
    val unit = delta / length
    val start = from + unit * (fromRadius + gap)
    val end = to - unit * (toRadius + gap)
    val color = style.color.copy(alpha = alpha * (0.45f + 0.55f * weight.coerceIn(0.0, 1.0).toFloat()))
    val width = style.widthDp.dp.toPx()
    drawLine(
        color = color,
        start = start,
        end = end,
        strokeWidth = width,
        cap = StrokeCap.Round,
        pathEffect = style.dash?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * density }) },
    )
    if (style.arrow) {
        val angle = atan2(unit.y, unit.x)
        val head = 7.dp.toPx()
        val spread = 0.45f
        val path = Path().apply {
            moveTo(end.x, end.y)
            lineTo(end.x - head * cos(angle - spread), end.y - head * sin(angle - spread))
            lineTo(end.x - head * cos(angle + spread), end.y - head * sin(angle + spread))
            close()
        }
        drawPath(path, color)
    }
    if (style.crossMark) {
        // A small cross at the middle: a contradiction reads as one even in greyscale.
        val mid = (start + end) / 2f
        val arm = 4.dp.toPx()
        drawLine(color, mid + Offset(-arm, -arm), mid + Offset(arm, arm), strokeWidth = width, cap = StrokeCap.Round)
        drawLine(color, mid + Offset(-arm, arm), mid + Offset(arm, -arm), strokeWidth = width, cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawNode(
    centre: Offset,
    radius: Float,
    accent: Color,
    shape: ScopeKind,
    activation: Double,
    selected: Boolean,
    dimmed: Boolean,
    scheme: ColorScheme,
) {
    val alpha = if (dimmed) 0.3f else 1f
    val glow = activation.coerceIn(0.0, 1.0).toFloat()
    if (glow > 0.05f && !dimmed) {
        val reach = radius * (1.8f + 0.9f * glow)
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.35f * glow), Color.Transparent), center = centre, radius = reach), radius = reach, center = centre)
    }
    val fill = accent.copy(alpha = 0.28f).compositeOver(scheme.surfaceContainerLowest).copy(alpha = alpha)
    val ring = accent.copy(alpha = alpha)
    val ringWidth = 1.5.dp.toPx()
    when (shape) {
        ScopeKind.SHARED -> {
            val side = radius * 1.8f
            val topLeft = centre - Offset(side / 2, side / 2)
            val corner = CornerRadius(radius * 0.45f)
            drawRoundRect(fill, topLeft, Size(side, side), corner)
            drawRoundRect(ring, topLeft, Size(side, side), corner, style = Stroke(ringWidth))
        }
        ScopeKind.PRIVATE -> {
            drawCircle(fill, radius, centre)
            drawCircle(ring, radius, centre, style = Stroke(ringWidth))
        }
        ScopeKind.TASK -> {
            drawCircle(fill, radius, centre)
            drawCircle(ring, radius, centre, style = Stroke(ringWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
        }
    }
    if (selected) {
        drawCircle(scheme.onSurface, radius + 5.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
    }
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, centre: Offset, radius: Float, style: TextStyle, backdrop: Color, bold: Boolean) {
    val maxWidth = 140.dp.roundToPx()
    val layout = measurer.measure(
        text,
        style = if (bold) style.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = maxWidth),
    )
    val padX = 5.dp.toPx()
    val padY = 2.dp.toPx()
    val width = layout.size.width + 2 * padX
    val height = layout.size.height + 2 * padY
    val left = (centre.x - width / 2).coerceIn(0f, (size.width - width).coerceAtLeast(0f))
    val top = (centre.y + radius + 4.dp.toPx()).coerceAtMost(size.height - height)
    drawRoundRect(backdrop.copy(alpha = 0.88f), Offset(left, top), Size(width, height), CornerRadius(height / 2))
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}
