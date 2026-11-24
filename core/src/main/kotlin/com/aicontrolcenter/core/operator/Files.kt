package com.aicontrolcenter.core.operator

import com.aicontrolcenter.core.FolderId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The agents' workspace directory in this app's private storage (`files/workspace`). Only
 * [Workspace] and [WorkspaceFile.resolve] use it directly. Implemented in `:device` from the
 * app's files directory; tests use a temporary directory.
 */
fun interface WorkspaceRoot {
    /** The workspace directory. Exists when this returns. */
    fun directory(): File
}

/**
 * A workspace path that passed the sandbox: inside the root, outside the trash folder (unless
 * asked for), and with no symbolic link on any existing component. Gateways take this instead of
 * a [File], so none of them re-implements the sandbox. Only [resolve] makes one; resolve again
 * right before use rather than keeping one across a wait.
 */
class WorkspaceFile private constructor(
    /** The path inside the workspace. */
    val path: RelativePath,
    /** The checked file; may not exist yet. */
    val file: File,
) {
    override fun toString(): String = path.value

    override fun equals(other: Any?): Boolean = other is WorkspaceFile && other.file == file

    override fun hashCode(): Int = file.hashCode()

    /** The sandbox check. */
    companion object {
        /**
         * Resolves [path] under [root]. [DocResult.NotAllowed] when the root or any existing
         * component is a symbolic link (checked without following links), or when the path is
         * in [Workspace.TRASH_FOLDER] and [allowTrash] is false (only [Workspace] itself reaches
         * the trash).
         */
        fun resolve(root: File, path: RelativePath, allowTrash: Boolean = false): DocResult<WorkspaceFile> {
            if (!allowTrash && path.segments.firstOrNull() == Workspace.TRASH_FOLDER) {
                return DocResult.NotAllowed("The trash is reached only through the trash and restore operations.")
            }
            val base: Path = root.toPath().toAbsolutePath().normalize()
            if (Files.isSymbolicLink(base)) return DocResult.NotAllowed("The workspace folder is a link, so it is not used.")
            var current = base
            for (segment in path.segments) {
                current = current.resolve(segment)
                if (Files.isSymbolicLink(current)) {
                    return DocResult.NotAllowed("\"${path.value}\" goes through a link, and links are not followed in the workspace.")
                }
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break
            }
            val target = base.resolve(path.value).normalize()
            if (!target.startsWith(base)) return DocResult.NotAllowed("\"${path.value}\" is outside the workspace.")
            return DocResult.Ok(WorkspaceFile(path, target.toFile()))
        }
    }
}

/** One item in the workspace trash. Stored as JSON next to the trashed content (see [Workspace]). */
@Serializable
data class TrashItem(
    /** Name-safe id, the trash folder's entry name. */
    val id: String,
    /** Where it was, as [RelativePath.value]; [Workspace.restore] puts it back there. */
    val originalPath: String,
    val isFolder: Boolean,
    val sizeBytes: Long? = null,
    val trashedAt: Long,
    /**
     * True for an item the workspace found in the trash without a usable record (a trash that an
     * earlier version of the app was stopped in, or a damaged record) and gave a record again:
     * where it was is not known, so [originalPath] is its name at the top of the workspace.
     */
    val recovered: Boolean = false,
)

/** What [Workspace.runCommand] did. */
sealed interface CommandTurn<out T> {
    /**
     * The command ran, and [result] is what it returned. [overLimit]: why the workspace is over
     * its total size limit now, as a sentence, or null when it is within it.
     */
    data class Ran<T>(val result: T, val overLimit: String?) : CommandTurn<T>

    /** The workspace changed after the count the caller gave, so the command was not run. */
    data object Changed : CommandTurn<Nothing>
}

/** A [Workspace.search] hit: a name match (no line) or a line of text. */
data class WorkspaceHit(val path: RelativePath, val line: Int? = null, val excerpt: String? = null)

/**
 * The agents' workspace as one service, used by both the workspace tools and the app's file
 * manager, so they share one sandbox and one trash. Implemented once in `:framework` over
 * [WorkspaceRoot]; every path goes through [WorkspaceFile.resolve]. Size limits return
 * [DocResult.TooLarge]. Reads and lists never touch [TRASH_FOLDER] content except through the
 * trash methods.
 *
 * Trash format: [trash] writes `.trash/<id>.json` holding the item's [TrashItem] and then moves
 * the item to `.trash/<id>/<name>` (the record first, so an item that left its place always has
 * one; a record whose content is not there is not an item); [restore] moves it back to
 * [TrashItem.originalPath] (missing parent
 * folders recreated; [DocResult.AlreadyExists] if something is there now); [emptyTrash] deletes
 * both. A file that [write], [replaceText], [move] or [import] replaces is kept in the trash the
 * same way, so replacing a file can be undone like trashing it. It never leaves its place while
 * that happens: its content is linked (or copied) into the trash, and the new file is then
 * renamed over it, so a reader sees the old file or the new one, never none.
 *
 * Every write into the workspace goes through this service, gateways' included ([receive]), so
 * the size limits, the change signal and the serialisation of changes hold for every caller.
 * The one writer that does not go through it, a shell command, runs inside [runCommand], which
 * holds every other change back while it runs.
 */
