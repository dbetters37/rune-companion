package com.aicontrolcenter.app.ui.models

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.label
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.components.putText
import com.aicontrolcenter.core.ProviderId
import com.aicontrolcenter.core.llm.LlmException
import com.aicontrolcenter.core.llm.ProviderDraft
import com.aicontrolcenter.core.llm.ProviderField
import com.aicontrolcenter.core.llm.ProviderManager
import com.aicontrolcenter.core.llm.ProviderPreset
import com.aicontrolcenter.core.llm.ProviderValidationException
import com.aicontrolcenter.core.llm.ProviderValidationIssue
import com.aicontrolcenter.core.registry.ConnectionStatus
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.ModelProvider
import com.aicontrolcenter.core.registry.ProviderKind
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.framework.llm.ProviderPresets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStepState { WAITING, RUNNING, DONE, FAILED, SKIPPED }

@Immutable
data class SetupStepUi(val title: String, val state: SetupStepState, val detail: String? = null)

/** What happened after saving: the save itself, the connection check and the model list, in that order. */
@Immutable
data class SetupProgressUi(
    val providerId: ProviderId,
    val providerName: String,
    val steps: List<SetupStepUi>,
    val finished: Boolean = false,
) {
    val succeeded: Boolean get() = finished && steps.all { it.state == SetupStepState.DONE }
}

@Immutable
data class ProviderFormUiState(
    val isLoading: Boolean = true,
    val notFound: Boolean = false,
    val isEditing: Boolean = false,
    val kind: ProviderKind = ProviderKind.OPENAI_COMPATIBLE,
    /** The preset this form started from, for its title and setup hint. */
    val presetTitle: String? = null,
    val setupHint: String? = null,
    /** The preset's plain line on what using it costs, when adding from a preset. */
    val costLine: String? = null,
    val needsApiKey: Boolean = false,
    val name: String = "",
    val baseUrl: String = "",
    /**
     * What is being typed into the key field. It lives here only until the provider is saved:
     * then it goes to the provider manager, which puts it in the vault, and is cleared.
     */
    val apiKey: String = "",
    val headersText: String = "",
    /** Editing only: a key is already in the vault for this provider. */
    val keyStored: Boolean = false,
    /** Editing only: the owner asked to delete the stored key. */
    val removeStoredKey: Boolean = false,
    val issues: Map<ProviderField, List<String>> = emptyMap(),
    val isDirty: Boolean = false,
    val isSaving: Boolean = false,
    /** Set once saving succeeded; the screen then shows the set-up steps instead of the form. */
    val setup: SetupProgressUi? = null,
    val message: UserMessage? = null,
) {
    val kindLabel: String get() = kind.label
    val showBaseUrl: Boolean get() = kind.hasAddress

    /** Where prompts would go with the address as typed. */
    val locality: Locality get() = Locality.infer(kind, baseUrl.trim().ifEmpty { null })

    fun issue(field: ProviderField): String? = issues[field]?.joinToString(" ")

    /** Never prints the key being typed, so a log line or a crash report cannot carry it. */
    override fun toString(): String =
        "ProviderFormUiState(kind=$kind, name=$name, baseUrl=$baseUrl, apiKey=${if (apiKey.isEmpty()) "empty" else "[typed, hidden]"}, " +
            "keyStored=$keyStored, removeStoredKey=$removeStoredKey, issues=$issues, isSaving=$isSaving, setup=$setup)"
}

/**
 * The provider form's text fields. The fields read these and the keyboard writes them directly,
 * so every key lands in order; the form's state follows them and never writes back while the
 * owner types. Filled once, when the preset or the provider has loaded.
 */
class ProviderTextFields {
    val name = TextFieldState()
    val baseUrl = TextFieldState()

    /** The key being typed: emptied, with its undo history, as soon as it has gone to the vault. */
    val apiKey = TextFieldState()
    val headers = TextFieldState()

    internal fun read() = Texts(name.text.toString(), baseUrl.text.toString(), apiKey.text.toString(), headers.text.toString())

