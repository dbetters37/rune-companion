package com.aicontrolcenter.app.ui.dashboard.network

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.theme.AppColors
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.agents.label
import kotlin.math.roundToInt

private val HubAvatarSize = 60.dp
private val NodeAvatarSize = 46.dp
private val NodeWidth = 88.dp
private val LabelGap = 4.dp
private val LabelHeight = 16.dp
private val EdgeGap = 5.dp

/**
 * The interactive agent network: the Director in the middle, the other agents around it, a
 * line for each reporting relationship, and a curve between agents that exchanged messages in
 * a running objective. Messages travel along them as dots, from sender to recipient.
 *
 * Lines, the orbit and pulses are drawn on a canvas. The nodes themselves are real composables
 * laid over it, so each one has a proper touch target, a spoken description and a selected
 * state. Pinch (or drag once zoomed) changes [viewport]; an unzoomed one-finger drag is left
 * alone so the page behind keeps scrolling.
 */
@Composable
fun AgentNetworkView(
    network: AgentNetwork,
    selectedNodeId: String?,
    onSelect: (String?) -> Unit,
    viewport: Viewport,
    onViewportChange: (Viewport) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positions = remember(network.nodes.map { it.id }, network.hub?.id) { NetworkLayout.arrange(network) }
    val colors = appColors
    val backdrop = MaterialTheme.colorScheme.surface
    val glow = MaterialTheme.colorScheme.primary
    val currentViewport by rememberUpdatedState(viewport)
    val currentOnViewportChange by rememberUpdatedState(onViewportChange)
    val currentOnSelect by rememberUpdatedState(onSelect)
    // Pulses keep moving while agents work. (Compose tests stop infinite animations, so pictures show them at rest.)
    val pulsePhase = if (network.pulses.isEmpty()) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "pulses")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing), RepeatMode.Restart),
            label = "pulse phase",
        )
        phase
    }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .testTag("agent-network")
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnSelect(null) }) }
            .pointerInput(Unit) {
                detectNetworkTransform(
                    isZoomed = { currentViewport.isZoomed },
                    onTransform = { zoom, pan ->
                        currentOnViewportChange(
                            currentViewport.transformedBy(zoom, pan.x, pan.y, size.width.toFloat(), size.height.toFloat()),
                        )
                    },
                )
            },
    ) {
        // Radii of the ring in each axis, leaving room for a node and its label at the edge.
        val radiusX = (maxWidth - NodeWidth) / 2
        val radiusY = (maxHeight - NodeAvatarSize - LabelGap - LabelHeight) / 2 - 4.dp
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = viewport.scale
                scaleY = viewport.scale
                translationX = viewport.offsetX
                translationY = viewport.offsetY
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val centre = Offset(size.width / 2, size.height / 2)
                val rx = radiusX.toPx()
                val ry = radiusY.toPx()
                // Node composables are centred on their slot, but the avatar sits above the label.
                val avatarLift = (LabelGap + LabelHeight).toPx() / 2
                fun avatarCentre(id: String): Offset? =
                    positions[id]?.let { Offset(centre.x + it.x * rx, centre.y + it.y * ry - avatarLift) }

                drawBackdrop(centre, rx, ry, avatarLift, glow, colors, hasHub = network.hub != null)
                val hubId = network.hub?.id
                val curvedPairs = network.edges.filter { it.kind == EdgeKind.EXCHANGE }.map { setOf(it.fromId, it.toId) }.toSet()
                network.edges.forEach { edge ->
                    val from = avatarCentre(edge.fromId) ?: return@forEach
                    val to = avatarCentre(edge.toId) ?: return@forEach
                    val accent = colors.agentAccent(network.node(edge.fromId)?.colorIndex ?: 0)
                    if (edge.kind == EdgeKind.EXCHANGE) {
                        drawExchange(from, to, Offset(centre.x, centre.y - avatarLift), accent)
                    } else {
                        drawEdge(from, to, edge.live, accent, colors.hairlineStrong, toHub = edge.toId == hubId)
                    }
                }
                network.pulses.forEach { pulse ->
                    val from = avatarCentre(pulse.fromId) ?: return@forEach
                    val to = avatarCentre(pulse.toId) ?: return@forEach
                    val progress = (pulse.progress + pulsePhase) % 1f
                    // A message between two agents outside the reporting lines follows their curved edge.
                    val at = if (setOf(pulse.fromId, pulse.toId) in curvedPairs) {
                        quadPoint(from, curveControl(from, to, Offset(centre.x, centre.y - avatarLift)), to, progress)
                    } else {
                        Offset(from.x + (to.x - from.x) * progress, from.y + (to.y - from.y) * progress)
                    }
                    val color = colors.agentAccent(pulse.colorIndex)
                    drawCircle(color.copy(alpha = 0.25f), radius = 10.dp.toPx(), center = at)
                    drawCircle(color, radius = 5.dp.toPx(), center = at)
                }
            }
            network.nodes.forEach { node ->
                val position = positions[node.id] ?: return@forEach
                NetworkNodeView(
                    node = node,
                    selected = node.id == selectedNodeId,
                    backdrop = backdrop,
                    onClick = { onSelect(node.id) },
                    modifier = Modifier.align(Alignment.Center).offset {
                        IntOffset((position.x * radiusX.toPx()).roundToInt(), (position.y * radiusY.toPx()).roundToInt())
                    },
                )
            }
        }
    }
}

