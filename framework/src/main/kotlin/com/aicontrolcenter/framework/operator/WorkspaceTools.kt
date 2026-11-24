package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.operator.ConsequentialControls
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.DocumentEntry
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.tools.ActionAssessment
import com.aicontrolcenter.core.tools.RiskLevel
import com.aicontrolcenter.core.tools.Tool
import com.aicontrolcenter.core.tools.ToolContext
import com.aicontrolcenter.core.tools.ToolOutcome
import com.aicontrolcenter.framework.operator.OperatorText.q
import com.aicontrolcenter.framework.operator.OperatorText.size
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant

/** The workspace tools (`workspace.*`), all through the one [Workspace] service. */
internal object WorkspaceTools {
    fun create(d: OperatorToolDependencies): List<Tool> = listOf(
        WorkspaceList(d), WorkspaceRead(d), WorkspaceSearch(d), WorkspaceWrite(d), WorkspaceAppend(d), WorkspaceReplaceText(d),
        WorkspaceMakeFolder(d), WorkspaceMove(d), WorkspaceTrash(d), WorkspaceRestore(d), WorkspaceEmptyTrash(d), WorkspaceExport(d),
    )

    const val WORKSPACE = "the workspace"
}

/** What a workspace or folder entry looked like when a call was assessed. */
internal data class EntryState(
    val exists: Boolean,
    val isFolder: Boolean = false,
    val sizeBytes: Long? = null,
    val modifiedAt: Long? = null,
    /** For a folder: what is inside, when it was looked at. */
    val tree: TreeState? = null,
    /** For a file whose exact content matters (replace, export, install): its SHA-256. */
    val sha256: String? = null,
) {
    /** How [now] differs from this, as a phrase naming [what]; null when it is the same. */
    fun changeTo(now: EntryState, what: String): String? = when {
        exists && !now.exists -> "$what was removed"
        !exists && now.exists -> "$what was created"
        isFolder != now.isFolder -> "$what was replaced"
        tree != null && now.tree != null && tree != now.tree -> "$what now holds ${OperatorText.count(now.tree.files, "file")} (it held ${tree.files})" +
            (if (tree.files == now.tree.files) " and its content changed" else "")
        sha256 != null && sha256 != now.sha256 -> "the content of $what changed"
        sizeBytes != now.sizeBytes || modifiedAt != now.modifiedAt -> "$what changed"
        else -> null
    }

    companion object {
        val ABSENT = EntryState(false)
    }
}

/** A folder's content at one moment: counts and a fingerprint of every entry's path, size and time. */
internal data class TreeState(val files: Int, val folders: Int, val bytes: Long, val fingerprint: String, val complete: Boolean)

/** Workspace helpers shared by the tools. */
internal object WorkspaceStates {
    suspend fun of(workspace: Workspace, path: RelativePath, tree: Boolean = false, hashUpTo: Long? = null): EntryState =
        when (val stat = workspace.stat(path)) {
            is DocResult.Ok -> {
                val e = stat.value
                EntryState(
                    exists = true, isFolder = e.isFolder, sizeBytes = e.sizeBytes, modifiedAt = e.modifiedAt,
                    tree = if (tree && e.isFolder) treeOf(workspace, path, Int.MAX_VALUE) else null,
                    sha256 = if (hashUpTo != null && !e.isFolder && (e.sizeBytes ?: Long.MAX_VALUE) <= hashUpTo) sha256(workspace, path) else null,
                )
            }
            is DocResult.NotFound -> EntryState.ABSENT
            else -> throw ToolCallRejected(OperatorText.problem(stat, q(path.value)))
        }