    /** Puts into the fields the texts [form] changed from [before] (a change made from code, not typed). */
    internal fun showChanges(before: ProviderFormUiState, form: ProviderFormUiState) {
        if (form.name != before.name) name.putText(form.name)
        if (form.baseUrl != before.baseUrl) baseUrl.putText(form.baseUrl)
        if (form.apiKey != before.apiKey) if (form.apiKey.isEmpty()) forgetKey() else apiKey.putText(form.apiKey)
        if (form.headersText != before.headersText) headers.putText(form.headersText)
    }

    /** Forgets the typed key, including what undo could bring back. */
    internal fun forgetKey() {
        apiKey.clearText()
        apiKey.undoState.clearHistory()
    }

    internal data class Texts(val name: String, val baseUrl: String, val apiKey: String, val headers: String) {
        fun applyTo(form: ProviderFormUiState): ProviderFormUiState = form.copy(
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            headersText = headers,
            removeStoredKey = if (apiKey.isNotEmpty()) false else form.removeStoredKey,
        )

        /** Never prints the key. */
        override fun toString(): String = "Texts(name=$name, baseUrl=$baseUrl, apiKey=${if (apiKey.isEmpty()) "empty" else "[typed, hidden]"})"
    }
}

/**
 * Adds a provider from a preset, or edits one. Every change is validated by
 * [ProviderManager.validate] and the issues are shown next to their fields, so the form never
 * disagrees with what saving would accept. After a successful save it checks the connection
 * and fetches the model list straight away, reporting each step.
 */
