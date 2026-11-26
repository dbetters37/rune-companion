package com.aicontrolcenter.app.ui.team

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.CenterFocus
import com.aicontrolcenter.app.ui.icons.MemoryBrain
import com.aicontrolcenter.app.ui.icons.ZoomIn
import com.aicontrolcenter.app.ui.icons.ZoomOut
import com.aicontrolcenter.app.ui.theme.LocalCalmMotion
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.RequestId
import kotlin.math.roundToInt

/** The tag of the picture, for tests. */
const val TEAM_PICTURE_TAG = "team-picture"

/** Links whose sign (a 48 dp button with the kind's glyph) is shown at their middle: the newest ones. */
private const val SIGNED_LINKS = 12

/** What the picture can open. */
@Immutable
class PictureActions(
    val onOpenNode: (String) -> Unit,
    val onOpenLink: (String) -> Unit,
    /** A request or question that waits for the owner, opened on its own screen (a question: `QuestionRoute`, rule 13). */
    val onOpenRequest: (RequestId, Boolean) -> Unit,
)

/** Who is placed where: only ids, so the layout is worked out again only when someone joins or leaves the picture. */
@Immutable
private data class LayoutIds(val hasOwner: Boolean, val director: String?, val ring: List<String>, val spaces: List<String>)

private val ViewportSaver = Saver<TeamViewport, List<Float>>(
    save = { listOf(it.scale, it.x, it.y) },
    restore = { TeamViewport(it[0], it[1], it[2]) },
)

/**
 * The Team view's picture (goal 9): "You" at the top, the Director in the centre, agents on a ring, spaces along the
 * bottom; one line per pair that exchanged messages, styled by what they exchanged, with the kind's glyph at the middle
 * of the newest [SIGNED_LINKS] lines; a dot travels once along a line when a message is sent (never under Calm).
 *
 * Nodes and link signs are real composables with 48 dp targets, spoken descriptions, custom actions and a reading order
 * (You, the Director, the ring clockwise from the top, the spaces, then the lines: the container is a traversal group).
 * Lines and dots are drawn on one canvas whose paths are built once per layout ([drawWithCache]); the frame time is
 * read only in its draw lambda.
 */
