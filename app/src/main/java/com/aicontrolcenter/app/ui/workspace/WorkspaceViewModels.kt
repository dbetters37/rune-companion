package com.aicontrolcenter.app.ui.workspace

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicontrolcenter.app.operator.mimeTypeOf
import com.aicontrolcenter.app.ui.components.UserMessage
import com.aicontrolcenter.app.ui.components.fileKindOf
import com.aicontrolcenter.app.ui.components.formatBytes
import com.aicontrolcenter.app.ui.components.formatCompactTimestamp
import com.aicontrolcenter.app.ui.components.plural
import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.activity.ActivityRecorder
import com.aicontrolcenter.core.activity.ActivityType
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.DocumentEntry
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.core.operator.Workspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.time.ZoneId

/*
 * The owner's file manager for the agents' workspace. It goes through the same Workspace service
 * as the workspace tools, so it has the same sandbox (nothing outside the workspace, no links
 * followed), the same size limits and the same trash, and it sees agents' changes as they happen.
 */

/** A file the owner asked to share; the screen hands it to Android's share sheet once. */
@Immutable
data class ShareRequest(val file: File, val mimeType: String, val title: String)

/** One file or folder in a workspace listing. */
@Immutable
data class WorkspaceEntryUi(
    val path: RelativePath,
    val name: String,
    val isFolder: Boolean,
    /** "12.4 KB · 14:05", "Folder", or why it is not opened. */
    val detail: String,
    /** A link an agent's command created: listed so the owner knows, never opened. */
    val isLink: Boolean,
)

/** One folder level of the path, for the breadcrumb. */
@Immutable
data class Crumb(val title: String, val path: RelativePath)

@Immutable
data class WorkspaceUiState(
    val isLoading: Boolean = true,
    val path: RelativePath = RelativePath.ROOT,
    val crumbs: List<Crumb> = emptyList(),
    val entries: List<WorkspaceEntryUi> = emptyList(),
    /** Why the folder cannot be shown (it was moved or trashed meanwhile, a bad path); null when it can. */
    val problem: String? = null,
    val trashCount: Int = 0,
    /** The trash lists nothing more than [trashCount] says, yet holds leftovers of an interrupted change: it is not empty. */
    val trashHasLeftovers: Boolean = false,
    val isBusy: Boolean = false,
    val share: ShareRequest? = null,
    val message: UserMessage? = null,
) {
    val title: String get() = if (path.isRoot) "Workspace" else path.name
}

/**
 * One workspace folder: its files and folders, folders first, and the owner's actions on them:
 * new folder, import, rename, share and move to the trash. Every action re-reads the workspace;
 * the listing follows every change, the agents' included.
 */
