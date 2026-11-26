package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.SIMULATOR_LABEL
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelDescriptor
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.usage.ModelPrice
import com.aicontrolcenter.core.usage.ModelPrices
import com.aicontrolcenter.core.usage.OwnerModelPrices
import com.aicontrolcenter.core.usage.UsageRange
import com.aicontrolcenter.core.usage.UsageReports
import com.aicontrolcenter.core.usage.UsageTotals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The owner's description of a model: what can be edited about it. */
@Immutable
data class ModelDescriptionDraft(
    val displayName: String = "",
    val notes: String = "",
    val capabilities: ModelCapabilities = ModelCapabilities(),
    /** The context window as typed; empty means unknown. */
    val contextWindowText: String = "",
    val maxOutputText: String = "",
)

@Immutable
data class ModelEditorUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val wireName: String = "",
    val providerId: ProviderId? = null,
    val providerName: String = "",
    val locality: Locality? = null,
    val status: StatusLabel = StatusLabel("Not checked", StatusTone.DISABLED),
    val source: ModelSource = ModelSource.MANUAL,
    val enabled: Boolean = true,
    val isSimulator: Boolean = false,
    /** Agents that use this model as their primary or a fallback, by name. */
    val agentsUsing: List<String> = emptyList(),
    /** How many agents exist; "Use for agents…" needs at least one. */
    val agentCount: Int = 0,
    /** The open "Use for agents…" chooser, or null. */
    val useForAgents: UseForAgentsUi? = null,
    val draft: ModelDescriptionDraft = ModelDescriptionDraft(),
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val nameError: String? = null,
    /** Set once after the model was removed here; the route leaves. */
    val removed: Boolean = false,
    val message: UserMessage? = null,
    /** The owner's price for this model (Phase 6, rule 29): typed from the provider's price page, never looked up. */
    val price: PriceUi = PriceUi(),
    /** How fast and reliable the model has been lately; null until read. */
    val speed: SpeedUi? = null,
    /** A cheaper model that can use tools, for helper agents (Tier B); null when there is nothing to suggest. */
    val cheaper: CheaperHelperUi? = null,
) {
    /** Unsaved edits of the description or of the price. */
    val hasUnsavedChanges: Boolean get() = isDirty || price.isDirty
}

/** The price section: the stored price and what the owner has typed. */
@Immutable
data class PriceUi(
    /** False when prices cannot be stored here (no owner port); the section is then not shown. */
    val available: Boolean = false,
    val stored: ModelPrice? = null,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val inputError: String? = null,
    val outputError: String? = null,
    val cachedError: String? = null,
    /** A problem with the price as a whole (too high, no currency). */
    val error: String? = null,
)

/**
 * The model editor's text fields. The fields read these and the keyboard writes them directly, so
 * every key lands in order; the draft follows them and never writes back while the owner types.
 * Filled when the model loads, and again only while the owner has changed nothing.
 */
class ModelTextFields {
    val displayName = TextFieldState()
    val notes = TextFieldState()

    /** Digits only; empty means unknown. */
    val contextWindow = TextFieldState()
    val maxOutput = TextFieldState()

    internal fun fill(draft: ModelDescriptionDraft) {
        displayName.putText(draft.displayName)
        notes.putText(draft.notes)
        contextWindow.putText(draft.contextWindowText)
        maxOutput.putText(draft.maxOutputText)
    }

    internal fun read() = Texts(displayName.text.toString(), notes.text.toString(), contextWindow.text.toString(), maxOutput.text.toString())

    /** Puts into the fields the texts [draft] changed from [before] (a change made from code, not typed). */
    internal fun showChanges(before: ModelDescriptionDraft, draft: ModelDescriptionDraft) {
        if (draft.displayName != before.displayName) displayName.putText(draft.displayName)
        if (draft.notes != before.notes) notes.putText(draft.notes)
        if (draft.contextWindowText != before.contextWindowText) contextWindow.putText(draft.contextWindowText)
        if (draft.maxOutputText != before.maxOutputText) maxOutput.putText(draft.maxOutputText)
    }

    internal data class Texts(val displayName: String, val notes: String, val contextWindow: String, val maxOutput: String) {
        /** [draft] with these texts; the number fields keep their digits, as the fields do. */
        fun applyTo(draft: ModelDescriptionDraft) = draft.copy(
            displayName = displayName,
            notes = notes,
            contextWindowText = contextWindow.filter(Char::isDigit).take(MAX_DIGITS),
            maxOutputText = maxOutput.filter(Char::isDigit).take(MAX_DIGITS),
        )
    }

    internal companion object {
        const val MAX_DIGITS = 8
    }
}

