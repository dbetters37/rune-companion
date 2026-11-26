package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ChevronRight
import com.aicontrolcenter.app.ui.icons.Key
import com.aicontrolcenter.app.ui.icons.Insights
import com.aicontrolcenter.app.ui.icons.Lightbulb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.llm.ProviderPreset
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ProviderKind

/** A group of presets in the gallery, by where their models run. */
internal data class PresetGroup(val title: String, val presets: List<ProviderPreset>)

/**
 * Groups presets by where prompts would go, nearest first, so the most private choices are
 * the first ones seen. The simulator is left out: it is built in and always registered.
 */
internal fun groupPresets(presets: List<ProviderPreset>): List<PresetGroup> {
    val usable = presets.filter { it.kind != ProviderKind.SIMULATOR }
    val (custom, known) = usable.partition { it.baseUrl == null && it.kind == ProviderKind.OPENAI_COMPATIBLE }
    val byLocality = known.groupBy { Locality.infer(it.kind, it.baseUrl) }
    return listOfNotNull(
        byLocality[Locality.ON_DEVICE]?.let { PresetGroup("On this phone", it) },
        byLocality[Locality.LOCAL_NETWORK]?.let { PresetGroup("On your local network", it) },
        byLocality[Locality.CLOUD]?.let { PresetGroup("Cloud services", it) },
        custom.takeIf { it.isNotEmpty() }?.let { PresetGroup("Something else", it) },
    )
}

@Composable
fun PresetGalleryRoute(onBack: () -> Unit, onPick: (presetId: String) -> Unit) {
    val graph = LocalAppGraph.current
    val groups = remember(graph) { groupPresets(graph.providerManager.presets) }
    PresetGalleryScreen(groups, onBack, onPick)
}

@Composable
internal fun PresetGalleryScreen(groups: List<PresetGroup>, onBack: () -> Unit, onPick: (String) -> Unit) {
    ScreenScaffold(title = "Add a provider", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).readableWidth().fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item {
                InfoBanner(
                    text = "A provider serves models. Pick what you want to connect; the next screen is pre-filled and " +
                        "you can change everything before saving.",
                )
            }
            groups.forEach { group ->
                item(key = "header:${group.title}") { SectionHeader(group.title, Modifier.padding(top = Spacing.sm)) }
                items(group.presets, key = { it.id }) { preset -> PresetCard(preset, onClick = { onPick(preset.id) }) }
            }
        }
    }
}

/** A preset's description in its two parts: what it is, and the plain line on what using it costs (null when it has none). */
internal fun presetDescription(preset: ProviderPreset): Pair<String, String?> {
    val lines = preset.description.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return if (lines.size < 2) preset.description.trim() to null else lines.dropLast(1).joinToString(" ") to lines.last()
}

/** "What it costs", set apart from what the preset is, with the usage icon. */
@Composable
internal fun PresetCostLine(cost: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "What it costs: $cost" },
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(AppIcons.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(cost, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetCard(preset: ProviderPreset, onClick: () -> Unit) {
    val locality = Locality.infer(preset.kind, preset.baseUrl)
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(preset.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                val (what, cost) = presetDescription(preset)
                Text(what, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                cost?.let { PresetCostLine(it) }
                FlowRow(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (preset.baseUrl == null && preset.kind == ProviderKind.OPENAI_COMPATIBLE) {
                        NeutralChip("Where it goes depends on its address")
                    } else {
                        TintedChip(locality.label, appColors.locality(locality), icon = locality.icon)
                    }
                    if (preset.needsApiKey) NeutralChip("Needs an API key", icon = AppIcons.Key)
                }
                Row(
                    modifier = Modifier.padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(
                        AppIcons.Lightbulb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 1.dp).size(16.dp),
                    )
                    Text(preset.setupHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
