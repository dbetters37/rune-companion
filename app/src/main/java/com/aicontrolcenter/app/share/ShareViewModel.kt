package com.aicontrolcenter.app.share

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.ui.components.formatBytes
import com.aicontrolcenter.app.ui.components.formatCount
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.app.ui.memory.spaceColorIndex
import com.aicontrolcenter.app.ui.workspace.problemOf
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.WallClock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.core.memory.MemoryActor
import com.aicontrolcenter.core.memory.MemoryPermissionException
import com.aicontrolcenter.core.memory.MemoryService
import com.aicontrolcenter.core.memory.MemoryValidationException
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.registry.Locality
import com.aicontrolcenter.core.registry.RegistryDirectory
import com.aicontrolcenter.core.registry.effectiveLocality
import com.aicontrolcenter.core.registry.RegistrySnapshot
import com.aicontrolcenter.core.settings.SettingsView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * One shared text or file, as [ShareReceiverActivity] read it within the caps of rule 34.
 *
 * @param name a file's name, or the share's subject for shared text; null when the other app gave none.
 * @param fromFile true for a file (`EXTRA_STREAM`), false for shared text (`EXTRA_TEXT`).
 * @param markdown true for a Markdown file: saved in the workspace with ".md".
 */
@Immutable
data class SharedItem(val name: String?, val text: String, val fromFile: Boolean = false, val markdown: Boolean = false)

/** Where a share may be saved: a memory space the owner can write, or the workspace as a file. */
sealed interface ShareTarget {
    data class Space(val id: SpaceId) : ShareTarget

    data object Workspace : ShareTarget
}

/** One shared item on the screen: what it is called, how big it is, and how it starts. */
@Immutable
data class SharedItemUi(
    val title: String,
    /** "About 1,240 words · 7.4 KB". */
    val size: String,
    /** The first few hundred characters, shown as quoted data. */
    val preview: String,
    /** True when [preview] is only the start. */
    val previewIsStart: Boolean,
    /** How many memories it becomes in a space. */
    val parts: Int,
)

/** One place in the picker. */
@Immutable
data class ShareTargetUi(
    val target: ShareTarget,
    val name: String,
    /** Who can read it: "Researcher and Writer can read it." */
    val detail: String,
    val colorIndex: Int,
)

/** How far saving has come: [done] of [total] memories or files. */
@Immutable
data class ShareProgress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/** What the screen says once it was saved. */
@Immutable
data class ShareDone(val headline: String, val detail: String)

/** What the share screen shows. */
@Immutable
data class ShareUiState(
    val isLoading: Boolean = true,
    val items: List<SharedItemUi> = emptyList(),
    /** "1 text · about 1,240 words · 7.4 KB". */
    val summary: String = "",
    /** A sentence for each part of the share that was left out. */
    val leftOut: List<String> = emptyList(),
    val targets: List<ShareTargetUi> = emptyList(),
    val selected: ShareTarget? = null,
    /** True when there is no memory space yet (only the workspace can be picked). */
    val noSpaces: Boolean = false,
    /** What Save will do: "Saves 3 memories in “Team knowledge”." Null until a place is picked. */
    val saveLine: String? = null,
    val localOnly: Boolean = false,
    /** True when one item is shared: its title can be changed before saving. */
    val asksTitle: Boolean = false,
    /** Set while saving. */
    val saving: ShareProgress? = null,
    /** Set once saved. */
    val done: ShareDone? = null,
    /** Why the last Save did not save (nothing was kept). */
    val problem: String? = null,
) {
    /** Nothing in the share can be saved. */
    val nothingToSave: Boolean get() = !isLoading && items.isEmpty()

    /** Save can be tapped: something to save, a place picked, not saving or saved. */
    val canSave: Boolean get() = !isLoading && items.isNotEmpty() && selected != null && saving == null && done == null
}

