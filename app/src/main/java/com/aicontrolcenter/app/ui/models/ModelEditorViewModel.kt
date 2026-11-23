package com.aicontrolcenter.app.ui.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.StatusLabel
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.toStatusLabel
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.core.registry.ModelSource
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.effectiveLocality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val draft: ModelDescriptionDraft = ModelDescriptionDraft(),
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val nameError: String? = null,
    /** Set once after the model was removed here; the route leaves. */
    val removed: Boolean = false,
    val message: UserMessage? = null,
)

/**
 * Edits how a model is described (display name, capabilities, notes), switches it on and off,
 * and removes it. Changes go through [ProviderManager]; a listed model whose description is
 * edited keeps the owner's version from then on (the manager records it as entered by hand).
 */
class ModelEditorViewModel(
    private val modelId: ModelId,
    private val directory: RegistryDirectory,
    private val providerManager: ProviderManager,
) : ViewModel() {

    private val state = MutableStateFlow(ModelEditorUiState())
    val uiState: StateFlow<ModelEditorUiState> = state.asStateFlow()
    private var original: ModelDescriptionDraft? = null
    private var messageCounter = 0L

    init {
        viewModelScope.launch {
            directory.observe().collect { snapshot ->
                val model = snapshot.modelsById[modelId]
                if (model == null) {
                    state.update { it.copy(isLoading = false, notFound = !it.removed) }
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
                state.update { current ->
                    // The owner's unsaved edits win over a registry change made elsewhere.
                    val draft = if (current.isDirty) current.draft else stored
                    if (!current.isDirty) original = stored
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
                        draft = draft,
                    )
                }
            }
        }
    }

    fun edit(change: (ModelDescriptionDraft) -> ModelDescriptionDraft) {
        val current = state.value
        if (current.isLoading || current.notFound || current.isSimulator) return
        val draft = change(current.draft)
        state.update {
            it.copy(
                draft = draft,
                isDirty = draft != original,
                nameError = if (draft.displayName.isBlank()) "Give the model a display name." else null,
            )
        }
    }

    fun setDisplayName(value: String) = edit { it.copy(displayName = value) }

    fun setNotes(value: String) = edit { it.copy(notes = value) }

    fun setCapabilities(change: (ModelCapabilities) -> ModelCapabilities) = edit { it.copy(capabilities = change(it.capabilities)) }

    fun setReasoning(tier: CapabilityTier) = setCapabilities { it.copy(reasoning = tier) }

    fun setCoding(tier: CapabilityTier) = setCapabilities { it.copy(coding = tier) }

    fun setContextWindowText(text: String) = edit { it.copy(contextWindowText = text.filter(Char::isDigit).take(MAX_DIGITS)) }

    fun setMaxOutputText(text: String) = edit { it.copy(maxOutputText = text.filter(Char::isDigit).take(MAX_DIGITS)) }

    fun save() {
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

    fun setEnabled(enabled: Boolean) = act("Changing the model") { providerManager.setModelEnabled(modelId, enabled) }

    fun remove() = act("Removing the model") {
        providerManager.removeModel(modelId)
        state.update { it.copy(removed = true) }
    }

    fun messageShown() = state.update { it.copy(message = null) }

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

    private companion object {
        const val MAX_DIGITS = 8
    }
}
