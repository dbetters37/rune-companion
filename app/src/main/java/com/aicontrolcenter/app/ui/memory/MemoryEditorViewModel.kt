package com.aicontrolcenter.app.ui.memory

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryChange
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryRecord
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.memory.MemoryTier
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.registry.RegistryDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** A place a new memory can go: one agent's private memory, or a shared space. */
@Immutable
data class ScopeChoiceUi(
    val scope: MemoryScope,
    val label: String,
    val kind: ScopeKind,
    val colorIndex: Int,
    /** What choosing it means in practice ("Read by 3 agents"), or a warning. */
    val note: String,
    /** The note is a warning: the memory would not be recalled as things stand. */
    val warn: Boolean = false,
)

/** What is being typed. Everything is kept as entered; trimming happens on save. */
@Immutable
data class MemoryForm(
    val scope: MemoryScope? = null,
    val title: String = "",
    val content: String = "",
    val kind: MemoryKind = MemoryKind.NOTE,
    val importance: Double = 0.5,
    val tags: List<String> = emptyList(),
    val shortTerm: Boolean = false,
)

@Immutable
data class MemoryEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    /** The memory being edited does not exist any more. */
    val notFound: Boolean = false,
    val form: MemoryForm = MemoryForm(),
    val scopeChoices: List<ScopeChoiceUi> = emptyList(),
    /** Where an existing memory lives; it cannot be moved. */
    val fixedScope: ScopeUi? = null,
    /** Task memory is always short-term, so the tier cannot be changed. */
    val isTaskMemory: Boolean = false,
    val scopeError: String? = null,
    val titleError: String? = null,
    val contentError: String? = null,
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    /** Set once after a save; the route leaves and opens the memory. */
    val savedId: MemoryId? = null,
    /** How long short-term memory lives, in whole days, for the switch's explanation. */
    val shortTermDays: Int = 3,
    val message: UserMessage? = null,
)

/**
 * Writes a new memory as the owner, or edits one. Provenance of a new memory says the owner
 * wrote it; an edit keeps the original provenance (the service bumps the revision), because
 * where a memory came from is never edited away.
 *
 * @param initialScope pre-selects where a new memory goes (the explorer's current filter).
 */