@Composable
private fun NetworkNodeView(
    node: NetworkNode,
    selected: Boolean,
    backdrop: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val avatarSize: Dp = if (node.isHub) HubAvatarSize else NodeAvatarSize
    val description = buildString {
        append(node.name).append(", ").append(node.role.label)
        if (node.isHub) append(", designated Director")
        append(", ").append(node.status.text)
        if (node.working) node.status.detail?.let { append(": ").append(it) }
    }
    Column(
        modifier = modifier
            .width(NodeWidth)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Select",
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LabelGap),
    ) {
        Box(
            modifier = Modifier
                .then(
                    when {
                        selected -> Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        node.working -> Modifier.border(2.dp, appColors.working, CircleShape)
                        else -> Modifier
                    },
                )
                .padding(4.dp),
        ) {
            AgentAvatar(
                name = node.name,
                colorIndex = node.colorIndex,
                size = avatarSize - 8.dp,
                status = node.status.tone,
                dimmed = node.status.tone == StatusTone.DISABLED,
                backdrop = backdrop,
            )
        }
        Text(
            text = node.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // On the card's colour, so a line to the hub passes behind the name instead of through it.
            modifier = Modifier.height(LabelHeight).background(backdrop, CircleShape).padding(horizontal = 6.dp),
        )
    }
}

private fun DrawScope.drawBackdrop(
    centre: Offset,
    rx: Float,
    ry: Float,
    avatarLift: Float,
    glow: Color,
    colors: AppColors,
    hasHub: Boolean,
) {
    val orbitCentre = Offset(centre.x, centre.y - avatarLift)
    if (hasHub) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = if (colors.isDark) 0.16f else 0.10f), Color.Transparent),
                center = orbitCentre,
                radius = minOf(rx, ry) * 0.9f,
            ),
            radius = minOf(rx, ry) * 0.9f,
            center = orbitCentre,
        )
    }
    drawOval(
        color = colors.hairline,
        topLeft = Offset(orbitCentre.x - rx, orbitCentre.y - ry),
        size = androidx.compose.ui.geometry.Size(rx * 2, ry * 2),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** A line between two avatars, stopped short of each so it never runs under a monogram. */
private fun DrawScope.drawEdge(from: Offset, to: Offset, live: Boolean, accent: Color, dormant: Color, toHub: Boolean) {
    val delta = to - from
    val length = delta.getDistance()
    val startInset = NodeAvatarSize.toPx() / 2 + EdgeGap.toPx()
    val endInset = (if (toHub) HubAvatarSize else NodeAvatarSize).toPx() / 2 + EdgeGap.toPx()
    if (length <= startInset + endInset) return
    val unit = delta / length
    drawLine(
        color = if (live) accent.copy(alpha = 0.85f) else dormant,
        start = from + unit * startInset,
        end = to - unit * endInset,
        strokeWidth = (if (live) 2.dp else 1.5.dp).toPx(),
        cap = StrokeCap.Round,
        pathEffect = if (live) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())),
    )
}

/**
 * Two agents that exchanged messages: a curve bowed away from the centre, so it never passes
 * under the Director, trimmed short of both avatars.
 */
private fun DrawScope.drawExchange(from: Offset, to: Offset, centre: Offset, accent: Color) {
    val control = curveControl(from, to, centre)
    val approxLength = (control - from).getDistance() + (to - control).getDistance()
    val inset = NodeAvatarSize.toPx() / 2 + EdgeGap.toPx()
    if (approxLength <= inset * 2) return
    val start = inset / approxLength
    val path = Path()
    val steps = 24
    for (i in 0..steps) {
        val t = start + (1 - 2 * start) * i / steps
        val point = quadPoint(from, control, to, t)
        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    drawPath(path, accent.copy(alpha = 0.7f), style = Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round))
}

/** The control point of an exchange curve: the midpoint pushed away from the centre (sideways for opposite agents). */
private fun curveControl(from: Offset, to: Offset, centre: Offset): Offset {
    val mid = (from + to) / 2f
    var away = mid - centre
    if (away.getDistance() < 1f) away = Offset(-(to - from).y, (to - from).x)
    val length = away.getDistance().takeIf { it > 0f } ?: return mid
    return mid + away / length * ((to - from).getDistance() * 0.3f)
}

private fun quadPoint(a: Offset, control: Offset, b: Offset, t: Float): Offset {
    val u = 1 - t
    return a * (u * u) + control * (2 * u * t) + b * (t * t)
}
