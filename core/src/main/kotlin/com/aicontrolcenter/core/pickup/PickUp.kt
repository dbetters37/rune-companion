package com.aicontrolcenter.core.pickup

import com.aicontrolcenter.core.FolderId
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.PickUpRuleId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.operator.OwnerResult
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
     * Refused, with a sentence, when the folder or the space does not exist, the folder already
     * has a rule, or [PickUpLimits.MAX_RULES] exist. It runs only while the master switch is on.
     */
    suspend fun create(folderId: FolderId, spaceId: SpaceId): OwnerResult<PickUpRule>

    /** Points the rule at another existing space. Refused when the rule or the space is missing. */
    suspend fun changeSpace(id: PickUpRuleId, spaceId: SpaceId): OwnerResult<PickUpRule>

    suspend fun setEnabled(id: PickUpRuleId, enabled: Boolean): OwnerResult<PickUpRule>

    /** Deletes the rule and its import records; memories it made stay. Returns false when it did not exist. */
    suspend fun delete(id: PickUpRuleId): Boolean
}