@Composable
internal fun TeamPicture(
    graph: TeamGraph,
    resetKey: Any?,
    actions: PictureActions,
    modifier: Modifier = Modifier,
) {
    val calm = LocalCalmMotion.current
    val clock = rememberPulseClock(graph.pulses, calm, resetKey)
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .clipToBounds()
            .testTag(TEAM_PICTURE_TAG)
            .semantics {
                isTraversalGroup = true
                dotsInFlight = clock.inFlight
            },
    ) {
        val viewWidth = maxWidth.value
        val viewHeight = maxHeight.value
        val ids = LayoutIds(graph.owner != null, graph.director?.id, graph.ring.map { it.id }, graph.spaces.map { it.id })
        val placement = remember(ids, viewWidth, viewHeight) {
            TeamLayout.arrange(ids.hasOwner, ids.director, ids.ring, ids.spaces, viewWidth, viewHeight)
        }
        val contentW = with(density) { placement.width.dp.toPx() }
        val contentH = with(density) { placement.height.dp.toPx() }
        val viewW = with(density) { maxWidth.toPx() }
        val viewH = with(density) { maxHeight.toPx() }
        var viewport by rememberSaveable(resetKey, stateSaver = ViewportSaver) { mutableStateOf(TeamViewport()) }
        val shown = viewport.clamped(contentW, contentH, viewW, viewH)
        val current by rememberUpdatedState(shown)
        val bounds by rememberUpdatedState(listOf(contentW, contentH, viewW, viewH))
        val kinds = remember(graph) { graph.nodes.associate { it.id to it.kind } }
        val lines = remember(placement, graph.links) {
            graph.links.mapNotNull { link -> LinkLine.of(placement, link) { kinds[it] }?.let { link to it } }
        }
        val linesByKey = remember(lines) { lines.associate { (link, line) -> link.key to (link to line) } }
        val order = remember(graph) { traversalOrder(graph) }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val (cw, ch, vw, vh) = bounds
                        viewport = current.transformed(zoom, pan.x, pan.y, centroid.x, centroid.y, cw, ch, vw, vh)
                    }
                }
                .wrapContentSize(Alignment.TopStart, unbounded = true),
        ) {
            Box(
                Modifier
                    .requiredSize(placement.width.dp, placement.height.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = shown.scale
                        scaleY = shown.scale
                        translationX = shown.x
                        translationY = shown.y
                    },
            ) {
                LinesAndDots(lines, linesByKey, clock, calm)
                graph.nodes.forEach { node ->
                    val at = placement.positions[node.id] ?: return@forEach
                    NodeView(
                        node = node,
                        graph = graph,
                        size = placement.sizeOf(node.kind).dp,
                        showLabel = placement.showLabels,
                        traversal = order[node.id] ?: 0f,
                        actions = actions,
                        modifier = Modifier.offset {
                            val half = (if (placement.showLabels) TeamLayout.LABEL_WIDTH else TeamLayout.TOUCH) / 2
                            IntOffset(
                                ((at.x - half).dp.toPx()).roundToInt(),
                                ((at.y - maxOf(TeamLayout.TOUCH, placement.sizeOf(node.kind)) / 2).dp.toPx()).roundToInt(),
                            )
                        },
                    )
                }
                lines.sortedByDescending { it.first.lastAt }.take(SIGNED_LINKS).forEachIndexed { index, (link, line) ->
                    LinkSign(
                        link = link,
                        description = TeamDescriptions.linkTitle(link, graph),
                        traversal = 1_000f + index,
                        onOpen = { actions.onOpenLink(link.key) },
                        modifier = Modifier.offset {
                            IntOffset(((line.mid.x - TeamLayout.TOUCH / 2).dp.toPx()).roundToInt(), ((line.mid.y - TeamLayout.TOUCH / 2).dp.toPx()).roundToInt())
                        },
                    )
                }
            }
        }
        ZoomButtons(
            viewport = shown,
            onChange = { viewport = it },
            zoom = { factor -> shown.zoomedBy(factor, contentW, contentH, viewW, viewH) },
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        )
    }
}

/** Reading order for TalkBack: You, the Director, the ring clockwise from the top, then the spaces. */
private fun traversalOrder(graph: TeamGraph): Map<String, Float> {
    val ordered = listOfNotNull(graph.owner, graph.director) + graph.ring + graph.spaces
    return ordered.mapIndexed { index, node -> node.id to index.toFloat() }.toMap()
}

@Composable
private fun ZoomButtons(viewport: TeamViewport, onChange: (TeamViewport) -> Unit, zoom: (Float) -> TeamViewport, modifier: Modifier = Modifier) {
    Row(modifier.semantics { traversalIndex = 2_000f }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onClick = { onChange(zoom(1f / TeamViewport.ZOOM_STEP)) }, enabled = viewport.isZoomed) {
            Icon(AppIcons.ZoomOut, contentDescription = "Zoom out")
        }
        FilledTonalIconButton(onClick = { onChange(zoom(TeamViewport.ZOOM_STEP)) }, enabled = viewport.canZoomIn) {
            Icon(AppIcons.ZoomIn, contentDescription = "Zoom in")
        }
        FilledTonalIconButton(onClick = { onChange(TeamViewport()) }, enabled = viewport.isZoomed || viewport.y != 0f) {
            Icon(AppIcons.CenterFocus, contentDescription = "Show the whole picture")
        }
    }
}

