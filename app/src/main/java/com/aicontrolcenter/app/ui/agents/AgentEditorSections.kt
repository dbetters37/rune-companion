package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicontrolcenter.app.ui.components.RowText
import com.aicontrolcenter.app.ui.icons.Groups
import com.aicontrolcenter.app.ui.memory.spaceColorIndex
import com.aicontrolcenter.app.ui.theme.appColors
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
import com.aicontrolcenter.app.ui.components.SIMULATOR_LABEL
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.registry.Locality
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
                        text = "A model is reached through a provider. Add one under Models, Providers; until then this agent " +
                            "can be fully configured but cannot run.",
                    )
                }
                val selected = state.model(draft.modelId)
                when {
                    selected != null -> {
                        ModelOptionRow(selected, label = "Primary model")
                        ModelWarning(selected)
                    }
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
                        Row(verticalAlignment = Alignment.Top) {
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

/** Says plainly what choosing [model] means for the owner's data, or that it is not a real model. */
@Composable
private fun ModelWarning(model: ModelOptionUi) {
    val locality = model.locality
    when {
        model.isSimulator -> InfoBanner(
            title = SIMULATOR_LABEL,
            text = "Replies are scripted and labelled as simulated. Use it to try this agent and its tools, then choose a real model.",
            tone = StatusTone.ATTENTION,
        )
        locality != null && locality.leavesDevice -> InfoBanner(
            title = "Leaves this device",
            text = "What this agent is given, including your messages, is sent to ${model.providerName}, " +
                (if (locality == Locality.CLOUD) "a service on the internet." else "a machine on your network.") +
                " Local-only mode blocks it.",
            tone = StatusTone.ATTENTION,
            icon = locality.icon,
        )
    }
}

/** The tools this agent may use. Each switch only makes a tool available; the permission rules still apply to every call. */
@Composable
internal fun ToolsEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    Column {
        SectionHeader("Tools")
        AppCard {
            Column {
                Text(
                    "Only safe information tools exist so far: they read facts and change nothing. Tools that act on this phone " +
                        "arrive in a later version, with their own permissions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )
                if (state.tools.isEmpty()) {
                    Text("No tools are registered.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.tools.forEachIndexed { index, tool ->
                    if (index > 0) RowDivider()
                    SwitchRow(
                        title = tool.title,
                        description = "${tool.risk.label}. ${tool.description}",
                        checked = tool.id in state.draft.toolIds,
                        onCheckedChange = { on -> actions.onSetTool(tool.id, on) },
                    )
                }
            }
        }
    }
}

/** How much a tool can do, in words. */
internal val RiskLevel.label: String
    get() = when (this) {
        RiskLevel.SAFE -> "Safe: reads only"
        RiskLevel.MODIFYING -> "Changes data you can undo"
        RiskLevel.CONSEQUENTIAL -> "Consequential: always asks you first"
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

/**
 * The agent's memory policy: its own private memory, which shared spaces it may read and which
 * it may write (separately, so an agent is never forced to share everything), and automatic recall.
 */
@Composable
internal fun MemoryEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    val memory = state.draft.memory
    Column {
        SectionHeader("Memory policy")
        AppCard {
            Column {
                SwitchRow(
                    title = "Private memory",
                    description = if (memory.privateMemoryEnabled) {
                        "The agent keeps its own memories that no other agent can read."
                    } else {
                        "Off: it neither saves nor recalls private memories. Memories already kept stay, unused, until you delete them."
                    },
                    checked = memory.privateMemoryEnabled,
                    onCheckedChange = { on -> actions.onEdit { it.copy(memory = it.memory.copy(privateMemoryEnabled = on)) } },
                )
                RowDivider()
                SpaceAccessEditor(state, actions)
                RowDivider()
                SwitchRow(
                    title = "Recall automatically",
                    description = "Before each turn, the memories most relevant to the request are added to its prompt.",
                    checked = memory.autoRecall,
                    onCheckedChange = { on -> actions.onEdit { it.copy(memory = it.memory.copy(autoRecall = on)) } },
                )
                if (memory.autoRecall) {
                    RowDivider()
                    StepperRow(
                        title = "Memories per turn",
                        description = "The most memories automatic recall adds to one prompt.",
                        value = memory.recallLimit,
                        onValueChange = { limit -> actions.onEdit { it.copy(memory = it.memory.copy(recallLimit = limit)) } },
                        range = 0..50,
                    )
                }
                state.issue(AgentField.MEMORY)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Read and write access per shared space, as two chips on each space's row. */
@Composable
private fun SpaceAccessEditor(state: AgentEditorUiState, actions: AgentEditorActions) {
    val memory = state.draft.memory
    Column(Modifier.padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        RowText(
            title = "Shared spaces",
            description = "Read: it may recall what is in the space. Write: it may add to it. Spaces you allow nothing stay invisible to it.",
        )
        if (state.spaces.isEmpty()) {
            Text(
                "There are no shared spaces yet. Create one under Memory, Spaces.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        state.spaces.forEach { space ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(AppIcons.Groups, contentDescription = null, tint = appColors.agentAccent(spaceColorIndex(space.id)), modifier = Modifier.size(18.dp))
                Text(
                    space.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                SpaceAccessChip("Read", space.id in memory.readableSpaces, "May read ${space.name}") { actions.onSetSpaceReadable(space.id, it) }
                SpaceAccessChip("Write", space.id in memory.writableSpaces, "May write ${space.name}") { actions.onSetSpaceWritable(space.id, it) }
            }
        }
    }
}

@Composable
private fun SpaceAccessChip(label: String, selected: Boolean, description: String, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        modifier = Modifier.semantics { contentDescription = description },
    )
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
