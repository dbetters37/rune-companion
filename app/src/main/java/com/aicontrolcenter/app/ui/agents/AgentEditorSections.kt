package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.KeyboardType
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StepperRow
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.core.agents.AgentField
import java.util.Locale

private enum class ModelPickerTarget { PRIMARY, FALLBACK }

@Composable
internal fun ModelEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    val draft = state.draft
    var picker by rememberSaveable { mutableStateOf<ModelPickerTarget?>(null) }
    val fallbackCandidates = state.models.filter { it.id != draft.modelId && it.id !in draft.fallbackModelIds }

    Column {
        SectionHeader("Model")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (state.models.isEmpty()) {
                    InfoBanner(
                        title = "No models are registered yet",
                        text = "A model is reached through a provider, and connecting providers is the next phase of this " +
                            "app. Until then an agent can be fully configured but cannot run. See the Models section.",
                    )
                }
                val selected = state.model(draft.modelId)
                when {
                    selected != null -> ModelOptionRow(selected, label = "Primary model")
                    draft.modelId != null -> Text(
                        "The assigned model is no longer registered. Choose another one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(
                        "No model assigned. The agent cannot run without one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.issue(AgentField.MODEL)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (state.models.isNotEmpty() || draft.modelId != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (state.models.isNotEmpty()) {
                            OutlinedButton(onClick = { picker = ModelPickerTarget.PRIMARY }) {
                                Text(if (draft.modelId == null) "Choose model" else "Change model")
                            }
                        }
                        if (draft.modelId != null) {
                            TextButton(onClick = { actions.onSetModel(null) }) { Text("Remove model") }
                        }
                    }
                }
                if (draft.fallbackModelIds.isNotEmpty() || fallbackCandidates.isNotEmpty()) {
                    RowDivider()
                    Text(
                        "Fallback models",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Tried in order when the primary model is unavailable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    draft.fallbackModelIds.forEachIndexed { index, id ->
                        val option = state.model(id)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (option != null) {
                                    ModelOptionRow(option, label = "Fallback ${index + 1}")
                                } else {
                                    Text(
                                        "Fallback ${index + 1}: no longer registered",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            IconButton(onClick = { actions.onRemoveFallback(id) }) {
                                Icon(AppIcons.Close, contentDescription = "Remove fallback ${option?.name ?: index + 1}")
                            }
                        }
                    }
                    if (fallbackCandidates.isNotEmpty()) {
                        TextButton(onClick = { picker = ModelPickerTarget.FALLBACK }) { Text("Add fallback model") }
                    }
                }
            }
        }
    }

    when (picker) {
        ModelPickerTarget.PRIMARY -> ModelPickerDialog(
            title = "Choose a model",
            models = state.models,
            selectedId = draft.modelId,
            onPick = {
                actions.onSetModel(it)
                picker = null
            },
            onDismiss = { picker = null },
        )
        ModelPickerTarget.FALLBACK -> ModelPickerDialog(
            title = "Add a fallback model",
            models = fallbackCandidates,
            selectedId = null,
            onPick = {
                actions.onAddFallback(it)
                picker = null
            },
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

@Composable
internal fun GenerationEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    val generation = state.draft.generation
    val temperature = generation.temperature
    Column {
        SectionHeader("Generation settings")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SwitchRow(
                    title = "Use the model's default temperature",
                    description = "Turn off to set how predictable or varied this agent's replies are.",
                    checked = temperature == null,
                    onCheckedChange = { useDefault -> actions.onSetTemperature(if (useDefault) null else DEFAULT_TEMPERATURE) },
                )
                if (temperature != null) {
                    val shown = String.format(Locale.ROOT, "%.1f", temperature)
                    Text(
                        "Temperature $shown",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Slider(
                        value = temperature.toFloat().coerceIn(0f, 2f),
                        onValueChange = { actions.onSetTemperature(it.toDouble()) },
                        valueRange = 0f..2f,
                        steps = 19,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Temperature" },
                    )
                    Text(
                        "0 is the most predictable, 2 the most varied.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RowDivider(Modifier.padding(vertical = Spacing.xs))
                AppTextField(
                    value = generation.maxOutputTokens?.toString().orEmpty(),
                    onValueChange = actions.onSetMaxOutputTokens,
                    label = "Maximum output tokens",
                    placeholder = "Model default",
                    supportingText = "Leave empty to use the model's own limit.",
                    errorText = state.issue(AgentField.GENERATION),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    transformInput = { typed -> typed.filter(Char::isDigit).take(7) },
                )
            }
        }
    }
}

@Composable
internal fun MemoryEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    val memory = state.draft.memory
    Column {
        SectionHeader("Memory policy")
        AppCard {
            Column {
                SwitchRow(
                    title = "Private memory",
                    description = "The agent keeps its own memories that no other agent can read.",
                    checked = memory.privateMemoryEnabled,
                    onCheckedChange = { on -> actions.onEdit { it.copy(memory = it.memory.copy(privateMemoryEnabled = on)) } },
                )
                RowDivider()
                SwitchRow(
                    title = "Recall automatically",
                    description = "Relevant memories are retrieved before each task.",
                    checked = memory.autoRecall,
                    onCheckedChange = { on -> actions.onEdit { it.copy(memory = it.memory.copy(autoRecall = on)) } },
                )
                RowDivider()
                StepperRow(
                    title = "Memories per task",
                    description = "The most memories added to a prompt by automatic recall.",
                    value = memory.recallLimit,
                    onValueChange = { limit -> actions.onEdit { it.copy(memory = it.memory.copy(recallLimit = limit)) } },
                    range = 0..50,
                )
                state.issue(AgentField.MEMORY)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "The policy is stored now and takes effect when the memory system is built.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }
}

@Composable
internal fun DelegationEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    val delegation = state.draft.delegation
    Column {
        SectionHeader("Delegation policy")
        AppCard {
            Column {
                SwitchRow(
                    title = "May delegate",
                    description = "Can hand subtasks to other agents.",
                    checked = delegation.canDelegate,
                    onCheckedChange = { on -> actions.onEdit { it.copy(delegation = it.delegation.copy(canDelegate = on)) } },
                )
                RowDivider()
                SwitchRow(
                    title = "May ask for assistance",
                    description = "Can ask other agents for help without handing over the task.",
                    checked = delegation.canRequestAssistance,
                    onCheckedChange = { on ->
                        actions.onEdit { it.copy(delegation = it.delegation.copy(canRequestAssistance = on)) }
                    },
                )
                RowDivider()
                StepperRow(
                    title = "Delegation depth",
                    description = "How many levels of delegation are allowed below this agent.",
                    value = delegation.maxDepth,
                    onValueChange = { depth -> actions.onEdit { it.copy(delegation = it.delegation.copy(maxDepth = depth)) } },
                    range = 0..10,
                )
                state.issue(AgentField.DELEGATION)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private const val DEFAULT_TEMPERATURE = 0.7