/** Every line, built once per layout, and the dots in the air, drawn at the frame time (read only here). */
@Composable
private fun LinesAndDots(
    lines: List<Pair<TeamLink, LinkLine>>,
    linesByKey: Map<String, Pair<TeamLink, LinkLine>>,
    clock: PulseClock,
    calm: Boolean,
) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val attention = appColors.attention
    val backdrop = MaterialTheme.colorScheme.background
    val dot = MaterialTheme.colorScheme.primary
    Spacer(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val dp = density
                fun px(p: DpPoint) = Offset(p.x * dp, p.y * dp)
                val drawn = lines.map { (link, line) ->
                    val path = Path().apply {
                        moveTo(line.start.x * dp, line.start.y * dp)
                        val c = line.control
                        if (c == null) lineTo(line.end.x * dp, line.end.y * dp) else quadraticTo(c.x * dp, c.y * dp, line.end.x * dp, line.end.y * dp)
                    }
                    Triple(link, line, path)
                }
                onDrawBehind {
                    drawn.forEach { (link, line, path) ->
                        val color = (if (link.kind == LinkKind.ASKS_YOU) attention else ink).copy(alpha = if (link.recent) 0.95f else 0.6f)
                        drawLink(path, link, color, backdrop)
                        val arrow = color.copy(alpha = 0.95f)
                        if (link.aToB) drawArrow(px(line.end), line.direction(1f), arrow)
                        if (link.bToA) drawArrow(px(line.start), line.direction(0f).let { DpPoint(-it.x, -it.y) }, arrow)
                    }
                    if (!calm) {
                        val at = clock.frameMs.longValue
                        clock.positions(at).forEach { (flight, t) ->
                            val (link, line) = linesByKey[flight.pulse.linkKey] ?: return@forEach
                            val along = if (flight.pulse.fromId == link.aId) t else 1f - t
                            val point = px(line.at(along))
                            drawCircle(dot.copy(alpha = 0.25f), radius = 10.dp.toPx(), center = point)
                            drawCircle(dot, radius = 5.dp.toPx(), center = point)
                        }
                    }
                }
            },
    )
}

/** One line in its kind's style; a recent one (the last minute) is drawn thicker. */
private fun DrawScope.drawLink(path: Path, link: TeamLink, color: Color, backdrop: Color) {
    val weight = if (link.recent) 1.7f else 1f
    val px = 1.dp.toPx()
    when (link.kind.style) {
        LineStyle.SOLID -> drawPath(path, color, style = Stroke(2f * px * weight, cap = StrokeCap.Round))
        LineStyle.THIN -> drawPath(path, color, style = Stroke(1.25f * px * weight, cap = StrokeCap.Round))
        LineStyle.DASHED -> drawPath(path, color, style = Stroke(2f * px * weight, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8 * px, 6 * px))))
        LineStyle.DOTTED -> drawPath(
            path, color,
            style = Stroke(3f * px * weight, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f * px, 6 * px))),
        )
        LineStyle.DOUBLE -> {
            drawPath(path, color, style = Stroke(5.5f * px * weight, cap = StrokeCap.Butt))
            drawPath(path, backdrop, style = Stroke(2f * px * weight, cap = StrokeCap.Butt))
        }
        LineStyle.DASH_DOT -> drawPath(
            path, color,
            style = Stroke(2f * px * weight, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10 * px, 5 * px, 1 * px, 5 * px))),
        )
        LineStyle.LONG_DASH -> drawPath(path, color, style = Stroke(2f * px * weight, pathEffect = PathEffect.dashPathEffect(floatArrayOf(16 * px, 6 * px))))
    }
}

/** An arrowhead at [tip], pointing along [direction]. */
private fun DrawScope.drawArrow(tip: Offset, direction: DpPoint, color: Color) {
    val length = 8.dp.toPx()
    val half = 4.5.dp.toPx()
    val back = Offset(tip.x - direction.x * length, tip.y - direction.y * length)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x - direction.y * half, back.y + direction.x * half)
        lineTo(back.x + direction.y * half, back.y - direction.x * half)
        close()
    }
    drawPath(path, color)
}