/** An agent offered by "Use for agents…", with the model it uses now. */
@Immutable
data class AgentChoiceUi(
    val id: AgentId,
    val name: String,
    val colorIndex: Int,
    val isDirector: Boolean,
    /** "Now: Llama 3.2 3B", "Now: no model", or "Already uses this model". */
    val currentModel: String,
    val alreadyUsesThis: Boolean,
)

/**
 * What makes giving this model to agents worth a second look, worded like the agent editor's
 * model warnings. [blocksRunning] is true when agents given it cannot run on it right now.
 */
@Immutable
data class ModelUseWarning(val title: String, val text: String, val blocksRunning: Boolean)

/** Giving this model to several agents at once: every agent, the Director first, all chosen to begin with. */
@Immutable
data class UseForAgentsUi(
    val choices: List<AgentChoiceUi>,
    val selected: Set<AgentId>,
    val warning: ModelUseWarning? = null,
    val isAssigning: Boolean = false,
    /** From the cheaper-model suggestion: what the helpers would trade away in quality (D3A-10); null otherwise. */
    val tradeOff: String? = null,
) {
    /** Chosen agents that do not use this model as their primary one yet. */
    val changeCount: Int get() = choices.count { it.id in selected && !it.alreadyUsesThis }
}

/**
 * Edits how a model is described (display name, capabilities, notes), switches it on and off,
 * and removes it. Changes go through [ProviderManager]; a listed model whose description is
 * edited keeps the owner's version from then on (the manager records it as entered by hand).
 *
 * "Use for agents…" makes it the primary model of several agents at once through
 * [AgentManager.assignModel]. Like the agent editor, it lets the owner choose a model that
 * cannot run right now (they may be fixing it) and says plainly why, instead of refusing.
 */
