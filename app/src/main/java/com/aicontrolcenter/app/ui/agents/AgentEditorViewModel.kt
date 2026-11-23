package com.aicontrolcenter.app.ui.agents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.app.ui.models.isSimulator
import com.aicontrolcenter.core.registry.ModelStatus
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.ToolSpec
import com.aicontrolcenter.core.agents.AgentDraft
import com.aicontrolcenter.core.agents.AgentField
import com.aicontrolcenter.core.agents.AgentManager
import com.aicontrolcenter.core.agents.AgentOperationException
import com.aicontrolcenter.core.agents.AgentValidationException
import com.aicontrolcenter.core.agents.ValidationIssue
import com.aicontrolcenter.core.agents.toDraft
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.registry.effectiveLocality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** A registered model as offered by the model picker. */
@Immutable
data class ModelOptionUi(
    val id: ModelId,
    val name: String,
    val providerName: String,
    val locality: Locality?,
    /** Disabled or unavailable models stay selectable (the owner may be fixing them) but are marked. */
    val note: String? = null,
    val isSimulator: Boolean = false,
)

/** Every registered model as a picker option, with its provider, where prompts go, and what is wrong with it. */
internal fun RegistrySnapshot.modelOptions(): List<ModelOptionUi> = models.map { model ->
    val provider = provider(model)
    ModelOptionUi(
        id = model.id,
        name = model.displayName,
        providerName = provider?.name ?: "Unregistered provider",
        locality = provider?.effectiveLocality,
        note = when {
            !model.enabled -> "Disabled"
            provider == null -> "Provider missing"
            !provider.enabled -> "Provider disabled"
            model.status == ModelStatus.UNAVAILABLE -> "No longer listed"
            else -> null
        },
        isSimulator = model.isSimulator,
    )
}

/** A tool's description is written for the model; the owner gets its first sentence, which says what it does. */
internal fun firstSentence(text: String): String {
    val end = text.indexOf(". ")
    return if (end < 0) text.trim() else text.substring(0, end + 1).trim()
}

/** A registered tool as the editor offers it. */
@Immutable
data class ToolOptionUi(
    val id: ToolId,
    val title: String,
    val description: String,
    val risk: RiskLevel,
    /** True when calling it sends information off the device; such tools are blocked in local-only mode. */
    val sendsDataOffDevice: Boolean,
)

@Immutable
data class AgentEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    /** The agent being edited was deleted elsewhere; there is nothing to edit. */
    val notFound: Boolean = false,
    val draft: AgentDraft = AgentDraft(name = ""),
    /** Problems reported by the framework for the current draft, by field. */
    val issues: Map<AgentField, List<String>> = emptyMap(),
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    val models: List<ModelOptionUi> = emptyList(),
    val tools: List<ToolOptionUi> = emptyList(),
    /** Set once after a successful save; the route navigates and calls back to clear it. */
    val savedAgentId: AgentId? = null,
    val message: UserMessage? = null,
) {
    fun issue(field: AgentField): String? = issues[field]?.joinToString(" ")

    fun model(id: ModelId?): ModelOptionUi? = models.firstOrNull { it.id == id }
}

/**
 * Keeps the draft across process death. The route backs it with the navigation entry's saved
 * state; tests and previews use [None].
 */
interface DraftStore {
    fun read(): String?
    fun write(value: String?)

    object None : DraftStore {
        override fun read(): String? = null
        override fun write(value: String?) = Unit
    }
}

/**
 * Edits one agent draft: a new agent (optionally from a template) or an existing one.
 *
 * Validation is never duplicated here. Every change is sent to [AgentManager.validate] and its
 * issues are shown next to their fields, so the editor can never accept something the manager
 * would refuse, or refuse something it would accept.
 */
