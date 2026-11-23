package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.SIMULATOR_LABEL
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.Key
import com.aicontrolcenter.app.ui.icons.ModelChip
import com.aicontrolcenter.app.ui.icons.Provider
import com.aicontrolcenter.app.ui.icons.Scan
import com.aicontrolcenter.app.ui.icons.Sparkle
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.OverlineTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId

private val TabPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg, bottom = Spacing.xl)

/** Model providers: what is connected, where each sends prompts, and whether it answers. */
@Composable
internal fun ProvidersTab(
    providers: List<ProviderRowUi>,
    isSearching: Boolean,
    onAddProvider: () -> Unit,
    onFindServers: () -> Unit,
    onOpenProvider: (ProviderId) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = TabPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Button(
                    onClick = onAddProvider,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Add provider", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onFindServers,
                    enabled = !isSearching,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                    } else {
                        Icon(AppIcons.Scan, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    }
                    Text("Find servers on this phone", modifier = Modifier.padding(start = 8.dp), maxLines = 2)
                }
            }
        }
        if (providers.isEmpty()) {
            item {
                EmptyState(
                    icon = AppIcons.Provider,
                    title = "No providers yet",
                    message = "A provider is a connection to something that serves models: a cloud API, a server on your " +
                        "network, or a server on this phone. Add one to give your agents a model to think with.",
                )
            }
        }
        items(providers, key = { it.id.value }) { provider -> ProviderCard(provider, onClick = { onOpenProvider(provider.id) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderCard(provider: ProviderRowUi, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(
                if (provider.isSimulator) AppIcons.Sparkle else AppIcons.Provider,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(provider.status.text, provider.status.tone)
                }
                Text(
                    "${provider.kindLabel} · ${plural(provider.modelCount, "model")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                provider.address?.let {
                    Text(it, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TintedChip(provider.locality.label, appColors.locality(provider.locality), icon = provider.locality.icon)
                    // In the list only what matters: a stored key, or a missing one a cloud service needs.
                    if (provider.keyMissing) {
                        TintedChip(provider.keyNote ?: "No API key stored", appColors.attention, icon = AppIcons.Key)
                    } else if (provider.keyStored == true) {
                        NeutralChip(provider.keyNote ?: "API key stored", icon = AppIcons.Key)
                    }
                }
                provider.statusMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = appColors.status(provider.status.tone),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(provider.lastChecked, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Registered models, grouped by the provider that serves them. A model is an LLM, not an app and not an agent. */
@Composable
internal fun ModelsListTab(
    groups: List<ProviderGroupUi>,
    onOpenModel: (ModelId) -> Unit,
    onSetEnabled: (ModelId, Boolean) -> Unit,
    onAddModel: (ProviderId, String) -> Unit,
    onAddProvider: () -> Unit,
) {
    var addingTo by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = TabPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (groups.isEmpty()) {
            item {
                EmptyState(
                    icon = AppIcons.ModelChip,
                    title = "No models yet",
                    message = "A model is an LLM that agents think with. Models come from a provider: add one, and its " +
                        "models are listed here.",
                    actionLabel = "Add provider",
                    onAction = onAddProvider,
                )
            }
        }
        groups.forEach { group ->
            item(key = "group:${group.providerId.value}") {
                GroupHeader(group, adding = addingTo == group.providerId.value, onAddModel = { addingTo = group.providerId.value })
            }
            if (addingTo == group.providerId.value) {
                item(key = "add:${group.providerId.value}") {
                    AddModelCard(
                        providerName = group.providerName,
                        onAdd = { name ->
                            onAddModel(group.providerId, name)
                            addingTo = null
                        },
                        onDismiss = { addingTo = null },
                    )
                }
            }
            if (group.models.isEmpty()) {
                item(key = "empty:${group.providerId.value}") {
                    Text(
                        "No models yet. Refresh the list on the provider's page, or add one by name.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                    )
                }
            }
            items(group.models, key = { it.id.value }) { model ->
                ModelCard(model, onClick = { onOpenModel(model.id) }, onSetEnabled = { onSetEnabled(model.id, it) })
            }
        }
    }
}

@Composable
private fun GroupHeader(group: ProviderGroupUi, adding: Boolean, onAddModel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                group.providerName.uppercase(),
                style = OverlineTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "Models of ${group.providerName}" },
            )
            Text(
                group.locality.label + if (group.providerEnabled) "" else " · provider disabled",
                style = MaterialTheme.typography.bodySmall,
                color = appColors.locality(group.locality),
            )
        }
        if (!group.isSimulator && !adding) {
            TextButton(onClick = onAddModel) {
                Icon(AppIcons.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Add model", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelCard(model: ModelRowUi, onClick: () -> Unit, onSetEnabled: (Boolean) -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Icon(
                if (model.isSimulator) AppIcons.Sparkle else AppIcons.ModelChip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        model.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(model.status.text, model.status.tone)
                }
                Text(model.wireName, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (model.isSimulator) TintedChip(SIMULATOR_LABEL, appColors.attention, icon = AppIcons.Sparkle)
                    model.locality?.let { TintedChip(it.label, appColors.locality(it), icon = it.icon) }
                    model.chips.forEach { NeutralChip(it.text) }
                }
                if (model.chips.isNotEmpty() && model.chips.any { it.estimated }) {
                    Text(model.capabilityNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (model.agentsUsing == 0) "Not used by any agent" else "Used by ${plural(model.agentsUsing, "agent")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = model.enabled,
                        onCheckedChange = onSetEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = if (model.enabled) "Disable ${model.name}" else "Enable ${model.name}"
                        },
                    )
                }
            }
        }
    }
}

/** The tone of a status for text written in its colour. */
internal val StatusTone.isProblem: Boolean get() = this == StatusTone.UNAVAILABLE || this == StatusTone.ATTENTION
