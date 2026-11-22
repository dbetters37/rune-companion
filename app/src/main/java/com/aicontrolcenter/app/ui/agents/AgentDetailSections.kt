package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.KeyValueRow
import com.aicontrolcenter.app.ui.components.NeutralChip
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ModelChip
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.agents.label
import java.util.Locale

@Composable
internal fun IdentitySection(agent: AgentDetailUi) {
    val profile = agent.profile
    Column {
        SectionHeader("Identity")
        AppCard {
            KeyValueRow("Name", profile.name)
            KeyValueRow("Role", "${profile.kind.label}. ${profile.kind.roleExplanation}")
            KeyValueRow("Description", profile.description.ifBlank { "None" })
            KeyValueRow("Agent id", profile.id.value, mono = true)
        }
    }
}

@Composable
internal fun InstructionsSection(agent: AgentDetailUi) {
    val profile = agent.profile
    Column {
        SectionHeader("Instructions")
        AppCard { ExpandableText(profile.systemInstructions, emptyText = "No instructions have been written.") }
        SectionHeader("Personality", modifier = Modifier.padding(top = Spacing.md))
        AppCard { ExpandableText(profile.personality, emptyText = "No personality has been described.") }
    }
}

/** Long prose, cut to a few lines until the owner asks for all of it. */
@Composable
private fun ExpandableText(text: String, emptyText: String, collapsedLines: Int = 6) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var overflows by rememberSaveable { mutableStateOf(false) }
    if (text.isBlank()) {
        Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
    )
    if (overflows) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = Spacing.xs)) {
            Text(if (expanded) "Show less" else "Show all")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ResponsibilitiesSection(agent: AgentDetailUi) {
    val profile = agent.profile
    Column {
        SectionHeader("Responsibilities")
        AppCard {
            if (profile.responsibilities.isEmpty()) {
                Text("None listed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                profile.responsibilities.forEach { responsibility ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        Text(responsibility, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        SectionHeader("Skills", modifier = Modifier.padding(top = Spacing.md))
        AppCard {
            if (profile.skills.isEmpty()) {
                Text("None listed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    profile.skills.forEach { NeutralChip(it) }
                }
            }
        }
    }
}

@Composable
internal fun ModelSection(agent: AgentDetailUi, onOpenModels: () -> Unit) {
    Column {
        SectionHeader("Model")
        AppCard {
            val model = agent.model
            if (model == null) {
                Text("No model assigned", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = if (agent.modelsAvailable) {
                        "This agent cannot run until a model is assigned. Edit the agent to choose one."
                    } else {
                        "This agent cannot run until a model is assigned, and no models are registered yet. " +
                            "Connecting model providers is the next phase of this app."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
                OutlinedButton(onClick = onOpenModels, modifier = Modifier.padding(top = Spacing.md)) { Text("Open Models") }
            } else {
                ModelRefRow(model, label = "Primary model")
                agent.fallbackModels.forEachIndexed { index, fallback ->
                    RowDivider(Modifier.padding(vertical = Spacing.md))
                    ModelRefRow(fallback, label = "Fallback ${index + 1}")
                }
                if (agent.fallbackModels.isEmpty()) {
                    Text(
                        "No fallback models.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.md),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRefRow(model: ModelRefUi, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Icon(
            AppIcons.ModelChip,
            contentDescription = null,
            tint = if (model.registered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(model.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = model.providerName ?: if (model.registered) "Provider not registered" else "Its model is no longer registered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        model.locality?.let { TintedChip(it.label, appColors.locality(it), icon = it.icon) }
    }
}

@Composable
internal fun PolicySections(agent: AgentDetailUi) {
    val profile = agent.profile
    Column {
        SectionHeader("Generation settings")
        AppCard {
            KeyValueRow(
                "Temperature",
                profile.generation.temperature?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "Model default",
            )
            KeyValueRow("Maximum output tokens", profile.generation.maxOutputTokens?.toString() ?: "Model default")
        }
        SectionHeader("Memory policy", modifier = Modifier.padding(top = Spacing.md))
        AppCard {
            KeyValueRow("Private memory", if (profile.memory.privateMemoryEnabled) "On" else "Off")
            KeyValueRow("Automatic recall", if (profile.memory.autoRecall) "On" else "Off")
            KeyValueRow("Memories per task", profile.memory.recallLimit.toString())
            KeyValueRow("Shared spaces it reads", countOrNone(profile.memory.readableSpaces.size))
            KeyValueRow("Shared spaces it writes", countOrNone(profile.memory.writableSpaces.size))
        }
        SectionHeader("Delegation policy", modifier = Modifier.padding(top = Spacing.md))
        AppCard {
            KeyValueRow("May delegate", if (profile.delegation.canDelegate) "Yes" else "No")
            KeyValueRow("May ask for assistance", if (profile.delegation.canRequestAssistance) "Yes" else "No")
            KeyValueRow("Delegation depth", profile.delegation.maxDepth.toString())
        }
    }
}

private fun countOrNone(count: Int): String = if (count == 0) "None" else count.toString()

/** Designate and delete: the two actions that change the team's structure. */
@Composable
internal fun ManageSection(agent: AgentDetailUi, isBusy: Boolean, onDesignate: () -> Unit, onDelete: () -> Unit) {
    Column {
        SectionHeader("Manage")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (agent.canDesignateAsDirector) {
                    OutlinedButton(
                        onClick = onDesignate,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(AppIcons.Star, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text("Designate as Director", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        "The designated Director receives your objectives. Only one agent holds that position.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RowDivider()
                }
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !isBusy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text("Delete agent", modifier = Modifier.padding(start = 8.dp))
                }
                if (agent.deleteBlockedReason != null) {
                    Text(
                        "The designated Director cannot be deleted. Designate another Director first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
