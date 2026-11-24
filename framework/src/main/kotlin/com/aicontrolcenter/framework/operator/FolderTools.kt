package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.SharedFolder
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.CapabilityConstraint
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
import com.aicontrolcenter.framework.operator.OperatorText.size
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import java.security.MessageDigest

/**
 * The shared-folder tools (`folders.*`): files in folders the owner picked, through
 * [com.aicontrolcenter.core.operator.SharedFolders]. Every call is constrained to one folder
 * ([CapabilityConstraint.Folder]), so a grant for one folder never reaches another. A folder is
 * named by its name or id; calling with an unknown name answers with the folders that exist.
 */
internal object FolderTools {
    fun create(d: OperatorToolDependencies): List<Tool> = listOf(
        FoldersList(d), FoldersRead(d), FoldersCopyToWorkspace(d), FoldersWrite(d), FoldersMakeFolder(d), FoldersRename(d),
        FoldersCopyFromWorkspace(d), FoldersDelete(d),
    )

    fun JsonObjectBuilder.folderProp() =
        stringProp("folder", "The shared folder's name or id (\"fld_…\"). Call folders_list without it to see the folders the owner shared.")

    /** The folder an agent named; rejects an unknown or ambiguous name with the list of folders. */
    suspend fun resolve(d: OperatorToolDependencies, raw: String?): SharedFolder {
        val all = d.folders.all()
        val wanted = raw?.trim().orEmpty()
        if (wanted.isEmpty()) throw ToolCallRejected("Name a shared folder. ${list(all)}")
        all.firstOrNull { it.id.value == wanted }?.let { return it }
        val named = all.filter { it.displayName.equals(wanted, ignoreCase = true) }
        return when (named.size) {
            1 -> named.single()
            0 -> throw ToolCallRejected("There is no shared folder called ${q(wanted)}. ${list(all)}")
            else -> throw ToolCallRejected("${named.size} shared folders are called ${q(wanted)}; name one by its id. ${list(named)}")
        }
    }

    fun list(all: List<SharedFolder>): String =
        if (all.isEmpty()) "The owner has not shared any folder (they can in Tools › Folders)."
        else "Shared folders: " + all.joinToString("; ") { "${q(it.displayName)} (id ${it.id.value}${if (it.readOnly) ", read-only" else ""})" } + "."

    /** Why [folder] can no longer be used as it was when assessed, or null. */
    suspend fun folderChange(d: OperatorToolDependencies, folder: SharedFolder, writing: Boolean): String? {
        val now = d.folders.all().firstOrNull { it.id == folder.id } ?: return "the shared folder ${q(folder.displayName)} was removed"
        if (now.treeUri != folder.treeUri) return "the shared folder ${q(folder.displayName)} was replaced"
        if (writing && now.readOnly) return "the shared folder ${q(folder.displayName)} was made read-only"
        return null
    }

    /** The state of [path] in [folder]; a [StateProblem] for anything but found or not found. */
    suspend fun state(d: OperatorToolDependencies, folder: SharedFolder, path: RelativePath, tree: Boolean = false): EntryState =
        when (val r = d.sharedFolders.stat(folder.id, path)) {
            is DocResult.Ok -> EntryState(
                exists = true, isFolder = r.value.isFolder, sizeBytes = r.value.sizeBytes, modifiedAt = r.value.modifiedAt,
                tree = if (tree && r.value.isFolder) treeOf(d, folder.id, path) else null,
            )
            is DocResult.NotFound -> EntryState.ABSENT
            else -> throw StateProblem(OperatorText.problem(r, where(folder, path)))
        }

