package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per registered model.
 *
 * `provider_id` is indexed but is not a foreign key: the provider port removes a provider's
 * models explicitly through `ModelRepository.deleteByProvider`, and a provider listing may be
 * stored before or after its provider row.
 */
@Entity(
    tableName = ModelEntity.TABLE,
    indices = [
        Index(value = ["created_at", "id"]),
        Index(value = ["provider_id", "created_at", "id"]),
    ],
)
internal data class ModelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "model_name") val modelName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "capabilities_json") val capabilitiesJson: String,
    val source: String,
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long?,
    val enabled: Boolean,
    val status: String,
    val notes: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val TABLE = "models"
    }
}