class MemoryEditorViewModel(
    private val memory: MemoryService,
    private val directory: RegistryDirectory,
    private val clock: Clock,
    private val editingId: MemoryId?,
    private val initialScope: MemoryScope? = null,
    shortTermTtlMs: Long = 3L * DAY_MS,
) : ViewModel() {
    private val state = MutableStateFlow(
        MemoryEditorUiState(isNew = editingId == null, shortTermDays = (shortTermTtlMs / DAY_MS).toInt().coerceAtLeast(1)),
    )
    val uiState: StateFlow<MemoryEditorUiState> = state.asStateFlow()

    private var original: MemoryRecord? = null
    private var initialForm = MemoryForm()
    private var messageCounter = 0L

    /**
     * The title and the text being typed. The fields read these and the keyboard writes them
     * directly, so every key lands in order; the form follows them and never writes back while
     * the owner types. Filled once, when the memory being edited has loaded.
     */
    val title = TextFieldState()
    val content = TextFieldState()

    init {
        viewModelScope.launch { load() }
        // The form follows what is typed. (Before it has loaded, edits are ignored.)
        viewModelScope.launch { snapshotFlow { title.text.toString() to content.text.toString() }.collect { edit { it } } }
    }

    private suspend fun load() {
        try {
            val snapshot = directory.snapshot()
            val spaces = memory.observeSpaces().first()
            val names = MemoryNames.of(snapshot, spaces)
            if (editingId != null) {
                val record = memory.get(MemoryActor.Owner, editingId)
                if (record == null) {
                    state.update { it.copy(isLoading = false, notFound = true) }
                    return
                }
                original = record
                title.putText(record.title)
                content.putText(record.content)
                initialForm = MemoryForm(
                    scope = record.scope, title = record.title, content = record.content, kind = record.kind,
                    importance = record.importance, tags = record.tags.sorted(), shortTerm = record.tier == MemoryTier.SHORT_TERM,
                )
                state.update {
                    it.copy(isLoading = false, form = initialForm, fixedScope = names.scope(record.scope), isTaskMemory = record.scope is MemoryScope.Task)
                }
            } else {
                val readers = { id: com.aicontrolcenter.core.SpaceId -> snapshot.agents.count { id in it.profile.memory.readableSpaces } }
                val choices = snapshot.agents.map { entry ->
                    val profile = entry.profile
                    ScopeChoiceUi(
                        scope = MemoryScope.Agent(profile.id),
                        label = profile.name,
                        kind = ScopeKind.PRIVATE,
                        colorIndex = profile.colorIndex,
                        note = if (profile.memory.privateMemoryEnabled) {
                            "Only ${profile.name} can recall it."
                        } else {
                            "Private memory is off for ${profile.name}: it will not recall this until you switch it on."
                        },
                        warn = !profile.memory.privateMemoryEnabled,
                    )
                } + spaces.sortedBy { it.name.lowercase() }.map { space ->
                    val count = readers(space.id)
                    ScopeChoiceUi(
                        scope = MemoryScope.Shared(space.id),
                        label = space.name,
                        kind = ScopeKind.SHARED,
                        colorIndex = spaceColorIndex(space.id),
                        note = if (count == 0) "No agent may read this space yet." else "${plural(count, "agent")} may read it.",
                        warn = count == 0,
                    )
                }
                val preselected = initialScope?.takeIf { wanted -> choices.any { it.scope == wanted } }
                initialForm = MemoryForm(scope = preselected)
                state.update { it.copy(isLoading = false, form = initialForm, scopeChoices = choices) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            state.update { it.copy(isLoading = false, notFound = editingId != null, message = message("Could not load: ${failure.message ?: "storage error"}")) }
        }
    }

    /**
     * Applies a change to the form. It starts from what the title and text fields hold now (they
     * may be a key ahead of the form); a text it changes goes into its field.
     */
    private fun edit(transform: (MemoryForm) -> MemoryForm) {
        val current = state.value
        if (current.isLoading || current.notFound) return
        val typed = current.form.copy(title = title.text.toString(), content = content.text.toString())
        val form = transform(typed)
        if (form.title != typed.title) title.putText(form.title)
        if (form.content != typed.content) content.putText(form.content)
        state.update {
            it.copy(
                form = form,
                isDirty = form != initialForm,
                scopeError = if (form.scope != null) null else it.scopeError,
                titleError = if (form.title.isNotBlank()) null else it.titleError,
                contentError = if (form.content.isNotBlank()) null else it.contentError,
            )
        }
    }

    /** Only a new memory can choose; an existing one stays where it was saved. */
    fun setScope(scope: MemoryScope) = edit { if (editingId == null) it.copy(scope = scope) else it }

    /** Replaces the title from code; typing goes into [title] directly. */
    fun setTitle(text: String) = edit { it.copy(title = text) }

    /** Replaces the text from code; typing goes into [content] directly. */
    fun setContent(text: String) = edit { it.copy(content = text) }

    fun setKind(kind: MemoryKind) = edit { it.copy(kind = kind) }

    /** Kept to one decimal place, the precision the slider offers. */
    fun setImportance(value: Double) = edit { it.copy(importance = (Math.round(value.coerceIn(0.0, 1.0) * 10) / 10.0)) }

    /** Tags are stored lower-case, so they are shown that way as soon as they are added. */
    fun addTag(text: String) = edit { form ->
        val tag = text.trim().lowercase(Locale.ROOT)
        if (tag.isEmpty() || tag in form.tags) form else form.copy(tags = form.tags + tag)
    }

    fun removeTag(tag: String) = edit { it.copy(tags = it.tags - tag) }

    fun setShortTerm(shortTerm: Boolean) = edit { if (state.value.isTaskMemory) it else it.copy(shortTerm = shortTerm) }

    fun save() {
        // Exactly what the fields hold now, even if the owner taps Save straight after the last key.
        edit { it }
        val current = state.value
        if (current.isLoading || current.notFound || current.isSaving || current.savedId != null) return
        val form = current.form
        val scopeError = if (form.scope == null) "Choose where this memory goes." else null
        val titleError = if (form.title.isBlank()) "Give it a short title." else null
        val contentError = if (form.content.isBlank()) "Write what should be remembered." else null
        if (scopeError != null || titleError != null || contentError != null) {
            state.update { it.copy(scopeError = scopeError, titleError = titleError, contentError = contentError) }
            return
        }
        state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val saved = original?.let { update(it, form) } ?: create(form)
                initialForm = form
                state.update { it.copy(isSaving = false, isDirty = false, savedId = saved.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: MemoryValidationException) {
                state.update { it.copy(isSaving = false, message = message(refused.message ?: "The memory could not be saved.")) }
            } catch (refused: MemoryPermissionException) {
                state.update { it.copy(isSaving = false, message = message(refused.message ?: "Not allowed.")) }
            } catch (failure: Exception) {
                state.update { it.copy(isSaving = false, message = message("Could not save: ${failure.message ?: "storage error"}")) }
            }
        }
    }

    private suspend fun create(form: MemoryForm): MemoryRecord = memory.remember(
        MemoryActor.Owner,
        MemoryDraft(
            scope = requireNotNull(form.scope),
            kind = form.kind,
            title = form.title.trim(),
            content = form.content.trim(),
            tier = if (form.shortTerm) MemoryTier.SHORT_TERM else MemoryTier.LONG_TERM,
            tags = form.tags.toSet(),
            importance = form.importance,
            provenance = Provenance(origin = ProvenanceOrigin.OWNER, recordedAt = clock.now()),
        ),
    )

    /** Sends only what changed, so an edit never rewrites fields the owner did not touch. */
    private suspend fun update(record: MemoryRecord, form: MemoryForm): MemoryRecord {
        val tier = if (form.shortTerm) MemoryTier.SHORT_TERM else MemoryTier.LONG_TERM
        val change = MemoryChange(
            title = form.title.trim().takeIf { it != record.title },
            content = form.content.trim().takeIf { it != record.content },
            kind = form.kind.takeIf { it != record.kind },
            tags = form.tags.toSet().takeIf { it != record.tags },
            importance = form.importance.takeIf { it != record.importance },
            tier = tier.takeIf { it != record.tier },
        )
        return if (change == MemoryChange()) record else memory.update(MemoryActor.Owner, record.id, change)
    }

    fun savedHandled() = state.update { it.copy(savedId = null) }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun message(text: String) = UserMessage(text, ++messageCounter)

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
