package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per remembered item (`MemoryRecord`).
 *
 * - The scope is spread over plain columns so that it can be filtered and indexed:
 *   [scopeType] says which kind of scope, [scopeAgentId] is the owning agent of private and of
 *   task memory, [scopeSpaceId] the knowledge space of shared memory and [scopeTaskId] the
 *   task of task memory. Columns a scope type does not use are null; the mapper writes them so,
 *   which makes "same scope" an exact comparison of all four.
 * - Kind and tier are enum names. Tags, provenance and history (the list of later edits, shares
 *   and promotions) are JSON (see `DataJson`).
 * - [searchText] is title, content and tags, folded in Kotlin (compatibility decomposition,
 *   accents removed, lower case with full Unicode rules) and maintained on every write.
 *   SQLite's `LIKE` folds only ASCII letters, so this column is what makes the text search
 *   ignore case in every alphabet, and accents the way recall's keyword matching does. It is
 *   never read back into a record; reads use [MemoryRow], which leaves it out.
 * - There is deliberately no foreign key to `knowledge_spaces`: a memory may name a space that
 *   is not (or no longer) registered, and deleting a space removes its memories explicitly.
 *
 * Indexes: newest-first listing (`updated_at DESC, id ASC`, the exact listing order), private
 * and task scopes (by owning agent, scope type and task), one agent's private or task memory
 * newest first (by owning agent and scope type, then the listing order, so "an agent's task
 * memory, newest first, at most n" stops after n rows), shared scopes (by space), kind, and
 * expiry.
 */
@Entity(
    tableName = MemoryEntity.TABLE,
    indices = [
        Index(value = ["updated_at", "id"], orders = [Index.Order.DESC, Index.Order.ASC]),
        Index(value = ["scope_agent_id", "scope_type", "scope_task_id"]),
        Index(
            value = ["scope_agent_id", "scope_type", "updated_at", "id"],
            orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.ASC],
        ),
        Index(value = ["scope_space_id"]),
        Index(value = ["kind", "updated_at"]),
        Index(value = ["expires_at"]),
    ],
)
internal data class MemoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "scope_type") val scopeType: String,
    @ColumnInfo(name = "scope_agent_id") val scopeAgentId: String?,
    @ColumnInfo(name = "scope_space_id") val scopeSpaceId: String?,
    @ColumnInfo(name = "scope_task_id") val scopeTaskId: String?,
    val kind: String,
    val tier: String,
    val title: String,
    val content: String,
    @ColumnInfo(name = "tags_json") val tagsJson: String,
    val importance: Double,
    val confidence: Double,
    @ColumnInfo(name = "provenance_json") val provenanceJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long?,
    @ColumnInfo(name = "access_count") val accessCount: Int,
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
    val revision: Int,
    @ColumnInfo(name = "history_json") val historyJson: String,
    @ColumnInfo(name = "search_text") val searchText: String,
) {
    companion object {
        const val TABLE = "memories"
    }
}
