package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A typed, weighted, directed edge between two memories (`MemoryLink`).
 *
 * Both ends are foreign keys into `memories` with `ON DELETE CASCADE`, so no link can point at
 * a memory that does not exist, and whatever removes a memory row takes its links in both
 * directions along. The DAO does not rely on that alone: it removes links itself in the same
 * transaction as their memories, like conversation entries. Each end has an index, for
 * "links of this memory" and for the cascade itself.
 */
@Entity(
    tableName = MemoryLinkEntity.TABLE,
    foreignKeys = [
        ForeignKey(entity = MemoryEntity::class, parentColumns = ["id"], childColumns = ["from_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MemoryEntity::class, parentColumns = ["id"], childColumns = ["to_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["from_id"]),
        Index(value = ["to_id"]),
    ],
)
internal data class MemoryLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "from_id") val fromId: String,
    @ColumnInfo(name = "to_id") val toId: String,
    val relation: String,
    val weight: Double,
    @ColumnInfo(name = "provenance_json") val provenanceJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val TABLE = "memory_links"
    }
}
