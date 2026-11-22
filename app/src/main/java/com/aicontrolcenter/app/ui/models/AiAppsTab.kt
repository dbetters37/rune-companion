package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.monogramOf
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Apps
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.Scan
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AiAppId

/** The registry of AI applications: scan, register manually, and the list of what is registered. */
@Composable
internal fun AiAppsTab(
    apps: List<AiAppRowUi>,
    isScanning: Boolean,
    lastScan: ScanSummaryUi?,
    onScan: () -> Unit,
    onOpenApp: (AiAppId) -> Unit,
    onRegisterManually: () -> Unit,
    onOpenConcepts: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Button(
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
                    Text(if (isScanning) "Scanning…" else "Scan device", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onRegisterManually,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Register manually", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
        }
        if (lastScan != null) {
            item { ScanSummaryBanner(lastScan) }
        }
        item {
            Column {
                Text(
                    "An AI app is an installed Android app with its own screen. Registering one records that it exists; " +
                        "it never means this framework can control it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenConcepts, contentPadding = PaddingValues(0.dp)) {
                    Text("AI application, model or agent?")
                }
            }
        }
        if (apps.isEmpty()) {
            item {
                EmptyState(
                    icon = AppIcons.Apps,
                    title = "No AI apps registered",
                    message = "Scan this device to find installed AI applications, or register one yourself.",
                )
            }
        }
        items(apps, key = { it.id.value }) { app ->
            AiAppCard(app, onClick = { onOpenApp(app.id) })
        }
    }
}

@Composable
private fun ScanSummaryBanner(scan: ScanSummaryUi) {
    val text = if (scan.nothingChanged) {
        "Nothing has changed since the last scan."
    } else {
        buildList {
            add("${scan.added.size} new" + scan.added.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty())
            add("${scan.changed} changed")
            add(
                "${scan.missing.size} no longer installed" +
                    scan.missing.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty(),
            )
        }.joinToString(", ") + "."
    }
    InfoBanner(
        title = "Scan complete",
        text = text,
        tone = if (scan.missing.isNotEmpty()) StatusTone.ATTENTION else StatusTone.READY,
    )
}

/** A registered app: what it is, how it was found and exactly what may be done with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiAppCard(app: AiAppRowUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AppMonogram(app.label)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(app.status.text, app.status.tone)
                }
                Text(
                    app.packageName,
                    style = MonoTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                app.vendorAndVersion?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (app.unverified) {
                        StatusChip(app.sourceLabel, StatusTone.ATTENTION)
                    } else {
                        TintedChip(app.sourceLabel, MaterialTheme.colorScheme.secondary)
                    }
                    if (!app.enabledInRegistry) StatusChip("Disabled in registry", StatusTone.DISABLED)
                    app.capabilities.forEach { NeutralChip(it) }
                }
                Text(
                    text = if (app.permitted.isEmpty()) {
                        "Nothing may be done with this app right now."
                    } else {
                        "Permitted: " + app.permitted.joinToString(", ").lowercase() + "."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                AppIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}

/**
 * A rounded-square monogram standing in for the app. Square, to keep AI applications visually
 * distinct from agents, whose avatars are round.
 */
@Composable
internal fun AppMonogram(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            monogramOf(label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}
