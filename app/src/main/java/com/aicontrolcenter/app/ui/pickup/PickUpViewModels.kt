package com.aicontrolcenter.app.ui.pickup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.di.wiring.PickUpImports
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.formatBytes
import com.aicontrolcenter.app.ui.routines.RoutineWords
import com.aicontrolcenter.app.ui.theme.StatusTone
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.operator.OwnerFolders
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.pickup.OwnerPickUpRules
import com.aicontrolcenter.core.pickup.PickUpLimits
import com.aicontrolcenter.core.pickup.PickUpRule
import com.aicontrolcenter.core.pickup.PickUpSafety
import com.aicontrolcenter.core.pickup.PickedUpFile
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** One file a rule picked up, or left out because it was there before the rule was switched on. */
@Immutable
data class PickedUpFileUi(
    val name: String,
    /** "Picked up today at 03:12 · 2.1 KB", or why it was left out. */
    val line: String,
    val leftOut: Boolean,
)

/** One rule as the list shows it. */
@Immutable
data class PickUpRuleUi(
    val id: PickUpRuleId,
    /** The folder's name, or "A folder that was removed". */
    val folder: String,
    /** The memory space it fills. */
    val space: String,
    val spaceId: SpaceId,
    val enabled: Boolean,
    /** "On", "Off" or "Stopped". */
    val status: String,
    val tone: StatusTone,
    /** "Last looked today at 03:12", or "Not looked at yet". */
    val lastRun: String,
    /** What the last pick-up did, in the app's words (it says which files were skipped and why). */
    val lastResult: String?,
    /** The rule was switched off because agents may change its folder (or one beside it) again. */
    val stoppedForSafety: Boolean,
    /** The newest files it picked up or left out. */
    val files: List<PickedUpFileUi>,
)

/** What the Folder pick-up list shows. */
@Immutable
data class PickUpRulesUiState(
    val isLoading: Boolean = true,
    /** The master switch (Settings › Routines and folder pick-up): off, nothing is picked up. */
    val masterOn: Boolean = false,
    val rules: List<PickUpRuleUi> = emptyList(),
    /** Fewer than [PickUpLimits.MAX_RULES] exist. */
    val canAdd: Boolean = true,
    /** "1 of 10 rules". */
    val count: String = "",
    /** A switch is being saved: the buttons wait. */
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
)