    private suspend fun treeOf(d: OperatorToolDependencies, folder: FolderId, path: RelativePath): TreeState {
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0
        var folders = 0
        var bytes = 0L
        var seen = 0
        val queue = ArrayDeque(listOf(path))
        while (queue.isNotEmpty()) {
            val entries = when (val r = d.sharedFolders.list(folder, queue.removeFirst())) {
                is DocResult.Ok -> r.value
                else -> throw StateProblem(OperatorText.problem(r, "Looking inside the folder"))
            }
            for (e in entries) {
                if (++seen > d.limits.maxCountedEntries) return TreeState(files, folders, bytes, WorkspaceStates.hex(digest.digest()), complete = false)
                digest.update("${e.path.value}|${e.isFolder}|${e.sizeBytes}|${e.modifiedAt}\n".toByteArray())
                if (e.isFolder) {
                    folders++
                    queue.addLast(e.path)
                } else {
                    files++
                    bytes += e.sizeBytes ?: 0
                }
            }
        }
        return TreeState(files, folders, bytes, WorkspaceStates.hex(digest.digest()), complete = true)
    }

    fun where(folder: SharedFolder, path: RelativePath): String =
        if (path.isRoot) "the shared folder ${q(folder.displayName)}" else "${q(path.value)} in the shared folder ${q(folder.displayName)}"

    fun constraint(folder: SharedFolder) = CapabilityConstraint.Folder(folder.id)

    /** The details line of a call that replaces a file in a shared folder. */
    const val NO_TRASH = "Shared folders have no trash: the file it replaces cannot be recovered, so you approve this every time."

    /** For a call that changes the folder: fails when the owner made it read-only. */
    fun readOnlyProblem(folder: SharedFolder): String? =
        if (folder.readOnly) "The owner made the shared folder ${q(folder.displayName)} read-only, so nothing in it can be changed." else null
}