    /** Walks [path] with [Workspace.list], at most [maxEntries] entries. */
    suspend fun treeOf(workspace: Workspace, path: RelativePath, maxEntries: Int): TreeState {
        val digest = MessageDigest.getInstance("SHA-256")
        var files = 0
        var folders = 0
        var bytes = 0L
        var seen = 0
        val queue = ArrayDeque(listOf(path))
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val entries = (workspace.list(dir) as? DocResult.Ok)?.value ?: continue
            for (e in entries) {
                if (++seen > maxEntries) return TreeState(files, folders, bytes, hex(digest.digest()), complete = false)
                digest.update("${e.path.value}|${e.isFolder}|${e.sizeBytes}|${e.modifiedAt}|${e.mimeType}\n".toByteArray())
                if (e.isFolder) {
                    folders++
                    queue.addLast(e.path)
                } else {
                    files++
                    bytes += e.sizeBytes ?: 0
                }
            }
        }
        return TreeState(files, folders, bytes, hex(digest.digest()), complete = true)
    }

    /** SHA-256 of a workspace file, read in chunks; null if it cannot be read. */
    suspend fun sha256(workspace: Workspace, path: RelativePath): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        while (true) {
            val chunk = (workspace.read(path, offset, CHUNK) as? DocResult.Ok)?.value ?: return null
            digest.update(chunk.bytes)
            offset += chunk.bytes.size
            if (!chunk.truncated || chunk.bytes.isEmpty()) break
        }
        return hex(digest.digest())
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun describe(e: EntryState): String = when {
        !e.exists -> "nothing"
        e.isFolder && e.tree != null -> "a folder with ${OperatorText.count(e.tree.files, "file")}, ${size(e.tree.bytes)}"
        e.isFolder -> "a folder"
        else -> "a file of ${size(e.sizeBytes)}"
    }

    fun entryLine(e: DocumentEntry): String = when {
        e.isFolder -> "${e.name}/"
        e.mimeType == LocalWorkspace.LINK_MIME -> "${e.name}  (a link; not usable)"
        else -> "${e.name}  (${size(e.sizeBytes)})"
    }

    fun entryJson(e: DocumentEntry) = buildJsonObject {
        put("path", e.path.value)
        put("folder", e.isFolder)
        e.sizeBytes?.let { put("size", it) }
        e.mimeType?.let { put("mimeType", it) }
        e.modifiedAt?.let { put("modified", Instant.ofEpochMilli(it).toString()) }
    }

    private const val CHUNK = 1L shl 20
}

/** workspace.list */
internal class WorkspaceList(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.list", "List workspace files",
    "Lists the files and folders directly inside a folder of the workspace, the agents' own storage in this app. Folders end with \"/\".",
    objectSchema { stringProp("path", "Folder inside the workspace, e.g. \"reports/2025\". Omit for the top level.") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val path = Args(arguments).path("path", RelativePath.ROOT)
        return assessment("List ${OperatorText.path(path)}", prepared { list(path) })
    }

    private suspend fun list(path: RelativePath): ToolOutcome {
        val entries = when (val r = d.workspace.list(path)) {
            is DocResult.Ok -> r.value
            else -> return ToolOutcome.failure(OperatorText.problem(r, "Listing ${OperatorText.path(path)}"))
        }
        val shown = entries.take(d.limits.maxListed)
        val header = "${OperatorText.path(path).replaceFirstChar { it.uppercase() }} holds ${OperatorText.count(entries.size, "entry", "entries")}" +
            (if (shown.size < entries.size) "; the first ${shown.size} are listed" else "") + "."
        val body = shown.joinToString("\n") { WorkspaceStates.entryLine(it) }
        return ToolOutcome.success(
            if (entries.isEmpty()) "${OperatorText.path(path).replaceFirstChar { it.uppercase() }} is empty." else "$header\n$body",
            buildJsonArray { shown.forEach { add(WorkspaceStates.entryJson(it)) } },
        )
    }
}

