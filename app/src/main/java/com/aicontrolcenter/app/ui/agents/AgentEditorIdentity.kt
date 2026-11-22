package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.ChipEditor
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.AppColors
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.agents.AgentField
import com.aicontrolcenter.core.agents.AgentKind
import com.aicontrolcenter.core.agents.label

/** What each protocol role means, in the owner's terms. */
internal val AgentKind.roleExplanation: String
    get() = when (this) {
        AgentKind.DIRECTOR -> "Receives your objectives, plans the work, assigns it and assembles the final result."
        AgentKind.SPECIALIST -> "Does the work it is assigned in its speciality."
        AgentKind.REVIEWER -> "Checks other agents' results and asks for revisions."
        AgentKind.OPERATOR -> "The role that may be granted phone-operation tools once those exist."
    }

@Composable
internal fun IdentityEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    val draft = state.draft
    Column {
        SectionHeader("Identity")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AppTextField(
                    value = draft.name,
                    onValueChange = { name -> actions.onEdit { it.copy(name = name) } },
                    label = "Name",
                    errorText = state.issue(AgentField.NAME),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Role", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        AgentKind.entries.forEachIndexed { index, kind ->
                            SegmentedButton(
                                selected = draft.kind == kind,
                                onClick = { actions.onEdit { it.copy(kind = kind) } },
                                shape = SegmentedButtonDefaults.itemShape(index, AgentKind.entries.size),
                                icon = {},
                                label = { Text(kind.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
                            )
                        }
                    }
                    val kindIssue = state.issue(AgentField.KIND)
                    Text(
                        text = kindIssue ?: draft.kind.roleExplanation,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (kindIssue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AppTextField(
                    value = draft.description,
                    onValueChange = { text -> actions.onEdit { it.copy(description = text) } },
                    label = "Description",
                    supportingText = "One line saying what this agent is for. The Director reads it when assigning work.",
                    singleLine = false,
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                AccentPicker(selected = draft.colorIndex, onSelect = { index -> actions.onEdit { it.copy(colorIndex = index) } })
                RowDivider()
                SwitchRow(
                    title = "Enabled",
                    description = "A disabled agent stays in the registry but is never given work.",
                    checked = draft.enabled,
                    onCheckedChange = { enabled -> actions.onEdit { it.copy(enabled = enabled) } },
                )
            }
        }
    }
}

@Composable
private fun AccentPicker(selected: Int, onSelect: (Int) -> Unit) {
    val colors = appColors
    val current = Math.floorMod(selected, AppColors.AGENT_ACCENT_COUNT)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text("Accent colour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(AppColors.AGENT_ACCENT_COUNT) { index ->
                val isSelected = index == current
                val accent = colors.agentAccent(index)
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(index) })
                        .semantics { contentDescription = "Accent colour ${index + 1}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 34.dp else 26.dp)
                            .then(if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .padding(if (isSelected) 4.dp else 0.dp)
                            .background(accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                AppIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun InstructionsEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    val draft = state.draft
    Column {
        SectionHeader("Instructions and personality")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AppTextField(
                    value = draft.systemInstructions,
                    onValueChange = { text -> actions.onEdit { it.copy(systemInstructions = text) } },
                    label = "Instructions",
                    supportingText = "The agent's standing instructions: what it does and how it should work.",
                    errorText = state.issue(AgentField.INSTRUCTIONS),
                    singleLine = false,
                    minLines = 5,
                    maxLines = 14,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                AppTextField(
                    value = draft.personality,
                    onValueChange = { text -> actions.onEdit { it.copy(personality = text) } },
                    label = "Personality",
                    supportingText = "Tone and character, kept apart from the instructions so either can change alone.",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
            }
        }
    }
}

@Composable
internal fun ResponsibilitiesAndSkillsEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    val draft = state.draft
    Column {
        SectionHeader("Responsibilities")
        AppCard {
            ChipEditor(
                values = draft.responsibilities,
                onAdd = actions.onAddResponsibility,
                onRemove = actions.onRemoveResponsibility,
                itemName = "responsibility",
                emptyText = "The specialised jobs this agent is responsible for.",
            )
        }
        SectionHeader("Skills", modifier = Modifier.padding(top = Spacing.md))
        AppCard {
            ChipEditor(
                values = draft.skills.toList(),
                onAdd = actions.onAddSkill,
                onRemove = actions.onRemoveSkill,
                itemName = "skill",
                emptyText = "Tags the Director matches work against, such as research or review.",
            )
        }
    }
}