/** folders.list */
internal class FoldersList(private val d: OperatorToolDependencies) : OperatorTool(
    "folders.list", "List a shared folder",
    "Lists the files and folders directly inside a folder the owner shared, or a folder inside it. " +
        "Without \"folder\", answers with the shared folders that exist.",
    objectSchema {
        with(FolderTools) { folderProp() }
        stringProp("path", "A folder inside the shared folder, e.g. \"2025/receipts\". Omit for its top level.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val folder = FolderTools.resolve(d, args.string("folder"))
        val path = args.path("path", RelativePath.ROOT)
        return assessment(
            "List ${FolderTools.where(folder, path)}",
            prepared {
                val entries = when (val r = d.sharedFolders.list(folder.id, path)) {
                    is DocResult.Ok -> r.value
                    else -> return@prepared ToolOutcome.failure(OperatorText.problem(r, "Listing ${FolderTools.where(folder, path)}"))
                }
                val shown = entries.take(d.limits.maxListed)
                val text = if (entries.isEmpty()) "${FolderTools.where(folder, path).replaceFirstChar { it.uppercase() }} is empty." else
                    "${FolderTools.where(folder, path).replaceFirstChar { it.uppercase() }} holds ${OperatorText.count(entries.size, "entry", "entries")}" +
                        (if (shown.size < entries.size) "; the first ${shown.size} are listed" else "") + ":\n" +
                        shown.joinToString("\n") { WorkspaceStates.entryLine(it) }
                ToolOutcome.success(text, buildJsonArray { shown.forEach { add(WorkspaceStates.entryJson(it)) } })
            },
            constraint = FolderTools.constraint(folder),
        )
    }
}

/** folders.read */
internal class FoldersRead(private val d: OperatorToolDependencies) : OperatorTool(
    "folders.read", "Read from a shared folder",
    "Reads a text file (txt, md, csv, json, html …) in a folder the owner shared. HTML is returned as readable text. " +
        "Reads from the start, at most \"max_bytes\".",
    objectSchema("folder", "path") {
        with(FolderTools) { folderProp() }
        stringProp("path", "The file inside the shared folder.")
        intProp("max_bytes", "How many bytes to read at most. Default 32000.", 1, 200_000)
        boolProp("raw_html", "True to get HTML files as written instead of as text. Default false.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val folder = FolderTools.resolve(d, args.string("folder"))
        val path = args.entryPath("path")
        val max = args.int("max_bytes", d.limits.readBytes, 1..d.limits.maxReadBytes)
        val raw = args.bool("raw_html", false)
        return assessment(
            "Read ${FolderTools.where(folder, path)}",
            prepared {
                when (val r = d.sharedFolders.read(folder.id, path, max.toLong())) {
                    is DocResult.Ok -> TextReads.answer(path.value, r.value.bytes, 0, r.value.totalBytes, r.value.truncated, raw)
                    else -> ToolOutcome.failure(OperatorText.problem(r, "Reading ${FolderTools.where(folder, path)}"))
                }
            },
            constraint = FolderTools.constraint(folder),
        )
    }
}

/** folders.copy_to_workspace */
internal class FoldersCopyToWorkspace(private val d: OperatorToolDependencies) : OperatorTool(
    "folders.copy_to_workspace", "Copy from a shared folder",
    "Copies a file from a folder the owner shared into the workspace. Never replaces a workspace file: choose a free \"to\".",
    objectSchema("folder", "path") {
        with(FolderTools) { folderProp() }
        stringProp("path", "The file inside the shared folder.")
        stringProp("to", "Where to put it in the workspace. Default: the same name at the workspace's top level.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val folder = FolderTools.resolve(d, args.string("folder"))
        val path = args.entryPath("path")
        val to = if (args.has("to")) args.entryPath("to") else RelativePath.of(path.name)
        val summary = "Copy ${FolderTools.where(folder, path)} to the workspace as ${q(to.value)}"
        return orFailing(summary, FolderTools.constraint(folder)) {
            val source = FolderTools.state(d, folder, path)
            val target = WorkspaceStates.of(d.workspace, to)
            val details = listOf("From: ${FolderTools.where(folder, path)}, ${WorkspaceStates.describe(source)}", "To: ${q(to.value)} in the workspace")
            when {
                !source.exists || source.isFolder -> assessment(summary, failing("There is no file ${FolderTools.where(folder, path)}."), details, constraint = FolderTools.constraint(folder))
                target.exists -> assessment(summary, failing("${q(to.value)} already exists in the workspace, and copies never replace a file. Choose another \"to\"."), details, constraint = FolderTools.constraint(folder))
                else -> assessment(
                    "$summary (${size(source.sizeBytes)})",
                    prepared(check = {
                        FolderTools.folderChange(d, folder, writing = false)
                            ?: source.changeTo(FolderTools.state(d, folder, path), FolderTools.where(folder, path))
                            ?: target.changeTo(WorkspaceStates.of(d.workspace, to), q(to.value))
                    }) {
                        // Through the workspace, so its size limits, lock and change signal apply to the copy.
                        when (val r = d.workspace.receive(to, d.limits.maxCopyBytes) { file, max -> d.sharedFolders.copyToWorkspace(folder.id, path, file, max) }) {
                            is DocResult.Ok -> ToolOutcome.success("Copied ${FolderTools.where(folder, path)} to ${q(to.value)} in the workspace (${size(r.value)}).")
                            else -> ToolOutcome.failure(OperatorText.problem(r, "Copying ${FolderTools.where(folder, path)}"))
                        }
                    },
                    details,
                    constraint = FolderTools.constraint(folder),
                )
            }
        }
    }
}

/** folders.write */
internal class FoldersWrite(private val d: OperatorToolDependencies) : OperatorTool(
    "folders.write", "Write in a shared folder",
    "Creates a text file in a folder the owner shared, or replaces an existing file when \"overwrite\" is true. Shared folders have no " +
        "trash, so replacing a file needs the owner's approval every time.",
    objectSchema("folder", "path", "content") {
        with(FolderTools) { folderProp() }
        stringProp("path", "The file inside the shared folder, e.g. \"notes/today.md\".")
        stringProp("content", "The complete text of the file.")
        boolProp("overwrite", "True to replace an existing file. Default false.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val folder = FolderTools.resolve(d, args.string("folder"))
        val path = args.entryPath("path")
        val content = args.text("content", d.limits.maxWriteChars, required = false) ?: throw ToolCallRejected("\"content\" is required.")
        val overwrite = args.bool("overwrite", false)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val c = FolderTools.constraint(folder)
        val summary = "Write ${FolderTools.where(folder, path)} (${size(bytes.size.toLong())})"
        val details = listOf("File: ${FolderTools.where(folder, path)}", "New content: ${size(bytes.size.toLong())}, ${WorkspaceWrite.preview(content)}")
        FolderTools.readOnlyProblem(folder)?.let { return assessment(summary, failing(it), details, constraint = c) }
        return orFailing(summary, c) {
            val before = FolderTools.state(d, folder, path)
            when {
                before.isFolder -> assessment(summary, failing("${FolderTools.where(folder, path)} is a folder."), details, constraint = c)
                before.exists && !overwrite -> assessment(summary, failing("${FolderTools.where(folder, path)} already exists; pass \"overwrite\": true to replace it."), details, constraint = c)
                else -> {
                    // Replacing a file the owner keeps there cannot be undone: approved every time.
                    val replaced = if (before.exists) 1 else 0
                    val risk = if (before.exists) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING
                    assessment(
                        if (before.exists) "Replace ${FolderTools.where(folder, path)} (${size(before.sizeBytes)}) with new text (${size(bytes.size.toLong())}), for good"
                        else "Create ${FolderTools.where(folder, path)} (${size(bytes.size.toLong())})",
                        prepared(check = {
                            FolderTools.folderChange(d, folder, writing = true)
                                ?: before.changeTo(FolderTools.state(d, folder, path), FolderTools.where(folder, path))
                        }) { ctx ->
                            BulkRule.take(d, ctx, replaced, risk)?.let { return@prepared ToolOutcome.failure(it) }
                            when (val r = d.sharedFolders.write(folder.id, path, bytes, MimeTypes.of(path.name), overwrite = before.exists)) {
                                is DocResult.Ok -> ToolOutcome.success("${if (before.exists) "Replaced" else "Created"} ${FolderTools.where(folder, path)}.")
                                else -> ToolOutcome.failure(OperatorText.problem(r, "Writing ${FolderTools.where(folder, path)}"))
                            }
                        },
                        details + listOfNotNull(if (before.exists) FolderTools.NO_TRASH else null),
                        risk = risk,
                        constraint = c,
                    )
                }
            }
        }
    }
}

/** folders.make_folder */
internal class FoldersMakeFolder(private val d: OperatorToolDependencies) : OperatorTool(
    "folders.make_folder", "Make a folder in a shared folder",
    "Creates a folder (and missing parents) inside a folder the owner shared. An existing folder is left as it is.",
    objectSchema("folder", "path") {
        with(FolderTools) { folderProp() }
        stringProp("path", "The new folder inside the shared folder.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val folder = FolderTools.resolve(d, args.string("folder"))
        val path = args.entryPath("path")
        val c = FolderTools.constraint(folder)
        val summary = "Create the folder ${FolderTools.where(folder, path)}"
        FolderTools.readOnlyProblem(folder)?.let { return assessment(summary, failing(it), constraint = c) }
        return orFailing(summary, c) {
            val before = FolderTools.state(d, folder, path)
            when {
                before.exists && !before.isFolder -> assessment(summary, failing("A file ${FolderTools.where(folder, path)} already exists."), constraint = c)
                before.exists -> assessment(summary, prepared { ToolOutcome.success("${FolderTools.where(folder, path)} already exists.") }, constraint = c)
                else -> assessment(
                    summary,
                    prepared(check = {
                        FolderTools.folderChange(d, folder, writing = true) ?: before.changeTo(FolderTools.state(d, folder, path), FolderTools.where(folder, path))
                    }) {
                        when (val r = d.sharedFolders.makeFolder(folder.id, path)) {
                            is DocResult.Ok -> ToolOutcome.success("Created ${FolderTools.where(folder, path)}.")
                            else -> ToolOutcome.failure(OperatorText.problem(r, "Creating ${FolderTools.where(folder, path)}"))
                        }
                    },
                    constraint = c,
                )
            }
        }
    }
}

/** folders.rename */
internal class FoldersRename(private val d: OperatorToolDependencies) : OperatorTool(
    "folders.rename", "Rename in a shared folder",
    "Renames a file or folder in place inside a folder the owner shared. \"new_name\" is a single name, not a path.",
    objectSchema("folder", "path", "new_name") {
        with(FolderTools) { folderProp() }
        stringProp("path", "The file or folder to rename.")
        stringProp("new_name", "Its new name, e.g. \"report-final.pdf\".")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val folder = FolderTools.resolve(d, args.string("folder"))
        val path = args.entryPath("path")
        val newName = args.requireString("new_name")
        val target = try {
            path.parent!!.child(newName)
        } catch (e: IllegalArgumentException) {
            throw ToolCallRejected("\"new_name\" must be a single valid name: ${e.message}")
        }
        if (target == path) throw ToolCallRejected("The new name is the same as the old one.")
        val c = FolderTools.constraint(folder)
        val summary = "Rename ${FolderTools.where(folder, path)} to ${q(newName)}"
        FolderTools.readOnlyProblem(folder)?.let { return assessment(summary, failing(it), constraint = c) }
        return orFailing(summary, c) {
            val source = FolderTools.state(d, folder, path)
            val existing = FolderTools.state(d, folder, target)
            when {
                !source.exists -> assessment(summary, failing("There is nothing at ${FolderTools.where(folder, path)}."), constraint = c)
                existing.exists -> assessment(summary, failing("${FolderTools.where(folder, target)} already exists."), constraint = c)
                else -> assessment(
                    summary,
                    prepared(check = {
                        FolderTools.folderChange(d, folder, writing = true)
                            ?: source.changeTo(FolderTools.state(d, folder, path), FolderTools.where(folder, path))
                            ?: existing.changeTo(FolderTools.state(d, folder, target), FolderTools.where(folder, target))
                    }) {
                        when (val r = d.sharedFolders.rename(folder.id, path, newName)) {
                            is DocResult.Ok -> ToolOutcome.success("Renamed ${FolderTools.where(folder, path)} to ${q(newName)}.")
                            else -> ToolOutcome.failure(OperatorText.problem(r, "Renaming ${FolderTools.where(folder, path)}"))
                        }
                    },
                    listOf("Now: ${WorkspaceStates.describe(source)}"),
                    constraint = c,
                )
            }
        }
    }
}

/** folders.copy_from_workspace */
internal class FoldersCopyFromWorkspace(private val d: OperatorToolDependencies) : OperatorTool(
    "folders.copy_from_workspace", "Copy into a shared folder",
    "Copies a workspace file into a folder the owner shared. An existing file there is replaced only with \"overwrite\": true, " +
        "and then only with the owner's approval, every time (shared folders have no trash).",
    objectSchema("from", "folder", "to") {
        stringProp("from", "The workspace file to copy.")
        with(FolderTools) { folderProp() }
        stringProp("to", "Where to put it inside the shared folder, e.g. \"exports/report.csv\".")
        boolProp("overwrite", "True to replace an existing file. Default false.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val from = args.entryPath("from")
        val folder = FolderTools.resolve(d, args.string("folder"))
        val to = args.entryPath("to")
        val overwrite = args.bool("overwrite", false)
        val c = FolderTools.constraint(folder)
        val summary = "Copy the workspace file ${q(from.value)} to ${FolderTools.where(folder, to)}"
        FolderTools.readOnlyProblem(folder)?.let { return assessment(summary, failing(it), constraint = c) }
        return orFailing(summary, c) {
            val source = WorkspaceStates.of(d.workspace, from, hashUpTo = d.limits.maxCopyBytes)
            val target = FolderTools.state(d, folder, to)
            val details = listOf("From: ${q(from.value)} in the workspace, ${WorkspaceStates.describe(source)}", "To: ${FolderTools.where(folder, to)}, now ${WorkspaceStates.describe(target)}")
            when {
                !source.exists || source.isFolder -> assessment(summary, failing("There is no workspace file ${q(from.value)}."), details, constraint = c)
                source.sha256 == null -> assessment(summary, failing("${q(from.value)} is larger than ${size(d.limits.maxCopyBytes)}."), details, constraint = c)
                target.isFolder -> assessment(summary, failing("${FolderTools.where(folder, to)} is a folder."), details, constraint = c)
                target.exists && !overwrite -> assessment(summary, failing("${FolderTools.where(folder, to)} already exists; pass \"overwrite\": true to replace it."), details, constraint = c)
                else -> {
                    val replaced = if (target.exists) 1 else 0
                    val risk = if (target.exists) RiskLevel.CONSEQUENTIAL else RiskLevel.MODIFYING
                    assessment(
                        summary + if (target.exists) ", replacing the file there (${size(target.sizeBytes)}) for good" else " (${size(source.sizeBytes)})",
                        prepared(check = {
                            FolderTools.folderChange(d, folder, writing = true)
                                ?: source.changeTo(WorkspaceStates.of(d.workspace, from, hashUpTo = d.limits.maxCopyBytes), q(from.value))
                                ?: target.changeTo(FolderTools.state(d, folder, to), FolderTools.where(folder, to))
                        }) { ctx ->
                            val file = when (val r = d.workspace.resolve(from)) {
                                is DocResult.Ok -> r.value
                                else -> return@prepared ToolOutcome.failure(OperatorText.problem(r, "Copying ${q(from.value)}"))
                            }
                            BulkRule.take(d, ctx, replaced, risk)?.let { return@prepared ToolOutcome.failure(it) }
                            when (val r = d.sharedFolders.copyFromWorkspace(file, folder.id, to, overwrite = target.exists)) {
                                is DocResult.Ok -> ToolOutcome.success("Copied ${q(from.value)} to ${FolderTools.where(folder, to)}.")
                                else -> ToolOutcome.failure(OperatorText.problem(r, "Copying ${q(from.value)}"))
                            }
                        },
                        details + listOfNotNull(if (target.exists) FolderTools.NO_TRASH else null),
                        risk = risk,
                        constraint = c,
                    )
                }
            }
        }
    }
}

/** folders.delete */
internal class FoldersDelete(private val d: OperatorToolDependencies) : OperatorTool(
    "folders.delete", "Delete in a shared folder",
    "Deletes a file, or a folder with everything in it, from a folder the owner shared. There is no trash: the owner approves every time.",
    objectSchema("folder", "path") {
        with(FolderTools) { folderProp() }
        stringProp("path", "The file or folder to delete, inside the shared folder (never the shared folder itself).")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val folder = FolderTools.resolve(d, args.string("folder"))
        val path = args.entryPath("path")
        val c = FolderTools.constraint(folder)
        val summary = "Delete permanently ${FolderTools.where(folder, path)}"
        FolderTools.readOnlyProblem(folder)?.let { return assessment(summary, failing(it), constraint = c) }
        return orFailing(summary, c) {
            val before = FolderTools.state(d, folder, path, tree = true)
            val tree = before.tree
            val what = when {
                tree != null -> "a folder with ${OperatorText.count(tree.files, "file")} and ${OperatorText.count(tree.folders, "folder")}" +
                    (if (tree.complete) "" else ", at least; only the first ${d.limits.maxCountedEntries} entries were counted") + ", ${size(tree.bytes)}"
                else -> "a file of ${size(before.sizeBytes)}"
            }
            if (!before.exists) return@orFailing assessment(summary, failing("There is nothing at ${FolderTools.where(folder, path)}."), constraint = c)
            assessment(
                "$summary ($what)",
                prepared(check = {
                    FolderTools.folderChange(d, folder, writing = true)
                        ?: before.changeTo(FolderTools.state(d, folder, path, tree = true), FolderTools.where(folder, path))
                }) { ctx ->
                    BulkRule.take(d, ctx, tree?.files ?: 1, RiskLevel.CONSEQUENTIAL)
                    when (val r = d.sharedFolders.delete(folder.id, path)) {
                        is DocResult.Ok -> ToolOutcome.success("Deleted ${FolderTools.where(folder, path)}.")
                        else -> ToolOutcome.failure(OperatorText.problem(r, "Deleting ${FolderTools.where(folder, path)}"))
                    }
                },
                listOf("What: ${FolderTools.where(folder, path)}, $what", "Shared folders have no trash: this cannot be undone."),
                constraint = c,
            )
        }
    }
}