class WorkspaceViewModel(
    private val workspace: Workspace,
    rawPath: String,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private data class Local(val isBusy: Boolean = false, val share: ShareRequest? = null, val message: UserMessage? = null)

    private val parsed = RelativePath.parse(rawPath)
    private val path: RelativePath = (parsed as? RelativePath.Check.Valid)?.path ?: RelativePath.ROOT
    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private data class Listing(val entries: List<WorkspaceEntryUi>, val problem: String?, val trashCount: Int, val trashHasLeftovers: Boolean = false)

    private val listing = workspace.changes.map {
        if (parsed is RelativePath.Check.Invalid) return@map Listing(emptyList(), parsed.reason, 0)
        val now = clock.now()
        val trash = (workspace.listTrash() as? DocResult.Ok)?.value?.size ?: 0
        val leftovers = workspace.trashLeftovers() != null
        when (val result = workspace.list(path)) {
            is DocResult.Ok -> Listing(result.value.map { entryUi(it, now) }, null, trash, leftovers)
            is DocResult.NotFound -> Listing(emptyList(), "This folder is no longer there. It may have been moved or put in the trash.", trash, leftovers)
            else -> Listing(emptyList(), problemOf(result), trash, leftovers)
        }
    }

    val uiState: StateFlow<WorkspaceUiState> = combine(listing, local) { listing, local ->
        WorkspaceUiState(
            isLoading = false,
            path = path,
            crumbs = crumbsOf(path),
            entries = listing.entries,
            problem = listing.problem,
            trashCount = listing.trashCount,
            trashHasLeftovers = listing.trashHasLeftovers,
            isBusy = local.isBusy,
            share = local.share,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspaceUiState(path = path, crumbs = crumbsOf(path)))

    /** Creates a folder named [name] here. */
    fun makeFolder(name: String) = run {
        val target = childOrProblem(name) { return@run it }
        if (workspace.stat(target) is DocResult.Ok) return@run "Something named “${target.name}” is already here."
        when (val result = workspace.makeFolder(target)) {
            is DocResult.Ok -> "Folder “${target.name}” created."
            else -> problemOf(result)
        }
    }

    /** Renames [entry] in place to [newName]; never over something that exists. */
    fun rename(entry: RelativePath, newName: String) = run {
        val target = childOrProblem(newName) { return@run it }
        if (target == entry) return@run "That is already its name."
        when (val result = workspace.move(entry, target, overwrite = false)) {
            is DocResult.Ok -> "Renamed to “${target.name}”."
            is DocResult.AlreadyExists -> "Something named “${target.name}” is already here. Nothing was renamed."
            else -> problemOf(result)
        }
    }

    /** Moves [entry] to the workspace trash, from where it can be restored. */
    fun trash(entry: RelativePath) = run {
        when (val result = workspace.trash(entry)) {
            is DocResult.Ok -> "“${entry.name}” moved to the trash. You can restore it from there."
            else -> problemOf(result)
        }
    }

    /**
     * Copies a document the owner picked into this folder as [name] (another free name if that one
     * is taken; nothing is ever replaced). [open] opens the document; it is closed afterwards.
     */
    fun import(name: String, open: suspend () -> InputStream?) = run {
        val base = safeName(name)
        val target = freeName(base) ?: return@run "Too many files named like “$base” are here already."
        val stream = open() ?: return@run "The file could not be opened. Nothing was imported."
        val result = stream.use { workspace.import(target, it, MAX_IMPORT_BYTES, overwrite = false) }
        when (result) {
            is DocResult.Ok -> {
                activity.record(
                    ActivityType.USER_ACTION, "File imported into the workspace: ${target.value}",
                    "${formatBytes(result.value.sizeBytes ?: 0)}. Agents can read it; changing it needs their workspace permission.",
                )
                "Imported as “${target.name}”."
            }
            is DocResult.TooLarge -> "The file is larger than ${formatBytes(result.limitBytes)}, the most the workspace takes in one file. Nothing was imported."
            else -> problemOf(result)
        }
    }

    /** Asks for the share sheet for [entry]: the app the owner picks gets read access to that one file. */
    fun share(entry: RelativePath) = run(changes = false) {
        when (val result = workspace.resolve(entry)) {
            is DocResult.Ok -> {
                val file = result.value.file
                if (!file.isFile) return@run "“${entry.name}” is not a file that can be shared."
                local.update { it.copy(share = ShareRequest(file, mimeTypeOf(entry.name), entry.name)) }
                activity.record(ActivityType.USER_ACTION, "Workspace file shared: ${entry.value}", "Handed to the app you pick in Android's share screen.")
                null
            }
            else -> problemOf(result)
        }
    }

    /** The share sheet was opened. */
    fun shareHandled() = local.update { it.copy(share = null) }

    fun messageShown() = local.update { it.copy(message = null) }

    private inline fun childOrProblem(name: String, onProblem: (String) -> Nothing): RelativePath {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) onProblem("Enter a name.")
        if ('/' in trimmed) onProblem("A name cannot contain “/”.")
        if (path.isRoot && trimmed == Workspace.TRASH_FOLDER) onProblem("“${Workspace.TRASH_FOLDER}” is reserved for the trash.")
        return when (val check = RelativePath.parse(trimmed)) {
            is RelativePath.Check.Valid -> if (check.path.segments.size == 1) path.child(check.path.value) else onProblem("“$trimmed” cannot be used as a name.")
            is RelativePath.Check.Invalid -> onProblem(check.reason)
        }
    }

    /** [base], or "name (2).ext" and so on, whichever is free here first. */
    private suspend fun freeName(base: String): RelativePath? {
        val stem = base.substringBeforeLast('.', base)
        val ext = base.substring(stem.length)
        for (n in 1..99) {
            val candidate = path.child(if (n == 1) base else "$stem ($n)$ext")
            if (workspace.stat(candidate) is DocResult.NotFound) return candidate
        }
        return null
    }

    private fun entryUi(entry: DocumentEntry, now: Long): WorkspaceEntryUi {
        val isLink = entry.mimeType == LINK_MIME
        return WorkspaceEntryUi(
            path = entry.path,
            name = entry.name,
            isFolder = entry.isFolder,
            detail = when {
                isLink -> "Link · not followed or opened"
                entry.isFolder -> "Folder" + (entry.modifiedAt?.let { " · ${formatCompactTimestamp(it, now, zone)}" } ?: "")
                else -> listOfNotNull(entry.sizeBytes?.let(::formatBytes), entry.modifiedAt?.let { formatCompactTimestamp(it, now, zone) }).joinToString(" · ")
            },
            isLink = isLink,
        )
    }

    /**
     * Runs one change at a time; a non-null outcome is shown as a message. A change ([changes]) asked for while a shell
     * command runs waits until that has ended, which can take a minute: the owner is told so at once.
     */
    private fun run(changes: Boolean = true, action: suspend () -> String?) {
        if (local.value.isBusy) return
        val waits = changes && workspace.commandRunning()
        local.update { it.copy(isBusy = true, message = if (waits) UserMessage(WAITS_FOR_COMMAND, ++messageCounter) else it.message) }
        viewModelScope.launch {
            val text = try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "That did not work: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isBusy = false, message = text?.let { t -> UserMessage(t, ++messageCounter) } ?: it.message) }
        }
    }

    companion object {
        /** The largest file the owner imports at once; the workspace's own limit applies too. */
        const val MAX_IMPORT_BYTES: Long = 50L * 1024 * 1024

        /** How the workspace lists a link an agent's command created. */
        const val LINK_MIME = "inode/symlink"

        /** "Workspace", then each folder down to [path]. */
        fun crumbsOf(path: RelativePath): List<Crumb> {
            val crumbs = mutableListOf(Crumb("Workspace", RelativePath.ROOT))
            var at = RelativePath.ROOT
            for (segment in path.segments) {
                at = at.child(segment)
                crumbs += Crumb(segment, at)
            }
            return crumbs
        }

        /** A name the workspace accepts for a picked document: path separators, control and invisible characters dropped. */
        fun safeName(raw: String): String {
            val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
                .filter { c -> !c.isISOControl() && !com.aicontrolcenter.core.operator.PreviewText.isHidden(c) && !com.aicontrolcenter.core.operator.PreviewText.isUnusualSpace(c.code) }
                .trim().trimStart('.')
                .take(120)
            val parsed = RelativePath.parse(cleaned)
            return if (cleaned.isNotEmpty() && parsed is RelativePath.Check.Valid && parsed.path.segments.size == 1) cleaned else "Imported file"
        }
    }
}