/**
 * The share screen's state (rule 34, Tier B; WP16): what was shared and how big it is, a picker of the memory spaces
 * (the owner can write every one) and "Workspace" (as a file), the line [ShareTexts.STORING], and Save.
 *
 * Nothing is stored before [save]. Save stores through the owner's memory path ([MemoryActor.Owner] on [memory]) as
 * imports ([ShareSaving.provenance]: "Shared from another app", never the owner's own words), split into parts within a
 * memory's size, or as files in the workspace folder [ShareSaving.FOLDER]. A save is all or nothing: if a part cannot be
 * stored, or the owner stops it, what this save had stored is taken back. The text itself is never logged; the activity
 * log gets one line saying how much was saved where. Nothing here starts work or calls a model.
 *
 * @param items what was shared (read by the activity, within the caps).
 * @param memory the memory service; used as the owner.
 * @param workspace the agents' workspace, for "Workspace (as a file)".
 * @param settings read only: whether local-only mode is on.
 * @param directory read only: which agents can read each space; null shows no names.
 * @param activity where the one line about a save goes; null logs nothing (the memory service logs each memory).
 * @param leftOut sentences for the parts of the share that were left out.
 * @param computation where the summary and the parts are worked out (off the main thread).
 */
class ShareViewModel(
    private val items: List<SharedItem>,
    private val memory: MemoryService,
    private val workspace: Workspace,
    private val settings: SettingsView,
    private val directory: RegistryDirectory? = null,
    private val activity: ActivityRecorder? = null,
    private val leftOut: List<String> = emptyList(),
    private val clock: Clock = WallClock,
    private val computation: CoroutineContext = Dispatchers.Default,
) : ViewModel() {
    private data class Local(
        val selected: ShareTarget? = null,
        val saving: ShareProgress? = null,
        val done: ShareDone? = null,
        val problem: String? = null,
    )

    private class Summary(val items: List<SharedItemUi>, val line: String, val parts: Int)

    private val local = MutableStateFlow(Local())
    private var saveJob: Job? = null

    /** The title of the one shared item (shown only then); read at the moment Save is tapped. */
    val title: TextFieldState = TextFieldState(
        if (items.size == 1) (items.single().name ?: ShareSaving.DEFAULT_TITLE).take(ShareSaving.MAX_TYPED_TITLE_CHARS) else "",
    )

    /** Worked out once, off the main thread: a long share is counted once, never per change of the screen. */
    private val summary = viewModelScope.async(computation) { summarise(items) }

    val uiState: StateFlow<ShareUiState> = combine(
        flow { emit(summary.await()) },
        memory.observeSpaces(),
        directory?.observe() ?: flowOf(RegistrySnapshot()),
        settings.settings.map { it.localOnlyMode }.distinctUntilChanged(),
        local,
    ) { summary, spaces, snapshot, localOnly, local ->
        val targets = targetsOf(spaces, snapshot)
        val selected = local.selected?.takeIf { chosen -> targets.any { it.target == chosen } }
        ShareUiState(
            isLoading = false,
            items = summary.items,
            summary = summary.line,
            leftOut = leftOut,
            targets = targets,
            selected = selected,
            noSpaces = spaces.isEmpty(),
            saveLine = selected?.let { saveLine(it, targets, summary.parts) + whatLeaves(it, snapshot, localOnly) },
            localOnly = localOnly,
            asksTitle = items.size == 1,
            saving = local.saving,
            done = local.done,
            problem = local.problem,
        )
    }.flowOn(computation).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShareUiState())

    /** The owner picked where to save it; stores nothing. */
    fun select(target: ShareTarget) {
        if (local.value.saving != null || local.value.done != null) return
        local.update { it.copy(selected = target, problem = null) }
    }

    /**
     * Saves the share where the owner picked (rule 34: only now is anything stored). All or nothing: what this save
     * stored is taken back when a part fails or [stopSaving] is called.
     */
    fun save() {
        val state = local.value
        if (state.saving != null || state.done != null || items.isEmpty()) return
        val target = state.selected ?: return local.update { it.copy(problem = ShareTexts.CHOOSE_FIRST) }
        // Read at the tap: the field may be a key ahead of any state derived from it.
        val typed = title.text.toString().trim()
        saveJob = viewModelScope.launch {
            local.update { it.copy(saving = ShareProgress(0, 0), problem = null) }
            try {
                val done = when (target) {
                    is ShareTarget.Space -> saveInSpace(target.id, typed)
                    ShareTarget.Workspace -> saveInWorkspace(typed)
                }
                local.update { it.copy(saving = null, done = done) }
            } catch (stopped: CancellationException) {
                local.update { it.copy(saving = null, problem = ShareTexts.STOPPED) }
                throw stopped
            } catch (refused: ShareProblem) {
                local.update { it.copy(saving = null, problem = ShareTexts.notSaved(refused.reason)) }
            } catch (refused: MemoryValidationException) {
                local.update { it.copy(saving = null, problem = ShareTexts.notSaved(refused.message ?: ShareTexts.STORE_FAILED)) }
            } catch (refused: MemoryPermissionException) {
                local.update { it.copy(saving = null, problem = ShareTexts.notSaved(refused.message ?: ShareTexts.STORE_FAILED)) }
            } catch (failure: Exception) {
                local.update { it.copy(saving = null, problem = ShareTexts.notSaved(ShareTexts.STORE_FAILED)) }
            }
        }
    }

    /** Stops a save under way; what it had stored is taken back. */
    fun stopSaving() {
        saveJob?.cancel()
    }

    private suspend fun saveInSpace(spaceId: SpaceId, typed: String): ShareDone {
        val space = memory.observeSpaces().first().firstOrNull { it.id == spaceId } ?: throw ShareProblem(ShareTexts.SPACE_GONE)
        val now = clock.now()
        val drafts = withContext(computation) {
            items.flatMap { item -> ShareSaving.drafts(item, titleOf(item, typed), spaceId, now) }
        }
        val made = ArrayList<MemoryId>()
        var finished = false
        try {
            local.update { it.copy(saving = ShareProgress(0, drafts.size)) }
            for ((index, draft) in drafts.withIndex()) {
                made += memory.remember(MemoryActor.Owner, draft).id
                local.update { it.copy(saving = ShareProgress(index + 1, drafts.size)) }
            }
            finished = true
        } finally {
            if (!finished) withContext(NonCancellable) { takeBackMemories(made) }
        }
        val what = plural(drafts.size, "memory", "memories")
        log("Saved what another app shared", "$what in the memory space “${space.name}”.")
        val readers = readersOf(spaceId)?.let { " " + ShareTexts.readers(it) }.orEmpty()
        return ShareDone(ShareTexts.SAVED, "Saved in “${space.name}” as $what, marked as shared from another app.$readers")
    }

    private suspend fun saveInWorkspace(typed: String): ShareDone {
        val folder = RelativePath.of(ShareSaving.FOLDER)
        when (val made = workspace.makeFolder(folder)) {
            is DocResult.Ok -> Unit
            else -> throw ShareProblem(problemOf(made))
        }
        val written = ArrayList<RelativePath>()
        var finished = false
        try {
            local.update { it.copy(saving = ShareProgress(0, items.size)) }
            for ((index, item) in items.withIndex()) {
                val base = ShareSaving.fileName(item, titleOf(item, typed))
                val path = freeName(folder, base) ?: throw ShareProblem("Too many files named like “$base” are in “${ShareSaving.FOLDER}” already.")
                val bytes = withContext(computation) { item.text.toByteArray(Charsets.UTF_8) }
                when (val result = workspace.write(path, bytes, overwrite = false)) {
                    is DocResult.Ok -> written += path
                    else -> throw ShareProblem(problemOf(result))
                }
                local.update { it.copy(saving = ShareProgress(index + 1, items.size)) }
            }
            finished = true
        } finally {
            if (!finished) withContext(NonCancellable) { takeBackFiles(written) }
        }
        val where = if (written.size == 1) "“${written.single().value}”" else "${plural(written.size, "file")} in the folder “${ShareSaving.FOLDER}”"
        log("Saved what another app shared", "${plural(written.size, "file")} in the workspace folder “${ShareSaving.FOLDER}”.")
        return ShareDone(ShareTexts.SAVED, "Saved in the workspace as $where.")
    }

    /** The names of the agents that can read [space] now; null without a directory or when it cannot be read. */
    private suspend fun readersOf(space: SpaceId): List<String>? {
        val known = directory ?: return null
        return try {
            known.snapshot().agents.map { it.profile }.filter { space in it.memory.readableSpaces }.map { it.name }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun titleOf(item: SharedItem, typed: String): String =
        if (items.size == 1) typed.ifEmpty { item.name ?: ShareSaving.DEFAULT_TITLE } else item.name ?: ShareSaving.DEFAULT_TITLE

    /** [base], or "name (2).txt" and so on, whichever is free in [folder] first. */
    private suspend fun freeName(folder: RelativePath, base: String): RelativePath? {
        val stem = base.substringBeforeLast('.', base)
        val ending = base.substring(stem.length)
        for (n in 1..99) {
            val candidate = folder.child(if (n == 1) base else "$stem ($n)$ending")
            if (workspace.stat(candidate) is DocResult.NotFound) return candidate
        }
        return null
    }

    private suspend fun takeBackMemories(made: List<MemoryId>) {
        for (id in made) {
            try {
                memory.forget(MemoryActor.Owner, id)
            } catch (_: Exception) {
                // Already gone, or the store failed: the owner can delete it in Memory.
            }
        }
    }

    /** Files this save wrote go to the trash and are then deleted from it for good; nothing else in the trash is touched. */
    private suspend fun takeBackFiles(written: List<RelativePath>) {
        val trashed = written.mapNotNull { path ->
            try {
                (workspace.trash(path) as? DocResult.Ok)?.value?.id
            } catch (_: Exception) {
                null
            }
        }
        if (trashed.isEmpty()) return
        try {
            workspace.emptyTrash(trashed.toSet(), leftovers = false)
        } catch (_: Exception) {
            // Left in the trash, where the owner can delete them.
        }
    }

    private suspend fun log(title: String, detail: String) {
        val recorder = activity ?: return
        try {
            recorder.record(ActivityType.USER_ACTION, title, detail)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The save itself is done; a missing log line is not worth undoing it.
        }
    }

    private fun targetsOf(spaces: List<KnowledgeSpace>, snapshot: RegistrySnapshot): List<ShareTargetUi> {
        val agents = snapshot.agents.map { it.profile }
        return spaces.sortedBy { it.name.lowercase() }.map { space ->
            val readers = agents.filter { space.id in it.memory.readableSpaces }.map { it.name }
            ShareTargetUi(
                target = ShareTarget.Space(space.id),
                name = space.name,
                detail = if (directory == null) "" else ShareTexts.readers(readers),
                colorIndex = spaceColorIndex(space.id),
            )
        } + ShareTargetUi(ShareTarget.Workspace, ShareTexts.WORKSPACE, ShareTexts.WORKSPACE_DETAIL, colorIndex = 0)
    }

    private fun saveLine(target: ShareTarget, targets: List<ShareTargetUi>, parts: Int): String = when (target) {
        is ShareTarget.Space -> {
            val name = targets.firstOrNull { it.target == target }?.name.orEmpty()
            "Saves ${plural(parts, "memory", "memories")} in “$name”." + if (parts > items.size) " " + ShareTexts.SPLIT else ""
        }
        ShareTarget.Workspace -> "Saves ${plural(items.size, "text file")} in the workspace folder “${ShareSaving.FOLDER}”."
    }

    /**
     * What leaves the phone once it is saved in [target] (D3B-3), from the registry as read: who can recall it (a space's
     * readers) or read it (agents with the workspace tools), and whether their models are off this phone and where.
     * Nothing without a directory to read it from.
     */
    private fun whatLeaves(target: ShareTarget, snapshot: RegistrySnapshot, localOnly: Boolean): String {
        if (directory == null) return ""
        val agents = snapshot.agents.map { it.profile }
        val readers = when (target) {
            is ShareTarget.Space -> agents.filter { target.id in it.memory.readableSpaces }
            ShareTarget.Workspace -> agents.filter { WORKSPACE_READ in it.toolIds }
        }
        // Each reader's models as registered: those off this phone, by provider; a reader with none registered has no model yet.
        val providers = readers.associateWith { agent ->
            (listOfNotNull(agent.modelId) + agent.fallbackModelIds).mapNotNull { id -> snapshot.modelsById[id]?.let(snapshot::provider) }
        }
        val away = providers.mapValues { (_, all) -> all.filter { it.effectiveLocality != Locality.ON_DEVICE }.map { it.name }.distinct() }
            .filterValues { it.isNotEmpty() }
        val sentence = ShareTexts.whatLeaves(
            readers = readers.map { it.name },
            offPhone = away.keys.map { it.name },
            destinations = away.values.flatten().distinct(),
            noModel = providers.filterValues { it.isEmpty() }.keys.map { it.name },
            localOnly = localOnly,
            workspace = target == ShareTarget.Workspace,
        )
        return " $sentence"
    }

    private fun summarise(items: List<SharedItem>): Summary {
        var parts = 0
        var words = 0L
        var bytes = 0L
        val shown = items.map { item ->
            val itemWords = ShareSaving.words(item.text)
            val itemBytes = utf8Length(item.text)
            val itemParts = ShareSaving.parts(item.text).size
            words += itemWords
            bytes += itemBytes
            parts += itemParts
            val start = item.text.trim().take(PREVIEW_CHARS)
            SharedItemUi(
                title = item.name ?: ShareSaving.DEFAULT_TITLE,
                size = "${wordsText(itemWords.toLong()).replaceFirstChar(Char::uppercaseChar)} · ${formatBytes(itemBytes)}",
                preview = start.trimEnd(),
                previewIsStart = start.length < item.text.trim().length,
                parts = itemParts,
            )
        }
        val texts = items.count { !it.fromFile }
        val files = items.count { it.fromFile }
        val what = listOfNotNull(
            plural(texts, "text").takeIf { texts > 0 },
            plural(files, "file").takeIf { files > 0 },
        ).joinToString(" and ")
        return Summary(shown, if (items.isEmpty()) "" else "$what · ${wordsText(words)} · ${formatBytes(bytes)}", parts)
    }

    private fun wordsText(words: Long): String = when {
        words == 1L -> "1 word"
        words < 100 -> "$words words"
        else -> "about ${formatCount(words)} words"
    }

    /** The owner tapped Done (or closed the screen): what the screen held is forgotten. */
    override fun onCleared() {
        title.clearText()
        super.onCleared()
    }

    /** Why a save did not happen, in the owner's words. */
    private class ShareProblem(val reason: String) : Exception(reason)

    private companion object {
        /** How much of each item the screen shows. */
        const val PREVIEW_CHARS = 400

        /** The tool an agent reads the workspace with. */
        val WORKSPACE_READ = com.aicontrolcenter.core.ToolId("workspace.read")

        fun utf8Length(text: String): Long {
            var count = 0L
            var i = 0
            while (i < text.length) {
                val c = text[i]
                count += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                        i++
                        4
                    }
                    else -> 3
                }
                i++
            }
            return count
        }
    }
}