interface Workspace {
    /** Increases after every change, from any caller, so screens can refresh. */
    val changes: Flow<Long>

    /** Entries directly inside [path], folders first, then by name. */
    suspend fun list(path: RelativePath): DocResult<List<DocumentEntry>>

    suspend fun stat(path: RelativePath): DocResult<DocumentEntry>

    /** At most [maxBytes] starting at byte [offset]. */
    suspend fun read(path: RelativePath, offset: Long, maxBytes: Long): DocResult<FileContent>

    /**
     * Creates the file (missing parents included), or replaces it when [overwrite], keeping the
     * replaced file in the trash; else [DocResult.AlreadyExists].
     */
    suspend fun write(path: RelativePath, bytes: ByteArray, overwrite: Boolean): DocResult<DocumentEntry>

    /** Appends to the file, creating it if missing. */
    suspend fun append(path: RelativePath, bytes: ByteArray): DocResult<DocumentEntry>

    /**
     * Replaces every occurrence of [find] in a UTF-8 text file. With [expectedCount], changes
     * nothing unless exactly that many occur. Returns how many were replaced. The version from
     * before the edit is kept in the trash, with one exception: when [ownVersionSha256] is given
     * and is the SHA-256 (lower-case hex) of exactly the content being replaced, the caller says
     * that version is one it wrote itself a short while ago, and it is not kept again.
     *
     * [stillOwn] is asked once the change lock is held, right before the file is replaced, and
     * only when the content matches [ownVersionSha256]: false keeps the version after all. A call
     * can wait for the lock as long as a shell command runs ([runCommand]), and what the caller
     * knew when it called may have ended meanwhile (the version was handed to another app, or
     * its minutes passed). It must answer at once and must not call this service.
     */
    suspend fun replaceText(
        path: RelativePath,
        find: String,
        replacement: String,
        expectedCount: Int?,
        ownVersionSha256: String? = null,
        stillOwn: () -> Boolean = { true },
    ): DocResult<Int>

    /** Creates the folder and missing parents; an existing folder is returned as is. */
    suspend fun makeFolder(path: RelativePath): DocResult<DocumentEntry>

    /**
     * Renames or moves; never onto an existing entry unless [overwrite] (a file it replaces is
     * kept in the trash), and never a folder into itself.
     */
    suspend fun move(from: RelativePath, to: RelativePath, overwrite: Boolean): DocResult<DocumentEntry>

    /**
     * Moves a file or folder to the trash. The item's record counts against the size limit like
     * any file: when it does not fit, nothing is trashed ([DocResult.Failed]), unless the trash is
     * empty, since trashing and then emptying the trash is the way to make room.
     */
    suspend fun trash(path: RelativePath): DocResult<TrashItem>

    /** Trash items, newest first. */
    suspend fun listTrash(): DocResult<List<TrashItem>>

    suspend fun restore(id: String): DocResult<DocumentEntry>

    /**
     * Deletes [ids] from the trash for good, or everything when null: every item, and whatever
     * else is under the trash folder ([trashLeftovers]). Returns how many items.
     */
    suspend fun emptyTrash(ids: Set<String>?): DocResult<Int>

    /**
     * What is under the trash folder without being an item [listTrash] lists: the bytes of what
     * a change that was interrupted left behind and that could not be given a record again, or
     * null when there is nothing of the kind. It counts against the size limit and cannot be
     * restored; only [emptyTrash] with null deletes it.
     */
    suspend fun trashLeftovers(): Long? = null

    /** Names, then text lines of UTF-8 files, containing [query] (case-insensitive), under [under]. */
    suspend fun search(query: String, under: RelativePath, maxResults: Int): DocResult<List<WorkspaceHit>>

    /** Copies [source] (a document the owner chose) to [path], at most [maxBytes]; a file it replaces is kept in the trash. */
    suspend fun import(path: RelativePath, source: InputStream, maxBytes: Long, overwrite: Boolean): DocResult<DocumentEntry>

