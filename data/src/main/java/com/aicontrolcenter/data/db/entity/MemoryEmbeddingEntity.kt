package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * The vector one embedder computed for one memory.
 *
 * - The primary key `(memory_id, embedder_id)` keeps at most one vector per memory and
 *   embedder; storing again replaces it.
 * - [vector] is [dimensions] IEEE-754 float32 values, little-endian, packed without gaps
 *   (see `EmbeddingCodec`). The count is stored separately so a truncated or foreign blob is
 *   recognised instead of being read as a shorter vector.
 * - The foreign key removes vectors together with their memory; the index on [embedderId]
 *   serves "all vectors of this embedder".
 */
@Entity(
    tableName = MemoryEmbeddingEntity.TABLE,
    primaryKeys = ["memory_id", "embedder_id"],
    foreignKeys = [
        ForeignKey(entity = MemoryEntity::class, parentColumns = ["id"], childColumns = ["memory_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["embedder_id"])],
)
internal class MemoryEmbeddingEntity(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "embedder_id") val embedderId: String,
    val dimensions: Int,
    val vector: ByteArray,
) {
    companion object {
        const val TABLE = "memory_embeddings"
    }
}

/** A stored vector as `embeddings` reads it. */
internal class MemoryEmbeddingRow(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    val dimensions: Int,
    val vector: ByteArray,
)