/** workspace.read */
internal class WorkspaceRead(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.read", "Read a workspace file",
    "Reads a text file of the workspace (txt, md, csv, json, html and other UTF-8 text). HTML is returned as readable text. " +
        "Long files are read in parts: pass the \"next_offset\" from the answer as \"offset\" to continue.",
    objectSchema("path") {
        stringProp("path", "The file, e.g. \"notes/plan.md\".")
        intProp("offset", "Byte offset to start at. Default 0.", 0, Long.MAX_VALUE)
        intProp("max_bytes", "How many bytes to read at most. Default 32000.", 1, 200_000)
        boolProp("raw_html", "True to get HTML files as written instead of as text. Default false.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val path = args.entryPath("path")
        val offset = args.long("offset", 0, 0..Long.MAX_VALUE)
        val max = args.int("max_bytes", d.limits.readBytes, 1..d.limits.maxReadBytes)
        val raw = args.bool("raw_html", false)
        return assessment("Read ${q(path.value)}", prepared { read(path, offset, max, raw) })
    }

    private suspend fun read(path: RelativePath, offset: Long, max: Int, raw: Boolean): ToolOutcome {
        val content = when (val r = d.workspace.read(path, offset, max.toLong())) {
            is DocResult.Ok -> r.value
            else -> return ToolOutcome.failure(OperatorText.problem(r, "Reading ${q(path.value)}"))
        }
        return TextReads.answer(path.value, content.bytes, offset, content.totalBytes, content.truncated, raw)
    }
}

/** How file reads are answered, for the workspace and shared folders alike. */
internal object TextReads {
    fun answer(name: String, bytes: ByteArray, offset: Long, total: Long?, truncated: Boolean, raw: Boolean): ToolOutcome {
        val text = OperatorText.decodeText(bytes, cutAtEnd = truncated)
            ?: return ToolOutcome.failure("${q(name)} is not text (${size(total)}); it cannot be read as text.")
        val used = text.toByteArray(Charsets.UTF_8).size
        val next = offset + used
        val html = !raw && OperatorText.isHtml(name)
        val shown = if (html) HtmlText.convert(text).let { page -> listOfNotNull(page.title?.let { "Title: $it" }, page.text).joinToString("\n\n") } else text
        val more = truncated || (total != null && next < total)
        val header = buildString {
            append(q(name))
            if (total != null) append(" (${size(total)})")
            if (offset > 0 || more) append(", bytes $offset to $next")
            if (html) append(", shown as text")
            append(':')
        }
        val footer = if (more) "\n\n[More follows: read again with offset $next.]" else ""
        return ToolOutcome.success(
            "$header\n$shown$footer",
            buildJsonObject {
                put("path", name)
                put("offset", offset)
                put("next_offset", next)
                total?.let { put("total_bytes", it) }
                put("more", more)
            },
        )
    }
}

/** workspace.search */
internal class WorkspaceSearch(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.search", "Search the workspace",
    "Finds files and folders whose name contains the text, then lines of text files that contain it (ignoring case).",
    objectSchema("query") {
        stringProp("query", "The text to look for.")
        stringProp("under", "Only search inside this folder. Omit for the whole workspace.")
        intProp("max_results", "At most this many hits. Default 50.", 1, 200)
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val query = args.text("query", 200)!!
        val under = args.path("under", RelativePath.ROOT)
        val max = args.int("max_results", 50, 1..200)
        return assessment("Search ${OperatorText.path(under)} for ${q(query)}", prepared { search(query, under, max) })
    }

    private suspend fun search(query: String, under: RelativePath, max: Int): ToolOutcome {
        val hits = when (val r = d.workspace.search(query, under, max)) {
            is DocResult.Ok -> r.value
            else -> return ToolOutcome.failure(OperatorText.problem(r, "Searching ${OperatorText.path(under)}"))
        }
        if (hits.isEmpty()) return ToolOutcome.success("Nothing in ${OperatorText.path(under)} matches ${q(query)}.")
        val lines = hits.joinToString("\n") { h -> if (h.line == null) "${h.path.value}  (name)" else "${h.path.value}:${h.line}: ${h.excerpt.orEmpty()}" }
        return ToolOutcome.success(
            "${OperatorText.count(hits.size, "hit")} for ${q(query)}${if (hits.size >= max) " (the limit; there may be more)" else ""}:\n$lines",
            buildJsonArray {
                hits.forEach { h ->
                    addJsonObject {
                        put("path", h.path.value)
                        h.line?.let { put("line", it) }
                        h.excerpt?.let { put("excerpt", it) }
                    }
                }
            },
        )
    }
}

/** workspace.write */
internal class WorkspaceWrite(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.write", "Write a workspace file",
    "Creates a text file in the workspace (missing folders are created), or replaces an existing one when \"overwrite\" is true; " +
        "the replaced file is kept in the workspace trash. Use it for txt, md, csv, json and html documents.",
    objectSchema("path", "content") {
        stringProp("path", "The file to write, e.g. \"reports/summary.md\".")
        stringProp("content", "The complete text of the file.")
        boolProp("overwrite", "True to replace an existing file. Default false: an existing file is left alone and the call fails.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val path = args.entryPath("path")
        val content = args.text("content", d.limits.maxWriteChars, required = false) ?: throw ToolCallRejected("\"content\" is required.")
        val overwrite = args.bool("overwrite", false)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val before = WorkspaceStates.of(d.workspace, path)
        val details = listOf("File: ${q(path.value)} in the workspace", "New content: ${size(bytes.size.toLong())}, ${preview(content)}")
        return when {
            before.isFolder -> assessment("Write ${q(path.value)}", failing("${q(path.value)} is a folder; choose a file name."), details)
            before.exists && !overwrite -> assessment(
                "Write ${q(path.value)}",
                failing("${q(path.value)} already exists; nothing was written. Pass \"overwrite\": true to replace it, or choose another name."),
                details,
            )
            else -> {
                val replaced = if (before.exists) 1 else 0
                val risk = BulkRule.risk(d, context, replaced)
                assessment(
                    if (before.exists) "Replace the workspace file ${q(path.value)} (${size(before.sizeBytes)}) with new text (${size(bytes.size.toLong())}); " +
                        "the current file goes to the workspace trash"
                    else "Create the workspace file ${q(path.value)} (${size(bytes.size.toLong())})",
                    prepared(check = { before.changeTo(WorkspaceStates.of(d.workspace, path), q(path.value)) }) { ctx ->
                        BulkRule.take(d, ctx, replaced, risk)?.let { return@prepared ToolOutcome.failure(it) }
                        when (val r = d.workspace.write(path, bytes, overwrite = before.exists)) {
                            is DocResult.Ok -> ToolOutcome.success(
                                if (before.exists) "Replaced ${q(path.value)} (${size(r.value.sizeBytes)}); the previous version is in the workspace trash (workspace_restore lists it)."
                                else "Created ${q(path.value)} (${size(r.value.sizeBytes)}).",
                            )
                            else -> ToolOutcome.failure(OperatorText.problem(r, "Writing ${q(path.value)}"))
                        }
                    },
                    details + listOfNotNull(
                        if (before.exists) KEPT_IN_TRASH else null,
                        BulkRule.detail(d, context, replaced),
                    ),
                    risk = risk,
                )
            }
        }
    }

    companion object {
        /** The start of a text for a details line, exact. */
        fun preview(text: String, chars: Int = 300): String =
            if (text.length <= chars) "text: ${com.aicontrolcenter.core.operator.PreviewText.quoted(text)}"
            else "starting: ${com.aicontrolcenter.core.operator.PreviewText.quoted(text.take(chars))} (+${text.length - chars} more characters)"

        /** The details line of a call that replaces a workspace file. */
        const val KEPT_IN_TRASH = "The file it replaces is kept in the workspace trash, from where it can be restored."
    }
}

/** workspace.append */
internal class WorkspaceAppend(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.append", "Add to a workspace file",
    "Adds text at the end of a workspace file, creating it if it does not exist. Include a line break at the start or end if you need one.",
    objectSchema("path", "content") {
        stringProp("path", "The file, e.g. \"log.md\".")
        stringProp("content", "The text to add.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val path = args.entryPath("path")
        val content = args.text("content", d.limits.maxWriteChars)!!
        val bytes = content.toByteArray(Charsets.UTF_8)
        val before = WorkspaceStates.of(d.workspace, path)
        val details = listOf("File: ${q(path.value)} in the workspace", "Added: ${size(bytes.size.toLong())}, ${WorkspaceWrite.preview(content)}")
        if (before.isFolder) return assessment("Add text to ${q(path.value)}", failing("${q(path.value)} is a folder."), details)
        return assessment(
            if (before.exists) "Add ${size(bytes.size.toLong())} of text to the end of the workspace file ${q(path.value)}"
            else "Create the workspace file ${q(path.value)} with ${size(bytes.size.toLong())} of text",
            prepared(check = { before.changeTo(WorkspaceStates.of(d.workspace, path), q(path.value)) }) {
                when (val r = d.workspace.append(path, bytes)) {
                    is DocResult.Ok -> ToolOutcome.success("Added ${size(bytes.size.toLong())} to ${q(path.value)}; it now has ${size(r.value.sizeBytes)}.")
                    else -> ToolOutcome.failure(OperatorText.problem(r, "Adding to ${q(path.value)}"))
                }
            },
            details,
        )
    }
}

/** workspace.replace_text */
internal class WorkspaceReplaceText(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.replace_text", "Edit a workspace file",
    "Replaces every occurrence of an exact piece of text in a workspace text file. With \"expected_count\", nothing changes unless " +
        "exactly that many occurrences are found.",
    objectSchema("path", "find", "replace") {
        stringProp("path", "The text file.")
        stringProp("find", "The exact text to find (case-sensitive).")
        stringProp("replace", "The text to put in its place; may be empty.")
        intProp("expected_count", "Optional: how many occurrences you expect.", 1, 100_000)
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val path = args.entryPath("path")
        val find = args.text("find", 10_000)!!
        val replacement = args.string("replace") ?: throw ToolCallRejected("\"replace\" is required.")
        if (replacement.length > d.limits.maxWriteChars) throw ToolCallRejected("\"replace\" is longer than ${d.limits.maxWriteChars} characters.")
        val expected = args.optionalInt("expected_count", 1..100_000)
        val details = listOf("File: ${q(path.value)} in the workspace", "Find: ${q(find)}", "Replace with: ${q(replacement)}")
        val summary = "Replace ${q(find)} with ${q(replacement)} in the workspace file ${q(path.value)}"
        val before = WorkspaceStates.of(d.workspace, path, hashUpTo = d.limits.maxCopyBytes)
        if (!before.exists || before.isFolder) return assessment(summary, failing("There is no file ${q(path.value)}."), details)
        val fileSize = before.sizeBytes ?: 0
        if (fileSize > d.limits.maxEditBytes) {
            return assessment(summary, failing("${q(path.value)} is larger than ${size(d.limits.maxEditBytes)}, too large to edit this way."), details)
        }
        val text = (d.workspace.read(path, 0, fileSize.coerceAtLeast(1)) as? DocResult.Ok)?.value?.bytes
            ?.let { OperatorText.decodeText(it, cutAtEnd = false) }
            ?: return assessment(summary, failing("${q(path.value)} is not a text file that can be edited."), details)
        val count = text.windowedCount(find)
        return when {
            count == 0 -> assessment(summary, failing("${q(find)} does not occur in ${q(path.value)}; nothing was changed."), details)
            expected != null && count != expected ->
                assessment(summary, failing("${q(find)} occurs ${OperatorText.count(count, "time")} in ${q(path.value)}, not $expected; nothing was changed."), details)
            else -> {
                val risk = BulkRule.risk(d, context, 1)
                assessment(
                    "Replace ${OperatorText.count(count, "occurrence")} of ${q(find)} with ${q(replacement)} in the workspace file ${q(path.value)}",
                    prepared(check = { before.changeTo(WorkspaceStates.of(d.workspace, path, hashUpTo = d.limits.maxCopyBytes), q(path.value)) }) { ctx ->
                        BulkRule.take(d, ctx, 1, risk)?.let { return@prepared ToolOutcome.failure(it) }
                        when (val r = d.workspace.replaceText(path, find, replacement, expectedCount = count)) {
                            is DocResult.Ok -> ToolOutcome.success("Replaced ${OperatorText.count(r.value, "occurrence")} in ${q(path.value)}.")
                            else -> ToolOutcome.failure(OperatorText.problem(r, "Editing ${q(path.value)}"))
                        }
                    },
                    details + listOfNotNull(BulkRule.detail(d, context, 1)),
                    risk = risk,
                )
            }
        }
    }

    private fun String.windowedCount(find: String): Int {
        var count = 0
        var at = indexOf(find)
        while (at >= 0) {
            count++
            at = indexOf(find, at + find.length)
        }
        return count
    }
}

/** workspace.make_folder */
internal class WorkspaceMakeFolder(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.make_folder", "Make a workspace folder",
    "Creates a folder in the workspace, with any missing parent folders. An existing folder is left as it is.",
    objectSchema("path") { stringProp("path", "The folder, e.g. \"projects/trip\".") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val path = Args(arguments).entryPath("path")
        val before = WorkspaceStates.of(d.workspace, path)
        val summary = "Create the workspace folder ${q(path.value)}"
        return when {
            before.exists && !before.isFolder -> assessment(summary, failing("A file called ${q(path.value)} already exists."))
            before.exists -> assessment(summary, prepared { ToolOutcome.success("The folder ${q(path.value)} already exists.") })
            else -> assessment(
                summary,
                prepared(check = { before.changeTo(WorkspaceStates.of(d.workspace, path), q(path.value)) }) {
                    when (val r = d.workspace.makeFolder(path)) {
                        is DocResult.Ok -> ToolOutcome.success("Created the folder ${q(path.value)}.")
                        else -> ToolOutcome.failure(OperatorText.problem(r, "Creating ${q(path.value)}"))
                    }
                },
            )
        }
    }
}

/** workspace.move */
internal class WorkspaceMove(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.move", "Move or rename in the workspace",
    "Moves or renames a file or folder inside the workspace. \"to\" is the new full path, not a folder to move into. " +
        "An existing file at \"to\" is replaced only with \"overwrite\": true (it is kept in the workspace trash); a folder is never replaced.",
    objectSchema("from", "to") {
        stringProp("from", "What to move, e.g. \"draft.md\".")
        stringProp("to", "Its new path, e.g. \"archive/2025/draft.md\".")
        boolProp("overwrite", "True to replace an existing file at \"to\". Default false.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val from = args.entryPath("from")
        val to = args.entryPath("to")
        val overwrite = args.bool("overwrite", false)
        if (from == to) throw ToolCallRejected("\"from\" and \"to\" are the same path.")
        if (to.value.startsWith(from.value + "/")) throw ToolCallRejected("A folder cannot be moved into itself.")
        val source = WorkspaceStates.of(d.workspace, from, tree = true)
        val target = WorkspaceStates.of(d.workspace, to)
        val verb = if (from.parent == to.parent) "Rename" else "Move"
        val summary = "$verb ${q(from.value)} to ${q(to.value)} in the workspace"
        val details = listOf("From: ${q(from.value)}, ${WorkspaceStates.describe(source)}", "To: ${q(to.value)}, now ${WorkspaceStates.describe(target)}")
        return when {
            !source.exists -> assessment(summary, failing("There is nothing at ${q(from.value)}."), details)
            target.exists && (!overwrite || target.isFolder || source.isFolder) -> assessment(
                summary,
                failing("${q(to.value)} already exists; nothing was moved." + if (!overwrite && !target.isFolder && !source.isFolder) " Pass \"overwrite\": true to replace that file." else ""),
                details,
            )
            else -> {
                // At most one file is replaced; it is kept in the trash and counted by the bulk rule.
                val replaced = if (target.exists) 1 else 0
                val risk = BulkRule.risk(d, context, replaced)
                assessment(
                    summary + if (replaced == 1) ", replacing the file there (${size(target.sizeBytes)}), which goes to the workspace trash" else "",
                    prepared(check = {
                        source.changeTo(WorkspaceStates.of(d.workspace, from, tree = true), q(from.value))
                            ?: target.changeTo(WorkspaceStates.of(d.workspace, to), q(to.value))
                    }) { ctx ->
                        BulkRule.take(d, ctx, replaced, risk)?.let { return@prepared ToolOutcome.failure(it) }
                        when (val r = d.workspace.move(from, to, overwrite = target.exists)) {
                            is DocResult.Ok -> ToolOutcome.success(
                                "Moved ${q(from.value)} to ${q(to.value)}." + if (replaced == 1) " The file it replaced is in the workspace trash." else "",
                            )
                            else -> ToolOutcome.failure(OperatorText.problem(r, "Moving ${q(from.value)}"))
                        }
                    },
                    details + listOfNotNull(if (replaced == 1) WorkspaceWrite.KEPT_IN_TRASH else null, BulkRule.detail(d, context, replaced)),
                    risk = risk,
                )
            }
        }
    }
}

/** workspace.trash */
internal class WorkspaceTrash(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.trash", "Move to the workspace trash",
    "Moves files or folders of the workspace to its trash, from where workspace_restore can bring them back. " +
        "More than ${ConsequentialControls.BULK_FILE_LIMIT} files replaced, edited or trashed within " +
        "${ConsequentialControls.BULK_WINDOW_MS / 60_000} minutes need the owner's approval.",
    objectSchema("paths") { stringArrayProp("paths", "The files or folders to trash, e.g. [\"old.md\", \"drafts\"].", 100) },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val raw = Args(arguments).strings("paths", 100, RelativePath.MAX_LENGTH) ?: throw ToolCallRejected("\"paths\" is required.")
        if (raw.isEmpty()) throw ToolCallRejected("\"paths\" is empty.")
        val paths = raw.map { Args.entryPathOf(it, "paths") }.distinct()
        paths.firstOrNull { p -> paths.any { other -> other != p && p.value.startsWith(other.value + "/") } }?.let { inner ->
            throw ToolCallRejected("${q(inner.value)} is inside another path of the list; list only the outer one.")
        }
        val states = paths.map { it to WorkspaceStates.of(d.workspace, it, tree = true) }
        val files = states.sumOf { (_, s) -> if (s.isFolder) s.tree?.files ?: 0 else if (s.exists) 1 else 0 }
        val bytes = states.sumOf { (_, s) -> if (s.isFolder) s.tree?.bytes ?: 0 else s.sizeBytes ?: 0 }
        val details = states.map { (p, s) -> "${q(p.value)}: ${WorkspaceStates.describe(s)}" }
        val summary = "Move ${OperatorText.count(paths.size, "item")} (${OperatorText.count(files, "file")}, ${size(bytes)}) to the workspace trash"
        states.firstOrNull { !it.second.exists }?.let { (p, _) -> return assessment(summary, failing("There is nothing at ${q(p.value)}; nothing was trashed."), details) }
        val risk = BulkRule.risk(d, context, files)
        return assessment(
            summary,
            prepared(check = {
                states.firstNotNullOfOrNull { (p, s) -> s.changeTo(WorkspaceStates.of(d.workspace, p, tree = true), q(p.value)) }
            }) { ctx ->
                BulkRule.take(d, ctx, files, risk)?.let { return@prepared ToolOutcome.failure(it) }
                val done = ArrayList<TrashItem>()
                for ((p, _) in states) {
                    when (val r = d.workspace.trash(p)) {
                        is DocResult.Ok -> done += r.value
                        else -> return@prepared ToolOutcome.failure(
                            "Moved ${done.size} of ${paths.size} to the trash${trashed(done)}; then stopped. " + OperatorText.problem(r, "Trashing ${q(p.value)}"),
                        )
                    }
                }
                ToolOutcome.success(
                    "Moved ${OperatorText.count(done.size, "item")} to the trash${trashed(done)}.",
                    buildJsonArray { done.forEach { addJsonObject { put("id", it.id); put("originalPath", it.originalPath) } } },
                )
            },
            details + listOfNotNull(BulkRule.detail(d, context, files)),
            risk = risk,
        )
    }

    private fun trashed(items: List<TrashItem>): String =
        if (items.isEmpty()) "" else " (" + items.joinToString(", ") { "${q(it.originalPath)} as ${it.id}" } + ")"
}

/** workspace.restore */
internal class WorkspaceRestore(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.restore", "Restore from the workspace trash",
    "Puts an item from the workspace trash back where it was. Call without \"id\" to list the trash.",
    objectSchema { stringProp("id", "The trash item's id, from the list.") },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val id = Args(arguments).string("id")?.trim()
        val items = (d.workspace.listTrash() as? DocResult.Ok)?.value.orEmpty()
        if (id.isNullOrEmpty()) {
            // Listing the trash is a read; nothing is restored.
            return assessment("List the workspace trash", prepared { ToolOutcome.success(TrashText.list(items)) }, risk = RiskLevel.MODIFYING)
        }
        val item = items.firstOrNull { it.id == id }
            ?: return assessment("Restore trash item ${q(id)}", failing("There is no item ${q(id)} in the trash. ${TrashText.list(items)}"))
        val original = RelativePath.parse(item.originalPath) as? RelativePath.Check.Valid
            ?: return assessment("Restore trash item ${q(id)}", failing("The trash record of ${q(id)} is damaged."))
        val target = WorkspaceStates.of(d.workspace, original.path)
        val summary = "Restore ${q(item.originalPath)} (${if (item.isFolder) "folder" else "file"}, ${size(item.sizeBytes)}) from the workspace trash"
        if (target.exists) return assessment(summary, failing("Something is at ${q(item.originalPath)} now; move it away first."))
        return assessment(
            summary,
            prepared(check = {
                val still = (d.workspace.listTrash() as? DocResult.Ok)?.value?.firstOrNull { it.id == id }
                when {
                    still != item -> "the trash item ${q(id)} is gone or changed"
                    else -> target.changeTo(WorkspaceStates.of(d.workspace, original.path), q(item.originalPath))
                }
            }) {
                when (val r = d.workspace.restore(id)) {
                    is DocResult.Ok -> ToolOutcome.success("Restored ${q(item.originalPath)}.")
                    else -> ToolOutcome.failure(OperatorText.problem(r, "Restoring ${q(item.originalPath)}"))
                }
            },
        )
    }
}

/** Trash descriptions. */
internal object TrashText {
    fun line(item: TrashItem): String =
        "${item.id}: ${q(item.originalPath)} (${if (item.isFolder) "folder" else "file"}, ${size(item.sizeBytes)}), trashed ${Instant.ofEpochMilli(item.trashedAt)}"

    fun list(items: List<TrashItem>): String =
        if (items.isEmpty()) "The workspace trash is empty." else "The workspace trash holds ${OperatorText.count(items.size, "item")}:\n" + items.joinToString("\n") { line(it) }
}

/** workspace.empty_trash */
internal class WorkspaceEmptyTrash(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.empty_trash", "Empty the workspace trash",
    "Deletes items in the workspace trash for good: the ones named, or all of them. The owner approves every time.",
    objectSchema { stringArrayProp("ids", "Trash item ids to delete. Omit to empty the whole trash.", 500) },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val ids = Args(arguments).strings("ids", 500, 64)?.map { it.trim() }?.toSet()
        val items = when (val r = d.workspace.listTrash()) {
            is DocResult.Ok -> r.value
            else -> throw ToolCallRejected(OperatorText.problem(r, "Reading the trash"))
        }
        val chosen = if (ids == null) items else items.filter { it.id in ids }
        val unknown = ids.orEmpty() - chosen.map { it.id }.toSet()
        val bytes = chosen.sumOf { it.sizeBytes ?: 0 }
        val summary = "Delete permanently ${OperatorText.count(chosen.size, "item")} (${size(bytes)}) from the workspace trash"
        val details = chosen.map { TrashText.line(it) }
        return when {
            unknown.isNotEmpty() -> assessment(summary, failing("The trash has no item ${unknown.joinToString(", ") { q(it) }}; nothing was deleted."), details)
            chosen.isEmpty() -> assessment(summary, failing("The trash is empty; nothing to delete."), details)
            else -> {
                val chosenIds = chosen.map { it.id }.toSet()
                assessment(
                    summary,
                    prepared(check = {
                        val now = (d.workspace.listTrash() as? DocResult.Ok)?.value?.filter { it.id in chosenIds }.orEmpty()
                        if (now.toSet() == chosen.toSet()) null else "the trash items to delete are no longer as they were"
                    }) {
                        when (val r = d.workspace.emptyTrash(chosenIds)) {
                            is DocResult.Ok -> ToolOutcome.success("Deleted ${OperatorText.count(r.value, "item")} from the trash for good.")
                            else -> ToolOutcome.failure(OperatorText.problem(r, "Emptying the trash"))
                        }
                    },
                    details,
                    risk = RiskLevel.CONSEQUENTIAL,
                )
            }
        }
    }
}

/** workspace.export */
internal class WorkspaceExport(private val d: OperatorToolDependencies) : OperatorTool(
    "workspace.export", "Share a workspace file",
    "Opens Android's share screen for a workspace file, so the owner can send or save it with an app they pick. Nothing is sent by itself.",
    objectSchema("path") {
        stringProp("path", "The file to share.")
        stringProp("app", "Optional package name of the app to share to, e.g. \"com.google.android.gm\". Omit to let the owner pick.")
    },
) {
    override suspend fun assess(arguments: JsonObject, context: ToolContext): ActionAssessment {
        val args = Args(arguments)
        val path = args.entryPath("path")
        val app = args.string("app")?.trim()?.ifEmpty { null }?.also(AppTools::checkPackageName)
        if (app != null && d.protectedPackages().isProtected(app)) throw ToolCallRejected("$app is a protected app; files are not shared to it.")
        val before = WorkspaceStates.of(d.workspace, path, hashUpTo = d.limits.maxCopyBytes)
        val goesTo = "${q(path.name)} to ${app ?: "the app you choose"}"
        val summary = "Open the share screen for the workspace file ${q(path.value)} (${size(before.sizeBytes)})" + (app?.let { " with $it" } ?: "")
        val details = listOf("File: ${q(path.value)}, ${WorkspaceStates.describe(before)}", "You complete or cancel the sharing in the app.")
        if (!before.exists || before.isFolder) return assessment(summary, failing("There is no file ${q(path.value)}."), details, dataGoesTo = goesTo)
        if (before.sha256 == null) return assessment(summary, failing("${q(path.value)} is larger than ${size(d.limits.maxCopyBytes)} and is not shared."), details, dataGoesTo = goesTo)
        return assessment(
            summary,
            prepared(check = { before.changeTo(WorkspaceStates.of(d.workspace, path, hashUpTo = d.limits.maxCopyBytes), q(path.value)) }) {
                val file = when (val r = d.workspace.resolve(path)) {
                    is DocResult.Ok -> r.value
                    else -> return@prepared ToolOutcome.failure(OperatorText.problem(r, "Sharing ${q(path.value)}"))
                }
                AppTools.answer(d.apps.shareFile(file, MimeTypes.of(path.name), app), "The share screen for ${q(path.value)} opened; the owner completes it there.")
            },
            details,
            dataGoesTo = goesTo,
        )
    }
}
