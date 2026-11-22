package com.aicontrolcenter.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per model provider. Holds the *alias* of the API key only; the key itself lives in
 * the secret vault and never reaches the database.
 */
@Entity(
    tableName = ProviderEntity.TABLE,
    indices = [Index(value = ["created_at", "id"])],
)
internal data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    @ColumnInfo(name = "base_url") val baseUrl: String?,
    @ColumnInfo(name = "api_key_alias") val apiKeyAlias: String?,
    @ColumnInfo(name = "extra_headers_json") val extraHeadersJson: String,
    val locality: String,
    val enabled: Boolean,
    val status: String,
    @ColumnInfo(name = "status_message") val statusMessage: String?,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val TABLE = "providers"
    }
}
