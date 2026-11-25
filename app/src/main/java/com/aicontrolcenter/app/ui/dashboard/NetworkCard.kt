package com.aicontrolcenter.app.ui.dashboard

import com.aicontrolcenter.app.Phase6Stub
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.dashboard.network.AgentNetwork
import com.aicontrolcenter.app.ui.dashboard.network.AgentNetworkView
import com.aicontrolcenter.app.ui.dashboard.network.NetworkNode
import com.aicontrolcenter.app.ui.dashboard.network.Viewport
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.CenterFocus
import com.aicontrolcenter.app.ui.icons.Hub
import com.aicontrolcenter.app.ui.icons.ZoomIn
import com.aicontrolcenter.app.ui.icons.ZoomOut
import com.aicontrolcenter.app.ui.theme.OverlineTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.agents.label

private val ViewportSaver = Saver<Viewport, List<Float>>(
    save = { listOf(it.scale, it.offsetX, it.offsetY) },
    restore = { Viewport(it[0], it[1], it[2]) },
)

/**
 * The agent network with its controls: zoom buttons (the accessible alternative to pinching),
 * a legend that explains every glyph and line style, and a card for the selected agent.
 */
@Phase6Stub("WP9")
@Composable
internal fun NetworkCard(
    network: AgentNetwork,
    selectedNode: NetworkNode?,
    onSelectNode: (String?) -> Unit,
    onOpenAgent: (AgentId) -> Unit,
    onNewAgent: () -> Unit,
    modifier: Modifier = Modifier,
    /** Wave 3 (WP9): the card's way into the Team view. */
    @Suppress("UNUSED_PARAMETER") onOpenTeam: () -> Unit = {},
) {
    var viewport by rememberSaveable(stateSaver = ViewportSaver) { mutableStateOf(Viewport()) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    AppCard(modifier = modifier, contentPadding = PaddingValues(0.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "AGENT NETWORK",
                style = OverlineTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (network.nodes.isNotEmpty()) {
                IconButton(
                    onClick = { viewport = viewport.zoomedOut(viewSize.width.toFloat(), viewSize.height.toFloat()) },
                    enabled = viewport.canZoomOut,
                ) { Icon(AppIcons.ZoomOut, contentDescription = "Zoom out") }
                IconButton(
                    onClick = { viewport = viewport.zoomedIn(viewSize.width.toFloat(), viewSize.height.toFloat()) },
                    enabled = viewport.canZoomIn,
                ) { Icon(AppIcons.ZoomIn, contentDescription = "Zoom in") }
                IconButton(onClick = { viewport = Viewport() }, enabled = viewport != Viewport()) {
                    Icon(AppIcons.CenterFocus, contentDescription = "Reset view")
                }
            }
        }
        if (network.nodes.isEmpty()) {
            EmptyState(
                icon = AppIcons.Hub,
                message = "There are no agents to show yet.",
                actionLabel = "New agent",
                onAction = onNewAgent,
            )
        } else {
            AgentNetworkView(
                network = network,
                selectedNodeId = selectedNode?.id,
                onSelect = onSelectNode,
                viewport = viewport,
                onViewportChange = { viewport = it },
                modifier = Modifier.fillMaxWidth().height(320.dp).onSizeChanged { viewSize = it },
            )
            NetworkLegend(hasHub = network.hub != null, Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md))
            RowDivider()
            SelectedNodePanel(selectedNode, onOpenAgent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkLegend(hasHub: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            LegendItem(StatusTone.WORKING, "Working")
            LegendItem(StatusTone.READY, "Ready")
            LegendItem(StatusTone.INFO, "No model yet")
            LegendItem(StatusTone.UNAVAILABLE, "Unavailable")
            LegendItem(StatusTone.DISABLED, "Disabled")
        }
        Text(
            text = if (hasHub) {
                "Lines show who reports to the Director: solid when both can run, dashed when one cannot. A curve joins " +
                    "two agents that exchanged messages in a running objective, and dots are messages on their way. " +
                    "A ringed agent is working. Pinch to zoom."
            } else {
                "No Director is designated, so there is no reporting structure to draw. Curves join agents that exchanged messages."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendItem(tone: StatusTone, label: String) {
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Legend: $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(tone.icon, contentDescription = null, tint = appColors.status(tone), modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SelectedNodePanel(node: NetworkNode?, onOpenAgent: (AgentId) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (node == null) {
            Text(
                text = "Tap an agent to see its role and status.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AgentAvatar(node.name, node.colorIndex, size = 44.dp, dimmed = node.status.tone == StatusTone.DISABLED)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (node.isHub) "${node.role.label} · designated" else node.role.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusChip(node.status.text, node.status.tone)
                if (node.working) {
                    node.status.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = appColors.working, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            FilledTonalButton(onClick = { onOpenAgent(AgentId(node.id)) }) { Text("Open") }
        }
    }
}
