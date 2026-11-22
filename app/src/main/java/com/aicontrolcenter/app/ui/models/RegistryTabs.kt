package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.explanation
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ModelChip
import com.aicontrolcenter.app.ui.icons.Provider
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors

private val TabPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg, bottom = Spacing.xl)

/** Model providers as the registry holds them. Read-only in this version, and it says so. */
@Composable
internal fun ProvidersTab(providers: List<ProviderRowUi>) {
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = TabPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (providers.isEmpty()) {
            item {
                EmptyState(
                    icon = AppIcons.Provider,
                    title = "No providers yet",
                    message = "A provider is a connection to something that serves models: a cloud API, a server on your " +
                        "network, or a runtime on this phone. Connecting providers is the next phase of this app, so " +
                        "there is nothing to add here yet.",
                )
            }
        } else {
            item { NotEditableYetNote("Providers can be viewed here. Adding and editing them arrives in the next phase of this app.") }
        }
        items(providers, key = { it.id.value }) { provider ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Icon(
                        AppIcons.Provider,
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
                            Text(it, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TintedChip(
                            provider.locality.label,
                            appColors.locality(provider.locality),
                            icon = provider.locality.icon,
                        )
                        Text(
                            provider.locality.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Registered models. A model is an LLM reached through a provider, not an app and not an agent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelsListTab(models: List<ModelRowUi>) {
    LazyColumn(
        modifier = Modifier.readableWidth().fillMaxSize(),
        contentPadding = TabPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (models.isEmpty()) {
            item {
                EmptyState(
                    icon = AppIcons.ModelChip,
                    title = "No models yet",
                    message = "A model is an LLM that agents think with. Models are listed from a provider or imported as " +
                        "a file, and both arrive in the next phase of this app. Until then no agent can run.",
                )
            }
        } else {
            item { NotEditableYetNote("Models can be viewed here. Adding and importing them arrives in the next phase of this app.") }
        }
        items(models, key = { it.id.value }) { model ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Icon(
                        AppIcons.ModelChip,
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            StatusChip(model.status.text, model.status.tone)
                        }
                        Text(model.wireName, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "From ${model.providerName} · used by ${plural(model.agentsUsing, "agent")}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            model.locality?.let { TintedChip(it.label, appColors.locality(it), icon = it.icon) }
                            model.traits.forEach { NeutralChip(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotEditableYetNote(text: String) {
    InfoBanner(text = text)
}
