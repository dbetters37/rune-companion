package com.aicontrolcenter.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.MoreHoriz
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors

/** An icon with a count badge when something in that section needs attention. */
@Composable
private fun BadgedIcon(icon: ImageVector, badge: Int) {
    BadgedBox(
        badge = {
            if (badge > 0) {
                // Nudged outwards: at its default place the badge covers a third of a 24dp icon.
                Badge(
                    containerColor = appColors.attention,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.offset(x = 6.dp, y = (-4).dp),
                ) { Text(if (badge > 99) "99+" else badge.toString()) }
            }
        },
    ) {
        Icon(icon, contentDescription = null)
    }
}

private fun attentionSuffix(badge: Int): String = if (badge > 0) ", ${plural(badge, "item")} need attention" else ""

/** Phone navigation: four sections and "More". */
@Composable
internal fun AppBottomBar(
    current: Section,
    state: ShellUiState,
    onOpenSection: (Section) -> Unit,
    onOpenMore: () -> Unit,
) {
    Column {
        HorizontalDivider(color = appColors.hairline)
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            val itemColors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Section.bottomBar.forEach { section ->
                val badge = state.badge(section)
                NavigationBarItem(
                    selected = current == section,
                    onClick = { onOpenSection(section) },
                    icon = { BadgedIcon(section.icon, badge) },
                    label = { Text(section.title, maxLines = 1) },
                    colors = itemColors,
                    modifier = Modifier.semantics { contentDescription = section.title + attentionSuffix(badge) },
                )
            }
            val moreBadge = state.moreBadge
            NavigationBarItem(
                selected = current !in Section.bottomBar,
                onClick = onOpenMore,
                icon = { BadgedIcon(AppIcons.MoreHoriz, moreBadge) },
                label = { Text("More", maxLines = 1) },
                colors = itemColors,
                modifier = Modifier.semantics { contentDescription = "More sections" + attentionSuffix(moreBadge) },
            )
        }
    }
}

/** Wide-screen navigation: all eight sections, scrollable when the window is short. */
@Composable
internal fun AppNavigationRail(current: Section, state: ShellUiState, onOpenSection: (Section) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Section.entries.forEach { section ->
                val badge = state.badge(section)
                NavigationRailItem(
                    selected = current == section,
                    onClick = { onOpenSection(section) },
                    icon = { BadgedIcon(section.icon, badge) },
                    label = { Text(section.title, maxLines = 1) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.semantics { contentDescription = section.title + attentionSuffix(badge) },
                )
            }
        }
    }
}

/** The sheet behind "More": every section with a one-line description, so none is more than two taps away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreSheet(
    current: Section,
    state: ShellUiState,
    onOpenSection: (Section) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        MoreSheetContent(current, state, onOpenSection)
    }
}

@Composable
internal fun MoreSheetContent(current: Section, state: ShellUiState, onOpenSection: (Section) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Spacing.lg)) {
        Text(
            "All sections",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm).semantics { heading() },
        )
        Section.entries.forEach { section ->
            val badge = state.badge(section)
            val isCurrent = section == current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Tab, onClickLabel = "Open ${section.title}") { onOpenSection(section) }
                    .semantics { selected = isCurrent }
                    .heightIn(min = 64.dp)
                    .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.shapes.small,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        section.icon,
                        contentDescription = null,
                        tint = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        section.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (badge > 0) {
                    Badge(
                        containerColor = appColors.attention,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.semantics { contentDescription = "${plural(badge, "item")} need attention" },
                    ) { Text(badge.toString()) }
                }
            }
        }
    }
}
