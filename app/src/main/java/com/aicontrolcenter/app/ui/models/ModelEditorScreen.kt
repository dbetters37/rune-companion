package com.aicontrolcenter.app.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aicontrolcenter.app.di.LocalAppGraph
import com.aicontrolcenter.app.ui.components.AgentAvatar
import com.aicontrolcenter.app.ui.components.AppCard
import com.aicontrolcenter.app.ui.components.AppTextField
import com.aicontrolcenter.app.ui.components.digitsOnly
import com.aicontrolcenter.app.ui.components.atMostChars
import com.aicontrolcenter.app.ui.chat.TOKENS_EXPLAINED
import com.aicontrolcenter.core.usage.ModelPrice
import androidx.compose.ui.semantics.semantics
import com.aicontrolcenter.app.ui.icons.Insights
import com.aicontrolcenter.app.ui.components.ConfirmDialog
import com.aicontrolcenter.app.ui.components.EmptyState
import com.aicontrolcenter.app.ui.components.InfoBanner
import com.aicontrolcenter.app.ui.components.RowDivider
import com.aicontrolcenter.app.ui.components.SIMULATOR_LABEL
import com.aicontrolcenter.app.ui.components.ScreenScaffold
import com.aicontrolcenter.app.ui.components.SectionHeader
import com.aicontrolcenter.app.ui.components.StatusChip
import com.aicontrolcenter.app.ui.components.SwitchRow
import com.aicontrolcenter.app.ui.components.TintedChip
import com.aicontrolcenter.app.ui.components.icon
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.readableWidth
import com.aicontrolcenter.app.ui.components.testLabel
import com.aicontrolcenter.app.ui.icons.Agent
import com.aicontrolcenter.app.ui.icons.AppIcons
import com.aicontrolcenter.app.ui.icons.ModelChip
import com.aicontrolcenter.app.ui.icons.OnDevice
import com.aicontrolcenter.app.ui.icons.Sparkle
import com.aicontrolcenter.app.ui.theme.MonoTextStyle
import com.aicontrolcenter.app.ui.theme.Spacing
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.app.ui.theme.appColors
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelSource

/**
 * A model's page: how it is described, the owner's price for it (`graph.ownerModelPrices`; typed from the
 * provider's price page, never looked up), "Speed and reliability" (`graph.usageReports`), which links to
 * [onOpenUsage] (Activity › Usage and cost), and the cheaper model for helpers.
 */