/** The share screen's words. */
object ShareTexts {
    const val TITLE: String = "Save to memory"

    private const val STORING_FREE: String = "Storing is free. Only what agents recall is sent to a model."

    /** The plan's cost and privacy line (owner request #43). */
    const val STORING: String =
        "$STORING_FREE A cloud model would receive this text; local-only mode and space access keep it on the phone."

    const val LOCAL_ONLY_ON: String = "Local-only mode is on: agents use only models on this phone, so what they recall stays on it."

    /** The cost and privacy line with local-only mode on: the cloud sentence is replaced, not added to (D3B-3). */
    const val STORING_LOCAL_ONLY: String = "$STORING_FREE $LOCAL_ONLY_ON"

    /**
     * What leaves the phone once it is saved in the chosen place, said right above Save (D3B-3): [readers] can recall it
     * from a space (or read it in the [workspace]); [offPhone] are those of them whose model is off this phone, at
     * [destinations]. "Researcher and Writer can recall it. They use a model off this phone (xAI), so what they recall
     * from it is sent there."
     */
    fun whatLeaves(
        readers: List<String>,
        offPhone: List<String>,
        destinations: List<String>,
        localOnly: Boolean,
        workspace: Boolean,
        noModel: List<String> = emptyList(),
    ): String {
        val verb = if (workspace) "read" else "recall"
        if (readers.isEmpty()) {
            return if (workspace) "No agent can use the workspace yet, so it stays on this phone until you let one."
            else "No agent can read it yet, so it stays on this phone until you let one."
        }
        val who = "${names(readers)} can $verb it."
        val one = readers.size == 1
        return when {
            localOnly -> "$who Local-only mode is on, so what ${if (one) readers[0] else "they"} ${if (one) verb + "s" else verb} stays on this phone."
            offPhone.isEmpty() && noModel.isNotEmpty() -> {
                val none = noModel.size == 1
                "$who ${if (noModel.size == readers.size) (if (none) "It has" else "They have") else names(noModel) + (if (none) " has" else " have")} " +
                    "no model yet: what ${if (none) noModel[0] + " " + verb + "s" else "they $verb"} goes to the model you give ${if (none) "it" else "them"}."
            }
            offPhone.isEmpty() -> "${names(readers)} can $verb it with ${if (one) "a model" else "models"} on this phone, so it stays here."
            else -> {
                val single = offPhone.size == 1
                val subject = when {
                    single -> offPhone[0]
                    offPhone.size == readers.size -> "They"
                    else -> names(offPhone)
                }
                val model = if (destinations.size == 1) "a model" else "models"
                val reader = if (single) offPhone[0] else "they"
                "$who $subject ${if (single) "uses" else "use"} $model off this phone (${destinations.joinToString(", ")}), " +
                    "so what $reader ${if (single) verb + "s" else verb} from it is sent there."
            }
        }
    }

