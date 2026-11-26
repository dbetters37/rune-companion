package com.aicontrolcenter.app.ui.memory.graph

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.CenterFocus
import com.aicontrolcenter.app.ui.icons.Hub
import com.aicontrolcenter.app.ui.icons.ZoomIn
import com.aicontrolcenter.app.ui.icons.ZoomOut
import com.aicontrolcenter.app.ui.memory.ChipRow
import com.aicontrolcenter.app.ui.memory.KindChip
import com.aicontrolcenter.app.ui.memory.ScopeChip
import com.aicontrolcenter.app.ui.memory.ScopeFilter
import com.aicontrolcenter.app.ui.memory.ScopeFilterChip
import com.aicontrolcenter.app.ui.memory.ScopeKind
import com.aicontrolcenter.app.ui.memory.icon
import com.aicontrolcenter.app.ui.memory.label
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.LinkRelation

/** What the graph tab can ask for. */
class GraphActions(
    val onToggleScope: (ScopeFilter) -> Unit,
    val onShowEverything: () -> Unit,
    val onIncludeTaskMemoryChange: (Boolean) -> Unit,
    val onSelect: (nodeId: String?) -> Unit,
    val onOpenMemory: (MemoryId) -> Unit,
    val onExport: () -> Unit,
)

/** Filters, the interactive graph with zoom controls and the tapped memory, a legend, and the export. */
@Composable
internal fun MemoryGraphTab(state: GraphUiState, actions: GraphActions) {
    var viewport by remember { mutableStateOf(GraphViewport()) }
    Column(Modifier.fillMaxSize()) {
        GraphFilterRow(state, actions)
        Text(
            summaryLine(state),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.screen, vertical = Spacing.xs),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = Spacing.screen)
                .clip(MaterialTheme.shapes.large)
                .border(BorderStroke(1.dp, appColors.hairline), MaterialTheme.shapes.large),
        ) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.failed -> EmptyState(icon = AppIcons.Hub, message = "The graph could not be drawn. Try again in a moment; the Explorer tab still lists every memory.")
                state.render.nodes.isEmpty() -> EmptyState(
                    icon = AppIcons.Hub,
                    title = "Nothing to draw",
                    message = if (state.filters.showsEverything) {
                        "There are no memories yet. As agents save memories and you link them, they appear here as a network."
                    } else {
                        "The chosen agents and spaces have no memories. Choose others, or show everything."
                    },
                )
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val widthPx = with(density) { maxWidth.toPx() }
                    val heightPx = with(density) { maxHeight.toPx() }
                    val paddingPx = with(density) { GraphCanvasPadding.toPx() }
                    // The buttons zoom around the selected memory when there is one, so it stays in view.
                    val zoomCentre = {
                        state.selected?.let { selected ->
                            val node = state.render.nodes[selected.index]
                            viewport.toScreen(node.x, node.y, widthPx, heightPx, paddingPx)
                        } ?: Offset(widthPx / 2, heightPx / 2)
                    }
                    MemoryGraphCanvas(
                        render = state.render,
                        selectedIndex = state.selected?.index,
                        viewport = viewport,
                        onViewportChange = { viewport = it },
                        onSelect = actions.onSelect,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ZoomControls(
                        viewport = viewport,
                        onZoomIn = { viewport = viewport.transformed(GraphViewport.ZOOM_STEP, Offset.Zero, zoomCentre(), widthPx, heightPx) },
                        onZoomOut = { viewport = viewport.transformed(1 / GraphViewport.ZOOM_STEP, Offset.Zero, zoomCentre(), widthPx, heightPx) },
                        onReset = { viewport = GraphViewport() },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.sm),
                    )
                }
            }
        }
        // Under the graph rather than over it, so the card never hides the memory it describes.
        val selected = state.selected
        if (selected != null) {
            SelectedNodeCard(
                selected = selected,
                onOpen = { actions.onOpenMemory(selected.memoryId) },
                onClose = { actions.onSelect(null) },
                modifier = Modifier.padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.sm),
            )
        }
        Legend(state.render.relations)
        if (selected == null) ExportRow(state, actions.onExport)
    }
}

