package com.aicontrolcenter.data.testing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicontrolcenter.core.MemoryId
import com.aicontrolcenter.core.SpaceId
import com.aicontrolcenter.core.memory.KnowledgeSpace
import com.aicontrolcenter.data.db.mapper.EmbeddingCodec
import com.aicontrolcenter.data.db.mapper.toEntity
import com.aicontrolcenter.testkit.contract.MemorySamples

/**
 * What the tables added by schema version 3 hold on a phone that ran release 0.3.0: a knowledge
 * space, memories in every kind of scope, a link and an embedding, written with plain SQL under
 * the column names of that version. Together with [VersionOneData] and [VersionTwoData] (whose
 * tables version 3 kept unchanged) this is a version 3 database with data in every table.
 *
 * As in the other fixtures, the column names are spelled out and must never follow a later rename.
 */
object VersionThreeData {
    private val s = MemorySamples

    val space = KnowledgeSpace(SpaceId("spc_commons"), "Commons", "Shared by every agent", createdAt = 400, updatedAt = 410)

    /** Newest first, the order memories are listed in. */
    val memories = listOf(s.memory("mem_a", s.researcher, 700), s.memory("mem_b", s.commons, 600), s.memory("mem_c", s.task, 500))
    val link = s.link("lnk_ab", "mem_a", "mem_b", at = 710)
    val embeddingOf = MemoryId("mem_a")
    const val EMBEDDER = "hash-v1"
    val embedding = floatArrayOf(0.6f, -0.8f)

    fun insertInto(db: SupportSQLiteDatabase) {
        space.toEntity().let {
            db.insertRow(
                "knowledge_spaces",
                "id" to it.id, "name" to it.name, "description" to it.description, "created_at" to it.createdAt,
                "updated_at" to it.updatedAt,
            )
        }
        memories.map { it.toEntity() }.forEach {
            db.insertRow(
                "memories",
                "id" to it.id, "scope_type" to it.scopeType, "scope_agent_id" to it.scopeAgentId, "scope_space_id" to it.scopeSpaceId,
                "scope_task_id" to it.scopeTaskId, "kind" to it.kind, "tier" to it.tier, "title" to it.title, "content" to it.content,
                "tags_json" to it.tagsJson, "importance" to it.importance, "confidence" to it.confidence,
                "provenance_json" to it.provenanceJson, "created_at" to it.createdAt, "updated_at" to it.updatedAt,
                "last_accessed_at" to it.lastAccessedAt, "access_count" to it.accessCount, "expires_at" to it.expiresAt,
                "revision" to it.revision, "history_json" to it.historyJson, "search_text" to it.searchText,
            )
        }
        link.toEntity().let {
            db.insertRow(
                "memory_links",
                "id" to it.id, "from_id" to it.fromId, "to_id" to it.toId, "relation" to it.relation, "weight" to it.weight,
                "provenance_json" to it.provenanceJson, "created_at" to it.createdAt,
            )
        }
        db.insertRow(
            "memory_embeddings",
            "memory_id" to embeddingOf.value, "embedder_id" to EMBEDDER, "dimensions" to embedding.size,
            "vector" to EmbeddingCodec.encode(embedding),
        )
    }

    private fun SupportSQLiteDatabase.insertRow(table: String, vararg columns: Pair<String, Any?>) {
        val values = ContentValues()
        for ((name, value) in columns) {
            when (value) {
                null -> values.putNull(name)
                is String -> values.put(name, value)
                is Int -> values.put(name, value)
                is Long -> values.put(name, value)
                is Double -> values.put(name, value)
                is ByteArray -> values.put(name, value)
                else -> error("no SQLite type for column $table.$name")
            }
        }
        check(insert(table, SQLiteDatabase.CONFLICT_ABORT, values) != -1L) { "could not insert into $table" }
    }
}