class ModelEditorViewModel(
    private val modelId: ModelId,
    private val directory: RegistryDirectory,
    private val providerManager: ProviderManager,
    private val agentManager: AgentManager,
    private val prices: ModelPrices = ModelPrices.None,
    /** Where the owner's price is stored; null hides the price section. */
    private val ownerPrices: OwnerModelPrices? = null,
    private val usageReports: UsageReports = UsageReports.None,
    private val clock: Clock = WallClock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    private val state = MutableStateFlow(ModelEditorUiState())
    val uiState: StateFlow<ModelEditorUiState> = state.asStateFlow()
    private var original: ModelDescriptionDraft? = null
    private var messageCounter = 0L
    private var latest: RegistrySnapshot? = null

    /** What is being typed into the editor's text fields. */
    val fields = ModelTextFields()

    /** What is being typed into the price fields. */
    val priceFields = ModelPriceFields()

    init {
        state.update { it.copy(price = it.price.copy(available = ownerPrices != null)) }
        // The price: the stored one fills the fields while the owner has changed nothing; what is typed marks it changed.
        viewModelScope.launch {
            prices.observeAll().collect { all ->
                val stored = all.firstOrNull { it.modelId == modelId }
                val current = state.value.price
                if (!current.isDirty) priceFields.fill(stored)
                state.update { it.copy(price = it.price.copy(stored = stored, isDirty = !PriceTexts.of(stored).sameAs(priceFields.read()))) }
            }
        }
        viewModelScope.launch {
            snapshotFlow { priceFields.read() }.collect { typed ->
                state.update { it.copy(price = it.price.copy(isDirty = !PriceTexts.of(it.price.stored).sameAs(typed))) }
            }
        }
        // The cheaper model for helpers follows the registry and every price.
        viewModelScope.launch {
            combine(directory.observe(), prices.observeAll()) { snapshot, all -> snapshot to all.associateBy { it.modelId } }.collect { (snapshot, byModel) ->
                val model = snapshot.modelsById[modelId]
                state.update { it.copy(cheaper = model?.takeUnless { m -> m.isSimulator }?.let { m -> snapshot.cheaperHelper(m, byModel) }) }
            }
        }
        // Speed and reliability over the last days, again whenever a call of any model is recorded.
        viewModelScope.launch {
            usageReports.observeByModel(UsageRange.lastDays(SPEED_DAYS, clock.now(), zone())).collect { rows ->
                val mine = rows.filter { it.modelId == modelId }.map { it.totals }.takeIf { it.isNotEmpty() }?.reduce(UsageTotals::plus)
                state.update { it.copy(speed = speedOf(mine)) }
            }
        }
        // The draft follows what is typed. (Before the model has loaded, edits are ignored.)
        viewModelScope.launch { snapshotFlow { fields.read() }.collect { edit { it } } }
        viewModelScope.launch {
            directory.observe().collect { snapshot ->
                latest = snapshot
                val model = snapshot.modelsById[modelId]
                if (model == null) {
                    state.update { it.copy(isLoading = false, notFound = !it.removed, useForAgents = null) }
                    return@collect
                }
                val provider = snapshot.provider(model)
                val stored = ModelDescriptionDraft(
                    displayName = model.displayName,
                    notes = model.notes,
                    capabilities = model.capabilities,
                    contextWindowText = model.capabilities.contextWindow?.toString().orEmpty(),
                    maxOutputText = model.capabilities.maxOutputTokens?.toString().orEmpty(),
                )
                // The owner's unsaved edits win over a registry change made elsewhere. Whether there
                // are any is read from the fields themselves, which may be a key ahead of the draft.
                val before = state.value
                val typed = if (before.isLoading || before.notFound) null else fields.read().applyTo(before.draft)
                val dirty = typed != null && typed != original
                if (!dirty) {
                    original = stored
                    fields.fill(stored)
                }
                state.update { current ->
                    val draft = if (dirty && typed != null) typed else stored
                    current.copy(
                        isLoading = false,
                        notFound = false,
                        wireName = model.modelName,
                        providerId = model.providerId,
                        providerName = provider?.name ?: "Unregistered provider",
                        locality = provider?.effectiveLocality,
                        status = if (model.enabled) model.status.toStatusLabel() else StatusLabel("Disabled", StatusTone.DISABLED),
                        source = model.source,
                        enabled = model.enabled,
                        isSimulator = model.isSimulator,
                        agentsUsing = snapshot.agents
                            .filter { it.profile.modelId == modelId || modelId in it.profile.fallbackModelIds }
                            .map { it.profile.name },
                        agentCount = snapshot.agents.size,
                        // An open chooser follows the registry: new agents join unchosen, deleted ones leave.
                        useForAgents = current.useForAgents?.let { open ->
                            val choices = snapshot.agentChoices()
                            open.copy(
                                choices = choices,
                                selected = open.selected.filterTo(mutableSetOf()) { id -> choices.any { it.id == id } },
                                warning = snapshot.useWarning(model),
                            )
                        },
                        draft = draft,
                        isDirty = dirty,
                    )
                }
            }
        }
    }

    /**
     * Applies a change to the draft. It starts from what the text fields hold now (they may be a
     * key ahead of the draft); a text it changes goes into its field.
     */
    fun edit(change: (ModelDescriptionDraft) -> ModelDescriptionDraft) {
        val current = state.value
        if (current.isLoading || current.notFound || current.isSimulator) return
        val typed = fields.read().applyTo(current.draft)
        val draft = change(typed)
        fields.showChanges(typed, draft)
        state.update {
            it.copy(
                draft = draft,
                isDirty = draft != original,
                nameError = if (draft.displayName.isBlank()) "Give the model a display name." else null,
            )
        }
    }

    // Typing goes into [fields] directly; these replace a field's text from code.

    fun setDisplayName(value: String) = edit { it.copy(displayName = value) }

    fun setNotes(value: String) = edit { it.copy(notes = value) }

    fun setCapabilities(change: (ModelCapabilities) -> ModelCapabilities) = edit { it.copy(capabilities = change(it.capabilities)) }

    fun setReasoning(tier: CapabilityTier) = setCapabilities { it.copy(reasoning = tier) }

    fun setCoding(tier: CapabilityTier) = setCapabilities { it.copy(coding = tier) }

    fun setContextWindowText(text: String) = edit { it.copy(contextWindowText = text.filter(Char::isDigit).take(ModelTextFields.MAX_DIGITS)) }

    fun setMaxOutputText(text: String) = edit { it.copy(maxOutputText = text.filter(Char::isDigit).take(ModelTextFields.MAX_DIGITS)) }

    fun save() {
        // Exactly what the fields hold now, even if the owner taps Save straight after the last key.
        edit { it }
        val current = state.value
        if (current.isSaving || current.isLoading || current.notFound || current.isSimulator) return
        val draft = current.draft
        if (draft.displayName.isBlank()) {
            state.update { it.copy(nameError = "Give the model a display name.") }
            return
        }
        state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val capabilities = draft.capabilities.copy(
                contextWindow = draft.contextWindowText.toIntOrNull()?.takeIf { it > 0 },
                maxOutputTokens = draft.maxOutputText.toIntOrNull()?.takeIf { it > 0 },
            )
            try {
                providerManager.updateModel(modelId, draft.displayName, capabilities, draft.notes)
                original = draft
                state.update { it.copy(isSaving = false, isDirty = false, message = message("Saved.")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                state.update { it.copy(isSaving = false, message = message(failure.providerFailureText("Saving"))) }
            }
        }
    }

    /**
     * Stores the price exactly as the fields hold it now. The owner types it from the provider's price page; the app
     * never looks it up (rule 29). Saving a price leaves the model's description as it is.
     */
    fun savePrice() {
        val port = ownerPrices ?: return
        val current = state.value
        if (current.isLoading || current.notFound || current.isSimulator || current.price.isSaving) return
        when (val parsed = priceFields.read().toPrice(modelId)) {
            is PriceParse.Invalid -> state.update {
                it.copy(price = it.price.copy(inputError = parsed.inputError, outputError = parsed.outputError, cachedError = parsed.cachedError, error = parsed.general))
            }
            is PriceParse.Valid -> {
                state.update { it.copy(price = it.price.copy(isSaving = true, inputError = null, outputError = null, cachedError = null, error = null)) }
                viewModelScope.launch {
                    val text = try {
                        when (val result = port.set(parsed.price)) {
                            is OwnerResult.Done -> {
                                priceFields.fill(result.value)
                                state.update { it.copy(price = it.price.copy(stored = result.value, isDirty = false)) }
                                "Price saved. Costs with this model are now estimated from it."
                            }
                            is OwnerResult.Refused -> result.reason
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        "The price could not be saved: ${failure.message ?: "storage error"}"
                    }
                    state.update { it.copy(price = it.price.copy(isSaving = false), message = message(text)) }
                }
            }
        }
    }

    /** Removes the price: this model's costs are no longer estimated (its tokens are still counted). */
    fun removePrice() {
        val port = ownerPrices ?: return
        if (state.value.price.isSaving) return
        state.update { it.copy(price = it.price.copy(isSaving = true)) }
        viewModelScope.launch {
            val text = try {
                port.remove(modelId)
                priceFields.fill(null)
                state.update { it.copy(price = it.price.copy(stored = null, isDirty = false, inputError = null, outputError = null, cachedError = null, error = null)) }
                "Price removed. Costs with this model are no longer estimated."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "The price could not be removed: ${failure.message ?: "storage error"}"
            }
            state.update { it.copy(price = it.price.copy(isSaving = false), message = message(text)) }
        }
    }

    /** Puts back the stored price, dropping what was typed. */
    fun discardPriceChanges() {
        priceFields.fill(state.value.price.stored)
        state.update { it.copy(price = it.price.copy(isDirty = false, inputError = null, outputError = null, cachedError = null, error = null)) }
    }

    fun setEnabled(enabled: Boolean) = act("Changing the model") { providerManager.setModelEnabled(modelId, enabled) }

    fun remove() = act("Removing the model") {
        providerManager.removeModel(modelId)
        state.update { it.copy(removed = true) }
    }

    /** Opens "Use for agents…" with every agent chosen. */
    fun openUseForAgents() {
        val snapshot = latest ?: return
        val model = snapshot.modelsById[modelId] ?: return
        if (state.value.useForAgents != null) return
        val choices = snapshot.agentChoices()
        if (choices.isEmpty()) return
        state.update {
            it.copy(useForAgents = UseForAgentsUi(choices, choices.map { c -> c.id }.toSet(), snapshot.useWarning(model)))
        }
    }

    /**
     * "Use for helpers…" from the cheaper-model suggestion: the same chooser as "Use for agents…", with only the
     * helpers on dearer models ticked, so the Director keeps its model unless the owner ticks it.
     */
    fun openUseForHelpers() {
        val snapshot = latest ?: return
        val model = snapshot.modelsById[modelId] ?: return
        val cheaper = state.value.cheaper?.takeIf { it.isThisModel } ?: return
        if (state.value.useForAgents != null) return
        val choices = snapshot.agentChoices()
        if (cheaper.helpers.none { id -> choices.any { it.id == id } }) return
        // Rated lower at reasoning than what the helpers use now: nobody is ticked, and the chooser says why (D3A-10).
        val selected = cheaper.preselected.filterTo(mutableSetOf()) { id -> choices.any { it.id == id } }
        state.update { it.copy(useForAgents = UseForAgentsUi(choices, selected, snapshot.useWarning(model), tradeOff = cheaper.tradeOff)) }
    }

    fun toggleAgentChoice(id: AgentId) = state.update { current ->
        val open = current.useForAgents ?: return@update current
        // An agent that already runs on it stays ticked: unticking would suggest taking the model away.
        if (open.isAssigning || open.choices.any { it.id == id && it.alreadyUsesThis }) return@update current
        current.copy(useForAgents = open.copy(selected = if (id in open.selected) open.selected - id else open.selected + id))
    }

    fun dismissUseForAgents() = state.update { if (it.useForAgents?.isAssigning == true) it else it.copy(useForAgents = null) }

    /** Makes this model the primary model of every chosen agent, and says which agents changed. */
    fun useForChosenAgents() {
        val open = state.value.useForAgents ?: return
        if (open.isAssigning || open.selected.isEmpty()) return
        state.update { it.copy(useForAgents = open.copy(isAssigning = true)) }
        viewModelScope.launch {
            val changed = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (choice in open.choices.filter { it.id in open.selected && !it.alreadyUsesThis }) {
                try {
                    agentManager.assignModel(choice.id, modelId)
                    changed += choice.name
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    failed += "${choice.name} (${failure.message ?: "storage error"})"
                }
            }
            val name = state.value.draft.displayName.ifBlank { "This model" }
            val text = buildString {
                if (changed.isEmpty()) {
                    append(if (failed.isEmpty()) "Every chosen agent already uses $name." else "No agent was changed.")
                } else {
                    append("$name is now the model of ${listNames(changed)}.")
                    val warning = latest?.let { s -> s.modelsById[modelId]?.let(s::useWarning) }
                    if (warning?.blocksRunning == true) {
                        append(" ${if (changed.size == 1) "It" else "They"} cannot run on it yet (${warning.title}).")
                    }
                }
                if (failed.isNotEmpty()) append(" Could not change ${listNames(failed)}.")
            }
            state.update { it.copy(useForAgents = null, message = message(text)) }
        }
    }

    fun messageShown() = state.update { it.copy(message = null) }

    /** Every agent, the designated Director first and then by name, with the model it uses now. */
    private fun RegistrySnapshot.agentChoices(): List<AgentChoiceUi> =
        agents.sortedWith(compareBy({ !it.isDesignatedDirector }, { it.profile.name.lowercase() })).map { entry ->
            val current = entry.profile.modelId
            AgentChoiceUi(
                id = entry.profile.id,
                name = entry.profile.name,
                colorIndex = entry.profile.colorIndex,
                isDirector = entry.isDesignatedDirector,
                currentModel = when (current) {
                    modelId -> "Already uses this model"
                    null -> "Now: no model"
                    else -> "Now: ${modelsById[current]?.displayName ?: "a model that is no longer registered"}"
                },
                alreadyUsesThis = current == modelId,
            )
        }

    private fun act(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                state.update { it.copy(message = message(failure.providerFailureText(what))) }
            }
        }
    }

    private fun message(text: String) = UserMessage(text, ++messageCounter)
}