class AgentEditorViewModel(
    private val agentManager: AgentManager,
    private val directory: RegistryDirectory,
    private val editingId: AgentId?,
    private val templateId: String?,
    private val draftStore: DraftStore = DraftStore.None,
    tools: List<ToolSpec> = emptyList(),
) : ViewModel() {

    private val state = MutableStateFlow(
        AgentEditorUiState(
            isNew = editingId == null,
            tools = tools.sortedBy { it.title }.map { ToolOptionUi(it.id, it.title, firstSentence(it.description), it.risk, it.sendsDataOffDevice) },
        ),
    )
    val uiState: StateFlow<AgentEditorUiState> = state.asStateFlow()

    private var original: AgentDraft? = null
    private var validation: Job? = null
    private var messageCounter = 0L

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            directory.observe().collect { snapshot -> state.update { it.copy(models = snapshot.modelOptions()) } }
        }
    }

    private suspend fun load() {
        val snapshot = directory.snapshot()
        val base: AgentDraft = if (editingId != null) {
            val entry = snapshot.agentsById[editingId]
            if (entry == null) {
                state.update { it.copy(isLoading = false, notFound = true) }
                return
            }
            entry.profile.toDraft()
        } else {
            val template = agentManager.templates.firstOrNull { it.id == templateId }?.draft
                ?: AgentDraft(name = "New agent")
            template.copy(name = uniqueName(template.name, snapshot.agents.map { it.profile.name }))
        }
        original = base
        val restored = draftStore.read()?.let { runCatching { json.decodeFromString(AgentDraft.serializer(), it) }.getOrNull() }
        val draft = restored ?: base
        state.update { it.copy(isLoading = false, draft = draft, isDirty = draft != base) }
        validate(draft)
    }

    /** Applies an edit to the draft. All field changes go through here. */
    fun edit(transform: (AgentDraft) -> AgentDraft) {
        val current = state.value
        if (current.isLoading || current.notFound) return
        val draft = transform(current.draft)
        if (draft == current.draft) return
        state.update { it.copy(draft = draft, isDirty = draft != original) }
        draftStore.write(json.encodeToString(AgentDraft.serializer(), draft))
        validate(draft)
    }

    fun setName(name: String) = edit { it.copy(name = name) }

    fun addResponsibility(text: String) = edit { draft ->
        val value = text.trim()
        if (value.isEmpty() || draft.responsibilities.any { it.equals(value, ignoreCase = true) }) {
            draft
        } else {
            draft.copy(responsibilities = draft.responsibilities + value)
        }
    }

    fun removeResponsibility(text: String) = edit { it.copy(responsibilities = it.responsibilities - text) }

    /** Skills are stored lower-case, so they are shown that way as soon as they are added. */
    fun addSkill(text: String) = edit { draft ->
        val value = text.trim().lowercase()
        if (value.isEmpty()) draft else draft.copy(skills = draft.skills + value)
    }

    fun removeSkill(text: String) = edit { it.copy(skills = it.skills - text) }

    /** Assigns the primary model. A model cannot be its own fallback, so it leaves that list. */
    fun setModel(id: ModelId?) = edit { it.copy(modelId = id, fallbackModelIds = it.fallbackModelIds.filter { f -> f != id }) }

    fun addFallback(id: ModelId) = edit { draft ->
        if (id == draft.modelId || id in draft.fallbackModelIds) draft else draft.copy(fallbackModelIds = draft.fallbackModelIds + id)
    }

    fun removeFallback(id: ModelId) = edit { it.copy(fallbackModelIds = it.fallbackModelIds - id) }

    /** Switches a tool on or off for this agent. Using it still needs the matching permission. */
    fun setTool(id: ToolId, enabled: Boolean) = edit { if (enabled) it.copy(toolIds = it.toolIds + id) else it.copy(toolIds = it.toolIds - id) }

    /** Null means "use the model's own default". Values are kept to one decimal place. */
    fun setTemperature(value: Double?) = edit {
        it.copy(generation = it.generation.copy(temperature = value?.let { v -> Math.round(v * 10) / 10.0 }))
    }

    /** Accepts what was typed in the token field: digits only, empty for the model's default. */
    fun setMaxOutputTokensText(text: String) = edit {
        val digits = text.filter(Char::isDigit).take(MAX_TOKEN_DIGITS)
        it.copy(generation = it.generation.copy(maxOutputTokens = digits.toIntOrNull()))
    }

    fun save() {
        val current = state.value
        // A second tap while the first save is still being acted on must not save again.
        if (current.isSaving || current.isLoading || current.notFound || current.savedAgentId != null) return
        state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val saved = if (editingId == null) agentManager.create(current.draft) else agentManager.update(editingId, current.draft)
                draftStore.write(null)
                original = current.draft
                state.update { it.copy(isSaving = false, isDirty = false, savedAgentId = saved.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: AgentValidationException) {
                state.update {
                    it.copy(isSaving = false, issues = invalid.issues.byField(), message = message("Fix the marked fields, then save again."))
                }
            } catch (refused: AgentOperationException) {
                state.update { it.copy(isSaving = false, message = message(refused.message ?: "The agent could not be saved.")) }
            } catch (failure: Exception) {
                state.update { it.copy(isSaving = false, message = message("Could not save: ${failure.message ?: "storage error"}")) }
            }
        }
    }

    /** Forgets the stored draft; called when the owner chooses to discard changes. */
    fun discard() = draftStore.write(null)

    fun savedHandled() = state.update { it.copy(savedAgentId = null) }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun validate(draft: AgentDraft) {
        validation?.cancel()
        validation = viewModelScope.launch {
            val issues = try {
                agentManager.validate(draft, editingId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Validation reads the registry. If that fails, saving reports the problem; typing must not crash.
                return@launch
            }
            state.update { if (it.draft == draft) it.copy(issues = issues.byField()) else it }
        }
    }

    private fun message(text: String) = UserMessage(text, ++messageCounter)

    private fun List<ValidationIssue>.byField(): Map<AgentField, List<String>> = groupBy({ it.field }, { it.message })

    companion object {
        private const val MAX_TOKEN_DIGITS = 7
        private const val MAX_NAME_LENGTH = 40
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * A name not used by any existing agent, following the manager's own rule for templates
         * ("Researcher", "Researcher 2", …), so a template opens without an instant name clash.
         */
        internal fun uniqueName(wanted: String, existing: List<String>): String {
            val taken = existing.map { it.lowercase() }.toSet()
            val base = wanted.take(MAX_NAME_LENGTH)
            if (base.lowercase() !in taken) return base
            var n = 2
            while (true) {
                val suffix = " $n"
                val candidate = base.take(MAX_NAME_LENGTH - suffix.length) + suffix
                if (candidate.lowercase() !in taken) return candidate
                n++
            }
        }
    }
}
