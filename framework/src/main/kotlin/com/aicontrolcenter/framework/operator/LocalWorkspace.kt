package com.aicontrolcenter.framework.operator

import com.aicontrolcenter.core.Clock
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.DocumentEntry
import com.aicontrolcenter.core.operator.FileContent
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.TrashItem
import com.aicontrolcenter.core.operator.Workspace
import com.aicontrolcenter.core.operator.WorkspaceFile
import com.aicontrolcenter.core.operator.WorkspaceHit
import com.aicontrolcenter.core.operator.WorkspaceRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/** Size limits of the workspace. Every limit applies to every caller, the tools and the owner's file manager alike. */
data class WorkspaceLimits(
    /** Largest single file written, appended to or imported. */
    val maxFileBytes: Long = 50L * 1024 * 1024,
    /** All files together, the trash included. */
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    /** Largest file [Workspace.replaceText] edits (it is read into memory). */
    val maxTextEditBytes: Long = 4L * 1024 * 1024,
    /** Larger files are searched by name only. */
    val maxSearchFileBytes: Long = 2L * 1024 * 1024,
    /** Files looked at by one search; the rest are skipped. */
    val maxSearchFiles: Int = 5_000,
) {
    init {
        require(maxFileBytes > 0 && maxTotalBytes >= maxFileBytes && maxTextEditBytes > 0 && maxSearchFileBytes > 0 && maxSearchFiles > 0) {
            "limits must be positive"
        }
    }
}

/**
 * The [Workspace] over the app-private directory [WorkspaceRoot] gives, used by both the
 * operator tools and the owner's file manager. Every path goes through
 * [WorkspaceFile.resolve]; recursive work (search, sizes, emptying the trash) never follows
 * symbolic links, and the links an agent's shell command could create are listed as
 * `inode/symlink` entries that nothing reads, writes or moves. Writes land in a staging file
 * first and are renamed into place, so a reader never sees half a file. Changes are serialised.
 *
 * Trash layout, as [Workspace] describes: `.trash/<id>/<name>` plus `.trash/<id>.json` with the
 * [TrashItem]; ids are `<millis>-<random>` in lower-case letters, digits and "-". In-flight
 * writes are staged in `.trash/.staging`, which no trash operation lists.
 *
 * Replacing a file (a write, an edit, a move or an import over it) keeps the replaced version in
 * the trash, so it stays restorable and keeps counting against the size limit until the trash is
 * emptied. The file never leaves its place for that: its content is given a second name in a new
 * trash holder (a hard link; a copy where the file system or Android refuses links, or the file
 * already has other names), the trash record is written, and only then is the new file renamed
 * over the old one in a single step. A reader therefore sees the old file or the new one, never
 * none, and if the app is killed at any point the file is still there. What a kill can leave
 * behind (a staged file, a holder without its record, a kept "version" that is still the live
 * file itself) is cleared before the first change after a start, and by emptying the trash.
 *
 * Gateways that create workspace files themselves (media and folder copies, screenshots) do so
 * through [receive], under the same lock and limits; after a shell command, [changedOutside]
 * tells the screens and reports a workspace over its limit.
 *
 * Residual risk: the sandbox checks paths when they are resolved. A process the owner approved
 * (a shell command) that swaps a folder for a link between that check and the use could
 * redirect one operation; only the final component is opened without following links.
 */