class ProviderFormViewModel(
    private val providerManager: ProviderManager,
    private val directory: RegistryDirectory,
    private val presetId: String?,
    private val editingId: ProviderId?,
    /** An address found on this phone, which wins over the preset's example address. */
    private val baseUrlOverride: String? = null,
) : ViewModel() {

    private val state = MutableStateFlow(ProviderFormUiState(isEditing = editingId != null))
    val uiState: StateFlow<ProviderFormUiState> = state.asStateFlow()

    private var original: ProviderFormUiState? = null
    private var validation: Job? = null
    private var messageCounter = 0L

    /** "Write each header as Name: value" when a line cannot be read. */
    private var headerParseProblem: String? = null

    /** What is being typed into the form's text fields. */
    val fields = ProviderTextFields()

    init {
        viewModelScope.launch { load() }
        // The form follows what is typed. (Before the form has loaded, edits are ignored.)
        viewModelScope.launch { snapshotFlow { fields.read() }.collect { edit { it } } }
    }

    private suspend fun load() {
        val snapshot = directory.snapshot()
        val loaded = if (editingId != null) {
            val provider = snapshot.providersById[editingId]
            if (provider == null) {
                state.update { it.copy(isLoading = false, notFound = true) }
                return
            }
            formFor(provider, keyStored = safeHasKey(provider.id))
        } else {
            val preset = providerManager.presets.firstOrNull { it.id == presetId }
                ?: providerManager.presets.firstOrNull { it.id == ProviderPresets.CUSTOM_OPENAI_COMPATIBLE }
                ?: ProviderPreset(ProviderPresets.CUSTOM_OPENAI_COMPATIBLE, "Custom server", "", ProviderKind.OPENAI_COMPATIBLE, null, false, "")
            ProviderFormUiState(
                isLoading = false,
                kind = preset.kind,
                presetTitle = preset.title,
                setupHint = preset.setupHint,
                costLine = presetDescription(preset).second,
                needsApiKey = preset.needsApiKey,
                name = uniqueName(preset.title, snapshot.providers.map { it.name }),
                baseUrl = baseUrlOverride ?: preset.baseUrl.orEmpty(),
            )
        }
        original = loaded
        fields.name.putText(loaded.name)
        fields.baseUrl.putText(loaded.baseUrl)
        fields.headers.putText(loaded.headersText)
        state.value = loaded
        validate()
    }

    private fun formFor(provider: ModelProvider, keyStored: Boolean) = ProviderFormUiState(
        isLoading = false,
        isEditing = true,
        kind = provider.kind,
        needsApiKey = provider.missingKey(false),
        name = provider.name,
        baseUrl = provider.baseUrl.orEmpty(),
        headersText = provider.extraHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" },
        keyStored = keyStored,
    )

    // Typing goes into [fields] directly; these replace a field's text from code.

    fun setName(value: String) = edit { it.copy(name = value) }

    fun setBaseUrl(value: String) = edit { it.copy(baseUrl = value) }

    fun setApiKey(value: String) = edit { it.copy(apiKey = value, removeStoredKey = if (value.isNotEmpty()) false else it.removeStoredKey) }

    fun setHeadersText(value: String) = edit { it.copy(headersText = value) }

    /** Editing only: delete the stored key when saving. Typing a new key replaces it instead. */
    fun setRemoveStoredKey(remove: Boolean) = edit { it.copy(removeStoredKey = remove, apiKey = if (remove) "" else it.apiKey) }

    /**
     * Applies a change to the form. It starts from what the text fields hold now (they may be a key
     * ahead of the form); a text it changes goes into its field.
     */
    private fun edit(change: (ProviderFormUiState) -> ProviderFormUiState) {
        val current = state.value
        if (current.isLoading || current.notFound || current.setup != null) return
        val typed = fields.read().applyTo(current)
        val next = change(typed)
        fields.showChanges(typed, next)
        if (next == current) return
        state.value = next.copy(isDirty = next.contentDiffers(original))
        validate()
    }

    fun save() {
        // Exactly what the fields hold now, even if the owner taps Save straight after the last key.
        edit { it }
        val current = state.value
        if (current.isSaving || current.isLoading || current.notFound || current.setup != null) return
        val draft = draftOf(current)
        if (headerParseProblem != null) {
            state.update { it.copy(message = message("Fix the extra headers, then save again.")) }
            return
        }
        state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            try {
                val provider = if (editingId == null) providerManager.addProvider(draft) else providerManager.updateProvider(editingId, draft)
                val keyNote = when {
                    !draft.apiKey.isNullOrBlank() -> " The API key went straight to encrypted storage and is not shown again."
                    draft.apiKey == "" && current.keyStored -> " The stored API key was deleted."
                    else -> ""
                }
                original = null
                // The key has reached the vault; from here on nothing in the UI holds it.
                fields.forgetKey()
                state.update {
                    it.copy(
                        apiKey = "",
                        removeStoredKey = false,
                        isSaving = false,
                        isDirty = false,
                        setup = SetupProgressUi(
                            provider.id,
                            provider.name,
                            listOf(
                                SetupStepUi(if (editingId == null) "Saved" else "Changes saved", SetupStepState.DONE, "${provider.name} is registered.$keyNote"),
                                SetupStepUi("Checking the connection", SetupStepState.RUNNING),
                                SetupStepUi("Fetching the model list", SetupStepState.WAITING),
                            ),
                        ),
                    )
                }
                runSetup(provider)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: ProviderValidationException) {
                state.update {
                    it.copy(isSaving = false, issues = invalid.issues.byField(), message = message("Fix the marked fields, then save again."))
                }
            } catch (failure: Exception) {
                state.update { it.copy(isSaving = false, message = message(failure.providerFailureText("Saving"))) }
            }
        }
    }

    private suspend fun runSetup(provider: ModelProvider) {
        val health = try {
            providerManager.checkProvider(provider.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            step(1, SetupStepState.FAILED, failure.providerFailureText("The check"))
            step(2, SetupStepState.SKIPPED, "Skipped, because the provider could not be checked.")
            finish()
            return
        }
        val online = health.status == ConnectionStatus.ONLINE
        step(1, if (online) SetupStepState.DONE else SetupStepState.FAILED, health.describe(provider.name))
        if (health.status == ConnectionStatus.OFFLINE || health.status == ConnectionStatus.AUTH_ERROR) {
            step(2, SetupStepState.SKIPPED, "Skipped until the connection works. Fix it, then refresh the models on the provider's page.")
            finish()
            return
        }
        step(2, SetupStepState.RUNNING)
        try {
            val report = providerManager.refreshModels(provider.id).toUi()
            val listed = directory.snapshot().models.count { it.providerId == provider.id }
            step(2, SetupStepState.DONE, "${plural(listed, "model")} registered. ${report.summary}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LlmException) {
            step(2, SetupStepState.FAILED, failure.providerFailureText("Fetching the models") + " You can add a model by name on the provider's page.")
        } catch (failure: Exception) {
            step(2, SetupStepState.FAILED, failure.providerFailureText("Fetching the models"))
        }
        finish()
    }

    private fun step(index: Int, newState: SetupStepState, detail: String? = null) = state.update { current ->
        val setup = current.setup ?: return@update current
        current.copy(setup = setup.copy(steps = setup.steps.mapIndexed { i, step -> if (i == index) step.copy(state = newState, detail = detail) else step }))
    }

    private fun finish() = state.update { it.copy(setup = it.setup?.copy(finished = true)) }

    fun messageShown() = state.update { it.copy(message = null) }

    private fun validate() {
        val current = state.value
        val draft = draftOf(current)
        val parseProblem = headerParseProblem
        validation?.cancel()
        validation = viewModelScope.launch {
            val issues = try {
                providerManager.validate(draft, editingId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Validation reads the registry. If that fails, saving reports it; typing must not crash.
                return@launch
            }
            val withParse = if (parseProblem != null) issues + ProviderValidationIssue(ProviderField.HEADERS, parseProblem) else issues
            state.update { if (draftOf(it) == draft) it.copy(issues = withParse.byField()) else it }
        }
    }

    /** The draft the form describes. A blank key field means "no key" when adding and "unchanged" when editing. */
    private fun draftOf(form: ProviderFormUiState): ProviderDraft {
        val headers = parseHeaders(form.headersText)
        headerParseProblem = headers.problem
        val key = when {
            form.apiKey.isNotBlank() -> form.apiKey
            editingId != null && form.removeStoredKey -> ""
            else -> null
        }
        return ProviderDraft(
            name = form.name,
            kind = form.kind,
            baseUrl = form.baseUrl.takeIf { form.showBaseUrl }?.trim()?.ifEmpty { null },
            apiKey = key,
            extraHeaders = headers.headers,
        )
    }

    private suspend fun safeHasKey(id: ProviderId): Boolean = try {
        providerManager.hasApiKey(id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun message(text: String) = UserMessage(text, ++messageCounter)

    private fun List<ProviderValidationIssue>.byField(): Map<ProviderField, List<String>> = groupBy({ it.field }, { it.message })

    internal data class ParsedHeaders(val headers: Map<String, String>, val problem: String?)

    companion object {
        /** One header per line, "Name: value"; blank lines are ignored. */
        internal fun parseHeaders(text: String): ParsedHeaders {
            val headers = LinkedHashMap<String, String>()
            text.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEachIndexed
                val colon = line.indexOf(':')
                if (colon <= 0) return ParsedHeaders(headers, "Line ${index + 1}: write each header as Name: value.")
                headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
            return ParsedHeaders(headers, null)
        }

        /** "Ollama on this phone", then "Ollama on this phone 2", … so a preset opens without a name clash. */
        internal fun uniqueName(wanted: String, existing: List<String>): String {
            val taken = existing.map { it.trim().lowercase() }.toSet()
            if (wanted.lowercase() !in taken) return wanted
            var n = 2
            while ("$wanted $n".lowercase() in taken) n++
            return "$wanted $n"
        }
    }
}

/** Whether the owner changed anything since the form was loaded; the key counts as soon as something is typed. */
private fun ProviderFormUiState.contentDiffers(original: ProviderFormUiState?): Boolean =
    original == null || name != original.name || baseUrl != original.baseUrl || apiKey.isNotEmpty() ||
        headersText != original.headersText || removeStoredKey