@Composable
fun ModelEditorRoute(modelId: ModelId, onBack: () -> Unit, onOpenUsage: () -> Unit = {}) {
    val graph = LocalAppGraph.current
    val viewModel = viewModel {
        ModelEditorViewModel(
            modelId, graph.registryDirectory, graph.providerManager, graph.agentManager,
            prices = graph.modelPrices, ownerPrices = graph.ownerModelPrices, usageReports = graph.usageReports,
            clock = graph.clock, zone = graph.zone,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    ModelEditorScreen(state, viewModel, onBack, onOpenUsage)
}

@Composable
internal fun ModelEditorScreen(state: ModelEditorUiState, viewModel: ModelEditorViewModel, onBack: () -> Unit, onOpenUsage: () -> Unit = {}) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = state.hasUnsavedChanges) { confirmDiscard = true }
    ScreenScaffold(
        title = state.draft.displayName.ifBlank { "Model" },
        onBack = { if (state.hasUnsavedChanges) confirmDiscard = true else onBack() },
        actions = {
            if (!state.isLoading && !state.notFound && !state.isSimulator) {
                TextButton(onClick = viewModel::save, enabled = state.isDirty && !state.isSaving) { Text("Save") }
            }
        },
        message = state.message,
        onMessageShown = viewModel::messageShown,
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.notFound -> EmptyState(
                icon = AppIcons.ModelChip,
                message = "This model is no longer registered.",
                actionLabel = "Go back",
                onAction = onBack,
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .imePadding()
                    .readableWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.screen, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                ModelHeader(state, viewModel::setEnabled, viewModel::openUseForAgents)
                if (state.isSimulator) {
                    InfoBanner(
                        title = SIMULATOR_LABEL,
                        text = "It produces labelled, scripted replies and has no ability to rate, so its description cannot be edited.",
                        tone = StatusTone.ATTENTION,
                        icon = AppIcons.Sparkle,
                    )
                } else {
                    state.cheaper?.let { CheaperHelperCard(it, viewModel::openUseForHelpers) }
                    if (state.locality == Locality.ON_DEVICE) {
                        // A model on this phone costs nothing: no price fields, so it is never charged anywhere (D3A-10).
                        InfoBanner(
                            title = "Free on this phone",
                            text = "Models on this phone cost nothing; no price is needed. The daily limits do not count them.",
                            tone = StatusTone.READY,
                            icon = AppIcons.OnDevice,
                        )
                    } else if (state.price.available) {
                        PriceEditor(state, viewModel)
                    }
                    state.speed?.let { SpeedCard(it, onOpenUsage) }
                    DescriptionEditor(state, viewModel)
                    CapabilitiesEditor(state, viewModel)
                    Button(
                        onClick = viewModel::save,
                        enabled = state.isDirty && !state.isSaving,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Save changes") }
                    Column {
                        SectionHeader("Manage")
                        AppCard {
                            OutlinedButton(
                                onClick = { confirmRemove = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                                Text("Remove model", modifier = Modifier.padding(start = 8.dp))
                            }
                            if (state.source == ModelSource.PROVIDER_LISTING) {
                                Text(
                                    "The provider still lists it, so the next refresh adds it again. Disable it to keep it out of use.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = Spacing.sm),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove ${state.draft.displayName}?",
            message = if (state.agentsUsing.isEmpty()) {
                "No agent uses it. It is removed from the registry."
            } else {
                "${state.agentsUsing.joinToString(", ")} use it and will need another model. It is removed from the registry."
            },
            confirmLabel = "Remove",
            destructive = true,
            icon = AppIcons.Delete,
            onConfirm = {
                confirmRemove = false
                viewModel.remove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
    state.useForAgents?.let { open ->
        UseForAgentsDialog(
            modelName = state.draft.displayName.ifBlank { state.wireName },
            state = open,
            onToggle = viewModel::toggleAgentChoice,
            onConfirm = viewModel::useForChosenAgents,
            onDismiss = viewModel::dismissUseForAgents,
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            message = if (state.price.isDirty && !state.isDirty) "The price you typed has not been saved." else "Your edits to this model have not been saved.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelHeader(state: ModelEditorUiState, onSetEnabled: (Boolean) -> Unit, onUseForAgents: () -> Unit) {
    AppCard {
        Text(state.wireName, style = MonoTextStyle, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "From ${state.providerName} · ${state.source.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        FlowRow(
            modifier = Modifier.padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip(state.status.text, state.status.tone)
            state.locality?.let { TintedChip(it.label, appColors.locality(it), icon = it.icon) }
        }
        Text(
            if (state.agentsUsing.isEmpty()) "No agent uses it." else "Used by ${state.agentsUsing.joinToString(", ")}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        OutlinedButton(
            onClick = onUseForAgents,
            enabled = state.agentCount > 0,
            modifier = Modifier.padding(top = Spacing.sm).heightIn(min = 48.dp),
        ) {
            Icon(AppIcons.Agent, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text("Use for agents…", modifier = Modifier.padding(start = 8.dp))
        }
        RowDivider(Modifier.padding(top = Spacing.md))
        SwitchRow(
            title = "Enabled",
            description = if (state.enabled) "Agents can use it." else "Disabled: agents that use it cannot run on it.",
            checked = state.enabled,
            onCheckedChange = onSetEnabled,
        )
    }
}

@Composable
private fun DescriptionEditor(state: ModelEditorUiState, viewModel: ModelEditorViewModel) {
    Column {
        SectionHeader("Description")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                AppTextField(
                    state = viewModel.fields.displayName,
                    label = "Display name",
                    errorText = state.nameError,
                )
                AppTextField(
                    state = viewModel.fields.notes,
                    label = "Notes",
                    placeholder = "What it is good at, what it costs…",
                    singleLine = false,
                    minLines = 2,
                )
            }
        }
    }
}

@Composable
private fun CapabilitiesEditor(state: ModelEditorUiState, viewModel: ModelEditorViewModel) {
    val caps = state.draft.capabilities
    Column {
        SectionHeader("Capabilities")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (state.source == ModelSource.PROVIDER_LISTING) {
                    InfoBanner(text = "Once you save a change here, your description stands: refreshing the list no longer updates this model.")
                }
                SwitchRow(
                    title = "Tool calling",
                    description = "Native function calling. Without it, tools are offered through a written protocol.",
                    checked = caps.toolCalling,
                    onCheckedChange = { on -> viewModel.setCapabilities { it.copy(toolCalling = on) } },
                )
                RowDivider()
                SwitchRow(
                    title = testLabel("Vision"),
                    description = "Accepts images.",
                    checked = caps.vision,
                    onCheckedChange = { on -> viewModel.setCapabilities { it.copy(vision = on) } },
                )
                RowDivider()
                TierPicker(
                    "Reasoning",
                    "Agents on a stronger model are picked first for planning, comparing and deciding, and the New objective form warns when the Director's model is Basic.",
                    caps.reasoning,
                    viewModel::setReasoning,
                )
                TierPicker("Coding", "Agents on a stronger model are picked first for tasks about code, scripts and programs.", caps.coding, viewModel::setCoding)
                RowDivider()
                val digits = remember { digitsOnly(ModelTextFields.MAX_DIGITS) }
                AppTextField(
                    state = viewModel.fields.contextWindow,
                    label = "Context window (tokens)",
                    placeholder = "Unknown",
                    supportingText = "How much conversation fits. Used to refuse a conversation that has grown too long.",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    inputTransformation = digits,
                )
                AppTextField(
                    state = viewModel.fields.maxOutput,
                    label = "Longest reply (tokens)",
                    placeholder = "Unknown",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    inputTransformation = digits,
                )
            }
        }
    }
}

@Composable
private fun TierPicker(title: String, description: String, selected: CapabilityTier, onSelect: (CapabilityTier) -> Unit) {
    Column(Modifier.padding(vertical = Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            CapabilityTier.entries.forEach { tier ->
                FilterChip(
                    selected = tier == selected,
                    onClick = { onSelect(tier) },
                    label = { Text(tier.name.lowercase().replaceFirstChar(Char::uppercase)) },
                )
            }
        }
    }
}

/**
 * The owner's price for this model. Typed from the provider's price page, per million tokens; the app never looks
 * prices up (rule 29). Saving it is separate from the description, so a listed model stays updated by its provider.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriceEditor(state: ModelEditorUiState, viewModel: ModelEditorViewModel) {
    val price = state.price
    val fields = viewModel.priceFields
    val amount = remember { amountOnly() }
    val currencyChars = remember { atMostChars(ModelPrice.MAX_CURRENCY_CHARS) }
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Column {
        SectionHeader("Price")
        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "You enter these from your provider's price page; the app never looks them up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(TOKENS_EXPLAINED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppTextField(
                    state = fields.input,
                    label = "Input, per million tokens",
                    placeholder = "For example 3",
                    errorText = price.inputError,
                    keyboardOptions = numbers,
                    inputTransformation = amount,
                )
                AppTextField(
                    state = fields.output,
                    label = "Output, per million tokens",
                    placeholder = "For example 15",
                    errorText = price.outputError,
                    keyboardOptions = numbers,
                    inputTransformation = amount,
                )
                AppTextField(
                    state = fields.cachedInput,
                    label = "Cached input, per million tokens",
                    placeholder = "Same as input",
                    supportingText = "Some providers charge less for text they have seen recently. Leave it empty if yours does not say.",
                    errorText = price.cachedError,
                    keyboardOptions = numbers,
                    inputTransformation = amount,
                )
                AppTextField(
                    state = fields.currency,
                    label = "Currency",
                    supportingText = "For example US$ or €.",
                    inputTransformation = currencyChars,
                )
                price.error?.let { InfoBanner(text = it, tone = StatusTone.ATTENTION) }
                price.stored?.takeIf { !price.isDirty }?.let { stored ->
                    Text(typicalCostLine(stored), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } ?: run {
                    if (price.stored == null && !price.isDirty) {
                        Text(
                            "Without a price, this model's tokens are counted but no cost is shown, and the daily money limit does not count it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Button(onClick = viewModel::savePrice, enabled = price.isDirty && !price.isSaving, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Save price")
                    }
                    if (price.isDirty && price.stored != null) {
                        TextButton(onClick = viewModel::discardPriceChanges, enabled = !price.isSaving, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Undo changes")
                        }
                    }
                    if (price.stored != null) {
                        TextButton(onClick = viewModel::removePrice, enabled = !price.isSaving, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Remove price")
                        }
                    }
                }
            }
        }
    }
}

/** How fast and reliable the model has been lately, in words, with the way to the Usage and cost tab. */
@Composable
private fun SpeedCard(speed: SpeedUi, onOpenUsage: () -> Unit) {
    Column {
        SectionHeader("Speed and reliability")
        AppCard {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                speed.lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            TextButton(onClick = onOpenUsage, modifier = Modifier.padding(top = Spacing.xs).heightIn(min = 48.dp)) {
                Icon(AppIcons.Insights, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("See usage and cost", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** The cheaper model that can use tools, for helper agents (Tier B), offered through "Use for agents…". */
@Composable
private fun CheaperHelperCard(cheaper: CheaperHelperUi, onUseForHelpers: () -> Unit) {
    AppCard(borderColor = appColors.ready.copy(alpha = 0.45f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Top) {
            Icon(AppIcons.Insights, contentDescription = null, tint = appColors.ready, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(cheaper.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(cheaper.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (cheaper.isThisModel) {
            OutlinedButton(onClick = onUseForHelpers, modifier = Modifier.padding(top = Spacing.sm).heightIn(min = 48.dp)) {
                Icon(AppIcons.Agent, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text("Use for helpers…", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * Chooses the agents that should run on this model: every agent, the Director first, all ticked
 * to begin with, each with the model it uses now. A model they could not run on is still
 * offered, with the reason, as the agent editor does.
 */
@Composable
private fun UseForAgentsDialog(
    modelName: String,
    state: UseForAgentsUi,
    onToggle: (AgentId) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Agent, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
        title = { Text("Use $modelName for…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "It becomes the primary model of each agent you tick. Their fallback models are kept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.warning?.let {
                    InfoBanner(
                        title = it.title,
                        text = it.text,
                        tone = if (it.blocksRunning) StatusTone.UNAVAILABLE else StatusTone.ATTENTION,
                    )
                }
                state.tradeOff?.let { InfoBanner(title = "Cheaper can mean weaker", text = it, tone = StatusTone.ATTENTION) }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(state.choices, key = { it.id.value }) { choice ->
                        AgentChoiceRow(
                            choice,
                            checked = choice.id in state.selected,
                            enabled = !state.isAssigning && !choice.alreadyUsesThis,
                            onToggle = { onToggle(choice.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !state.isAssigning && state.selected.isNotEmpty()) {
                Text(
                    when (val count = state.changeCount) {
                        0 -> "Done"
                        1 -> "Use for 1 agent"
                        else -> "Use for $count agents"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.isAssigning) { Text("Cancel") } },
    )
}

@Composable
private fun AgentChoiceRow(choice: AgentChoiceUi, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 56.dp)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        AgentAvatar(choice.name, choice.colorIndex, size = 32.dp)
        Column(Modifier.weight(1f)) {
            Text(
                choice.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (choice.isDirector) "Designated Director · ${choice.currentModel}" else choice.currentModel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
