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
)

/**
 * One file a rule imported: its content's SHA-256 (lower-case hex), so the same content is never
 * imported twice by the rule. [documentName] is the file's name for the history; [memoryId] the
 * memory it became (null if that memory was later deleted).
 */
data class PickedUpFile(
    val ruleId: PickUpRuleId,
    val contentSha256: String,
    val documentName: String,
    val sizeBytes: Long,
    val importedAt: Long,
    val memoryId: MemoryId? = null,
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

    /** True when [ruleId] already imported content with this hash. */
    suspend fun wasImported(ruleId: PickUpRuleId, contentSha256: String): Boolean

    /**
     * Records an import, atomically: true when it was recorded, false when the rule already has
     * this hash (another pick-up got there first: the caller then drops its copy).
     * @throws IllegalArgumentException when the rule does not exist.
     */
    suspend fun recordImport(file: PickedUpFile): Boolean

    /** Bytes imported by every rule at or after [fromMs] (for the daily cap). */
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
     * read-only for agents ([PickUpSafety.FOLDER_NOT_READ_ONLY]), the folder already has a rule,
     * or [PickUpLimits.MAX_RULES] exist. It runs only while the master switch is on.
     */
    suspend fun create(folderId: FolderId, spaceId: SpaceId): OwnerResult<PickUpRule>

    /** Points the rule at another existing space. Refused when the rule or the space is missing. */
    suspend fun changeSpace(id: PickUpRuleId, spaceId: SpaceId): OwnerResult<PickUpRule>

    /**
     * Switches the rule on or off. Switching on is refused, with a sentence, when the rule's folder
     * is gone or is no longer read-only for agents ([PickUpSafety.folderProblem]); switching off
     * always works while the rule exists.
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

    /** The files and folders directly inside the folder's root. */
    suspend fun files(folder: FolderId): DocResult<List<DocumentEntry>>

    /** At most [maxBytes] of the file at [path]. */
    suspend fun read(folder: FolderId, path: RelativePath, maxBytes: Long): DocResult<FileContent>

    /** No folders: every pick-up finds its folder gone. */
    object None : PickUpFolders {
        override suspend fun folder(id: FolderId): SharedFolder? = null

        override suspend fun files(folder: FolderId): DocResult<List<DocumentEntry>> = DocResult.NotFound("")

        override suspend fun read(folder: FolderId, path: RelativePath, maxBytes: Long): DocResult<FileContent> = DocResult.NotFound(path.value)
    }
}

/**
 * The checks every pick-up makes, and the form of what it stores (security review of wave 1, F1).
 * Pure: the owner's port ([OwnerPickUpRules]) and the runner (WP17) call them, and the contract
 * suites pin them.
 *
 * The runner (WP17), at every run of every rule, before it lists the folder: reads the rule and the
 * folder afresh and calls [runProblem]; when it gives a sentence, the run imports nothing, stores
 * the sentence as the rule's `lastResult`, and (when the folder is the reason) switches the rule
 * off, so the owner must make the folder read-only again and switch the rule on. Every file it
 * imports becomes a memory made by [memoryDraft] (origin IMPORT, written through
 * `MemoryActor.Owner` so the owner's chosen space accepts it, and kept as IMPORT by the memory
 * service), never a memory with origin OWNER.
 */
object PickUpSafety {
    /** Why a rule is not made or switched on for a folder agents may change: what the owner should do. */
    const val FOLDER_NOT_READ_ONLY: String =
        "Make this folder read-only for agents first: in Tools > Folders, switch on \"Read-only\" for it. Then try again."

    /** The folder of a rule was removed. */
    const val FOLDER_GONE: String = "That shared folder is no longer there. Add it again in Tools > Folders."

    /** What a run that found the folder writable again stores as the rule's last result. */
    const val RUN_FOLDER_NOT_READ_ONLY: String =
        "Nothing was picked up: agents may change this folder again, so the rule was switched off. " +
            "Make the folder read-only in Tools > Folders, then switch the rule on."

    /** What a run stores when the master switch is off. */
    const val RUN_SWITCHED_OFF: String = "Nothing was picked up: folder pick-up is switched off in Settings."

    /** What a run stores when the rule is off. */
    const val RUN_RULE_OFF: String = "Nothing was picked up: this rule is switched off."

    /** Longest folder name kept in a memory's source line; a longer one is cut. */
    const val MAX_SOURCE_NAME_CHARS: Int = 80

    /** Why [folder] cannot feed a pick-up rule (a sentence for the owner), or null when it can: it exists and is read-only for agents. */
    fun folderProblem(folder: SharedFolder?): String? = when {
        folder == null -> FOLDER_GONE
        !folder.readOnly -> FOLDER_NOT_READ_ONLY
        else -> null
    }

    /**
     * Why this run of [rule] imports nothing (the sentence stored as its last result), or null
     * when it may import. Read [rule] and [folder] afresh at every run: the master switch
     * ([masterSwitchOn], `FrameworkSettings.folderPickUpEnabled`) and the rule must be on, the
     * folder must still exist and still be read-only for agents, and it must be the rule's folder.
     */
    fun runProblem(rule: PickUpRule?, folder: SharedFolder?, masterSwitchOn: Boolean): String? = when {
        !masterSwitchOn -> RUN_SWITCHED_OFF
        rule == null || !rule.enabled -> RUN_RULE_OFF
        folder == null || folder.id != rule.folderId -> FOLDER_GONE
        !folder.readOnly -> RUN_FOLDER_NOT_READ_ONLY
        else -> null
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
