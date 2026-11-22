package com.aicontrolcenter.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.aicontrolcenter.data.db.entity.ModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ModelDao {
    @Query("SELECT * FROM models ORDER BY created_at ASC, id ASC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models ORDER BY created_at ASC, id ASC")
    suspend fun getAll(): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id")
    suspend fun get(id: String): ModelEntity?

    @Query("SELECT * FROM models WHERE provider_id = :providerId ORDER BY created_at ASC, id ASC")
    suspend fun getByProvider(providerId: String): List<ModelEntity>

    @Upsert
    suspend fun upsert(model: ModelEntity)

    /** Room wraps a multi-row upsert in one transaction: observers see the whole listing or none of it. */
    @Upsert
    suspend fun upsertAll(models: List<ModelEntity>)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM models WHERE provider_id = :providerId")
    suspend fun deleteByProvider(providerId: String)
}