    /**
     * Lets a gateway create the new file [path] itself (a copy from a shared folder or the photo
     * library, a screenshot) under the workspace's rules: [path] must be free
     * ([DocResult.AlreadyExists] otherwise, and [write] is not called); changes are held back
     * while [write] runs; [write] gets the checked file and the most it may write (the smaller of
     * [maxBytes], the per-file limit and the room left); a file it leaves that breaks a size
     * limit is removed ([DocResult.TooLarge], or a full-workspace [DocResult.Failed]); and
     * listeners of [changes] are told. Returns what [write] returned otherwise.
     */
    suspend fun <T> receive(path: RelativePath, maxBytes: Long, write: suspend (target: WorkspaceFile, maxBytes: Long) -> DocResult<T>): DocResult<T>

    /**
     * Tells listeners of [changes] that something outside this service may have changed files
     * (a shell command the owner approved). Returns why the workspace is now over its total size
     * limit, as a sentence, or null when it is within it.
     */
    suspend fun changedOutside(): String?

    /** The checked file for a gateway call that reads (share, install) or runs in it (shell directory). */
    suspend fun resolve(path: RelativePath): DocResult<WorkspaceFile>

    /**
     * The change counter now, the latest value of [changes]. Every change made through this
     * service raises it, whoever makes it (a file tool, a script's host function, a gateway's
     * copy, the owner's file manager), and so does a shell command when it starts and when it
     * ends ([runCommand]). Two equal readings mean that nothing changed the workspace through
     * this app in between.
     */
    fun changeCount(): Long

    /**
     * Runs [command], a shell command the owner approved, as the only thing that changes the
     * workspace while it runs. It runs only if the change counter still is [unchangedSince],
     * the value read before the command was assessed; otherwise nothing runs
     * ([CommandTurn.Changed]). From that check until [command] has ended, however it ends, this
     * service's change lock is held: every other change, from any tool, agent or screen, waits,
     * and so does another command, so one runs at a time. The counter is raised when the command
     * starts and again when it has ended, which also tells the listeners of [changes], and a
     * command that waited for another one therefore does not run. [command] must end in bounded
     * time (a shell command has a time limit), since that is how long the others wait. Calls of
     * this service made from inside [command] do not wait for the lock it holds.
     */
    suspend fun <T> runCommand(unchangedSince: Long, command: suspend () -> T): CommandTurn<T>

    /**
     * True while a shell command runs inside [runCommand], so a change asked for now waits until
     * it has ended. For telling the owner why a screen waits, and for refusing another shell
     * command before anyone is asked for it (it could not run: the end of the running one counts
     * as a change). No guarantee rests on it; [runCommand] decides.
     */
    fun commandRunning(): Boolean = false

    /** Constants. */
    companion object {
        /** The trash's folder name at the workspace root. */
        const val TRASH_FOLDER: String = ".trash"
    }
}

/**
 * A folder in shared storage the owner picked with the system folder picker. Access is the
 * persisted Storage Access Framework grant for [treeUri]; nothing outside the tree is reachable.
 * Which agents may use it is decided by grants constrained to [id].
 */
@Serializable
data class SharedFolder(
    val id: FolderId,
    /** The `content://` tree URI the picker returned, as text. */
    val treeUri: String,
    val displayName: String,
    val addedAt: Long,
    /** The owner's switch: no agent may change anything in it, whatever was granted. */
    val readOnly: Boolean = false,
)

/**
 * Storage of the folders the owner picked. Lists are oldest first (added time, then id); flows
 * emit at once and after every change. Implementations: Room (`:data`) and in-memory
 * (`:testkit`); both pass `SharedFolderRepositoryContract`.
 */
interface SharedFolderRepository {
    fun observeAll(): Flow<List<SharedFolder>>

    suspend fun getAll(): List<SharedFolder>

    suspend fun get(id: FolderId): SharedFolder?

    suspend fun getByTreeUri(treeUri: String): SharedFolder?

    /** Inserts or replaces by id. @throws IllegalArgumentException if another folder has the same [SharedFolder.treeUri]. */
    suspend fun upsert(folder: SharedFolder)

    /** No-op if absent. [OwnerFolders.remove] revokes the folder's grants first. */
    suspend fun delete(id: FolderId)
}

/** One file or folder in a shared folder. */
data class DocumentEntry(
    /** Path from the shared folder's root. */
    val path: RelativePath,
    val isFolder: Boolean,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val modifiedAt: Long? = null,
) {
    val name: String get() = path.name
}

/** Bytes read from a file, possibly only part of it. */
class FileContent(
    val bytes: ByteArray,
    /** The file's full size when known. */
    val totalBytes: Long?,
    /** Fewer bytes were read than the file holds. Set by the gateway, which knows even when [totalBytes] does not. */
    val truncated: Boolean = totalBytes != null && totalBytes > bytes.size,
) {
    override fun equals(other: Any?): Boolean =
        other is FileContent && other.totalBytes == totalBytes && other.truncated == truncated && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * (31 * bytes.contentHashCode() + totalBytes.hashCode()) + truncated.hashCode()
}

