package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.aicontrolcenter.data.db.entity.PickUpImportEntity
import com.aicontrolcenter.data.db.entity.PickUpRuleEntity
import com.aicontrolcenter.data.db.entity.RoutineEntity
import com.aicontrolcenter.data.db.entity.RoutineRunEntity
import kotlinx.coroutines.flow.Flow

/**
 * Routines and their runs (schema 6, Appendix A). Every change that reads before it writes is
 * one write transaction, so concurrent changes queue up and none is lost; a routine goes
 * together with its runs.
 */
@Dao
internal interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY created_at ASC, id ASC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY created_at ASC, id ASC")
    suspend fun all(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun get(id: String): RoutineEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM routines WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Upsert
    suspend fun upsert(routine: RoutineEntity)

    /**
     * Reads the routine, lets [transform] compute its new row and writes that, in one write
     * transaction. [transform] returns null to write nothing; whatever it throws is rethrown and
     * nothing is written. Returns the row written, or null.
     */
    @Transaction
    suspend fun update(id: String, transform: (RoutineEntity) -> RoutineEntity?): RoutineEntity? {
        val stored = get(id) ?: return null
        val changed = transform(stored) ?: return null
        upsert(changed)
        return changed
    }

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutineRow(id: String)

    @Query("DELETE FROM routine_runs WHERE routine_id = :routineId")
    suspend fun deleteRunsOf(routineId: String)

    /** The routine and its runs together. */
    @Transaction
    suspend fun delete(id: String) {
        deleteRunsOf(id)
        deleteRoutineRow(id)
    }

    /** Returns -1 when a run with this id is stored already (the stored one is kept). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRunIfAbsent(run: RoutineRunEntity): Long

    /** Stores [run] unless its id is taken; false, storing nothing, when its routine does not exist. Checked in the same transaction. */
    @Transaction
    suspend fun appendRun(run: RoutineRunEntity): Boolean {
        if (!exists(run.routineId)) return false
        insertRunIfAbsent(run)
        return true
    }

    @Query("SELECT * FROM routine_runs WHERE id = :id")
    suspend fun run(id: String): RoutineRunEntity?

    @Upsert
    suspend fun upsertRun(run: RoutineRunEntity)

    /** Like [update], for a run. */
    @Transaction
    suspend fun updateRun(id: String, transform: (RoutineRunEntity) -> RoutineRunEntity?): RoutineRunEntity? {
        val stored = run(id) ?: return null
        val changed = transform(stored) ?: return null
        upsertRun(changed)
        return changed
    }

    @Query(RUNS)
    fun observeRuns(routineId: String, limit: Int): Flow<List<RoutineRunEntity>>

    @Query(RUNS)
    suspend fun runs(routineId: String, limit: Int): List<RoutineRunEntity>

    @Query("DELETE FROM routine_runs WHERE due_at < :before")
    suspend fun pruneRuns(before: Long): Int

    companion object {
        /** A routine's runs newest first, read backwards from the index `(routine_id, due_at, id)`. */
        const val RUNS = "SELECT * FROM routine_runs WHERE routine_id = :routineId ORDER BY due_at DESC, id DESC LIMIT :limit"
    }
}

/**
 * Folder pick-up rules and the files they imported (schema 6, Appendix A). One rule per folder
 * and one record per rule and content are checked in the transaction that writes them; the
 * unique index on `folder_id` and the primary key `(rule_id, content_sha256)` are the backstops.
 */
@Dao
internal interface PickUpDao {
    @Query("SELECT * FROM pickup_rules ORDER BY created_at ASC, id ASC")
    fun observeAll(): Flow<List<PickUpRuleEntity>>

    @Query("SELECT * FROM pickup_rules ORDER BY created_at ASC, id ASC")
    suspend fun all(): List<PickUpRuleEntity>

    @Query("SELECT * FROM pickup_rules WHERE id = :id")
    suspend fun get(id: String): PickUpRuleEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM pickup_rules WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM pickup_rules WHERE folder_id = :folderId AND id <> :id)")
    suspend fun folderTakenByAnother(folderId: String, id: String): Boolean

    @Upsert
    suspend fun upsertRow(rule: PickUpRuleEntity)

    /** Stores [rule] (insert or replace by id); false, storing nothing, when another rule has its folder. */
    @Transaction
    suspend fun upsert(rule: PickUpRuleEntity): Boolean {
        if (folderTakenByAnother(rule.folderId, rule.id)) return false
        upsertRow(rule)
        return true
    }

    /**
     * Reads the rule, lets [transform] compute its new row and writes that, in one write
     * transaction. Whatever [transform] throws is rethrown and nothing is written. Returns the
     * row written, or null when there is no rule with this id.
     *
     * @throws IllegalArgumentException, writing nothing, if the new row takes another rule's folder.
     */
    @Transaction
    suspend fun update(id: String, transform: (PickUpRuleEntity) -> PickUpRuleEntity): PickUpRuleEntity? {
        val stored = get(id) ?: return null
        val changed = transform(stored)
        require(!folderTakenByAnother(changed.folderId, changed.id)) { "That folder already has a pick-up rule." }
        upsertRow(changed)
        return changed
    }

    @Query("DELETE FROM pickup_rules WHERE id = :id")
    suspend fun deleteRuleRow(id: String)

    @Query("DELETE FROM pickup_imports WHERE rule_id = :ruleId")
    suspend fun deleteImportsOf(ruleId: String)

    /** The rule and its import records together; the memories they became stay. */
    @Transaction
    suspend fun delete(id: String) {
        deleteImportsOf(id)
        deleteRuleRow(id)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM pickup_imports WHERE rule_id = :ruleId AND content_sha256 = :contentSha256)")
    suspend fun wasImported(ruleId: String, contentSha256: String): Boolean

    /** Returns -1 when the rule already has this content (nothing is written then). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImportIfAbsent(file: PickUpImportEntity): Long

    /**
     * Records an import: [RecordImport.RECORDED], [RecordImport.DUPLICATE] when the rule already
     * has this content, [RecordImport.NO_RULE] when the rule does not exist. One transaction.
     */
    @Transaction
    suspend fun recordImport(file: PickUpImportEntity): RecordImport = when {
        !exists(file.ruleId) -> RecordImport.NO_RULE
        insertImportIfAbsent(file) == -1L -> RecordImport.DUPLICATE
        else -> RecordImport.RECORDED
    }

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM pickup_imports WHERE imported_at >= :from")
    suspend fun bytesImportedSince(from: Long): Long

    @Query(IMPORTS)
    fun observeImports(ruleId: String, limit: Int): Flow<List<PickUpImportEntity>>

    companion object {
        /** A rule's imports newest first, from the index `(rule_id, imported_at DESC, content_sha256 ASC)`. */
        const val IMPORTS = "SELECT * FROM pickup_imports WHERE rule_id = :ruleId ORDER BY imported_at DESC, content_sha256 ASC LIMIT :limit"
    }
}

/** What [PickUpDao.recordImport] did. */
internal enum class RecordImport { RECORDED, DUPLICATE, NO_RULE }
