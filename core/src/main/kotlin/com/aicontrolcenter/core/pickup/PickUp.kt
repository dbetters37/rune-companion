package com.aicontrolcenter.core.pickup

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.MemoryDraft
import com.aicontrolcenter.core.memory.MemoryKind
import com.aicontrolcenter.core.memory.MemoryScope
import com.aicontrolcenter.core.memory.Provenance
import com.aicontrolcenter.core.memory.ProvenanceOrigin
import com.aicontrolcenter.core.operator.DocResult
import com.aicontrolcenter.core.operator.DocumentEntry
import com.aicontrolcenter.core.operator.FileContent
import com.aicontrolcenter.core.operator.FolderTrees
import com.aicontrolcenter.core.operator.OwnerResult
import com.aicontrolcenter.core.operator.RelativePath
import com.aicontrolcenter.core.operator.SharedFolder
import kotlinx.coroutines.flow.Flow

/*
 * Automatic folder pick-up (Phase 6, owner decision Q1, Appendix A, Tier B; WP17 builds the
 * runner): an owner-made rule "import new text files from shared folder F into memory space S",
 * run by the upkeep job's scheduler while nobody watches. Every limit of Appendix A holds: the
 * master switch (`FrameworkSettings.folderPickUpEnabled`) is off after installing; only `text/…`
 * files ([PickUpLimits.accepts]); each file's content is imported once (SHA-256 of its bytes,
 * per rule); at most [PickUpLimits.MAX_BYTES_PER_DAY] a day in all; a file is never sent to a
 * model by importing (the offline hashing embedder indexes it); it lands in the one space the
 * owner chose, as recalled data. Only the owner creates or changes a rule ([OwnerPickUpRules]);
 * no agent can reach [PickUpRuleRepository] or [OwnerPickUpRules] (both architecture tests list
 * them). Reading the folder uses the owner's existing shared-folder grant; nothing here reads
 * another app's private data.
 */

/** The bounds of Appendix A. */
object PickUpLimits {
    /** All imports of one local day together (the owner's zone). */
    const val MAX_BYTES_PER_DAY: Long = 5L * 1024 * 1024

    /** One file; larger ones are skipped and counted. */
    const val MAX_FILE_BYTES: Long = 1L * 1024 * 1024

    /** Rules in all; at most one per folder. */
    const val MAX_RULES: Int = 10

    /** True for a `text/…` type, the only kind picked up. Case and parameters (`; charset=utf-8`) are ignored. */
    fun accepts(mimeType: String?): Boolean =
        mimeType != null && mimeType.substringBefore(';').trim().lowercase().let { it.startsWith("text/") && it.length > "text/".length }
}

/**
 * One rule: new `text/…` files directly inside shared folder [folderId] (sub-folders are not
 * read) go into memory space [spaceId]. [lastRunAt] and [lastResult] (an app-written sentence)
 * say how the last pick-up went.
 */
data class PickUpRule(
    val id: PickUpRuleId,
    val folderId: FolderId,
    val spaceId: SpaceId,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
    val lastResult: String? = null,
    /**
     * The folder's [SharedFolder.readOnlySince] when the rule was made or last switched on (security
     * review of wave 1, F1): a run imports nothing when the folder's value differs, because the
     * folder was writable for agents at some moment since.
     */
    val folderReadOnlySince: Long? = null,
)

/**
 * One file a rule imported: its content's SHA-256 (lower-case hex), so the same content is never
 * imported twice by the rule. [documentName] is the file's name for the history; [memoryId] the
 * memory it became (null if that memory was later deleted).
 *
 * [seenOnly]: a file that was already in the folder when the rule was made or switched on
 * (security review of wave 1, F1). It was not imported (it may have been written while agents
 * could change the folder) and never is by a run; it counts for no daily limit. Only the owner,
 * ticking it on a list, may have it imported (WP17).
 */