/** The outcome of a file operation through a gateway. Expected failures are values, not exceptions. */
sealed interface DocResult<out T> {
    /** Done; [value] is the result. */
    data class Ok<T>(val value: T) : DocResult<T>

    /** Nothing at [path]. */
    data class NotFound(val path: String) : DocResult<Nothing>

    /** Something is already at [path] and overwriting was not asked for (or it is a folder). */
    data class AlreadyExists(val path: String) : DocResult<Nothing>

    /** The file is bigger than the caller's limit. */
    data class TooLarge(val sizeBytes: Long, val limitBytes: Long) : DocResult<Nothing>

    /** Not permitted: the folder is read-only, removed or its access was withdrawn, a link, or the provider does not support the operation. */
    data class NotAllowed(val reason: String) : DocResult<Nothing>

    /** Two or more entries in one folder have the name at [path] (some providers allow it), so which one is meant is unknown; nothing was done. */
    data class Ambiguous(val path: String, val count: Int) : DocResult<Nothing>

    /** Anything else; [reason] is a sentence the model can read. */
    data class Failed(val reason: String) : DocResult<Nothing>
}

/** The outcome of [SharedFolders.takeAccess]. */
sealed interface FolderAccess {
    /** Access is persisted; [displayName] is the folder's name as the provider shows it. */
    data class Granted(val displayName: String) : FolderAccess

    /** The URI is not a tree this app may keep, or Android refused to persist access. */
    data class Refused(val reason: String) : FolderAccess
}

/**
 * Files in the folders the owner picked, through the Storage Access Framework (`:device`), as
 * the folder tools get them: they can read and change files inside a folder, never add, remove
 * or release a folder ([SharedFolders] adds that, for [OwnerFolders] only).
 * Tool-facing operations name a folder by [FolderId] and a [RelativePath] inside it; every call
 * re-reads the folder from [SharedFolderRepository], so a folder removed or switched to
 * read-only meanwhile is honoured ([DocResult.NotAllowed]). The gateway walks the tree by display
 * name and returns [DocResult.Ambiguous] when two children share a name; it never leaves the
 * tree. Size limits are the caller's ([maxBytes]); larger files return [DocResult.TooLarge] for
 * copies and a truncated [FileContent] for reads.
 */
interface SharedFolderFiles {
    /** Whether access still holds (it is lost if the folder is moved or deleted). */
    suspend fun hasAccess(folder: SharedFolder): Boolean

    /** The entries directly inside [path], folders first, then by name. */
    suspend fun list(folder: FolderId, path: RelativePath): DocResult<List<DocumentEntry>>

    suspend fun stat(folder: FolderId, path: RelativePath): DocResult<DocumentEntry>

    /** At most [maxBytes] from the start of the file. */
    suspend fun read(folder: FolderId, path: RelativePath, maxBytes: Long): DocResult<FileContent>

    /** Creates the file (missing parent folders included), or replaces it when [overwrite]; else [DocResult.AlreadyExists]. */
    suspend fun write(folder: FolderId, path: RelativePath, bytes: ByteArray, mimeType: String?, overwrite: Boolean): DocResult<DocumentEntry>

    /** Creates the folder and missing parents; an existing folder is returned as is. */
    suspend fun makeFolder(folder: FolderId, path: RelativePath): DocResult<DocumentEntry>

    /** Renames in place; [newName] is a single name. */
    suspend fun rename(folder: FolderId, path: RelativePath, newName: String): DocResult<DocumentEntry>

    /** Deletes a file, or a folder with everything in it. Never the folder's root. */
    suspend fun delete(folder: FolderId, path: RelativePath): DocResult<Unit>

    /** Copies a file to [target]; never overwrites ([DocResult.AlreadyExists]). Returns the bytes copied. */
    suspend fun copyToWorkspace(folder: FolderId, path: RelativePath, target: WorkspaceFile, maxBytes: Long): DocResult<Long>

    /** Copies the workspace file [source] into the folder, as [write] would. */
    suspend fun copyFromWorkspace(source: WorkspaceFile, folder: FolderId, path: RelativePath, overwrite: Boolean): DocResult<DocumentEntry>
}

/**
 * The whole folder gateway: [SharedFolderFiles] plus keeping and giving up Android's access to a
 * tree, which only the owner's Folders screen does ([OwnerFolders]). Agent-facing code gets
 * [SharedFolderFiles] only.
 */
interface SharedFolders : SharedFolderFiles {
    /** Persists access to a tree the owner just picked. Called by [OwnerFolders] only. */
    suspend fun takeAccess(treeUri: String): FolderAccess

    /** Gives up the persisted access, when the owner removes the folder ([OwnerFolders]). */
    suspend fun releaseAccess(folder: SharedFolder)
}