/**
 * The Folder pick-up list (Q1, Appendix A, Tier B; WP10c). Owner-only: the owner's rules, the files each picked up (read
 * only), the owner's folders (names), the spaces (names), and the master switch read only. Each rule shows its folder and
 * space, whether it is on, when it last looked and what that did ([PickUpRule.lastResult], the app's own sentences, which
 * name skipped and refused files and why), and its newest files: picked up, or left out because they were in the folder
 * before the rule was switched on. "Switch on again" goes through the owner's port, which checks the folder again.
 *
 * @param zone the owner's time zone, for "today at 03:12".
 * @param computation where the list is built (the app's background pool; the caller's dispatcher in tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PickUpRulesViewModel(
    private val rules: OwnerPickUpRules,
    private val imports: PickUpImports,
    private val folders: OwnerFolders,
    private val memory: MemoryService,
    private val settings: SettingsView,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {
    private data class Local(val isBusy: Boolean = false, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messages = 0L

    /** The newest files of every rule, by rule (at most [FILES_SHOWN] each; at most 10 rules). */
    private val filesByRule: Flow<Map<PickUpRuleId, List<PickedUpFile>>> =
        rules.observeAll().map { list -> list.map { it.id } }.distinctUntilChanged().flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> imports.observeImports(id, FILES_SHOWN).map { id to it } }) { pairs -> pairs.toMap() }
            }
        }

    val uiState: StateFlow<PickUpRulesUiState> = combine(
        rules.observeAll(),
        filesByRule,
        folders.observeAll(),
        memory.observeSpaces(),
        settings.settings.map { it.folderPickUpEnabled }.distinctUntilChanged(),
    ) { list, files, folderList, spaces, on -> build(list, files, folderList, spaces, on) }
        .flowOn(computation)
        .let { source -> combine(source, local) { state, local -> state.copy(isBusy = local.isBusy, message = local.message) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickUpRulesUiState())

    private fun build(
        list: List<PickUpRule>,
        files: Map<PickUpRuleId, List<PickedUpFile>>,
        folderList: List<SharedFolder>,
        spaces: List<KnowledgeSpace>,
        on: Boolean,
    ): PickUpRulesUiState {
        val now = clock.now()
        val zone = zone()
        val folderNames = folderList.associate { it.id to it.displayName }
        val spaceNames = spaces.associate { it.id to it.name }
        return PickUpRulesUiState(
            isLoading = false,
            masterOn = on,
            rules = list.map { rule ->
                val stopped = !rule.enabled && rule.lastResult?.let(PickUpSafety::switchesRuleOff) == true
                PickUpRuleUi(
                    id = rule.id,
                    folder = folderNames[rule.folderId] ?: FOLDER_REMOVED,
                    space = spaceNames[rule.spaceId] ?: SPACE_REMOVED,
                    spaceId = rule.spaceId,
                    enabled = rule.enabled,
                    status = when {
                        rule.enabled -> "On"
                        stopped -> "Stopped"
                        else -> "Off"
                    },
                    tone = when {
                        rule.enabled -> StatusTone.READY
                        stopped -> StatusTone.ATTENTION
                        else -> StatusTone.DISABLED
                    },
                    lastRun = rule.lastRunAt?.let { "Last looked " + RoutineWords.at(it, now, zone) } ?: "Not looked at yet",
                    lastResult = rule.lastResult?.takeIf { it.isNotBlank() },
                    stoppedForSafety = stopped,
                    files = files[rule.id].orEmpty().map { fileOf(it, now, zone) },
                )
            },
            canAdd = list.size < PickUpLimits.MAX_RULES,
            count = "${list.size} of ${PickUpLimits.MAX_RULES} rules",
        )
    }

    /** Switches a rule on (the folder is checked again; refused with a sentence) or off. */
    fun setEnabled(id: PickUpRuleId, on: Boolean) {
        if (local.value.isBusy) return
        local.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val text = try {
                when (val result = rules.setEnabled(id, on)) {
                    is OwnerResult.Done -> if (on) SWITCHED_ON else "Switched off: nothing more is picked up by this rule."
                    is OwnerResult.Refused -> result.reason
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                COULD_NOT_SAVE
            }
            local.update { it.copy(isBusy = false, message = UserMessage(text, ++messages)) }
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    companion object {
        /** Files shown under each rule. */
        const val FILES_SHOWN: Int = 10

        const val FOLDER_REMOVED: String = "A folder that was removed"
        const val SPACE_REMOVED: String = "A memory space that was deleted"
        const val COULD_NOT_SAVE: String = "The change could not be saved. Try again."
        const val SWITCHED_ON: String = "Switched on. Files already in the folder are left out; only new ones are picked up."

        /** Why a file was left out (security review of wave 1, F1). */
        const val LEFT_OUT: String = "Left out: it was already in the folder when the rule was switched on."

        fun fileOf(file: PickedUpFile, now: Long, zone: ZoneId): PickedUpFileUi = PickedUpFileUi(
            name = file.documentName,
            line = if (file.seenOnly) LEFT_OUT else "Picked up " + RoutineWords.at(file.importedAt, now, zone) + " · " + formatBytes(file.sizeBytes),
            leftOut = file.seenOnly,
        )
    }
}

/** A shared folder as the rule editor offers it. */
@Immutable
data class FolderChoiceUi(
    val id: FolderId,
    val name: String,
    val readOnly: Boolean,
    /** "Read-only for agents" or "Agents may change files in it". */
    val access: String,
    /** Why it cannot feed a rule now ([PickUpSafety.folderProblem], or that it has a rule), or null. */
    val problem: String?,
    /** It has a rule already (one rule per folder). */
    val hasRule: Boolean,
)

/** A memory space as the rule editor offers it. */
@Immutable
data class SpaceChoiceUi(val id: SpaceId, val name: String)

/** What the pick-up rule editor shows. */
@Immutable
data class PickUpRuleEditorUiState(
    val isLoading: Boolean = true,
    val isNew: Boolean = true,
    /** The rule was deleted meanwhile. */
    val missing: Boolean = false,
    /** A new rule: every shared folder, with why it can or cannot be chosen. */
    val folders: List<FolderChoiceUi> = emptyList(),
    val selectedFolder: FolderId? = null,
    /** The chosen folder (or the rule's folder), with its problem. */
    val folder: FolderChoiceUi? = null,
    /** "Make it read-only" is offered: the chosen folder exists and agents may change it. */
    val canMakeReadOnly: Boolean = false,
    val spaces: List<SpaceChoiceUi> = emptyList(),
    val selectedSpace: SpaceId? = null,
    /** An existing rule: on or off, and its last result. */
    val enabled: Boolean = false,
    val lastResult: String? = null,
    /** Why the last save or switch was refused, as the app says it. */
    val problem: String? = null,
    val isSaving: Boolean = false,
    val confirmDelete: Boolean = false,
    /** Saved: the screen goes back (once). */
    val saved: PickUpRuleId? = null,
    val deleted: Boolean = false,
    val message: UserMessage? = null,
) {
    /** Save can be tapped: a folder without a problem and a space are chosen (a new rule), or the space changed. */
    val canSave: Boolean get() = !isSaving && selectedSpace != null && (if (isNew) folder != null && folder.problem == null else true)
}

/**
 * A pick-up rule to make or change (WP10c): the folder (one the owner shared in Tools › Folders; it must be read-only for
 * agents, and "Make it read-only" goes through the owner's folders port, `setReadOnly`, which switches off any rule a
 * writable folder would endanger) and the one memory space it fills. Every refusal of the owner's port is shown as it is
 * (`FOLDER_NOT_READ_ONLY`, `FOLDER_OVERLAPS_WRITABLE`, the Downloads-entry, unknown-place and other-app sentences,
 * `FOLDER_UNREADABLE`, `FOLDER_GONE`). An existing rule can point at another space, be switched on or off, or be deleted
 * (its memories stay).
 */
class PickUpRuleEditorViewModel(
    private val ruleId: PickUpRuleId?,
    private val folderId: FolderId?,
    private val rules: OwnerPickUpRules,
    private val folders: OwnerFolders,
    private val memory: MemoryService,
    private val computation: CoroutineContext = EmptyCoroutineContext,
) : ViewModel() {
    private data class Form(
        val filled: Boolean = false,
        val folder: FolderId? = null,
        val space: SpaceId? = null,
        val problem: String? = null,
        val isSaving: Boolean = false,
        val confirmDelete: Boolean = false,
        val saved: PickUpRuleId? = null,
        val deleted: Boolean = false,
        val message: UserMessage? = null,
    )

    private val form = MutableStateFlow(Form(folder = folderId))
    private var messages = 0L

    init {
        viewModelScope.launch {
            val rule = if (ruleId == null) null else try {
                rules.observeAll().first().firstOrNull { it.id == ruleId }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // A new rule fills its only space at once, so a tap on Save is all that is left.
            val onlySpace = if (rule == null) {
                try {
                    memory.observeSpaces().first().singleOrNull()?.id
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            form.update { it.copy(filled = true, folder = rule?.folderId ?: it.folder, space = rule?.spaceId ?: it.space ?: onlySpace) }
        }
    }

    val uiState: StateFlow<PickUpRuleEditorUiState> = combine(
        rules.observeAll(),
        folders.observeAll(),
        memory.observeSpaces(),
        form,
    ) { list, folderList, spaces, form -> build(list, folderList, spaces, form) }
        .flowOn(computation)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickUpRuleEditorUiState(isNew = ruleId == null))

    private fun build(list: List<PickUpRule>, folderList: List<SharedFolder>, spaces: List<KnowledgeSpace>, form: Form): PickUpRuleEditorUiState {
        val rule = ruleId?.let { id -> list.firstOrNull { it.id == id } }
        val withRules = list.map { it.folderId }.toSet()
        val choices = folderList.map { folder ->
            val hasRule = folder.id in withRules && folder.id != rule?.folderId
            FolderChoiceUi(
                id = folder.id,
                name = folder.displayName,
                readOnly = folder.readOnly,
                access = if (folder.readOnly) "Read-only for agents" else "Agents may change files in it",
                problem = if (hasRule) HAS_RULE else PickUpSafety.folderProblem(folder, folderList),
                hasRule = hasRule,
            )
        }
        val chosenId = rule?.folderId ?: form.folder
        val chosen = choices.firstOrNull { it.id == chosenId }
        return PickUpRuleEditorUiState(
            isLoading = !form.filled,
            isNew = ruleId == null,
            missing = ruleId != null && form.filled && rule == null && !form.deleted,
            folders = choices,
            selectedFolder = chosen?.id,
            folder = chosen ?: rule?.let { FolderChoiceUi(it.folderId, PickUpRulesViewModel.FOLDER_REMOVED, false, "", PickUpSafety.FOLDER_GONE, false) },
            canMakeReadOnly = chosen != null && !chosen.readOnly && !chosen.hasRule,
            spaces = spaces.map { SpaceChoiceUi(it.id, it.name) },
            selectedSpace = form.space?.takeIf { id -> spaces.any { it.id == id } },
            enabled = rule?.enabled ?: false,
            lastResult = rule?.lastResult?.takeIf { it.isNotBlank() },
            problem = form.problem,
            isSaving = form.isSaving,
            confirmDelete = form.confirmDelete,
            saved = form.saved,
            deleted = form.deleted,
            message = form.message,
        )
    }

    /** The owner chose a folder for a new rule. */
    fun selectFolder(id: FolderId) {
        if (ruleId != null) return
        form.update { it.copy(folder = id, problem = null) }
    }

    fun selectSpace(id: SpaceId) = form.update { it.copy(space = id, problem = null) }

    /**
     * "Make it read-only": the chosen folder becomes read-only for agents (Tools › Folders' switch, through the owner's
     * folders port). Agents then cannot change anything in it; it can feed a rule.
     */
    fun makeReadOnly() {
        val id = uiState.value.folder?.id ?: return
        act {
            val updated = folders.setReadOnly(id, true) ?: return@act Outcome(problem = PickUpSafety.FOLDER_GONE)
            Outcome(message = "“${updated.displayName}” is read-only now: no agent can change anything in it.")
        }
    }

    /** Makes the rule (a new one) or points it at the chosen space (an existing one). Refusals are shown as they are. */
    fun save() {
        val state = uiState.value
        val space = state.selectedSpace ?: return run { form.update { it.copy(problem = CHOOSE_SPACE) } }
        if (ruleId == null) {
            val folder = state.folder?.id ?: return run { form.update { it.copy(problem = CHOOSE_FOLDER) } }
            act {
                when (val result = rules.create(folder, space)) {
                    is OwnerResult.Done -> Outcome(saved = result.value.id)
                    is OwnerResult.Refused -> Outcome(problem = result.reason)
                }
            }
        } else {
            act {
                when (val result = rules.changeSpace(ruleId, space)) {
                    is OwnerResult.Done -> Outcome(saved = result.value.id)
                    is OwnerResult.Refused -> Outcome(problem = result.reason)
                }
            }
        }
    }

    fun savedHandled() = form.update { it.copy(saved = null) }

    /** Switches an existing rule on (the folder is checked again, files there now are left out) or off. */
    fun setEnabled(on: Boolean) {
        val id = ruleId ?: return
        act {
            when (val result = rules.setEnabled(id, on)) {
                is OwnerResult.Done -> Outcome(message = if (on) PickUpRulesViewModel.SWITCHED_ON else "Switched off: nothing more is picked up by this rule.")
                is OwnerResult.Refused -> Outcome(problem = result.reason)
            }
        }
    }

    fun askDelete() = form.update { it.copy(confirmDelete = true) }

    fun cancelDelete() = form.update { it.copy(confirmDelete = false) }

    /** Deletes the rule and its list of files; the memories it made stay. */
    fun confirmDelete() {
        val id = ruleId ?: return
        form.update { it.copy(confirmDelete = false) }
        act {
            rules.delete(id)
            Outcome(deleted = true)
        }
    }

    fun messageShown() = form.update { it.copy(message = null) }

    private data class Outcome(val problem: String? = null, val message: String? = null, val saved: PickUpRuleId? = null, val deleted: Boolean = false)

    private fun act(action: suspend () -> Outcome) {
        if (form.value.isSaving) return
        form.update { it.copy(isSaving = true, problem = null) }
        viewModelScope.launch {
            val outcome = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Outcome(problem = PickUpRulesViewModel.COULD_NOT_SAVE)
            }
            form.update {
                it.copy(
                    isSaving = false,
                    problem = outcome.problem,
                    message = outcome.message?.let { text -> UserMessage(text, ++messages) } ?: it.message,
                    saved = outcome.saved ?: it.saved,
                    deleted = outcome.deleted || it.deleted,
                )
            }
        }
    }

    companion object {
        const val HAS_RULE: String = "This folder has a pick-up rule already. Open that rule to change it."
        const val CHOOSE_SPACE: String = "Choose the memory space the files go into."
        const val CHOOSE_FOLDER: String = "Choose the folder to pick files up from."
    }
}

/** What the Folder pick-up screens say about it, whatever the rule. */
object PickUpWords {
    const val WHAT_ARRIVES: String =
        "Only text files are picked up, each of at most 1 MB, and at most 5 MB a day in all. Files already in the folder " +
            "when the rule is switched on are left out: only files that arrive later are picked up. Sub-folders are not read."

    const val WHEN: String = "The folder is looked at about once a day, while the phone is charging and not in use."

    const val NO_MODEL: String =
        "Nothing is sent to a model by picking up: the files are only saved in memory on this phone. Agents that may read " +
            "this space can recall them, and what they recall goes to their model like any memory."

    const val READ_ONLY: String =
        "The folder must be read-only for agents, so that only you can put files in it. If agents may change it again, the " +
            "rule switches itself off."
}
