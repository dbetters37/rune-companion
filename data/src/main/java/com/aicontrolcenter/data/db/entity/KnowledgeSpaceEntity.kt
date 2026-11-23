package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A shared knowledge space (`KnowledgeSpace`). Its memories are rows of `memories` whose scope
 * names it; see [MemoryEntity] for why they have no foreign key to this table. The index serves
 * the listing, oldest first.
 */
@Entity(
    tableName = KnowledgeSpaceEntity.TABLE,
    indices = [Index(value = ["created_at", "id"])],
)
internal data class KnowledgeSpaceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val TABLE = "knowledge_spaces"
    }
}
