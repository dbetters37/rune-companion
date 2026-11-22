package com.aicontrolcenter.app.ui.dashboard.network

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
 * The interactive agent network: the Director in the middle, the other agents around it, and
 * a line for each reporting relationship.
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
                network.edges.forEach { edge ->
                    val from = avatarCentre(edge.fromId) ?: return@forEach
                    val to = avatarCentre(edge.toId) ?: return@forEach
                    val accent = colors.agentAccent(network.node(edge.fromId)?.colorIndex ?: 0)
                    drawEdge(from, to, edge.live, accent, colors.hairlineStrong)
                }
                network.pulses.forEach { pulse ->
                    val from = avatarCentre(pulse.fromId) ?: return@forEach
                    val to = avatarCentre(pulse.toId) ?: return@forEach
                    val progress = pulse.progress.coerceIn(0f, 1f)
                    val at = Offset(from.x + (to.x - from.x) * progress, from.y + (to.y - from.y) * progress)
                    val color = colors.agentAccent(pulse.colorIndex)
                    drawCircle(color.copy(alpha = 0.25f), radius = 7.dp.toPx(), center = at)
                    drawCircle(color, radius = 3.5.dp.toPx(), center = at)
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
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
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
private fun DrawScope.drawEdge(from: Offset, to: Offset, live: Boolean, accent: Color, dormant: Color) {
    val delta = to - from
    val length = delta.getDistance()
    val startInset = NodeAvatarSize.toPx() / 2 + EdgeGap.toPx()
    val endInset = HubAvatarSize.toPx() / 2 + EdgeGap.toPx()
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
