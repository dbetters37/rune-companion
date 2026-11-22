package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.explanation
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.OpenInNew
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.registry.AiApp
import com.aicontrolcenter.core.registry.AiAppStatus
import com.aicontrolcenter.core.registry.DiscoverySource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppIdentityCard(app: AiApp) {
    val status = app.status.toStatusLabel()
    AppCard {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            AppMonogram(app.label)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(app.label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(app.packageName, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOfNotNull(app.vendor, app.versionName?.let { "version $it" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FlowRow(
            modifier = Modifier.padding(top = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            NeutralChip("AI application")
            StatusChip(status.text, status.tone)
            if (app.source == DiscoverySource.HEURISTIC) {
                StatusChip(app.source.label, StatusTone.ATTENTION)
            } else {
                TintedChip(app.source.label, MaterialTheme.colorScheme.secondary)
            }
        }
        Text(
            text = listOfNotNull(status.detail, app.source.explanation).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.md),
        )
    }
}

/** The complete list of what may be done with the app, and the two actions available today. */
@Composable
internal fun PermittedSection(state: AiAppDetailUiState, onOpenApp: () -> Unit, onSendText: () -> Unit) {
    val app = state.app ?: return
    Column {
        SectionHeader("What this framework may do with it")
        AppCard {
            if (state.channels.isEmpty()) {
                Text(
                    text = when {
                        !app.enabled -> "Nothing: the app is disabled in this registry."
                        app.status == AiAppStatus.NOT_INSTALLED -> "Nothing: the app is not installed."
                        app.status == AiAppStatus.DISABLED_BY_SYSTEM -> "Nothing: the app is disabled in Android settings."
                        else -> "Nothing: the app exposes no supported way to interact with it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                state.channels.sortedBy { it.ordinal }.forEach { channel ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Icon(
                            AppIcons.Check,
                            contentDescription = "Permitted",
                            tint = appColors.ready,
                            modifier = Modifier.padding(top = 2.dp).size(18.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(channel.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                channel.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                "Nothing beyond this list is possible. Being registered never means an app can be controlled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.md),
            )
            RowDivider(Modifier.padding(vertical = Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Button(
                    onClick = onOpenApp,
                    enabled = state.canOpen && !state.isBusy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(AppIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Open app", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onSendText,
                    enabled = state.canHandOffText && !state.isBusy,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(AppIcons.Send, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Send text…", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
            Text(
                "Sending text is a hand-off: the reply stays in that app and is not visible here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CapabilitiesSection(app: AiApp) {
    Column {
        SectionHeader("What the app exposes on this device")
        AppCard {
            if (app.capabilities.isEmpty()) {
                Text(
                    "No integration surface was detected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    app.capabilities.sortedBy { it.ordinal }.forEach { NeutralChip(it.label) }
                }
            }
        }
    }
}

@Composable
internal fun RegistrySettingsSection(
    app: AiApp,
    isBusy: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onToggleAccessibility: (Boolean) -> Unit,
) {
    Column {
        SectionHeader("Your decisions")
        AppCard {
            SwitchRow(
                title = "Enabled in registry",
                description = "When off, the app stays listed but nothing is ever done with it.",
                checked = app.enabled,
                onCheckedChange = onSetEnabled,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth(),
            )
            RowDivider()
            SwitchRow(
                title = "Accessibility operation authorised",
                description = if (app.status == AiAppStatus.INSTALLED) {
                    "Your permission to operate this app's screen. Off unless you turn it on. " +
                        "Cannot be used until the operator service of a later version exists."
                } else {
                    "Only an installed app can be authorised."
                },
                checked = app.accessibilityAuthorized,
                onCheckedChange = onToggleAccessibility,
                enabled = !isBusy && (app.status == AiAppStatus.INSTALLED || app.accessibilityAuthorized),
            )
        }
    }
}