/** What went wrong, for the owner. */
/** Said when the owner asks for a change in the Workspace screens while a shell command they approved is running. */
internal const val WAITS_FOR_COMMAND = "A shell command is running. This is done as soon as it has ended."

internal fun problemOf(result: DocResult<*>): String = when (result) {
    is DocResult.Ok -> "Done."
    is DocResult.NotFound -> "“${result.path.substringAfterLast('/')}” is no longer there."
    is DocResult.AlreadyExists -> "Something named “${result.path.substringAfterLast('/')}” is already there."
    is DocResult.TooLarge -> "Too large: ${formatBytes(result.sizeBytes)}, the limit is ${formatBytes(result.limitBytes)}."
    is DocResult.NotAllowed -> result.reason
    is DocResult.Ambiguous -> "Several entries are named “${result.path.substringAfterLast('/')}”, so nothing was done."
    is DocResult.Failed -> result.reason
}

/** The preview of one workspace file. */
@Immutable
data class FilePreviewUi(
    /** The start of the file as text; null when it is not text. */
    val text: String?,
    /** "Showing the first 64 KB of 2.3 MB", when only part is shown. */
    val partial: String?,
    /** Why the file could not be read, when it could not. */
    val problem: String? = null,
)

@Immutable
data class WorkspaceFileUiState(
    val isLoading: Boolean = true,
    val path: RelativePath = RelativePath.ROOT,
    val name: String = "",
    /** Facts about the file: size, changed, type, where. */
    val facts: List<Pair<String, String>> = emptyList(),
    val preview: FilePreviewUi? = null,
    /** Why it cannot be shown (moved, trashed, a folder, a link); null when it can. */
    val problem: String? = null,
    /** Set once the file was moved to the trash; the screen goes back. */
    val trashed: Boolean = false,
    val isBusy: Boolean = false,
    val share: ShareRequest? = null,
    val message: UserMessage? = null,
)

