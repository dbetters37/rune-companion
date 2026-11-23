package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo

/**
 * A memory as the read queries return it: every column of [MemoryEntity] except `search_text`.
 *
 * The search column repeats title, content and tags. Fetching it with every listing would
 * double the bytes each row costs in Android's cursor window for nothing, so reads select
 * [COLUMNS] instead of `*`.
 */
internal data class MemoryRow(
    val id: String,
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
) {
    companion object {
        /** The selected columns, unqualified; for queries over `memories` without an alias. */
        const val COLUMNS =
            "id, scope_type, scope_agent_id, scope_space_id, scope_task_id, kind, tier, title, content, tags_json, " +
                "importance, confidence, provenance_json, created_at, updated_at, last_accessed_at, access_count, " +
                "expires_at, revision"
    }
}

/** What a search reads when some query words have to be checked outside SQL. */
internal data class MemorySearchCandidate(
    val id: String,
    @ColumnInfo(name = "search_text") val searchText: String,
)