class LocalWorkspace(
    private val root: WorkspaceRoot,
    private val clock: Clock,
    val limits: WorkspaceLimits = WorkspaceLimits(),
    private val io: CoroutineContext = Dispatchers.IO,
) : Workspace {
    private val changeCounter = MutableStateFlow(0L)
    private val mutex = Mutex()
    private val random = SecureRandom()

    /**
     * False once a hard link was refused here: every later version is kept as a copy. Tests set
     * it to false to take the path a phone takes when Android refuses links in app storage.
     */
    @Volatile internal var linksWork = true

    @Volatile private var tidied = false

    /**
     * For tests only: called right before each file-system step of a change that stages or
     * replaces a file, with the step's name, so a test can look at (or copy) the workspace as a
     * kill at that moment would leave it, or make the step fail.
     */
    internal var beforeStep: ((String) -> Unit)? = null

    private fun step(name: String) {
        beforeStep?.invoke(name)
    }

    override val changes: Flow<Long> = changeCounter.asStateFlow()

    private fun base(): Path = root.directory().toPath().toAbsolutePath().normalize()

    private fun trashDir(): Path = base().resolve(Workspace.TRASH_FOLDER)

    private fun changed() {
        changeCounter.value = changeCounter.value + 1
    }

    private fun check(path: RelativePath): DocResult<WorkspaceFile> = WorkspaceFile.resolve(root.directory(), path)

    private suspend fun <T> reading(block: () -> DocResult<T>): DocResult<T> = withContext(io) { guard(block) }

    private suspend fun <T> changing(block: () -> DocResult<T>): DocResult<T> = withContext(io) {
        mutex.withLock {
            tidyOnce()
            guard(block).also { if (it is DocResult.Ok) changed() }
        }
    }

    private inline fun <T> guard(block: () -> DocResult<T>): DocResult<T> = try {
        block()
    } catch (e: IOException) {
        DocResult.Failed("The file system refused (${e::class.simpleName}).")
    } catch (e: java.io.UncheckedIOException) {
        DocResult.Failed("The file system refused (${e.cause?.let { it::class.simpleName }}).")
    } catch (e: java.nio.file.DirectoryIteratorException) {
        DocResult.Failed("The folder could not be read.")
    } catch (e: java.nio.file.InvalidPathException) {
        DocResult.Failed("That name cannot be used on this phone's file system.")
    } catch (e: SecurityException) {
        DocResult.NotAllowed("Android refused access.")
    }

    override suspend fun list(path: RelativePath): DocResult<List<DocumentEntry>> = reading {
        val dir = when (val r = check(path)) {
            is DocResult.Ok -> r.value.file.toPath()
            else -> return@reading r.cast()
        }
        if (!Files.exists(dir, NOFOLLOW)) return@reading DocResult.NotFound(path.value)
        if (!Files.isDirectory(dir, NOFOLLOW)) return@reading DocResult.Failed("${OperatorText.q(path.value)} is a file, not a folder.")
        val entries = Files.newDirectoryStream(dir).use { stream ->
            stream.filter { !(path.isRoot && it.fileName.toString() == Workspace.TRASH_FOLDER) }
                // A name the sandbox would refuse (made by a shell command) cannot be used through it, so it is not listed.
                .mapNotNull { child -> childPath(path, child.fileName.toString())?.let { entryOf(it, child) } }
        }
        DocResult.Ok(entries.sortedWith(ENTRY_ORDER))
    }

    override suspend fun stat(path: RelativePath): DocResult<DocumentEntry> = reading {
        val file = when (val r = check(path)) {
            is DocResult.Ok -> r.value.file.toPath()
            else -> return@reading r.cast()
        }
        if (!Files.exists(file, NOFOLLOW)) DocResult.NotFound(path.value) else DocResult.Ok(entryOf(path, file))
    }

    override suspend fun read(path: RelativePath, offset: Long, maxBytes: Long): DocResult<FileContent> = reading {
        require(offset >= 0 && maxBytes > 0) { "offset must be >= 0 and maxBytes > 0" }
        val file = when (val r = check(path)) {
            is DocResult.Ok -> r.value.file.toPath()
            else -> return@reading r.cast()
        }
        if (!Files.exists(file, NOFOLLOW)) return@reading DocResult.NotFound(path.value)
        if (!Files.isRegularFile(file, NOFOLLOW)) return@reading DocResult.Failed("${OperatorText.q(path.value)} is a folder, not a file.")
        FileChannel.open(file, StandardOpenOption.READ, NOFOLLOW).use { channel ->
            val size = channel.size()
            val start = offset.coerceAtMost(size)
            val count = minOf(maxBytes, size - start, Int.MAX_VALUE.toLong() - 16).toInt()
            val buffer = ByteBuffer.allocate(count)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, start + buffer.position()) < 0) break
            }
            val bytes = if (buffer.position() == count) buffer.array() else buffer.array().copyOf(buffer.position())
            DocResult.Ok(FileContent(bytes, size, truncated = start + bytes.size < size))
        }
    }

    override suspend fun write(path: RelativePath, bytes: ByteArray, overwrite: Boolean): DocResult<DocumentEntry> = changing {
        writeLocked(path, overwrite, bytes.size.toLong()) { target -> Files.write(target, bytes) }
    }

    override suspend fun append(path: RelativePath, bytes: ByteArray): DocResult<DocumentEntry> = changing {
        val file = when (val r = writableTarget(path)) {
            is DocResult.Ok -> r.value
            else -> return@changing r.cast()
        }
        if (!Files.exists(file, NOFOLLOW)) return@changing writeLocked(path, false, bytes.size.toLong()) { Files.write(it, bytes) }
        if (!Files.isRegularFile(file, NOFOLLOW)) return@changing DocResult.AlreadyExists(path.value)
        val newSize = Files.size(file) + bytes.size
        if (newSize > limits.maxFileBytes) return@changing DocResult.TooLarge(newSize, limits.maxFileBytes)
        quotaProblem(bytes.size.toLong())?.let { return@changing it }
        FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND, NOFOLLOW).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        DocResult.Ok(entryOf(path, file))
    }

    override suspend fun replaceText(
        path: RelativePath,
        find: String,
        replacement: String,
        expectedCount: Int?,
        ownVersionSha256: String?,
    ): DocResult<Int> = changing {
        if (find.isEmpty()) return@changing DocResult.Failed("The text to find is empty.")
        val file = when (val r = writableTarget(path)) {
            is DocResult.Ok -> r.value
            else -> return@changing r.cast()
        }
        if (!Files.exists(file, NOFOLLOW)) return@changing DocResult.NotFound(path.value)
        if (!Files.isRegularFile(file, NOFOLLOW)) return@changing DocResult.Failed("${OperatorText.q(path.value)} is a folder, not a file.")
        val size = Files.size(file)
        if (size > limits.maxTextEditBytes) return@changing DocResult.TooLarge(size, limits.maxTextEditBytes)
        val current = Files.readAllBytes(file)
        val text = OperatorText.decodeText(current, cutAtEnd = false)
            ?: return@changing DocResult.Failed("${OperatorText.q(path.value)} is not UTF-8 text.")
        val count = occurrences(text, find)
        if (expectedCount != null && count != expectedCount) {
            return@changing DocResult.Failed("Found ${OperatorText.count(count, "occurrence")}, not $expectedCount, so nothing was changed.")
        }
        if (count == 0) return@changing DocResult.Ok(0)
        val updated = text.replace(find, replacement).toByteArray(Charsets.UTF_8)
        if (updated.size > limits.maxFileBytes) return@changing DocResult.TooLarge(updated.size.toLong(), limits.maxFileBytes)
        // The version being replaced is kept, unless it is exactly the one the caller says it wrote itself a short while ago.
        val keep = ownVersionSha256 == null || sha256(current) != ownVersionSha256
        // A kept version still counts, so then the whole new size is growth.
        quotaProblem(if (keep) updated.size.toLong() else updated.size.toLong() - current.size)?.let { return@changing it }
        stageAndMove(file, replace = true, fill = { Files.write(it, updated) }, keep = if (keep) { { keepInTrash(path, file) } } else null)
        DocResult.Ok(count)
    }

    override suspend fun makeFolder(path: RelativePath): DocResult<DocumentEntry> = changing {
        val dir = when (val r = check(path)) {
            is DocResult.Ok -> r.value.file.toPath()
            else -> return@changing r.cast()
        }
        if (Files.isDirectory(dir, NOFOLLOW)) return@changing DocResult.Ok(entryOf(path, dir))
        if (Files.exists(dir, NOFOLLOW)) return@changing DocResult.AlreadyExists(path.value)
        makeParents(path, includingSelf = true)?.let { return@changing it }
        DocResult.Ok(entryOf(path, dir))
    }

    override suspend fun move(from: RelativePath, to: RelativePath, overwrite: Boolean): DocResult<DocumentEntry> = changing {
        if (from.isRoot || to.isRoot) return@changing DocResult.NotAllowed("The workspace itself cannot be moved or replaced.")
        val source = when (val r = check(from)) {
            is DocResult.Ok -> r.value.file.toPath()
            else -> return@changing r.cast()
        }
        val target = when (val r = check(to)) {
            is DocResult.Ok -> r.value.file.toPath()
            else -> return@changing r.cast()
        }
        if (!Files.exists(source, NOFOLLOW)) return@changing DocResult.NotFound(from.value)
        if (from == to) return@changing DocResult.Failed("${OperatorText.q(from.value)} is already there.")
        if (to.value.startsWith(from.value + "/")) return@changing DocResult.Failed("A folder cannot be moved into itself.")
        val replacing = Files.exists(target, NOFOLLOW)
        if (replacing) {
            if (!overwrite || Files.isDirectory(source, NOFOLLOW) || !Files.isRegularFile(target, NOFOLLOW)) {
                return@changing DocResult.AlreadyExists(to.value)
            }
        }
        makeParents(to, includingSelf = false)?.let { return@changing it }
        // The file moved over is kept in the trash, so the move can be undone; it stays in its place until the moved file takes it.
        val kept = if (replacing) keepInTrash(to, target) else null
        try {
            step("rename the moved file into place")
            moveAtomically(source, target, replace = replacing)
        } catch (e: IOException) {
            kept?.let(::discardKept)
            throw e
        }
        DocResult.Ok(entryOf(to, target))
    }

    override suspend fun trash(path: RelativePath): DocResult<TrashItem> = changing {
        if (path.isRoot) return@changing DocResult.NotAllowed("The workspace itself cannot be trashed.")
        val source = when (val r = check(path)) {
            is DocResult.Ok -> r.value.file.toPath()
            else -> return@changing r.cast()
        }
        if (!Files.exists(source, NOFOLLOW)) return@changing DocResult.NotFound(path.value)
        DocResult.Ok(moveToTrash(path, source))
    }

    override suspend fun listTrash(): DocResult<List<TrashItem>> = reading { DocResult.Ok(trashItems()) }

    override suspend fun restore(id: String): DocResult<DocumentEntry> = changing {
        val item = trashItem(id) ?: return@changing DocResult.NotFound("trash item $id")
        val original = when (val check = RelativePath.parse(item.originalPath)) {
            is RelativePath.Check.Valid -> check.path
            is RelativePath.Check.Invalid -> return@changing DocResult.Failed("The trash record of $id is damaged: ${check.reason}")
        }
        if (original.isRoot) return@changing DocResult.Failed("The trash record of $id is damaged.")
        val target = when (val r = check(original)) {
            is DocResult.Ok -> r.value.file.toPath()
            else -> return@changing r.cast()
        }
        val content = trashDir().resolve(id).resolve(original.name)
        if (!Files.exists(content, NOFOLLOW)) return@changing DocResult.Failed("The trashed content of $id is missing.")
        if (Files.exists(target, NOFOLLOW)) return@changing DocResult.AlreadyExists(original.value)
        makeParents(original, includingSelf = false)?.let { return@changing it }
        moveAtomically(content, target, replace = false)
        Files.deleteIfExists(trashDir().resolve("$id.json"))
        deleteTree(trashDir().resolve(id))
        DocResult.Ok(entryOf(original, target))
    }

    override suspend fun emptyTrash(ids: Set<String>?): DocResult<Int> = changing {
        val trash = trashDir()
        if (!Files.isDirectory(trash, NOFOLLOW)) return@changing DocResult.Ok(0)
        val targets = ids ?: trashItems().map { it.id }.toSet()
        var deleted = 0
        for (id in targets) {
            if (!TRASH_ID.matches(id)) continue
            val record = trash.resolve("$id.json")
            val holder = trash.resolve(id)
            val existed = Files.exists(record, NOFOLLOW) || Files.exists(holder, NOFOLLOW)
            deleteTree(holder)
            Files.deleteIfExists(record)
            if (existed) deleted++
        }
        if (ids == null) {
            // Leftovers of an interrupted trash, restore or replace: holders and records without their other half,
            // and whatever was staged (changes are serialised, so nothing is being staged right now).
            Files.newDirectoryStream(trash).use { stream ->
                stream.filter { TRASH_ID.matches(it.fileName.toString().removeSuffix(".json")) }.forEach { deleteTree(it) }
            }
            clearStaging()
        }
        DocResult.Ok(deleted)
    }

    override suspend fun search(query: String, under: RelativePath, maxResults: Int): DocResult<List<WorkspaceHit>> = reading {
        require(maxResults > 0) { "maxResults must be positive" }
        if (query.isBlank()) return@reading DocResult.Failed("The search text is empty.")
        val start = when (val r = check(under)) {
            is DocResult.Ok -> r.value.file.toPath()
            else -> return@reading r.cast()
        }
        if (!Files.isDirectory(start, NOFOLLOW)) return@reading DocResult.NotFound(under.value)
        val needle = query.lowercase(Locale.ROOT)
        val files = ArrayList<Pair<RelativePath, Path>>()
        val nameHits = ArrayList<WorkspaceHit>()
        val base = base()
        val trash = trashDir()
        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == trash) return FileVisitResult.SKIP_SUBTREE
                    if (dir != start) visit(dir, attrs)
                    return if (files.size >= limits.maxSearchFiles) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    visit(file, attrs)
                    return if (files.size >= limits.maxSearchFiles) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE

                private fun visit(file: Path, attrs: BasicFileAttributes) {
                    val relative = RelativePath.parse(base.relativize(file).toString().replace('\\', '/'))
                    if (relative !is RelativePath.Check.Valid) return
                    if (file.fileName.toString().lowercase(Locale.ROOT).contains(needle)) nameHits += WorkspaceHit(relative.path)
                    if (attrs.isRegularFile) files += relative.path to file
                }
            },
        )
        val hits = ArrayList<WorkspaceHit>()
        hits += nameHits.sortedBy { it.path.value }.take(maxResults)
        for ((relative, file) in files.sortedBy { it.first.value }) {
            if (hits.size >= maxResults) break
            if (Files.size(file) > limits.maxSearchFileBytes) continue
            val text = OperatorText.decodeText(Files.readAllBytes(file), cutAtEnd = false) ?: continue
            text.lineSequence().forEachIndexed { index, line ->
                if (hits.size < maxResults) {
                    val at = line.lowercase(Locale.ROOT).indexOf(needle)
                    if (at >= 0) hits += WorkspaceHit(relative, index + 1, excerpt(line, at, needle.length))
                }
            }
        }
        DocResult.Ok(hits)
    }

    override suspend fun import(path: RelativePath, source: InputStream, maxBytes: Long, overwrite: Boolean): DocResult<DocumentEntry> = changing {
        val limit = minOf(maxBytes, limits.maxFileBytes)
        var tooLarge: Long? = null
        val result = writeLocked(path, overwrite, sizeHint = 0) { staging ->
            Files.newOutputStream(staging).use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) {
                        tooLarge = total
                        throw ImportTooLarge()
                    }
                    out.write(buffer, 0, n)
                }
            }
            // The staged copy already counts as used (it is inside the workspace), and so does a file it replaces (kept in the trash).
            val staged = Files.size(staging)
            val used = treeSize(base()) ?: 0
            if (used > limits.maxTotalBytes) throw ImportOverQuota(fullProblem(used - staged))
        }
        tooLarge?.let { DocResult.TooLarge(it, limit) } ?: result
    }

    override suspend fun <T> receive(
        path: RelativePath,
        maxBytes: Long,
        write: suspend (target: WorkspaceFile, maxBytes: Long) -> DocResult<T>,
    ): DocResult<T> = withContext(io) {
        mutex.withLock {
            var touched = false
            try {
                guard {
                    if (path.isRoot) return@guard DocResult.NotAllowed("A file needs a name.")
                    val file = when (val r = check(path)) {
                        is DocResult.Ok -> r.value
                        else -> return@guard r.cast()
                    }
                    val target = file.file.toPath()
                    if (Files.exists(target, NOFOLLOW)) return@guard DocResult.AlreadyExists(path.value)
                    val used = treeSize(base()) ?: 0
                    val room = limits.maxTotalBytes - used
                    if (room <= 0) return@guard fullProblem(used)
                    makeParents(path, includingSelf = false)?.let { return@guard it }
                    val limit = minOf(maxBytes, limits.maxFileBytes, room)
                    val result = write(file, limit)
                    touched = Files.exists(target, NOFOLLOW)
                    if (touched) {
                        // What the gateway left must keep every limit; it is new, so removing it loses nothing.
                        val size = if (Files.isRegularFile(target, NOFOLLOW)) Files.size(target) else null
                        val problem = when {
                            size == null -> DocResult.Failed("${OperatorText.q(path.value)} was not created as a plain file.")
                            size > limits.maxFileBytes -> DocResult.TooLarge(size, limits.maxFileBytes)
                            used + size > limits.maxTotalBytes -> fullProblem(used)
                            else -> null
                        }
                        if (problem != null) {
                            deleteTree(target)
                            return@guard problem
                        }
                    }
                    if (result is DocResult.TooLarge && limit == room && room < minOf(maxBytes, limits.maxFileBytes)) fullProblem(used) else result
                }
            } finally {
                if (touched) changed()
            }
        }
    }

    override suspend fun changedOutside(): String? = withContext(io) {
        mutex.withLock {
            changed()
            val used = try {
                treeSize(base()) ?: 0
            } catch (e: IOException) {
                0
            }
            if (used <= limits.maxTotalBytes) null else (fullProblem(used) as DocResult.Failed).reason
        }
    }

    override suspend fun resolve(path: RelativePath): DocResult<WorkspaceFile> = reading { check(path) }

    /** Bytes used by every file in the workspace, the trash included. */
    suspend fun usedBytes(): Long = withContext(io) { treeSize(base()) ?: 0 }

    // --- internals; called with the mutex held where they change anything ---

    private class ImportTooLarge : IOException()

    private class ImportOverQuota(val problem: DocResult<Nothing>) : IOException()

    /** The target of a write: checked, not the root, with no folder in the way. */
    private fun writableTarget(path: RelativePath): DocResult<Path> {
        if (path.isRoot) return DocResult.NotAllowed("A file needs a name.")
        return when (val r = check(path)) {
            is DocResult.Ok -> DocResult.Ok(r.value.file.toPath())
            else -> r.cast()
        }
    }

    private fun writeLocked(path: RelativePath, overwrite: Boolean, sizeHint: Long, fill: (Path) -> Unit): DocResult<DocumentEntry> {
        if (sizeHint > limits.maxFileBytes) return DocResult.TooLarge(sizeHint, limits.maxFileBytes)
        val target = when (val r = writableTarget(path)) {
            is DocResult.Ok -> r.value
            else -> return r.cast()
        }
        val exists = Files.exists(target, NOFOLLOW)
        if (exists && (!overwrite || !Files.isRegularFile(target, NOFOLLOW))) return DocResult.AlreadyExists(path.value)
        // A replaced file is kept in the trash, so it still counts: the whole new size is growth.
        quotaProblem(sizeHint)?.let { return it }
        makeParents(path, includingSelf = false)?.let { return it }
        try {
            stageAndMove(target, replace = exists, fill, keep = if (exists) { { keepInTrash(path, target) } } else null)
        } catch (e: ImportTooLarge) {
            return DocResult.TooLarge(limits.maxFileBytes + 1, limits.maxFileBytes)
        } catch (e: ImportOverQuota) {
            return e.problem
        }
        return DocResult.Ok(entryOf(path, target))
    }

    private fun quotaProblem(growthBytes: Long): DocResult<Nothing>? {
        if (growthBytes <= 0) return null
        val used = treeSize(base()) ?: 0
        return if (used + growthBytes <= limits.maxTotalBytes) null else fullProblem(used)
    }

    private fun fullProblem(used: Long): DocResult<Nothing> = DocResult.Failed(
        "The workspace is full: ${OperatorText.size(used)} of ${OperatorText.size(limits.maxTotalBytes)} are used. " +
            "Empty the trash or remove files first.",
    )

    /** Creates the missing parents of [path] (and [path] itself when [includingSelf]); a problem when a file is in the way. */
    private fun makeParents(path: RelativePath, includingSelf: Boolean): DocResult<Nothing>? {
        val last = if (includingSelf) path else path.parent ?: return null
        var current = RelativePath.ROOT
        for (segment in last.segments) {
            current = current.child(segment)
            val dir = when (val r = check(current)) {
                is DocResult.Ok -> r.value.file.toPath()
                else -> return r.cast()
            }
            if (Files.isDirectory(dir, NOFOLLOW)) continue
            if (Files.exists(dir, NOFOLLOW)) return DocResult.Failed("${OperatorText.q(current.value)} is a file, not a folder.")
            try {
                Files.createDirectory(dir)
            } catch (e: FileAlreadyExistsException) {
                if (!Files.isDirectory(dir, NOFOLLOW)) return DocResult.Failed("${OperatorText.q(current.value)} is a file, not a folder.")
            }
        }
        return null
    }

    /**
     * Writes through a staging file in the trash folder, then renames it to [target], over the
     * file there when [replace]. [keep] runs once the new content is complete and keeps the
     * version about to be replaced ([keepInTrash]); if the rename then fails, what it kept is
     * removed again, since the file never left its place.
     */
    private fun stageAndMove(target: Path, replace: Boolean, fill: (Path) -> Unit, keep: (() -> TrashItem)? = null, what: String = "the new file") {
        val staging = trashDir().resolve(STAGING)
        Files.createDirectories(staging)
        val temp = staging.resolve("w-" + java.lang.Long.toUnsignedString(random.nextLong(), 36))
        try {
            step("write $what to a staging file")
            fill(temp)
            val kept = keep?.invoke()
            try {
                step("rename $what into place")
                moveAtomically(temp, target, replace)
            } catch (e: IOException) {
                kept?.let(::discardKept)
                throw e
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Keeps the content of the file [current] (at [path]) as a new trash item while the file
     * stays where it is: the content gets a second name in a new trash holder (a hard link), or
     * is copied there where a link is not possible, and then the record is written. The caller
     * renames the new file over [current] next; if it cannot, it calls [discardKept].
     */
    private fun keepInTrash(path: RelativePath, current: Path): TrashItem {
        val size = Files.size(current)
        Files.createDirectories(trashDir())
        val id = newTrashId()
        val holder = trashDir().resolve(id)
        val item = TrashItem(id = id, originalPath = path.value, isFolder = false, sizeBytes = size, trashedAt = clock.now())
        step("make the trash holder")
        Files.createDirectory(holder)
        try {
            step("link or copy the current version into the holder")
            linkOrCopy(current, holder.resolve(path.name))
            writeRecord(item)
        } catch (e: IOException) {
            discardKept(item)
            throw e
        }
        return item
    }

    /** Removes what [keepInTrash] made, when the file it was made for was not replaced after all. */
    private fun discardKept(item: TrashItem) {
        try {
            Files.deleteIfExists(trashDir().resolve("${item.id}.json"))
            deleteTree(trashDir().resolve(item.id))
        } catch (_: IOException) {
            // It stays in the trash: one copy too many, which emptying the trash removes.
        }
    }

    private fun linkOrCopy(current: Path, kept: Path) {
        if (linksWork && nameCount(current) == 1) {
            try {
                Files.createLink(kept, current)
                return
            } catch (e: UnsupportedOperationException) {
                linksWork = false
            } catch (e: SecurityException) {
                linksWork = false
            } catch (e: IOException) {
                // Android's rules refuse hard links in app storage on many versions; from now on, copy.
                linksWork = false
                Files.deleteIfExists(kept)
            }
        }
        Files.copy(current, kept, StandardCopyOption.COPY_ATTRIBUTES, NOFOLLOW)
    }

    /**
     * How many names (hard links) [file] has, or null where that cannot be told. Only a file with
     * exactly one name is kept by giving it a second one in the trash: that second name is a
     * version of its own once the first is given to the new file. A file with other names (a hard
     * link a shell command made) could still be changed through them, so it is copied instead.
     */
    private fun nameCount(file: Path): Int? = try {
        (Files.getAttribute(file, "unix:nlink", NOFOLLOW) as? Number)?.toInt()
    } catch (e: Exception) {
        null
    }

    private fun writeRecord(item: TrashItem) {
        stageAndMove(
            trashDir().resolve("${item.id}.json"), replace = false,
            fill = { Files.write(it, JSON.encodeToString(TrashItem.serializer(), item).toByteArray()) }, what = "the trash record",
        )
    }

    /** Once after a start, before the first change: clears what a change that was interrupted (the app was killed) left behind. */
    private fun tidyOnce() {
        if (tidied) return
        tidied = true
        try {
            clearStaging()
            val trash = trashDir()
            if (!Files.isDirectory(trash, NOFOLLOW)) return
            // A replace that stopped after the old version was linked into the trash and before the new file took its
            // place: the "kept version" is the live file itself under a second name, not a version of its own. Only a
            // kept file that still has a second name can be one, so the others are passed over without reading their record.
            for (holder in Files.newDirectoryStream(trash).use { it.toList() }) {
                val id = holder.fileName.toString()
                if (!TRASH_ID.matches(id) || !Files.isDirectory(holder, NOFOLLOW)) continue
                val kept = Files.newDirectoryStream(holder).use { it.firstOrNull() } ?: continue
                if (!Files.isRegularFile(kept, NOFOLLOW) || (nameCount(kept) ?: 1) < 2) continue
                val item = trashItem(id) ?: continue
                val original = (RelativePath.parse(item.originalPath) as? RelativePath.Check.Valid)?.path ?: continue
                val live = (check(original) as? DocResult.Ok)?.value?.file?.toPath() ?: continue
                if (kept.fileName.toString() == original.name && Files.isRegularFile(live, NOFOLLOW) && Files.isSameFile(live, kept)) discardKept(item)
            }
        } catch (_: IOException) {
            // Tidying is best effort; emptying the trash clears the same things.
        } catch (_: RuntimeException) {
        }
    }

    /** Deletes what is in the staging folder. Called with the mutex held, when nothing is being staged. */
    private fun clearStaging() {
        val staging = trashDir().resolve(STAGING)
        if (!Files.isDirectory(staging, NOFOLLOW)) return
        Files.newDirectoryStream(staging).use { stream -> stream.toList() }.forEach { deleteTree(it) }
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Moves [source] (at [path]) into a new trash item with its record; nothing is left half done. */
    private fun moveToTrash(path: RelativePath, source: Path): TrashItem {
        val isFolder = Files.isDirectory(source, NOFOLLOW)
        val size = treeSize(source)
        Files.createDirectories(trashDir())
        val id = newTrashId()
        val holder = trashDir().resolve(id)
        Files.createDirectory(holder)
        moveAtomically(source, holder.resolve(path.name), replace = false)
        val item = TrashItem(id = id, originalPath = path.value, isFolder = isFolder, sizeBytes = size, trashedAt = clock.now())
        try {
            writeRecord(item)
        } catch (e: IOException) {
            // Without its record the item could not be restored: put it back.
            moveAtomically(holder.resolve(path.name), source, replace = false)
            Files.deleteIfExists(holder)
            throw e
        }
        return item
    }

    private fun moveAtomically(source: Path, target: Path, replace: Boolean) {
        if (!replace && Files.exists(target, NOFOLLOW)) throw FileAlreadyExistsException(target.fileName.toString())
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) else Files.move(source, target)
        }
    }

    private fun trashItems(): List<TrashItem> {
        val trash = trashDir()
        if (!Files.isDirectory(trash, NOFOLLOW)) return emptyList()
        val records = Files.newDirectoryStream(trash, "*.json").use { stream -> stream.map { it.fileName.toString().removeSuffix(".json") } }
        return records.mapNotNull(::trashItem).sortedWith(compareByDescending<TrashItem> { it.trashedAt }.thenBy { it.id })
    }

    private fun trashItem(id: String): TrashItem? {
        if (!TRASH_ID.matches(id)) return null
        val record = trashDir().resolve("$id.json")
        if (!Files.isRegularFile(record, NOFOLLOW) || !Files.isDirectory(trashDir().resolve(id), NOFOLLOW)) return null
        if (Files.size(record) > MAX_RECORD_BYTES) return null
        val item = runCatching { JSON.decodeFromString(TrashItem.serializer(), String(Files.readAllBytes(record), Charsets.UTF_8)) }.getOrNull() ?: return null
        return item.takeIf { it.id == id }
    }

    private fun newTrashId(): String {
        while (true) {
            val id = "${clock.now()}-${java.lang.Long.toUnsignedString(random.nextLong(), 36).take(8)}"
            if (!Files.exists(trashDir().resolve(id), NOFOLLOW) && !Files.exists(trashDir().resolve("$id.json"), NOFOLLOW)) return id
        }
    }

    private fun entryOf(path: RelativePath, file: Path): DocumentEntry {
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW)
        return when {
            attrs.isSymbolicLink -> DocumentEntry(path, isFolder = false, sizeBytes = null, mimeType = LINK_MIME, modifiedAt = attrs.lastModifiedTime().toMillis())
            attrs.isDirectory -> DocumentEntry(path, isFolder = true, modifiedAt = attrs.lastModifiedTime().toMillis())
            else -> DocumentEntry(path, isFolder = false, sizeBytes = attrs.size(), mimeType = MimeTypes.of(path.name), modifiedAt = attrs.lastModifiedTime().toMillis())
        }
    }

    private fun childPath(parent: RelativePath, name: String): RelativePath? =
        (RelativePath.parse(if (parent.isRoot) name else parent.value + "/" + name) as? RelativePath.Check.Valid)?.path
            ?.takeIf { it.segments.size == parent.segments.size + 1 }

    private fun occurrences(text: String, find: String): Int {
        var count = 0
        var at = text.indexOf(find)
        while (at >= 0) {
            count++
            at = text.indexOf(find, at + find.length)
        }
        return count
    }

    private fun excerpt(line: String, at: Int, length: Int): String {
        val trimmed = line.trim()
        if (trimmed.length <= MAX_EXCERPT) return trimmed
        val from = (at - MAX_EXCERPT / 3).coerceIn(0, maxOf(0, line.length - MAX_EXCERPT))
        val to = minOf(line.length, from + MAX_EXCERPT).coerceAtLeast(at + length)
        return (if (from > 0) "…" else "") + line.substring(from, minOf(line.length, to)).trim() + (if (to < line.length) "…" else "")
    }

    internal companion object {
        val NOFOLLOW = LinkOption.NOFOLLOW_LINKS
        const val STAGING = ".staging"
        const val LINK_MIME = "inode/symlink"
        const val MAX_EXCERPT = 160
        const val MAX_RECORD_BYTES = 64 * 1024
        val TRASH_ID = Regex("^[0-9]{1,19}-[0-9a-z]{1,13}$")
        val JSON = Json { ignoreUnknownKeys = true }
        val ENTRY_ORDER: Comparator<DocumentEntry> =
            compareBy<DocumentEntry>({ !it.isFolder }, { it.name.lowercase(Locale.ROOT) }, { it.name })

        @Suppress("UNCHECKED_CAST")
        fun <T> DocResult<*>.cast(): DocResult<T> = this as DocResult<T>

        /** Total size of the regular files under [path] without following links; null if it does not exist. */
        fun treeSize(path: Path): Long? {
            if (!Files.exists(path, NOFOLLOW)) return null
            var total = 0L
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile) total += attrs.size()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
            return total
        }

        /** Deletes [path] and everything under it; a link is deleted itself, never what it points to. */
        fun deleteTree(path: Path) {
            if (!Files.exists(path, NOFOLLOW)) return
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        if (exc !is NoSuchFileException) Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        try {
                            Files.deleteIfExists(dir)
                        } catch (e: DirectoryNotEmptyException) {
                            throw IOException("could not empty ${dir.fileName}", e)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
    }
}