    /** "Researcher", "Researcher and Writer", "Researcher, Writer and Analyst", "A, B, C and 2 more". */
    private fun names(all: List<String>): String = when (all.size) {
        1 -> all[0]
        2 -> "${all[0]} and ${all[1]}"
        3 -> "${all[0]}, ${all[1]} and ${all[2]}"
        else -> "${all[0]}, ${all[1]}, ${all[2]} and ${all.size - 3} more"
    }

    /** That the text is data, never the owner's instructions. */
    const val AS_DATA: String =
        "It is kept as text another app shared, never as your own words: agents who read it take it as information, not as instructions from you."

    const val WHERE: String = "Where to save it"
    const val WORKSPACE: String = "Workspace (as a file)"
    const val WORKSPACE_DETAIL: String =
        "A text file in the workspace folder “${ShareSaving.FOLDER}”. Agents allowed to use the workspace can read it."
    const val NO_SPACES: String =
        "There is no memory space yet. You can make one in the app (Memory › Spaces), or save this in the workspace now."
    const val SPLIT: String = "Long text is split into parts of up to 20,000 characters, one memory each."

    const val SAVE: String = "Save"
    const val DONT_SAVE: String = "Don't save"
    const val STOP: String = "Stop saving"
    const val DONE: String = "Done"
    const val CLOSE: String = "Close"
    const val TITLE_FIELD: String = "Title"
    const val CHOOSE_FIRST: String = "Choose where to save it first."
    const val SAVED: String = "Saved"
    const val STOPPED: String = "Stopped. Nothing was saved."
    const val STORE_FAILED: String = "it could not be stored just now. Try again."
    const val SPACE_GONE: String = "that memory space no longer exists. Choose another place."
    const val READING: String = "Reading what was shared…"
    const val NOTHING_TITLE: String = "Nothing to save"

    fun notSaved(reason: String): String =
        "Nothing was saved: " + reason.replaceFirstChar(Char::lowercaseChar).let { if (it.endsWith('.')) it else "$it." }

    /** Who can read a space: "Researcher and Writer can read it." */
    fun readers(names: List<String>): String = when (names.size) {
        0 -> "No agent can read it yet. You choose who can in Memory › Spaces."
        1 -> "${names[0]} can read it."
        2 -> "${names[0]} and ${names[1]} can read it."
        3 -> "${names[0]}, ${names[1]} and ${names[2]} can read it."
        else -> "${names[0]}, ${names[1]}, ${names[2]} and ${names.size - 3} more can read it."
    }
}