/**
 * Why agents given [model] could not run on it now, or what choosing it means for the owner's
 * data. Follows the registry's own availability rules (see `resolveAvailability`) and the
 * agent editor's warnings: a problem is reported, never silently skipped.
 */
internal fun RegistrySnapshot.useWarning(model: ModelDescriptor): ModelUseWarning? {
    val provider = provider(model)
    fun blocked(title: String, text: String) = ModelUseWarning(title, text, blocksRunning = true)
    return when {
        !model.enabled -> blocked("The model is disabled", "Agents given it cannot run on it until you enable it above.")
        provider == null -> blocked("Its provider is missing", "Its provider is no longer registered, so agents given it cannot run on it.")
        !provider.enabled -> blocked("Provider ${provider.name} is disabled", "Agents given it cannot run on it until you enable the provider.")
        model.status == ModelStatus.UNAVAILABLE ->
            blocked("It is no longer listed", "${provider.name} no longer offers it, so agents given it cannot run on it.")
        settings.localOnlyMode && provider.effectiveLocality.leavesDevice -> blocked(
            "Local-only mode is on",
            "${provider.name} would send data off this phone, so agents given it cannot run on it until you turn " +
                "local-only mode off in Settings.",
        )
        model.isSimulator -> ModelUseWarning(
            SIMULATOR_LABEL,
            "Replies are scripted and labelled as simulated. Use it to try agents, then choose a real model.",
            blocksRunning = false,
        )
        provider.effectiveLocality.leavesDevice -> ModelUseWarning(
            "Leaves this device",
            "What these agents are given, including your messages, is sent to ${provider.name}, " +
                (if (provider.effectiveLocality == Locality.CLOUD) "a service on the internet." else "a machine on your network.") +
                " Local-only mode blocks it.",
            blocksRunning = false,
        )
        else -> null
    }
}

/** "Researcher", "Researcher and Writer", "Director, Researcher and Writer": every name, none left out. */
internal fun listNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names.single()
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}