data class PickedUpFile(
    val ruleId: PickUpRuleId,
    val contentSha256: String,
    val documentName: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val memoryId: MemoryId? = null,
    val seenOnly: Boolean = false,
) {
    init {
        require(SHA256_HEX.matches(contentSha256)) { "A content hash is 64 lower-case hex digits." }
        require(sizeBytes >= 0) { "A size cannot be negative." }
    }

    private companion object {
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

/**
 * Storage of pick-up rules and the files they imported (schema 6). Owner-capable: nothing an
 * agent reaches may hold it. Rules are listed oldest first (created time, then id); imports
 * newest first (imported time descending, then hash). Flows emit at once and after every change.
 * Implementations: Room (WP1b) and in-memory (`:testkit`); both pass `PickUpRuleRepositoryContract`.
 */
interface PickUpRuleRepository {
    fun observeAll(): Flow<List<PickUpRule>>

    suspend fun getAll(): List<PickUpRule>

    suspend fun get(id: PickUpRuleId): PickUpRule?

    /** Inserts or replaces by id. @throws IllegalArgumentException when another rule has the same folder. */
    suspend fun upsert(rule: PickUpRule)

    /** Atomically applies [transform] to the stored rule and returns the result, or null when it does not exist. */
    suspend fun update(id: PickUpRuleId, transform: (PickUpRule) -> PickUpRule): PickUpRule?

    /** Deletes the rule and its import records (the memories stay). No-op when absent. */
    suspend fun delete(id: PickUpRuleId)

    /** True when [ruleId] already imported content with this hash, or saw it when it was switched on ([PickedUpFile.seenOnly]). */
    suspend fun wasImported(ruleId: PickUpRuleId, contentSha256: String): Boolean

    /**
     * Records an import, atomically: true when it was recorded, false when the rule already has
     * this hash (another pick-up got there first: the caller then drops its copy).
     * @throws IllegalArgumentException when the rule does not exist.
     */
    suspend fun recordImport(file: PickedUpFile): Boolean

    /** Bytes imported by every rule at or after [fromMs] (for the daily cap); files only seen ([PickedUpFile.seenOnly]) do not count. */
    suspend fun bytesImportedSince(fromMs: Long): Long

    /** The newest [limit] imports of one rule. */
    fun observeImports(ruleId: PickUpRuleId, limit: Int): Flow<List<PickedUpFile>>
}

/**
 * The owner's Folder pick-up screen (WP10) and nothing else. Every change is logged.
 * Implementation: WP17; the reference in `:testkit` and `OwnerPickUpRulesContract` define the behaviour.
 */
interface OwnerPickUpRules {
    fun observeAll(): Flow<List<PickUpRule>>

    /**
     * Creates a rule, switched on, from a shared folder the owner added to a memory space.
     * Refused, with a sentence, when the folder or the space does not exist, the folder is not
     * read-only for agents ([PickUpSafety.FOLDER_NOT_READ_ONLY]) or lies inside, or holds, a
     * shared folder agents may change ([PickUpSafety.FOLDER_OVERLAPS_WRITABLE]; or may, where this
     * app cannot tell: [PickUpSafety.FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE],
     * [PickUpSafety.FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE], [PickUpSafety.FOLDER_OTHER_APP_NEAR_WRITABLE]), the folder already
     * has a rule, or [PickUpLimits.MAX_RULES] exist. It runs only while the master switch is on.
     *
     * The files already in the folder are recorded as seen ([PickUpSafety.baseline],
     * [PickedUpFile.seenOnly]) before the rule is on, and the rule keeps the folder's
     * [SharedFolder.readOnlySince] ([PickUpRule.folderReadOnlySince]): only what arrives later,
     * while the folder stays read-only, is picked up. Refused with
     * [PickUpSafety.FOLDER_UNREADABLE] when the folder cannot be read for that.
     */
    suspend fun create(folderId: FolderId, spaceId: SpaceId): OwnerResult<PickUpRule>

    /** Points the rule at another existing space. Refused when the rule or the space is missing. */
    suspend fun changeSpace(id: PickUpRuleId, spaceId: SpaceId): OwnerResult<PickUpRule>

    /**
     * Switches the rule on or off. Switching on is refused, with a sentence, when the rule's folder
     * is gone, is no longer read-only for agents or overlaps a folder agents may change
     * ([PickUpSafety.folderProblem]); otherwise it records the files in the folder now as seen and
     * the folder's [SharedFolder.readOnlySince] anew, as [create] does. Switching off always works
     * while the rule exists.
     */
    suspend fun setEnabled(id: PickUpRuleId, enabled: Boolean): OwnerResult<PickUpRule>

    /** Deletes the rule and its import records; memories it made stay. Returns false when it did not exist. */
    suspend fun delete(id: PickUpRuleId): Boolean
}

/**
 * What folder pick-up may do with the owner's shared folders (security review of wave 1, F11):
 * read a folder's record, list the files directly in it, and read one. It cannot write, rename,
 * delete, add or remove a folder, or take or release Android's access. The runner (WP17) gets this
 * and nothing wider; the app's view over the folder store and the SAF gateway implements it.
 */
interface PickUpFolders {
    /** The folder as stored now (its [SharedFolder.readOnly] switch included), or null when it was removed. */
    suspend fun folder(id: FolderId): SharedFolder?

    /** Every folder the owner shares, as stored now (to find one that overlaps a rule's folder). */
    suspend fun all(): List<SharedFolder>

    /** The files and folders directly inside the folder's root. */
    suspend fun files(folder: FolderId): DocResult<List<DocumentEntry>>

    /** At most [maxBytes] of the file at [path]. */
    suspend fun read(folder: FolderId, path: RelativePath, maxBytes: Long): DocResult<FileContent>

    /** No folders: every pick-up finds its folder gone. */
    object None : PickUpFolders {
        override suspend fun folder(id: FolderId): SharedFolder? = null

        override suspend fun all(): List<SharedFolder> = emptyList()

        override suspend fun files(folder: FolderId): DocResult<List<DocumentEntry>> = DocResult.NotFound("")

        override suspend fun read(folder: FolderId, path: RelativePath, maxBytes: Long): DocResult<FileContent> = DocResult.NotFound(path.value)
    }
}

/**
 * The checks every pick-up makes, and the form of what it stores (security review of wave 1, F1).
 * Pure but for [baseline], which reads through the port it is given: the owner's port
 * ([OwnerPickUpRules]) and the runner (WP17) call them, and the contract suites pin them.
 *
 * Why a picked-up file can only be the owner's: a rule is switched on only for a folder that is
 * read-only for agents and overlaps no folder they may change; what is in the folder at that moment
 * is recorded as seen and never imported ([baseline]); the rule keeps the folder's
 * [SharedFolder.readOnlySince], so a folder that became writable at any moment since imports
 * nothing ([runProblem]); and making a folder writable switches its rules off (the owner's
 * Folders port, `DefaultOwnerFolders.setReadOnly`).
 *
 * The runner (WP17), at every run of every rule, before it lists the folder: reads the rule and
 * every folder afresh and calls [runProblem]; when it gives a sentence, the run imports nothing,
 * stores the sentence as the rule's `lastResult`, and (when the folder is the reason) switches the
 * rule off, so the owner must make the folder read-only again and switch the rule on. It skips every
 * file whose [contentHash] the rule has recorded (imported or seen), and calls [runProblem] again
 * after reading the files and before storing anything, dropping the run when it now gives a
 * sentence. Every file it imports becomes a memory made by [memoryDraft] (origin IMPORT, written
 * through `MemoryActor.Owner` so the owner's chosen space accepts it, and kept as IMPORT by the
 * memory service), never a memory with origin OWNER.
 */
object PickUpSafety {
    /** Why a rule is not made or switched on for a folder agents may change: what the owner should do. */
    const val FOLDER_NOT_READ_ONLY: String =
        "Make this folder read-only for agents first: in Tools › Folders, switch on \"Read-only\" for it. Then try again."

    /** The folder of a rule was removed. */
    const val FOLDER_GONE: String = "That shared folder is no longer there. Add it again in Tools › Folders."

    /** What a run that found the folder writable again stores as the rule's last result. */
    const val RUN_FOLDER_NOT_READ_ONLY: String =
        "Nothing was picked up: agents may change this folder again, so the rule was switched off. " +
            "Make the folder read-only in Tools › Folders, then switch the rule on."

    /** What a run stores when the master switch is off. */
    const val RUN_SWITCHED_OFF: String = "Nothing was picked up: folder pick-up is switched off in Settings."

    /** What a run stores when the rule is off. */
    const val RUN_RULE_OFF: String = "Nothing was picked up: this rule is switched off."

    /** Why a rule is not made or switched on for a folder inside, or holding, a shared folder agents may change. */
    const val FOLDER_OVERLAPS_WRITABLE: String =
        "This folder is inside another shared folder agents may change, or holds one. In Tools › Folders, make that folder " +
            "read-only too, or remove it. Then try again."

    /** What a run stores when the rule's folder now overlaps a folder agents may change. */
    const val RUN_FOLDER_OVERLAPS_WRITABLE: String =
        "Nothing was picked up: this folder is inside another shared folder agents may change, or holds one, so the rule was " +
            "switched off. Make that folder read-only in Tools › Folders, then switch the rule on."

    /**
     * Why a rule is not made or switched on when its folder, or a shared folder agents may change, came through the folder
     * picker's Downloads entry and the two may be the same place ([FolderTrees.overlap] cannot tell,
     * [FolderTrees.fromDownloadsEntryUnplaced]; device gate of wave 2b). It does not say that one holds the other.
     */
    const val FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE: String =
        "This folder may be inside another shared folder agents may change, or hold one: Android does not tell this app " +
            "where a folder shared through the folder picker's Downloads entry is. In Tools › Folders, make that other folder " +
            "read-only too, or remove the folder from the Downloads entry and share it again through the phone's storage " +
            "entry. Then try again."

    /** What a run stores when the rule's folder and a folder agents may change may be the same place through the Downloads entry. */
    const val RUN_FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE: String =
        "Nothing was picked up: this folder may be inside another shared folder agents may change, or hold one, because " +
            "Android does not tell this app where a folder shared through the folder picker's Downloads entry is. So the rule " +
            "was switched off. Make that other folder read-only in Tools › Folders, or remove the folder from the Downloads " +
            "entry and share it again through the phone's storage entry, then switch the rule on."

    /**
     * Why a rule is not made or switched on when this app cannot tell where its folder, or a shared folder agents may
     * change, is for another reason ([FolderTrees.overlap] is null: another profile's folder, the media list).
     */
    const val FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE: String =
        "This folder may be inside another shared folder agents may change, or hold one: one of them comes from another " +
            "profile on this phone or the media list, and this app cannot tell where it is. In Tools › Folders, make that " +
            "other folder read-only too, or remove it. Then try again."

    /** What a run stores when this app cannot tell where the rule's folder, or a folder agents may change, is. */
    const val RUN_FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE: String =
        "Nothing was picked up: this folder may be inside another shared folder agents may change, or hold one, because " +
            "one of them comes from another profile on this phone or the media list and this app cannot tell where it is. So " +
            "the rule was switched off. Make that other folder read-only in Tools › Folders, or remove it, then switch the " +
            "rule on."

    /**
     * Why a rule is not made or switched on when its folder, or a shared folder agents may change, comes from another
     * app (a file manager, a cloud drive) and the two may hold the same files ([FolderTrees.mayShareFiles], B1).
     */
    const val FOLDER_OTHER_APP_NEAR_WRITABLE: String =
        "This folder, or another shared folder agents may change, comes from another app, and this app cannot tell whether " +
            "that app shows the same files as the other folder. In Tools › Folders, make that other folder read-only too, or " +
            "remove it. Then try again."

    /** What a run stores when the rule's folder and a folder agents may change may hold the same files through another app. */
    const val RUN_FOLDER_OTHER_APP_NEAR_WRITABLE: String =
        "Nothing was picked up: this folder, or another shared folder agents may change, comes from another app, and this app " +
            "cannot tell whether that app shows the same files as the other folder, so the rule was switched off. Make that " +
            "other folder read-only in Tools › Folders, then switch the rule on."

    /** Why a rule is not made or switched on when the folder's files cannot be listed and read now. */
    const val FOLDER_UNREADABLE: String = "The folder could not be read just now, so nothing was changed. Try again."

    /** Longest folder name kept in a memory's source line; a longer one is cut. */
    const val MAX_SOURCE_NAME_CHARS: Int = 80

    /**
     * Why [folder] cannot feed a pick-up rule (a sentence for the owner), or null when it can: it
     * exists, is read-only for agents, and no other shared folder in [allFolders] that agents may
     * change is the same place, inside it or around it ([FolderTrees.overlap]; one whose place
     * cannot be told counts as overlapping, with its own sentence), or may show the same files
     * through another app ([FolderTrees.mayShareFiles], B1).
     */
    fun folderProblem(folder: SharedFolder?, allFolders: List<SharedFolder>): String? = when {
        folder == null -> FOLDER_GONE
        !folder.readOnly -> FOLDER_NOT_READ_ONLY
        else -> nearWritable(folder, allFolders)?.refusal
    }

    /**
     * True when a folder of [allFolders] other than [folder], that agents may change, overlaps it or may hold the same
     * files ([FolderTrees.mayShareFiles]: unknown counts).
     */
    fun overlapsWritable(folder: SharedFolder, allFolders: List<SharedFolder>): Boolean = nearWritable(folder, allFolders) != null

    /**
     * The sentence a run stores when [writable], a folder agents may now change, overlaps the rule's [folder] or may hold
     * the same files, or null when they are apart. For the Folders screen's switching off ([folder] itself made writable
     * is [RUN_FOLDER_NOT_READ_ONLY]).
     */
    fun runProblemBeside(folder: SharedFolder, writable: SharedFolder): String? = when {
        folder.id == writable.id -> RUN_FOLDER_NOT_READ_ONLY
        else -> near(folder, writable)?.runResult
    }

    /**
     * How a folder agents may change stands to a pick-up folder, most certain first: the same place, inside or around it;
     * maybe so, because one came through the Downloads entry, or for another reason this app cannot tell; or maybe the
     * same files through another app. Each with what the owner is told.
     */
    private enum class Near(val refusal: String, val runResult: String) {
        SAME_PLACE(FOLDER_OVERLAPS_WRITABLE, RUN_FOLDER_OVERLAPS_WRITABLE),
        DOWNLOADS_ENTRY(FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE, RUN_FOLDER_DOWNLOADS_ENTRY_NEAR_WRITABLE),
        PLACE_UNKNOWN(FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE, RUN_FOLDER_PLACE_UNKNOWN_NEAR_WRITABLE),
        OTHER_APP(FOLDER_OTHER_APP_NEAR_WRITABLE, RUN_FOLDER_OTHER_APP_NEAR_WRITABLE),
    }

    /** The most certain way any folder of [allFolders] that agents may change stands to [folder], or null when all are apart. */
    private fun nearWritable(folder: SharedFolder, allFolders: List<SharedFolder>): Near? =
        allFolders.asSequence()
            .filter { it.id != folder.id && !it.readOnly }
            .mapNotNull { near(folder, it) }
            .minOrNull()

    private fun near(folder: SharedFolder, other: SharedFolder): Near? = when (FolderTrees.overlap(folder.treeUri, other.treeUri)) {
        true -> Near.SAME_PLACE
        // Unknown counts as overlapping; the sentence says why it cannot be told, not that one holds the other.
        null -> if (FolderTrees.fromDownloadsEntryUnplaced(folder.treeUri) || FolderTrees.fromDownloadsEntryUnplaced(other.treeUri)) {
            Near.DOWNLOADS_ENTRY
        } else {
            Near.PLACE_UNKNOWN
        }
        false -> if (FolderTrees.mayShareFiles(folder.treeUri, other.treeUri) != false) Near.OTHER_APP else null
    }

    /** True when a run that gave [problem] switches its rule off: the folder, or one beside it agents may change, is the reason. */
    fun switchesRuleOff(problem: String): Boolean = problem == RUN_FOLDER_NOT_READ_ONLY || Near.entries.any { it.runResult == problem }

    /**
     * Why this run of [rule] imports nothing (the sentence stored as its last result), or null
     * when it may import. Read [rule], [folder] and [allFolders] afresh at every run: the master
     * switch ([masterSwitchOn], `FrameworkSettings.folderPickUpEnabled`) and the rule must be on,
     * the folder must still exist, be the rule's folder and still be read-only for agents, it must
     * have stayed read-only since the rule was switched on (its [SharedFolder.readOnlySince] is the
     * rule's [PickUpRule.folderReadOnlySince]), and no shared folder agents may change may overlap it
     * or hold the same files through another app ([FolderTrees.mayShareFiles]).
     */
    fun runProblem(rule: PickUpRule?, folder: SharedFolder?, allFolders: List<SharedFolder>, masterSwitchOn: Boolean): String? = when {
        !masterSwitchOn -> RUN_SWITCHED_OFF
        rule == null || !rule.enabled -> RUN_RULE_OFF
        folder == null || folder.id != rule.folderId -> FOLDER_GONE
        !folder.readOnly || folder.readOnlySince != rule.folderReadOnlySince -> RUN_FOLDER_NOT_READ_ONLY
        else -> nearWritable(folder, allFolders)?.runResult
    }

    /** The content hash a rule records for a file: SHA-256 of its bytes, lower-case hex. */
    fun contentHash(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * The files in [rule]'s folder now, as records of files seen but not imported
     * ([PickedUpFile.seenOnly], at [at]): every `text/…` file of at most
     * [PickUpLimits.MAX_FILE_BYTES] directly in the folder (others are never picked up anyway).
     * Null when the folder or one of those files cannot be read: the rule is then not made or not
     * switched on. Taken when a rule is made and whenever it is switched on.
     */
    suspend fun baseline(rule: PickUpRule, folders: PickUpFolders, at: Long): List<PickedUpFile>? {
        val entries = (folders.files(rule.folderId) as? DocResult.Ok)?.value ?: return null
        val seen = ArrayList<PickedUpFile>()
        for (entry in entries) {
            if (entry.isFolder || !PickUpLimits.accepts(entry.mimeType)) continue
            if ((entry.sizeBytes ?: 0) > PickUpLimits.MAX_FILE_BYTES) continue
            val content = (folders.read(rule.folderId, entry.path, PickUpLimits.MAX_FILE_BYTES) as? DocResult.Ok)?.value ?: return null
            if (content.truncated) continue
            seen += PickedUpFile(rule.id, contentHash(content.bytes), entry.name, content.bytes.size.toLong(), at, memoryId = null, seenOnly = true)
        }
        return seen.distinctBy { it.contentSha256 }
    }

    /** The source line of a picked-up memory: "Picked up from <folder>". */
    fun source(folder: SharedFolder): String = "Picked up from " + folder.displayName.trim().take(MAX_SOURCE_NAME_CHARS)

    /**
     * Where a picked-up memory came from: an import ([ProvenanceOrigin.IMPORT]) from [folder], no
     * agent, task or model. Never [ProvenanceOrigin.OWNER]: the owner did not write it, and
     * recall frames it as data like any other memory.
     */
    fun provenance(folder: SharedFolder, at: Long): Provenance =
        Provenance(origin = ProvenanceOrigin.IMPORT, source = source(folder), recordedAt = at)

    /** The longest memory title (the memory service's limit); a longer file name is cut. */
    const val MAX_TITLE_CHARS: Int = 200

    /**
     * The memory a picked-up file (or one part of it) becomes: a NOTE in [rule]'s space, titled
     * [title] (the file's name, cut to [MAX_TITLE_CHARS]), holding [text], with [provenance]. The
     * runner checks the size limits ([PickUpLimits]) before; the memory service checks the
     * content's length as for any memory (a file longer than one memory holds is split by the
     * runner, each part titled "<name> (part 2 of 3)").
     */
    fun memoryDraft(rule: PickUpRule, folder: SharedFolder, title: String, text: String, at: Long): MemoryDraft {
        require(folder.id == rule.folderId) { "The folder is not this rule's folder." }
        return MemoryDraft(
            scope = MemoryScope.Shared(rule.spaceId),
            kind = MemoryKind.NOTE,
            title = title.trim().take(MAX_TITLE_CHARS).ifEmpty { "Picked-up file" },
            content = text,
            tags = setOf(TAG),
            provenance = provenance(folder, at),
        )
    }

    /** The tag every picked-up memory carries, so the owner can find them. */
    const val TAG: String = "picked-up"
}
