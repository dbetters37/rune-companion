package com.aicontrolcenter.app.ui.agents

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.core.AgentId
import com.aicontrolcenter.core.ModelId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.app.ui.tools.ToolFamily
import com.aicontrolcenter.app.ui.tools.plainWordsFor
import com.aicontrolcenter.core.ToolId
import com.aicontrolcenter.app.ui.memory.SpaceOptionUi
import com.aicontrolcenter.core.memory.KnowledgeSpace
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import com.aicontrolcenter.core.registry.CapabilityTier
import com.aicontrolcenter.core.registry.ModelCapabilities
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.framework.agents.ToolMenu
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
    /** "Reasoning: Strong · Coding: Good" (D27: agents are picked by these); null when neither is rated. */
    val tiers: String? = null,
)

/** The model's Reasoning and Coding ratings in words, the ones it has; null for the simulator or when neither is rated. */
internal fun tiersOf(capabilities: ModelCapabilities, isSimulator: Boolean): String? {
    if (isSimulator) return null
    fun word(tier: CapabilityTier) = tier.name.lowercase().replaceFirstChar(Char::uppercase)
    val parts = listOfNotNull(
        capabilities.reasoning.takeIf { it != CapabilityTier.UNKNOWN }?.let { "Reasoning: ${word(it)}" },
        capabilities.coding.takeIf { it != CapabilityTier.UNKNOWN }?.let { "Coding: ${word(it)}" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

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
        tiers = tiersOf(model.capabilities, model.isSimulator),
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
    /** True when calling it sends information off the device or hands it to another app; such tools are blocked in local-only mode. */
    val sendsDataOffDevice: Boolean,
    /** Where it is listed, with the other tools of its kind. */
    val family: ToolFamily = ToolFamily.INFORMATION,
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
    /** Every shared knowledge space, by name, for the memory policy's read and write pickers. */
    val spaces: List<SpaceOptionUi> = emptyList(),
    /** Set once after a successful save; the route navigates and calls back to clear it. */
    val savedAgentId: AgentId? = null,
    val message: UserMessage? = null,
    /** About how many tokens the switched-on tools' descriptions add to every request (WP15's estimate). */
    val toolTokens: Int = 0,
) {
    /**
     * What the switched-on tools cost in every request, in plain words; above the menu threshold the agent is offered
     * its tools in groups (`tools_open`), so each request stays small.
     */
    val toolCostLine: String?
        get() = when {
            toolTokens <= 0 -> null
            toolTokens > ToolMenu.THRESHOLD_TOKENS ->
                "These tools would add about ${formatCount(toolTokens.toLong())} tokens to every request. That is many, so they are " +
                    "offered in groups: each request carries only the groups the agent opens, and opening one may take one more step."
            else -> "These tools add about ${formatCount(toolTokens.toLong())} tokens to every request this agent makes."
        }

    fun issue(field: AgentField): String? = issues[field]?.joinToString(" ")

    fun model(id: ModelId?): ModelOptionUi? = models.firstOrNull { it.id == id }

    /** The tools by family, in catalogue order; families without a registered tool are left out. */
    val toolFamilies: List<Pair<ToolFamily, List<ToolOptionUi>>>
        get() = tools.groupBy { it.family }.toSortedMap().toList()
}

/**
 * The editor's text fields. The fields read these and the keyboard writes them directly, so every
 * key lands in order; the draft follows them (see [AgentEditorViewModel]) and never writes back
 * while the owner types. Filled once, when the agent (or a restored draft) has loaded.
 */
class AgentTextFields {
    val name = TextFieldState()
    val description = TextFieldState()
    val instructions = TextFieldState()
    val personality = TextFieldState()

    /** Digits only; empty means the model's own limit. */
    val maxOutputTokens = TextFieldState()

    internal fun fill(draft: AgentDraft) {
        name.putText(draft.name)
        description.putText(draft.description)
        instructions.putText(draft.systemInstructions)
        personality.putText(draft.personality)
        maxOutputTokens.putText(draft.generation.maxOutputTokens?.toString().orEmpty())
    }

    /** Puts into the fields the texts [draft] changed from [before] (a change made from code, not typed). */
    internal fun showChanges(before: AgentDraft, draft: AgentDraft) {
        if (draft.name != before.name) name.putText(draft.name)
        if (draft.description != before.description) description.putText(draft.description)
        if (draft.systemInstructions != before.systemInstructions) instructions.putText(draft.systemInstructions)
        if (draft.personality != before.personality) personality.putText(draft.personality)
        val tokens = draft.generation.maxOutputTokens
        if (tokens != before.generation.maxOutputTokens) maxOutputTokens.putText(tokens?.toString().orEmpty())
    }

    internal fun read() = Texts(
        name.text.toString(), description.text.toString(), instructions.text.toString(), personality.text.toString(),
        maxOutputTokens.text.toString(),
    )

    internal data class Texts(val name: String, val description: String, val instructions: String, val personality: String, val maxOutputTokens: String) {
        /** [draft] with these texts; the token count keeps its digits, as the field does. */
        fun applyTo(draft: AgentDraft): AgentDraft = draft.copy(
            name = name,
            description = description,
            systemInstructions = instructions,
            personality = personality,
            generation = draft.generation.copy(maxOutputTokens = maxOutputTokens.filter(Char::isDigit).take(MAX_TOKEN_DIGITS).toIntOrNull()),
        )
    }

    internal companion object {
        const val MAX_TOKEN_DIGITS = 7
    }
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
    spaces: Flow<List<KnowledgeSpace>> = flowOf(emptyList()),
) : ViewModel() {

    private val state = MutableStateFlow(
        AgentEditorUiState(
            isNew = editingId == null,
            tools = tools.sortedBy { it.title }.map { ToolOptionUi(it.id, it.title, plainWordsFor(it), it.risk, it.sendsDataOffDevice, ToolFamily.of(it)) },
        ),
    )

    /** Each tool's definition as a request carries it, for the token line. */
    private val definitions = tools.associate { it.id to ToolMenu.definition(it) }

    private fun tokensFor(draft: AgentDraft): Int =
        if (draft.toolIds.isEmpty()) 0 else ToolMenu.estimatedTokens(draft.toolIds.sortedBy { it.value }.mapNotNull(definitions::get))
    val uiState: StateFlow<AgentEditorUiState> = state.asStateFlow()

    private var original: AgentDraft? = null
    private var validation: Job? = null
    private var messageCounter = 0L

    /** What is being typed into the editor's text fields. */
    val fields = AgentTextFields()

    init {
        viewModelScope.launch { load() }
        // The draft follows what is typed. (Before the agent has loaded, edits are ignored, and the
        // fields are filled from the draft once it has.)
        viewModelScope.launch { snapshotFlow { fields.read() }.collect { edit { it } } }
        viewModelScope.launch {
            directory.observe().collect { snapshot -> state.update { it.copy(models = snapshot.modelOptions()) } }
        }
        viewModelScope.launch {
            spaces.collect { list -> state.update { it.copy(spaces = list.sortedBy { s -> s.name.lowercase() }.map { s -> SpaceOptionUi(s.id, s.name) }) } }
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
        fields.fill(draft)
        state.update { it.copy(isLoading = false, draft = draft, isDirty = draft != base, toolTokens = tokensFor(draft)) }
        validate(draft)
    }

    /**
     * Applies an edit to the draft. All changes go through here. The edit starts from what the text
     * fields hold now (they may be a key ahead of the draft); a text it changes goes into its field.
     */
    fun edit(transform: (AgentDraft) -> AgentDraft) {
        val current = state.value
        if (current.isLoading || current.notFound) return
        val typed = fields.read().applyTo(current.draft)
        val draft = transform(typed)
        fields.showChanges(typed, draft)
        if (draft == current.draft) return
        val tokens = if (draft.toolIds == current.draft.toolIds) current.toolTokens else tokensFor(draft)
        state.update { it.copy(draft = draft, isDirty = draft != original, toolTokens = tokens) }
        draftStore.write(json.encodeToString(AgentDraft.serializer(), draft))
        validate(draft)
    }

    /** Replaces the name from code; typing goes into [fields] directly. */
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

    /** Lets the agent recall (read) a shared space, or stops it. */
    fun setSpaceReadable(id: SpaceId, allowed: Boolean) = edit {
        val policy = it.memory
        it.copy(memory = policy.copy(readableSpaces = if (allowed) policy.readableSpaces + id else policy.readableSpaces - id))
    }

    /** Lets the agent add memories to (write) a shared space, or stops it. */
    fun setSpaceWritable(id: SpaceId, allowed: Boolean) = edit {
        val policy = it.memory
        it.copy(memory = policy.copy(writableSpaces = if (allowed) policy.writableSpaces + id else policy.writableSpaces - id))
    }

    /** Null means "use the model's own default". Values are kept to one decimal place. */
    fun setTemperature(value: Double?) = edit {
        it.copy(generation = it.generation.copy(temperature = value?.let { v -> Math.round(v * 10) / 10.0 }))
    }

    /** Replaces the token field from code: digits only, empty for the model's default. */
    fun setMaxOutputTokensText(text: String) {
        val digits = text.filter(Char::isDigit).take(AgentTextFields.MAX_TOKEN_DIGITS)
        fields.maxOutputTokens.putText(digits)
        edit { it }
    }

    fun save() {
        // Exactly what the fields hold now, even if the owner taps Save straight after the last key.
        edit { it }
        val current = state.value
        // A second tap while the first save is still being acted on must not save again.
        if (current.isSaving || current.isLoading || current.notFound || current.savedAgentId != null) return
        state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val existing = current.spaces.mapTo(HashSet()) { it.id }
                val saved = if (editingId == null) {
                    agentManager.create(current.draft.withSpacesIn(existing, before = original))
                } else {
                    // D38: only what the owner changed here is written over the agent as it is stored now, so a change
                    // made elsewhere meanwhile (a space deleted, which also leaves every agent's lists) is kept.
                    val stored = directory.snapshot().agentsById[editingId]?.profile?.toDraft()
                    val base = original
                    val draft = if (stored == null || base == null) current.draft else mergeEdit(base, current.draft, stored, existing)
                    agentManager.update(editingId, draft)
                }
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
        private const val MAX_NAME_LENGTH = 40

        /**
         * D38: the agent as stored now ([stored]) with what the owner changed in the editor ([original] → [edited]):
         * each field the owner changed takes its new value, every other field keeps the stored one. Spaces the owner
         * ticked are added only while they exist ([existingSpaces]); spaces the owner unticked are taken away; a space
         * deleted meanwhile stays gone.
         */
        internal fun mergeEdit(original: AgentDraft, edited: AgentDraft, stored: AgentDraft, existingSpaces: Set<SpaceId>): AgentDraft {
            fun <T> pick(get: (AgentDraft) -> T): T = if (get(edited) != get(original)) get(edited) else get(stored)
            fun spaces(get: (com.aicontrolcenter.core.agents.MemoryPolicy) -> Set<SpaceId>): Set<SpaceId> {
                val added = get(edited.memory) - get(original.memory)
                val removed = get(original.memory) - get(edited.memory)
                return get(stored.memory) - removed + added.filter { it in existingSpaces }
            }
            val memory = stored.memory.copy(
                privateMemoryEnabled = pick { it.memory.privateMemoryEnabled },
                autoRecall = pick { it.memory.autoRecall },
                recallLimit = pick { it.memory.recallLimit },
                readableSpaces = spaces { it.readableSpaces },
                writableSpaces = spaces { it.writableSpaces },
            )
            return stored.copy(
                name = pick { it.name },
                kind = pick { it.kind },
                description = pick { it.description },
                systemInstructions = pick { it.systemInstructions },
                personality = pick { it.personality },
                responsibilities = pick { it.responsibilities },
                skills = pick { it.skills },
                modelId = pick { it.modelId },
                fallbackModelIds = pick { it.fallbackModelIds },
                generation = pick { it.generation },
                toolIds = pick { it.toolIds },
                memory = memory,
                delegation = pick { it.delegation },
                enabled = pick { it.enabled },
                colorIndex = pick { it.colorIndex },
                templateId = pick { it.templateId },
            )
        }

        /** A new agent's draft with only the spaces that exist now among those ticked since [before] (D38). */
        internal fun AgentDraft.withSpacesIn(existing: Set<SpaceId>, before: AgentDraft?): AgentDraft {
            val start = before?.memory
            fun keep(ids: Set<SpaceId>, was: Set<SpaceId>?) = ids.filterTo(HashSet()) { it in existing || (was != null && it in was) }
            return copy(memory = memory.copy(readableSpaces = keep(memory.readableSpaces, start?.readableSpaces), writableSpaces = keep(memory.writableSpaces, start?.writableSpaces)))
        }
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