/** A member of the picture: a 48 dp target with its avatar (or "You", or a space), its state's glyph and its name. */
@Composable
private fun NodeView(
    node: TeamNode,
    graph: TeamGraph,
    size: Dp,
    showLabel: Boolean,
    traversal: Float,
    actions: PictureActions,
    modifier: Modifier = Modifier,
) {
    val description = remember(node, graph) { TeamDescriptions.node(node, graph) }
    val custom = remember(node, graph, actions) { customActions(node, graph, actions) }
    val target = maxOf(TeamLayout.TOUCH.dp, size)
    Column(
        modifier = modifier
            .width(if (showLabel) TeamLayout.LABEL_WIDTH.dp else target)
            .clickable(onClickLabel = "Show details", role = Role.Button) { actions.onOpenNode(node.id) }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = traversal
                customActions = custom
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(target), contentAlignment = Alignment.Center) {
            NodeFace(node, size)
        }
        if (showLabel) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (node.state == NodeState.WAITING_FOR_YOU) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .height(TeamLayout.LABEL_HEIGHT.dp)
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f), CircleShape)
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

/** The face of a member: the agent's avatar with its state's glyph, a ring when it works or waits for you. */
@Composable
private fun NodeFace(node: TeamNode, size: Dp) {
    val colors = appColors
    val ring = when (node.state) {
        NodeState.WAITING_FOR_YOU -> Modifier.border(2.5.dp, colors.attention, CircleShape)
        NodeState.WORKING -> Modifier.border(2.dp, colors.working, CircleShape)
        else -> Modifier
    }
    when (node.kind) {
        TeamNodeKind.OWNER -> Box(
            Modifier
                .size(size)
                .then(ring)
                .padding(3.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size * 0.55f))
            if (node.requestIds.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .background(colors.attention, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        node.requestIds.size.coerceAtMost(9).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.surface,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        TeamNodeKind.SPACE -> Box(
            Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                .border(1.dp, colors.hairlineStrong, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.MemoryBrain, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.55f))
        }
        else -> Box(Modifier.size(size).then(ring).padding(3.dp)) {
            AgentAvatar(
                name = node.name,
                colorIndex = node.colorIndex,
                size = size - 6.dp,
                status = node.tone,
                dimmed = node.dimmed,
                backdrop = MaterialTheme.colorScheme.background,
            )
        }
    }
}

/** TalkBack's actions on a member: open what waits for you, open the messages with each one it dealt with. */
private fun customActions(node: TeamNode, graph: TeamGraph, actions: PictureActions): List<CustomAccessibilityAction> = buildList {
    node.requestIds.firstOrNull()?.let { id ->
        val question = id in node.questionIds
        add(CustomAccessibilityAction(if (question) "Answer the question" else "Open the request") { actions.onOpenRequest(id, question); true })
    }
    TeamDescriptions.linkRows(node.id, graph).take(6).forEach { row ->
        add(CustomAccessibilityAction("Show messages with ${row.otherName}") { actions.onOpenLink(row.key); true })
    }
}

/** The sign at the middle of a line: its kind's 20 dp glyph in a 48 dp button that opens the line's messages. */
@Composable
private fun LinkSign(link: TeamLink, description: String, traversal: Float, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val tint = if (link.kind == LinkKind.ASKS_YOU) appColors.attention else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .size(TeamLayout.TOUCH.dp)
            .clickable(onClickLabel = "Show the messages", role = Role.Button, onClick = onOpen)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                traversalIndex = traversal
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(1.dp, tint.copy(alpha = 0.7f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(link.kind.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/** A state's glyph and word, for the legend and the list (never a colour alone). */
@Composable
internal fun StateGlyph(tone: StatusTone, modifier: Modifier = Modifier) {
    Icon(tone.icon, contentDescription = null, tint = appColors.status(tone), modifier = modifier.size(16.dp))
}
