package com.aicontrolcenter.app.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.ActivityEventRow
import com.aicontrolcenter.app.ui.components.ActivityRowUi
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.Scan
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors

/** The "needs attention" list. Each row leads to the place where the problem is fixed. */
@Composable
internal fun AttentionSection(
    items: List<AttentionItem>,
    onOpen: (AttentionTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(if (items.isEmpty()) "Needs attention" else "Needs attention · ${items.size}")
        AppCard(contentPadding = PaddingValues(0.dp)) {
            if (items.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    Icon(
                        StatusTone.READY.icon,
                        contentDescription = null,
                        tint = appColors.ready,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        "Nothing needs your attention.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            items.forEachIndexed { index, item ->
                if (index > 0) RowDivider()
                AttentionRow(item, onClick = { onOpen(item.target) })
            }
        }
    }
}

@Composable
private fun AttentionRow(item: AttentionItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Fix", onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(
            imageVector = item.tone.icon,
            contentDescription = if (item.tone == StatusTone.UNAVAILABLE) "Blocking" else "Attention",
            tint = appColors.status(item.tone),
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The two things the owner most often starts from the dashboard. */
@Composable
internal fun QuickActionsSection(
    isScanning: Boolean,
    onNewAgent: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader("Quick actions")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Button(
                onClick = onNewAgent,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("New agent", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
            OutlinedButton(
                onClick = onScan,
                enabled = !isScanning,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                } else {
                    Icon(AppIcons.Scan, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Text(
                    text = if (isScanning) "Scanning…" else "Scan for AI apps",
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

/** The newest few events, with a way to the full log. */
@Composable
internal fun RecentActivitySection(
    rows: List<ActivityRowUi>,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader("Latest activity", actionLabel = "See all", onAction = onSeeAll)
        AppCard(contentPadding = PaddingValues(vertical = Spacing.xs)) {
            if (rows.isEmpty()) {
                Text(
                    text = "Nothing has been recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) RowDivider(Modifier.padding(start = 64.dp))
                ActivityEventRow(row)
            }
        }
    }
}