/**
 * One workspace file, read-only: its facts and, for text, the start of its content exactly as
 * stored (nothing is run, rendered or followed). Share and Move to trash are offered.
 */
class WorkspaceFileViewModel(
    private val workspace: Workspace,
    rawPath: String,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private data class Local(val trashed: Boolean = false, val isBusy: Boolean = false, val share: ShareRequest? = null, val message: UserMessage? = null)

    private val parsed = RelativePath.parse(rawPath)
    private val path: RelativePath = (parsed as? RelativePath.Check.Valid)?.path ?: RelativePath.ROOT
    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private data class Loaded(val facts: List<Pair<String, String>>, val preview: FilePreviewUi?, val problem: String?)

    private val loaded = workspace.changes.map {
        if (parsed is RelativePath.Check.Invalid || path.isRoot) return@map Loaded(emptyList(), null, "This is not a file in the workspace.")
        val entry = when (val stat = workspace.stat(path)) {
            is DocResult.Ok -> stat.value
            is DocResult.NotFound -> return@map Loaded(emptyList(), null, "This file is no longer there. It may have been moved or put in the trash.")
            else -> return@map Loaded(emptyList(), null, problemOf(stat))
        }
        if (entry.isFolder) return@map Loaded(emptyList(), null, "This is a folder.")
        if (entry.mimeType == WorkspaceViewModel.LINK_MIME) {
            return@map Loaded(emptyList(), null, "This is a link an agent's command created. Links are never followed or opened.")
        }
        val now = clock.now()
        val facts = listOfNotNull(
            entry.sizeBytes?.let { "Size" to formatBytes(it) },
            entry.modifiedAt?.let { "Changed" to formatCompactTimestamp(it, now, zone) },
            "Type" to fileKindOf(mimeTypeOf(entry.name)),
            "In" to ("Workspace" + (path.parent?.takeIf { !it.isRoot }?.let { " › " + it.segments.joinToString(" › ") } ?: "")),
        )
        val preview = when (val read = workspace.read(path, 0, PREVIEW_BYTES)) {
            is DocResult.Ok -> previewOf(read.value.bytes, read.value.totalBytes, read.value.truncated)
            else -> FilePreviewUi(null, null, problemOf(read))
        }
        Loaded(facts, preview, null)
    }

    val uiState: StateFlow<WorkspaceFileUiState> = combine(loaded, local) { loaded, local ->
        WorkspaceFileUiState(
            isLoading = false,
            path = path,
            name = path.name.ifEmpty { "File" },
            facts = loaded.facts,
            preview = loaded.preview,
            problem = if (local.trashed) null else loaded.problem,
            trashed = local.trashed,
            isBusy = local.isBusy,
            share = local.share,
            message = local.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspaceFileUiState(path = path, name = path.name))

    fun share() = act(changes = false) {
        when (val result = workspace.resolve(path)) {
            is DocResult.Ok -> if (result.value.file.isFile) {
                local.update { it.copy(share = ShareRequest(result.value.file, mimeTypeOf(path.name), path.name)) }
                activity.record(ActivityType.USER_ACTION, "Workspace file shared: ${path.value}", "Handed to the app you pick in Android's share screen.")
                null
            } else {
                "This file is no longer there."
            }
            else -> problemOf(result)
        }
    }

    fun shareHandled() = local.update { it.copy(share = null) }

    fun trash() = act {
        when (val result = workspace.trash(path)) {
            is DocResult.Ok -> {
                local.update { it.copy(trashed = true) }
                null
            }
            else -> problemOf(result)
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    /** As the folder's: a change asked for while a shell command runs waits for it, and the owner is told so at once. */
    private fun act(changes: Boolean = true, action: suspend () -> String?) {
        if (local.value.isBusy) return
        val waits = changes && workspace.commandRunning()
        local.update { it.copy(isBusy = true, message = if (waits) UserMessage(WAITS_FOR_COMMAND, ++messageCounter) else it.message) }
        viewModelScope.launch {
            val text = try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "That did not work: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isBusy = false, message = text?.let { t -> UserMessage(t, ++messageCounter) } ?: it.message) }
        }
    }

    companion object {
        /** How much of a file the preview shows. */
        const val PREVIEW_BYTES: Long = 64L * 1024

        /**
         * The start of a file as text, if it is text: valid UTF-8 (a character cut at the end is
         * dropped) with no NUL and few other control characters. Otherwise no text.
         */
        fun previewOf(bytes: ByteArray, totalBytes: Long?, truncated: Boolean): FilePreviewUi {
            val partial = if (truncated) "Showing the first ${formatBytes(bytes.size.toLong())}" + (totalBytes?.let { " of ${formatBytes(it)}" } ?: "") else null
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            // A multi-byte character cut by the preview's end is not a reason to call the file binary.
            val text = (0..3).firstNotNullOfOrNull { cut ->
                if (cut > 0 && !truncated) return@firstNotNullOfOrNull null
                if (cut > bytes.size) return@firstNotNullOfOrNull null
                try {
                    decoder.reset().decode(java.nio.ByteBuffer.wrap(bytes, 0, bytes.size - cut)).toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    null
                }
            }
            val looksLikeText = text != null && '\u0000' !in text &&
                text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } <= text.length / 100
            return FilePreviewUi(if (looksLikeText) text else null, partial)
        }
    }
}

/** One item in the workspace trash. */
@Immutable
data class TrashItemUi(val id: String, val name: String, val detail: String, val isFolder: Boolean)

@Immutable
data class WorkspaceTrashUiState(
    val isLoading: Boolean = true,
    val items: List<TrashItemUi> = emptyList(),
    /**
     * Bytes under the trash folder that are no listed item: what a change that was interrupted
     * (the app was stopped in the middle) left behind and that cannot be restored. Null when
     * there is nothing of the kind, and while a change or a shell command is under way: what is
     * there then may be that change's own work, so the screen shows the listed items only.
     */
    val leftoverBytes: Long? = null,
    val isBusy: Boolean = false,
    val message: UserMessage? = null,
) {
    /** Emptying is offered whenever anything at all is in the trash, listed or not. */
    val canEmpty: Boolean get() = items.isNotEmpty() || leftoverBytes != null

    /** What emptying the trash deletes, for the confirmation: the items, the leftovers, or both. */
    val emptyingDeletes: String
        get() {
            val leftovers = leftoverBytes?.let { "leftovers of an interrupted change (${formatBytes(it)})" }
            return when {
                items.isEmpty() && leftovers == null -> "The trash is empty now."
                items.isEmpty() -> "The $leftovers are deleted for good. This cannot be undone."
                leftovers == null -> "${plural(items.size, "item")} ${if (items.size == 1) "is" else "are"} deleted for good. This cannot be undone."
                else -> "${plural(items.size, "item")} and the $leftovers are deleted for good. This cannot be undone."
            }
        }
}

/**
 * The workspace trash, newest first: restoring an item puts it back where it was; emptying
 * deletes everything in it for good, after the owner confirms on screen. That includes what is
 * in the trash without being listed (leftovers of a change the app was stopped in), which is why
 * emptying is offered whenever the trash folder holds anything at all.
 */
class WorkspaceTrashViewModel(
    private val workspace: Workspace,
    private val activity: ActivityRecorder,
    private val clock: Clock,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private data class Local(val isBusy: Boolean = false, val message: UserMessage? = null)

    private val local = MutableStateFlow(Local())
    private var messageCounter = 0L

    private val items = workspace.changes.map {
        val now = clock.now()
        val listed = when (val result = workspace.listTrash()) {
            is DocResult.Ok -> result.value.map { itemUi(it, now) }
            else -> emptyList()
        }
        listed to workspace.trashLeftovers()
    }

    val uiState: StateFlow<WorkspaceTrashUiState> = combine(items, local) { (items, leftovers), local ->
        WorkspaceTrashUiState(isLoading = false, items = items, leftoverBytes = leftovers, isBusy = local.isBusy, message = local.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspaceTrashUiState())

    fun restore(id: String) = run {
        when (val result = workspace.restore(id)) {
            is DocResult.Ok -> "Restored to ${result.value.path.value}."
            is DocResult.AlreadyExists -> "Something is at ${result.path} now, so it was not restored. Rename or trash that first."
            else -> problemOf(result)
        }
    }

    /** Deletes everything in the trash for good, leftovers of interrupted changes included. The screen asks the owner first. */
    fun emptyTrash() = run {
        // While another change is under way the workspace claims no leftovers; what the owner was shown, and confirmed, still goes.
        val leftovers = workspace.trashLeftovers() ?: uiState.value.leftoverBytes
        when (val result = workspace.emptyTrash(null)) {
            is DocResult.Ok -> {
                val deleted = when {
                    result.value > 0 && leftovers != null -> "${plural(result.value, "item")} and leftovers of an interrupted change"
                    result.value > 0 -> plural(result.value, "item")
                    leftovers != null -> "Leftovers of an interrupted change (${formatBytes(leftovers)})"
                    else -> null
                }
                if (deleted != null) activity.record(ActivityType.USER_ACTION, "Workspace trash emptied", "$deleted deleted for good, at your request.")
                when {
                    result.value > 0 -> "${plural(result.value, "item")} deleted for good."
                    leftovers != null -> "What was left in the trash was deleted for good."
                    else -> "The trash was already empty."
                }
            }
            else -> problemOf(result)
        }
    }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun itemUi(item: TrashItem, now: Long): TrashItemUi {
        val from = item.originalPath.substringBeforeLast('/', "")
        return TrashItemUi(
            id = item.id,
            name = item.originalPath.substringAfterLast('/'),
            detail = listOfNotNull(
                // An item the workspace found in the trash without its record: where it was is not known.
                if (item.recovered) "Old place not known, so it is restored to the top of the workspace" else "From " + if (from.isEmpty()) "the workspace" else from,
                "trashed ${formatCompactTimestamp(item.trashedAt, now, zone)}",
                item.sizeBytes?.let(::formatBytes),
            ).joinToString(" · "),
            isFolder = item.isFolder,
        )
    }

    /** Restoring and emptying are changes: asked for while a shell command runs, they wait for it, and the owner is told so at once. */
    private fun run(action: suspend () -> String) {
        if (local.value.isBusy) return
        val waits = workspace.commandRunning()
        local.update { it.copy(isBusy = true, message = if (waits) UserMessage(WAITS_FOR_COMMAND, ++messageCounter) else it.message) }
        viewModelScope.launch {
            val text = try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "That did not work: ${failure.message ?: "storage error"}"
            }
            local.update { it.copy(isBusy = false, message = UserMessage(text, ++messageCounter)) }
        }
    }
}