@Composable
private fun GraphFilterRow(state: GraphUiState, actions: GraphActions) {
    val filters = state.filters
    ChipRow(label = "Show", modifier = Modifier.padding(top = Spacing.sm)) {
        FilterChip(selected = filters.showsEverything, onClick = actions.onShowEverything, label = { Text("Everything") })
        state.agentOptions.forEach { option ->
            val id = (option.filter as ScopeFilter.Agent).id
            ScopeFilterChip(option, id in filters.agents, actions.onToggleScope)
        }
        state.spaceOptions.forEach { option ->
            val id = (option.filter as ScopeFilter.Space).id
            ScopeFilterChip(option, id in filters.spaces, actions.onToggleScope)
        }
        FilterChip(
            selected = filters.includeTaskMemory,
            onClick = { actions.onIncludeTaskMemoryChange(!filters.includeTaskMemory) },
            label = { Text("Task memory") },
            leadingIcon = { Icon(ScopeKind.TASK.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        )
    }
}

private fun summaryLine(state: GraphUiState): String {
    if (state.isLoading) return "Laying out the graph…"
    val nodes = state.render.nodes.size
    val edges = state.render.edges.size
    val base = "${plural(nodes, "memory", "memories")} · ${plural(edges, "link")}"
    return when {
        state.truncated -> "$base · the most important and recent are drawn"
        nodes > 0 -> "$base · pinch to zoom, tap a memory"
        else -> base
    }
}

@Composable
private fun ZoomControls(viewport: GraphViewport, onZoomIn: () -> Unit, onZoomOut: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        // Anchored at the bottom: the reset button appears above the others without moving them.
        if (viewport.isZoomed) {
            FilledTonalIconButton(onClick = onReset) { Icon(AppIcons.CenterFocus, contentDescription = "Show the whole graph") }
        }
        FilledTonalIconButton(onClick = onZoomIn, enabled = viewport.canZoomIn) { Icon(AppIcons.ZoomIn, contentDescription = "Zoom in") }
        FilledTonalIconButton(onClick = onZoomOut, enabled = viewport.isZoomed) { Icon(AppIcons.ZoomOut, contentDescription = "Zoom out") }
    }
}

@Composable
private fun SelectedNodeCard(selected: SelectedNodeUi, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, appColors.hairlineStrong),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) { Icon(AppIcons.Close, contentDescription = "Clear selection") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScopeChip(selected.scope)
                    selected.kind?.let { KindChip(it) }
                }
                Button(onClick = onOpen, modifier = Modifier.heightIn(min = 40.dp)) { Text("Open") }
            }
            Text(
                if (selected.linkCount == 0) "Not linked to other memories in this view." else "Linked to ${plural(selected.linkCount, "memory", "memories")} in this view.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** What the shapes and the line styles mean. Only relations that are drawn are listed. */
@Composable
private fun Legend(relations: List<LinkRelation>) {
    val colors = appColors
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.screen, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShapeKey(ScopeKind.PRIVATE, "Private")
        ShapeKey(ScopeKind.SHARED, "Shared")
        ShapeKey(ScopeKind.TASK, "Task")
        relations.forEach { relation ->
            val style = edgeStyle(relation, colors, scheme)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(width = 22.dp, height = 10.dp).clearAndSetSemantics {}) {
                    drawLine(
                        style.color,
                        Offset(0f, size.height / 2),
                        Offset(size.width, size.height / 2),
                        strokeWidth = style.widthDp.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = style.dash?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * density }) },
                    )
                }
                Text(relation.label, style = MaterialTheme.typography.labelSmall, color = if (relation == LinkRelation.CONTRADICTS) colors.unavailable else scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ShapeKey(shape: ScopeKind, label: String) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(12.dp).clearAndSetSemantics {}) {
            val stroke = Stroke(1.5.dp.toPx(), pathEffect = if (shape == ScopeKind.TASK) PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx())) else null)
            if (shape == ScopeKind.SHARED) {
                drawRoundRect(tint, Offset(1f, 1f), Size(size.width - 2f, size.height - 2f), CornerRadius(3.dp.toPx()), style = stroke)
            } else {
                drawCircle(tint, size.minDimension / 2 - 1f, style = stroke)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/**
 * The bridge to external graph viewers, with a note on what the file is and where it may go: the export is the owner's
 * own act through the share sheet, so the app chosen there may send it off the phone (rule 21, D37: also in local-only
 * mode, where the snackbar says the same once more).
 */
@Composable
private fun ExportRow(state: GraphUiState, onExport: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Shares this view as aicc.memory-graph JSON, a neutral format for external graph viewers. Includes full text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(state.exportNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(
            onClick = onExport,
            enabled = !state.isExporting && state.render.nodes.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            if (state.isExporting) {
                CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
            } else {
                Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            }
            Text("Export graph", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
        }
    }
}
